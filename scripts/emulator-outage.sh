#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Classifies a failed Maestro flow from its logs (its console, its JUnit report, Maestro's log of the flow; a file that
# does not exist is skipped): prints `retry <signature>` when the emulator, not the app, went away, so that the flow may
# run again; `fail <reason>` otherwise. Used by scripts/run-maestro.sh and scripts/perf-measure.sh.
# Usage: scripts/emulator-outage.sh <log file>...
set -uo pipefail

# What Maestro prints when the flow or the app failed: a red, never run again, even next to an outage's words.
FAILURE='Assertion is false|Element not found|App crashed or stopped|FATAL EXCEPTION'
# What Maestro and adb print when the emulator or Maestro's driver on it, not the app, is the problem, and what the
# scripts write after a failure when the emulator has not finished booting. Never read in the device's logcat, where
# adb and the system print such words on their own.
OUTAGE="device offline|DeviceServerDiedException|device not found|device '[^']*' not found|emulator boot not completed|"\
'no devices/emulators found|Maestro driver did not start up in time|Failed to connect to /127\.0\.0\.1:7001|UNAVAILABLE: io exception|UNAVAILABLE: End of stream|Unable to connect to adb daemon'

files=()
for file in "$@"; do
    [ -f "$file" ] && files+=("$file")
done
failure=""
signature=""
if [ "${#files[@]}" -gt 0 ]; then
    failure="$(grep -ohsE "$FAILURE" "${files[@]}" | head -n 1)"
    signature="$(grep -ohsE "$OUTAGE" "${files[@]}" | head -n 1)"
fi
if [ -n "$failure" ]; then
    echo "fail $failure"
elif [ -n "$signature" ]; then
    echo "retry $signature"
else
    echo "fail no outage of the emulator in the logs"
fi
