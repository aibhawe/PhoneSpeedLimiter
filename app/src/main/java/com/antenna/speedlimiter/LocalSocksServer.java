package com.antenna.speedlimiter;

import android.net.Network;
import android.net.VpnService;
import android.util.Log;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local SOCKS5 server used by the tun2socks engine.
 *
 * v1.5 compatibility notes:
 * - Every upstream TCP/UDP socket is explicitly bound to the real underlying
 *   Android Network and protected from the VPN before use.
 * - DNS resolution for SOCKS domain names is also performed on that Network.
 * - SOCKS5 UDP uses two sockets: a loopback socket for the local tun2socks
 *   client and a separate physical-network socket for Internet traffic.
 *
 * The last point is important: a socket bound to 127.0.0.1 must not also be
 * used as the Internet-facing UDP socket. Some Android kernels/OEM stacks are
 * more tolerant of that pattern than others. Keeping the local and upstream
 * sides separate makes behavior deterministic on Samsung, Xiaomi/HyperOS,
 * and other Android implementations.
 */
final class LocalSocksServer implements Closeable {
    private static final String TAG = "LocalSocksServer";
    private static final int SOCKS_VERSION = 5;
    private static final int CMD_CONNECT = 1;
    private static final int CMD_UDP_ASSOCIATE = 3;
    private static final int ATYP_IPV4 = 1;
    private static final int ATYP_DOMAIN = 3;
    private static final int ATYP_IPV6 = 4;

    private final VpnService vpnService;
    private final Network underlyingNetwork;
    private final int port;
    private final BandwidthLimiter uploadLimiter;
    private final BandwidthLimiter downloadLimiter;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Set<Closeable> openResources =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ServerSocket serverSocket;
    private Thread acceptThread;

    LocalSocksServer(VpnService vpnService,
                     Network underlyingNetwork,
                     int port,
                     long uploadKbps,
                     long downloadKbps) {
        this.vpnService = vpnService;
        this.underlyingNetwork = underlyingNetwork;
        this.port = port;
        this.uploadLimiter = new BandwidthLimiter(uploadKbps);
        this.downloadLimiter = new BandwidthLimiter(downloadKbps);
    }

