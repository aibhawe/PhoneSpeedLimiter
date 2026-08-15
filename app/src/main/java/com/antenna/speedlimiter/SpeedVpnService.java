package com.antenna.speedlimiter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class SpeedVpnService extends VpnService {
    public static final String ACTION_START = "com.antenna.speedlimiter.START";
    public static final String ACTION_STOP = "com.antenna.speedlimiter.STOP";
    public static final String EXTRA_DOWNLOAD_KBPS = "download_kbps";
    public static final String EXTRA_UPLOAD_KBPS = "upload_kbps";

    static final String KEY_ENABLED = "enabled";
    static final String KEY_RUNNING = "running";
    static final String KEY_DOWNLOAD_KBPS = "download_kbps";
    static final String KEY_UPLOAD_KBPS = "upload_kbps";
    static final String KEY_LAST_ERROR = "last_error";

    private static final int SOCKS_PORT = 10808;
    private static final int NOTIFICATION_ID = 10;
    private static final String CHANNEL_ID = "speed_limit";
    private static final String PREFS = "speed_limiter";

    // Match the stable defaults used by heiher/sockstun.
    private static final int TUN_MTU = 8500;
    private static final String TUN_IPV4 = "198.18.0.1";
    private static final String TUN_IPV6 = "fc00::1";
    private static final String MAPPED_DNS = "198.18.0.2";

    private static native boolean TProxyStartService(String configPath, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private ParcelFileDescriptor tunFd;
    private LocalSocksServer socksServer;
    private volatile boolean explicitStop;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            explicitStop = true;
            setDesiredEnabled(false);
            cleanupResources();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        SharedPreferences p = prefs(this);
        boolean alwaysOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn();
        boolean desiredEnabled = p.getBoolean(KEY_ENABLED, false) || alwaysOn;

        long downloadKbps = p.getLong(KEY_DOWNLOAD_KBPS, 5000L);
        long uploadKbps = p.getLong(KEY_UPLOAD_KBPS, 1000L);
        if (intent != null) {
            downloadKbps = Math.max(0L,
                    intent.getLongExtra(EXTRA_DOWNLOAD_KBPS, downloadKbps));
            uploadKbps = Math.max(0L,
                    intent.getLongExtra(EXTRA_UPLOAD_KBPS, uploadKbps));
        }

        if (!desiredEnabled && intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            desiredEnabled = true;
        }

        saveLimits(downloadKbps, uploadKbps);
        if (desiredEnabled) setDesiredEnabled(true);
        prefs(this).edit().remove(KEY_LAST_ERROR).apply();

        startAsForeground(downloadKbps, uploadKbps);

        if (tunFd != null && socksServer != null) {
            socksServer.updateLimits(uploadKbps, downloadKbps);
            setRunning(true);
            return START_STICKY;
        }

        try {
            startEverything(downloadKbps, uploadKbps);
            setRunning(true);
        } catch (Throwable e) {
            String msg = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            prefs(this).edit().putString(KEY_LAST_ERROR, msg).apply();
            e.printStackTrace();
            setRunning(false);
            cleanupResources();
        }
        return START_STICKY;
    }

    private void startEverything(long downloadKbps, long uploadKbps) throws Exception {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) throw new IOException("ConnectivityManager unavailable");

        Network underlying = findUnderlyingNetwork(cm);
        if (underlying == null) {
            throw new IOException("No usable physical network available");
        }

        socksServer = new LocalSocksServer(
                this, underlying, SOCKS_PORT, uploadKbps, downloadKbps);
        socksServer.start();

        Builder builder = new Builder()
                .setSession("Phone Speed Limiter")
                .setMtu(TUN_MTU)
                .setBlocking(false)
                .setUnderlyingNetworks(new Network[]{underlying})
                .addAddress(TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_IPV6, 128)
                .addRoute("::", 0)
                // Mapped DNS avoids depending on a normal UDP DNS exchange
                // through the VPN. hev-socks5-tunnel maps domains to fake IPv4
                // addresses and later reconstructs the domain for SOCKS CONNECT.
                .addDnsServer(MAPPED_DNS);

        // Explicitly keep the local SOCKS control path out of the TUN on API 33+.
        // This is useful on OEM routing stacks where a 0/0 VPN route can otherwise
        // interact badly with localhost traffic.
        if (Build.VERSION.SDK_INT >= 33) {
            builder.excludeRoute(new IpPrefix(InetAddress.getByName("127.0.0.0"), 8));
        }

        tunFd = builder.establish();
        if (tunFd == null) throw new IOException("Could not establish VPN");

        if (!setUnderlyingNetworks(new Network[]{underlying})) {
            // Not fatal: Builder already received the same underlying network.
            prefs(this).edit().putString(KEY_LAST_ERROR,
                    "Warning: setUnderlyingNetworks() returned false").apply();
        }

        File configFile = new File(getCacheDir(), "tproxy.conf");
        String config = "misc:\n" +
                "  task-stack-size: 81920\n" +
                "tunnel:\n" +
                "  mtu: " + TUN_MTU + "\n" +
                "  icmp: 'reply'\n" +
                "socks5:\n" +
                "  port: " + SOCKS_PORT + "\n" +
                "  address: '127.0.0.1'\n" +
                "  udp: 'udp'\n" +
                "mapdns:\n" +
                "  address: " + MAPPED_DNS + "\n" +
                "  port: 53\n" +
                "  network: 240.0.0.0\n" +
                "  netmask: 240.0.0.0\n" +
                "  cache-size: 10000\n";

        try (FileOutputStream out = new FileOutputStream(configFile, false)) {
            out.write(config.getBytes(StandardCharsets.UTF_8));
        }

        if (!TProxyStartService(configFile.getAbsolutePath(), tunFd.getFd())) {
            throw new IOException("Could not start tun2socks");
        }
    }

    private Network findUnderlyingNetwork(ConnectivityManager cm) {
        Network active = cm.getActiveNetwork();
        if (isUsableUnderlying(cm, active)) return active;

        Network fallback = null;
        for (Network network : cm.getAllNetworks()) {
            if (!isUsableUnderlying(cm, network)) continue;
            if (fallback == null) fallback = network;

            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return network;
            }
        }
        return fallback;
    }

    private boolean isUsableUnderlying(ConnectivityManager cm, Network network) {
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private void startAsForeground(long downloadKbps, long uploadKbps) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn()
                ? " • Always-on"
                : " • تشغيل دائم";
        String text = "↓ " + human(downloadKbps) + "   ↑ " + human(uploadKbps) + mode;
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("محدد سرعة الهاتف يعمل")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private static String human(long kbps) {
        if (kbps <= 0) return "غير محدود";
        if (kbps >= 1000) {
            return String.format(java.util.Locale.US, "%.1f Mbps", kbps / 1000.0);
        }
        return kbps + " Kbps";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "تحديد سرعة الإنترنت",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("خدمة VPN محلية دائمة لتحديد سرعة الهاتف");
            nm.createNotificationChannel(channel);
        }
    }

    private void cleanupResources() {
        try {
            if (TProxyIsRunning()) TProxyStopService();
        } catch (Throwable ignored) {
        }

        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }

        if (socksServer != null) {
            socksServer.close();
            socksServer = null;
        }
        setRunning(false);
    }

    private void setDesiredEnabled(boolean enabled) {
        prefs(this).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private void setRunning(boolean running) {
        prefs(this).edit().putBoolean(KEY_RUNNING, running).apply();
    }

    private void saveLimits(long downloadKbps, long uploadKbps) {
        prefs(this).edit()
                .putLong(KEY_DOWNLOAD_KBPS, downloadKbps)
                .putLong(KEY_UPLOAD_KBPS, uploadKbps)
                .apply();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public void onRevoke() {
        explicitStop = true;
        setDesiredEnabled(false);
        cleanupResources();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        cleanupResources();
        if (explicitStop) setDesiredEnabled(false);
        super.onDestroy();
    }
}
