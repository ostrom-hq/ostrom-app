#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Notarizes, staples and checks the signed macOS app and disk image of the desktop release (release-desktop.yml).
# Ported from TeamCode's build.yml. Expects BINARIES (companion-desktop/build/compose/binaries/main), RUNNER_TEMP,
# NOTARY_KEY_ID, NOTARY_ISSUER_ID, TEAM_ID, OSTROM_MACOS_SIGNING_IDENTITY, OSTROM_MACOS_SIGNING_KEYCHAIN, and the
# notarization key at $RUNNER_TEMP/notary-key.p8.
set -euo pipefail
app="$BINARIES/app/Ostrom.app"
dmg="$(ls "$BINARIES"/dmg/*.dmg)"
# Every call to Apple's services (notarytool, stapler's CloudKit lookups) goes through apple(): up to 6 tries
# with 15, 30, 60, 120 and 240 s between them, and only when the failure is a transient network or service one
# (a lost connection, CloudKit, stapler's error 68, an HTTP 5xx). Anything else fails at once. A timeout is
# NOT in that list for notarytool: a timed-out submission may still be running at Apple, so it is never sent
# again; its status is read instead (notarize() below). Stapler's calls are idempotent, so a timeout there is
# retried ($apple_failure_stapler). Output is left in $apple_out (stdout) and $apple_err (stderr).
apple_failure='network connection was lost|NSURLErrorDomain|could not connect|CloudKit|Error 68|offline|temporarily unavailable|Service Unavailable|HTTP[^0-9]*5[0-9][0-9]|internal error|connection (reset|refused)'
apple_failure_stapler="$apple_failure|timed out|timeout"
apple_retry="$apple_failure"
apple_out="$RUNNER_TEMP/apple.out"
apple_err="$RUNNER_TEMP/apple.err"
apple() {
  local attempt status delay
  for attempt in 1 2 3 4 5 6; do
    status=0
    "$@" > "$apple_out" 2> "$apple_err" || status=$?
    cat "$apple_out"
    cat "$apple_err" >&2
    if [ "$status" -eq 0 ]; then return 0; fi
    if ! cat "$apple_out" "$apple_err" | grep -qiE "$apple_retry"; then
      return "$status"
    fi
    if [ "$attempt" -eq 6 ]; then break; fi
    delay=$((15 * 2 ** (attempt - 1)))
    if [ "$delay" -gt 240 ]; then delay=240; fi
    echo "::warning::Apple service failure (try $attempt of 6): $* ; trying again in ${delay}s"
    sleep "$delay"
  done
  return "$status"
}
notary=(--key "$RUNNER_TEMP/notary-key.p8" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER_ID")
json_field() {
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1" < "$apple_out" 2>/dev/null || true
}
# Submits once (--no-wait), waits for the verdict, then reads the status of that submission. The submission is only
# sent again when the call failed before Apple gave it an id and the failure is a network one; a wait that times out
# never resubmits: the status is read and, still In Progress, the release fails with the id to look at.
notarize() {
  local id status
  apple xcrun notarytool submit "$1" "${notary[@]}" --no-wait --output-format json || true
  id="$(json_field id)"
  if [ -z "$id" ]; then
    echo "::error::Notarization of $1 was not accepted for submission."
    return 1
  fi
  echo "Notarization submission $id"
  # A rejection (Invalid) is a finished submission, not a failure of the call: it is not retried either.
  apple xcrun notarytool wait "$id" "${notary[@]}" --timeout 30m --output-format json || true
  apple xcrun notarytool info "$id" "${notary[@]}" --output-format json || true
  status="$(json_field status)"
  echo "Notarization $id ${status:-no status}"
  if [ "$status" != Accepted ]; then
    if [ "$status" = "In Progress" ]; then
      echo "::error::Notarization $id is still in progress after the wait: not submitted again. Check it with notarytool info, then run the release again."
    elif [ -n "$status" ]; then
      apple xcrun notarytool log "$id" "${notary[@]}" || true
    fi
    return 1
  fi
}
# The ticket is stapled, then validated: each retried on its own, so a lost connection while validating does
# not staple again. A failed `stapler staple` always fails the release. `stapler validate` looks the ticket up
# in CloudKit, which macOS runners sometimes cannot reach (the dropped Intel ones most of the time: actions/runner-images#14924; Apple DTS
# advises retrying: https://developer.apple.com/forums/thread/824494). When it still fails after its retries
# with a network-only error, the offline gate below replaces it; any other failure still fails.
offline_gate() {
  local artifact="$1" kind="$2" details
  details="$(codesign -dvv "$artifact" 2>&1)" || { echo "::error::Offline gate: cannot read the signature of $artifact."; return 1; }
  codesign --verify --deep --strict --verbose=2 "$artifact" || return 1
  grep -q '^Authority=Developer ID Application: ' <<< "$details" || { echo "::error::Offline gate: $artifact is not signed by a Developer ID Application."; return 1; }
  grep -qx "TeamIdentifier=$TEAM_ID" <<< "$details" || { echo "::error::Offline gate: $artifact is not signed by team $TEAM_ID."; return 1; }
  if [ "$kind" = app ]; then
    grep -q 'flags=.*runtime' <<< "$details" || { echo "::error::Offline gate: $artifact lacks the hardened runtime."; return 1; }
    # `stapler staple` writes the ticket at Contents/CodeResources of the bundle.
    [ -s "$artifact/Contents/CodeResources" ] || { echo "::error::Offline gate: no stapled ticket in $artifact."; return 1; }
    spctl --assess --type execute --verbose=2 "$artifact" || return 1
  else
    # The ticket is appended after the signed image: the file must have grown, its signed part untouched.
    local before_size
    before_size="$(cat "$RUNNER_TEMP/dmg-before-staple.size")"
    [ "$(stat -f %z "$artifact")" -gt "$before_size" ] || { echo "::error::Offline gate: no stapled ticket in $artifact."; return 1; }
    cmp -n "$before_size" "$artifact" "$RUNNER_TEMP/dmg-before-staple.dmg" || { echo "::error::Offline gate: the signed image changed while stapling."; return 1; }
    spctl --assess --type open --context context:primary-signature --verbose=2 "$artifact" || return 1
  fi
}
staple() {
  local apple_retry="$apple_failure_stapler"
  apple xcrun stapler staple "$1" || return $?
  local status=0
  apple xcrun stapler validate "$1" && return 0 || status=$?
  if ! cat "$apple_out" "$apple_err" | grep -qiE "$apple_failure_stapler"; then return "$status"; fi
  echo "::warning::stapler validate could not reach Apple's CloudKit after 6 tries; checked $1 with the offline gate instead (signature, Developer ID, stapled ticket, spctl)."
  offline_gate "$1" "$2"
}
codesign --verify --deep --strict --verbose=2 "$app"
codesign -dv "$app" 2>&1 | grep -qx "TeamIdentifier=$TEAM_ID"
ditto -c -k --keepParent "$app" "$RUNNER_TEMP/notarize-app.zip"
notarize "$RUNNER_TEMP/notarize-app.zip"
staple "$app" app
spctl --assess --type execute --verbose=2 "$app"
# The disk image carries its own signature, then its notarization ticket.
codesign --force --timestamp --keychain "$OSTROM_MACOS_SIGNING_KEYCHAIN" --sign "Developer ID Application: $OSTROM_MACOS_SIGNING_IDENTITY" "$dmg"
notarize "$dmg"
cp "$dmg" "$RUNNER_TEMP/dmg-before-staple.dmg"
stat -f %z "$dmg" > "$RUNNER_TEMP/dmg-before-staple.size"
staple "$dmg" dmg
spctl --assess --type open --context context:primary-signature --verbose=2 "$dmg"
