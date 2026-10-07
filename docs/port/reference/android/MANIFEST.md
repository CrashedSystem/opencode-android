# Android reference screenshots

68 uncropped PNGs of the debug build at `8b09b46`, the Android twin of `../ios/MANIFEST.md`, with the same file names and states. The device is the AVD `iphone_size`: Android 16 (API 36, Google APIs, arm64) at 1320 by 2868 pixels and density 480, which is 440 by 956 dp, the same as the iOS captures' 440 by 956 points at scale 3. The status bar is frozen at 9:41 with SystemUI demo mode.

Recapture from the repo root with `scripts/capture-android-reference.sh`. Pass a test name, then `light` or `dark`, to recapture one test's files. The script creates and boots the AVD if it is missing, starts the five fixtures from `.upstream/opencode-ios/scripts` when their ports are free, builds and installs the debug APK, and runs `scripts/android-reference/capture.py`, the twin of `CaptureTests.swift`. The app is launched with the same lab environment and arguments as an `am start` extra each (`../fixtures.md`), and the fixtures are reached at `10.0.2.2`. Appearance comes from `cmd uimode night` plus `-lab-interface-style`.

The driver finds elements by test tag, text, or content description in `android layout`, so it waits for what is on screen rather than for offscreen rows that XCUITest can see. `09-transcript-table` waits for the last line of the reply instead of the heading above it. `03-connect-error` uses the same `http://127.0.0.1:9`, which inside the emulator is the emulator itself and refuses the connection the same way. `16-streaming` streams for 12 seconds instead of 6, because a screencap takes about 2 seconds and a 6-second stream has ended by the time the shot lands. `testConnectScreens` clears the app's data first, because Android storage persists across launches where the iOS lab's does not.

The first full unattended run produced all 68 files. Two light-mode tests passed on their retry: the drawer's search field and the composer's message field moved under an animation when first looked up. The driver now waits for a field to settle for longer.

