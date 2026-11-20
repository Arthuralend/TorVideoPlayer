#!/bin/bash
# Downloads TorrServer-LT (https://github.com/trinity-aml/TorrServer-LT,
# GPL-3.0) for Android into the "lt" flavor as lib<abi>/libtorrserverlt.so,
# for the experimental FS MediaPlayer LT. Set TORRSERVER_LT_VERSION to pin
# a release tag. Only ARM builds exist (phones, Mi Box and other TV boxes).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/lt/jniLibs"
REPO="https://github.com/trinity-aml/TorrServer-LT/releases"
if [ -n "${TORRSERVER_LT_VERSION:-}" ]; then BASE="$REPO/download/$TORRSERVER_LT_VERSION"; else BASE="$REPO/latest/download"; fi
fetch() { # abi asset
  mkdir -p "$OUT/$1"
  curl -fSL --retry 3 -o "$OUT/$1/libtorrserverlt.so" "$BASE/TorrServer-LT-android-$2"
  chmod +x "$OUT/$1/libtorrserverlt.so"
  echo "Fetched $OUT/$1/libtorrserverlt.so"
}
fetch armeabi-v7a armv7
fetch arm64-v8a arm64
