# Voice port brief

Voice calls are being ported at upstream `d809b1e` (newest main on 2026-10-08). The shared contract is checkpoint `d021b9f` on branch `voice-core`: `VoiceFeature`'s State, Action, Delegate and CancelID; every voice client interface with live and test values; and the event, protocol, and model types they carry. Every voice worker branches from it and builds against it without changing its declarations. If a declaration is wrong, keep the old shape compiling, add what you need, and say so in your report.

Read `docs/port/architecture.md`, `docs/port/worker-brief.md`, and in `.upstream/opencode-ios/AGENTS.md` every paragraph on voice calls. Read upstream files with `git -C .upstream/opencode-ios show d809b1e:<path>`; do not change `.upstream`'s checkout. Port files in full and mark them `@file:PortedFrom("<path>", "d809b1e")`.

## Groups

| Group | Owns | Tests |
| --- | --- | --- |
| 1. Network | `RealtimeProtocol.kt`, `RealtimeClient.kt`, `LiveProtocol.kt`, `LiveClient.kt`: JSON, OkHttp WebSockets, socket warmer, error mapping | `RealtimeProtocolTests`, `LiveProtocolTests` |
| 2. Audio and haptics | `VoiceAudioClient.kt` (AudioRecord and AudioTrack with echo cancellation and noise suppression, the call's sound clock, synthesized cues), `VoiceAudioQuality.route`, `VoiceHapticDesign.kt`, `VoiceHapticEngine.kt`, voice haptics in `HapticsClient.kt`, the end-tone hook in `DictationClient.kt` | `VoiceHapticTests.kt`, `LiveHapticsTests` |
| 3. Call orchestration | `VoiceCallClient.kt` (the call host, both coordinators, turn history), `VoiceLabDemo.kt` | `VoiceResumeTests`, `LiveCallCoordinatorTests` |
| 4. Reducer | the `VoiceFeature.kt` body, `VoiceTranscript.kt` instruction builders, `VoiceDelegator.kt`, `VoiceDelegatorWorkspace.kt`, voice changes in `SessionFeature.kt` and `HomeFeature.kt` | `VoiceFeatureTests`, `SessionVoiceTests`, `VoiceTranscriptTests`, `RaiseToTalkTests`, `VoiceEnginePersistenceTests`, `LiveDelegationFeatureTests`, `VoiceResumeFeatureTests`, the rest of `VoiceHandoffParseTests.kt` |
| 5a. Voice UI | `VoiceView.kt`, `DotScopeView.kt` with `DotScopeShaders.metal` rewritten in AGSL (and a fallback for API 31 and 32), `DotScopeSimulation.kt`, `VoiceCallLogView.kt`, `VoiceLabTriggers.kt`, `RaiseToEar.kt`, voice surfaces in `SessionView.kt` and `SettingsView.kt` | `DotScopeSimulationTests.kt`, `CaptionRevealTests` |
| 5b. Call notification | `VoiceLiveActivityClient.kt`, `VoiceCallActivity.kt`, `OpenCodeWidgets/VoiceCallLiveActivity.swift` as a microphone foreground service with an ongoing call-style notification offering Mute and End, the manifest | `VoiceLiveActivityTests.kt` |

The integration step owns `OpenCodeUITests/VoiceCallTests.swift` and `VoiceLiveActivityUITests.swift`, which become on-device flows once every group has merged.

## Shared files

- `VoiceFeatureTests.kt`, `VoiceLiveTests.kt` and `VoiceResumeTests.kt` hold one Kotlin class per upstream suite, each an `@Ignore` stub naming its owning group (`scripts/voice-test-outline.py` regenerates them). Fill only your own classes.
- Leave `hangingUpDuringStalledHandshakeStopsRingsAndAudioPromptly` (needs group 2's sound clock) and `liveInstructionsFollowPromptingGuideTemplate` (needs group 4's `buildLive`) ignored for integration.
- Do not edit `docs/port/sync.json` or `docs/port/decisions.tsv`; the coordinator does.
- Swift `Data` is `okio.ByteString`; `ContinuousClock.Instant` is `TimeSource.Monotonic.ValueTimeMark`; streams are `Flow`. Android's gravity sensor reports the opposite sign to iOS, so flip it before `isHeldUp`.

## Verify and report

`./gradlew assembleDebug testDebugUnitTest` passes in your worktree, your upstream tests pass with their Swift literals, and anything you can only check on a device is checked on the emulator the coordinator names in your prompt. Without a named emulator, prove what Robolectric can prove and write the device checks as an instrumented test under `app/src/androidTest` that the integration step runs; never install onto an emulator another worker owns. Never use a real OpenAI key in tests; the integration step places the one live call. Report in at most 15 lines: commits, Swift and Kotlin lines, tests passing and still ignored, device checks, and any contract change.

## Integration checklist

The coordinator merges each group into `voice-integration` (from d021b9f) as it lands, runs the full unit suite, and adds what the group leaves open here.

- Group 1 (merged at `9e044f3`): un-ignore the six `RealtimeProtocolTests` cases that need `VoiceCallClient` once group 3 lands. Done at integration: `session.update` now uses `VoiceInstructions.tools`, and `liveInstructionsFollowPromptingGuideTemplate` is ported and passing.
- Group 1: a WebSocket connect failure after a passing preflight ends the call with "The voice connection was lost." iOS shows the system text of the raw `URLError`. Decide whether to match it.
- Group 4 (merged at `0737c2c`): un-ignore `abandoningDelegationOnSessionSwitchClearsOpenDelegationBeforeReconnectSeed` once group 3's coordinator harness lands. Group 4 added `VoiceKeyFingerprint` to `SettingsView.kt`; if group 5a adds it too, keep one. Done at integration: the kit's `ImmediateClock` now yields like swift-clocks, so `hangingUpAbandonsVoiceWorkButNotTheServersRun` asserts Swift's order again.
- Group 5b (merged at `09ff62b`): run `VoiceCallNotificationDeviceTest` on a device, and check there that a denied notification permission hides the notification without blocking the call. Done at integration: its three reducer-dependent `VoiceLiveActivityTests` run and pass, `sync.json` tracks the Dynamic Island as the unported rest, and `deviations.md` has a Voice calls section.
- Group 3 (merged at `546f141`): port the six empty `RealtimeProtocolTests` stubs that wait for `VoiceCallClient` (`aDelegateResultNeverStartsAReplyWhileTheUserIsTalking`, `aResultThatArrivesMidReplyIsQueuedBehindIt`, `bargeInAfterGenerationEndedStillTruncatesWhatWasHeard`, `bargeInFlushesTruncatesCancelsAndDropsLateAudio`, `bargeInRacesStaySilent`, `abandonedEventStreamHangsUpTheCall`), the empty `abandoningDelegationOnSessionSwitchClearsOpenDelegationBeforeReconnectSeed`, and `TraceTests.marksEachTurnOfACall`. Group 2 must give the lab call its `VoiceCallSounds` call clock, and must not end `start()` inside a `withContext`, or a cancelled caller loses the engine it just started.
- The second sync (merged into `voice-integration` at `87fa6fe`) left these voice hunks, each a `partial` entry in `sync.json`: the Voice settings page's dot scope preview (494e433), Raise to Talk's toggle and footer (2e313a1) and its detector task in `SessionView`, the GPT-Live socket warmer in `SessionView` and `VoiceKeyFingerprint` in Settings (f3bda52, and group 4 already added one `VoiceKeyFingerprint`), the Diagnostics Voice Calls row and saved-call uploads plus `DeveloperMode.upload(VoiceCallLog)` (494e433, which also renamed `voiceCallUploads` to `developerMode` and `voiceCallUploadOwner` to `developerModeOwner` with the same storage keys), and `HomeFeature`'s handling of the `setPinned` and `openDraft` delegates (5cfc623), which group 4 ported only on the `SessionFeature` side.
- Upstream moved past d809b1e. Voice commits `998cb16` (weak-connection warning, network log, why calls move) and `e001892` (haptics keep playing while dictation or a call records) must be ported before voice merges into main. Non-voice commits `083cd94`, `8adf650`, `637c1fa`, `4482259`, `f79782d` and `a411d3d` go in the third sync. Vogel's pending fix for GPT-Live answering its own delegations must be ported once it lands.
- Group 2 (merged at `82b1d04`): `VoiceAudioEngine.start()` returns from inside `withContext(actor)`, so a caller cancelled while the engine starts gets a `CancellationException` and the started engine keeps the microphone and the communication mode with no owner. Swift hands the session back and the coordinator stops it. Stop the engine in `start()` when its result is discarded, and prove it with a test. Barge-in does not flush the `AudioTrack`, so up to its buffer (at least 40 ms) of the reply still plays after the user speaks over it; measure that on a device and flush if it is audible. `bloomArrivalsMatchTheScannersFronts` and `hangUpLandsAsTheSweepReachesEnd` wait for group 5a's `Scan` init. Done at integration: `hangingUpDuringStalledHandshakeStopsRingsAndAudioPromptly` runs and passes, and `HapticsClient.swift` left `sync.json` `partial`.
- Run group 2's `VoiceAudioOnDeviceTest` and group 5b's instrumented test on a device.
- Port `OpenCodeUITests/VoiceCallTests.swift` and `VoiceLiveActivityUITests.swift` as on-device flows.
- Clear the voice entries from `sync.json` `partial` and move the voice markers in `SessionView.kt`, `SettingsView.kt` and `HapticsClient.kt` once the second sync has merged.
- Place a demo call on the emulator, then one live call each over Realtime and GPT-Live with the key in `~/.config/opencode-android/openai_api_key`.
