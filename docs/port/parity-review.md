# Android/iOS parity review

## Summary

Reviewed every original PNG: 34 states, light and dark, 136 images. Both sets are 1320 × 2868 pixels, or 440 × 956 logical units. The iOS reference is `553a407`; the Android captures are `7c2c9a2`. Kotlin locations refer to the inspected checkout at `b5da18a`.

| Severity | App findings | Glass findings | Total |
| --- | ---: | ---: | ---: |
| Blocker | 2 | 0 | 2 |
| Visible | 14 | 3 | 17 |
| Minor | 9 | 0 | 9 |
| Total | 25 | 3 | 28 |

Counts are deduplicated finding IDs, not repeated occurrences or separate light/dark copies. The two blockers are blue inline links that have no link action, and the noninteractive **Send Reports To** row. The largest visual differences outside glass are text layout, undersized inline Markdown images, and Catch Up's extra transcript inset.

Severity follows the requested scale: **blocker** breaks a flow or presents a false affordance; **visible** is noticeable in a comparison; **minor** is local polish. Glass has its own category and worker allocation. Expected platform differences and capture mismatches below do not enter the counts.

## Scope and evidence

- Each state section covers `docs/port/reference/ios/<state>-light.png`, its Android twin, and both dark images. Images were opened individually at original size, not judged from a contact sheet.
- Compared content, alignment, spacing, sizes, typography, colors, glyphs, corners, separators, and selected/disabled/attention state. Read both manifests, `docs/port/deviations.md`, both `AGENTS.md` files, and the relevant Kotlin and Swift renderers.
- Kotlin paths in the tables are relative to `app/src/main/java/ai/opencode/mobilecode/`. Their Swift counterparts live under `.upstream/opencode-ios/OpenCode/`; transcript files live in its `Transcript/` directory.
- Pixel measurements use the original PNGs, converted to sRGB. For example, assistant line starts in state 35 have about a 24.3 pt pitch on iOS versus 26 dp on Android. The blue ink in states 04 and 35 is `#0088FF` / `#0091FF` on iOS light/dark versus `#007AFF` / `#0A84FF` on Android. No WCAG contrast failure is claimed.
- The blockers have screenshot affordances and missing source wiring as evidence. No live tap tests, motion recording, TalkBack/VoiceOver walk, enlarged-text tests, or narrow-device tests were run. Streaming fade behavior is established from both implementations, not timing inferred from one still.
- Current Android source already justifies assistant prose. Keep that deviation. Neither the captured ragged right edge nor the current justified spacing is a defect.

### Expected differences and capture limits

| Difference | Judgment and owner |
| --- | --- |
| Status bar, navigation indicator, keyboard layout and height | Expected Android chrome. The smaller top safe area explains the roughly 38-unit upward shift of normal session headers and onboarding content. The bottom inset also differs. Do not hard-code iPhone safe areas into Android. |
| Native sheet outline, detent, scrim, toolbar presentation; native menu placement, icons and separators | Expected under `AGENTS.md`. Compare content/order/state, not iOS system decoration. Fewer rows above a native sheet's fold are not missing rows. |
| SF versus Roboto, including different glyph widths and some wrapping/truncation | Expected typeface difference. Findings below concern actual metrics or container behavior, not a request to install SF on Android. |
| 06, 17, 18, 25, 26, 35 lack the iOS blue drawer dot | The Android manifest explains the settled read receipt. Capture/state mismatch, not evidence of a broken badge. Align read-receipt timing in `scripts/android-reference/capture.py` if these shots are used for strict comparisons. |
| 07 and 08 show different amounts of preceding/following transcript | The capture helpers accept broad positioning bands. `park_heading` at `capture.py:642` and `parkHeading` at `CaptureTests.swift:394` allow headings anywhere between 100 and 220 units, with different gesture/element geometry. Anchor the same rendered text edge before claiming a scroll-position bug. The lower Android heading in 07 is not proof of extra app padding. |
| 12 has an iOS jump-to-latest pill but no Android pill | The iOS expanded reply extends below its composer; Android has more usable height and its end fits. Insets and text metrics differ. Recheck after text-layout fixes, but do not force a browsing indicator when the end is already visible. |
| 16 stream update number and elapsed time; 27/28 shell timers; 32 request counts | Run/capture timing. Android deliberately streams for 12 seconds, iOS for 6. Do not change model text or timers to match a still. |
| 18 pasted thumbnail has softer edges and a different indigo on Android | The lab image is not pixel-identical. iOS `OpenCodeApp.swift:31` makes a 16 pt renderer image at simulator scale 3; `OpenCodeApp.kt:57` makes a 16 × 16 pixel bitmap with a hard-coded indigo. Match the fixture's pixel resolution/color before judging production image-import quality. |
| 29 iOS capture does not paint the request banner's leading glyph; Android does | Both `ComposerBanner` implementations request that glyph. Minor capture/reference discrepancy, not a reason to remove the Android icon. Verify the SF symbol on iOS/device first. |
| 03 error detail/build text and 31 server address | Native transport error details, build metadata, and `127.0.0.1` versus emulator host `10.0.2.2`. Preserve truthful diagnostics; these are not different connection failures. |

## State-by-state findings

"Both" means the difference appears in light and dark. Reused IDs refer to the same fix, not another counted finding. V02's user-text leading also affects the other visible multiline user prompts; 29 is the clearest example of its separate excess-width symptom.

### 01-first-launch

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| M01 | Minor | Alignment, both | Step titles align to the number badge's first baseline on iOS. Android top-aligns the badge and title column, so the title sits higher relative to its number. | `AppView.kt:345`, `SetupStep`, `Alignment.Top`; Swift uses `.firstTextBaseline`. |
| M03 | Minor | Separator, both | iOS setup separators are hairlines. Android draws a full 1 dp Material divider, three physical pixels at this density. | `AppView.kt:286,290`, `SetupSteps`, `HorizontalDivider`. |
| M05 | Minor | Icons, both | The QR-viewfinder and copy glyph have different contours/ink bounds. Android's copy mark is narrower and its viewfinder is lighter. The labels and actions match. | `system/SystemImage.kt:21,30`, `symbolSize` / `SystemImage`; `system/SymbolNames.kt:208,218`, `drawable`. |

Wordmark size, 24-unit content edge, copy, step order and main button are retained. The higher Android content is the native safe area, not an onboarding redesign.