| File | State | Fixture | Command | Device | dp | Density |
| --- | --- | --- | --- | --- | --- | --- |
| 01-first-launch-light.png | First launch, Scan Pairing Code | No server | `scripts/capture-android-reference.sh testConnectScreens light` | iphone_size | 440x956 | 480 |
| 01-first-launch-dark.png | First launch, Scan Pairing Code | No server | `scripts/capture-android-reference.sh testConnectScreens dark` | iphone_size | 440x956 | 480 |
| 02-server-manual-light.png | Enter a server manually | No server | `scripts/capture-android-reference.sh testConnectScreens light` | iphone_size | 440x956 | 480 |
| 02-server-manual-dark.png | Enter a server manually | No server | `scripts/capture-android-reference.sh testConnectScreens dark` | iphone_size | 440x956 | 480 |
| 03-connect-error-light.png | Connect error | Typed `http://127.0.0.1:9` | `scripts/capture-android-reference.sh testConnectScreens light` | iphone_size | 440x956 | 480 |
| 03-connect-error-dark.png | Connect error | Typed `http://127.0.0.1:9` | `scripts/capture-android-reference.sh testConnectScreens dark` | iphone_size | 440x956 | 480 |
| 04-drawer-light.png | Drawer, pinned and recents | Gauntlet `:4120`, pin `ses_gauntlet_02` and `ses_gauntlet_03`, start `04`, ask `05` | `scripts/capture-android-reference.sh testDrawerCatchUpAndWhatsNew light` | iphone_size | 440x956 | 480 |
| 04-drawer-dark.png | Drawer, pinned and recents | Gauntlet `:4120`, pin `ses_gauntlet_02` and `ses_gauntlet_03`, start `04`, ask `05` | `scripts/capture-android-reference.sh testDrawerCatchUpAndWhatsNew dark` | iphone_size | 440x956 | 480 |
| 05-drawer-search-light.png | Drawer search, query composer | Gauntlet `:4120` | `scripts/capture-android-reference.sh testDrawerCatchUpAndWhatsNew light` | iphone_size | 440x956 | 480 |
| 05-drawer-search-dark.png | Drawer search, query composer | Gauntlet `:4120` | `scripts/capture-android-reference.sh testDrawerCatchUpAndWhatsNew dark` | iphone_size | 440x956 | 480 |
| 06-draft-light.png | New session draft | Session lab `:4110`, checkpoint `intro` | `scripts/capture-android-reference.sh testDraftComposerAndMenus light` | iphone_size | 440x956 | 480 |
| 06-draft-dark.png | New session draft | Session lab `:4110`, checkpoint `intro` | `scripts/capture-android-reference.sh testDraftComposerAndMenus dark` | iphone_size | 440x956 | 480 |
| 07-transcript-markdown-light.png | Transcript heading, prose, and code | Session lab `:4110`, checkpoint `typography` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 07-transcript-markdown-dark.png | Transcript heading, prose, and code | Session lab `:4110`, checkpoint `typography` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 08-transcript-tasks-light.png | Task list, two checked and one open | Session lab `:4110`, checkpoint `typography` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 08-transcript-tasks-dark.png | Task list, two checked and one open | Session lab `:4110`, checkpoint `typography` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 09-transcript-table-light.png | Summary table at the end of the reply | Session lab `:4110`, checkpoint `typography` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 09-transcript-table-dark.png | Summary table at the end of the reply | Session lab `:4110`, checkpoint `typography` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 10-transcript-links-light.png | File links in the transcript | Session lab `:4110`, checkpoint `files` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 10-transcript-links-dark.png | File links in the transcript | Session lab `:4110`, checkpoint `files` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 11-tools-collapsed-light.png | Used 1 Shell, collapsed | Session lab `:4110`, checkpoint `tool-completed` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 11-tools-collapsed-dark.png | Used 1 Shell, collapsed | Session lab `:4110`, checkpoint `tool-completed` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 12-tools-expanded-light.png | Used 1 Shell, expanded | Session lab `:4110`, checkpoint `tool-completed` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 12-tools-expanded-dark.png | Used 1 Shell, expanded | Session lab `:4110`, checkpoint `tool-completed` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 13-subagent-light.png | Subagent card open | Session lab `:4110`, checkpoint `subagent` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 13-subagent-dark.png | Subagent card open | Session lab `:4110`, checkpoint `subagent` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 14-images-light.png | Tool image mosaic | Session lab `:4110`, checkpoint `tool-images` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 14-images-dark.png | Tool image mosaic | Session lab `:4110`, checkpoint `tool-images` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 15-running-light.png | Working line | Session lab `:4110`, checkpoint `tool-running` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 15-running-dark.png | Working line | Session lab `:4110`, checkpoint `tool-running` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 16-streaming-light.png | Window stream update | Session lab `:4110`, checkpoint `long`, `POST /lab/window-stream` for 12 s | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 16-streaming-dark.png | Window stream update | Session lab `:4110`, checkpoint `long`, `POST /lab/window-stream` for 12 s | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 17-composer-text-light.png | Composer with typed text | Session lab `:4110`, checkpoint `intro` | `scripts/capture-android-reference.sh testDraftComposerAndMenus light` | iphone_size | 440x956 | 480 |
| 17-composer-text-dark.png | Composer with typed text | Session lab `:4110`, checkpoint `intro` | `scripts/capture-android-reference.sh testDraftComposerAndMenus dark` | iphone_size | 440x956 | 480 |
| 18-composer-attachments-light.png | Pasted image attachment | Session lab `:4110`, checkpoint `intro`, `-lab-pasteboard-image` | `scripts/capture-android-reference.sh testDraftComposerAndMenus light` | iphone_size | 440x956 | 480 |
| 18-composer-attachments-dark.png | Pasted image attachment | Session lab `:4110`, checkpoint `intro`, `-lab-pasteboard-image` | `scripts/capture-android-reference.sh testDraftComposerAndMenus dark` | iphone_size | 440x956 | 480 |
| 19-composer-steer-menu-light.png | Steer and queue menu | Session lab `:4110`, checkpoint `tool-running` | `scripts/capture-android-reference.sh testTranscript light` | iphone_size | 440x956 | 480 |
| 19-composer-steer-menu-dark.png | Steer and queue menu | Session lab `:4110`, checkpoint `tool-running` | `scripts/capture-android-reference.sh testTranscript dark` | iphone_size | 440x956 | 480 |
| 20-permission-light.png | Permission sheet, Allow Once | Approval `:4105`, mode `permission` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet light` | iphone_size | 440x956 | 480 |
| 20-permission-dark.png | Permission sheet, Allow Once | Approval `:4105`, mode `permission` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet dark` | iphone_size | 440x956 | 480 |
| 21-question-light.png | Question form | Approval `:4105`, mode `question` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet light` | iphone_size | 440x956 | 480 |
| 21-question-dark.png | Question form | Approval `:4105`, mode `question` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet dark` | iphone_size | 440x956 | 480 |
| 22-header-context-light.png | Header context ring at 85% | Session lab `:4110`, checkpoint `markdown`, `POST /lab/context` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet light` | iphone_size | 440x956 | 480 |
| 22-header-context-dark.png | Header context ring at 85% | Session lab `:4110`, checkpoint `markdown`, `POST /lab/context` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet dark` | iphone_size | 440x956 | 480 |
| 23-session-menu-light.png | Session menu | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet light` | iphone_size | 440x956 | 480 |
| 23-session-menu-dark.png | Session menu | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet dark` | iphone_size | 440x956 | 480 |
| 24-session-sheet-light.png | Session sheet | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet light` | iphone_size | 440x956 | 480 |
| 24-session-sheet-dark.png | Session sheet | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-android-reference.sh testRequestsHeaderAndSheet dark` | iphone_size | 440x956 | 480 |
| 25-model-picker-light.png | Model picker | Session lab `:4110`, checkpoint `intro` | `scripts/capture-android-reference.sh testDraftComposerAndMenus light` | iphone_size | 440x956 | 480 |
| 25-model-picker-dark.png | Model picker | Session lab `:4110`, checkpoint `intro` | `scripts/capture-android-reference.sh testDraftComposerAndMenus dark` | iphone_size | 440x956 | 480 |
| 26-attachment-menu-light.png | Attachment menu | Session lab `:4110`, checkpoint `intro` | `scripts/capture-android-reference.sh testDraftComposerAndMenus light` | iphone_size | 440x956 | 480 |
| 26-attachment-menu-dark.png | Attachment menu | Session lab `:4110`, checkpoint `intro` | `scripts/capture-android-reference.sh testDraftComposerAndMenus dark` | iphone_size | 440x956 | 480 |
| 27-background-work-light.png | Background work panel | Gauntlet `:4120`, `shell-start` and `tour-child-start` on `ses_gauntlet_09` | `scripts/capture-android-reference.sh testBackgroundShellSettingsAndError light` | iphone_size | 440x956 | 480 |
| 27-background-work-dark.png | Background work panel | Gauntlet `:4120`, `shell-start` and `tour-child-start` on `ses_gauntlet_09` | `scripts/capture-android-reference.sh testBackgroundShellSettingsAndError dark` | iphone_size | 440x956 | 480 |
| 28-shell-output-light.png | Shell output | Gauntlet `:4120`, shell `sh_gauntlet_1` | `scripts/capture-android-reference.sh testBackgroundShellSettingsAndError light` | iphone_size | 440x956 | 480 |
| 28-shell-output-dark.png | Shell output | Gauntlet `:4120`, shell `sh_gauntlet_1` | `scripts/capture-android-reference.sh testBackgroundShellSettingsAndError dark` | iphone_size | 440x956 | 480 |
| 29-catch-up-light.png | Catch Up | Gauntlet `:4120` | `scripts/capture-android-reference.sh testDrawerCatchUpAndWhatsNew light` | iphone_size | 440x956 | 480 |
| 29-catch-up-dark.png | Catch Up | Gauntlet `:4120` | `scripts/capture-android-reference.sh testDrawerCatchUpAndWhatsNew dark` | iphone_size | 440x956 | 480 |
| 30-whats-new-light.png | What's New | Gauntlet `:4120`, `OPENCODE_LAB_WHATS_NEW=unseen` | `scripts/capture-android-reference.sh testDrawerCatchUpAndWhatsNew light` | iphone_size | 440x956 | 480 |
| 30-whats-new-dark.png | What's New | Gauntlet `:4120`, `OPENCODE_LAB_WHATS_NEW=unseen` | `scripts/capture-android-reference.sh testDrawerCatchUpAndWhatsNew dark` | iphone_size | 440x956 | 480 |
| 31-settings-light.png | Settings | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-android-reference.sh testSettingsDiagnosticsAndError light` | iphone_size | 440x956 | 480 |
| 31-settings-dark.png | Settings | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-android-reference.sh testSettingsDiagnosticsAndError dark` | iphone_size | 440x956 | 480 |
| 32-diagnostics-light.png | Diagnostics | Session lab `:4110`, opened from Settings | `scripts/capture-android-reference.sh testSettingsDiagnosticsAndError light` | iphone_size | 440x956 | 480 |
| 32-diagnostics-dark.png | Diagnostics | Session lab `:4110`, opened from Settings | `scripts/capture-android-reference.sh testSettingsDiagnosticsAndError dark` | iphone_size | 440x956 | 480 |
| 33-error-banner-light.png | Request load error | Approval `:4105`, mode `initial-fetch-failure` | `scripts/capture-android-reference.sh testSettingsDiagnosticsAndError light` | iphone_size | 440x956 | 480 |
| 33-error-banner-dark.png | Request load error | Approval `:4105`, mode `initial-fetch-failure` | `scripts/capture-android-reference.sh testSettingsDiagnosticsAndError dark` | iphone_size | 440x956 | 480 |
| 35-composer-empty-light.png | Empty composer on an existing session | Session lab `:4110`, checkpoint `intro`, `ses_approval_root` | `scripts/capture-android-reference.sh testDraftComposerAndMenus light` | iphone_size | 440x956 | 480 |
| 35-composer-empty-dark.png | Empty composer on an existing session | Session lab `:4110`, checkpoint `intro`, `ses_approval_root` | `scripts/capture-android-reference.sh testDraftComposerAndMenus dark` | iphone_size | 440x956 | 480 |
