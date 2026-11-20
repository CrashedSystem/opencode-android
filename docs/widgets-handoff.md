# Agent widgets on Android: handoff

WIP from the VM agent; **it doesn't build yet** (see Known problems). Contract:
`specs/v2/widgets.md` (opencode `widgets` branch); canvas/shader/`send` additions are on the
`widget-canvas` branch (`packages/widget/src/{prelude.js,node.ts}`, built-ins `chart.js` and
`backdrop.js`). Reference client: rune-desktop `crates/opencode-widget` (machine, runner, node),
`crates/opencode-widget-view` (frame, Local), `features/widgets`.

## Done (code written, not compiled end to end)

`:server`
- `model/Widgets.kt`: `OpenCodeWidgetRef` (name@version, validated), `OpenCodeWidgetMessage`
  (data/response kept as raw JSON text), `OpenCodeWidgetResponse` (respond body),
  `OpenCodeWidgetMessageValue` (`send` body `{ value, wake }`).
- `model/OpenCodeTranscriptItem.kt`: new items `Widget` and `WidgetResponse` (quiet `◈` line).
- `live/Payloads.kt`: `MessagePayload` decodes `type: "widget"` leniently (bad ref/status → no row).
- `live/LiveTranscript.kt`: `"widget"` → `Widget` item; `syntheticNotice` turns a synthetic with
  `metadata.widget.messageID` into `WidgetResponse(description)`.
- `live/LiveEvents.kt`: `session.widget.posted` / `session.widget.responded` → `MessageInvalidated`
  (REST stays authoritative, as for other content events).
- `live/LiveWidgets.kt`: `GET /api/experimental/widget/template/{name}/{version}` → `data.program`;
  `POST …/widget/{messageID}/respond`; `POST …/widget/{messageID}/message` (send); `isWidgetAlreadyAnswered` (409).
- `AgentWidgetClient.kt`: dependency client (`program`, `respond`, `message`) + `AgentWidgetProgramCache`
  (disk cache forever: `filesDir/support/widget-templates/<name>@<version>.js`, tmp+rename).

`:feature:transcript` `widgets/`
- `AgentWidgetEngine.kt`: interface for the FFI (`compile(program) → AgentWidgetProgram.step(json)`,
  `translateShader(wgsl)`), `Unavailable` stub; `NativeAgentWidgetEngine.kt` returns null (stub).
- `AgentWidgetNode.kt`: node model + parser (all nodes incl. canvas/shader; unknown types dropped,
  bad required props fail the view).
- `AgentWidgetStep.kt`: step inputs built as JSON text (init/event/draw), output parse (state,
  view, submit/dismiss, `send`), events (`press/change/pointer`), submitted-value summary.
- `AgentWidgetMachine.kt`: pure port of rune `machine.rs` (Loading → Starting → Ready ⇄ Stepping →
  Responding → Closed, Failed + Retry, 409 → "already answered", `send` → `Effect.Send` stays Ready,
  `SendFailed` inline). Closed keeps the init state for canvas drawing.
- `AgentWidgetPrograms.kt`: process-wide compile-once LRU (12) by name@version behind a key;
  `AgentWidgetLane` = `Dispatchers.Default.limitedParallelism(1)` per widget (serialized steps).
- `AgentWidgetStore.kt`: list-owned store (to retain like `LinkPreviewStore`) + `AgentWidgetController`
  (Compose-state `phase`, runs effects, queues/coalesces events while stepping, input drafts flushed
  before other events, `draw(frame)` on the lane, perf logs via `AppLog.perf`).
- `AgentWidgetView.kt`: `AgentWidgetRow` (uses `LocalTranscriptRowContext` + `LocalAgentWidgetStore`),
  `AgentWidgetFrame` (loading skeleton, error card + Try Again, inline step error, Sending…,
  Submitted · summary / Dismissed), `AgentWidgetResponseLine` (`◈ …`).
- `AgentWidgetNodes.kt`: column/row (cards scroll sideways at ≤260 dp; ≥2 buttons wrap)/text/markdown
  (app Markdown)/carousel (HorizontalPager + dots, `index` sync, `change` with id)/card (selectable,
  accent border)/button (OpenCode buttons)/checkbox/slider (emits on release, step snapping)/select
  (chips or radio/check rows)/input (300 ms debounce, Done, blur)/divider.
- `AgentWidgetImage.kt`: http via Coil, relative/`data:` via `OpenCodeClient.markdownImage` (server
  file endpoint), downsampled decode, `DecodedImageCache`, reserved aspect box.
- `AgentWidgetDrawList.kt`: allocation-light JSON pull parser for draw commands into flat int/float
  arrays, theme tokens resolved at decode, 2,000-command cap.
