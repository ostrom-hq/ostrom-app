#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Proves that a store build never carries the in-memory server of src/api/mocks (only the E2E build does, through
# EXPO_PUBLIC_OSTROM_MOCK=1 and metro.config.js). It first exports the E2E bundles, the control: the mock's strings
# must be found there, on each platform, or the search would prove nothing. Then it exports the production bundles as
# a store build makes them, and fails on any trace of the mock on either platform.
set -euo pipefail

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
platforms=(android ios)

# Strings only the mock server and its fixtures hold: a minifier keeps string literals.
strings=("ost_mock_token" "No such route" "Name the organization with the Ostrom-Org header" "Deploy acme-api to production")
# Names and words of the mock, always searched in the store bundles too. A minifier may drop a name: the control says
# which ones the E2E bundles keep, so the reader knows which of these searches can find something.
words=("msw" "EXPO_PUBLIC_OSTROM_MOCK" "createMockServer" "createMockApi" "createMockAppServices" "mockFetch")

# The JavaScript bundles of one platform (Hermes bytecode or JS), never the fonts and images next to them.
bundles() {
    local dir="$1/_expo/static/js/$2"
    if [ -z "$(find "$dir" -type f -print -quit 2> /dev/null)" ]; then
        echo "::error::No $2 bundle under $dir: the export changed, and a search there would find nothing" >&2
        exit 1
    fi
    echo "$dir"
}

echo "E2E build (EXPO_PUBLIC_OSTROM_MOCK=1), the control:"
EXPO_PUBLIC_OSTROM_MOCK=1 npx expo export --platform android --platform ios --output-dir "$out/e2e" > /dev/null
for platform in "${platforms[@]}"; do
    dir="$(bundles "$out/e2e" "$platform")"
    for text in "${strings[@]}"; do
        if ! grep -rqaF -- "$text" "$dir"; then
            echo "::error::The control found no \"$text\" in the $platform E2E bundle: the search proves nothing"
            exit 1
        fi
    done
    kept=()
    for word in "${words[@]}"; do
        if grep -rqaw -- "$word" "$dir"; then kept+=("$word"); fi
    done
    echo "  $platform: the mock's strings are there; names kept: ${kept[*]:-none}"
done

echo "Store build (production):"
env -u EXPO_PUBLIC_OSTROM_MOCK npx expo export --platform android --platform ios --output-dir "$out/store" > /dev/null
found=0
for platform in "${platforms[@]}"; do
    dir="$(bundles "$out/store" "$platform")"
    for text in "${strings[@]}"; do
        if grep -rqaF -- "$text" "$dir"; then echo "  $platform: found \"$text\""; found=1; fi
    done
    for word in "${words[@]}"; do
        if grep -rqaw -- "$word" "$dir"; then echo "  $platform: found \"$word\""; found=1; fi
    done
done
if [ "$found" -ne 0 ]; then
    echo "::error::A store bundle carries the in-memory server of src/api/mocks"
    exit 1
fi
echo "  no trace of the mock server in the Android and iOS bundles"
