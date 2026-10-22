#!/bin/bash
# Builds Bambu Widget APK with Ubuntu's Android tools (aapt, dx, apksigner).
set -euo pipefail
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
[ -f "$SDK" ] || SDK=$(ls /usr/lib/android-sdk/platforms/*/android.jar | head -1)
rm -rf build && mkdir -p build/gen build/classes build/dex
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$SDK" -F build/unsigned.apk
javac -nowarn --release 8 -encoding UTF-8 -cp "$SDK" -d build/classes $(find src build/gen -name '*.java') 2>&1 | grep -v "bootstrap class path\|^1 warning\|^warning" || true
[ -f build/classes/sk/bambuwidget/MainActivity.class ] || { echo "javac failed"; exit 1; }
dalvik-exchange --dex --min-sdk-version=23 --output=build/dex/classes.dex build/classes
(cd build/dex && aapt add ../unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
# Signing key: keep release.keystore and its password private (they are not in the repository).
# Updates install over an existing app only when signed with the same key.
: "${BW_KEYSTORE:=release.keystore}"
: "${BW_KS_PASS:?Set BW_KS_PASS to the keystore password}"
if [ ! -f "$BW_KEYSTORE" ]; then
  echo "Creating a new signing key in $BW_KEYSTORE"
  keytool -genkeypair -keystore "$BW_KEYSTORE" -storepass "$BW_KS_PASS" -keypass "$BW_KS_PASS" -alias bw \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Bambu Widget" >/dev/null 2>&1
fi
apksigner sign --ks "$BW_KEYSTORE" --ks-pass env:BW_KS_PASS --key-pass env:BW_KS_PASS --ks-key-alias bw \
  --out build/BambuWidget.apk build/aligned.apk
apksigner verify --print-certs build/BambuWidget.apk | head -2
ls -la build/BambuWidget.apk
