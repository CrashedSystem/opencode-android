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

- Runner: `opencode-widget-ffi` through UniFFI/JNA (`NativeAgentWidgetEngine`). Run
  `scripts/widget-ffi.sh <ffi-out/android>` to install the `.so` files (gitignored) and refresh
  the generated Kotlin; without them widgets show "The widget runtime isn't available".
- Shader: `AgentWidgetGL` (one GL thread and context, programs cached by GLSL, 96-byte UBO bound
  to `Widget_block_0Fragment`), `AgentWidgetShader` (`TextureView` per node, `withFrameNanos`,
  pause off screen / list not resumed, 50 ms × 3 stop, poster/alt on failure). The FFI's GLSL
  entry point flips `frag.y`, so no transform is needed.
- Canvas: step and draw-list decode on the widget's lane, own program copy while animating,
  frames only while visible, `Budget` errors count toward the 3-overrun stop.

## Workflow (Mac)

- JDK: Android Studio's JBR (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`);
  SDK `~/Library/Android/sdk` (cmdline-tools and NDK 29 installed there).
- Dev server: Enter a Server Manually → `http://10.0.2.2:4097`, user `opencode`, password from
  `oc-server.log`. Session lab: `python3 -B …/opencode-ios/scripts/session-lab.py --port 4112
  serve --checkpoint widgets`, connect to `http://10.0.2.2:4112`.

## Open

- Translations: the 10 `transcript_widget_*` strings have English only. `scripts/translate.py`'s
  default model (`opencode/gpt-6.1-sol`) was unavailable; rerun it (or with `--model`).
- `image` canvas draw command is parsed but not drawn.
- A vertical drag that starts inside a multiline widget `input` doesn't scroll the list.
- The bundled `.so` predates the FFI source's `Budget` error and view normalisation; rebuild
  with `build-android.sh` (NDK 29 is installed) when the FFI agent publishes again.
- No unit tests or `@Preview`s for widgets yet (machine, node parse, draw-list decode, payloads).
