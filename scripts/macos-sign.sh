#!/bin/bash
# Signs a built .app for distribution outside the App Store.
#
# With a Developer ID certificate (MACOS_CERT_P12 = base64 of the .p12,
# MACOS_CERT_PASSWORD) the app and everything inside it are signed with the
# hardened runtime. With APPLE_ID, APPLE_APP_PASSWORD (app-specific password)
# and APPLE_TEAM_ID the app is also notarized by Apple and the ticket stapled,
# so it opens without Gatekeeper warnings.
# Without a certificate it falls back to ad-hoc signing (as before).
set -euo pipefail
APP="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ -z "${MACOS_CERT_P12:-}" ]; then
  echo "No Developer ID certificate: ad-hoc signing."
  codesign --force --deep --sign - "$APP"
  exit 0
fi

TMP="$(mktemp -d)"
KEYCHAIN="$TMP/signing.keychain-db"
KPASS="$(uuidgen)"
security create-keychain -p "$KPASS" "$KEYCHAIN"
security set-keychain-settings -lut 21600 "$KEYCHAIN"
security unlock-keychain -p "$KPASS" "$KEYCHAIN"
echo "$MACOS_CERT_P12" | base64 --decode > "$TMP/cert.p12"
security import "$TMP/cert.p12" -k "$KEYCHAIN" -P "${MACOS_CERT_PASSWORD:-}" -T /usr/bin/codesign -T /usr/bin/security
rm -f "$TMP/cert.p12"
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KPASS" "$KEYCHAIN" > /dev/null
# shellcheck disable=SC2046
security list-keychains -d user -s "$KEYCHAIN" $(security list-keychains -d user | tr -d '"')
IDENTITY="$(security find-identity -v -p codesigning "$KEYCHAIN" | grep "Developer ID Application" | head -1 | sed -E 's/.*"(.*)"/\1/')"
if [ -z "$IDENTITY" ]; then
  echo "No 'Developer ID Application' identity in the certificate." >&2
  exit 1
fi
echo "Signing with: $IDENTITY"
# Later steps (DMG) reuse the identity from the same keychain.
[ -n "${GITHUB_ENV:-}" ] && echo "MACOS_SIGN_IDENTITY=$IDENTITY" >> "$GITHUB_ENV"

ENT="$ROOT/App/Release.entitlements"
sign() { codesign --force --timestamp --options runtime --sign "$IDENTITY" "$@"; }

# Inside out: loose libraries, frameworks, helper executables, then the app.
find "$APP/Contents" -type f \( -name "*.dylib" -o -name "*.so" \) -print0 | while IFS= read -r -d '' lib; do sign "$lib"; done
for fw in "$APP"/Contents/Frameworks/*.framework; do [ -e "$fw" ] && sign "$fw"; done
MAIN="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Contents/Info.plist")"
for exe in "$APP"/Contents/MacOS/*; do
  [ "$(basename "$exe")" = "$MAIN" ] && continue
  sign --entitlements "$ENT" "$exe"
done
sign --entitlements "$ENT" "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

if [ -z "${APPLE_ID:-}" ] || [ -z "${APPLE_APP_PASSWORD:-}" ] || [ -z "${APPLE_TEAM_ID:-}" ]; then
  echo "APPLE_ID / APPLE_APP_PASSWORD / APPLE_TEAM_ID not set: signed, not notarized."
  exit 0
fi
ditto -c -k --keepParent "$APP" "$TMP/app.zip"
"$ROOT/scripts/macos-notarize.sh" "$TMP/app.zip"
xcrun stapler staple "$APP"
spctl --assess --type execute --verbose=2 "$APP"
rm -rf "$TMP/app.zip"