### 02-server-manual

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| M02 | Minor | Field alignment, both | iOS pads the entire password row by 14 units. Android pads only the text field; the eye button sits 14 dp farther toward the trailing field edge. | `AppView.kt:631,702,719`, `CredentialForm` / `Field` / `ServerField`. |
| G03 | Visible | Glass shape, both | The iOS disabled Connect action keeps the capsule shape of its enabled primary button. Android's disabled branch substitutes 14 dp rounded corners. | `AppView.kt:664`, `CredentialForm`, disabled modifier branch. |

All three field labels, placeholders, password visibility control, disabled state, hint and Cancel are present. Native sheet/toolbar presentation is excluded.

### 03-connect-error

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V06 | Visible | Color, both | Warning orange differs from the iOS 27 reference. Android uses the older fixed system-orange values. | `system/SystemColors.kt:135,175`, `LightPalette` / `DarkPalette`; applied by `AppView.kt:400`, `ConnectionErrorCard`. |
| G01 | Visible | Glass, both | The iOS error card/copy action have quieter depth. Android's light surface is flatter; its dark rim and top highlight are more conspicuous. | `system/Glass.kt:60`, `glassEffect`, through `AppView.kt:380`, `ConnectionErrorCard`. |
| M01, M03, M05 | Minor | Setup alignment / separator / icons, both | The onboarding differences from 01 remain below the error card. | `AppView.kt`, `SetupStep` / `SetupSteps`; `system/SystemImage.kt`, `SystemImage`. |

Failure title, phase, target, attempt, copy-report action and recovery actions match. Native exception wording and version/build are not parity defects.

### 04-drawer

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V06 | Visible | Color, both | Catch Up, What's New, unread dots and attention orange differ in hue. The measured iOS blue is brighter than Android's old fixed blue. | `system/SystemColors.kt`, palettes; `HomeView.kt:590,1022,1158`, `CatchUpRow` / `SessionIndicator` / `DrawerShortcutLabel`. |
| M05 | Minor | Icons, both | iOS uses a stack of rounded rectangles, a gift with a different bow, and a filled cog. Android's stack is layered diamonds and its cog is more outlined. | `system/SymbolNames.kt`, `drawable`; `HomeView.kt:634,1158`, `DrawerControls` / `DrawerShortcutLabel`. |
| G01 | Visible | Glass, both | Floating New Session/Settings and the exposed session controls have nearly absent light rims but strong dark white rims/highlights on Android. | `system/Glass.kt`, `glassEffect`; `HomeView.kt:694`, `drawerGlass`. |

Catch Up count 9, blue What's New dot, two pins, recents order, attention/unread/running meanings and selected-session styling are retained. Different title truncation alone is not a defect when both use the same single-line body style.

### 05-drawer-search

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| G01 | Visible | Glass, both | The exposed session control/composer glass remains flatter in light and more rim-lit in dark. | `system/Glass.kt`, `glassEffect`. |

Query, Pinned heading, matching pinned row and hiding the bottom drawer controls while search owns focus match. Keyboard design and caret blink phase are expected differences.

### 06-draft

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| M05 | Minor | Icons, both | Android's project/model double chevrons and CPU/folder marks have smaller/different ink bounds. The chevrons are especially faint relative to the iOS disclosure cue. | `system/SystemImage.kt`, `symbolSize` / `SystemImage`; `TranscriptListRows.kt:141`, `SetupRow`. |
| M07 | Minor | Corner curve, both | Project/model cards use the same 14-unit radius, but Android's quarter-circle curve differs from iOS's continuous curve. | `TranscriptListRows.kt:150`, `SetupRow`, `RoundedCornerShape`. |
| G01 | Visible | Glass, both | Header and collapsed composer have the shared light/dark glass mismatch. | `system/Glass.kt`, `glassEffect`; `SessionView.kt`, `DrawerControl` / `ComposerCapsule`. |

Project, directory, model, placeholder, plus and mic are present in the same order. Missing unread dot is the documented capture timing.

### 07-transcript-markdown

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V01 | Visible | Line height, both | iOS body lines have about a 24.3-unit pitch. Android forces 26 sp. Across paragraphs/lists this makes the reply taller and changes what fits on screen. Keep Roboto and justification; correct the pitch instead. | `MarkdownBlockView.kt:121,135,193`, `BlockContent` / `ListItem`, `lineHeight = 26.sp`. |
| V07 | Visible | Quote geometry / color, both | The iOS quote bar extends through its 4-unit top/bottom padding and uses secondary at half opacity. Android's bar starts/ends with the inner text and is darker because `copy(alpha = 0.5f)` replaces secondary's alpha instead of multiplying it. | `MarkdownBlockView.kt:115`, `BlockContent`, blockquote branch. |
| M04 | Minor | List alignment, both | iOS bullet/number markers use the first text baseline. Android top-aligns a smaller marker line box, placing numbers high relative to the body text. | `MarkdownBlockView.kt:158`, `ListItem`, `Alignment.Top`. |
| M09 | Minor | Band background, both | iOS fades overflowing code while retaining the faint band's fill. Android paints an extra page-background gradient over the band, whitening/blackening its right edge. | `MarkdownBlockView.kt:515,546`, `MarkdownBand`; the extra overlay duplicates `horizontalOverflowFade`. |
| G01 | Visible | Glass, both | Shared header/composer material difference. | `system/Glass.kt`, `glassEffect`. |

The different scroll anchor is a capture limit. Heading sizes/weights, bold, italic, inline code and syntax roles are retained. Do not infer a missing code block from this screenshot's different viewport.

### 08-transcript-tasks

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V01, V07 | Visible | Line height / quote styling, both | The reply's longer Android pitch and shorter/darker quote bar remain visible. | `MarkdownBlockView.kt`, `BlockContent` / `ListItem`. |
| M04, M05 | Minor | Task marker alignment / glyph, both | Android task boxes are smaller in ink and top-aligned rather than baseline-aligned. | `MarkdownBlockView.kt:173`, `ListItem`; `system/SystemImage.kt`, `SystemImage`. |
| G01 | Visible | Glass, both | Shared header/composer material difference. | `system/Glass.kt`, `glassEffect`. |

Two checked tasks, one open task and secondary treatment of done text match. Do not replace the actual task markers with ordinary bullets.

