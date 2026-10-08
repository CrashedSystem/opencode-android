# iOS voice problems kept out of Android

On 2026-10-08 the iOS team reported GPT-Live calls that kept breaking, answered their own delegations, delegated again as if the user had spoken, cut out, and dropped in volume. A read-only investigation read Kit's call logs (9 calls) against upstream main at `998cb16` and the Android port on `voice-integration`. The user asked that these problems stay out of the Android app, so Android fixes the mechanisms below now and reconciles with upstream when its fixes land.

## Mechanisms

| Symptom | Mechanism (upstream main) | On Android before the fix | Android decision |
| --- | --- | --- | --- |
| Answers its own delegation; speaks old results after the user moved on | GPT-Live receives up to three role-less messages per delegation: every delegator step as `session.thinking.append` (`VoiceFeature.swift:929-931`), the answer as `session.commentary.append` (`VoiceFeature.swift:726`), and a late reply as commentary with no ID (`VoiceFeature.swift:734-738`). The quiet gate (`VoiceCallClient.swift:1755-1778`) releases them at the first pause even after newer speech. GPT-Live speaks after each one. In one call it spoke 7 of 13 progress notes aloud. | Same code (`VoiceFeature.kt`, `VoiceCallClient.kt`) | Keep delegator step notes in the call log only; stop sending them to GPT-Live. Leave the held-result rule to upstream: no rule has been tested against gpt-live-1. |
| Delegates again "as if it was me" | A delegation with no new user words still sends the delegator a "The user's request:" filled with the agent's own acknowledgement or the last caption, as a `user` message (`VoiceFeature.swift:877-878`, `VoiceDelegator.swift:141,153`). Found by reading the code; all 38 requests in Kit's logs carried new user words. | Same code | Fail a delegation that has no new user words instead of making up a request. |
| Cuts out | Each 100 ms reply chunk plays as it arrives with nothing queued ahead (`VoiceAudioClient.swift:965-983`), while arrival gaps reach p95 406 ms. Kit's own agent removed the gaps in the simulator with a 0.2 to 0.5 s pre-roll, which is not on main. | `PlaybackMixer` plays speech the moment it is queued over a track buffer of at least 40 ms | Start a reply once 200 ms is queued, and wait for 500 ms after it runs dry. |
| Volume drops | Dictation's `prepare()` replaced the call's audio category and moved audio to the earpiece; fixed upstream in `e686e59` and `4b8736e`. With Raise to Talk on, any "near" proximity reading moves the call to the earpiece with no held-up check (`RaiseToEar.swift:61-65`). | Only `VoiceAudioClient.kt` sets the audio mode, and it restores mode and device when they change | Move to the earpiece only after `isHeldUp` and the 350 ms wait. |
| Calls break on a weak network | Uplink stalls: microphone sends took up to 11 s and most chunks dropped (`998cb16` diagnoses the call). The app can only warn. | No warning | Port `998cb16`'s weak-connection warning and network log. |
| Echo | Not supported by the logs: no user line repeats the agent's recent speech. | Platform echo cancellation (`VOICE_COMMUNICATION`, `AcousticEchoCanceler`, `USAGE_VOICE_COMMUNICATION`) | Keep it. Never fix volume with `USAGE_MEDIA` or gain. Log whether the echo canceller was created, since it varies by device maker. |

## Reconciling with upstream

Ryan Vogel has an unreleased fix for the repeated delegations. When it lands, port it and drop whichever Android change it replaces. Each Android change is listed in `deviations.md` under Voice calls until then.
