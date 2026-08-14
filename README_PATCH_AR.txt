PhoneSpeedLimiter v1.3 HyperOS compatibility patch

استبدل الملفات التالية في المستودع بنفس المسارات:
1) app/src/main/java/com/antenna/speedlimiter/LocalSocksServer.java
2) app/src/main/java/com/antenna/speedlimiter/SpeedVpnService.java
3) app/build.gradle
4) .github/workflows/build-apk.yml

أهم التغييرات:
- Network.bindSocket للاتصالات الخارجية TCP و UDP.
- التحقق من VpnService.protect.
- DNS عبر الشبكة الفعلية.
- فصل SOCKS5 UDP المحلي عن UDP الخارجي.
- VPN داخلي IPv4 فقط لتفادي مشاكل OEM/HyperOS؛ IPv6 يُحجب أثناء تشغيل المحدد بدلاً من تجاوزه بلا تحديد.
- حذف الاعتماد على addDisallowedApplication(self).
- الإصدار 1.3 وJNI CLSNAME=SpeedVpnService.
