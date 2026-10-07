# iOS reference screenshots

68 uncropped PNGs of OpenCode iOS `d809b1e` on simulator `OpenCode Android Port Lab` (`8E4837B1-36AB-4F52-9799-E5EEDD1968A0`), an iPhone 17 Pro Max running iOS 27.0. Each file is 440 by 956 points at scale 3, which is 1320 by 2868 pixels. `sips` reported 1320 by 2868 for every PNG. The status bar time is 9:41.

Recapture from the Android repo root with `scripts/capture-ios-reference.sh`, with `IOS_CHECKOUT` set to a worktree of the commit to capture. Pass a test id, then `light` or `dark`, to recapture one file's test. The driver is `scripts/ios-reference/CaptureUITests/CaptureTests.swift`. Fixture setup for each server is in `../fixtures.md`. The drawer shot is taken only after `ses_gauntlet_02` and `ses_gauntlet_03` are pinned and neither Pin nor Unpin is on screen.

`34` is unused. The simulator has no camera to scan with, so pairing is `01-first-launch` with **Scan Pairing Code** and no scanner, though the attachment menu still offers Camera. `35-composer-empty` is the empty composer on `ses_approval_root`, not the new-session draft in `06-draft`.

## Changes since `41099dd`

Twenty-seven files changed bytes but match the previous pixels within 2%: both appearances of `03`, `06`, `09` through `12`, `14`, `22`, `25`, `33`, and `35`, plus `05-drawer-search-light`, `08-transcript-tasks-light`, `20-permission-light`, `21-question-dark`, and `29-catch-up-light`. Nine more match the previous bytes.

`26-attachment-menu` shows the menu that grows out of the composer: one glass panel standing above it with Camera, Photos, Files, and the system's Paste control, and the + turned into a ×.

`30-whats-new` leads with Hold + to Attach, then Send from Dictation.

`31-settings` is the short list: the server card with its dot field, Connected in green, and Change; then Notifications, Voice, and Diagnostics as tinted rows; then What's New. Android has no Notifications row (`../../deviations.md`).

`32-diagnostics` leads with Developer Mode and Voice Calls above Record diagnostics.

The rest differ only by something that was moving: the text caret on `05-drawer-search-dark`, `17-composer-text`, and `18-composer-attachments`; the spinner on `04-drawer`; the activity glyph's frame on `13-subagent`, `19-composer-steer-menu`, and `27-background-work`; the stream on `15-running` and `16-streaming`; the live log on `28-shell-output`; the session menu's glass shadow on `23-session-menu`; and the transcript under the composer's glass on `07-transcript-markdown-dark` and `08-transcript-tasks-dark`.

