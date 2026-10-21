#!/bin/bash
# Builds the Go torrent engine as a universal (arm64 + x86_64) macOS binary.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/engine"
mkdir -p "$OUT"
cd "$ROOT/engine"
for arch in arm64 amd64; do
  CGO_ENABLED=0 GOOS=darwin GOARCH=$arch go build -trimpath -ldflags="-s -w" -o "$OUT/torengine-$arch" .
done
lipo -create -output "$OUT/torengine" "$OUT/torengine-arm64" "$OUT/torengine-amd64"
rm "$OUT/torengine-arm64" "$OUT/torengine-amd64"
codesign --force --sign - "$OUT/torengine"
echo "Built $OUT/torengine"
