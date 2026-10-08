# Glass blur frame cost while a reply streams

These runs use the `benchmark` build type from commit `62ef3de`: release with R8 and resource shrinking, not debuggable, plus the debug lab sources. Its `baseline` glass mode runs the shipped Haze path, `hazeBlur` over the full-screen `hazeSource` in `GlassBackdrop`. Each run streams a scripted reply in `SessionLabActivity` and reads `dumpsys gfxinfo` for the stream only. Values are the median with the range in parentheses.

## The blur has no measurable cost on the host GPU

`emulator-5554` (medium_phone, API 36, 1080x2400) ran with `-gpu host`, so the Apple M4 renders. These are five 20 s streams per side with the sides alternated, measured on 2026-10-08. `emulator-5556` was busy with another worker's voice calls throughout.

| | Janky frames | p50 | p90 | p99 | Frames | Host load at start |
|---|---|---|---|---|---|---|
| Blur on | 0.16% (0.08 to 2.45) | 24 ms (22 to 27) | 29 ms (25 to 36) | 36 ms (31 to 65) | 1276 (1259 to 1279) | 5.8 (4.3 to 6.9) |
| Blur off | 0.54% (0 to 3.74) | 22 ms (20 to 25) | 28 ms (22 to 36) | 46 ms (27 to 73) | 1283 (1229 to 1291) | 6.0 (5.1 to 9.3) |

Every range overlaps, and both sides drew at the display's 60 Hz. The script counted no crashes or dead processes. The runs in order, with the host load average at the start and end of each stream:

| Run | Blur | Janky | p50 ms | p90 ms | p99 ms | Load at start | Load at end |
|---|---|---|---|---|---|---|---|
| 1 | on | 0.16% | 24 | 29 | 36 | 4.67 | 5.13 |
| 2 | off | 0.54% | 21 | 28 | 46 | 6.00 | 5.66 |
| 3 | on | 1.18% | 26 | 32 | 57 | 5.77 | 5.69 |
| 4 | off | 3.74% | 25 | 36 | 73 | 5.40 | 7.26 |
| 5 | on | 0.16% | 22 | 25 | 31 | 6.91 | 6.47 |
| 6 | off | 0.08% | 22 | 26 | 32 | 6.43 | 5.88 |
| 7 | on | 0.08% | 23 | 27 | 36 | 5.88 | 5.37 |
| 8 | off | 0.00% | 20 | 22 | 27 | 5.10 | 4.25 |
| 9 | on | 2.45% | 27 | 36 | 65 | 4.31 | 7.39 |
| 10 | off | 0.95% | 24 | 30 | 48 | 9.28 | 9.69 |

The raw output is in `glass-blur-host-gpu.jsonl`.

## Where a blur-on frame's time goes

One Perfetto trace covered a 10 s blur-on stream on the same device: 15.1 s of trace and 902 frames. The summary that `scripts/session-lab-perfetto.py` printed is in `glass-blur-host-gpu-trace.txt`.

- The UI thread ran for 2258 ms, about 2.5 ms per frame. `Choreographer#doFrame` has a median of 3.6 ms. Of its 6552 ms in total, 4684 ms is `postAndWait`, where the UI thread waits for the RenderThread to take the frame.
- `DrawFrames` on the RenderThread has a median of 16.4 ms. Of its 14658 ms in total, 10984 ms is `dequeueBuffer` inside `eglSwapBuffersWithDamageKHR`, which waits for the display to release a buffer. The RenderThread ran for 3642 ms, about 4 ms per frame. `flush commands` took 1.3 ms per frame. `flush layers`, which renders the offscreen layers that Haze blurs, took 0.38 ms per frame.
- GPU time per frame (`GpuCompleted` minus `SwapBuffers` in framestats, last 120 frames) has a median of 4.1 ms, a p90 of 8.5 ms, and a p99 of 16.1 ms. The gfxinfo GPU p50 was 18 ms on both sides of the A/B above.
- Each thread spent less than 2% of the trace waiting for a CPU.
- SurfaceFlinger's frame timeline marked 3 of 920 frames as app deadline misses. It marked 912 as buffer stuffing, which means the app had frames queued ahead of the display.

## The default emulator overstates the cost

On 2026-10-07 the same build ran on `emulator-5554` without `-gpu host`. SurfaceFlinger reported SwiftShader, a software renderer that runs every offscreen layer and blur pass on the host CPU. There were five alternating 20 s streams per side, and the host load average was 15.7 when they started.

| | Janky frames | p50 | p90 | p99 |
|---|---|---|---|---|
| Blur on | 49% (34 to 61) | 53 ms (46 to 57) | 81 ms (57 to 101) | 150 ms (81 to 150) |
| Blur off | 18% (2.2 to 22) | 36 ms (30 to 38) | 57 ms (36 to 69) | 97 ms (53 to 105) |

The raw output is in `glass-blur-swiftshader.jsonl`. No candidate in `system/Glass.kt` beat the shipped path there. `sharedhold` gave 58% (55 to 66) against 60% (53 to 63) for baseline, in a noisier window with no blur-off runs, so that comparison is inconclusive. Its raw output is in `glass-blur-sharedhold-swiftshader.jsonl`. The single 8 s triage runs of the other candidates are in `glass-blur-triage-swiftshader.jsonl`.

## Not covered

No phone was measured. An Apple M4 is much faster than a low-end phone GPU, so such a phone could still pay for the blur.

## Rerun

Boot the emulator with the main checkout's `scripts/boot-emulator.sh medium_phone 5554`, which starts it detached with `-gpu host`. Then run these commands from the worktree root:

```
./gradlew :app:assembleBenchmark
adb -s emulator-5554 install -r app/build/outputs/apk/benchmark/app-benchmark.apk
python3 scripts/session-lab-gfxinfo.py --device emulator-5554 --runs 5 --compare-glass-blur
curl -LO https://get.perfetto.dev/trace_processor && chmod +x trace_processor
python3 scripts/session-lab-perfetto.py --device emulator-5554 --stream-ms 10000 --trace-processor ./trace_processor --out session-lab.pftrace
```
