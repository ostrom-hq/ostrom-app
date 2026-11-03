#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Tests scripts/emulator-outage.sh on log extracts of real runs. An outage of the emulator is run again; a failed
# assertion of a flow or a crash of the app is a red, never run again, even when the logs also show an outage.
# Usage: scripts/emulator-outage.test.sh (in the CI job `ci`).
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
failures=0

# $1 the case, $2 the expected verdict (retry or fail), then the lines of the extract.
expect() {
    local name="$1" wanted="$2" got
    shift 2
    printf '%s\n' "$@" > "$dir/$name.log"
    got="$("$here/emulator-outage.sh" "$dir/$name.log")"
    if [ "${got%% *}" = "$wanted" ]; then
        echo "ok      $name: $got"
    else
        echo "FAILED  $name: expected $wanted, got: $got"
        failures=$((failures + 1))
    fi
}

# Outages of the emulator: run again.
expect device-offline retry \
    "21:03:53.809 [ERROR] maestro.orchestra.Orchestra.executeCommands: [Command execution] CommandFailed: Command failed (host:transport:emulator-5554): device offline"
expect device-server-died retry \
    "maestro.android.DeviceServerDiedException: Device server died during 'deviceInfo' on emulator-5554 (66ms since last byte, connection age 2849ms): StatusRuntimeException: UNAVAILABLE"
expect device-not-found-by-name retry \
    "Tap on id: login-server..." \
    "Exception in thread \"Thread-5\" java.io.IOException: Command failed (host:transport:emulator-5554): device 'emulator-5554' not found"
expect boot-not-completed retry \
    "[Failed] login (1s)" \
    "emulator boot not completed: sys.boot_completed is not 1 after the failure"

# The app or the flow: a red.
expect assertion fail \
    "[Failed] sign-in-confirm (1m 47s) (Assertion is false: \"Authorize|Accept & continue|Use without an account|No thanks\" is visible)"
expect element-not-found fail \
    "[Failed] login (12s) (Element not found: Id matching regex: login-github)"
expect app-crash fail \
    "[Failed] journey (8s) (App crashed or stopped while executing flow, please check device logs)"
expect assertion-with-outage-words fail \
    "21:03:50.101 [WARN] adb: device offline, reconnected" \
    "[Failed] spending (20s) (Assertion is false: \"Over budget\" is visible)"
expect crash-with-outage-words fail \
    "[Failed] inbox (9s) (App crashed or stopped while executing flow, please check device logs)" \
    "maestro.android.DeviceServerDiedException: Device server died during 'viewHierarchy' on emulator-5554"
expect unknown-error fail \
    "<failure>Unknown error</failure>"
expect no-logs fail ""

if [ "$failures" -gt 0 ]; then
    echo "$failures case(s) failed"
    exit 1
fi
echo "all cases passed"
