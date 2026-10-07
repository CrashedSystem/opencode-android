# Porting OpenCode iOS to Android

This is the run plan for the first port and the reference for later syncs. The decision trail is `docs/port/decisions.tsv`.

## Definition of done

The port is done for this run when every statement below can be checked and holds.

1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes from a clean checkout.
2. On an API 36 emulator the app pairs with a real OpenCode V2 server, lists sessions in the drawer, opens a session, renders its transcript, streams a live reply over SSE, answers a pending permission and question, and runs the session menu actions. Each step is proven by a screenshot or a UI dump from the emulator.
3. For every captured iOS reference state in `docs/port/reference/ios/`, an Android screenshot of the same state with the same fixture data exists in `docs/port/reference/android/`, and a reviewer on a different model has compared each pair and listed every visible difference.
4. `scripts/ios-sync.py check` passes. Every iOS source file is ported (a Kotlin file carries `@file:PortedFrom`) or deferred with a written reason in `docs/port/sync.json`.
5. `AGENTS.md` and the `port-ios-changes` skill let an agent port a new iOS commit with `scripts/ios-sync.py plan` and `advance`.

## Scope

The iOS app is 37.6k lines of app Swift in 96 files plus 17k lines of tests (`scripts/ios-sync.py progress`). The largest files are `OpenCodeClient.swift` (3.3k), `SessionFeature.swift` (3k), `SessionView.swift` (2.4k), `VoiceCallClient.swift` (1.8k), and `TranscriptListViewController.swift` (1.6k). The state layer has 8 TCA features and 221 action cases.

Voice calls (about 7k lines across `Voice/`, `LiveActivity/`, `OpenCodeWidgets/`) depend on the user's OpenAI key and the GPT-Live service. They come last and may be deferred with a reason.

## Rigor

High. UI parity is the user's first requirement, and the architecture is a one-way door that every later iOS sync goes through. Gates per unit are a compile, the ported unit tests, and an emulator screenshot of the affected screen.

## Workflow

1. Ground: four explorers wrote `docs/port/explore/*.md`, synthesized in `docs/port/ios-architecture.md`.
2. Harness before features: the iOS reference screenshots and fixture notes in `docs/port/reference/`, the sync gate in `scripts/ios-sync.py`, and a toolchain smoke test on the emulator.
3. Architecture: a three-model arena designs the Kotlin TCA kit and the naming rules (`docs/port/arena/kit-task.md`). The synthesized design becomes `docs/port/architecture.md`.
4. Layer 0, sequential: Gradle project, the kit, the design system, platform clients, wire models, `OpenCodeClient`, pure models.
5. Layer 1, parallel by file ownership, one worktree per worker: leaf features and views that depend only on layer 0.
6. Layer 2 and 3: `SessionFeature` with `SessionView`, then `HomeFeature` with the drawer, then `AppFeature` and the activity.
7. Verify on the emulator against the live server and the fixtures, capture the Android screenshot set, and run the side-by-side review.
8. Voice, if time allows. Otherwise defer it in `sync.json` with the reason.

Each worker owns a disjoint set of files named after their iOS counterparts, so parallel branches merge without conflicts.
