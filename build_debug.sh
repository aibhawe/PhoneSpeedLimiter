#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

"$ROOT/setup_dependency.sh"

if [ -z "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}" ]; then
  echo "حدد ANDROID_SDK_ROOT أو ANDROID_HOME إلى Android SDK أولاً." >&2
  exit 2
fi

if command -v gradle >/dev/null 2>&1; then
  GRADLE=gradle
else
  CACHE="$HOME/.cache/phone-speed-limiter"
  GHOME="$CACHE/gradle-8.8"
  mkdir -p "$CACHE"
  if [ ! -x "$GHOME/bin/gradle" ]; then
    echo "تنزيل Gradle 8.8..."
    curl -L --fail https://services.gradle.org/distributions/gradle-8.8-bin.zip -o "$CACHE/gradle.zip"
    rm -rf "$GHOME"
    unzip -q "$CACHE/gradle.zip" -d "$CACHE"
  fi
  GRADLE="$GHOME/bin/gradle"
fi

cd "$ROOT"
"$GRADLE" :app:assembleDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo
if [ -f "$APK" ]; then
  echo "APK: $APK"
else
  echo "انتهى البناء لكن لم أجد APK في المسار المتوقع." >&2
fi
