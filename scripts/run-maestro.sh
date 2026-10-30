#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Runs each Maestro flow of .maestro/ on the CI emulator, one at a time. The emulator sometimes goes offline for a
# moment ("adb: device offline", DeviceServerDiedException: runs 41035568416, 41038267977, 41041831225), mostly just
# after it boots. So before the first flow the device must answer adb, have finished booting and answer the package
# manager, and a warm-up flow (the app starts and shows its first screen) runs without counting. A flow that fails is
# run once more, after `adb reconnect`, from a clean state, with a warning. A flow that fails twice fails the job, so a
# real failure of the app is never hidden.
set -uo pipefail

# Waits at most this long for each condition, then lets the flow fail on its own.
LIMIT_SECONDS=180

until_ok() {
    local deadline=$((SECONDS + LIMIT_SECONDS))
    until "$@" > /dev/null 2>&1; do
        if [ "$SECONDS" -ge "$deadline" ]; then echo "::warning::still not ready after ${LIMIT_SECONDS}s: $*"; return 1; fi
        sleep 2
    done
}

booted() {
    [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]
}

steady() {
    timeout "$LIMIT_SECONDS" adb wait-for-device
    until_ok booted
    until_ok adb shell pm list packages
}

run() {
    local flow="$1" attempt="$2" name
    name="$(basename "$flow" .yaml)"
    maestro test --format junit --output "maestro-report-$name-$attempt.xml" --test-output-dir "maestro-output/$name-$attempt" "$flow"
}

steady
maestro test --test-output-dir maestro-output/warm-up .maestro/common/warm-up.yaml \
    || { adb reconnect offline; steady; maestro test --test-output-dir maestro-output/warm-up-2 .maestro/common/warm-up.yaml; } \
    || echo "::warning::the warm-up did not pass: the flows run anyway"

failed=0
for flow in .maestro/*.yaml; do
    steady
    if run "$flow" 1; then continue; fi
    echo "::warning::$(basename "$flow") failed once: adb reconnect, then run it again from a clean state"
    adb reconnect offline
    steady
    if ! run "$flow" 2; then
        echo "::error::$(basename "$flow") failed twice"
        failed=1
    fi
done
exit "$failed"
