#!/bin/bash
# Packs the app into a DMG with an Applications shortcut.
set -euo pipefail
APP="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$(mktemp -d)"
cp -R "$APP" "$STAGE/FS MediaPlayer.app"
ln -s /Applications "$STAGE/Applications"
DMG="$ROOT/build/FS-MediaPlayer.dmg"
rm -f "$DMG"
hdiutil create -volname "FS MediaPlayer" -srcfolder "$STAGE" -ov -format UDZO "$DMG"
rm -rf "$STAGE"
echo "DMG: $DMG"
