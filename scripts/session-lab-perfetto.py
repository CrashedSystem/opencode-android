#!/usr/bin/env python3
"""Where the session screen's frame time goes while a reply streams.

Starts SessionLabActivity, records a Perfetto trace across its scripted stream,
and pulls the trace. trace_processor then reports the app's UI thread
(Choreographer#doFrame) and RenderThread (DrawFrames) time per frame, their
heaviest slices, how long each thread ran or waited for a CPU, and how
SurfaceFlinger classified the frames. GPU time per frame comes from
`dumpsys gfxinfo framestats` (GpuCompleted minus SwapBuffers), which keeps
only the last frames of the stream.

    python3 scripts/session-lab-perfetto.py --device emulator-5554 --stream-ms 10000 \\
      --trace-processor ./trace_processor --out /tmp/session-lab.pftrace

Get trace_processor with `curl -LO https://get.perfetto.dev/trace_processor`.
"""

import argparse
import csv
import io
import os
import re
import statistics
import subprocess
import tempfile
import time

PACKAGE = "ai.opencode.mobilecode"
ACTIVITY = f"{PACKAGE}/.SessionLabActivity"
DEVICE_TRACE = "/data/misc/perfetto-traces/session-lab.pftrace"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

CONFIG = """
buffers { size_kb: 131072 fill_policy: RING_BUFFER }
buffers { size_kb: 2048 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "sched/sched_wakeup_new"
      ftrace_events: "task/task_newtask"
      ftrace_events: "task/task_rename"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_categories: "dalvik"
      atrace_apps: "@PACKAGE@"
      buffer_size_kb: 16384
      drain_period_ms: 250
    }
  }
}
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 0 } }
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config { scan_all_processes_on_start: true }
  }
}
duration_ms: @DURATION@
"""

APP_THREADS = """
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = '@PACKAGE@' AND (t.tid = p.pid OR t.name = 'RenderThread')
"""

SLICES = """
SELECT CASE WHEN t.tid = p.pid THEN 'main' ELSE t.name END AS thread, s.name AS name, s.dur AS dur, s.depth AS depth
""" + APP_THREADS

STATES = """
SELECT CASE WHEN t.tid = p.pid THEN 'main' ELSE t.name END AS thread, ts.state AS state, SUM(ts.dur) AS dur
FROM thread_state ts
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = '@PACKAGE@' AND (t.tid = p.pid OR t.name = 'RenderThread')
GROUP BY 1, 2
"""

JANK = """
SELECT a.jank_type AS jank, COUNT(*) AS frames
FROM actual_frame_timeline_slice a
JOIN process p USING (upid)
WHERE p.name = '@PACKAGE@'
GROUP BY 1
ORDER BY 2 DESC
"""


def adb(device, *args, check=True, stdin=None):
    return subprocess.run([ADB, "-s", device, *args], check=check, capture_output=True, text=True, input=stdin).stdout