### 09-transcript-table

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V01 | Visible | Surrounding prose line height, both | The prose above/below the table uses the longer Android body pitch. | `MarkdownBlockView.kt`, `BlockContent`. |
| M09 | Minor | Band background, both | At logical coordinates `(433,590)` and `(433,720)`, Android's band is `#FEFEFE` while the iOS band stays `#F9F9F9`. Both are `#F9F9F9` away from the trailing fade. | `MarkdownBlockView.kt:546`, `MarkdownBand`, extra background gradient. |
| M05 | Minor | Copy icon, both | Android's reply-copy mark is narrower/different in contour. | `system/SystemImage.kt`, `SystemImage`; `TranscriptEntryView.kt`, `ReplyActions`. |
| G01 | Visible | Glass, both | Shared controls/composer material difference. | `system/Glass.kt`, `glassEffect`. |

Same three columns, row order, values, full-width bands and row rules. Column widths may differ with typeface metrics. The table's measured wrapped-cell pitches are close; do not apply the prose's 26-to-24.3 correction indiscriminately to table cells.

### 10-transcript-links

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| B01 | Blocker | Link action / false affordance, both | iOS paths and local Markdown links open the file viewer. Android paints them blue, but its annotated builder drops URL/link annotations and its text renderer has no link action. The inline file-opening flow is missing despite the visible affordance. | `MarkdownBlockView.kt:432`, `annotated`, only `SpanStyle`; `ArrivingText:414` / `BlockContent:104` do not route link taps to `onOpenFile`. |
| V01 | Visible | Line height, both | File-link paragraphs have the longer Android body pitch. | `MarkdownBlockView.kt`, `BlockContent` / `ListItem`. |
| V06 | Visible | Link color, both | Blue differs from the iOS reference. This is separate from the missing action. | `system/SystemColors.kt`, palettes. |
| M05 | Minor | File/play/copy glyphs, both | Android's file/media pictograms differ; the trailing play/chevron is smaller and lighter. | `FileAttachmentViews.kt:60,74`, `FileAttachmentCard`; `system/SystemImage.kt`, `SystemImage`. |
| M07 | Minor | Corner curve, both | File cards keep radius 14 but not the continuous iOS curve. | `FileAttachmentViews.kt:139`, `AgentFileCards`. |
| G01 | Visible | Glass, both | Shared controls/composer material difference. | `system/Glass.kt`, `glassEffect`. |

Media/document cards are present in the same order and do have their own click handlers. B01 concerns inline text, not a claim that all file cards are inert. Preserve outside-directory reads only on explicit taps.

### 11-tools-collapsed

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| B01 | Blocker | Inline link action, both | The blue `SessionView.swift` target in the first paragraph lacks an Android link action. | `MarkdownBlockView.kt`, `annotated` / `ArrivingText`. |
| V06 | Visible | Link color, both | Shared old Android blue in the inline file target. | `system/SystemColors.kt`, palettes. |
| V01, V07 | Visible | Line height / quote styling, both | Prose/list rhythm and quote bar differ as in 07. | `MarkdownBlockView.kt`, `BlockContent` / `ListItem`. |
| M04, M05 | Minor | Marker alignment / copy glyph, both | Numbers sit higher on Android; the reply-copy icon differs. | `MarkdownBlockView.kt`, `ListItem`; `system/SystemImage.kt`, `SystemImage`. |
| G01 | Visible | Glass, both | Shared controls/composer material difference. | `system/Glass.kt`, `glassEffect`. |

“Used 1 Shell” is collapsed, plain and caret-free on both. Extra empty space below Android's short transcript follows its larger usable viewport, not proof of a follower failure.

### 12-tools-expanded

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| B01 | Blocker | Inline link action, both | The same first-paragraph file target remains blue but inert on Android. | `MarkdownBlockView.kt`, `annotated` / `ArrivingText`. |
| V06 | Visible | Link color, both | Shared old Android blue in the inline file target. | `system/SystemColors.kt`, palettes. |
| V01, V07 | Visible | Line height / quote styling, both | Same reply rhythm/quote differences as 11. | `MarkdownBlockView.kt`, `BlockContent` / `ListItem`. |
| M04, M05 | Minor | Marker alignment / completion glyphs, both | Numbers and check/copy marks have the shared baseline/ink-bound differences. | `MarkdownBlockView.kt`, `ListItem`; `system/SystemImage.kt`, `SystemImage`. |
| G01 | Visible | Glass, both | Shared controls/composer material difference. | `system/Glass.kt`, `glassEffect`. |

Both reveal one Shell row and the same one-line command detail, not a different expanded output block. Jump-pill difference is explained in the capture limits.

### 13-subagent

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V01 | Visible | Prose line height, both | Android's surrounding reply is taller at the same body size. | `MarkdownBlockView.kt`, `BlockContent`. |
| M06 | Minor | Card alignment, both | iOS places the subagent chevron at the top alongside the first title line. Android explicitly centers it vertically in the whole card. | `TranscriptEntryView.kt:630`, `SubagentToolView`, `.align(Alignment.CenterVertically)`. |
| M05, M07 | Minor | Glyph / corner curve, both | Smaller chevron ink; same 14-unit card radius with a different curve. | `system/SystemImage.kt`, `SystemImage`; `TranscriptEntryView.kt:585`, `SubagentToolView`. |
| G01 | Visible | Glass, both | Shared composer/background capsule material difference. | `system/Glass.kt`, `glassEffect`. |

Child title/description, activity glyph, open disclosure and background count match. Working uses body/medium on both; its apparent SF/Roboto weight difference is not a separate defect.

### 14-images

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V01 | Visible | Surrounding prose line height, both | Android prose retains the longer pitch. | `MarkdownBlockView.kt`, `BlockContent`. |
| M07 | Minor | Mosaic corner curve, both | iOS clips the outer collage with a continuous 14-unit curve. Android uses a circular 14 dp curve. | `TranscriptEntryView.kt:972`, `ToolImageMosaic`. |
| M05 | Minor | Copy glyph, both | Shared reply-copy pictogram difference. | `system/SystemImage.kt`, `SystemImage`. |
| G01 | Visible | Glass, both | Shared controls/composer material difference. | `system/Glass.kt`, `glassEffect`. |

Tile dimensions, 4-unit gaps, order, cropping and “+1” count match. Do not change the collage's 148 × 204 / 148 × 100 geometry or invent thicker separators.

