#!/bin/sh
# Builds a signed release APK into /data/apk.
#   SURFER_APP=tablet (default): /src      -> surfer.apk      (Android 4.4+)
#   SURFER_APP=x10:              /src-x10  -> surfer-x10.apk  (Android 1.6 to 4.3)
# Mounts: /src, /src-x10 (app projects, read-only), /data (server volume),
#         /keystore (signing key, kept between builds), ~/.gradle (cache).
set -eu

case "${SURFER_APP:-tablet}" in
  tablet) SRC=/src; OUT=surfer.apk ;;
  x10) SRC=/src-x10; OUT=surfer-x10.apk; export SURFER_DEFAULT_PORT="${SURFER_LEGACY_DEFAULT_PORT:-8688}" ;;
  *) echo "error: SURFER_APP must be tablet or x10" >&2; exit 1 ;;
esac

CERT=/data/tls/server.crt
if [ ! -f "$CERT" ]; then
  echo "error: $CERT not found. Start the server once (docker compose up -d) so it creates its certificate." >&2
  exit 1
fi

# Signing key: created once and reused, otherwise updates would not install over the old app.
KEYSTORE=/keystore/surfer.jks
PASSFILE=/keystore/password
if [ ! -f "$KEYSTORE" ]; then
  echo "creating signing key"
  umask 077
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$PASSFILE"
  keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storetype PKCS12 -alias surfer \
    -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Surfer" \
    -storepass "$(cat "$PASSFILE")" -keypass "$(cat "$PASSFILE")"
fi

# Build from a writable copy; the source mount is read-only.
rm -rf /work/project
cp -r "$SRC" /work/project
rm -rf /work/project/app/build /work/project/build /work/project/.gradle
mkdir -p /work/project/app/src/main/res/raw
cp "$CERT" /work/project/app/src/main/res/raw/server.crt

export SURFER_KEYSTORE="$KEYSTORE"
export SURFER_KEYSTORE_PASSWORD="$(cat "$PASSFILE")"
export SURFER_VERSION_CODE="$(( $(date +%s) / 60 ))"

# The X10 app also runs full lint: its API-level check is what keeps it working on old Android.
if [ $# -eq 0 ]; then
  if [ "$OUT" = surfer-x10.apk ]; then set -- lintRelease assembleRelease; else set -- assembleRelease; fi
fi
gradle --no-daemon --console=plain -p /work/project "$@"

APK=/work/project/app/build/outputs/apk/release/app-release.apk
if [ -f "$APK" ] && [ "$OUT" = surfer-x10.apk ]; then
  # Android 2.1 cannot read aapt2's UTF-8 string pools ("parse error"): rewrite
  # them as UTF-16, then sign again (v1 only, the scheme Android 2.x checks).
  BT="$ANDROID_HOME/build-tools/36.0.0"
  java /usr/local/lib/surfer/Utf16Pools.java "$APK" /work/utf16.apk
  "$BT/zipalign" -f -p 4 /work/utf16.apk /work/aligned.apk
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-type PKCS12 --ks-key-alias surfer --ks-pass "file:$PASSFILE"     --min-sdk-version 4 --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false     --out "$APK" /work/aligned.apk
  rm -f /work/utf16.apk /work/aligned.apk
fi
if [ -f "$APK" ]; then
  mkdir -p /data/apk
  cp "$APK" "/data/apk/$OUT.tmp"
  mv "/data/apk/$OUT.tmp" "/data/apk/$OUT"
  echo "APK: /data/apk/$OUT  version $SURFER_VERSION_CODE  sha256 $(sha256sum "/data/apk/$OUT" | cut -d' ' -f1)"
fi
