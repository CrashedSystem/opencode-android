# Platform-integrated features (iOS, commit 4fb5e0e)

Source tree: `/Users/nxl/code/sandbox/opencode-android/.upstream/opencode-ios`. Read-only. Line counts are `wc -l` of the listed files.

There is no custom URL scheme, no universal link, and no `onOpenURL` handler. Pairing is a camera QR of a text payload (`OpenCodePairingLink.decode` in `AppFeature`), not a registered deep link. The only private URL scheme in code is `opencode-file`, and it is an in-process `OpenURLAction`, not an Info.plist scheme.

## Components Found

### App identity, lifecycle, permissions

| Artifact | What it is |
|---|---|
| `project.yml` (135) | XcodeGen. App `ai.opencode.mobilecode`, share `ai.opencode.mobilecode.share`, widgets `ai.opencode.mobilecode.widgets`. Team `5NZ4Q7NXJ4`. Marketing `0.1.0`, build `37`. iOS 18, iPhone only (`TARGETED_DEVICE_FAMILY: 1`). Swift 6, default actor `MainActor`. TCA `1.26.2`. |
| `OpenCode/Info.plist` (53) | Display name OpenCode. Camera, photo library, microphone, speech-recognition usage strings. `UIBackgroundModes` = `audio` only. `NSSupportsLiveActivities` true. `NSAllowsLocalNetworking` true. `ITSAppUsesNonExemptEncryption` false. Custom font `BerkeleyMonoVariable.ttf`. Empty `UILaunchScreen`. No `CFBundleURLTypes`. |
| `OpenCode/OpenCode.entitlements` (10) | App group `group.ai.opencode.mobilecode` only. No push, no associated domains, no keychain-sharing group. |
| `OpenCodeShare/Info.plist` (47) | Share extension. Activation: images ≤10, files ≤10, movies ≤10, text, one web URL. Principal class `ShareViewController`. |
| `OpenCodeShare/OpenCodeShare.entitlements` (10) | Same app group. |
| `OpenCodeWidgets/Info.plist` (29) | WidgetKit extension (`com.apple.widgetkit-extension`). No entitlements file in `project.yml`. |
| `OpenCode/OpenCodeApp.swift` (72) | `@main`. Builds `StoreOf<AppFeature>`, ends leftover Live Activities, DEBUG lab hooks. |

`OpenCodeApp` (`OpenCode/OpenCodeApp.swift:8-72`):

- `static let store` marks `app_launch` / `app_store_ready` via `LabMarks`, then `Store(initialState: AppFeature.State()) { AppFeature() }`.
- `init` fires `Task { await VoiceLiveActivityClient.liveValue.endAll() }` so a relaunch never keeps a call island (`OpenCodeApp.swift:17-18`). A call does not survive process death.
- DEBUG: `-lab-pasteboard-image` plus `OPENCODE_LAB_SERVER_URL` seeds a 16×16 indigo JPEG on `UIPasteboard.general` (`:24-38`). `OPENCODE_LAB_SERVER_URL` plus UserDefaults `lab-interface-style` / `lab-reduce-motion` force color scheme and reduce motion (`:57-70`).
- Body is `WindowGroup { AppView(store:) }`. Optional HotReloadUI in DEBUG.

Scene phase is not handled here. `AppView` (`OpenCode/AppView.swift:28-35`) on `.task` and on `scenePhase == .active` sends `pendingSharesRequested`. `HomeView` (`OpenCode/HomeView.swift:83-89`) on inactive/background sends `transcriptCacheFlushRequested` (which also publishes share targets) and records `app_<phase>` on the diagnostics timeline; on active it retries the SSE connection.

### Attachments (composer)

| File | Lines | Types |
|---|---:|---|
| `OpenCode/AttachmentMenu.swift` | 650 | `AttachmentMenuPhase`, `AttachmentMenuMotion`, `InlinePhotoFilter`, `AttachmentMenuOverlay`, `AttachmentPanelSurface`, `AttachmentMenuRow`, `SystemPasteControl` |
| `OpenCode/AttachmentCameraView.swift` | 899 | `AttachmentCameraView`, `AttachmentCameraControls`, `AttachmentLensPicker`, `AttachmentFocusIndicator`, `AttachmentCameraState`, `AttachmentCameraPreview`, `AttachmentCaptureSession` |
| `OpenCode/AttachmentCameraLenses.swift` | 234 | `CameraDeviceDescription`, `CameraZoomModel`, `CameraZoomState`, `AttachmentCameraFlash` |
| `OpenCode/AttachmentPicker.swift` | 72 | `AttachmentPickerButton` (max 4), `AttachmentMenuAnchors`, `StripPhotoLocation` |
| `OpenCode/AttachmentFileType.swift` | 116 | `AttachmentFileType` (shared with the share extension) |
| `OpenCode/ComposerFileAttachment.swift` | 117 | `OpenCodeFileReference`, `OpenCodePromptPresentation`, `OpenCodeTranscriptFile`, `OpenCodeUploadEvent`, `ComposerFileAttachment` |
| `OpenCode/ComposerFileImport.swift` | 78 | `ComposerFileSource`, `ComposerMovieFile`, `ComposerFileRouting`, `ComposerFileImporter` |
| `OpenCode/ComposerImageImport.swift` | 146 | `ComposerImageTransfer`, `ImageAttachmentImporter`, `ComposerItemProviderLoader`, `ComposerImageSource`, `ComposerImageImporter` |
| `OpenCode/InlinePhotoSelection.swift` | 84 | `InlinePhotoSelection` |
| `OpenCode/FileAttachmentViews.swift` | 186 | `FileAttachmentCard`, `TranscriptFileChips`, `AgentFileCards`, `ComposerFileChip` |
| staging lives in `OpenCode/ShareHandoff.swift:150-197` | — | `ComposerStagedFile`, `ComposerFileStaging` |

Wiring is in `SessionView` (`OpenCode/SessionView.swift`), not a TCA reducer. The reducer (`SessionFeature`) only receives staged files, upload progress, and send.

### Agent files

| File | Lines | Types |
|---|---:|---|
| `OpenCode/AgentFiles/AgentFileReference.swift` | 254 | `AgentFileReference`, `Kind`, `Target` |
| `OpenCode/AgentFiles/AgentFileLoader.swift` | 384 | `AgentFileError`, `AgentFileProbe`, `AgentFileLoader`, `DownloadProgressDelegate`, `StreamingFileLoader` |
| `OpenCode/AgentFiles/AgentFileViewer.swift` | 560 | `AgentTextDocument`, `AgentFileViewerModel`, `AgentFileViewer`, `AgentFilePage`, `AgentFilePlayer`, `QuickLookPreview`, `AgentCodeView` |

Request builder is `OpenCodeServer.fileReadRequest` in `OpenCode/OpenCodeClient.swift:138-146`.

### Share extension

| File | Lines | Types |
|---|---:|---|
| `OpenCodeShare/ShareViewController.swift` | 32 | `ShareViewController` |
| `OpenCodeShare/ShareView.swift` | 180 | `ShareView` |
| `OpenCodeShare/ShareModel.swift` | 229 | `ShareModel`, `SharedItemLoader` |
| `OpenCode/ShareHandoff.swift` | 322 | `AppGroup`, `SharedImageEncoder`, `ShareTargets`, `ShareHandoff`, `ComposerFileStaging`, `ReceivedShare`, `ShareHandoffStore` |
| `OpenCode/ShareInbox.swift` | 133 | `ShareInboxClient`, `SharedComposerDraft`, `SharedDraftTarget` |

`project.yml:62-64` compiles `ShareHandoff.swift` and `AttachmentFileType.swift` into both the app and the extension. The extension never talks to a server.

### Dictation

`OpenCode/DictationClient.swift` (344): `DictationClient`, `DictationLevels`, `DictationError`, `LiveDictation` (iOS 26), `BufferConverter`. Consumed by `SessionFeature` (`dictationPhase`, hold-to-lock, send-on-finish).

### Voice call