| File | State | Fixture | Command | Device | Points | Scale |
| --- | --- | --- | --- | --- | --- | --- |
| 01-first-launch-light.png | First launch, Scan Pairing Code | No server | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testConnectScreens light` | iPhone 17 Pro Max | 440x956 | 3 |
| 01-first-launch-dark.png | First launch, Scan Pairing Code | No server | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testConnectScreens dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 02-server-manual-light.png | Enter a server manually | No server | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testConnectScreens light` | iPhone 17 Pro Max | 440x956 | 3 |
| 02-server-manual-dark.png | Enter a server manually | No server | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testConnectScreens dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 03-connect-error-light.png | Connect error | Typed `http://127.0.0.1:9` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testConnectScreens light` | iPhone 17 Pro Max | 440x956 | 3 |
| 03-connect-error-dark.png | Connect error | Typed `http://127.0.0.1:9` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testConnectScreens dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 04-drawer-light.png | Drawer, pinned and recents | Gauntlet `:4120`, pin `ses_gauntlet_02` and `ses_gauntlet_03`, start `04`, ask `05` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDrawerCatchUpAndWhatsNew light` | iPhone 17 Pro Max | 440x956 | 3 |
| 04-drawer-dark.png | Drawer, pinned and recents | Gauntlet `:4120`, pin `ses_gauntlet_02` and `ses_gauntlet_03`, start `04`, ask `05` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDrawerCatchUpAndWhatsNew dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 05-drawer-search-light.png | Drawer search, query composer | Gauntlet `:4120` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDrawerCatchUpAndWhatsNew light` | iPhone 17 Pro Max | 440x956 | 3 |
| 05-drawer-search-dark.png | Drawer search, query composer | Gauntlet `:4120` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDrawerCatchUpAndWhatsNew dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 06-draft-light.png | New session draft | Session lab `:4110`, checkpoint `intro` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus light` | iPhone 17 Pro Max | 440x956 | 3 |
| 06-draft-dark.png | New session draft | Session lab `:4110`, checkpoint `intro` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 07-transcript-markdown-light.png | Transcript heading, prose, and code | Session lab `:4110`, checkpoint `typography` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 07-transcript-markdown-dark.png | Transcript heading, prose, and code | Session lab `:4110`, checkpoint `typography` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 08-transcript-tasks-light.png | Task list, two checked and one open | Session lab `:4110`, checkpoint `typography` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 08-transcript-tasks-dark.png | Task list, two checked and one open | Session lab `:4110`, checkpoint `typography` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 09-transcript-table-light.png | Summary table at the end of the reply | Session lab `:4110`, checkpoint `typography` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 09-transcript-table-dark.png | Summary table at the end of the reply | Session lab `:4110`, checkpoint `typography` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 10-transcript-links-light.png | File links in the transcript | Session lab `:4110`, checkpoint `files` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 10-transcript-links-dark.png | File links in the transcript | Session lab `:4110`, checkpoint `files` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 11-tools-collapsed-light.png | Used 1 Shell, collapsed | Session lab `:4110`, checkpoint `tool-completed` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 11-tools-collapsed-dark.png | Used 1 Shell, collapsed | Session lab `:4110`, checkpoint `tool-completed` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 12-tools-expanded-light.png | Used 1 Shell, expanded | Session lab `:4110`, checkpoint `tool-completed` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 12-tools-expanded-dark.png | Used 1 Shell, expanded | Session lab `:4110`, checkpoint `tool-completed` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 13-subagent-light.png | Subagent card open | Session lab `:4110`, checkpoint `subagent` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 13-subagent-dark.png | Subagent card open | Session lab `:4110`, checkpoint `subagent` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 14-images-light.png | Tool image mosaic | Session lab `:4110`, checkpoint `tool-images` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 14-images-dark.png | Tool image mosaic | Session lab `:4110`, checkpoint `tool-images` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 15-running-light.png | Working line | Session lab `:4110`, checkpoint `tool-running` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 15-running-dark.png | Working line | Session lab `:4110`, checkpoint `tool-running` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 16-streaming-light.png | Window stream update | Session lab `:4110`, checkpoint `long`, `POST /lab/window-stream` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 16-streaming-dark.png | Window stream update | Session lab `:4110`, checkpoint `long`, `POST /lab/window-stream` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 17-composer-text-light.png | Composer with typed text | Session lab `:4110`, checkpoint `intro` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus light` | iPhone 17 Pro Max | 440x956 | 3 |
| 17-composer-text-dark.png | Composer with typed text | Session lab `:4110`, checkpoint `intro` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 18-composer-attachments-light.png | Pasted image attachment | Session lab `:4110`, checkpoint `intro`, `-lab-pasteboard-image` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus light` | iPhone 17 Pro Max | 440x956 | 3 |
| 18-composer-attachments-dark.png | Pasted image attachment | Session lab `:4110`, checkpoint `intro`, `-lab-pasteboard-image` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 19-composer-steer-menu-light.png | Steer and queue menu | Session lab `:4110`, checkpoint `tool-running` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript light` | iPhone 17 Pro Max | 440x956 | 3 |
| 19-composer-steer-menu-dark.png | Steer and queue menu | Session lab `:4110`, checkpoint `tool-running` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testTranscript dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 20-permission-light.png | Permission sheet, Allow Once | Approval `:4105`, mode `permission` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet light` | iPhone 17 Pro Max | 440x956 | 3 |
| 20-permission-dark.png | Permission sheet, Allow Once | Approval `:4105`, mode `permission` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 21-question-light.png | Question form | Approval `:4105`, mode `question` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet light` | iPhone 17 Pro Max | 440x956 | 3 |
| 21-question-dark.png | Question form | Approval `:4105`, mode `question` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 22-header-context-light.png | Header context ring at 85% | Session lab `:4110`, checkpoint `markdown`, `POST /lab/context` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet light` | iPhone 17 Pro Max | 440x956 | 3 |
| 22-header-context-dark.png | Header context ring at 85% | Session lab `:4110`, checkpoint `markdown`, `POST /lab/context` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 23-session-menu-light.png | Session menu | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet light` | iPhone 17 Pro Max | 440x956 | 3 |
| 23-session-menu-dark.png | Session menu | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 24-session-sheet-light.png | Session sheet | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet light` | iPhone 17 Pro Max | 440x956 | 3 |
| 24-session-sheet-dark.png | Session sheet | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testRequestsHeaderAndSheet dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 25-model-picker-light.png | Model picker | Session lab `:4110`, checkpoint `intro` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus light` | iPhone 17 Pro Max | 440x956 | 3 |
| 25-model-picker-dark.png | Model picker | Session lab `:4110`, checkpoint `intro` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 26-attachment-menu-light.png | Attachment menu | Session lab `:4110`, checkpoint `intro` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus light` | iPhone 17 Pro Max | 440x956 | 3 |
| 26-attachment-menu-dark.png | Attachment menu | Session lab `:4110`, checkpoint `intro` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 27-background-work-light.png | Background work panel | Gauntlet `:4120`, `shell-start` and `tour-child-start` on `ses_gauntlet_09` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testBackgroundShellSettingsAndError light` | iPhone 17 Pro Max | 440x956 | 3 |
| 27-background-work-dark.png | Background work panel | Gauntlet `:4120`, `shell-start` and `tour-child-start` on `ses_gauntlet_09` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testBackgroundShellSettingsAndError dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 28-shell-output-light.png | Shell output | Gauntlet `:4120`, shell `sh_gauntlet_1` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testBackgroundShellSettingsAndError light` | iPhone 17 Pro Max | 440x956 | 3 |
| 28-shell-output-dark.png | Shell output | Gauntlet `:4120`, shell `sh_gauntlet_1` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testBackgroundShellSettingsAndError dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 29-catch-up-light.png | Catch Up | Gauntlet `:4120` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDrawerCatchUpAndWhatsNew light` | iPhone 17 Pro Max | 440x956 | 3 |
| 29-catch-up-dark.png | Catch Up | Gauntlet `:4120` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDrawerCatchUpAndWhatsNew dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 30-whats-new-light.png | What's New | Gauntlet `:4120`, `OPENCODE_LAB_WHATS_NEW=unseen` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDrawerCatchUpAndWhatsNew light` | iPhone 17 Pro Max | 440x956 | 3 |
| 30-whats-new-dark.png | What's New | Gauntlet `:4120`, `OPENCODE_LAB_WHATS_NEW=unseen` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDrawerCatchUpAndWhatsNew dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 31-settings-light.png | Settings | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testSettingsDiagnosticsAndError light` | iPhone 17 Pro Max | 440x956 | 3 |
| 31-settings-dark.png | Settings | Session lab `:4110`, checkpoint `markdown` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testSettingsDiagnosticsAndError dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 32-diagnostics-light.png | Diagnostics | Session lab `:4110`, opened from Settings | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testSettingsDiagnosticsAndError light` | iPhone 17 Pro Max | 440x956 | 3 |
| 32-diagnostics-dark.png | Diagnostics | Session lab `:4110`, opened from Settings | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testSettingsDiagnosticsAndError dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 33-error-banner-light.png | Request load error | Approval `:4105`, mode `initial-fetch-failure` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testSettingsDiagnosticsAndError light` | iPhone 17 Pro Max | 440x956 | 3 |
| 33-error-banner-dark.png | Request load error | Approval `:4105`, mode `initial-fetch-failure` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testSettingsDiagnosticsAndError dark` | iPhone 17 Pro Max | 440x956 | 3 |
| 35-composer-empty-light.png | Empty composer on an existing session | Session lab `:4110`, checkpoint `intro`, `ses_approval_root` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus light` | iPhone 17 Pro Max | 440x956 | 3 |
| 35-composer-empty-dark.png | Empty composer on an existing session | Session lab `:4110`, checkpoint `intro`, `ses_approval_root` | `scripts/capture-ios-reference.sh CaptureUITests/CaptureTests/testDraftComposerAndMenus dark` | iPhone 17 Pro Max | 440x956 | 3 |
