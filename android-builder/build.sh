#!/bin/sh
# Builds a signed release APK into /data/apk.
#   SURFER_APP=tablet (default): /src      -> surfer.apk      (Android 4.4+)
#   SURFER_APP=x10:              /src-x10  -> surfer-x10.apk  (Android 2.x)
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

[ $# -eq 0 ] && set -- assembleRelease
gradle --no-daemon --console=plain -p /work/project "$@"

APK=/work/project/app/build/outputs/apk/release/app-release.apk
if [ -f "$APK" ]; then
  mkdir -p /data/apk
  cp "$APK" "/data/apk/$OUT.tmp"
  mv "/data/apk/$OUT.tmp" "/data/apk/$OUT"
  echo "APK: /data/apk/$OUT  version $SURFER_VERSION_CODE  sha256 $(sha256sum "/data/apk/$OUT" | cut -d' ' -f1)"
fi