| File | Lines | Role |
|---|---:|---|
| `OpenCode/Voice/VoiceFeature.swift` | 772 | TCA reducer. One call, follows the open session / draft. |
| `OpenCode/Voice/VoiceView.swift` | 695 | Call bar, captions, failure card. |
| `OpenCode/Voice/VoiceCallClient.swift` | 1808 | `VoiceCallClient`, `VoiceCallHost`, `VoiceCallCoordinator` (Realtime), `LiveCallCoordinator` (GPT-Live). |
| `OpenCode/Voice/VoiceAudioClient.swift` | 1029 | `VoiceAudioEngine` (AVAudioEngine + voice processing), `VoiceAudioLevels`, `VoiceCallSounds`, `VoiceCallSynthesizer`, `PCM`. |
| `OpenCode/Voice/VoiceTurnTaking.swift` | 182 | Realtime floor bookkeeping. GPT-Live does not use this; it is full-duplex. |
| `OpenCode/Voice/RealtimeProtocol.swift` | 291 | Realtime JSON commands/events. |
| `OpenCode/Voice/RealtimeClient.swift` | 293 | WebSocket + model preflight. |
| `OpenCode/Voice/LiveProtocol.swift` | 445 | GPT-Live commands/events, `VoiceEngine`. |
| `OpenCode/Voice/LiveClient.swift` | 153 | GPT-Live WebSocket. |
| `OpenCode/Voice/VoiceWorkClient.swift` | 95 | Bridge to OpenCode server (create session, steer prompt, wait, reply). |
| `OpenCode/Voice/VoiceTranscript.swift` | 446 | Tail, `VoiceHandoff`, `VoiceInstructions` (Realtime tools + GPT-Live prompt). |
| `OpenCode/Voice/VoiceTimeline.swift` | 94 | Diagnostics marks composed in front of `VoiceFeature`. |
| `OpenCode/Voice/DotScopeView.swift` | 174 | Metal view. |
| `OpenCode/Voice/DotScopeSimulation.swift` | 691 | CPU LED-matrix simulation, haptics enum, sound scheduler. |
| `OpenCode/Voice/DotScopeShaders.metal` | 124 | Fragment shader: dots, bloom, side glows. |
| `OpenCode/Voice/VoiceLabDemo.swift` | 250 | DEBUG scripted call / stand-in server. |

### Live Activity / widget

| File | Lines | Types |
|---|---:|---|
| `OpenCode/LiveActivity/VoiceCallActivity.swift` | 100 | `VoiceCallActivityAttributes`, `VoiceCallCommand`, `VoiceCallCommands`, `EndVoiceCallIntent`, `ToggleVoiceMuteIntent`. Compiled into app and widget (`project.yml:86`). |
| `OpenCode/LiveActivity/VoiceLiveActivityClient.swift` | 329 | `VoiceLiveActivity`, `VoiceLiveActivityClient`, `LiveVoiceActivity`. |
| `OpenCodeWidgets/VoiceCallLiveActivity.swift` | 281 | Lock Screen + Dynamic Island. Mute and End are `LiveActivityIntent`s. |
| `OpenCodeWidgets/OpenCodeWidgetsBundle.swift` | 9 | Only widget is `VoiceCallLiveActivity`. |

No push token (`pushType: nil` at `VoiceLiveActivityClient.swift:309`). Updates are local.

### Diagnostics

| File | Lines | Types |
|---|---:|---|
| `OpenCode/Diagnostics.swift` | 500 | `DiagnosticsErrorCategory`, `DiagnosticsEvent`, `DiagnosticsRecorder` |
| `OpenCode/DiagnosticsJournal.swift` | 256 | `InternalBuild`, `DiagnosticsJournal` |
| `OpenCode/DiagnosticsClient.swift` | 49 | TCA dependency over the recorder |
| `OpenCode/DiagnosticsView.swift` | 201 | Settings list + `DiagnosticsExport` (ShareLink text/JSON) |
| `OpenCode/LabMarks.swift` | 86 | `LabMarks` → OSLog `opencode.perf` + recorder + optional DEBUG POST |

`perfLog` is declared in `OpenCode/HomeFeature.swift:5`: `Logger(subsystem: "opencode.perf", category: "session")`.

### Problem reports

| File | Lines | Types |
|---|---:|---|
| `OpenCode/ProblemReports/ProblemReport.swift` | 228 | `ProblemReportTrigger`, `ProblemReport`, `ProblemReportBuilder` |
| `OpenCode/ProblemReports/ProblemReportStore.swift` | 106 | On-disk queue |
| `OpenCode/ProblemReports/ProblemReportsFeature.swift` | 411 | TCA reducer |
| `OpenCode/ProblemReports/ProblemReportViews.swift` | 573 | Shake, screenshot, sheet, offer pill, project picker |
| `OpenCode/ProblemReports/PhoneReportsDelivery.swift` | 147 | Delivery as a queued OpenCode prompt |
| `OpenCode/ProblemReports/HomeFeature+ProblemReports.swift` | 75 | Context + call-dropped / stream-stalled triggers |

Gated by `InternalBuild.isEnabled`: DEBUG, or a TestFlight `sandboxReceipt` (`DiagnosticsJournal.swift:6-22`). App Store builds do not offer reports. Home tests force the dependency off.

### Secrets, haptics, lab marks

| File | Lines | Types |
|---|---:|---|
| `OpenCode/Keychain.swift` | 146 | `KeychainKey` (`SharedKey`), `KeychainStorage` |
| `OpenCode/SharedKeys.swift` | 389 | Which values are keychain vs file vs memory. Relevant keys listed under Keychain flow. |
| `OpenCode/HapticsClient.swift` | 101 | `HapticsClient`, `LiveHaptics` |
| `OpenCode/LabMarks.swift` | 86 | Timing marks |

## Flow

### 1. Attachments

**What the user sees and does.** Composer `+` (`AttachmentPickerButton`, 44×44, SF Symbol `plus`, or a spinner while an import is in flight). Tap opens a glass menu that grows out of the button (`AttachmentMenuPhase`: `closed` → `menu` → `photos`). Rows, bottom-aligned over the composer (`AttachmentMenu.swift:122-133`, `171-218`):