### 15-running

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| B01 | Blocker | Inline link action, both | The visible first-paragraph file target lacks an Android link action. | `MarkdownBlockView.kt`, `annotated` / `ArrivingText`. |
| V06 | Visible | Link color, both | Shared old Android blue in the file target. | `system/SystemColors.kt`, palettes. |
| V01, V07 | Visible | Line height / quote styling, both | The reply and quote have the same differences as 07. | `MarkdownBlockView.kt`, `BlockContent` / `ListItem`. |
| M04, M05 | Minor | List alignment / icons, both | High number markers and different stop/disclosure glyph ink. | `MarkdownBlockView.kt`, `ListItem`; `system/SystemImage.kt`, `SystemImage`. |
| G01 | Visible | Glass, both | The background-work pill and composer have the shared material difference. | `system/Glass.kt`, `glassEffect`. |

Working, stop action and the background-work capsule are present. A shimmer's sampled phase is not a selected/running-state mismatch.

### 16-streaming

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V04 | Visible | Arrival motion, both | iOS fades new text chunks over 0.2 s without moving glyphs. Android renders them at full opacity: `ArrivalFade` changes `shown` but never applies it; `ArrivingText` ignores `arrivals`. This was also true at the capture commit. | `MarkdownBlockView.kt:399,414`, `ArrivalFade` / `ArrivingText`; Swift counterparts draw fading arrival layers. |
| V01, V06 | Visible | Line height / link color, both | Longer body pitch and different blue remain visible in the streaming window. | `MarkdownBlockView.kt`, `BlockContent`; `system/SystemColors.kt`, palettes. |
| G01 | Visible | Glass, both | Shared controls/composer material difference. | `system/Glass.kt`, `glassEffect`. |

Stream tick/content number and the amount of history shown are not defects. Restore fades without undoing justified text or changing stream duration to fake parity.

### 17-composer-text

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| B01 | Blocker | Inline link action, both | The blue `SessionView.swift` target remains visible above the composer and lacks an Android link action. | `MarkdownBlockView.kt`, `annotated` / `ArrivingText`. |
| V06 | Visible | Link color, both | Shared old Android blue in that file target. | `system/SystemColors.kt`, palettes. |
| V01 | Visible | Transcript line height, both | The visible assistant reply still has the longer Android pitch. | `MarkdownBlockView.kt`, `BlockContent`. |
| M05 | Minor | Composer glyphs, both | Plus, model chevron, mic and send arrow have different ink bounds/strokes. The model chevron is particularly small. | `system/SystemImage.kt`, `symbolSize` / `SystemImage`; `SessionView.kt:1444,1511`, `ComposerLeadingControls` / `TrailingButton`. |
| G01 | Visible | Glass, both | Expanded composer has the shared material difference. | `system/Glass.kt`, `glassEffect`; `SessionView.kt:1322`, `ComposerCapsule`. |

Typed text, selected model, send-enabled state and accessory order match. There is no evidenced 40 dp composer/keyboard gap: both keep a small margin above their native keyboards. Missing blue drawer dot is capture timing.

### 18-composer-attachments

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| B01 | Blocker | Inline link action, both | The blue file target above the composer lacks an Android link action. | `MarkdownBlockView.kt`, `annotated` / `ArrivingText`. |
| V06 | Visible | Link color, both | Shared old Android blue in that file target. | `system/SystemColors.kt`, palettes. |
| V08 | Visible | Attachment spacing, both | iOS starts the 76-unit thumbnail at the expanded capsule's top. Android adds 8 dp top padding to the attachment row, making the composition taller and lowering the thumbnail/remove control relative to that edge. | `SessionView.kt:1629`, `PendingAttachmentsRow`, `.padding(top = 8.dp)`. |
| M05 | Minor | Remove/accessory glyphs, both | Android builds a 22 dp black remove circle with an 11 dp X rather than the iOS body-sized palette symbol. Plus/model/mic/send have the shared ink-bound differences. | `SessionView.kt:1643`, `RemovableAttachment`; `system/SystemImage.kt`, `SystemImage`. |
| M07 | Minor | Thumbnail corner curve, both | Same 76-unit thumbnail and 22-unit radius, different continuous/circular curve. | `SessionView.kt:1705`, `AttachmentThumbnail`. |
| V01 | Visible | Transcript line height, both | Shared longer body pitch above the composer. | `MarkdownBlockView.kt`, `BlockContent`. |
| G01 | Visible | Glass, both | Shared expanded-composer material difference. | `system/Glass.kt`, `glassEffect`. |

Thumbnail softness/color is the nonidentical lab seed described above. Do not “fix” import resampling from that fixture. Keyboard and unread-dot differences are excluded.

### 19-composer-steer-menu

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V05 | Visible | Keyboard/focus state, both | iOS keeps the keyboard and typed composer in place while its send menu is open. Android dismisses the keyboard and relocates the composer/transcript before showing the menu. Native menu styling is allowed; this loss of the composing state is a separate UX difference. | `system/Chrome.kt:157`, `SystemMenu`, default focusable `DropdownMenu`; invoked by `SessionView.kt:1539`, `TrailingButton`. |
| V01, V07 | Visible | Transcript line height / quote styling, both | Shared reply rhythm and quote-bar differences. | `MarkdownBlockView.kt`, `BlockContent` / `ListItem`. |
| M04, M05 | Minor | Marker / app glyphs, both | Shared high number markers and small composer glyphs. Native popup glyph/style differences are excluded. | `MarkdownBlockView.kt`, `ListItem`; `system/SystemImage.kt`, `SystemImage`. |
| G01 | Visible | Glass, both | Shared composer material difference. | `system/Glass.kt`, `glassEffect`. |

Steer then Queue, typed “Steer this”, and one-shot delivery options are retained. The stills do not prove focus restoration after dismissing the popup; test that with V05.

### 20-permission

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V06 | Visible | Action color, both | Allow Once uses the older Android blue instead of the iOS reference's blue. | `system/SystemColors.kt`, palettes; `RequestReviewView.kt:440`, `Actions`. |
| G01 | Visible | Glass, both | Neutral Deny/Always Allow controls lose their distinct light glass outlines on Android and gain stronger dark highlights. | `system/Glass.kt`, `glassEffect`; `Glass.kt:56`, `OpenCodeButton`. |

Request title, resource/command text, three response choices, their order and neutral treatment of Deny match. Native sheet/toolbar layout and monospace typeface wrapping are allowed. No command-font blocker was found; both use the 15-unit code role.

