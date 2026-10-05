#!/usr/bin/env bash
# Build the Circa watch apps with Gradle, run their JVM tests, sign them with the public AOSP test keys, and write
# prebuilt/<Module>.apk plus a generated prebuilt/Android.bp and prebuilt/packages.mk for the Circa image build.
#
# Checked out by the Circa manifest at vendor/circa-apps, the image build picks the apps up through circa-apps.mk
# (inherited by device/circa) once this script has run; without prebuilt/ the image builds without them.
#
# Usage: ./build-all.sh [--only launcher,watchlink,...] [--out DIR] [--no-tests]
#
# Environment (all optional; whatever is missing is fetched into .toolchain/, which is git-ignored):
#   JAVA_HOME         a JDK 17 or newer
#   ANDROID_HOME      an Android SDK (platforms android-36 and android-37.0, build-tools 36.0.0 and 37.0.0;
#                     missing packages are installed with its sdkmanager when it has one)
#   GRADLE_USER_HOME  Gradle's cache (default: .toolchain/gradle)
#   CIRCA_KEYS        directory with platform.{pk8,x509.pem} and testkey.{pk8,x509.pem}. Default:
#                     $ANDROID_BUILD_TOP/build/make/target/product/security, else fetched from AOSP.
#
# Signing: the apps that need platform signature permissions (launcher, settings, clock, companion, exercise,
# keyboard) use the AOSP *platform* test key, as the rest of a test-keys Circa image; WatchLink uses the AOSP
# *testkey*: it talks to the phone over Bluetooth and needs no platform powers. These keys are public, which is
# fine for a test-keys build and nothing more (see README.md).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="$HERE/.toolchain"
OUT="$HERE/prebuilt"
ONLY=""
TESTS=1
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --no-tests) TESTS=0; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# name | Gradle project dir | Soong module | key | JVM test task
APPS=(
  "launcher|launcher|CircaLauncher|platform|testDebugUnitTest"
  "settings|settings|CircaSettings|platform|testDebugUnitTest"
  "clock|clock|CircaClock|platform|testDebugUnitTest"
  "companion|companion|CircaCompanion|platform|testDebugUnitTest"
  "exercise|exercise|CircaExercise|platform|testDebugUnitTest"
  "keyboard|keyboard|CircaKeyboard|platform|testDebugUnitTest"
  "watchlink|watchlink|WatchLink|testkey|hostTest"
)

JDK_URL=https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz
CMDLINE_URL=https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
KEYS_URL=https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security

fetch() { curl -fsSL --retry 3 --max-time 600 -o "$2" "$1"; }

# ---- JDK -----------------------------------------------------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  if [ ! -x "$TC/jdk/bin/java" ]; then
    echo "== fetching JDK 17 into $TC/jdk"
    mkdir -p "$TC/jdk"
    fetch "$JDK_URL" "$TC/jdk.tar.gz"
    tar -xzf "$TC/jdk.tar.gz" -C "$TC/jdk" --strip-components=1 && rm -f "$TC/jdk.tar.gz"
  fi
  export JAVA_HOME="$TC/jdk"
fi
export PATH="$JAVA_HOME/bin:$PATH"

# ---- Android SDK ---------------------------------------------------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ]; then
  export ANDROID_HOME="$TC/sdk"
  if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "== fetching the Android command-line tools into $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    fetch "$CMDLINE_URL" "$TC/cmdline-tools.zip"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$TC/cmdline-tools"
    unzip -q "$TC/cmdline-tools.zip" -d "$TC" && mv "$TC/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -f "$TC/cmdline-tools.zip"
  fi
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
need=()
[ -d "$ANDROID_HOME/platforms/android-37.0" ] || need+=("platforms;android-37.0")
[ -d "$ANDROID_HOME/platforms/android-36" ] || need+=("platforms;android-36")
[ -d "$ANDROID_HOME/build-tools/37.0.0" ] || need+=("build-tools;37.0.0")
[ -d "$ANDROID_HOME/build-tools/36.0.0" ] || need+=("build-tools;36.0.0")
if [ "${#need[@]}" -gt 0 ]; then
  SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  [ -x "$SDKM" ] || { echo "error: SDK packages missing (${need[*]}) and no sdkmanager at $SDKM" >&2; exit 1; }
  echo "== installing SDK packages: ${need[*]} (accepting the SDK licences)"
  yes | "$SDKM" --licenses >/dev/null || true
  "$SDKM" --install "${need[@]}"
