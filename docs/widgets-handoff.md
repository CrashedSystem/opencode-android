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
  `TranscriptConfiguration.widgets` through `AgentWidgetAdapter.states(items, records)`. The pre-plugin
  `widget` message type and REST routes are gone (servers from 877be1f02 on).
- `widgets/AgentWidgetAdapter.kt` pairs each `widget` tool call with its plugin state (else the call
  alone: `metadata.template`, or the latest version, open) and routes answers. Rows, runtime and
  renderer see only `AgentWidgetState` and `AgentWidgetTransport`.
- `send` over 10 a second fails `rate_limited` and is dropped; `closed` shows "This widget isn't open
  any more." / "already answered".
- Synthetic answer and send messages carry `metadata.widget { messageID, callID, kind }` and show as
  the quiet `◈` line.
- `rpc.unavailable` means the plugin isn't loaded yet (a cold server never waits for plugins; ~0.5 s),
  never "no widgets": `program`, `respond` and `send` try again a few times (0.75 s, 1.5 s, …); a failed
  `states` read marks the session (`widgetStatesUnread`) and is read again on the location's
  `plugin.updated` (`OpenCodeServerEvent.PluginsUpdated`, forwarded by Home), the next transcript refresh or a
  `changed` event.

## Full screen (`specs/v2/widgets-fullscreen.md`)

- Offered (expand button, top right, always shown) while the widget is open and its root's
  `fullscreen` is true, or absent and the view has a `canvas`/`shader` (`AgentWidgetController.offersFullscreen`).
- `AgentWidgetStore.fullscreen` holds the one widget open full screen; `AgentWidgetFullscreenHost`
  (mounted in `TranscriptList`, outside the lazy list) shows it in a `Dialog`: status bar hidden,
  `SCREEN_ORIENTATION_FULL_USER` while open, a title bar with Close, swipe down (nested-scroll overscroll
  or a drag where nothing scrolls) and system back exit; it closes itself when the widget closes. The inline
  row shows a placeholder at its last height. Same controller: state carries on, canvas/shader clocks
  live in the controller (`startNanos`).
- Entering/leaving sends `{ id: "", type: "fullscreen", value }`; draw frames carry `fullscreen`;
  `LocalAgentWidgetFullscreen` makes `fill` canvases/shaders take the height left (their `height` is the
  minimum), else the view scrolls.
- Live mode will send the same event through `widget.event()` and `Frame.fullscreen` once it exists.

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

## Live widgets (`specs/v2/widgets-live.md`)

- A template's `mode` comes with its program (`OpenCodeWidgetProgram.isLive`, cached with it on disk);
  `AgentWidgetPrograms` compiles it with `\nreturn await __widget_live(__input())` (`compileLive`).
- `AgentWidgetLiveDriver` owns one run per open widget (`Program.startLive`, input `{ data }`): started when
  the widget first shows, `pause()` while it's off screen, the list isn't resumed (background) and it isn't
  full screen, `resume()` (the program gets `{ type: "resumed", pausedMs }`), and after 30 s paused
  `snapshot()` (a failed snapshot, e.g. a fetch in flight, stops the run and it restarts from `main` when shown).
- Posts are read on the run thread and applied on the main thread: `render` replaces the view (and its root
  `fullscreen`), `draw` replaces a canvas's commands (decoded off the main thread, 2,000-command cap),
  `frame` asks for one frame (delivered at the next vsync while shown, with the first live canvas's size and
  pointer, `dark` and `fullscreen`), `submit`/`dismiss` stop the run and answer through RPC `respond`,
  `send` goes through RPC `send`. Events (taps, changes, canvas pointers, fullscreen) go in as
  `{ type: "event", event }`. A failed run shows its error with Restart.
- Fetch: `AgentWidgetFetchTransport` (OkHttp, no redirects/cookies/cache, DNS filtered with
  `isPublicAddress`); the runner enforces the rules. `AgentWidgetCapabilities.grantedHosts` is the one grant
  decision (everything declared is granted for now); granted hosts show as "Network: …" under the widget.
  Hosts come from the widget state's `capabilities`, else the program's.
- Live widgets offer full screen unless their root says `fullscreen: false`.

## Open

- psychoscript-mobile `3d0dc94`: a live run waiting on `widget.next({ timeout })` (a `Promise.race` of
  `receive` and `sleep`) and then on `widget.sleep` is stopped with SliceBudget ("computed for more than 50 ms
  without waiting") right after the timeout fires; `minigame` hits it after a pause/resume. Repro: the
  `wait-probe` live template (render, `await widget.next({ timeout: 10000 })`, `await widget.sleep(3000)`).

- Translations: the 10 `transcript_widget_*` strings have English only. `scripts/translate.py`'s
  default model (`opencode/gpt-6.1-sol`) was unavailable; rerun it (or with `--model`).
- No `@Preview`s for widgets yet; `AgentWidgetTests` covers the readers, machine and tool call.