### 21-question

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V14 | Visible | Answer-card color, dark | iOS answer cards/answer field use elevated `#2C2C2E` over `#1C1C1E`. Android uses base `#1C1C1E` over black. These are app-owned answer cards, not just the native sheet outline. | `RequestReviewView.kt:356,397`, `Choice` / `AnswerField`, fixed `secondarySystemBackground`; account for elevated dark presentation locally. |
| V06 | Visible | Action color, both | Send Answers uses the older blue. | `system/SystemColors.kt`, palettes. |
| M05, M07 | Minor | Choice glyph / corner curve, both | Smaller radio glyph ink and circular rather than continuous 10-unit card corners. | `system/SystemImage.kt`, `SystemImage`; `RequestReviewView.kt:363`, `Choice`. |
| G01 | Visible | Glass, both | Skip's light boundary is nearly absent; dark boundaries/highlights are stronger. | `system/Glass.kt`, `glassEffect`; `Glass.kt`, `OpenCodeButton`. |

Question, option order/descriptions, unselected state, custom-answer field, Skip and Send Answers match. There is no redundant single-choice instruction.

### 22-header-context

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V06 | Visible | Context/link color, both | Orange context rim and inline link blue differ in hue. | `system/SystemColors.kt`, palettes; `SessionContextRing.kt:31`, `tint`. |
| B01 | Blocker | Link action, both | The visible blue `SessionView.swift` link is not interactive on Android. | `MarkdownBlockView.kt`, `annotated` / `ArrivingText`. |
| V01, V07 | Visible | Line height / quote styling, both | Shared reply rhythm and quote-bar differences. | `MarkdownBlockView.kt`, `BlockContent` / `ListItem`. |
| M04, M09 | Minor | Marker alignment / band fade, both | High number markers and the extra right-edge band background gradient. | `MarkdownBlockView.kt`, `ListItem` / `MarkdownBand`. |
| G01 | Visible | Glass, both | Shared header/composer material difference. | `system/Glass.kt`, `glassEffect`. |

The 85% gauge is present and orange on both. The header implementations both use a 2-unit rim; no independent thickness/off-center ring defect is established.

### 23-session-menu

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V01 | Visible | Underlying transcript line height, both | The longer Android pitch remains behind the menu. | `MarkdownBlockView.kt`, `BlockContent` / `ListItem`. |
| G01 | Visible | Underlying glass, both | Shared header/composer material difference. | `system/Glass.kt`, `glassEffect`. |

Pin, Rename, Copy Session ID, Mark as Unread, Compact, Fork, Export, Session Info and Delete Session retain their order and destructive treatment. Android popup position, title style, icons and separators are native chrome, not blockers. Do not replace the native menu with an iOS imitation.

### 24-session-sheet

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V13 | Visible | Model-row contrast/state, both | iOS renders the actionable model value primary, black in the light capture. Android renders it secondary gray, visually grouping it with passive Context/Cost values. The sampled light model ink is black on iOS versus `#7F7F85` on Android. | `SessionSheetView.kt:268,278`, `ModelRow`, `color = secondaryLabel`; Swift explicitly applies `.foregroundStyle(.primary)` to this button. |
| V06 | Visible | Action/context color, both | Folder/actions blue and orange gauge differ. | `system/SystemColors.kt`, palettes. |
| M05 | Minor | App action glyphs, both | Folder, Compact, Fork and Export use different/smaller pictograms. Compact becomes tiny opposed chevrons instead of arrows into a band. | `system/SymbolNames.kt`, `drawable`; `SessionSheetView.kt:343`, `ActionRow`. |
| V01 | Visible | Underlying transcript line height, both | Longer Android reply pitch behind the sheet. | `MarkdownBlockView.kt`, `BlockContent`. |

Session title, project/directory, model, 85% of 400K, $1.42, Compact/Fork/Export and subsequent sections match. The native sheet/list presentation is allowed. Directory text is not an italic-style omission; the Swift location row also uses ordinary footnote text.

### 25-model-picker

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V06 | Visible | Selection color, both | Selected model/checkmark blue differs. | `system/SystemColors.kt`, palettes; `SessionView.kt:1996`, `ModelRow`. |
| M05 | Minor | Favorite glyph, both | Android's star has different contour/ink bounds. Both use subheadline size, so this is glyph calibration, not a wrong text size. | `system/SystemImage.kt`, `SystemImage`; `SessionView.kt:2021`, `ModelRow`. |
| G01 | Visible | Underlying/control glass, both | Shared material mismatch remains behind the picker and in app-skinned controls. | `system/Glass.kt`, `glassEffect`. |

Model/provider, selected checkmark, unfilled favorite state, search and Cancel match. Native search/sheet/list chrome is excluded. The missing underlying unread dot is timing.

### 26-attachment-menu

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| G02 | Visible | Glass/backdrop, both | iOS separates the custom menu from the composer with blurred material. Android lets the composer/placeholder and panel rectangles remain conspicuous beneath/through its rows, especially in dark mode. | `system/Glass.kt:45,60`, `GlassBackdrop` / `glassEffect`; host `SessionView.kt`, `AttachmentMenuOverlayHost`; `AttachmentMenu.kt:309`, `MenuPanel`. |
| M05 | Minor | Custom menu glyphs, both | Camera/Photos/Files/Paste marks have smaller ink bounds than their iOS counterparts. This is the app-specific attachment overlay, not the excluded native popup menu. | `AttachmentMenu.kt:357,373`, `AttachmentMenuRow`, fixed `size(18.dp)`; `system/SystemImage.kt`, `SystemImage`. |
| G01 | Visible | Composer material, both | Shared composer material mismatch. | `system/Glass.kt`, `glassEffect`. |

Camera, Photos, Files and Paste remain in the same order. Both row implementations use 52-unit rows, 36-unit icon circles and 12-unit icon-to-label gaps. Do not widen/narrow these from the earlier unmeasured impression. Both panels request radius 28.

