#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# What a store build is given besides its JavaScript, checked on every pull request (AUDIT-SECU-APP):
# - every action of the workflows is pinned by its commit SHA, never a tag a third party can move;
# - the native configuration `expo prebuild` makes from app.json, as a store build gets it: on Android, no permission
#   outside the list the app uses, no clear text, not debuggable; on iOS, no App Transport Security exception beyond
#   Expo's local networking (the app refuses HTTP itself in a store build, `normalizeServerUrl`).
# The debug manifest (clear text for Metro, the dev menu's overlay) is not a store build's and is not read.
set -euo pipefail

failed=0
fail() {
    echo "::error::$1"
    failed=1
}

# Actions pinned by SHA.
while IFS= read -r line; do
    fail "Action not pinned by a commit SHA: $line"
done < <(grep -nE '^\s*(-\s+)?uses:' .github/workflows/*.yml | grep -vE 'uses:\s*[^@[:space:]]+@[0-9a-f]{40}(\s|$)' || true)

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
cp -R app.json package.json package-lock.json tsconfig.json "$out/" 2> /dev/null
ln -s "$PWD/node_modules" "$out/node_modules"
(cd "$out" && npx expo prebuild --platform all --no-install > /dev/null)

# Android, the main manifest: the release build's own entries (libraries add theirs at merge time).
manifest="$out/android/app/src/main/AndroidManifest.xml"
allowed=(INTERNET USE_BIOMETRIC USE_FINGERPRINT VIBRATE POST_NOTIFICATIONS)
while IFS= read -r permission; do
    name="${permission##*.}"
    if [[ " ${allowed[*]} " != *" $name "* ]]; then fail "Android release asks for $permission, which the app does not use"; fi
done < <(grep -o '<uses-permission android:name="[^"]*"[^>]*>' "$manifest" | grep -v 'tools:node="remove"' | sed -E 's/.*android:name="([^"]*)".*/\1/')
if grep -q 'usesCleartextTraffic="true"' "$manifest"; then fail "Android release allows clear text"; fi
if grep -q 'debuggable="true"' "$manifest"; then fail "Android release is debuggable"; fi

# iOS: no arbitrary loads, no per-domain exception.
plist="$(find "$out/ios" -maxdepth 2 -name Info.plist | head -n 1)"
if [ -z "$plist" ]; then fail "No Info.plist after prebuild: the iOS checks would prove nothing"; fi
if tr -d '[:space:]' < "$plist" | grep -q '<key>NSAllowsArbitraryLoads</key><true/>'; then fail "iOS allows arbitrary loads"; fi
for key in NSExceptionDomains NSAllowsArbitraryLoadsInWebContent NSAllowsArbitraryLoadsForMedia; do
    if grep -q "<key>$key</key>" "$plist"; then fail "iOS App Transport Security has $key"; fi
done

if [ "$failed" -ne 0 ]; then exit 1; fi
echo "Release configuration: actions pinned; Android asks only for ${allowed[*]}, no clear text, not debuggable; iOS without ATS exception"
