#!/usr/bin/env bash
# PacketFlowDPI - open build without Gradle (Linux / macOS / GitHub Actions).
#
# Requirements:
#   * JDK 17 (JAVA_HOME)
#   * Android SDK with build-tools and a platform
#     (ANDROID_SDK_ROOT or ANDROID_HOME)
#
# Usage:
#   ./build.sh
#   ./build.sh --install
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
    echo "ERROR: set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK" >&2
    exit 1
fi
sdk="$(cd "$sdk" && pwd)"

jdk="${JAVA_HOME:-}"
if [ -z "$jdk" ] || [ ! -x "$jdk/bin/javac" ]; then
    echo "ERROR: set JAVA_HOME to a JDK 17 installation" >&2
    exit 1
fi

pick_latest() {
    # $1 = directory, $2 = glob; prints the highest version-like directory
    find "$1" -mindepth 1 -maxdepth 1 -type d -name "$2" -print 2>/dev/null |
        while IFS= read -r d; do
            printf '%s\t%s\n' "$(basename "$d" | sed 's/[^0-9.]//g')" "$d"
        done |
        sort -t. -k1,1n -k2,2n -k3,3n |
        tail -n 1 |
        cut -f2-
}

build_tools="$(pick_latest "$sdk/build-tools" '*')"
platform="$(pick_latest "$sdk/platforms" 'android-*')"
if [ -z "$build_tools" ] || [ -z "$platform" ]; then
    echo "ERROR: no build-tools or Android platform found in $sdk" >&2
    exit 1
fi

android_jar="$platform/android.jar"
aapt2="$build_tools/aapt2"
zipalign="$build_tools/zipalign"
apksigner="$build_tools/apksigner"
d8="$build_tools/d8"
javac="$jdk/bin/javac"
keytool="$jdk/bin/keytool"

for tool in "$android_jar" "$aapt2" "$zipalign" "$apksigner" "$d8" "$javac" "$keytool"; do
    if [ ! -e "$tool" ]; then
        echo "ERROR: missing build tool: $tool" >&2
        exit 1
    fi
done

export JAVA_HOME="$jdk"
export PATH="$jdk/bin:$PATH"

echo "SDK:         $sdk"
echo "build-tools: $(basename "$build_tools")"
echo "platform:    $(basename "$platform")"
echo "JDK:         $jdk"

app_dir="$root/app"
out="$root/build"
unsigned="$out/app-unsigned.apk"
aligned="$out/app-aligned.apk"
final="$root/PacketFlowDPI.apk"
keystore="$root/debug.keystore"

rm -rf "$out"
mkdir -p "$out/classes" "$out/dex"

echo "[1/6] aapt2 compile + link"
"$aapt2" compile --dir "$app_dir/res" -o "$out/res.zip"
"$aapt2" link -o "$unsigned" -I "$android_jar" \
    --manifest "$app_dir/AndroidManifest.xml" "$out/res.zip" \
    --min-sdk-version 30 --target-sdk-version 33 \
    --version-code 1 --version-name "1.0" --auto-add-overlay

echo "[2/6] javac"
sources=()
while IFS= read -r -d '' f; do sources+=("$f"); done \
    < <(find "$app_dir/java" -name '*.java' -print0)
if [ "${#sources[@]}" -eq 0 ]; then
    echo "ERROR: no Java sources under $app_dir/java" >&2
    exit 1
fi
"$javac" -source 11 -target 11 -encoding UTF-8 -Xlint:-options \
    -classpath "$android_jar" -d "$out/classes" "${sources[@]}"

echo "[3/6] d8"
classes=()
while IFS= read -r -d '' f; do classes+=("$f"); done \
    < <(find "$out/classes" -name '*.class' -print0)
"$d8" --lib "$android_jar" --min-api 30 --output "$out/dex" "${classes[@]}"

echo "[4/6] package"
(cd "$out/dex" && zip -q "$unsigned" classes.dex)

echo "[5/6] zipalign"
"$zipalign" -f -p 4 "$unsigned" "$aligned"

echo "[6/6] sign"
if [ ! -f "$keystore" ]; then
    echo "generating debug.keystore"
    "$keytool" -genkeypair -keystore "$keystore" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

"$apksigner" sign --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$final" "$aligned"
"$apksigner" verify --print-certs "$final"

size="$(du -k "$final" | cut -f1)"
echo "Built: $final (${size} KB)"

if [ "${1:-}" = "--install" ]; then
    adb install -r -d "$final"
fi
