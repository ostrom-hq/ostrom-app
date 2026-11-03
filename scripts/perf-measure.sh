#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# The performance pass of the app, on the CI emulator, against the release E2E build (workflow `Perf`).
#   scripts/perf-measure.sh <apk> <JS bundle>
# Measures, and writes to $PERF_OUT (perf/ by default) as perf.json and summary.md:
# - the size of the APK and of the JS bundle (Hermes bytecode);
# - the cold start, signed in, $PERF_RUNS times (5): the first frame (`am start -W`, TotalTime), and the first useful
#   screen, from the system's START of the activity to the app's `ostrom.perf first-useful-screen` (the inbox list laid
#   out), both read in logcat on the emulator's clock;
# - the calls made at launch: the E2E build logs each one (`ostrom.e2e request`), counted until 5 s after the first
#   useful screen;
# - each long list (inbox, activity, history: 500 more rows on a server of many.example): the time to show it, then 20
#   flings, the frames rendered and janky (`dumpsys gfxinfo`) and the memory (`dumpsys meminfo`, total PSS).
# The emulator renders without a GPU: compare runs with each other, not with a phone.
set -euo pipefail

apk="$1"
bundle="$2"
APP=run.ostrom.app
RUNS="${PERF_RUNS:-5}"
out="${PERF_OUT:-perf}"
mkdir -p "$out"

# What Maestro prints when the emulator, not the app, is the problem (the same list as scripts/run-maestro.sh).
OUTAGE='device offline|DeviceServerDiedException|device not found|no devices/emulators found|Maestro driver did not start up in time|Failed to connect to /127\.0\.0\.1:7001|UNAVAILABLE: io exception'

# Runs one flow; a second time only when its first failure is such an outage, never when the flow's assertion failed.
flow() {
    local name attempt
    name="$(basename "$1" .yaml)"
    for attempt in 1 2; do
        if maestro test --test-output-dir "$out/maestro/$name-$attempt" "$1" > "$out/$name.console" 2>&1; then
            return 0
        fi
        # Maestro writes the outage in its own log of the flow, the console only says "Failed" (run 41657636900).
        if [ "$attempt" = 1 ] && grep -rqE "$OUTAGE" "$out/$name.console" "$out/maestro/$name-$attempt"; then
            adb reconnect > /dev/null 2>&1 || true
            adb wait-for-device
            continue
        fi
        break
    done
    cat "$out/$name.console"
    echo "::error::$name failed"
    exit 1
}

# The emulator's epoch time (seconds, with milliseconds) of the first logcat line that matches.
first_at() {
    adb logcat -d -v epoch | grep -m1 -E "$1" | awk '{print $1}'
}

# Waits until logcat has a line that matches, 60 s at most.
wait_for() {
    local deadline=$((SECONDS + 60))
    until adb logcat -d | grep -qE "$1"; do
        if [ "$SECONDS" -ge "$deadline" ]; then return 1; fi
        sleep 0.2
    done
}

now() {
    date +%s.%N
}

adb install -r "$apk" > /dev/null
# Maestro's driver often fails its very first flow just after the boot: a warm-up that does not count.
maestro test .maestro/common/warm-up.yaml > "$out/warm-up.console" 2>&1 || true
flow .maestro/perf/sign-in-many.yaml

