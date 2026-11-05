#!/bin/bash
# Builds the Go torrent engine for Windows x64 (works on any OS with Go).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/engine-windows"
mkdir -p "$OUT"
cd "$ROOT/engine"
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o "$OUT/torengine.exe" .
echo "Built $OUT/torengine.exe"
