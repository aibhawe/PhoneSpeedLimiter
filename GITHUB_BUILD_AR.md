# بناء APK باستخدام GitHub Actions

1. أنشئ Repository جديداً على GitHub.
2. ارفع محتويات مجلد PhoneSpeedLimiter نفسه إلى جذر المستودع.
3. تأكد من وجود `.github/workflows/build-apk.yml`.
4. افتح تبويب Actions ثم Build Android APK.
5. اضغط Run workflow ثم Run workflow.
6. بعد نجاح البناء افتح الـ Run وانزل إلى Artifacts.
7. حمّل PhoneSpeedLimiter-v1.1-debug-apk، ثم فك ZIP لتحصل على app-debug.apk.

يعمل الـ workflow أيضاً تلقائياً عند Push إلى main أو master.
