#!/usr/bin/env bash
# Creates a release signing key + keystore.properties (both git-ignored).
set -euo pipefail
cd "$(dirname "$0")/.."

read -r -s -p "Choose a keystore password: " STOREPASS; echo
[ -n "$STOREPASS" ] || { echo "Password cannot be empty."; exit 1; }

keytool -genkeypair -v \
  -keystore arena-release.jks \
  -alias arena \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$STOREPASS" -keypass "$STOREPASS" \
  -dname "CN=Arena AI, OU=Mobile, O=Arena, L=Internet, C=IN"

cat > keystore.properties <<EOF
storeFile=arena-release.jks
storePassword=$STOREPASS
keyAlias=arena
keyPassword=$STOREPASS
EOF

echo
echo "[OK] arena-release.jks + keystore.properties created. Now run ./build.sh release"