### 27-background-work

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V03 | Visible | Inline image size, both | iOS scales the Markdown screenshot to the prose width. Android constrains the box to that width but fits the bitmap into its intrinsic-height box, leaving a much smaller centered image with large side fill. | `TranscriptEntryView.kt:1013,1037`, `MarkdownImage`, `fillMaxWidth` plus `heightIn` and `ContentScale.Fit` without aspect-ratio sizing. |
| G02 | Visible | Glass/backdrop, both | iOS blurs the transcript behind the work rows. On Android “Drawer Responsive”, “Context compacted” and “Used 1 Subagent” read through and collide with the panel's own titles. | `system/Glass.kt`, `GlassBackdrop` / `glassEffect`; `BackgroundWorkPanel.kt:106,186`, `BackgroundWork` / `BackgroundWorkPanel`. |
| M05 | Minor | Navigation glyph, both | Panel chevrons and terminal glyph have smaller/different ink bounds. | `system/SystemImage.kt`, `SystemImage`; `BackgroundWorkPanel.kt:305`, `BackgroundWorkRow`. |
| V01 | Visible | Transcript line height, both | Longer body pitch above/behind the panel. | `MarkdownBlockView.kt`, `BlockContent`. |

Same subagent/shell count, names, command and order. Both sources use 48-unit rows and a 20-unit-inset separator. The main failure is backdrop legibility, not a different row count or a proved oversized row.

### 28-shell-output

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V10 | Visible | Command-title typography, both | iOS's command title is 15 pt semibold monospaced with middle truncation. Android sends it through the generic 17 sp proportional navigation headline with end truncation. | `ShellOutputView.kt:136`, `ShellOutputView`; `system/Navigation.kt:138`, `NavigationBar` / `NavigationTitle`. |
| V11 | Visible | Output line height, both | Both output fonts are 13 units, but Android's default TextView leading spaces log lines more widely. Recurring log-line starts show about 16.5 dp pitch versus about 14.8 pt on iOS. | `ShellOutputView.kt:359,372`, `ShellOutputTextView`, TextView factory; explicit leading/font-padding calibration is absent. |
| V12 | Visible | Stop action size, both | iOS's filled capsule occupies about 44 units vertically. Android paints only the text-plus-12-dp padding, about 34 dp, inside a larger hit area. The visible action is smaller. | `ShellOutputView.kt:251,272`, `StatusBar`, inner Stop Shell `Text` background/padding. |
| G01 | Visible | Glass, both | App-skinned toolbar controls have the shared material mismatch. | `system/Glass.kt`, `glassEffect`. |

Output content, running status, Copy/Share/Close and Stop Shell remain available. Different elapsed times are capture timing. Toolbar placement/chrome itself is native; V10 is the command's deliberately specified content style.

### 29-catch-up

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V02 | Visible | User-bubble layout, both | iOS wrapped short messages shrink to their text's width. Android's two short bubbles take the entire 78%-width allowance, producing much wider empty fill. Android also forces a 24 sp user line height instead of the iOS body's approximately 22.3-unit pitch with its 2-unit line spacing. | `TranscriptUserMessage.kt:258,318`, `TrailingFractionLayout` / `Bubble`; `Text` measures to the wrap constraint and uses `lineHeight = 24.sp`. |
| V09 | Visible | Catch Up transcript inset, both | The first Android bubble sits about 24 dp farther below the card's header after comparing card-local coordinates. The card consumes status-bar insets, but the transcript still adds the raw 24 dp status-bar height. | `SessionView.kt:2162`, `SessionTranscriptView`, `statusBars.getTop`; `SessionDrawerContainer.kt:183,225` consumes those insets for Catch Up. |
| G03 | Visible | Glass shape, both | iOS's green Mark as Read has the same capsule silhouette as Keep Unread. Android gives the green action explicit 14 dp rounded corners. | `HomeView.kt:1136,1140`, `CatchUpChrome`; shared `openCodePrimaryButton` already uses `GlassSurface.Capsule`, so fix this override. |
| V01, V06 | Visible | Reply pitch / link color, both | Shared longer body pitch and different inline link blue. | `MarkdownBlockView.kt`, `BlockContent`; `system/SystemColors.kt`, palettes. |
| B01 | Blocker | Inline link action, both | `SessionView.swift` is another blue but inert inline file target on Android. | `MarkdownBlockView.kt`, `annotated` / `ArrivingText`. |
| M05, M07 | Minor | App glyphs / bubble corner curve, both | Smaller title chevron/checkmark, different copy mark and circular rather than continuous bubble curves. | `system/SystemImage.kt`, `SystemImage`; `TranscriptUserMessage.kt`, `Bubble`. |
| G01 | Visible | Glass, both | Shared check control, request banner and footer material mismatch. | `system/Glass.kt`, `glassEffect`. |

9 Left, disabled Undo, title-as-open-action, pending-request count/Review, Keep Unread and Mark as Read match. The iOS banner's unpainted leading symbol is a reference discrepancy noted above, not a missing Android requirement.

### 30-whats-new

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| M05 | Minor | Illustration glyphs, both | Catch Up's rectangle stack becomes layered diamonds. The voice illustration's mic/waveform contours and ink bounds differ. | `system/SystemImage.kt`, `SystemImage`; `WhatsNew/WhatsNewView.kt:195`, `WhatsNewIllustration`. |
| G01 | Visible | Glass controls, both | App-skinned Done and underlying drawer control have the shared material difference. | `system/Glass.kt`, `glassEffect`. |

October 6, Catch Up Shortcuts, GPT-Live Voice, summaries and examples match. Calls on the Lock Screen starts below the Android sheet's fold, not absent from the list. Done is not a plain-text-only Android button: `ToolbarSlot` wraps it in a capsule. Native sheet/list presentation is excluded.

### 31-settings

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| B02 | Blocker | Missing navigation / affordance, both | iOS **Send Reports To** has a disclosure chevron and opens `ReportProjectPicker`. Android shows “None” with no chevron and no click/navigation handler. The settings route to configure the report destination is missing. The two report rows are also flattened into one grouped row, losing their separator. | `ProblemReports/ProblemReportViews.kt:630,645`, `ProblemReportSettingsRows`; `SettingsView.kt:178` embeds the whole column as one row. |
| M05 | Minor | Settings glyphs, both | Change Server icon and navigation chevrons have smaller/different ink bounds. | `system/SystemImage.kt`, `SystemImage`; `SettingsView.kt:389,405`, `SettingsLabel` / `NavigationLinkRow`. |
| V01 | Visible | Underlying reply line height, both | Shared longer Android reply pitch behind Settings. | `MarkdownBlockView.kt`, `BlockContent`. |
| G01 | Visible | Glass controls, both | Shared Done/underlying-control material mismatch. | `system/Glass.kt`, `glassEffect`. |

