#!/usr/bin/env bash
# Checks the design rules, then builds release APKs and copies them to ~/Desktop/APKs (never loose on the Desktop).
#   phone = arm64-v8a, tv = armeabi-v7a (the user's TV is 32-bit ARM)
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

# The user's design rules first (CLAUDE.md "Design rules"): a new violation stops the release.
if ! tool/check_design.sh --if-changed; then
    echo "Release stopped: design rules failed (see above)." >&2
    exit 1
fi

./gradlew :core:test :app:assembleRelease --console=plain -q

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
out="$HOME/Desktop/APKs"
mkdir -p "$out"
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk "$out/OneStream-$version-phone.apk"
cp app/build/outputs/apk/release/app-armeabi-v7a-release.apk "$out/OneStream-$version-tv.apk"

# Official key: Gradle signs with the debug key, then this re-signs with One Stream's own key plus the
# debug → release rotation record, so phones and the TV take it as a normal update (data kept) and Play
# Protect sees a real signer. Key, password and record live in ~/.one-stream-signing (never in git).
sign="$HOME/.one-stream-signing/signing.properties"
if [ ! -f "$sign" ]; then
    echo "Release stopped: $sign missing (the One Stream signing key folder; restore it from backup)." >&2
    exit 1
fi
prop() { sed -n "s/^$1=//p" "$sign"; }
bt=$(ls -d "$HOME"/Library/Android/sdk/build-tools/* | tail -1)
for apk in "$out/OneStream-$version-phone.apk" "$out/OneStream-$version-tv.apk"; do
    "$bt/apksigner" sign \
        --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey \
        --next-signer --ks "$(prop storeFile)" --ks-pass "pass:$(prop storePassword)" \
        --ks-key-alias "$(prop keyAlias)" --key-pass "pass:$(prop keyPassword)" \
        --lineage "$(prop lineage)" --rotation-min-sdk-version 28 \
        --in "$apk" --out "$apk.signed"
    mv "$apk.signed" "$apk"
    rm -f "$apk.signed.idsig" "$apk.idsig"
done
ls -lh "$out"/OneStream-"$version"-*.apk
# The decoder for this version's short names: report stacks are translated with it on the Mac.
mkdir -p mappings
cp app/build/outputs/mapping/release/mapping.txt "mappings/OneStream-$version.txt"