def wait_for(device, pattern, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if re.search(pattern, adb(device, "logcat", "-d", "-s", "SessionLab:I")):
            return
        time.sleep(0.25)
    raise SystemExit(f"timed out waiting for {pattern!r}")


def capture(args):
    adb(args.device, "shell", "am", "force-stop", PACKAGE)
    adb(args.device, "logcat", "-c")
    adb(
        args.device, "shell", "am", "start", "-W", "-n", ACTIVITY,
        "--ei", "stream-ms", str(args.stream_ms),
        "--ei", "start-delay-ms", str(args.start_delay_ms),
        "--ez", "glass-blur", "false" if args.blur_off else "true",
        "--es", "glass-mode", "baseline",
    )
    adb(args.device, "shell", "rm", "-f", DEVICE_TRACE, check=False)
    duration = args.start_delay_ms + args.stream_ms + 1500
    config = CONFIG.replace("@PACKAGE@", PACKAGE).replace("@DURATION@", str(duration))
    adb(args.device, "shell", "perfetto", "--txt", "-c", "-", "-o", DEVICE_TRACE, "--background", stdin=config)
    time.sleep(max(0, args.start_delay_ms / 1000 - 1.5))
    adb(args.device, "shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    wait_for(args.device, r"stream finished", args.stream_ms / 1000 + 30)
    framestats = adb(args.device, "shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    deadline = time.time() + duration / 1000 + 30
    while adb(args.device, "shell", "pidof", "perfetto", check=False).strip() and time.time() < deadline:
        time.sleep(0.5)
    adb(args.device, "pull", DEVICE_TRACE, args.out)
    return framestats


def query(trace_processor, trace, sql):
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as file:
        file.write(sql.replace("@PACKAGE@", PACKAGE))
    out = subprocess.run([trace_processor, "-q", file.name, trace], check=True, capture_output=True, text=True).stdout
    lines = out.splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith('"'))
    rows = list(csv.reader(io.StringIO("\n".join(lines[start:]))))
    return [dict(zip(rows[0], row)) for row in rows[1:]]


def describe(values):
    values = sorted(values)
    if not values:
        return "no samples"
    pick = lambda fraction: values[min(len(values) - 1, int(fraction * len(values)))]
    return f"n={len(values)} median={statistics.median(values):.1f} p90={pick(0.9):.1f} p99={pick(0.99):.1f} ms"


def framestats_rows(text):
    block = next((part for part in text.split("---PROFILEDATA---")[1::2] if part.strip()), "")
    rows = list(csv.reader(io.StringIO(block.strip())))
    if not rows:
        return []
    column = {name: index for index, name in enumerate(rows[0]) if name}
    return [
        {name: int(row[index]) for name, index in column.items()}
        for row in rows[1:]
        if len(row) >= len(column) and row[column["Flags"]] == "0"
    ]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--stream-ms", type=int, default=10_000)
    parser.add_argument("--start-delay-ms", type=int, default=4_000)
    parser.add_argument("--blur-off", action="store_true")
    parser.add_argument("--trace-processor", default="trace_processor")
    parser.add_argument("--out", default="session-lab.pftrace")
    args = parser.parse_args()

    framestats = capture(args)
    print(f"trace: {args.out}")

    slices = query(args.trace_processor, args.out, SLICES)
    frame_slices = {"main": "Choreographer#doFrame", "RenderThread": "DrawFrames"}
    for thread, prefix in frame_slices.items():
        durations = [int(s["dur"]) / 1e6 for s in slices if s["thread"] == thread and s["name"].startswith(prefix)]
        print(f"{thread} {prefix}: {describe(durations)}")
        totals = {}
        for s in slices:
            if s["thread"] != thread:
                continue
            name = re.sub(r"[\s#]*\d+$", "", s["name"])
            total, count, depth = totals.get(name, (0, 0, int(s["depth"])))
            totals[name] = (total + int(s["dur"]), count + 1, min(depth, int(s["depth"])))
        for name, (total, count, depth) in sorted(totals.items(), key=lambda item: -item[1][0])[:12]:
            print(f"  depth {depth} {name[:60]:60} total {total / 1e6:8.1f} ms  n={count}")

    for row in query(args.trace_processor, args.out, STATES):
        print(f"state {row['thread']:12} {row['state']:4} {int(row['dur']) / 1e6:9.1f} ms")
    for row in query(args.trace_processor, args.out, JANK):
        print(f"frametimeline {row['jank']}: {row['frames']}")

    frames = framestats_rows(framestats)
    print("framestats UI thread (SyncQueued - HandleInputStart):", describe([(f["SyncQueued"] - f["HandleInputStart"]) / 1e6 for f in frames]))
    print("framestats RenderThread (FrameCompleted - SyncStart):", describe([(f["FrameCompleted"] - f["SyncStart"]) / 1e6 for f in frames]))
    print("framestats GPU (GpuCompleted - SwapBuffers):", describe([(f["GpuCompleted"] - f["SwapBuffers"]) / 1e6 for f in frames if f.get("GpuCompleted", 0) > 0]))


if __name__ == "__main__":
    main()
