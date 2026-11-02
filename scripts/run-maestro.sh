#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Runs each Maestro flow of .maestro/ on the CI emulator, one at a time. The emulator sometimes goes offline for a
# moment ("adb: device offline", DeviceServerDiedException: runs 41035568416, 41038267977, 41041831225), mostly just
# after it boots. So the device must answer adb, have finished booting and answer the package manager before each
# flow (with one `adb reconnect` when it does not), and a warm-up flow runs first without counting.
# A flow is run a second time only when its first failure is such an outage of the emulator, never when an assertion
# of the flow failed: an app that fails now and then fails the job.
set -uo pipefail

# Waits at most this long for each condition.
LIMIT_SECONDS=180
# What Maestro and adb print when the emulator or Maestro's driver on it, not the app, is the problem. Read in the
# flow's console and in Maestro's log of the flow, where the outage is written (the console only says "Failed", run
# 41071045167); never in the device's logcat, where adb and the system print such words on their own.
OUTAGE='device offline|DeviceServerDiedException|device not found|no devices/emulators found|Maestro driver did not start up in time|Failed to connect to /127\.0\.0\.1:7001|UNAVAILABLE: io exception'

until_ok() {
    local deadline=$((SECONDS + LIMIT_SECONDS))
    until "$@" > /dev/null 2>&1; do
        if [ "$SECONDS" -ge "$deadline" ]; then return 1; fi
        sleep 2
    done
}

booted() {
    [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]
}

ready() {
    timeout "$LIMIT_SECONDS" adb wait-for-device && until_ok booted && until_ok adb shell pm list packages
}

# Ready, or reconnected once and ready; says so when the device stays away.
steady() {
    ready && return 0
    echo "::warning::the emulator does not answer: adb reconnect"
    adb reconnect offline
    ready || { echo "::warning::the emulator still does not answer after adb reconnect"; return 1; }
}

run() {
    local flow="$1" attempt="$2" name
    name="$(basename "$flow" .yaml)"
    mkdir -p maestro-output
    maestro test --format junit --output "maestro-report-$name-$attempt.xml" \
        --test-output-dir "maestro-output/$name-$attempt" "$flow" 2>&1 | tee "maestro-output/$name-$attempt.console"
}

# The first attempt failed because the emulator went away, not because the app did.
outage() {
    local logs=("maestro-output/$1-1/"*/*/logs/maestro.log)
    grep -qsE "$OUTAGE" "maestro-output/$1-1.console" "${logs[@]}"
}

# An emulator that stays away after a reconnect fails the job at once, rather than each flow waiting for it in turn.
steady_or_stop() {
    steady && return 0
    echo "::error::the emulator does not answer: the remaining flows are not run"
    exit 1
}

steady_or_stop
if ! maestro test --test-output-dir maestro-output/warm-up .maestro/common/warm-up.yaml; then
    adb reconnect offline
    steady_or_stop
    maestro test --test-output-dir maestro-output/warm-up-2 .maestro/common/warm-up.yaml \
        || echo "::warning::the warm-up did not pass twice: the flows run anyway"
fi

failed=0
for flow in .maestro/*.yaml; do
    name="$(basename "$flow" .yaml)"
    # A workspace configuration is not a flow.
    [ "$name" = "config" ] && continue
    steady_or_stop
    if run "$flow" 1; then continue; fi
    if ! outage "$name"; then
        echo "::error::$name failed (no outage of the emulator: not run again)"
        failed=1
        continue
    fi
    echo "::warning::$name failed on an outage of the emulator: adb reconnect, then run it again from a clean state"
    adb reconnect offline
    steady_or_stop
    if ! run "$flow" 2; then
        echo "::error::$name failed twice"
        failed=1
    fi
done
exit "$failed"
