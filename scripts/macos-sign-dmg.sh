#!/bin/bash
# Signs, notarizes and staples a DMG when a Developer ID identity is set up
# (scripts/macos-sign.sh exports MACOS_SIGN_IDENTITY); otherwise does nothing.
set -euo pipefail
DMG="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${MACOS_SIGN_IDENTITY:-}" ] && [ -n "${MACOS_CERT_P12:-}" ]; then
  # Same job, same step as scripts/macos-sign.sh: its keychain is in the search list.
  MACOS_SIGN_IDENTITY="$(security find-identity -v -p codesigning | grep "Developer ID Application" | head -1 | sed -E 's/.*"(.*)"/\1/' || true)"
fi
if [ -z "${MACOS_SIGN_IDENTITY:-}" ]; then
  echo "DMG left unsigned (no Developer ID certificate)."
  exit 0
fi
codesign --force --timestamp --sign "$MACOS_SIGN_IDENTITY" "$DMG"
if [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_APP_PASSWORD:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ]; then
  "$ROOT/scripts/macos-notarize.sh" "$DMG"
  xcrun stapler staple "$DMG"
  spctl --assess --type open --context context:primary-signature --verbose=2 "$DMG"
fi
