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

- Runner: `psychoscript-mobile` (anomalyco/psychoscript `mobile`, rev in `ffi-out/android/REVISION`), the
  language only: `NativeAgentWidgetEngine` compiles `program + "\nreturn __widget_step(__input())"` once and
  `run`s steps at 250 ms and draws at 8 ms (a draw timeout counts as an overrun). Everything else widget-shaped
  is the app's: step inputs, output parsing and validation, the 2,000-command cap, rendering.
  `scripts/psychoscript.sh <dir>` installs the `.so` files (gitignored) and refreshes the committed Kotlin
  bindings; without them widgets show "The widget runtime isn't available". When the generic API's names
  land, only `NativeAgentWidgetEngine` changes.
- Shader: `AgentWidgetTransport.shader(server, wgsl)` → vertex + fragment GLSL ES 3.00 with uniform block
  names; `AgentWidgetShaderStore` caches it by sha256(target + wgsl) in memory and under
  `files/support/widget-shaders/`. Until the server's `opencode.widget` translation RPC exists, the transport
  translates on device (`LocalAgentWidgetShaderTranslator`, the spec's header and a GL entry that flips
  `frag.y`); the swap is that one lambda. `AgentWidgetGL` (one GL thread and context, programs cached by GLSL,
  96-byte UBO bound to the reported blocks), `AgentWidgetShader` (`TextureView` per node, `withFrameNanos`,
  pause off screen / list not resumed, 50 ms × 3 stop, poster/alt on failure).
- Canvas: step and draw-list decode on the widget's lane, own program copy while animating,
  frames only while visible, `Budget` errors count toward the 3-overrun stop.

## Server seam (contract change pending)

Widgets are becoming an internal server plugin: the history entry is the `widget` tool call,
and the REST routes become RPC calls on `/api/rpc/opencode.widget/<method>` with a `changed`
event. Everything server-facing lives in `widgets/AgentWidgetAdapter.kt`:

- `AgentWidgetState` (keyed by tool call ID, `handle` = what the server answers it by) is all
  the runtime and renderer see. The session passes `TranscriptConfiguration.widgets`
  (`AgentWidgetAdapter.states(items)`); `LocalAgentWidgetStates` reaches the rows.
- `AgentWidgetTransport` (program, shader, respond, send) wraps the REST `AgentWidgetClient` today.
- A `widget` tool call is its own transcript row (`ToolView` → `AgentWidgetToolRow`); a failed
  call stays in its tool group; the `widget` message adds no row.

For the swap: replace `states(items)` with the plugin's per-session states (refreshed on
`changed`) and the transport's calls (program, shader, respond, send) with the RPC methods; drop `OpenCodeWidgetMessage`
decoding in `:server`.

## Workflow (Mac)

- JDK: Android Studio's JBR (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`);
  SDK `~/Library/Android/sdk` (cmdline-tools and NDK 29 installed there).
- Dev server: Enter a Server Manually → `http://10.0.2.2:4097`, user `opencode`, password from
  `oc-server.log`. Session lab: `python3 -B …/opencode-ios/scripts/session-lab.py --port 4112
  serve --checkpoint widgets`, connect to `http://10.0.2.2:4112`.

## Open

- Translations: the 10 `transcript_widget_*` strings have English only. `scripts/translate.py`'s
  default model (`opencode/gpt-6.1-sol`) was unavailable; rerun it (or with `--model`).
- A vertical drag that starts inside a multiline widget `input` doesn't scroll the list.
- No `@Preview`s for widgets yet; `AgentWidgetTests` covers the readers, machine and tool call.