Connected status, Change Server, Advanced, Diagnostics, Report Problem and footer copy match. `10.0.2.2` is the correct emulator host. Footer/next section position differs with the native detent and row layout; do not delete offscreen rows.

### 32-diagnostics

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| M08 | Minor | Switch component, both | Android draws a hard-coded old iOS-style 51 × 31 switch, rather than the current wider/flatter iOS control or the native Android Switch used elsewhere in this app. This is a custom mimic, not evidence that Material's native switch needs to look like iOS. | `DiagnosticsView.kt:189,212`, `DiagnosticsToggle` / `IosSwitch`; use the native switch policy and preserve checked-state semantics. |
| V01 | Visible | Underlying reply line height, both | Shared longer reply pitch behind Diagnostics. | `MarkdownBlockView.kt`, `BlockContent`. |
| G01 | Visible | Glass controls, both | Shared app-skinned back/share/clear material mismatch. | `system/Glass.kt`, `glassEffect`; `SettingsView.kt:463`, `BackButton`. |

Record diagnostics and Include Transcripts are on in both; summary labels, Pending and No event recorded match. Counts are run-specific. The summary's lower tail is below Android's fold, not removed. Native toolbar arrangement and sheet background are not defects.

### 33-error-banner

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| V06 | Visible | Feedback/action color, both | Warning orange and Retry blue differ from the reference. | `system/SystemColors.kt`, palettes; `SessionView.kt:468`, `ComposerBanner`. |
| V01 | Visible | Assistant line height, both | Longer Android body pitch above the banner. | `MarkdownBlockView.kt`, `BlockContent`. |
| M05 | Minor | App glyphs, both | Copy, warning and mic marks have different ink bounds/strokes. | `system/SystemImage.kt`, `SystemImage`; `SessionView.kt`, `ComposerBanner` / `ComposerControlLabel`. |
| G01 | Visible | Glass, both | Light banner/composer boundaries are nearly absent; dark ones have much stronger rims/highlights. | `system/Glass.kt`, `glassEffect`. |

Error title, HTTP 503 detail, Retry and the still-available composer match. Both request 28-unit corners for the banner; the difference is mainly material rendering, not an independently wrong banner radius.

### 35-composer-empty

| ID | Severity | Category / appearance | iOS versus Android | Likely Kotlin owner |
| --- | --- | --- | --- | --- |
| B01 | Blocker | Link action, both | The blue inline file target lacks an Android link action. | `MarkdownBlockView.kt`, `annotated` / `ArrivingText`. |
| V01, V06 | Visible | Body pitch / link color, both | Measured 24.3 versus 26-unit assistant pitch and the old Android blue. | `MarkdownBlockView.kt`, `BlockContent`; `system/SystemColors.kt`, palettes. |
| M05 | Minor | Composer/copy glyphs, both | Plus, mic and copy have different ink geometry. | `system/SystemImage.kt`, `SystemImage`; `SessionView.kt`, `ComposerControlLabel`. |
| G01 | Visible | Glass, both | Shared collapsed-composer/header material mismatch. | `system/Glass.kt`, `glassEffect`. |

Empty placeholder, plus and mic are in the same order and have the same intended controls. The blue drawer dot is the documented read-receipt mismatch. Normal header/composer absolute positions differ with native safe areas.

## Deduplicated fix list by file

Ordered by user impact. Give each file group to one worker. Shared IDs in different groups describe a shared visual symptom with separate local call sites; they do not authorize two workers to edit the same file. Keep the glass groups separate from text/layout work.

### 1. `MarkdownBlockView.kt`

- **B01:** Preserve actual link annotations, route local/path links through `onOpenFile`, and use Android URL handling for web links. Verify links remain tappable through the existing justified-text path. Do not merely recolor them. Check 10, 22, 29 and 35.
- **V01:** Match the measured body line pitch in `BlockContent` and `ListItem`, while retaining 17 sp Roboto and justified paragraphs/lists/quotes. Do not change table cells or all semantic text styles globally.
- **V04:** Implement arrival opacity in `ArrivalFade` and `ArrivingText`. Keep stable layout, per-chunk clocks, no repeated fade on reused/history rows, and the iOS Reduce Motion policy. Check a recorded stream, not a different tick number.
- **V07:** Draw the quote bar across the padded height and multiply the secondary alpha by 0.5. Retain the documented behind-text implementation required by justification.
- **M04:** Align smaller list markers to the first body baseline; calibrate task-box ink bounds without changing checked state.
- **M09:** Remove the extra page-background gradient from `MarkdownBand`. Keep the existing content mask fade and the band's fill/rules.
- Recheck 07 through 12 and 15/16, with the same rendered scroll anchor.

### 2. `ProblemReports/ProblemReportViews.kt`

- **B02:** Make Send Reports To a real navigation action to the existing `ReportProjectPicker`, with a chevron and accessible action/state. Restore the boundary between the report rows within the owned component. Avoid requiring a simultaneous `SettingsView.kt` edit if local row construction can handle it.
- Verify selecting a destination changes None to its project, then verify Report Problem still opens and sends to that destination. State 31 is the starting evidence.

### 3. `TranscriptEntryView.kt`

- **V03:** Size loaded `MarkdownImage` from its aspect ratio under the available width and 360-unit height cap. Do not leave a full-width box containing an intrinsic-size thumbnail. Check 27 and the underlying image in 28.
- **M06:** Keep the subagent chevron beside the first title line, not vertically centered in the whole card. Check 13.
- **M07:** If polishing corner parity, preserve existing radius 14 and adjust only the continuous curve of subagent cards/mosaics. Do not change collage tile sizes or gaps.

### 4. `TranscriptUserMessage.kt`

- **V02:** Make wrapped bubbles fit their rendered text rather than always filling the maximum width. Keep 0.78 as a cap, right alignment and radius 18. Correct user-text line pitch without changing typeface or font size. Check the two short bubbles in 29 plus long prompts in 10/35.
- **M07:** Preserve radius 18 while matching the continuous corner curve if that polish is taken on.

### 5. `system/Chrome.kt`

- **V05:** Preserve the composer/IME state across the native send dropdown. Check popup focus properties and dismissal focus restoration. Keep Android's native menu, Steer then Queue, tap-to-steer, and one-shot choices. Verify 19 with keyboard open, menu dismissal, and each send action.

