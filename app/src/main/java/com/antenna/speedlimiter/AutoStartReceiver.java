package com.antenna.speedlimiter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;

/**
 * Restarts the limiter after a device reboot or after this app is updated.
 * Android still requires the user to grant VPN consent at least once.
 */
public final class AutoStartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        SharedPreferences prefs = SpeedVpnService.prefs(context);
        if (!prefs.getBoolean(SpeedVpnService.KEY_ENABLED, false)) return;

        // prepare() returns null when the user has already authorized this VPN app.
        // A BroadcastReceiver is not allowed to display the VPN consent activity.
        if (VpnService.prepare(context) != null) {
            prefs.edit().putBoolean(SpeedVpnService.KEY_RUNNING, false).apply();
            return;
        }

        long down = prefs.getLong(SpeedVpnService.KEY_DOWNLOAD_KBPS, 5000L);
        long up = prefs.getLong(SpeedVpnService.KEY_UPLOAD_KBPS, 1000L);

        Intent service = new Intent(context, SpeedVpnService.class)
                .setAction(SpeedVpnService.ACTION_START)
                .putExtra(SpeedVpnService.EXTRA_DOWNLOAD_KBPS, down)
                .putExtra(SpeedVpnService.EXTRA_UPLOAD_KBPS, up);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException ignored) {
            prefs.edit().putBoolean(SpeedVpnService.KEY_RUNNING, false).apply();
        }
    }
}