- Camera, only if `UIImagePickerController.isSourceTypeAvailable(.camera)` (`SessionView.swift:1917`).
- Photos, always.
- Files, only if `store.supportsFileUploads` (server has not 404'd the write route this launch).
- Paste, only if `UIPasteboard.general.hasImages`.

Photos morphs the same panel into a card under the composer (card height ~50% of screen, min 340, corner radius 44). The composer lifts by `pickerReservation`. Inline `PhotosPicker` (`.inline`, `.continuousAndOrdered`, max 4, `.shared()` library). Filter menu: All Photos / Screenshots / Videos. Videos hidden unless uploads work (`InlinePhotoFilter`, `AttachmentMenu.swift:29-64`, `407-419`). Scrim tap or downward drag dismisses. Reduce Motion cross-fades instead of morphing.

Camera is a full-screen cover (`SessionView.swift:1536-1546`), dark. Preview fills the screen (`AVCaptureVideoPreviewLayer`, `.resizeAspectFill`). Controls (`AttachmentCameraControls`, `AttachmentCameraView.swift:95-194`): flash (back camera only; off → auto → on), yellow "AE/AF Lock" badge, lens capsules (0.5 / 1 / 2 / tele, live factor on the active lens), close, white shutter, flip. Pinch zoom, tap to focus (yellow square ~1.1s), long-press 0.5s to lock AE/AF. VoiceOver adjustable action steps zoom. Denied camera shows "Open Settings". Capture dismisses the cover and feeds JPEG bytes into the image importer.

Files presents `fileImporter` for `UTType.item`, multiple (`SessionView.swift:1528-1535`). Files over 200 MB (`SessionView.largeUploadBytes`) confirm before upload.

Paste uses `UIPasteControl` (label only) so the system paste alert is skipped (`AttachmentMenu.swift:575-649`). Accepts images only.

Drop onto the composer accepts `UTType.image` and `UTType.fileURL` and routes them through the image importer (`SessionView.swift:1514-1523`).

Chips: images in the composer tray; non-images are `ComposerFileChip` (220×76) with "Uploading N%", a linear progress bar, or a red retry. Sent files render as `TranscriptFileChips` and open in the agent file viewer when they have a server path.

**Data flow.**

1. Images (photos, camera, paste, drop, Files items whose UTType conforms to png/jpeg/gif/webp/heic/heif): load bytes → reject if `> 20_000_000` (`SharedImageEncoder.maxSourceBytes`) → `CGImageSourceCreateThumbnailAtIndex` max 1568 px, orientation applied, JPEG quality 0.8. That drops EXIF/GPS (`ShareHandoff.swift:17-57`). Result is `OpenCodeOutgoingAttachment` (`mime: image/jpeg`). One import at a time; a new import cancels the previous (`ComposerImageImporter`).
2. Photos picker selection is mirrored by `InlinePhotoSelection`: each `PhotosPickerItem` gets a stable UUID that is the chip id. Deselect removes the chip; removing the chip deselects. Videos are `ComposerFileSource.photosPickerItem` and stage via `PhotosPickerItem.loadTransferable(ComposerMovieFile)`.
3. Non-images: `ComposerFileStaging.stage` copies into `tmp/ComposerFiles/<uuid>/<sanitized name>` (security-scoped access started/stopped). MIME from UTType plus a 4 KB NUL/control sniff (`AttachmentFileType.mime`). Text-like types become `text/plain`. Chip starts `.uploading(sent: 0)`.
4. Send: images travel inline as `data:<mime>;base64,…` in the prompt body's `files` (`OpenCodeClient.swift:1119-1121`, `2071-2084`). Path files are folded into model text as `Attached file: \`<path>\`` and also stored in `metadata.attachments` plus `metadata.displayText` (`ComposerFileAttachment.swift:12-48`, `OpenCodeClient.swift:2064-2084`). Prompt route: `POST /api/session/<id>/prompt`.
5. Upload (`OpenCodeClient.uploadFile`, `2104-2183`): `GET /api/info` for `paths.tmp`. 404 or missing `paths.tmp` → `uploadsUnsupported` (remembered in memory for this launch, `SharedKeys.uploadUnsupportedServerIDs`). Then `POST /api/experimental/fs/write?location[directory]=<session dir>&path=<tmp>/uploads/<uuid>/<name>`, `Content-Type: application/octet-stream`, Basic auth, 120s idle timeout, `URLSession.upload(fromFile:delegate:)`. Response `{ data: { path } }`. Progress via `URLSessionTaskDelegate`. Local staged copy discarded on success. Desktop path shape is `${tmp}/uploads/${uuid}/${name}` (`OpenCodeClient.swift:2097-2101`).

**OS APIs.** PhotosUI `PhotosPicker` / `PHPickerFilter` / `PhotosPickerItem`. AVFoundation capture session, photo output, device zoom ramp, focus/exposure point of interest, `RotationCoordinator`. UniformTypeIdentifiers. `UIPasteControl` + `UIPasteConfiguration`. SwiftUI `fileImporter` (document picker). ImageIO thumbnail. `FileManager` security-scoped copy. No `PHPhotoLibrary` authorization request for the inline picker (system picker). Camera uses `AVCaptureDevice.requestAccess(for: .video)`.

**Server routes.** `GET /api/info`. `POST /api/experimental/fs/write`. `POST /api/session/<id>/prompt` (images inline, files as metadata). No separate attachment route.

**Android.** Photo Picker (`ActivityResultContracts.PickMultipleVisualMedia`, max 4) for the system picker. It is not an embeddable card: the iOS inline grid that morphs out of the Photos row has no 1:1 Android API. Closest visual match is a custom MediaStore grid inside the same bottom card, with the system picker as fallback. CameraX `Preview` + `ImageCapture` for stills (this camera does not record video). `CameraSelector` + `ZoomState` for lenses; map `CameraZoomModel` 1:1 (display zoom is relative to the wide lens, cap 10× back / 2× front). SAF `OpenMultipleDocuments` or `ACTION_OPEN_DOCUMENT` for Files. Stage copies in `cacheDir/ComposerFiles`. Image pipeline: `BitmapFactory` with `inSampleSize` to 1568, `Bitmap.compress` JPEG 0.8, which also drops EXIF if you do not copy it. Clipboard: `ClipboardManager` — Android 12+ shows a toast on read; there is no silent `UIPasteControl` equivalent. Uploads: OkHttp/Ktor streaming PUT/POST of the staged file with progress, same two routes.

**Blockers.** Inline Photos card is a UX gap, not a protocol gap. `photosPickerMetadataOptions(.removeLocation)` is iOS 27-only (`AttachmentPicker.swift:64-71`); re-encode is the real privacy control and is portable. HEIC must be decoded before JPEG encode (Android `BitmapFactory` does this if the decoder is present). Very large uploads are held in server memory (comment at `SessionView.swift:1886`); the 200 MB confirm is a product rule, keep it.

### 2. Agent files

**What the user sees.** A sheet titled with the file name and a location subtitle (`opencode-ios/OpenCode`, `~/Downloads`, or the parent path). Phases: "Opening…", "Downloading 1.2 MB of 3.1 MB", a confirm for files over 100 MB, failure (`ContentUnavailableView`), then one of:

- Video/audio: `AVPlayerViewController`. Video may PiP. Audio shows a waveform glyph + name over black and starts playing. Streaming vs downloaded is an accessibility id only.
- Markdown (no `:line`): rendered transcript blocks, toolbar toggles Show Source. Relative links push another page.
- Other text under 2 MB and without a NUL in the first 8 KB: code view with line numbers, optional wrap, yellow highlight on `:42`, horizontal scroll of all lines together.
- Images, PDF, Office, zip, oversized/binary text: Quick Look.

Share toolbar item appears only after a local copy exists (not while streaming). Done dismisses.

Cards appear under agent replies (`AgentFileCards`) and on sent attachments (`TranscriptFileChips` → `AgentFileReference(path:)`). Markdown links and strict inline-code paths become tappable `opencode-file://open?path=&line=` links (`AgentFileReference.swift:201-220`). That scheme is not registered with the OS.

**Data flow.** `AgentFileReference.target(in:)` resolves `~/` from `/Users/<name>` or `/home/<name>` in the session directory, relative paths against the session directory, and collapses `.` / `..` without touching the phone filesystem (`AgentFileReference.swift:158-198`). A file outside the session uses the file's parent as `location[directory]` — that boots a server location, so it only happens on an explicit tap.

`GET /api/fs/read/<relative>?location[directory]=<location>` with Basic auth (`OpenCodeClient.swift:138-146`). Probe sends `Range: bytes=0-1` and cancels the body (`AgentFileLoader.swift:96-113`). 206 means ranges work. Video/audio with ranges stream: `AVURLAsset` URL scheme rewritten to `opencode-stream`, `AVAssetResourceLoaderDelegate` reissues each range with the same auth (`StreamingFileLoader`, `AgentFileLoader.swift:262-384`). Otherwise download to `Caches/AgentFiles/<12-hex of SHA256(serverID|absolutePath)>/<sanitized name>`, conditional on ETag / Last-Modified, progress throttled to ~200 steps. 304 reuses the cache.

**OS APIs.** `URLSession` bytes + download task. `CryptoKit.SHA256`. `AVFoundation` player + resource loader. `AVAudioSession` category `.playback` / `.moviePlayback` while playing. `QuickLook` `QLPreviewController`. `ShareLink`. No server-side transcode.

**Server routes.** Only `GET /api/fs/read/…?location[directory]=`. V2 ignores a bare `directory` query (`OpenCodeClient.swift:138-139`).

**Android.** OkHttp with `Range` for the probe and for streaming. ExoPlayer `HttpDataSource` (or a custom one) that sets `Authorization` and `Range` — do not use a private URL scheme; ExoPlayer accepts headers directly, which is simpler than `StreamingFileLoader`. PdfRenderer or an external viewer instead of Quick Look (no system equivalent for docx/pages/keynote; `ACTION_VIEW` is the fallback, matching Quick Look's "hand to the system" behavior). Text/markdown viewer can be a near 1:1 of `AgentCodeView` / markdown blocks. Cache key algorithm should stay `SHA256(persistenceID + "|" + absolutePath)` prefix 12 hex so a later shared cache format matches.

**Blockers.** Quick Look fidelity for Office files will not match. Range streaming requires the OpenCode server to answer 206; old servers return 200 and the phone downloads instead (`AgentFileProbe` comment, `AgentFileLoader.swift:54-57`). Paths are server paths, not Android paths.

### 3. Share extension

**What the user sees.** From any app's share sheet, "OpenCode" / "Add to OpenCode". If the app has never published targets: "Connect to a Server First". Otherwise a list: image strip (72×72), file rows (name + type · size), shared text (3 lines), "Add a message" field, then destinations under the server host: New Session (project name as detail), Pinned, Recents (max 20). Checkmark on the selection. Default destination is the last-viewed session if it is in pinned+recents, else New Session (`ShareModel.swift:35-38`). Add is disabled until loading finishes and there is content. Footer: "Shared items wait in the session's composer. Open OpenCode to send them." Skipped-image / skipped-file counts. Save failure: "Try again. If it keeps happening, free up some storage on this iPhone."

**Data flow.** Extension loads `NSItemProvider`s (`ShareModel.load`, `ShareModel.swift:54-80`): images re-encoded in the provider callback (memory limit) via `SharedImageEncoder`; other data/movies/PDFs/named text files copied with `ComposerFileStaging.stage`; `http(s)` URLs become text; `file://` URLs stage as files; plain text appended, de-duped (Safari shares link + same text). Caps: 10 images, 10 files (`ShareHandoff.maximumImages/Files`). Save writes an atomic directory in the app group: stage as `.staging-<uuid>`, write JPEGs + file copies + `share.json`, rename to `<uuid>` (`ShareHandoffStore.save`, `ShareHandoff.swift:229-251`). No credentials in the payload. `ShareTargets` (`share-targets.json`) is written by the app when it leaves the foreground (`HomeFeature.swift:645-648`), from pinned keys + 20 recents + last viewed + most recent project. Removing a server deletes targets if they match (`AppFeature.swift:366-368`).

App consumes on launch and every return to `.active` (`AppView.swift:29-34` → `AppFeature.pendingSharesRequested`). `consumeAll` renames the directory to `.claimed-<uuid>` first so two activations cannot double-deliver (`ShareHandoff.swift:256-281`). Max age 24h. Abandoned staging/claimed dirs older than 1h are deleted. Images become `OpenCodeOutgoingAttachment`; files move into `tmp/ComposerFiles`. Unknown server → alert and discard. Known server: if not the connected one, switch and connect, then `HomeFeature.sharesReceived` (`HomeFeature.swift:650-666`). Deleted session falls back to a new-session draft in the same project (`ShareInbox.swift:97-104`). Draft text is appended after whatever is already typed, separated by `\n\n`. Haptic success. Nothing is sent until the user hits Send.

**OS APIs.** `NSExtension` share-services. `NSItemProvider` loadFileRepresentation / loadItem. App group container `FileManager.containerURL(forSecurityApplicationGroupIdentifier:)`. `completeFileProtection` on writes. No network in the extension.

**Server routes.** None from the extension. After the user sends, same prompt/upload routes as attachments.

**Android.** `intent-filter` `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `image/*`, `video/*`, `*/*`, `text/plain`. A share `Activity` (or `Activity` alias) is the sheet UI; it does not need a separate process, but if it is a separate process it cannot see the app's DataStore unless you use a shared user-id (not recommended) or a file in a `FileProvider`. Practical port: one app, share Activity writes the same JSON+files queue under `filesDir/Shares`, main Activity consumes it in `onResume`. There is no app-group equivalent and none is required if both sides are the same app. Direct Share / `ChooserTarget` can stand in for pinned/recents. Do not put the server password in the share payload (iOS does not).

**Blockers.** None on protocol. Android share targets cannot show a live session list unless the app has written `share-targets.json` (or Room) before the share Activity starts — same constraint as iOS, which only publishes on background. Image re-encode must happen before the provider's temp file is deleted (iOS does it inside `loadFileRepresentation`'s callback, `ShareModel.swift:139-147`).

### 4. Dictation

**What the user sees.** Mic is available only on iOS 26 (`SessionView.swift:1365-1366`). Collapsed composer: mic can sit in the trailing slot or as a secondary button. Hold starts dictation; drag locks it (`dictationHold`); tap send while recording finishes and sends (`dictationSendTapped`). "Preparing dictation…" while the on-device model installs or the engine starts. Recording button has a halo driven by `DictationLevels` (RMS, −48…−12 dB mapped to 0…1), read on a timer, not via TCA actions. Errors: mic denied, locale unsupported, audio format, or "Dictation requires iOS 26." Transcript replaces the draft (or is appended to the text that was there at start — `SessionFeature` concatenates `base + transcript`). A keyboard edit mid-dictation stops dictation rather than fighting the user. Starting a voice call stops dictation first (`SessionFeature.swift:849-860`).

**Data flow.** `DictationClient.prepare` on session appear (`SessionFeature` task). `start` returns `AsyncThrowingStream<String, Error>` where each element is the full transcript so far (finalized prefix + volatile tail). `stop` finalizes and finishes the stream. `LiveDictation` (`DictationClient.swift:128-305`): `AVAudioApplication.requestRecordPermission`, `SpeechTranscriber` for `Locale.current` or same language or `en-US`, `AssetInventory.assetInstallationRequest` download (shared task, not cancelled if the screen dismisses), `SpeechAnalyzer.bestAvailableAudioFormat`, `AVAudioEngine` tap 4096 frames, `BufferConverter` to the analyzer format, `reportingOptions: [.volatileResults, .fastResults]`. Interruptions and engine configuration changes finish the stream so the reducer can tear down.

**OS APIs.** iOS 26 `Speech` framework: `SpeechAnalyzer`, `SpeechTranscriber`, `AssetInventory`, `AnalyzerInput`. `AVAudioEngine` input tap. `AVAudioSession` category `.playAndRecord`, mode `.default`, options `[.mixWithOthers, .allowBluetoothA2DP]`, preferred input built-in mic, `setAllowHapticsAndSystemSoundsDuringRecording(false)` (`DictationClient.swift:181-192`). Comment: `.record` would silence music; HFP would turn a car into a phone call. Audio never leaves the phone (usage string says so).

**Server routes.** None. Dictation only fills the composer. Send is the normal prompt.

**Android.** `SpeechRecognizer` with `RecognizerIntent.EXTRA_PREFER_OFFLINE` and `EXTRA_PARTIAL_RESULTS`, or the newer `android.speech.SpeechRecognizer` on-device models where the OEM ships them. There is no Android API matching iOS 26 `SpeechAnalyzer` / `AssetInventory`. Audio capture if you roll your own: `AudioRecord` at the recognizer's expected rate. Do not use a cloud recognizer if the product promise is "audio never leaves the phone."

**Blockers.** This is the sharpest platform gap. iOS deployment target is 18, but the live client throws `DictationError.unsupported` below iOS 26 (`DictationClient.swift:58-70`). Quality, partials, and the silent model download will not match unless you ship your own on-device model (licensing: Apple's model cannot be ported). Android 12+ mic foreground-service rules apply if recognition must continue in the background; iOS dictation does not set a background mode of its own (the app's `audio` background mode is for voice calls).

### 5. Voice calls

**What the user sees.** Opt-in: Settings → Advanced → "Enable Voice Mode" (`voice-mode-enabled`, default false) plus a non-empty OpenAI key (`SessionFeature.State.isVoiceAvailable`, `SessionFeature.swift:202-209`). Picker "Voice Model": GPT-Live (default, including upgrades) or Realtime (`SharedKeys.swift:302-314`). Key field is secure, eye toggle, Verify button. Footer: speech streams to OpenAI with your own project key; the key stays on the phone and is never sent to the OpenCode server (`SettingsView.swift:152-154`).

Call button replaces Send when the composer is empty and voice is available (`SessionView.swift:1374`). Tap replaces the composer with a 56 pt bar (`VoiceCallComposer`, `VoiceView.swift:10-86`): mute (mic / mic.slash on a tertiary disc), a clear middle that shows a raised hand if the session needs input or a pause if audio was interrupted, red phone-down End. Behind the buttons, the dot scope. Tap the middle to grow the capsule's glass upward into a transcript (captions); the bar does not move. Status text: Connecting…, Reconnecting…, Paused, Needs your input, Working, Speaking, Muted, Listening, Ending…, Ended, Call ended, or the error string (`VoiceFeature.swift:235-249`). Failure/dropped shows a card with Try Again / Check API Key / Close (`VoiceView.swift` around 623-681). Hang-up sweeps the scope out, then the composer returns after `DotScopeSimulation.exitDuration` (`SessionFeature.swift:868-878`).

Connecting plays synthesized sweep tones and haptics; connect plays a chord + success haptic; end plays a glide. Cues are PCM synthesized in-process (`VoiceCallSynthesizer`), not bundled audio files. Mid-call reconnect is silent (`DotScopeSimulation.SoundScheduler` comment, `DotScopeSimulation.swift:93-98`).

**External services and protocols.** User's OpenAI key, Bearer, never sent to OpenCode.

Preflight before connect: `GET https://api.openai.com/v1/models/<model>` (`RealtimeClient.swift:48-68`). 401 → "OpenAI rejected the API key." Transport errors → "No connection." A SHA-256 fingerprint of the last accepted key is stored in caches (`verified-openai-key.json`), not the key (`SharedKeys.swift:338-343`).

Two engines (`VoiceEngine`, `LiveProtocol.swift:4-22`), chosen at call start and locked in `VoiceFeature.State.activeEngine`:

- **GPT-Live (default).** `wss://api.openai.com/v1/live/sessions`. Model `gpt-live-1`. First message is `session.start` with `delegation.type = client`, PCM 24 kHz, voice `marin`, instructions, optional `session.input` history (max 128 messages). Audio append: `session.input_audio.append` (base64 PCM16, even byte count). Mute/unmute: `session.input_audio.mute|unmute`. Context: `session.instructions.append`, `session.thinking.append`, `session.commentary.append` (chunked at 1400 chars, commentary max 2 chunks, `LiveContentChunker`). Close: `session.close`. Server events decoded in `LiveServerEvent.decode` (`LiveProtocol.swift:336-413`): `session.started`, input/output transcript deltas, `session.output_audio.delta`, `session.delegation.created` (target `client`), mute acks, usage, `session.closed` (reasons: close_requested, expired, content, remote_hangup, connection_lost). Docs cited in comments (`live-conversations.md`, `live-delegation.md`) are not in this repo.
- **Realtime.** `wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1`. First message `session.update` (`RealtimeProtocol.swift:81-111`): `session.type = realtime`, output modality audio, tools, input/output `audio/pcm` 24 kHz, transcription model `gpt-live-transcribe` languages `["en"]`, turn detection `semantic_vad` eagerness `low`, `create_response` and `interrupt_response` true. No server noise reduction (device voice processing already runs; stacking clipped soft syllables, comment at `RealtimeProtocol.swift:77-80`). Commands: `input_audio_buffer.append|commit`, `conversation.item.create` (function output or system message), `conversation.item.truncate`, `response.create|cancel`. Events: `session.created` (expires_at), speech started/stopped, input transcript delta/completed, `response.output_audio.delta`, transcript delta/done, `response.function_call_arguments.done`, `response.created|done`, `error`.

Both sockets: `URLSessionWebSocketTask`, `maximumMessageSize` 8 MB, `waitsForConnectivity = false`, messages as UTF-8 JSON strings. DEBUG can redirect either URL via `VoiceLabDemo`.

Sessions last ~60 minutes. A replacement is opened at 55 min (seeded with prior turns) and swapped at a quiet moment; forced at 58 min (`VoiceCallTiming`, `VoiceCallClient.swift:172-184`). Dropped socket retries after 0.5s, 2.5s, 6s, then `RealtimeClientError.dropped` → "Call ended" card and a problem-report offer. Reconnect shorter than 1s does not show "Reconnecting…" (`VoiceFeature.swift:432-441`).

**Audio pipeline.** `VoiceAudioEngine.start` (`VoiceAudioClient.swift:691-764`):

1. `AVAudioApplication.requestRecordPermission`.
2. `AVAudioSession` category `.playAndRecord`, mode `.voiceChat`, options `[.defaultToSpeaker, .allowBluetoothHFP]`, `setPrefersNoInterruptionsFromSystemAlerts(true)`, haptics allowed during recording, `setActive(true)`.
3. `AVAudioEngine`: player node and cue player → main mixer. Input tap at hardware format, buffer ~50 ms (`sampleRate/20`). `inputNode.setVoiceProcessingEnabled(true)` after the playback graph is attached (required for echo cancellation; chat mode alone does not enable it, comment `VoiceAudioClient.swift:637-639`).
4. Capture converted to PCM16 mono 24 kHz, 100 ms chunks (2400 frames, `PCM.chunkFrames`). Levels from RMS.
5. Playback: Int16 → Float32 at 24 kHz, mixer resamples to hardware. Player tap (~43 ms) drives the assistant meter from what is audible, not from network arrival.
6. Interruptions, route changes, media-services reset are `VoiceAudioEvent`s. Flush returns milliseconds heard for Realtime `conversation.item.truncate`.
7. `UIBackgroundModes: audio` keeps the session alive in the background. The dot scope stops drawing when `scenePhase != .active`; audio does not (`DotScopeView.swift:47-48`).

GPT-Live while muted still sends 100 ms zero chunks so the server timeline keeps advancing and delegated replies can be spoken (`VoiceCallClient.swift:1448-1461`).

**Turn taking.**

- Realtime: `VoiceTurnTaking` (`VoiceTurnTaking.swift`). User always preempts. Server VAD interrupts only a response that already exists, so a reply created after the user started talking is cancelled locally. Tool results wait for a free floor (not user speaking, not generating, not playing, not awaiting server reply, not audio-interrupted) and collapse into one `response.create`. After the user stops, wait 1500 ms for the server's own reply before asking. Barge-in truncates the playing item to heard milliseconds and cancels an in-flight response.
- GPT-Live: full duplex. User transcript deltas do not flush assistant audio (`VoiceCallClient.swift:1537-1540`). Playback stops when the server stops sending speech-energy frames (3 buffers of hangover) or a moderation error cuts output. Delegation waits 180 ms for trailing transcript fragments (`liveDelegationSettle`). Assistant captions settle 450 ms after the last transcript delta if no audio remains.

**How the call drives the OpenCode session.** The voice model does not edit files. It delegates.

- Realtime tools (`VoiceTranscript.swift:244-268`): `delegate(text)` and `end_voice_session`. Unknown tool returns a failure function output.
- GPT-Live: `session.delegation.created` with target `client` is surfaced as the same `functionCall(name: "delegate", arguments: {})`. The spoken request is the transcript tail, not a tool argument (`VoiceFeature.delegateWork`, `VoiceFeature.swift:611-676`). Completion is `session.commentary.append` of `[COMPLETE] …` (max 1600 chars), not a function output. Progress is `session.thinking.append`.

`VoiceWorkClient.submit` (`VoiceWorkClient.swift:36-51`): if the call is on a draft, `createSession(directory)` then optional `switchModel` to the draft's model, then `sendDeliveredPrompt` → `POST /api/session/<id>/prompt` with delivery `steer` (follow-ups reach a running session at the next step; an idle session starts at once). Prompt body is `VoiceHandoff.format`: a fixed header plus JSON `{ context?, instruction, transcript_delta }` (`VoiceTranscript.swift:151-171`). Then `POST /api/experimental/session/<id>/wait` (timeout 600s) and `GET /api/session/<id>/message?order=desc&limit=40` for the assistant text after that prompt id. Wait retries up to 4 times on transient errors (`VoiceFeature.swift:678-699`). Recent context for instructions: `transcript` limit 12, formatted `user:` / `assistant:` excerpts of 400 chars.

Moving the call (drawer, New Session, draft project change) updates instructions (`session.update` or, on GPT-Live, full instructions plus a short `session.instructions.append` retarget). Admitted work keeps running in the old session; the model is told so (`VoiceFeature.swift:482-519`). A second delegate while a draft session is still being created is rejected. `end_voice_session` waits for the goodbye to drain or 6 seconds, then hangs up.

One call process-wide (`VoiceCallHost` ends the previous on start). Dictation is stopped before start.

**Dot scope.** `DotScopeSimulation` (691 lines) is the picture. Fixed 240 Hz step so the same inputs always match. Two traces (you = left/warm pearl, agent = right/cool pearl), each a sum of four travelling sines whose amplitudes follow loudness through a spring, delayed by distance from that speaker's end so syllables roll outward. Quiet: two near-flat dim lines and a slow breath. Phosphor: beam sampled at 120 Hz, lit dots fade over a few hundred ms. Glow lobes sit behind the mute and end buttons and swell with level. Scanner: while connecting, one beam sweeps back and forth; on connect, one last sweep draws the braid; on end, the beam sweeps toward End and erases. Reduce Motion draws the still frame only. Grid is pixel-exact, odd row count so a flat trace is one dot (`DotScopeSimulation.swift:3-77`, `DotScopeView.swift:4-8`).

GPU side (`DotScopeView` + `DotScopeShaders.metal`): `MTKView` 60 fps, clear, not opaque. CPU writes a `float4` field per cell into a triple-buffered `MTLBuffer`. Fragment shader draws a full-screen triangle, round dots with bloom, faint unlit dots fading at top/bottom, and the two glow ellipses. Palette is semantic (warm vs cool), not raw hues. Widget cannot run this animation; it uses static 5×5 `DotMatrixGlyph` patterns (`VoiceCallLiveActivity.swift:219-280`).

**Live Activity.** `HomeFeature` maps `VoiceFeature.State` → `VoiceLiveActivity.Call` and pushes only when that projection changes (`HomeFeature.swift:1122-1131`). Attributes: `serverID`, `sessionID` (empty on a draft). Content: title, phase (connecting/live/reconnecting/ended), activity (listening/speaking/working/needsInput), muted, paused, `startedAt`. A user hang-up (`phase == .ended`) dismisses immediately. Failed/dropped shows "Call ended" for 3 seconds (`endedLinger`) then ends; a `UIBackgroundTask` named "Voice call activity" covers that linger (`VoiceLiveActivityClient.swift:229-253`). Stale date is now+15 min; a heartbeat every 5 min refreshes it so a live call never looks abandoned (`staleInterval` / `heartbeatInterval`, `VoiceLiveActivityClient.swift:143-144`). If the user swipes the activity away, it is not recreated for that call. Button taps (`EndVoiceCallIntent`, `ToggleVoiceMuteIntent`) yield `VoiceCallCommand` into `VoiceCallCommands.shared`. Home's task listens and forwards `.end` / `.toggleMute` into the voice reducer (`HomeFeature.swift:623-643`). If nothing is listening (app relaunched), the intent ends the activity. `OpenCodeApp.init` ends all activities.

Lock Screen row: state glyph, title, "Listening · 0:12" timer, mute + end. Dynamic Island: compact leading glyph / trailing timer, expanded title + status + controls, minimal glyph. Colors are hardcoded dark (`VoiceCallLiveActivity.swift:10`, `82-84`), not theme tokens.

**Server routes (OpenCode, not OpenAI).** `POST /api/session` (create, draft only). `POST` model switch (existing client). `POST /api/session/<id>/prompt` delivery `steer`. `POST /api/experimental/session/<id>/wait`. `GET /api/session/<id>/message?order=desc&limit=40` and limit 12 for context. No voice route on the OpenCode server.

**Android.**

- Sockets: OkHttp `WebSocket` to the same two URLs. Protocol types in `RealtimeProtocol.swift` / `LiveProtocol.swift` port 1:1 (JSON, base64 PCM16 LE 24 kHz mono).
- Audio: `AudioRecord` + `AudioTrack`, or Oboe/`AAudio` if you need the voice-processing latency. Enable `AcousticEchoCanceler` and `NoiseSuppressor` on the record session; they are the stand-in for `setVoiceProcessingEnabled(true)` and are weaker. WebRTC AEC is the closer match if calls echo. Mode analogue of `.voiceChat` + `.defaultToSpeaker` + Bluetooth SCO/HFP: `AudioManager.MODE_IN_COMMUNICATION`, speakerphone on, `setCommunicationDevice` for BT.
- Background: a foreground service with types `microphone` and `mediaPlayback` (Android 14+), ongoing notification. That notification is the Live Activity: title, status, timer, mute, end. There is no Dynamic Island. Lock-screen ongoing notification is the lock-screen view. `connectedDevice` / `PARTIAL_WAKE_LOCK` is unnecessary if the service holds the mic. Do not use a full-screen incoming-call UI; this is not CallKit and not a PSTN call.
- Dot scope: port `DotScopeSimulation` as Kotlin (it is pure and clock-driven). Draw the field with Compose `Canvas` or an AGSL `RuntimeShader`. The Metal shader is presentation only; matching the simulation matters more than matching Metal. Widget glyphs are a 5×5 boolean grid — trivial in Compose.
- Haptics: `VibrationEffect` compositions for the six `VoiceCallHaptic` cases. Skip when not resumed, matching `UIApplication.applicationState == .active`.

**Blockers.**

- OpenAI key is user-supplied. No key is shipped. Preflight and both sockets fail closed without it.
- GPT-Live (`gpt-live-1`, `/v1/live/sessions`, client delegation) is not the public Realtime API. Access, pricing, and whether `gpt-live-1` / voice `marin` / `gpt-live-transcribe` are available to a given key are external. The cited markdown specs are not in this checkout. Realtime (`gpt-realtime-2.1`) is the public fallback and is selected in Settings.
- Echo cancellation quality will not match Apple's voice-processing I/O without a real AEC (WebRTC or Oboe + AEC). The Realtime session deliberately does not ask OpenAI for noise reduction because the device already did it (`RealtimeProtocol.swift:77-80`).
- Android 14 foreground-service microphone permission and the Play policy for mic-in-background. iOS only declares `audio` background mode.
- 60-minute session rotation and the 8 MB websocket cap are protocol constraints, not OS ones. Keep them.
- Licensing: OpenAI ToS for streaming user speech to their API. The app already discloses this in the mic usage string and Settings footer. No other third-party voice SDK.

### 6. Diagnostics and problem reports

**What the user sees.** Settings → Diagnostics (`DiagnosticsView`). Toggle "Record diagnostics". On TestFlight/debug, a second toggle "Include Transcripts" (120-char voice excerpts). Summary: last session open, tap → first frame, tap → first fresh content, SSE name, last-60s request count and wire→decoded bytes. Sections per navigation epoch. Share Text or JSON, or Clear. Footer states what is never stored: credentials, headers, prompts, tool input, response bodies.

Problem reports (debug + TestFlight only):

- Shake the phone (`UIWindow.motionEnded`, `ProblemReportViews.swift:55-61`). Ignored if the first responder has undo/redo, or the report sheet is already up.
- Settings → Report Problem (same sheet, screenshot delayed 650 ms so Settings has closed).
- Automatic offers: call dropped (non-terminal), SSE timeout after the stream had connected and the app has been foregrounded ≥120s, previous run ended in the foreground without backgrounding (crash). At most one automatic offer per 10 minutes, except crash. Debug builds do not offer crash reports unless launched with `-lab-crash-report` (a debugger stop looks like a crash).
- Offer pill: "Send Report?". Opens the sheet. Dismiss deletes the draft.
- Sheet: note, screenshot thumbnail (toggle), Send. First send on a server asks which project receives "Phone Reports". Then "Sending…", "Sent" (~1s) or "Saved — will send when you're back online" (~1.6s). Share Report exports the files if delivery is stuck (`ProblemReportViews.swift:391`).

**Data flow.** `DiagnosticsRecorder` (opt-in on App Store, on by default in internal builds): max 5000 events / 15 min retention in shipping-internal, 2000 / 10 min in the default constructor. Kinds: lifecycle, navigation, mark, requestStart/End, networkMetrics, stream, voice. Paths redacted for `/auth/…` and `/api/fs/read/…` (`Diagnostics.swift:417-436`). Query values replaced with `<redacted>`. Journal: `Application Support/OpenCode/Diagnostics/current.jsonl` plus one rotated segment (768 KB), previous run set aside at launch. Foreground marker file cleared on background/terminate; if it matches this build and events exist, the previous run `endedUnexpectedly`. File protection `completeUntilFirstUserAuthentication` so a background voice call can keep appending.

Problem-report bundle (`Application Support/OpenCode/ProblemReports/<uuid>/`): `report.json`, `summary.md`, `timeline.txt` (≤300 KB tail), `timeline.json` (≤700 KB, newest events), `previous-run.txt` (≤150 KB, if crash or ended within 15 min), `screenshot.jpg` (≤600 KB). Whole bundle aimed under 2 MB. Max 12 reports. Screenshot: key window `drawHierarchy`, scale min(2, displayScale), JPEG quality 0.7/0.5/0.3. Skipped in background, while Settings (credential surface) is visible, or while a secure field is first responder (`WindowSnapshot.isAllowed`, `ProblemReportViews.swift:100-136`). Crash reports have no screenshot.

Delivery (`PhoneReportsDelivery`): remembered destination per server in `Documents/phone-reports.json` (project id, directory, session id). Creates a session titled "Phone Reports" if missing or 404. Uploads timeline txt/json and previous-run via the same `uploadFile` route, named `phone-report-<stamp>-<file>`. Screenshot is an inline JPEG attachment, not an upload. Prompt text is the markdown summary. Delivery is `queue`, not `steer`, so a report never interrupts a running agent (`PhoneReportsDelivery.swift:90-91`). One delivery at a time, oldest pending first, 5 automatic attempts per launch, then wait for Send or next launch. Server-reachable clears the defer set. If uploads 404, the timeline is inlined (24 KB tail) and that server is marked upload-unsupported for the launch.

**OS APIs.** `OSLog` / `Logger`. `URLSessionTaskMetrics` (DNS, connect, TLS, TTFB, download, reused connection, protocol). `UserDefaults` keys `diagnostics.enabled`, `diagnostics.includeTranscripts`. `UIApplication` notifications for foreground marker (string names, not the UIKit constants, so the journal file does not import UIKit — `DiagnosticsJournal.swift:206-219`). Motion shake. `UIGraphicsImageRenderer`. Darwin notifications only for UI tests (`ai.opencode.lab.shake`). `uname` for the machine identifier. `CFBundle` version. App Store receipt filename to detect TestFlight.

**Server routes.** Same as a normal queued prompt plus uploads: `POST /api/session` (create Phone Reports), rename, `POST /api/experimental/fs/write`, `POST /api/session/<id>/prompt` with `delivery: queue`. No dedicated reports endpoint. The OpenCode agent in that session is the reader.

**Android.** A rolling JSONL journal in `filesDir` is a direct port of `DiagnosticsJournal`. `OkHttp` event listener for the timing fields `URLSessionTaskMetrics` provides. Shake: `SensorManager` accelerometer peak, with the same undo/sheet exclusions. Screenshot: `PixelCopy` of the activity window, same skip rules (not when a password field is focused, not from a service). Delivery can stay "queued prompt in a Phone Reports session" so the server side does not change. Ongoing-notification actions are unrelated.

**Blockers.** TestFlight detection (`sandboxReceipt`) has no meaning on Play; gate reports on a build flavor. Shake-to-report is a debug/internal feature — do not ship it on a Play production track unless product asks. Screenshot of a secure field must stay forbidden. Do not upload the OpenAI key; the iOS report context redacts the server to `scheme://host:port` (`ProblemReportBuilder.redactedServer`).

### 7. Keychain

**What it stores.** Service name `dev.kitlangton.opencode.servers` (`Keychain.swift:137`) — historical, not the bundle id. Generic-password items, account = key, accessible after first unlock (`kSecAttrAccessibleAfterFirstUnlock`). Update in place; add only on `errSecItemNotFound`, so a failed write cannot delete the old secret (`Keychain.swift:100-111`). JSON Codable values. No subscription (nothing else writes these items). Tests use an in-memory mutex dictionary and cannot touch the real keychain.

Keys (`SharedKeys.swift:267-322`):

- `saved-server`: last `OpenCodeServer?` (URL, username, password, alternates).
- `saved-servers`: the list, most recent first.
- `openai-api-key`: `String?`. Empty/nil disables voice.

Not secrets, so file/UserDefaults instead: voice-mode toggle, voice engine, voice transcripts JSON, verified key fingerprint, phone-reports destinations, home snapshot, favorites, pins, read dates. Upload-unsupported set is in-memory only.

**OS APIs.** Security framework `SecItemCopyMatching` / `SecItemUpdate` / `SecItemAdd`. No iCloud keychain, no access group, no biometrics.

**Server routes.** None. The password becomes `Authorization: Basic base64(username:password)` on every OpenCode request (`OpenCodeClient.swift:112-116`). The OpenAI key is `Authorization: Bearer` on OpenAI only.

**Android.** `EncryptedSharedPreferences` or DataStore plus Android Keystore (`MasterKey` / `KeyGenParameterSpec`, AES-GCM). `Keystore` keys that require user unlock after boot match `kSecAttrAccessibleAfterFirstUnlock` better than keys usable before first unlock. Do not put the OpenAI key in a backup (`allowBackup=false` for that file, or exclude from cloud backup). The service string can stay `dev.kitlangton.opencode.servers` only if you ever need a documented alias; a new app has no iOS keychain to migrate, so a new alias is fine. Keep the three logical keys.

**Blockers.** No cross-platform secret migration. Android Auto Backup will leak the key unless excluded. `EncryptedSharedPreferences` is deprecated in favor of DataStore + Tink/Keystore; either is acceptable if the key never hits logs.

### 8. Haptics

`HapticsClient` (`HapticsClient.swift`): `selection`, `impact` (light), `success`, `error`, `voice(VoiceCallHaptic)`. Generators are prepared after each fire. Voice haptics no-op unless `UIApplication.shared.applicationState == .active`, and no-op in lab runs unless `OPENCODE_LAB_VOICE_HAPTICS` is set (`HapticsClient.swift:51-72`). Mapping: callStarted medium 0.75, sweepPass rigid 0.5, connected notification success, muted soft 0.6, unmuted rigid 0.65, ended soft 0.85. `VoiceCallSounds.recordHaptic` runs even when hardware is skipped, for lab telemetry.

**Android.** `Vibrator` / `VibrationEffect.createWaveform` or predefined effects (`EFFECT_CLICK`, `EFFECT_TICK`, `EFFECT_HEAVY_CLICK`). No 1:1 of `UINotificationFeedbackGenerator.success`. Gate on `Lifecycle.State.RESUMED`. No licensing issue.

### 9. Lab marks

`LabMarks.mark` always logs to `opencode.perf` and `DiagnosticsRecorder.recordMark`. If DEBUG and `OPENCODE_LAB_MARKS_URL` is set, it also POSTs JSON `{name, t, sessionID?, …}` with a 2s timeout (`LabMarks.swift:14-77`). `needsNetworkMetrics` is true when that endpoint is set or diagnostics recording is on, which makes `OpenCodeClient` attach a metrics delegate. Not a user feature. Android: keep the mark names if you want the same gauntlet; a debug-only HTTP POST is optional.

### 10. App lifecycle, plist, entitlements, project.yml

Already listed under Components. Behavioral summary:

- Launch builds the TCA store, ends Live Activities, shows `AppView`. If a saved server exists, `AppFeature.task` connects; otherwise the pairing/setup screen waits. Shares are consumed immediately and again on every foreground.
- Background audio is the only background mode. No silent push, no fetch, no processing, no VoIP, no location.
- Live Activities supported, not frequent updates, not push-driven.
- Local HTTP allowed (`NSAllowsLocalNetworking`) so a LAN `opencode` server works. No arbitrary ATS exception.
- App group is only for the share queue and `share-targets.json`. Widgets do not use it; ActivityKit matches the shared `VoiceCallActivityAttributes` type.
- No URL scheme. `opencode-file` and `opencode-stream` are in-process only (`AgentFileReference.linkScheme`, `StreamingFileLoader.scheme`). Do not export them on Android.
- Camera usage string covers both attachments and pairing QR (`PairingScanner`, not in this slice's file list, but the permission is shared).
- Microphone string covers dictation (on device) and voice (streams to OpenAI with the user's key).
- Speech-recognition string: "Transcribe your dictation on device. Dictation audio never leaves your phone."
- Photo library string: recent photos inside the composer. The inline picker is the system picker; the string is still required.
- iPhone only. No iPad idiom.
- Widgets have no entitlements file. Share extension and app share the group and two Swift files.

**Android manifest analogue.**

- `INTERNET`.
- `CAMERA` + camera feature (not required, so devices without a camera still install; hide the row as iOS does).
- `RECORD_AUDIO`.
- `POST_NOTIFICATIONS` (Android 13+) for the call notification.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` only if you build a custom gallery; the system photo picker does not need them.
- Cleartext: a network security config that allows cleartext to private ranges, matching `NSAllowsLocalNetworking`, not global cleartext.
- No intent-filter `VIEW` / custom scheme unless product adds one. Share filters as in section 3.
- `android:allowBackup` false, or backup rules that exclude keystore-wrapped prefs.

## Files Read

- `project.yml`, `project.hotreload.yml`
- `OpenCode/Info.plist`, `OpenCode/OpenCode.entitlements`, `OpenCode/OpenCodeApp.swift`
- `OpenCodeShare/Info.plist`, `OpenCodeShare/OpenCodeShare.entitlements`, `ShareViewController.swift`, `ShareView.swift`, `ShareModel.swift`
- `OpenCodeWidgets/Info.plist`, `OpenCodeWidgetsBundle.swift`, `VoiceCallLiveActivity.swift`
- `OpenCode/AttachmentMenu.swift`, `AttachmentCameraView.swift`, `AttachmentCameraLenses.swift`, `AttachmentPicker.swift`, `AttachmentFileType.swift`, `ComposerFileAttachment.swift`, `ComposerFileImport.swift`, `ComposerImageImport.swift`, `InlinePhotoSelection.swift`, `FileAttachmentViews.swift`
- `OpenCode/ShareHandoff.swift`, `ShareInbox.swift`
- `OpenCode/AgentFiles/AgentFileReference.swift`, `AgentFileLoader.swift`, `AgentFileViewer.swift`
- `OpenCode/DictationClient.swift`
- `OpenCode/Voice/` all Swift listed above, plus `DotScopeShaders.metal` (first 80 lines; shader role confirmed from the header and `DotScopeView`)
- `OpenCode/LiveActivity/VoiceCallActivity.swift`, `VoiceLiveActivityClient.swift`
- `OpenCode/Diagnostics.swift`, `DiagnosticsJournal.swift`, `DiagnosticsClient.swift`, `DiagnosticsView.swift`, `LabMarks.swift`
- `OpenCode/ProblemReports/` all six Swift files
- `OpenCode/Keychain.swift`, `HapticsClient.swift`, `SharedKeys.swift` (250-389)
- Wiring: `AppFeature.swift` (share consume/switch), `AppView.swift` (scene phase), `HomeFeature.swift` (share publish, voice commands, live activity, SSE stall), `HomeView.swift` (scene phase), `SessionFeature.swift` (voice start, dictation, upload), `SessionView.swift` (menu, camera, files, mic, call button), `OpenCodeClient.swift` (upload, file read, prompt, wait), `SettingsView.swift` (voice toggle and key)

Not read end-to-end: `VoiceLabDemo.swift` (DEBUG only; referenced from clients), `VoiceView.swift` past the call bar and the failure-card grep hits, `DotScopeShaders.metal` past line 80, `DotScopeSimulation.swift` past the public model (the 691-line simulation body is described from its header comment, which specifies the algorithm). `ProblemReportViews.swift` past the trigger/screenshot/modifier (sheet layout confirmed via grep and the feature reducer).

## Boundaries

- Share extension and widget are separate targets. Shared source is explicit in `project.yml` (`ShareHandoff.swift`, `AttachmentFileType.swift`, `VoiceCallActivity.swift`). They do not link the app binary.
- Extension never holds credentials and never calls the server. The app group is the only IPC.
- Voice never sends the OpenAI key to OpenCode. `VoiceWorkClient` is the only voice→server seam (`VoiceWorkClient.swift:4-6`).
- `VoiceCallClient` hides JSON. Reducers see `VoiceCallEvent` only.
- Dictation audio stays on device. Voice audio does not.
- Diagnostics must not contain headers, bodies, or credentials. The one exception is a 120-char voice excerpt while Include Transcripts is on, and turning that off rewrites the journal (`Diagnostics.swift:92-97`, `DiagnosticsJournal.removeExcerpts`).
- Problem reports exist only when `InternalBuild.isEnabled`. App Store builds keep the diagnostics toggle off by default and have no shake handler effect (`ProblemReportsFeature.isAvailable` gates `motionEnded`).
- Agent-file reads of paths outside the session directory boot a server location. Image loads in the transcript do not (`OpenCodeClient.imageRequest` returns nil for absolute paths outside the session directory, `OpenCodeClient.swift:121-136`).
- Live Activity button intents run in the app process (`VoiceCallActivity.swift:37-38`). They are not a widget-side network call.
- Keychain service id is not the bundle id. Do not assume `ai.opencode.mobilecode` is the SecItem service.
- `opencode-file` and `opencode-stream` must not become exported Android intent schemes.

## Non-Obvious Things

- Images and files are different pipes. Images are re-encoded JPEG data URLs on the prompt. Everything else is uploaded to `paths.tmp/uploads/<uuid>/<name>` and referenced by path. Videos from the photo picker are files, not images (`InlinePhotoSelection.isVideo`, `SessionView.syncPickedPhotos`).
- `AttachmentFileType.maxInlineBytes` (20 MiB) is the server's inline cap (`AttachmentFileType.swift:10-12`). The image encoder's cap is 20_000_000 bytes, slightly under that. Files have no client size cap except the 200 MB confirm.
- Inline photo selection and chips share UUIDs so deselect and chip-remove stay in sync, including items still importing (`InlinePhotoSelection.unlandedRemovals`).
- `PhotosPicker` filter objects are static singletons because a new filter instance reloads the picker and loses scroll position (`AttachmentMenu.swift:51-52`).
- The system picker cannot be drawn translucent (it shows a blurred placeholder). The glass veil fades; the picker stays opaque (`AttachmentMenu.swift:278-282`, `423-425`).
- Camera front photos are unmirrored, matching the system Camera app (`AttachmentCameraView.swift:688-693`). Zoom "1×" is the wide lens even when the virtual device's 1.0 is the ultra-wide (`AttachmentCameraLenses.swift:23-25`).
- Share consume renames to `.claimed-` before reading, so a double foreground cannot deliver twice. Staging dirs younger than 1 hour are left alone (`ShareHandoff.swift:264-269`).
- Share targets are published on leaving the foreground, not continuously. A session created and shared without backgrounding the app will not be in the sheet.
- Dictation's `AVAudioSession` refuses HFP on purpose. Voice calls request HFP on purpose (`.allowBluetoothHFP`, `.voiceChat`). They cannot share a session configuration. Starting a call ends dictation first; `AudioSessionGeneration` bumps so a late deactivation does not tear down a newer call (`VoiceAudioClient.swift:640-669`).
- GPT-Live muted calls send silence. Stopping the mic entirely stalls delegated speech (`VoiceCallClient.swift:1452-1453`).
- Realtime barge-in and GPT-Live duplex are different state machines behind one `VoiceCallEvent` enum. Port both coordinators; do not collapse them.
- `delegate` on GPT-Live has empty arguments. The prompt text is assembled from the transcript tail after a 180 ms settle, and late transcript deltas can steer a follow-up prompt (`VoiceFeature.swift:360-376`, `521-553`).
- Voice transcripts are phone-local (`Documents/voice-transcripts.json`), keyed `serverID:sessionID`, and injected into the next call's instructions. They are not on the server.
- Live Activity is not a second source of truth. Home pushes a projection. Relaunch ends it. A swipe-away sticks for that call.
- Widget activities cannot animate the scope, so the island uses static dot patterns. Do not try to run `DotScopeSimulation` in the notification shade.
- Crash detection is "foreground marker file still present and build matches", not a crash reporter SDK. An App Store update that kills the process is excluded because the marker's build string would differ (`DiagnosticsJournal.PreviousRun`, `DiagnosticsJournal.swift:35-38`, `107-110`).
- Keychain writes never delete-then-add. A port that replaces the whole encrypted blob on every save can lose the password if the process dies mid-write; write to a temp file and rename, or update in place.
- `LabMarks` POSTs only in DEBUG with an env URL. Release still logs to OSLog and, if recording is on, to the journal.
- Camera permission string also covers pairing QR. One permission, two features.
- No deep link. Do not invent `opencode://` on Android to match iOS; iOS has nothing to match.

## Open Questions

- GPT-Live access: `wss://api.openai.com/v1/live/sessions`, model `gpt-live-1`, voice `marin`, and the cited `live-conversations.md` / `live-delegation.md` are not in this repo. Confirm the key's entitlement before treating GPT-Live as shippable on Android. Realtime is the documented fallback and is a Settings choice.
- Whether `gpt-realtime-2.1` and `gpt-live-transcribe` are stable public model ids or preview ids. The preflight is a live `GET /v1/models/{id}` and will tell a given key.
- Server contract for `POST /api/experimental/fs/write` and `GET /api/info` `paths.tmp` — required for files, share-then-send, and problem-report attachments. A server without them disables Files, videos, and report uploads (timeline inlined instead). Confirm the Android client should treat 404 the same way (`OpenCodeClientError.uploadsUnsupported`).
- `GET /api/fs/read` range support on the servers this app will talk to. Streaming vs full download branches on 206 (`AgentFileLoader.probe`).
- Play policy for a microphone foreground service that exists only for an OpenAI voice call, and whether the disclosure copy must match the iOS mic usage string verbatim.
- On-device dictation: no decision is encoded for an Android model. iOS uses Apple's iOS 26 asset. Shipping a third-party on-device model is a licensing choice this tree does not make.
- Inline photo card: product may accept the system photo picker (separate activity) instead of a custom MediaStore grid. The iOS UX is specifically an inline card that lifts the composer (`AttachmentMenu.swift:66-70`). That should be an explicit product call, not assumed.
- Widget/Live Activity visual fidelity: Android notifications cannot host the Metal scope. The iOS widget already dropped the animation. Matching `CallDisplay` + `DotMatrixGlyph` is the right bar, not a live shader in the shade.
- `dev.kitlangton.opencode.servers` is a leftover service name. Irrelevant for a new Android keystore unless someone later wants a migration note in docs.
