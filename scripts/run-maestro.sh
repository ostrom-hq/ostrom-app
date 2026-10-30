#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Runs each Maestro flow of .maestro/ on the emulator, one at a time, after the device has finished booting. The CI
# emulator sometimes goes offline for a moment ("device offline", DeviceServerDiedException: runs 41035568416 and
# 41038267977): a flow that fails is run once more, from a clean state, with a warning. A flow that fails twice fails
# the job, so a real failure of the app is never hidden.
set -uo pipefail

wait_for_device() {
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
}

run() {
    local flow="$1" attempt="$2" name
    name="$(basename "$flow" .yaml)"
    maestro test --format junit --output "maestro-report-$name-$attempt.xml" --test-output-dir "maestro-output/$name-$attempt" "$flow"
}

failed=0
for flow in .maestro/*.yaml; do
    wait_for_device
    if run "$flow" 1; then continue; fi
    echo "::warning::$(basename "$flow") failed once: run again from a clean state"
    wait_for_device
    if ! run "$flow" 2; then
        echo "::error::$(basename "$flow") failed twice"
        failed=1
    fi
done
exit "$failed"