- `AgentWidgetCanvas.kt`: Compose `Canvas` + `withFrameNanos` loop (fps, `duration`), double-buffered
  draw lists, native-canvas replay with reused Paint/Path, 8 ms × 3 overrun stop, pointer down/move/up
  (horizontal drags consumed, vertical lets the list scroll), perf log every 120 frames.

## Not done / stubbed

- **FFI not wired**: `NativeAgentWidgetEngine.load()` returns null, so every widget shows the error
  card. When `opencode-widget-ffi` lands (`ffi-out/android/`): copy `.so` to
  `feature/transcript/src/main/jniLibs/{arm64-v8a,x86_64}/`, add the generated Kotlin, add JNA
  (`net.java.dev.jna:jna:<v>@aar`) to `gradle/libs.versions.toml` + AGENTS.md justification, and
  implement `load()` (map FFI errors to `AgentWidgetEngineException`, timeout flag).
- **`AgentWidgetShader` doesn't exist** (referenced from `AgentWidgetNodes.kt`). Plan: `TextureView`
  in `AndroidView`, one shared EGL context on a GL thread, program cache by GLSL hash, GLSL from
  `engine.translateShader(header + wgsl + entry)` (check whether the FFI wraps it itself), std140
  UBO (resolution vec2, time, scale, pointer vec4 @16, values vec4[4] @32; 96 bytes; get the block
  name via `glGetActiveUniformBlockName`), full-screen-triangle vertex shader, flip Y via
  `TextureView.setTransform` (gl_FragCoord is bottom-left), physical-pixel frag/pointer, pause when
  detached/not resumed, 50 ms × 3 stop, `poster`/`alt` on failure. `backdrop` uses pointer locally.
- **Transcript not hooked up**: add `is Widget`/`is WidgetResponse` branches to every exhaustive
  `when` (compiler lists them; first: `TranscriptEntry.kt:243`; Widget → `flush(); Single(item)`,
  WidgetResponse → `endReply(); flush(); Single(item)`), render in `rows/TranscriptEntryView.kt`
  (`AgentWidgetRow(item.widget)`, `AgentWidgetResponseLine(item.text)`), and in `list/TranscriptList.kt`
  `retain { AgentWidgetStore() }` + `RetainedEffect { onRetire { close() } }` + provide
  `LocalAgentWidgetStore`. Also `ParsedMessageCache`/session/drawer `when`s in other modules.
- **New spec bits not done**: `type: "system"` messages with `metadata.widget = { messageID, kind:
  "response" | "message" }` → `WidgetResponse` line (`◈ Responded to "<title>"` / `◈ <title>: <short value>`);
  `MessagePayload` doesn't decode `system` description/metadata yet (check `Payloads.kt`).
- **Strings missing** (`feature/transcript/src/main/res/values/strings.xml`, then
  `scripts/translate.py all` + `scripts/translations.py all`): `transcript_widget_loading` "Loading
  widget…", `transcript_widget_failed` "Couldn’t show this widget", `transcript_widget_sending`
  "Sending…", `transcript_widget_submitted` "Submitted", `transcript_widget_dismissed` "Dismissed",
  `transcript_widget_already_answered` "This widget was already answered.", `transcript_widget_not_open`
  "This widget isn’t open any more.", `transcript_widget_step_failed` "The widget couldn’t respond.",
  `transcript_widget_page` "Page %1$d of %2$d".
- Tests (machine, node parse, draw list decode, payload decoding, 409), `@Preview`s with static
  trees, parity rows (`docs/parity/transcript.md`, `server.md`; iOS has no widget code yet).

## Known compile problems

- `AgentWidgetNode.Canvas` lacks `duration: Float?` (used by `AgentWidgetCanvas.kt`; parse `duration`).
- `AgentWidgetEvent.pointer(...)` takes 4 args; canvas calls it with width/height (6). Add
  `width`/`height` to the pointer value (new spec).
- `AgentWidgetShader` missing; strings missing; transcript `when`s non-exhaustive.
- `CanvasRenderer.paints` returns a `Pair` per shape (boxing): replace with fields for the cursors.
- `IMAGE` draw command is parsed but not drawn.
- Unverified API names: `Modifier.openCodeHairline(shape)` usage, `FlowRow(itemVerticalAlignment=)`,
  `SFSymbol.PhotoBadgeExclamationmark`, `OpenCodeTextField` params.

## Environment notes

- VM had JDK 17 + SDK at `~/android-sdk` (`local.properties`, not committed); `:feature:transcript`
  compiled to the first exhaustive-`when` error, so the server changes compile.
- Mac: emulator `emulator-5554` (`Medium_Phone_API_35`, arm64) was started with a window and left
  running. No Mac builds were started. Dev server: `http://10.0.2.2:4097` from the emulator, password
  in `~/workspace/widgets/oc-server.log`.
