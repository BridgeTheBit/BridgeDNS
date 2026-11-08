#!/usr/bin/env bash
# Offline app packaging after SDK and native dependencies have been installed.
# Useful when Gradle artifact repositories are unavailable. No external Java dependencies.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_HOME:?Set ANDROID_HOME}"
SDK="$ANDROID_HOME/build-tools/35.0.0"
JAR="$ANDROID_HOME/platforms/android-35/android.jar"
ABIS="${NAVA_ABI:-arm64-v8a armeabi-v7a x86_64}"
KEYSTORE="${NAVA_SIGNING_KEYSTORE:-$ROOT/signing/development.keystore}"
KEY_ALIAS="${NAVA_KEY_ALIAS:-androiddebugkey}"
KEYSTORE_PASSWORD="${NAVA_KEYSTORE_PASSWORD:-android}"
KEY_PASSWORD="${NAVA_KEY_PASSWORD:-android}"
OUTPUT_NAME="${NAVA_OUTPUT_NAME:-BridgeDNS-universal-v0.2.1-debug.apk}"
VERSION_CODE="${NAVA_VERSION_CODE:-3}"
VERSION_NAME="${NAVA_VERSION_NAME:-0.2.1}"
if [[ "$KEYSTORE" == "$ROOT/signing/development.keystore" ]]; then mkdir -p "$ROOT/signing"; fi
OUT="$ROOT/app/build/sdk"; mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"
for abi in $ABIS; do
    for lib in masterdns hev-socks5-tunnel bridgedns; do test -f "$ROOT/app/src/main/jniLibs/$abi/lib$lib.so"; done
done
python3 "$ROOT/scripts/verify-native.py" $ABIS
"$SDK/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/resources.zip"
NAVA_VERSION_CODE="$VERSION_CODE" NAVA_VERSION_NAME="$VERSION_NAME" python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PY'
import os,sys,xml.etree.ElementTree as ET
ET.register_namespace('android','http://schemas.android.com/apk/res/android')
t=ET.parse(sys.argv[1]); r=t.getroot(); r.set('package','dev.bridgedns.vpn')
r.set('{http://schemas.android.com/apk/res/android}versionCode',os.environ['NAVA_VERSION_CODE']);r.set('{http://schemas.android.com/apk/res/android}versionName',os.environ['NAVA_VERSION_NAME'])
t.write(sys.argv[2],encoding='utf-8',xml_declaration=True)
PY
"$SDK/aapt2" link -o "$OUT/base.apk" -I "$JAR" -A "$ROOT/app/src/main/assets" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 35 "$OUT/resources.zip"
find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -classpath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$SDK/d8" --lib "$JAR" --min-api 26 --output "$OUT/dex" "$OUT/classes.jar"
NAVA_ABIS="$ABIS" python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$ROOT/app/src/main/jniLibs" <<'PY'
import os,sys,zipfile,pathlib
with zipfile.ZipFile(sys.argv[1],'a',compression=zipfile.ZIP_DEFLATED) as z:
 z.write(sys.argv[2],'classes.dex')
 for abi in os.environ['NAVA_ABIS'].split():
  for p in pathlib.Path(sys.argv[3],abi).glob('*.so'): z.write(p,'lib/'+abi+'/'+p.name)
PY
"$SDK/zipalign" -f -P 16 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [[ ! -f "$KEYSTORE" ]]; then keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$KEYSTORE_PASSWORD" -keypass "$KEY_PASSWORD" -alias "$KEY_ALIAS" -dname 'CN=BridgeDNS Development,O=BridgeDNS,C=US' -keyalg RSA -keysize 2048 -validity 10000; fi
"$SDK/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass pass:"$KEYSTORE_PASSWORD" --key-pass pass:"$KEY_PASSWORD" --out "$OUT/$OUTPUT_NAME" "$OUT/aligned.apk"
"$SDK/apksigner" verify --verbose "$OUT/$OUTPUT_NAME"
echo "APK: $OUT/$OUTPUT_NAME"
