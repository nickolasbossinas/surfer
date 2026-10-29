#!/bin/sh
# Builds the signed release APK into /data/apk/surfer.apk.
# Mounts: /src (android project, read-only), /data (server volume),
#         /keystore (signing key, kept between builds), ~/.gradle (cache).
set -eu

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
cp -r /src /work/project
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
  cp "$APK" /data/apk/surfer.apk.tmp
  mv /data/apk/surfer.apk.tmp /data/apk/surfer.apk
  echo "APK: /data/apk/surfer.apk  version $SURFER_VERSION_CODE  sha256 $(sha256sum /data/apk/surfer.apk | cut -d' ' -f1)"
fi
