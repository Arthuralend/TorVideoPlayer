#!/bin/bash
# Builds the Go torrent engine for Android. The binary is packaged as
# lib/<abi>/libtorengine.so so Android extracts it to nativeLibraryDir,
# the one place an app may execute its own binaries from.
#
# With the Android NDK every ABI is a real android/* binary linked against
# bionic (C++ runtime linked in statically): DNS goes through the system resolver and networking follows
# Android's rules (e.g. the netlink restrictions of Android 11+). This
# matters most for 32-bit TV boxes (Mi Box, Chromecast with Google TV).
# Without an NDK, a fallback builds static linux binaries for the ABIs that
# need cgo on Android (32-bit ARM, x86, x86_64).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/jniLibs"
API=26
cd "$ROOT/engine"

find_ndk() {
  for d in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${ANDROID_NDK:-}"; do
    [ -n "$d" ] && [ -d "$d/toolchains/llvm" ] && { echo "$d"; return; }
  done
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -n "$sdk" ] && [ -d "$sdk/ndk" ] || continue
    d=$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -1)
    [ -n "$d" ] && [ -d "$d/toolchains/llvm" ] && { echo "${d%/}"; return; }
  done
}

NDK="$(find_ndk || true)"
LDFLAGS="-checklinkname=0 -s -w"

ndk_build() { # abi goarch goarm clang-target
  local host
  host=$(ls "$NDK/toolchains/llvm/prebuilt/")
  mkdir -p "$OUT/$1"
  CGO_ENABLED=1 GOOS=android GOARCH=$2 GOARM=${3:-} \
    CC="$NDK/toolchains/llvm/prebuilt/$host/bin/$4$API-clang" \
    CXX="$NDK/toolchains/llvm/prebuilt/$host/bin/$4$API-clang++" \
    go build -trimpath -ldflags="$LDFLAGS -extldflags=-static-libstdc++" -o "$OUT/$1/libtorengine.so" .
  echo "Built $OUT/$1/libtorengine.so (android, NDK)"
}

static_build() { # abi goarch goarm goos
  mkdir -p "$OUT/$1"
  CGO_ENABLED=0 GOOS=$4 GOARCH=$2 GOARM=${3:-} go build -trimpath -ldflags="$LDFLAGS" -o "$OUT/$1/libtorengine.so" .
  echo "Built $OUT/$1/libtorengine.so ($4, static)"
}

if [ -n "$NDK" ]; then
  echo "Using NDK: $NDK"
  ndk_build arm64-v8a arm64 "" aarch64-linux-android
  ndk_build armeabi-v7a arm 7 armv7a-linux-androideabi
  ndk_build x86_64 amd64 "" x86_64-linux-android
  # Only for the universal APK (x86 Android TV emulators, rare Intel TV boxes).
  ndk_build x86 386 "" i686-linux-android
elif [ "${REQUIRE_NDK:-}" = 1 ]; then
  echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2
  exit 1
else
  echo "WARNING: Android NDK not found; 32-bit ARM and x86 engines will be static linux binaries." >&2
  static_build arm64-v8a arm64 "" android
  static_build armeabi-v7a arm 7 linux
  static_build x86_64 amd64 "" linux
  static_build x86 386 "" linux
fi
