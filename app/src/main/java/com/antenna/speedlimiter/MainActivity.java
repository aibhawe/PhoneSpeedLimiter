package com.antenna.speedlimiter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private EditText downloadInput;
    private EditText uploadInput;
    private Button applyButton;
    private Button stopButton;
    private TextView statusText;
    private long pendingDownloadKbps;
    private long pendingUploadKbps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        loadSavedValues();
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private View buildUi() {
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(24), pad, pad);
        root.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("محدد سرعة الهاتف");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap(dp(8)));

        TextView sub = new TextView(this);
        sub.setText("تحديد السرعة على هذا الهاتف مع تشغيل تلقائي دائم بعد إعادة التشغيل.");
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub, matchWrap(dp(24)));

        root.addView(label("حد التنزيل (Mbps)"), matchWrap(dp(6)));
        downloadInput = speedInput("مثال: 5");
        root.addView(downloadInput, matchWrap(dp(16)));

        root.addView(label("حد الرفع (Mbps)"), matchWrap(dp(6)));
        uploadInput = speedInput("مثال: 1");
        root.addView(uploadInput, matchWrap(dp(6)));

        TextView zeroHint = new TextView(this);
        zeroHint.setText("اكتب 0 لجعل الاتجاه غير محدود.");
        zeroHint.setTextSize(13);
        root.addView(zeroHint, matchWrap(dp(18)));

        applyButton = new Button(this);
        applyButton.setTextSize(17);
        applyButton.setMinHeight(dp(52));
        applyButton.setText("حفظ وتشغيل دائمًا");
        applyButton.setOnClickListener(v -> prepareAndStart());
        root.addView(applyButton, matchWrap(dp(10)));

        Button alwaysOnButton = new Button(this);
        alwaysOnButton.setText("فتح إعدادات Always-on VPN");
        alwaysOnButton.setOnClickListener(v -> openVpnSettings());
        root.addView(alwaysOnButton, matchWrap(dp(8)));

        Button batteryButton = new Button(this);
        batteryButton.setText("منع النظام من تقييد التطبيق");
        batteryButton.setOnClickListener(v -> requestBatteryExemption());
        root.addView(batteryButton, matchWrap(dp(10)));

        stopButton = new Button(this);
        stopButton.setText("إيقاف التشغيل الدائم");
        stopButton.setOnClickListener(v -> stopPersistentMode());
        root.addView(stopButton, matchWrap(dp(14)));

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(statusText, matchWrap(0));

        TextView note = new TextView(this);
        note.setText(
                "لأقوى ثبات: بعد التشغيل لأول مرة، افتح إعدادات Always-on VPN " +
                "وفعّل التطبيق كـ Always-on. ثم امنع تحسين البطارية عنه. " +
                "سيظهر رمز VPN لأن التحديد يتم محليًا داخل الهاتف.");
        note.setTextSize(13);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note, matchWrap(0));

        return root;
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(16);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private EditText speedInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setGravity(Gravity.CENTER);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = bottomMargin;
        return p;
    }

    private void prepareAndStart() {
        try {
            pendingDownloadKbps = parseMbpsToKbps(downloadInput.getText().toString());
            pendingUploadKbps = parseMbpsToKbps(uploadInput.getText().toString());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "أدخل سرعة صحيحة، مثل 5 أو 0.5", Toast.LENGTH_LONG).show();
            return;
        }

        if (pendingDownloadKbps == 0 && pendingUploadKbps == 0) {
            Toast.makeText(this,
                    "كلا الاتجاهين غير محدودين؛ أدخل حدًا واحدًا على الأقل.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Save desired state before launching the service. This is what the boot
        // receiver uses after a restart.
        SpeedVpnService.prefs(this).edit()
                .putBoolean(SpeedVpnService.KEY_ENABLED, true)
                .putLong(SpeedVpnService.KEY_DOWNLOAD_KBPS, pendingDownloadKbps)
                .putLong(SpeedVpnService.KEY_UPLOAD_KBPS, pendingUploadKbps)
                .apply();

        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            startActivityForResult(permission, REQ_VPN);
        } else {
            startLimiter();
        }
    }

    private void startLimiter() {
        Intent start = new Intent(this, SpeedVpnService.class)
                .setAction(SpeedVpnService.ACTION_START)
                .putExtra(SpeedVpnService.EXTRA_DOWNLOAD_KBPS, pendingDownloadKbps)
                .putExtra(SpeedVpnService.EXTRA_UPLOAD_KBPS, pendingUploadKbps);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(start);
        } else {
            startService(start);
        }
        statusText.setText("جاري تشغيل الوضع الدائم…");
    }

    private void stopPersistentMode() {
        SpeedVpnService.prefs(this).edit()
                .putBoolean(SpeedVpnService.KEY_ENABLED, false)
                .putBoolean(SpeedVpnService.KEY_RUNNING, false)
                .apply();
        Intent stop = new Intent(this, SpeedVpnService.class)
                .setAction(SpeedVpnService.ACTION_STOP);
        try {
            startService(stop);
        } catch (RuntimeException ignored) {
        }
        Toast.makeText(this,
                "تم تعطيل التشغيل التلقائي. إذا كان Always-on مفعّلًا، عطّله أيضًا من إعدادات VPN.",
                Toast.LENGTH_LONG).show();
        refreshState();
    }

    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, "افتح الإعدادات > VPN ثم فعّل Always-on لهذا التطبيق.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "لا يحتاج هذا الإصدار من Android إلى هذا الإعداد.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "التطبيق مستثنى بالفعل من تحسين البطارية.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                Toast.makeText(this,
                        "اجعل استخدام البطارية للتطبيق: غير مقيّد / Unrestricted من إعدادات النظام.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startLimiter();
            } else {
                SpeedVpnService.prefs(this).edit()
                        .putBoolean(SpeedVpnService.KEY_ENABLED, false)
                        .apply();
                Toast.makeText(this,
                        "يلزم السماح باتصال VPN المحلي لتحديد السرعة.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadSavedValues() {
        SharedPreferences p = SpeedVpnService.prefs(this);
        long down = p.getLong(SpeedVpnService.KEY_DOWNLOAD_KBPS, 5000L);
        long up = p.getLong(SpeedVpnService.KEY_UPLOAD_KBPS, 1000L);
        downloadInput.setText(formatMbps(down));
        uploadInput.setText(formatMbps(up));
    }

    private void refreshState() {
        SharedPreferences p = SpeedVpnService.prefs(this);
        boolean enabled = p.getBoolean(SpeedVpnService.KEY_ENABLED, false);
        boolean running = p.getBoolean(SpeedVpnService.KEY_RUNNING, false);
        boolean batteryExempt = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            batteryExempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        String state;
        if (running) {
            state = "الحالة: يعمل الآن • التشغيل بعد إعادة التشغيل: مفعّل";
        } else if (enabled) {
            state = "الحالة: الوضع الدائم مفعّل، والخدمة بانتظار التشغيل/إعادة التشغيل";
        } else {
            state = "الحالة: متوقف";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            state += batteryExempt ? "\nالبطارية: غير مقيّد" : "\nالبطارية: يفضّل اختيار غير مقيّد";
        }
        statusText.setText(state);
        stopButton.setEnabled(enabled || running);
    }

    private static long parseMbpsToKbps(String text) {
        String s = text.trim().replace(',', '.');
        if (s.isEmpty()) throw new NumberFormatException();
        double mbps = Double.parseDouble(s);
        if (!Double.isFinite(mbps) || mbps < 0 || mbps > 10_000) {
            throw new NumberFormatException();
        }
        return Math.round(mbps * 1000.0);
    }

    private static String formatMbps(long kbps) {
        if (kbps == 0) return "0";
        double v = kbps / 1000.0;
        if (Math.abs(v - Math.rint(v)) < 0.0001) {
            return String.format(Locale.US, "%.0f", v);
        }
        return String.format(Locale.US, "%.2f", v)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
