#!/bin/bash
# Packs the app into a DMG with an Applications shortcut.
set -euo pipefail
APP="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$(mktemp -d)"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
DMG="$ROOT/build/TorVideoPlayer.dmg"
rm -f "$DMG"
hdiutil create -volname "TorVideoPlayer" -srcfolder "$STAGE" -ov -format UDZO "$DMG"
rm -rf "$STAGE"
echo "DMG: $DMG"