    void updateLimits(long uploadKbps, long downloadKbps) {
        uploadLimiter.setKbps(uploadKbps);
        downloadLimiter.setKbps(downloadKbps);
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        openResources.add(serverSocket);

        acceptThread = new Thread(this::acceptLoop, "speedlimit-socks-accept");
        acceptThread.start();
        Log.i(TAG, "Local SOCKS5 server listening on 127.0.0.1:" + port
                + " via " + underlyingNetwork);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                openResources.add(client);
                executor.execute(() -> handleClient(client));
            } catch (SocketException e) {
                if (running.get()) Log.e(TAG, "SOCKS accept failed", e);
                break;
            } catch (IOException e) {
                if (running.get()) Log.e(TAG, "SOCKS accept I/O failure", e);
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            negotiate(in, out);
            Request request = readRequest(in);
            if (request.command == CMD_CONNECT) {
                handleTcpConnect(client, in, out, request);
            } else if (request.command == CMD_UDP_ASSOCIATE) {
                handleUdpAssociate(client, in, out);
            } else {
                sendReply(out, 7, null, 0);
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "SOCKS client failed", e);
        } finally {
            closeQuietly(client);
            openResources.remove(client);
        }
    }

    private void negotiate(InputStream in, OutputStream out) throws IOException {
        int ver = readU8(in);
        if (ver != SOCKS_VERSION) throw new IOException("Unsupported SOCKS version");
        int methods = readU8(in);
        readFully(in, methods);
        out.write(new byte[]{5, 0});
        out.flush();
    }

    private Request readRequest(InputStream in) throws IOException {
        int ver = readU8(in);
        int cmd = readU8(in);
        readU8(in);
        int atyp = readU8(in);
        if (ver != SOCKS_VERSION) throw new IOException("Bad request version");
        Host host = readHost(in, atyp);
        int port = (readU8(in) << 8) | readU8(in);
        return new Request(cmd, host, port);
    }

    private void bindAndProtect(Socket socket) throws IOException {
        if (!vpnService.protect(socket)) {
            throw new IOException("VpnService.protect(TCP) failed");
        }
        underlyingNetwork.bindSocket(socket);
    }

    private void bindAndProtect(DatagramSocket socket) throws IOException {
        if (!vpnService.protect(socket)) {
            throw new IOException("VpnService.protect(UDP) failed");
        }
        underlyingNetwork.bindSocket(socket);
    }

    private Socket connectRemote(Host host, int port) throws IOException {
        InetAddress[] destinations = host.resolveAll(underlyingNetwork);
        IOException last = null;

        for (InetAddress destination : destinations) {
            Socket candidate = new Socket();
            openResources.add(candidate);
            try {
                bindAndProtect(candidate);
                candidate.setTcpNoDelay(true);
                candidate.connect(new InetSocketAddress(destination, port), 15_000);
                return candidate;
            } catch (IOException e) {
                last = e;
                closeQuietly(candidate);
                openResources.remove(candidate);
            }
        }

        throw new IOException("Unable to connect to " + host.display() + ":" + port, last);
    }

    private void handleTcpConnect(Socket client,
                                  InputStream clientIn,
                                  OutputStream clientOut,
                                  Request request) throws IOException {
        Socket remote = connectRemote(request.host, request.port);
        try {
            InetSocketAddress local = (InetSocketAddress) remote.getLocalSocketAddress();
            sendReply(clientOut, 0, local.getAddress(), local.getPort());

            InputStream remoteIn = remote.getInputStream();
            OutputStream remoteOut = remote.getOutputStream();

            Future<?> up = executor.submit(() -> pump(clientIn, remoteOut, uploadLimiter));
            Future<?> down = executor.submit(() -> pump(remoteIn, clientOut, downloadLimiter));

            while (running.get() && !up.isDone() && !down.isDone()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            closeQuietly(remote);
            closeQuietly(client);
            up.cancel(true);
            down.cancel(true);
        } finally {
            closeQuietly(remote);
            openResources.remove(remote);
        }
    }

    private void pump(InputStream in, OutputStream out, BandwidthLimiter limiter) {
        byte[] buffer = new byte[32 * 1024];
        try {
            while (running.get()) {
                int n = in.read(buffer);
                if (n < 0) break;
                limiter.acquire(n);
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Standard SOCKS5 UDP ASSOCIATE relay.
     *
     * clientUdp is loopback-only and talks to hev-socks5-tunnel.
     * upstreamUdp is bound to the selected physical Network and talks to the
     * Internet. They are intentionally different sockets.
     */
    private void handleUdpAssociate(Socket control,
                                    InputStream controlIn,
                                    OutputStream controlOut) throws IOException {
        DatagramSocket clientUdp = new DatagramSocket(null);
        DatagramSocket upstreamUdp = new DatagramSocket(null);
        openResources.add(clientUdp);
        openResources.add(upstreamUdp);

        Future<?> uplink = null;
        Future<?> downlink = null;
        try {
            clientUdp.setReuseAddress(true);
            clientUdp.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));

            // Bind the Internet-facing UDP socket to the real network before it
            // is locally bound or used.
            bindAndProtect(upstreamUdp);
            upstreamUdp.bind(new InetSocketAddress(0));

            sendReply(controlOut, 0,
                    InetAddress.getByName("127.0.0.1"), clientUdp.getLocalPort());

            AtomicReference<InetSocketAddress> clientEndpoint = new AtomicReference<>();
            uplink = executor.submit(() ->
                    udpClientToNetwork(clientUdp, upstreamUdp, clientEndpoint));
            downlink = executor.submit(() ->
                    udpNetworkToClient(upstreamUdp, clientUdp, clientEndpoint));

            // RFC 1928: the association remains alive while the TCP control
            // connection remains open.
            try {
                while (running.get() && controlIn.read() >= 0) {
                    // Ignore unexpected data on the control channel.
                }
            } catch (IOException ignored) {
            }
        } finally {
            clientUdp.close();
            upstreamUdp.close();
            if (uplink != null) uplink.cancel(true);
            if (downlink != null) downlink.cancel(true);
            openResources.remove(clientUdp);
            openResources.remove(upstreamUdp);
        }
    }

    private void udpClientToNetwork(DatagramSocket clientUdp,
                                    DatagramSocket upstreamUdp,
                                    AtomicReference<InetSocketAddress> clientEndpoint) {
        byte[] buffer = new byte[65_535];
        try {
            clientUdp.setSoTimeout(1000);
            while (running.get() && !clientUdp.isClosed() && !upstreamUdp.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    clientUdp.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                clientEndpoint.set(new InetSocketAddress(packet.getAddress(), packet.getPort()));
                UdpFrame frame = parseUdpFrame(
                        packet.getData(), packet.getOffset(), packet.getLength());
                if (frame == null || frame.fragment != 0) continue;

                InetAddress destination = frame.host.resolveForUdp(underlyingNetwork);
                uploadLimiter.acquire(frame.payloadLength);
                DatagramPacket outbound = new DatagramPacket(
                        packet.getData(), frame.payloadOffset, frame.payloadLength,
                        destination, frame.port);
                upstreamUdp.send(outbound);
            }
        } catch (Exception e) {
            if (running.get() && !clientUdp.isClosed()) Log.e(TAG, "UDP uplink failed", e);
        }
    }

    private void udpNetworkToClient(DatagramSocket upstreamUdp,
                                    DatagramSocket clientUdp,
                                    AtomicReference<InetSocketAddress> clientEndpoint) {
        byte[] buffer = new byte[65_535];
        try {
            upstreamUdp.setSoTimeout(1000);
            while (running.get() && !upstreamUdp.isClosed() && !clientUdp.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    upstreamUdp.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                InetSocketAddress client = clientEndpoint.get();
                if (client == null) continue;

                byte[] wrapped = buildUdpFrame(
                        packet.getAddress(), packet.getPort(),
                        packet.getData(), packet.getOffset(), packet.getLength());
                downloadLimiter.acquire(packet.getLength());

                DatagramPacket inbound = new DatagramPacket(
                        wrapped, wrapped.length, client.getAddress(), client.getPort());
                clientUdp.send(inbound);
            }
        } catch (Exception e) {
            if (running.get() && !upstreamUdp.isClosed()) Log.e(TAG, "UDP downlink failed", e);
        }
    }

    private UdpFrame parseUdpFrame(byte[] data, int offset, int length) throws IOException {
        if (length < 7) return null;
        int p = offset;
        if (data[p++] != 0 || data[p++] != 0) return null;
        int frag = data[p++] & 0xff;
        int atyp = data[p++] & 0xff;
        ParsedHost parsed = parseHost(data, p, offset + length, atyp);
        p = parsed.next;
        if (p + 2 > offset + length) return null;
        int port = ((data[p++] & 0xff) << 8) | (data[p++] & 0xff);
        return new UdpFrame(frag, parsed.host, port, p, offset + length - p);
    }


    private byte[] buildUdpFrame(InetAddress source,
                                 int port,
                                 byte[] payload,
                                 int offset,
                                 int length) {
        byte[] addr = source.getAddress();
        int headerLen = 4 + addr.length + 2;
        byte[] out = new byte[headerLen + length];
        int p = 0;
        out[p++] = 0;
        out[p++] = 0;
        out[p++] = 0;
        out[p++] = (byte) (source instanceof Inet6Address ? ATYP_IPV6 : ATYP_IPV4);
        System.arraycopy(addr, 0, out, p, addr.length);
        p += addr.length;
        out[p++] = (byte) ((port >>> 8) & 0xff);
        out[p++] = (byte) (port & 0xff);
        System.arraycopy(payload, offset, out, p, length);
        return out;
    }

    private Host readHost(InputStream in, int atyp) throws IOException {
        switch (atyp) {
            case ATYP_IPV4:
                return Host.ofAddress(InetAddress.getByAddress(readFully(in, 4)));
            case ATYP_IPV6:
                return Host.ofAddress(InetAddress.getByAddress(readFully(in, 16)));
            case ATYP_DOMAIN:
                int len = readU8(in);
                return Host.ofName(new String(readFully(in, len), StandardCharsets.UTF_8));
            default:
                throw new IOException("Unsupported ATYP");
        }
    }

    private ParsedHost parseHost(byte[] data, int p, int end, int atyp) throws IOException {
        if (atyp == ATYP_IPV4) {
            if (p + 4 > end) throw new EOFException();
            byte[] b = new byte[4];
            System.arraycopy(data, p, b, 0, 4);
            return new ParsedHost(Host.ofAddress(InetAddress.getByAddress(b)), p + 4);
        } else if (atyp == ATYP_IPV6) {
            if (p + 16 > end) throw new EOFException();
            byte[] b = new byte[16];
            System.arraycopy(data, p, b, 0, 16);
            return new ParsedHost(Host.ofAddress(InetAddress.getByAddress(b)), p + 16);
        } else if (atyp == ATYP_DOMAIN) {
            if (p >= end) throw new EOFException();
            int len = data[p++] & 0xff;
            if (p + len > end) throw new EOFException();
            String name = new String(data, p, len, StandardCharsets.UTF_8);
            return new ParsedHost(Host.ofName(name), p + len);
        }
        throw new IOException("Unsupported ATYP");
    }

    private void sendReply(OutputStream out,
                           int reply,
                           InetAddress address,
                           int port) throws IOException {
        if (address == null) address = InetAddress.getByName("0.0.0.0");
        byte[] addr = address.getAddress();
        out.write(5);
        out.write(reply);
        out.write(0);
        out.write(address instanceof Inet6Address ? ATYP_IPV6 : ATYP_IPV4);
        out.write(addr);
        out.write((port >>> 8) & 0xff);
        out.write(port & 0xff);
        out.flush();
    }

    private static int readU8(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int p = 0;
        while (p < length) {
            int n = in.read(out, p, length - p);
            if (n < 0) throw new EOFException();
            p += n;
        }
        return out;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        for (Closeable c : openResources.toArray(new Closeable[0])) {
            closeQuietly(c);
        }
        openResources.clear();
        executor.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
        Log.i(TAG, "Local SOCKS5 server stopped");
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Request {
        final int command;
        final Host host;
        final int port;

        Request(int command, Host host, int port) {
            this.command = command;
            this.host = host;
            this.port = port;
        }
    }

    private static final class Host {
        final InetAddress address;
        final String name;

        private Host(InetAddress address, String name) {
            this.address = address;
            this.name = name;
        }

        static Host ofAddress(InetAddress address) {
            return new Host(address, null);
        }

        static Host ofName(String name) {
            return new Host(null, name);
        }

        InetAddress[] resolveAll(Network network) throws IOException {
            if (address != null) return new InetAddress[]{address};
            InetAddress[] all = network.getAllByName(name);
            if (all == null || all.length == 0) {
                throw new IOException("DNS returned no addresses for " + name);
            }
            return all;
        }

        InetAddress resolveForUdp(Network network) throws IOException {
            InetAddress[] all = resolveAll(network);
            for (InetAddress candidate : all) {
                if (candidate instanceof Inet4Address) return candidate;
            }
            return all[0];
        }

        String display() {
            return name != null ? name : address.getHostAddress();
        }
    }

    private static final class ParsedHost {
        final Host host;
        final int next;

        ParsedHost(Host host, int next) {
            this.host = host;
            this.next = next;
        }
    }

    private static final class UdpFrame {
        final int fragment;
        final Host host;
        final int port;
        final int payloadOffset;
        final int payloadLength;

        UdpFrame(int fragment,
                 Host host,
                 int port,
                 int payloadOffset,
                 int payloadLength) {
            this.fragment = fragment;
            this.host = host;
            this.port = port;
            this.payloadOffset = payloadOffset;
            this.payloadLength = payloadLength;
        }
    }
}