### 6. `SessionView.kt`

- **V09:** Use the remaining, unconsumed top inset for Catch Up's transcript. The card already consumes window insets; avoid adding the raw status-bar height again. Do not change normal-session safe areas. Check 29 card-local header/bubble positions.
- **V08:** Remove the unmatched attachment-row top padding while preserving the remove control's 44 dp target and unclipped glyph. Check 18 with keyboard shown and hidden.
- **M05/M07, local only:** Calibrate the custom remove-circle/accessory use and thumbnail curve after the shared glyph worker provides sizing. Do not edit `system/SystemImage.kt` from this group.

### 7. `ShellOutputView.kt` and `system/Navigation.kt`

- **V10:** Let the shell supply its command-title style and middle truncation to the navigation bar. Keep ordinary sheet titles unchanged. These two files belong to the same worker if the title API needs an extension.
- **V11:** Calibrate the output TextView's 13 sp line height/font padding to the reference's log density, keeping native text selection and tailing behavior.
- **V12:** Make Stop Shell's painted capsule occupy the reference-sized action, not just a larger invisible target. Preserve confirmation and status-bar layout.
- Check 28, then long titles and scrolled output; do not judge elapsed time equality.

### 8. `system/SystemColors.kt`

- **V06:** Update app accent/link/status color roles against the iOS 27 reference, using the measured sRGB colors as evidence. Recheck non-glass blue ink/dots first; do not compensate for glass compositing by distorting base colors.
- Check 03/04, 21/22/24/25, 29 and 33/35 in both themes. Preserve semantic orange at 85% and all attention/unread distinctions.

### 9. `SessionSheetView.kt`

- **V13:** Render the actionable model value primary in `ModelRow`, leaving passive Context/Cost values secondary. Keep the model picker route and the existing 85%/400K/$1.42 values. Check 24.
- **M05, local only:** Keep action glyph slots/spacing consistent after shared glyph calibration. Do not rewrite the native Session menu.

### 10. `RequestReviewView.kt`

- **V14:** Use elevated dark answer-card/field colors in `Choice` and `AnswerField`; do not change base transcript bubble colors globally. Light/dark, selected/unselected and custom-answer states need verification.
- **M07:** Optional continuous 10-unit card curve polish. Preserve full-width choices, option order, no redundant instruction, and native sheet chrome.

### 11. `system/SystemImage.kt`, `system/SymbolNames.kt`, app glyph drawables

- **M05:** Calibrate actual glyph ink bounds and weight/meaning for app-owned controls. One universal `SymbolBoxScale = 1.2` does not correct every vector's internal bounds; e.g. the chevron occupies only part of its 960-unit viewport. Preserve Android assets rather than importing the SF typeface.
- Prioritize tiny disclosure/play marks, the rectangle-stack substitute, filled cog and composer/attachment marks. Compare 04/06/10/13/18/24/26/30/35.
- Native menu/status-bar/keyboard symbols are excluded. Leaf workers own local fixed-size call sites; this worker owns only the shared renderer/mapping/assets.

### 12. `AppView.kt`

- **M01:** Restore step first-baseline alignment.
- **M02:** Apply the password row's trailing 14 dp inset without double-padding the text fields or shrinking the eye's touch target.
- **M03:** Use physical-pixel setup separators rather than 1 dp dividers.
- **G03, glass shape only:** Keep disabled Connect on the primary action's capsule shape. Coordinate with the glass worker, but keep this file owned by this group.
- Check 01/02/03 in both themes.

### 13. `FileAttachmentViews.kt`

- **M05, local only:** Separate the 28-unit leading slot from the glyph's intended title3 ink size, and calibrate trailing play/disclosure use against the shared renderer. Keep filenames/types and click actions.
- **M07:** Optional continuous 14-unit file-card curve. Preserve horizontal scrolling and card widths. Check 10.

### 14. `AttachmentMenu.kt`

- **M05, local only:** Calibrate the fixed 18 dp menu glyph boxes against their actual ink. Keep the verified 52-unit rows, 36-unit circles, 12-unit label gaps and content order.
- Do not independently tune `system/Glass.kt`; let the glass group handle **G02** and report any remaining hosting/occlusion issue after recapture. Check 26.

### 15. `DiagnosticsView.kt`

- **M08:** Replace the hard-coded `IosSwitch` drawing with the app's native Android switch policy, with real checked-state semantics. Preserve both enabled values and labels. Check 32; do not redesign it to copy the iOS switch's pixels.

### 16. `TranscriptListRows.kt`

- **M07:** Optional continuous 14-unit draft setup-card curve. Keep the existing card dimensions, labels, content edge and click handlers. Check 06.
- **M05, local only:** Recheck project/model disclosure ink after shared glyph calibration; this file does not own the shared renderer.

### 17. `system/Glass.kt`, glass pass

- **G01:** Tune veil/rim/highlight so light controls remain distinct on white and dark controls do not have conspicuous thick white rims/top shine. The shader currently adds a 1 dp border and paints its highlight after `drawContent`.
- **G02:** Verify the actual backdrop sampling/blur of custom attachment and background-work overlays. Text must not remain readable through their own row text. Hosts are `SessionView.kt`, `AttachmentMenu.kt` and `BackgroundWorkPanel.kt`; ask their owning workers for a host change rather than editing those files concurrently.
- Use current captures to locate problems, then recapture. iOS simulator glass also under-renders scroll-edge blur; do not add opaque transcript backdrops to compensate for a simulator artifact. Flat native Android sheet chrome is allowed.

### 18. `HomeView.kt`, glass shape pass

- **G03:** Use the existing capsule-shaped primary style for Catch Up's tinted Mark as Read, instead of the local 14 dp shape override. The shared `Glass.kt` primary/secondary helpers are already capsules and need no radius rewrite.
- Check 29 against its equally sized Keep Unread peer. Leave row order, counts and drawer behavior alone.

### 19. `OpenCodeApp.kt` and capture driver, reference maintenance only

- Match the pasted lab image's pixel resolution/color before recapturing 18. This is not a production-import defect.
- Pin transcript captures to the same rendered anchors in `scripts/android-reference/capture.py`, and align known badge/read-receipt timing. Refresh screenshots after justified-text and glass changes; do not undo those changes to reproduce the older captures.

No app source was changed for this review. Only this report was written.
