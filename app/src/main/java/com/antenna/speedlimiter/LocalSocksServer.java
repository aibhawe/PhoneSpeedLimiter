package com.antenna.speedlimiter;

import android.net.VpnService;

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

/**
 * Minimal local SOCKS5 server used only by this app's tun2socks engine.
 * It forwards TCP and UDP directly through the phone's real network while
 * applying one aggregate upload limiter and one aggregate download limiter.
 */
final class LocalSocksServer implements Closeable {
    private static final int SOCKS_VERSION = 5;
    private static final int CMD_CONNECT = 1;
    private static final int CMD_UDP_ASSOCIATE = 3;
    private static final int ATYP_IPV4 = 1;
    private static final int ATYP_DOMAIN = 3;
    private static final int ATYP_IPV6 = 4;

    private final VpnService vpnService;
    private final int port;
    private final BandwidthLimiter uploadLimiter;
    private final BandwidthLimiter downloadLimiter;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Set<Closeable> openResources = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ServerSocket serverSocket;
    private Thread acceptThread;

    LocalSocksServer(VpnService vpnService, int port, long uploadKbps, long downloadKbps) {
        this.vpnService = vpnService;
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
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                openResources.add(client);
                executor.execute(() -> handleClient(client));
            } catch (SocketException e) {
                if (running.get()) e.printStackTrace();
                break;
            } catch (IOException e) {
                if (running.get()) e.printStackTrace();
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
        } catch (Exception ignored) {
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
        out.write(new byte[]{5, 0}); // no authentication
        out.flush();
    }

    private Request readRequest(InputStream in) throws IOException {
        int ver = readU8(in);
        int cmd = readU8(in);
        readU8(in); // reserved
        int atyp = readU8(in);
        if (ver != SOCKS_VERSION) throw new IOException("Bad request version");
        Host host = readHost(in, atyp);
        int port = (readU8(in) << 8) | readU8(in);
        return new Request(cmd, host, port);
    }

    private void handleTcpConnect(Socket client, InputStream clientIn, OutputStream clientOut, Request request) throws IOException {
        Socket remote = new Socket();
        openResources.add(remote);
        try {
            vpnService.protect(remote);
            remote.setTcpNoDelay(true);
            remote.connect(new InetSocketAddress(request.host.resolve(), request.port), 15_000);
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

    private void handleUdpAssociate(Socket control, InputStream controlIn, OutputStream controlOut) throws IOException {
        DatagramSocket udp = new DatagramSocket(null);
        openResources.add(udp);
        try {
            udp.setReuseAddress(true);
            udp.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            vpnService.protect(udp);
            sendReply(controlOut, 0, InetAddress.getByName("127.0.0.1"), udp.getLocalPort());

            Thread relay = new Thread(() -> udpRelayLoop(udp), "speedlimit-socks-udp");
            relay.start();

            // SOCKS5 keeps the UDP association alive while this TCP connection is open.
            try {
                while (running.get() && controlIn.read() >= 0) {
                    // Ignore any unexpected control-channel payload.
                }
            } catch (IOException ignored) {
            }
        } finally {
            udp.close();
            openResources.remove(udp);
        }
    }

    private void udpRelayLoop(DatagramSocket socket) {
        byte[] buffer = new byte[65_535];
        InetSocketAddress clientEndpoint = null;
        try {
            socket.setSoTimeout(1000);
            while (running.get() && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                InetSocketAddress source = new InetSocketAddress(packet.getAddress(), packet.getPort());
                boolean fromClient = clientEndpoint == null || source.equals(clientEndpoint);
                if (clientEndpoint == null) clientEndpoint = source;

                if (fromClient) {
                    UdpFrame frame = parseUdpFrame(packet.getData(), packet.getOffset(), packet.getLength());
                    if (frame == null || frame.fragment != 0) continue;
                    uploadLimiter.acquire(frame.payloadLength);
                    DatagramPacket outbound = new DatagramPacket(
                            packet.getData(), frame.payloadOffset, frame.payloadLength,
                            frame.host.resolve(), frame.port);
                    socket.send(outbound);
                } else {
                    byte[] wrapped = buildUdpFrame(packet.getAddress(), packet.getPort(),
                            packet.getData(), packet.getOffset(), packet.getLength());
                    downloadLimiter.acquire(packet.getLength());
                    DatagramPacket inbound = new DatagramPacket(
                            wrapped, wrapped.length, clientEndpoint.getAddress(), clientEndpoint.getPort());
                    socket.send(inbound);
                }
            }
        } catch (Exception ignored) {
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

    private byte[] buildUdpFrame(InetAddress source, int port, byte[] payload, int offset, int length) {
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

    private void sendReply(OutputStream out, int reply, InetAddress address, int port) throws IOException {
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
        for (Closeable c : openResources.toArray(new Closeable[0])) closeQuietly(c);
        openResources.clear();
        executor.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
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
        static Host ofAddress(InetAddress address) { return new Host(address, null); }
        static Host ofName(String name) { return new Host(null, name); }
        InetAddress resolve() throws IOException {
            return address != null ? address : InetAddress.getByName(name);
        }
    }

    private static final class ParsedHost {
        final Host host;
        final int next;
        ParsedHost(Host host, int next) { this.host = host; this.next = next; }
    }

    private static final class UdpFrame {
        final int fragment;
        final Host host;
        final int port;
        final int payloadOffset;
        final int payloadLength;
        UdpFrame(int fragment, Host host, int port, int payloadOffset, int payloadLength) {
            this.fragment = fragment;
            this.host = host;
            this.port = port;
            this.payloadOffset = payloadOffset;
            this.payloadLength = payloadLength;
        }
    }
}
