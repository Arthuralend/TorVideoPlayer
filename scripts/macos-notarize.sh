#!/bin/bash
# Submits a .zip or .dmg to Apple's notary service and waits for the result.
# Needs APPLE_ID, APPLE_APP_PASSWORD (app-specific password) and APPLE_TEAM_ID.
set -euo pipefail
FILE="$1"
AUTH=(--apple-id "$APPLE_ID" --password "$APPLE_APP_PASSWORD" --team-id "$APPLE_TEAM_ID")
OUT="$(xcrun notarytool submit "$FILE" "${AUTH[@]}" --wait --timeout 45m --output-format json)"
echo "$OUT"
ID="$(echo "$OUT" | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))')"
STATUS="$(echo "$OUT" | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))')"
if [ "$STATUS" != "Accepted" ]; then
  echo "Notarization failed: $STATUS" >&2
  [ -n "$ID" ] && xcrun notarytool log "$ID" "${AUTH[@]}" || true
  exit 1
fi
echo "Notarized: $FILE"