: > "$out/starts.tsv"
for run in $(seq 1 "$RUNS"); do
    adb shell am force-stop "$APP"
    adb logcat -c
    total=$(adb shell am start-activity -W -n "$APP/.MainActivity" | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')
    if ! wait_for "ostrom.perf first-useful-screen"; then
        adb logcat -d > "$out/logcat-start-$run.txt"
        echo "::error::no first useful screen within 60 s (run $run)"
        exit 1
    fi
    started=$(first_at "START u0 .*$APP")
    useful=$(first_at "ostrom.perf first-useful-screen")
    sleep 5
    adb logcat -d | grep -o "ostrom.e2e request .*" | sed 's/^ostrom.e2e request //' > "$out/requests-$run.txt" || true
    printf '%s\t%s\t%s\t%s\n' "$run" "$total" "$(python3 -c "print(round(($useful - $started) * 1000))")" \
        "$(wc -l < "$out/requests-$run.txt" | tr -d ' ')" >> "$out/starts.tsv"
done

read -r width height < <(adb shell wm size | tr -d '\r' | awk -F'[ x]' '/Physical/ {print $3, $4}')
for list in inbox activity history; do
    begin=$(now)
    flow ".maestro/perf/open-$list.yaml"
    shown=$(python3 -c "print(round(($(now) - $begin) * 1000))")
    adb shell dumpsys gfxinfo "$APP" reset > /dev/null
    for _ in $(seq 1 20); do
        adb shell input swipe $((width / 2)) $((height * 8 / 10)) $((width / 2)) $((height * 2 / 10)) 150
    done
    sleep 2
    adb shell dumpsys gfxinfo "$APP" > "$out/gfxinfo-$list.txt"
    adb shell dumpsys meminfo "$APP" > "$out/meminfo-$list.txt"
    echo "$shown" > "$out/shown-$list.txt"
done

python3 - "$out" "$apk" "$bundle" << 'PY'
import json, os, re, statistics, sys

out, apk, bundle = sys.argv[1:4]
starts = [line.split("\t") for line in open(os.path.join(out, "starts.tsv")).read().split("\n") if line]


def median(values):
    return round(statistics.median(values))


def number(pattern, text, default=None):
    found = re.search(pattern, text, re.M)
    return float(found.group(1)) if found else default


lists = {}
for name in ("inbox", "activity", "history"):
    gfx = open(os.path.join(out, f"gfxinfo-{name}.txt")).read()
    mem = open(os.path.join(out, f"meminfo-{name}.txt")).read()
    pss = number(r"TOTAL PSS:\s+(\d+)", mem) or number(r"^\s*TOTAL\s+(\d+)", mem)
    lists[name] = {
        "shownMs": int(open(os.path.join(out, f"shown-{name}.txt")).read()),
        "frames": number(r"Total frames rendered:\s+(\d+)", gfx, 0),
        "jankyFrames": number(r"Janky frames:\s+(\d+)", gfx, 0),
        "jankyPercent": number(r"Janky frames:\s+\d+\s+\(([\d.]+)%\)", gfx, 0),
        "p90FrameMs": number(r"90th percentile:\s+(\d+)ms", gfx, 0),
        "p99FrameMs": number(r"99th percentile:\s+(\d+)ms", gfx, 0),
        "pssKiB": pss,
    }

requests = open(os.path.join(out, "requests-1.txt")).read().split("\n")
result = {
    "apkBytes": os.path.getsize(apk),
    "bundleBytes": os.path.getsize(bundle),
    "coldStart": {
        "runs": len(starts),
        "firstFrameMs": median([int(run[1]) for run in starts]),
        "firstUsefulScreenMs": median([int(run[2]) for run in starts]),
        "each": [{"firstFrameMs": int(run[1]), "firstUsefulScreenMs": int(run[2]), "requests": int(run[3])} for run in starts],
    },
    "launchRequests": {"count": median([int(run[3]) for run in starts]), "first": [line for line in requests if line]},
    "lists": lists,
}
json.dump(result, open(os.path.join(out, "perf.json"), "w"), indent=2)

mib = lambda value: f"{value / 1024 / 1024:.2f} MiB"
rows = [
    "| Measure | Value |",
    "| --- | --- |",
    f"| APK | {mib(result['apkBytes'])} |",
    f"| JS bundle (Hermes) | {mib(result['bundleBytes'])} |",
    f"| Cold start, first frame (median of {len(starts)}) | {result['coldStart']['firstFrameMs']} ms |",
    f"| Cold start, first useful screen (median of {len(starts)}) | {result['coldStart']['firstUsefulScreenMs']} ms |",
    f"| Calls at launch | {result['launchRequests']['count']} |",
]
for name, values in lists.items():
    rows.append(
        f"| {name}: shown / janky / p90 / p99 / PSS | {values['shownMs']} ms / {values['jankyFrames']:.0f} of {values['frames']:.0f} "
        f"({values['jankyPercent']}%) / {values['p90FrameMs']:.0f} ms / {values['p99FrameMs']:.0f} ms / {(values['pssKiB'] or 0) / 1024:.0f} MiB |"
    )
rows += ["", "Calls at launch (run 1):", "", *[f"- `{line}`" for line in result["launchRequests"]["first"]]]
open(os.path.join(out, "summary.md"), "w").write("\n".join(rows) + "\n")
print("\n".join(rows))
PY
