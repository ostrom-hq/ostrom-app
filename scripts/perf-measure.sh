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
# - each long list (inbox, activity, history: 500 more rows on a server of many.example): what a person waits after the
#   press of its tab, from the app's `ostrom.perf tab <name>` to its `ostrom.perf shown <list>` (the list laid out with
#   its rows, the network calls included), on the phone's clock; never the Maestro flow's own duration, mostly the
#   tool's start (about 10 s). The inbox is already shown by the cold starts: its time is the first useful screen. Then
#   20 flings, the frames rendered and janky (`dumpsys gfxinfo`) and the memory (`dumpsys meminfo`, total PSS);
# - the memory over $PERF_NAV_SECONDS (300) seconds of navigation: rounds through the tabs, each long list scrolled
#   (.maestro/perf/navigate.yaml), the total PSS read before, after every few rounds, and once more after 10 s idle on
#   the inbox. It is said rising when the last third of the readings stays above the first third's highest by more than
#   $PERF_NAV_GROWTH_MIB (10) MiB and the idle reading does not come back under it: memory that goes up without coming
#   down.
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

# Runs one flow (then Maestro's own arguments, such as `-e NAME=value`); a second time only when its first failure is
# such an outage, never when the flow's assertion failed.
flow() {
    local name attempt
    name="$(basename "$1" .yaml)"
    for attempt in 1 2; do
        if maestro test --test-output-dir "$out/maestro/$name-$attempt" "${@:2}" "$1" > "$out/$name.console" 2>&1; then
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

adb install -r "$apk" > /dev/null
# Maestro's driver often fails its very first flow just after the boot: a warm-up that does not count.
maestro test .maestro/common/warm-up.yaml > "$out/warm-up.console" 2>&1 || true
flow .maestro/perf/sign-in-many.yaml

: > "$out/starts.tsv"
# One cold start, written to starts.tsv. Fails when adb lost the emulator on the way: an outage, not the app (Perf run
# 41685033001, attempt 3: "adb: device offline" in the second start, after a first one that read no request at all).
cold_start() {
    local run="$1" total started useful log
    adb shell am force-stop "$APP" && adb logcat -c || return 1
    total=$(adb shell am start-activity -W -n "$APP/.MainActivity" | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}') || return 1
    if ! wait_for "ostrom.perf first-useful-screen"; then
        adb logcat -d > "$out/logcat-start-$run.txt" || true
        return 1
    fi
    started=$(first_at "START u0 .*$APP") && useful=$(first_at "ostrom.perf first-useful-screen") || return 1
    sleep 5
    log=$(adb logcat -d) || return 1
    { grep -o "ostrom.e2e request .*" <<< "$log" || true; } | sed 's/^ostrom.e2e request //' > "$out/requests-$run.txt"
    [ -n "$total" ] && [ -n "$started" ] && [ -n "$useful" ] || return 1
    printf '%s\t%s\t%s\t%s\n' "$run" "$total" "$(python3 -c "print(round(($useful - $started) * 1000))")" \
        "$(wc -l < "$out/requests-$run.txt" | tr -d ' ')" >> "$out/starts.tsv"
}

for run in $(seq 1 "$RUNS"); do
    cold_start "$run" && continue
    # The emulator still answers: the app showed nothing useful, a real failure. Otherwise one more try, once back.
    if [ "$(adb get-state 2> /dev/null)" = device ]; then
        echo "::error::no first useful screen within 60 s (run $run)"
        exit 1
    fi
    echo "::warning::the emulator went away during cold start $run: once more, after a reconnect"
    adb reconnect > /dev/null 2>&1 || true
    timeout 120 adb wait-for-device
    cold_start "$run" || { echo "::error::cold start $run failed twice"; exit 1; }
done

read -r width height < <(adb shell wm size | tr -d '\r' | awk -F'[ x]' '/Physical/ {print $3, $4}')
for list in inbox activity history; do
    adb logcat -c
    flow ".maestro/perf/open-$list.yaml"
    tap=""
    if [ "$list" != inbox ]; then
        pressed=$(first_at "ostrom.perf tab $list")
        laid=$(first_at "ostrom.perf shown $list")
        [ -n "$pressed" ] && [ -n "$laid" ] || { echo "::error::$list: no tab press or no list layout in logcat"; exit 1; }
        tap=$(python3 -c "print(round(($laid - $pressed) * 1000))")
        [ "$tap" -ge 0 ] || { echo "::error::$list: its list was laid out before its tab was pressed"; exit 1; }
    fi
    adb shell dumpsys gfxinfo "$APP" reset > /dev/null
    for _ in $(seq 1 20); do
        adb shell input swipe $((width / 2)) $((height * 8 / 10)) $((width / 2)) $((height * 2 / 10)) 150
    done
    sleep 2
    adb shell dumpsys gfxinfo "$APP" > "$out/gfxinfo-$list.txt"
    adb shell dumpsys meminfo "$APP" > "$out/meminfo-$list.txt"
    echo "$tap" > "$out/tap-$list.txt"
done

# The app's total PSS, in KiB.
pss() {
    adb shell dumpsys meminfo "$APP" | tr -d '\r' | awk '/TOTAL PSS:/ {print $3; exit} /^ *TOTAL +[0-9]/ {print $2; exit}'
}
nav_seconds="${PERF_NAV_SECONDS:-300}"
printf '%s\t%s\t%s\n' 0 "$(pss)" navigating > "$out/memory.tsv"
nav_start=$SECONDS
while [ $((SECONDS - nav_start)) -lt "$nav_seconds" ]; do
    flow .maestro/perf/navigate.yaml -e ROUNDS=3
    printf '%s\t%s\t%s\n' $((SECONDS - nav_start)) "$(pss)" navigating >> "$out/memory.tsv"
done
sleep 10
printf '%s\t%s\t%s\n' $((SECONDS - nav_start)) "$(pss)" idle >> "$out/memory.tsv"

python3 - "$out" "$apk" "$bundle" "${PERF_NAV_GROWTH_MIB:-10}" << 'PY'
import json, os, re, statistics, sys

out, apk, bundle, growth_mib = sys.argv[1:5]
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
        # From the press of its tab to its list laid out, on the phone; None for the inbox, shown by the cold start.
        "tapToListMs": int(tap) if (tap := open(os.path.join(out, f"tap-{name}.txt")).read().strip()) else None,
        "frames": number(r"Total frames rendered:\s+(\d+)", gfx, 0),
        "jankyFrames": number(r"Janky frames:\s+(\d+)", gfx, 0),
        "jankyPercent": number(r"Janky frames:\s+\d+\s+\(([\d.]+)%\)", gfx, 0),
        "p90FrameMs": number(r"90th percentile:\s+(\d+)ms", gfx, 0),
        "p99FrameMs": number(r"99th percentile:\s+(\d+)ms", gfx, 0),
        "pssKiB": pss,
    }