fi
BT="$ANDROID_HOME/build-tools/37.0.0"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TC/gradle}"

# ---- signing keys --------------------------------------------------------------------------------------------------
KEYS="${CIRCA_KEYS:-}"
if [ -z "$KEYS" ] && [ -n "${ANDROID_BUILD_TOP:-}" ] && [ -s "$ANDROID_BUILD_TOP/build/make/target/product/security/platform.pk8" ]; then
  KEYS="$ANDROID_BUILD_TOP/build/make/target/product/security"
fi
if [ -z "$KEYS" ]; then
  KEYS="$TC/keys"
  mkdir -p "$KEYS"
  for k in platform testkey; do
    if [ ! -s "$KEYS/$k.pk8" ] || [ ! -s "$KEYS/$k.x509.pem" ]; then
      echo "== fetching the AOSP $k test key"
      fetch "$KEYS_URL/$k.pk8?format=TEXT" "$KEYS/$k.pk8.b64" && base64 -d "$KEYS/$k.pk8.b64" > "$KEYS/$k.pk8"
      fetch "$KEYS_URL/$k.x509.pem?format=TEXT" "$KEYS/$k.pem.b64" && base64 -d "$KEYS/$k.pem.b64" > "$KEYS/$k.x509.pem"
      rm -f "$KEYS/$k.pk8.b64" "$KEYS/$k.pem.b64"
    fi
  done
fi
for k in platform testkey; do
  [ -s "$KEYS/$k.pk8" ] && [ -s "$KEYS/$k.x509.pem" ] || { echo "error: $KEYS/$k.{pk8,x509.pem} missing" >&2; exit 1; }
done

# ---- build ---------------------------------------------------------------------------------------------------------
mkdir -p "$OUT"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
built=()
for row in "${APPS[@]}"; do
  IFS='|' read -r name dir module key testtask <<<"$row"
  if [ -n "$ONLY" ] && [[ ",$ONLY," != *",$name,"* ]]; then continue; fi
  echo "== $name: $( [ $TESTS = 1 ] && echo "tests + " )release build"
  tasks=(":app:assembleRelease")
  [ $TESTS = 1 ] && tasks=(":app:$testtask" "${tasks[@]}")
  (cd "$HERE/$dir" && ./gradlew --no-daemon --console=plain "${tasks[@]}")
  if [ $TESTS = 1 ] && [ "$testtask" = testDebugUnitTest ]; then
    res="$HERE/$dir/app/build/test-results/testDebugUnitTest"
    t=0; f=0
    for x in "$res"/TEST-*.xml; do
      t=$((t + $(sed -n 's/.*<testsuite [^>]*tests="\([0-9]*\)".*/\1/p' "$x" | head -1)))
      f=$((f + $(sed -n 's/.*<testsuite [^>]*failures="\([0-9]*\)".*/\1/p' "$x" | head -1)))
    done
    echo "   unit tests: $t run, $f failed"
  fi
  unsigned="$HERE/$dir/app/build/outputs/apk/release/app-release-unsigned.apk"
  [ -f "$unsigned" ] || { echo "error: $unsigned not found" >&2; exit 1; }
  "$BT/zipalign" -f -p 4 "$unsigned" "$W/$module.aligned.apk"
  "$BT/apksigner" sign --key "$KEYS/$key.pk8" --cert "$KEYS/$key.x509.pem" --out "$OUT/$module.apk" "$W/$module.aligned.apk"
  rm -f "$OUT/$module.apk.idsig"
  "$BT/apksigner" verify "$OUT/$module.apk"
  perms="$("$BT/aapt2" dump permissions "$OUT/$module.apk")"
  if grep -q 'android.permission.INTERNET' <<<"$perms"; then echo "error: $name declares INTERNET" >&2; exit 1; fi
  if [ "$name" = keyboard ]; then
    if grep -q 'uses-permission' <<<"$perms"; then echo "error: the keyboard should request no permissions" >&2; exit 1; fi
    if "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$OUT/$module.apk" | grep -q TypingTestActivity; then
      echo "error: the keyboard's debug test activity leaked into the release APK" >&2; exit 1
    fi
  fi
  echo "   $(sha256sum "$OUT/$module.apk" | cut -c1-16)  $OUT/$module.apk ($key key)"
  built+=("$module")
done

# ---- Soong modules for every APK in $OUT ---------------------------------------------------------------------------
"$HERE/tools/gen-prebuilt-bp.sh" "$OUT"
echo "== done: ${built[*]:-nothing built}"
