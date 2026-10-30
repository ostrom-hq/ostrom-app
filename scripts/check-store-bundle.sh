#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Proves that a store build never carries the in-memory server of src/api/mocks (only the E2E build does, through
# EXPO_PUBLIC_OSTROM_MOCK=1 and metro.config.js). It exports the production bundles as a store build makes them, and
# fails on any trace of the mock: msw, the build flag, the mock's names, its strings. Then it exports the E2E bundle
# and requires the mock's strings there: a search that finds nothing anywhere would prove nothing.
set -euo pipefail

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

# Strings only the mock server and its fixtures hold: a minifier keeps string literals, never local names.
strings=("ost_mock_token" "No such route" "Name the organization with the Ostrom-Org header" "Deploy acme-api to production")
# Names and words that must not appear either, whether or not the minifier keeps them.
words=("msw" "EXPO_PUBLIC_OSTROM_MOCK" "createMockServer" "createMockApi" "createMockAppServices" "mockFetch")

# Only the JavaScript bundles (Hermes bytecode or JS): the fonts and images next to them could hold "msw" by chance.
traces() {
    local dir="$1/_expo/static/js" found=0
    for text in "${strings[@]}"; do
        if grep -rqaF -- "$text" "$dir"; then echo "  found: $text"; found=1; fi
    done
    for word in "${words[@]}"; do
        if grep -rqaw -- "$word" "$dir"; then echo "  found: $word"; found=1; fi
    done
    return "$found"
}

echo "Store build (production, Android and iOS):"
# The bundles must exist where the search looks, or it would find nothing for the wrong reason.
env -u EXPO_PUBLIC_OSTROM_MOCK npx expo export --platform android --platform ios --output-dir "$out/store" > /dev/null
test -n "$(find "$out/store/_expo/static/js" -type f -print -quit)"
if ! traces "$out/store"; then
    echo "::error::The store bundle carries the in-memory server of src/api/mocks"
    exit 1
fi
echo "  no trace of the mock server"

echo "E2E build (EXPO_PUBLIC_OSTROM_MOCK=1, Android), the control:"
EXPO_PUBLIC_OSTROM_MOCK=1 npx expo export --platform android --output-dir "$out/e2e" > /dev/null
for text in "${strings[@]}"; do
    if ! grep -rqaF -- "$text" "$out/e2e/_expo/static/js"; then
        echo "::error::The control found no \"$text\" in the E2E bundle: the search proves nothing"
        exit 1
    fi
done
echo "  the mock server's strings are there: the search sees them"
