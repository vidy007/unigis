#!/usr/bin/env bash
# Builds the dependency-free APK without the Android Gradle Plugin, using only the
# Android SDK tools packaged by Debian/Ubuntu:
#   apt-get install android-sdk-platform-23 aapt dalvik-exchange apksigner zipalign
# plus Gradle (for kotlinc + kotlin-stdlib from Maven Central), a JDK and an Android NDK
# (only its clang is used, for the shared C++ core in app/src/main/cpp; no CMake/AGP).
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
export ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK:-}}}
ABIS=${ABIS:-arm64-v8a armeabi-v7a x86_64 x86}
MIN_SDK=26
CORE=../app/src/main/cpp
OUT=build/apk
GRADLE=${GRADLE:-$(command -v gradle || echo ../gradlew)}
# Same version as the main app unless CI overrides it.
VERSION_NAME=${VERSION_NAME:-$(sed -n 's/^unigis\.versionName=//p' ../gradle.properties | tr -d '\r')}
VERSION_CODE=${VERSION_CODE:-$(sed -n 's/^unigis\.versionCode=//p' ../gradle.properties | tr -d '\r')}
APK_NAME=unigis-lite-$VERSION_NAME.apk

if [ -z "$NDK" ] || [ ! -d "$NDK/toolchains/llvm/prebuilt" ]; then
  echo "Android NDK not found: set ANDROID_NDK_HOME" >&2
  exit 1
fi
CLANGXX=$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin/clang++)

rm -rf "$OUT" build/gen build/native
mkdir -p "$OUT" build/gen build/native

echo "> clang++: libunigis.so ($ABIS)"
for abi in $ABIS; do
  case $abi in
    arm64-v8a)   target=aarch64-linux-android$MIN_SDK ;;
    armeabi-v7a) target=armv7a-linux-androideabi$MIN_SDK ;;
    x86_64)      target=x86_64-linux-android$MIN_SDK ;;
    x86)         target=i686-linux-android$MIN_SDK ;;
    *) echo "Unknown ABI $abi" >&2; exit 1 ;;
  esac
  mkdir -p "build/native/lib/$abi"
  # Static libc++ keeps the APK to one .so per ABI; 16 KB pages for Android 15+ devices.
  "$CLANGXX" --target="$target" -std=c++20 -O2 -fPIC -shared -Wall -Wextra \
    -fvisibility=hidden -ffunction-sections -fdata-sections \
    -static-libstdc++ -Wl,--gc-sections -Wl,-z,max-page-size=16384 -s \
    "$CORE"/*.cpp -llog \
    -o "build/native/lib/$abi/libunigis.so"
done

echo "> aapt: resources + assets + R.java"
aapt package -f -m --replace-version \
  -M AndroidManifest.xml -S res -A ../app/src/main/assets -I "$ANDROID_JAR" \
  -J build/gen -F "$OUT/unsigned.apk" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "> kotlinc (Gradle)"
"$GRADLE" -q -p . jar copyRuntime

echo "> dx: classes.dex"
mkdir -p "$OUT/libs"
for jar in build/runtime/*.jar; do
  # dx does not understand multi-release / module-info entries.
  cp "$jar" "$OUT/libs/"
  zip -q -d "$OUT/libs/$(basename "$jar")" 'META-INF/versions/*' 'module-info.class' '*/module-info.class' >/dev/null 2>&1 || true
done
dalvik-exchange --dex --min-sdk-version=$MIN_SDK --output="$OUT/classes.dex" \
  build/libs/unigis-lite.jar "$OUT"/libs/*.jar

echo "> package + align + sign"
(cd "$OUT" && aapt add -f unsigned.apk classes.dex >/dev/null)
(cd build/native && aapt add -f "../../$OUT/unsigned.apk" lib/*/libunigis.so >/dev/null)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/$APK_NAME" "$OUT/aligned.apk"
apksigner verify "$OUT/$APK_NAME"
echo "OK: $(pwd)/$OUT/$APK_NAME"
