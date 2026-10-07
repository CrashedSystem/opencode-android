#!/usr/bin/env python3
"""Frame timing of the session screen while a reply streams.

Runs the debug build's SessionLabActivity on a device, resets `dumpsys gfxinfo`
just before its scripted stream starts, waits for the stream to finish, and
reads the frames rendered during the stream. Each run prints one JSON line;
the last lines are the median and range over the runs, per side.

    python3 scripts/session-lab-gfxinfo.py --device emulator-5554 --runs 5
    python3 scripts/session-lab-gfxinfo.py --runs 5 --compare-glass-blur
    python3 scripts/session-lab-gfxinfo.py --runs 1 --scroll-trace
"""

import argparse
import json
import os
import re
import statistics
import subprocess
import time

PACKAGE = "ai.opencode.mobilecode"
ACTIVITY = f"{PACKAGE}/.SessionLabActivity"


def adb(device, *args, check=True):
    sdk = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    binary = sdk if os.path.exists(sdk) else "adb"
    return subprocess.run([binary, "-s", device, *args], check=check, capture_output=True, text=True).stdout


def gfxinfo(device):
    out = adb(device, "shell", "dumpsys", "gfxinfo", PACKAGE)
    # Only the summary block for the whole process.
    def number(pattern, cast=float):
        match = re.search(pattern, out)
        return cast(match.group(1)) if match else None

    return {
        "frames": number(r"Total frames rendered: (\d+)", int),
        "janky": number(r"Janky frames: (\d+)", int),
        "janky_percent": number(r"Janky frames: \d+ \(([\d.]+)%\)"),
        "p50_ms": number(r"50th percentile: (\d+)ms", int),
        "p90_ms": number(r"90th percentile: (\d+)ms", int),
        "p95_ms": number(r"95th percentile: (\d+)ms", int),
        "p99_ms": number(r"99th percentile: (\d+)ms", int),
        "missed_vsync": number(r"Number Missed Vsync: (\d+)", int),
        "slow_ui_thread": number(r"Number Slow UI thread: (\d+)", int),
        "slow_draw": number(r"Number Slow issue draw commands: (\d+)", int),
        "deadline_missed": number(r"Number Frame deadline missed: (\d+)", int),
        "gpu_p90_ms": number(r"90th gpu percentile: (\d+)ms", int),
    }


def wait_for(device, pattern, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        log = adb(device, "logcat", "-d", "-s", "SessionLab:I")
        match = re.search(pattern, log)
        if match:
            return match
        time.sleep(0.25)
    raise SystemExit(f"timed out waiting for {pattern!r}")


def run(device, stream_ms, start_delay_ms, glass_blur=True, scroll_trace=False):
    adb(device, "shell", "am", "force-stop", PACKAGE)
    adb(device, "logcat", "-c")
    adb(
        device, "shell", "am", "start", "-W", "-n", ACTIVITY,
        "--ei", "stream-ms", str(stream_ms),
        "--ei", "start-delay-ms", str(start_delay_ms),
        "--ez", "lab-scroll-trace", "true" if scroll_trace else "false",
        "--ez", "glass-blur", "true" if glass_blur else "false",
    )
    # Let the first frames settle, then measure only the stream.
    time.sleep(max(0, start_delay_ms / 1000 - 1.5))
    before = gfxinfo(device)
    adb(device, "shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    wait_for(device, r"stream started", 10)
    finished = wait_for(device, r"stream finished after (\d+) ms: (\d+) batches, (\d+) tokens, (\d+) characters; trace (.*)", stream_ms / 1000 + 30)
    after = gfxinfo(device)
    crashes = adb(device, "logcat", "-d", "-b", "crash")
    alive = adb(device, "shell", "pidof", PACKAGE, check=False).strip()
    return {
        "before": before,
        "after": after,
        "stream_ms": int(finished.group(1)),
        "batches": int(finished.group(2)),
        "tokens": int(finished.group(3)),
        "characters": int(finished.group(4)),
        "trace": finished.group(5).strip(),
        "errors": crashes.count("FATAL EXCEPTION") + (0 if alive else 1),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--stream-ms", type=int, default=20_000)
    parser.add_argument("--start-delay-ms", type=int, default=5_000)
    parser.add_argument("--compare-glass-blur", action="store_true", help="alternate runs with and without the glass blur")
    parser.add_argument("--scroll-trace", action="store_true", help="check the follower on device; adds per-frame work, so do not report its timings")
    args = parser.parse_args()
    results = []
    for index in range(args.runs * (2 if args.compare_glass_blur else 1)):
        blur = not (args.compare_glass_blur and index % 2 == 1)
        result = run(args.device, args.stream_ms, args.start_delay_ms, glass_blur=blur, scroll_trace=args.scroll_trace)
        result["glass_blur"] = blur
        results.append(result)
        print(json.dumps({"run": index + 1, **result}), flush=True)
    for blur in (True, False) if args.compare_glass_blur else (True,):
        print(json.dumps({"glass_blur": blur, **summarize([r for r in results if r["glass_blur"] == blur])}))


def summarize(results):
    summary = {"runs": len(results), "errors": sum(r["errors"] for r in results)}
    for key in ("frames", "janky_percent", "p50_ms", "p90_ms", "p95_ms", "p99_ms", "slow_ui_thread", "deadline_missed"):
        values = [r["after"][key] for r in results if r["after"][key] is not None]
        if values:
            summary[key] = {"median": statistics.median(values), "min": min(values), "max": max(values)}
    return summary


if __name__ == "__main__":
    main()
