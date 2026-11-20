#!/bin/bash
# Downloads TorrServer-LT (https://github.com/trinity-aml/TorrServer-LT,
# GPL-3.0) for macOS and makes a universal binary for the experimental
# "FS MediaPlayer LT" build. Set TORRSERVER_LT_VERSION to pin a release tag.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/engine"
mkdir -p "$OUT"
REPO="https://github.com/trinity-aml/TorrServer-LT/releases"
if [ -n "${TORRSERVER_LT_VERSION:-}" ]; then BASE="$REPO/download/$TORRSERVER_LT_VERSION"; else BASE="$REPO/latest/download"; fi
for arch in arm64 amd64; do
  curl -fSL --retry 3 -o "$OUT/torrserver-lt-$arch" "$BASE/TorrServer-LT-darwin-$arch"
done
lipo -create -output "$OUT/torrserver-lt" "$OUT/torrserver-lt-arm64" "$OUT/torrserver-lt-amd64"
rm "$OUT/torrserver-lt-arm64" "$OUT/torrserver-lt-amd64"
chmod +x "$OUT/torrserver-lt"
codesign --force --sign - "$OUT/torrserver-lt"
"$OUT/torrserver-lt" --version || true
echo "Built $OUT/torrserver-lt"
