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

- Group 1 (merged at `9e044f3`): un-ignore the six `RealtimeProtocolTests` cases that need `VoiceCallClient` once group 3 lands, and switch the `session.update` test from `delegateTool` and `endCallTool` to `VoiceInstructions.tools` once group 4 lands.
- Group 1: a WebSocket connect failure after a passing preflight ends the call with "The voice connection was lost." iOS shows the system text of the raw `URLError`. Decide whether to match it.
- Un-ignore `liveInstructionsFollowPromptingGuideTemplate` (group 4) and `hangingUpDuringStalledHandshakeStopsRingsAndAudioPromptly` (group 2).
- Run group 2's `VoiceAudioOnDeviceTest` and group 5b's instrumented test on a device.
- Port `OpenCodeUITests/VoiceCallTests.swift` and `VoiceLiveActivityUITests.swift` as on-device flows.
- Clear the voice entries from `sync.json` `partial` and move the voice markers in `SessionView.kt`, `SettingsView.kt` and `HapticsClient.kt` once the second sync has merged.
- Place a demo call on the emulator, then one live call each over Realtime and GPT-Live with the key in `~/.config/opencode-android/openai_api_key`.
