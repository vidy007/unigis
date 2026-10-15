#!/usr/bin/env bash
# Builds the dependency-free APK without the Android Gradle Plugin, using only the
# Android SDK tools packaged by Debian/Ubuntu:
#   apt-get install android-sdk-platform-23 aapt dalvik-exchange apksigner zipalign
# plus Gradle (for kotlinc + kotlin-stdlib from Maven Central) and a JDK.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
export ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
OUT=build/apk
GRADLE=${GRADLE:-$(command -v gradle || echo ../gradlew)}

rm -rf "$OUT" build/gen
mkdir -p "$OUT" build/gen

echo "> aapt: resources + R.java"
aapt package -f -m \
  -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  -J build/gen -F "$OUT/unsigned.apk" \
  --version-code 1 --version-name 0.1.0-lite

echo "> kotlinc (Gradle)"
"$GRADLE" -q -p . jar copyRuntime

echo "> dx: classes.dex"
mkdir -p "$OUT/libs"
for jar in build/runtime/*.jar; do
  # dx does not understand multi-release / module-info entries.
  cp "$jar" "$OUT/libs/"
  zip -q -d "$OUT/libs/$(basename "$jar")" 'META-INF/versions/*' 'module-info.class' '*/module-info.class' >/dev/null 2>&1 || true
done
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" \
  build/libs/unigis-lite.jar "$OUT"/libs/*.jar

echo "> package + align + sign"
(cd "$OUT" && aapt add -f unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/unigis-lite.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/unigis-lite.apk"
echo "OK: $(pwd)/$OUT/unigis-lite.apk"
