#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Runs each Maestro flow of a directory (.maestro/ by default) on the CI emulator, one at a time; the arguments after
# the directory go to each `maestro test` (ostrom-server's app-journey passes `-e OSTROM_E2E_LOGIN=…`). The emulator sometimes goes offline for a
# moment ("adb: device offline", DeviceServerDiedException: runs 41035568416, 41038267977, 41041831225), mostly just
# after it boots. So the device must answer adb, have finished booting and answer the package manager before each
# flow (with one `adb reconnect` when it does not), and a warm-up flow runs first without counting.
# A flow is run again, twice at most, only when its failure is such an outage of the emulator (scripts/emulator-outage.sh),
# each time once the device answers adb and has finished booting; never when an assertion of the flow failed or the app
# crashed: an app that fails now and then fails the job. Each rerun is written to the job's summary with its signature.
set -uo pipefail

flows="${1:-.maestro}"
shift $(($# > 0 ? 1 : 0))

# Waits at most this long for each condition.
LIMIT_SECONDS=180
# Reruns of a flow after an outage of the emulator, at most.
MAX_RERUNS=2
classify="$(dirname "$0")/emulator-outage.sh"

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
    shift 2
    name="$(basename "$flow" .yaml)"
    mkdir -p maestro-output
    maestro test --format junit --output "maestro-report-$name-$attempt.xml" \
        --test-output-dir "maestro-output/$name-$attempt" "$@" "$flow" 2>&1 | tee "maestro-output/$name-$attempt.console"
}

# Why attempt $2 of flow $1 failed: `retry <signature>` on an outage of the emulator, `fail <reason>` otherwise. Read in
# its console, its JUnit report (a device server that dies mid-command is written there alone, the console saying
# "Failed" and Maestro's log stopping short: app-journey run 41661088741) and Maestro's log of the flow; an emulator
# that has not finished booting after the failure is written to its console first.
verdict() {
    local name="$1" attempt="$2"
    local logs=("maestro-output/$name-$attempt/"*/*/logs/maestro.log)
    booted || echo "emulator boot not completed: sys.boot_completed is not 1 after the failure" >> "maestro-output/$name-$attempt.console"
    "$classify" "maestro-output/$name-$attempt.console" "maestro-report-$name-$attempt.xml" "${logs[@]}"
}

# A line of the log, and of the job's summary when there is one.
note() {
    echo "::warning::$1"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "- $1" >> "$GITHUB_STEP_SUMMARY"; fi
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
for flow in "$flows"/*.yaml; do
    name="$(basename "$flow" .yaml)"
    # A workspace configuration is not a flow.
    [ "$name" = "config" ] && continue
    steady_or_stop
    attempt=1
    until run "$flow" "$attempt" "$@"; do
        why="$(verdict "$name" "$attempt")"
        if [ "${why%% *}" != retry ]; then
            echo "::error::$name failed: ${why#* } (not an outage of the emulator: not run again)"
            failed=1
            break
        fi
        if [ "$attempt" -gt "$MAX_RERUNS" ]; then
            echo "::error::$name failed on an outage of the emulator $attempt times, the last: ${why#retry }"
            failed=1
            break
        fi
        note "$name: run again ($attempt of $MAX_RERUNS) after an outage of the emulator: \`${why#retry }\`"
        adb reconnect offline
        steady_or_stop
        attempt=$((attempt + 1))
    done
done
exit "$failed"
