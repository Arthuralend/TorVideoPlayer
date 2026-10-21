#!/bin/bash
# Full local build on a Mac: engine + Xcode project + app + DMG.
# Requirements: Xcode, Go (brew install go), XcodeGen (brew install xcodegen),
# CocoaPods (brew install cocoapods).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

./scripts/build-engine.sh
xcodegen generate
pod install

xcodebuild \
  -workspace TorVideoPlayer.xcworkspace \
  -scheme TorVideoPlayer \
  -configuration Release \
  -derivedDataPath build/DerivedData \
  ARCHS="arm64 x86_64" ONLY_ACTIVE_ARCH=NO \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO \
  build

APP="build/DerivedData/Build/Products/Release/TorVideoPlayer.app"
codesign --force --deep --sign - "$APP"
rm -rf build/TorVideoPlayer.app
cp -R "$APP" build/TorVideoPlayer.app
echo "App: $ROOT/build/TorVideoPlayer.app"

./scripts/make-dmg.sh build/TorVideoPlayer.app
