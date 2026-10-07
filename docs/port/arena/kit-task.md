# Arena task: the Kotlin architecture kit for a 1:1 port of OpenCode iOS

## Context

We are porting the native OpenCode iOS app (SwiftUI + The Composable Architecture 1.26) to a native Android app in Kotlin + Jetpack Compose. Two product requirements drive the design:

1. The Android app keeps the iOS UI/UX, adapted to Android, and it must be fast. Streaming replies arrive as token deltas at up to 60 Hz while the transcript follows the bottom.
2. Future iOS changes must be easy to port. The iOS team keeps shipping. An agent that reads an iOS commit diff should be able to apply the same change to the Android code by translating it line by line, in the same file names, type names, action names, and state field names.

Requirement 2 makes the architecture a one-way door. Every one of roughly 30,000 Kotlin lines will be written against it.

## Grounding (read before designing)

- iOS source, read-only: `/Users/nxl/code/sandbox/opencode-android/.upstream/opencode-ios` (commit 4fb5e0e). `AGENTS.md` there is the product and architecture spec.
- `/Users/nxl/code/sandbox/opencode-android/docs/port/explore/state.md`: how TCA is used. Feature tree, reducer composition order, effect cancellation IDs, `navigationID` as the effect lifetime token, `@Shared` keys and their storage, bindings, presentation state, and 25 non-obvious behaviors a port can get wrong. This is the most important input.
- `/Users/nxl/code/sandbox/opencode-android/docs/port/explore/ui.md`: view hierarchy, tokens, the UIKit transcript list and its frame-clock follow behavior.
- `/Users/nxl/code/sandbox/opencode-android/docs/port/explore/platform.md`: platform features and their Android analogs.
- `/Users/nxl/code/sandbox/opencode-android/docs/port/explore/protocol.md` (may still be in progress): routes, wire models, the SSE stream.

## What to design

A design package for:

1. **The TCA kit in Kotlin.** Store, reducer composition (the analogs of `Reduce`, `Scope`, `ifLet` with child-first ordering and child-effect cancellation when the child's identity changes or goes nil, `@Presents`/`PresentationAction`, `BindingReducer`, `onChange(of:)`), effects (`none`, `send`, `run`, `merge`, `cancellable(id:cancelInFlight:)`, `cancel(id:)`), dependency clients with live and test values and `isTesting`, the clock, `@Shared` with its storage strategies (keychain, app storage, file, in-memory, plus the custom transcript cache key), and a `TestStore` analog with virtual time.
2. **State representation.** Decide how feature state is represented in Kotlin and defend it. The iOS code mutates value-type structs through `inout`. Show how this exact Swift line translates under your design: `state.items[id: id]?.liveText += delta`, and how an effect that captures `state.session.id` behaves. Name the aliasing, threading, and observation hazards and how the design rules them out.
3. **Compose integration.** How views read state with fine-grained recomposition so a token delta does not recompose the composer or the drawer. How `$store.draft` style bindings, `.task` lifecycle effects, sheets driven by optional child state, and alerts over optional fields map to Compose. Where the root store lives so it survives configuration changes (Android 17 forces resizability on large screens) and what happens on process death.
4. **File and naming mapping rules.** How an iOS file path, type, enum case, and state field map to Kotlin names and paths. State the rules precisely enough that a script could check them.
5. **Threading of the event stream.** Where SSE decoding and the `EventBatcher` (75 ms, adjacent-delta merge, cap 256, `bufferingNewest(128)`) run, and how batches enter the store.

## Required evidence

- The type sketch as Kotlin source files with `TODO()` bodies where logic is not essential, compiled. Copy the toolchain-verified template at `/private/var/folders/fm/46zkyn094pn2cmkjq62skpxr0000gn/T/opencode/android-smoke` into your output directory and put your sketch in its `app` module. `./gradlew compileDebugKotlin` must pass. Java comes from the macOS JDK registry (`java -version` prints 25), and the SDK is `~/Library/Android/sdk`.
- Two worked ports, side by side with their Swift originals in the rationale:
  - `OpenCode/WhatsNew/WhatsNewFeature.swift` ported in full to your kit, with one test in the `TestStore` analog that mirrors a test from `OpenCodeTests/WhatsNewTests.swift`.
  - A slice of `OpenCode/SessionFeature.swift`: the handling of one live content batch (text delta applied to the projected row) and one cancellable effect keyed by an ID, with a test that proves the cancellation.
- Kit tests that run: `./gradlew testDebugUnitTest` must pass for your worked ports and for the kit's own ordering and cancellation semantics (child-first `ifLet`, child effects cancelled when identity changes, `cancelInFlight`).

## Rubric (how candidates are judged)

1. Mirror fidelity. A Swift TCA diff ports line by line, and the semantics in `state.md` (ordering, cancellation, bindings, `onChange`, `@Shared`) are preserved, not approximated.
2. Hazards ruled out by construction: value-vs-reference aliasing, off-main mutation, effect lifetime across screen changes and configuration changes.
3. Streaming performance in Compose: token deltas recompose only the affected row.
4. Testability: exhaustive state assertions and received-action assertions with virtual time.
5. Depth: a small public surface that hides the hard parts, so the nearest example an agent copies is the right one.
6. Evidence: compiles, tests pass, worked ports read like their Swift originals.

## Output

Write everything under your assigned output directory:

- `design.md`: the rationale, shaped per `/Users/nxl/code/sandbox/oc-pstack/skills/architect/references/rationale-template.md`, including the Swift-vs-Kotlin side-by-side snippets.
- The compiled project with the kit, the worked ports, and the tests.
