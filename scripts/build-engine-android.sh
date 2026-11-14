#!/bin/bash
# Builds the Go torrent engine for Android. The binary is packaged as
# lib/<abi>/libtorengine.so so Android extracts it to nativeLibraryDir,
# the one place an app may execute its own binaries from.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/jniLibs"
cd "$ROOT/engine"
# android/arm needs cgo, so 32-bit ARM uses a static linux binary (runs on
# Android as is; DNS falls back to public servers, see engine/dns.go).
build() {
  mkdir -p "$OUT/$1"
  CGO_ENABLED=0 GOOS=$4 GOARCH=$2 GOARM=${3:-} go build -trimpath -ldflags="-checklinkname=0 -s -w" -o "$OUT/$1/libtorengine.so" .
  echo "Built $OUT/$1/libtorengine.so"
}
build arm64-v8a arm64 "" android
build armeabi-v7a arm 7 linux
build x86_64 amd64 "" linux
