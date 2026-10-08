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

`voice-integration` holds main's second sync and voice groups 1, 2, 3, 4 and 5b plus the voice wiring (998cb16 without the call bar, Developer Mode call log uploads). It passes 1087 tests with 17 ignored, and `ios-sync check` lists only the voice UI port's files. What is left, in order:

1. Merge `voice-ui` (group 5a). Expect conflicts in `SessionView.kt`, which the second sync also changed.
2. Port onto the redesigned Settings and Diagnostics:
   - the Voice page's dot scope preview (494e433)
   - Raise to Talk's toggle and footer (2e313a1)
   - `VoiceKeyFingerprint` (f3bda52; group 4 already added one in `SettingsView.kt`, so keep one)
   - the Diagnostics Voice Calls row opening `VoiceCallLogList` (494e433)
3. Port into `SessionView.kt`: Raise to Talk's detector task (2e313a1), the GPT-Live socket warmer (f3bda52), and 998cb16's weak-connection warning on the call bar (its `VoiceView.swift` hunk).
4. Enable `CaptionRevealTests` (7 cases) and `VoiceHapticTests`' `bloomArrivalsMatchTheScannersFronts` and `hangUpLandsAsTheSweepReachesEnd`.
5. Done: `voice-audio-fixes` merged at `f298db1` with its deviations. Check the reply pacing in the live GPT-Live call's log: on a strict 100 ms pace the pre-roll trades several short cuts for one longer one.
6. Done for delegations (`voice-delegation` merged at `0f274cf` with its deviations). The Raise to Talk earpiece rule comes with `voice-ui`; add its deviation line.
7. Done: upstream `e001892` merged with `voice-delegation`. Dictation now releases the recognizer when a drawer switch cancels it.
8. Done: the connect-failure text stays "The voice connection was lost.", recorded in `deviations.md`.
9. On a device:
   - Run `VoiceAudioOnDeviceTest`, `VoiceCallNotificationDeviceTest` and `VoiceCallLogNetworkOnDeviceTest`.
   - Check that a denied notification permission hides the notification without blocking the call.
   - Measure the leftover reply audio after a barge-in.
   - Port `OpenCodeUITests/VoiceCallTests.swift` and `VoiceLiveActivityUITests.swift` as on-device flows.
10. Place the lab demo call (`OPENCODE_LAB_VOICE_DEMO`). Then make one live call each over Realtime and GPT-Live with the key in `~/.config/opencode-android/openai_api_key`, including a delegation. Throttle the network with `adb emu network speed gsm` and check that "Weak connection" shows.
11. Clear the voice entries from `sync.json` `partial`. `ios-sync check` must pass. Then merge `voice-integration` into main.

## After integration: the WebRTC voice sync

Upstream moved both engines from WebSockets to WebRTC after d809b1e. Port these once `voice-integration` has merged into main:
- `e377b41` (GPT-Live over WebRTC: `LiveRTC.swift`, the app's audio engine stays in charge with WebRTC's own processing off, a WebSocket fallback through `LiveTransportChoice`, WebRTC stats in the weak-connection warning, and the lab's `OPENCODE_LAB_VOICE_INPUT` and `OPENCODE_LAB_OPENAI_KEY`)
- `9e1aad3` (Realtime over WebRTC too, and the "Steadier Voice Calls" What's New entry)
- `6994288` (Demo Mode plays calls as media for USB recordings, with its Diagnostics toggle and `voiceDemoMode` shared key)
- the third sync's voice hunks: `SessionFeature`'s `isVoiceAvailable` with `VoiceLabDemo.labAPIKey` and `SessionView`'s warm-up lab key (both `e377b41`)

Android needs a WebRTC library whose audio device takes the app's own capture and hands back decoded speech, the way iOS's `LiveRTCAudioDevice` does. Choose it on evidence. Then revisit the reply pre-roll in `deviations.md`: WebRTC's jitter buffer delivers speech at a steady pace.
