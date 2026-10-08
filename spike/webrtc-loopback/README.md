# WebRTC loopback spike

This module is a throwaway prototype on the `webrtc-spike` branch, and nothing here gets merged. It settles which WebRTC library and audio-device approach lets the Android port mirror the iOS WebRTC voice transport (`OpenCode/Voice/LiveRTC.swift` from upstream `e377b41` and `9e1aad3`).

## Decision

Use LiveKit's prebuilt `io.github.webrtc-sdk:android:150.7871.01` (M150, BSD-3-Clause) with the pure Kotlin device in `LiveRTCAudioDevice.kt`. It needs no NDK code.

- Capture. `JavaAudioDeviceModule.setAudioRecordEnabled(false)` stops WebRTC from opening an `AudioRecord`. WebRTC's record thread then asks `AudioBufferCallback` for every 10 ms frame, and the device fills it from the PCM the app delivered. The source is `sdk/android/src/java/org/webrtc/audio/WebRtcAudioRecord.java` lines 291-307 and 728-730 at `webrtc-sdk/webrtc@67c4578` (`m150_release`).
- Playout. `setSpeakerMute(true)` makes WebRTC's own `AudioTrack` write zeros, so it only sets the pace of the 10 ms pull. The decoded remote track reaches the app through `AudioTrack.addSink(AudioTrackSink)`. The sink delivers the mixer's 48 kHz, not the ADM's 24 kHz, so the device halves the rate before it hands on 40 ms chunks.
- Transport. Field trials go through `PeerConnectionFactory.InitializationOptions.setFieldTrials`. The event channel is `DataChannel.Init` with `negotiated = true` and `id = 4`. ICE-TCP uses `RTCConfiguration.tcpCandidatePolicy`, and the link stats come from `PeerConnection.getStats`. `LinkSample.from` in `Loopback.kt` reads the same keys as iOS's `LiveRTCLinkSample`.

## Alternatives

| Option | App capture and app-owned playout | WARP parity with iOS (M154) | Status |
| --- | --- | --- | --- |
| `io.github.webrtc-sdk:android:150.7871.01` (LiveKit) | Yes, through the fork APIs above | Yes. The offer carries `a=sctp-init` and `goog-sped-v1`, and DTLS 1.3 negotiates | Published 2026-09-05. 12.3 MB arm64 `.so` |
| `io.getstream:stream-video-webrtc-android:146.7.0` (Stream) | Yes, with the same fork APIs | No `goog-sped-v1`. That ICE option first appears in M150 | Published 2026-09-11. 12.5 MB arm64 `.so` |
| `io.getstream:stream-webrtc-android:1.3.10` (Stream, legacy) | No. Its `WebRtcAudioRecord` has no recordingless mode, so it always opens its own `AudioRecord` | No SNAP or SPED trials in the binary | Published 2025-09-11 |
| `ch.threema:webrtc-android:144.0.0`, `com.infobip:google-webrtc:1.0.48246t` | No. These ship upstream `JavaAudioDeviceModule` only, with no sink and no recordingless mode | Infobip has `goog-sped-v1`. Threema (M144) lacks it | Threema published 2026-01-08 and gives no support outside Threema. It patches codecs and ciphers. Infobip published 2026-07-29 |
| JNI `AudioDeviceModule` behind `AudioDeviceModule.getNative(long)` | Yes, the same push and pull as `RTCAudioDevice` | Matches the chosen prebuilt | Not built. It must copy the prebuilt's exact `webrtc::AudioDeviceModule` vtable, and LiveKit's `api/audio/audio_device.h` adds about eight virtuals to upstream's |
| Building libwebrtc | Yes, with any patch we want | Yes | Not built. Android builds need a Linux host and a rebase every milestone |

## Run it

Boot the AVD in the background from the main checkout with `scripts/boot-emulator.sh small_phone 5558`. Then run the modes, each in a fresh process:

```sh
ANDROID_SERIAL=emulator-5558 spike/webrtc-loopback/run.sh udp tcp apm chunk20
SECONDS_PER_RUN=30 ANDROID_SERIAL=emulator-5558 spike/webrtc-loopback/run.sh udp
WEBRTC=stream ANDROID_SERIAL=emulator-5558 spike/webrtc-loopback/run.sh udp tcp
```

`Loopback.kt` connects two peer connections in one process with the iOS configuration. The caller plays the phone. Its microphone is a 440 Hz tone that the app delivers in 100 ms chunks. The callee plays the voice service. It runs without Android's network monitor, so it can offer a passive TCP candidate as the service does on 443. Mode `tcp` strips UDP candidates from both descriptions. Mode `apm` leaves WebRTC's audio processing on as a control. Mode `chunk20` delivers 20 ms chunks. `results.txt` holds the output of the runs above.

## What the runs showed

- The tone arrived intact. Its 440 Hz share was 1.000 at -15.04 dBFS against -15.1 dBFS sent, with at most 0.2 dB of spread. With processing on, it fell to -23.46 dBFS with 2.71 dB of spread, so the four `goog*` constraints do turn processing off.
- WebRTC never touched the microphone. The manifest has no `RECORD_AUDIO`, `activeRecordingConfigurations` was empty during every call, and no record error fired.
- The pull ran in 10 ms frames. The 30 s run averaged 100.26 pulls per second, and NetEq stretched only 600 samples in that time. Six-second runs read between 98.2 and 102.1 per second. NetEq would have stretched far more for a steady 2% clock offset, so the spread comes from the emulator draining its output in bursts.
- The app's own 24 kHz `AudioTrack` played the chunks next to WebRTC's muted track with no underruns.
- ICE-TCP connected, with the phone connecting to the service's passive TCP candidate.
- Android binds each socket to its network and then calls `bind` with the any-address (`rtc_base/physical_socket_server.cc` lines 271-307), so WebRTC drops those host candidates as `0.0.0.0`. The phone's offer had no host candidates in 11 of 15 runs, and every call still connected through a peer-reflexive candidate. Whether the real service accepts such an offer is untested.
