# Agent widgets on Android: handoff

Contract: opencode `specs/v2/widgets.md` (`widgets` branch). Reference client: rune-desktop
`crates/opencode-widget`, `crates/opencode-widget-view`. Parity rows: `docs/parity/transcript.md`
and `server.md` ("Agent widgets").

## State

Builds, installs and runs on the emulator against the widgets dev server and session-lab's
`widgets` checkpoint. Every built-in renders and answers: `suggestions` (carousels, images,
note), `form` (input, slider, checkbox, select), `confirm` (live: Deploy → Sending →
Submitted, `◈ Responded to "…"`), `chart` (bar and line; animated intro, press/drag tooltip,
tap to submit) and `backdrop` (GLES 3 shader, speed slider).

- Runner: `psychoscript-mobile`, the PsychoScript language alone (anomalyco/psychoscript, branch `mobile`,
  crate `rust/crates/psychoscript-mobile`, package `com.anomalyco.psychoscript`). `NativeAgentWidgetEngine`
  compiles `program + "\nreturn __widget_step(__input())"` once and `run`s steps at 250 ms and draws at 8 ms
  (a draw timeout counts as an overrun). Everything else widget-shaped is the app's: step inputs, output
  parsing and validation, the 2,000-command cap, rendering.
- Shader: `AgentWidgetEngine.translateShader(wgsl)` → vertex + fragment GLSL ES 3.00 with uniform block
  names. `NativeAgentWidgetEngine` wraps the node's WGSL in the spec's header and a GL entry that flips
  `frag.y`, then calls the runner's `translateWgsl` (feature `shader`). `AgentWidgetShaderStore` caches the
  result by sha256(target + wgsl) in memory and under `files/support/widget-shaders/`. `AgentWidgetGL` (one
  GL thread and context, programs cached by GLSL, 96-byte UBO bound to the reported blocks),
  `AgentWidgetShader` (`TextureView` per node, `withFrameNanos`, pause off screen / list not resumed,
  50 ms × 3 stop, poster/alt on failure).
- Canvas: step and draw-list decode on the widget's lane, own program copy while animating,
  frames only while visible, `Budget` errors count toward the 3-overrun stop.

## Server seam: the `opencode.widget` plugin

A widget is the `widget` tool call (`{ template, data, title?, wait? }`), found by `(messageID, callID)`.
`:server` talks to the plugin over `POST /api/rpc/opencode.widget/<method>?location[directory]=…`
(`live/LiveWidgets.kt`, `AgentWidgetClient`): `program` (pinned versions cached on disk forever),
`states`, `respond`, `send`; RPC errors keep their `type` (`OpenCodeClientError.rpcErrorType`: `closed`,
`rate_limited`, `not_found`, `rpc.unavailable`). `rpc.opencode.widget.changed` becomes
`OpenCodeServerEvent.WidgetChanged`, which Home forwards to the session screen.

- `SessionFeature` reads `states` when the session opens, resumes or reconnects
  (`readWidgetStates`) and applies `WidgetChanged`; `State.widgetStates` goes to the transcript as
  `TranscriptConfiguration.widgets` through `AgentWidgetAdapter.states(items, records)`.
- `widgets/AgentWidgetAdapter.kt` pairs each `widget` tool call with its plugin state (else the call
  alone: `metadata.template`, or the latest version, open) and routes answers. Rows, runtime and
  renderer see only `AgentWidgetState` and `AgentWidgetTransport`.
- `send` over 10 a second fails `rate_limited` and is dropped; `closed` shows "This widget isn't open
  any more." / "already answered".
- Synthetic answer and send messages carry `metadata.widget { messageID, callID, kind }` and show as
  the quiet `◈` line.
- Legacy: widgets that pre-plugin servers stored as `widget` messages still render and answer through
  the old REST routes (`Route.Message`), and `program`/`states` fall back on `rpc.unavailable`.
  Delete this path (and `OpenCodeWidgetMessage` decoding) once no such server is in use.

## The runner build

`scripts/psychoscript.sh` pins psychoscript's rev (`REV=`), fetches it into `.psychoscript/` and builds it
into `vendor/psychoscript/` (both gitignored); `:feature:transcript` adds `vendor/psychoscript/jniLibs` to
every variant's jniLibs (`androidComponents`), so no `.so` is committed. The generated Kotlin bindings are
committed (`feature/transcript/src/main/kotlin/com/anomalyco/psychoscript/`) so builds without Rust (CI)
compile; the script refreshes them and says so when they change. A build without the libraries runs, and
widgets show "The widget runtime isn't available".

- Needs: NDK r28+ (`ANDROID_NDK_HOME`, else the newest under the SDK's `ndk/`), `cargo install cargo-ndk`,
  `rustup target add --toolchain 1.100.0 aarch64-linux-android x86_64-linux-android`. About 2 minutes cold.
- `scripts/psychoscript.sh --prebuilt <dir>` takes a build of the same rev made elsewhere (checked against
  its `REVISION`).
- To move the pin: change `REV`, run the script, commit the script and any binding changes together.

## Workflow (Mac)

- JDK: Android Studio's JBR (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`);
  SDK `~/Library/Android/sdk` (cmdline-tools and NDK 29 installed there).
- Dev server: Enter a Server Manually → `http://10.0.2.2:4097`, user `opencode`, password from
  `oc-server.log`. Session lab: `python3 -B …/opencode-ios/scripts/session-lab.py --port 4112
  serve --checkpoint widgets`, connect to `http://10.0.2.2:4112`.

## Next: live widgets

`specs/v2/widgets-live.md`: `mode: "live"` templates run one long program per open widget
(`return await __widget_live(__input())`) on psychoscript-mobile's coming LiveRun API with `__host`
post/receive/sleep/now/fetch; pause off screen, 50 ms without awaiting stops it, the same 2,000-command
draws. Planned as a second engine path behind `AgentWidgetEngine` and a live controller beside the
step machine, reusing the node and canvas renderers.

## Open

- Translations: the 10 `transcript_widget_*` strings have English only. `scripts/translate.py`'s
  default model (`opencode/gpt-6.1-sol`) was unavailable; rerun it (or with `--model`).
- No `@Preview`s for widgets yet; `AgentWidgetTests` covers the readers, machine and tool call.
