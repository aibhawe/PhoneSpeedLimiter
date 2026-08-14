package com.antenna.speedlimiter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

    private static final int SOCKS_PORT = 10808;
    private static final int NOTIFICATION_ID = 10;
    private static final String CHANNEL_ID = "speed_limit";
    private static final String PREFS = "speed_limiter";

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

        // A sticky restart can arrive with a null intent. In that case recover
        // the last saved speed limits instead of falling back to unlimited.
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

        // Any explicit start means the user wants persistence across reboot.
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            desiredEnabled = true;
        }

        saveLimits(downloadKbps, uploadKbps);
        if (desiredEnabled) setDesiredEnabled(true);

        startAsForeground(downloadKbps, uploadKbps);

        if (tunFd != null && socksServer != null) {
            socksServer.updateLimits(uploadKbps, downloadKbps);
            setRunning(true);
            return START_STICKY;
        }

        try {
            startEverything(downloadKbps, uploadKbps);
            setRunning(true);
        } catch (Exception e) {
            e.printStackTrace();
            setRunning(false);
            cleanupResources();
            // Keep KEY_ENABLED=true so START_STICKY / boot / Always-on can retry.
        }
        return START_STICKY;
    }

    private void startEverything(long downloadKbps, long uploadKbps) throws Exception {
        socksServer = new LocalSocksServer(this, SOCKS_PORT, uploadKbps, downloadKbps);
        socksServer.start();

        Builder builder = new Builder()
                .setSession("Phone Speed Limiter")
                .setMtu(1500)
                .setBlocking(false)
                .addAddress("10.111.222.1", 24)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:111:222::1", 64)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("2606:4700:4700::1111");

        // The proxy's own upstream sockets are protected as well, but excluding
        // this package provides another guard against accidental VPN loops.
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        tunFd = builder.establish();
        if (tunFd == null) throw new IOException("Could not establish VPN");

        File configFile = new File(getCacheDir(), "tproxy.conf");
        String config = "misc:\n" +
                "  task-stack-size: 20480\n" +
                "tunnel:\n" +
                "  mtu: 1500\n" +
                "  icmp: 'reply'\n" +
                "socks5:\n" +
                "  port: " + SOCKS_PORT + "\n" +
                "  address: '127.0.0.1'\n" +
                "  udp: 'udp'\n";

        try (FileOutputStream out = new FileOutputStream(configFile, false)) {
            out.write(config.getBytes(StandardCharsets.UTF_8));
        }

        if (!TProxyStartService(configFile.getAbsolutePath(), tunFd.getFd())) {
            throw new IOException("Could not start tun2socks");
        }
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
        // Revocation is an explicit Android/user action. We must honor it and
        // cannot legally recreate the VPN until consent exists again.
        explicitStop = true;
        setDesiredEnabled(false);
        cleanupResources();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Do not stop when the user swipes the activity away from Recents.
        // START_STICKY and Always-on VPN are responsible for process recovery.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        // System/OEM process death is not the same as the user disabling the
        // limiter. Preserve KEY_ENABLED so Android can recreate the sticky VPN.
        cleanupResources();
        if (explicitStop) setDesiredEnabled(false);
        super.onDestroy();
    }
}