readings = [line.split("\t") for line in open(os.path.join(out, "memory.tsv")).read().split("\n") if line]
navigating = [int(kib) for _, kib, state in readings if state == "navigating"]
idle = next(int(kib) for _, kib, state in readings if state == "idle")
third = max(1, len(navigating) // 3)
first_high = max(navigating[:third])
rising = min(navigating[-third:]) > first_high + float(growth_mib) * 1024 and idle > first_high + float(growth_mib) * 1024
memory = {
    "seconds": int(readings[-2][0]),
    "readingsKiB": [{"atSeconds": int(at), "pssKiB": int(kib), "state": state} for at, kib, state in readings],
    "startKiB": navigating[0],
    "peakKiB": max(navigating),
    "idleKiB": idle,
    "rising": rising,
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
    "memoryOverNavigation": memory,
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
        f"| {name}: tap to list laid out / janky / p90 / p99 / PSS | "
        f"{'first useful screen' if values['tapToListMs'] is None else str(values['tapToListMs']) + ' ms'} / {values['jankyFrames']:.0f} of {values['frames']:.0f} "
        f"({values['jankyPercent']}%) / {values['p90FrameMs']:.0f} ms / {values['p99FrameMs']:.0f} ms / {(values['pssKiB'] or 0) / 1024:.0f} MiB |"
    )
rows.append(
    f"| Memory over {memory['seconds']} s of navigation: start / peak / idle after / verdict | "
    f"{memory['startKiB'] / 1024:.0f} MiB / {memory['peakKiB'] / 1024:.0f} MiB / {memory['idleKiB'] / 1024:.0f} MiB / "
    f"{'rising, not coming down' if memory['rising'] else 'steady'} |"
)
rows += ["", "Calls at launch (run 1):", "", *[f"- `{line}`" for line in result["launchRequests"]["first"]]]
open(os.path.join(out, "summary.md"), "w").write("\n".join(rows) + "\n")
print("\n".join(rows))
PY
