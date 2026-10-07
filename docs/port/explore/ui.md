# iOS UI layer, for a Compose port

Source is the read-only checkout at `.upstream/opencode-ios`, commit `4fb5e0e`. Deployment target is iOS 18.0 (`project.yml` line 8). Liquid Glass is used only when `iOS 26` is available. Older systems fall back to materials and bordered buttons in `OpenCode/Glass.swift`.

There is no custom color asset catalog and no theme file. Almost every color is an iOS semantic style (`Color.primary`, `Color(.systemBackground)`, `.secondary`, `.tint`, `.separator`). A port that invents a branded palette will not match. Match iOS light and dark semantic colors, then layer the few hardcoded RGB values below.

The product spec for copy, glass, radii, and motion is `AGENTS.md` lines 16-69 and `docs/native-ui-guidance.md`. Guidance is dated research, not an automatic upgrade. Durable rules live in `AGENTS.md`.

Suggested file mapping, one Swift file to one Kotlin file unless noted:

| iOS | Compose analog |
| --- | --- |
| `OpenCodeApp.swift`, `AppView.swift` | `OpenCodeApp`, `AppScreen` |
| `HomeView.swift` | `HomeScreen` plus `DrawerScreen` |
| `SessionDrawerContainer.swift` | custom full-screen layout, not a `ModalDrawer` |
| `CatchUpStack.swift` | `CatchUpStage` over the same card |
| `SessionView.swift` | `SessionScreen` |
| `Transcript/TranscriptListViewController.swift` | custom list with a frame clock, not a plain `LazyColumn` scroll |
| `Transcript/TranscriptScrollBehavior.swift` | pure `TranscriptScrollBehavior` |
| `Transcript/TranscriptEntryView.swift` | `TranscriptEntry` |
| `Transcript/TranscriptUserMessage.swift` | `TranscriptUserMessage` |
| `Transcript/MarkdownBlockView.swift` plus parser in `OpenCodeClient.swift` | `MarkdownBlock` |
| `ComposerTextInput.swift` | `AndroidView` `EditText` or a custom `BasicTextField` that keeps identity |
| `Glass.swift`, `VariableBlurView.swift`, `HeaderBlurParameters.swift` | theme + header scrim |
| `SessionSheetView.swift`, `SettingsView.swift`, `RequestReviewView.swift`, `WhatsNewView.swift` | sheets |
| `BackgroundWorkPanel.swift`, `ShellOutputView.swift` | overlay + sheet |
| `PairingScanner.swift` | CameraX / ML Kit screen |
| `HapticsClient.swift` | `Haptics` |
| `SyntaxHighlighter.swift` | `SyntaxHighlighter` |
| `FileAttachmentViews.swift`, `SessionContextRing.swift` | same names |

## Components found

### App shell

`OpenCodeApp` (`OpenCode/OpenCodeApp.swift` lines 8-54) is a SwiftUI `App`. The window is `AppView(store:)`. Debug lab launches can force light, dark, or Reduce Motion. There is no custom accent color and no preferred color scheme in release.

`AppView` (`OpenCode/AppView.swift` lines 10-37) shows `HomeView` when `store.scope(\.home)` exists. Otherwise it shows `connectionView`. A share-inbox error is an alert titled "Couldn't Add Shared Items".

Connection screen hierarchy, top to bottom, inside a `NavigationStack`:

1. `ScrollView` of a leading `VStack`, spacing 28, padding 24, max width infinity, on `Color(.systemBackground)`.
2. Wordmark `Image("OpenCodeWordmark")` at 200 by 36, leading aligned, accessibility label "OpenCode" (lines 44-48). Tagline is `.body` secondary. Top padding 24 only when there are no saved servers.
3. Optional `connectionErrorCard`.
4. Either `setupSteps` (empty server list) or `savedServerList`.
5. Bottom `safeAreaInset` is `pairingActions`. Top `safeAreaInset` is a zero-height clear view whose background is `systemBackground` ignoring the top safe area, only while the server list is empty, so content does not run under the clock when the navigation bar is hidden (lines 74-81).
6. Navigation title "Servers" and a visible bar only when servers exist. A "Back" toolbar item appears when `returnServer` is set and nothing is connecting.
7. Modal sheet for the credential form. Modal sheet for `PairingScannerView`.

Setup steps (`setupSteps`, lines 118-166) are a vertical stack with hairline `Divider`s inset by `stepBadgeSize + 14`. Each step is an `HStack` aligned to the first baseline, spacing 14, vertical padding 16. The number badge is Berkeley Mono 15 semibold, `systemBackground` on `Color.primary`, corner radius 6, size `stepBadgeSize` (default 30, scaled relative to `.callout`, line 18). Titles are `.headline`. The download link is Berkeley Mono 14 relative to `.footnote`, underlined, secondary. The copy chip is Berkeley Mono 15 relative to `.callout`, horizontal padding 10, vertical 6, `tertiarySystemFill`, corner radius 8, min height 44.

Pairing actions (lines 262-301) are a `VStack` spacing 12, horizontal padding 24, top 12, bottom 16, `systemBackground`. "Enter a Server Manually" is `.subheadline` secondary, min height 44, plain. "Scan Pairing Code" is `.headline`, min height 52, `systemBackground` text on `Color.primary`, corner radius 16. While connecting, a `ProgressView` tinted `systemBackground` replaces the QR icon and the label becomes the progress string. The button is disabled during progress.

Saved server rows (lines 303-380) are `HStack` spacing 14, padding 16, glass in a 20 pt rounded rect, interactive. Host is `.headline`, middle truncation. Subtitle is `.footnote` secondary. Edit and delete are 44 by 44 plain icon buttons. Remove confirms with a dialog. Destructive role is only "Remove Server".

Credential form (lines 383-476) is a `VStack` spacing 16, padding 24. Field labels are `.caption` semibold secondary, spacing 7 above the control. Controls have horizontal padding 14, min height 50, `tertiarySystemFill`, corner radius 14. Connect uses `openCodePrimaryButton()` tinted `Color(.label)`. Disabled text is `.secondary`. Password reveal is a 44 by 44 eye button. Password fields set `textContentType` to `.oneTimeCode` so iOS does not offer to save the server password.

Error card (lines 216-260) is glass, corner radius 20, padding 20, spacing 14. Title is `.headline` orange with `exclamationmark.triangle.fill`. Body is `.subheadline` and selectable. Context is `.footnote` secondary. Copy uses `openCodeSecondaryButton()`.

### Home and drawer

`HomeView` (`OpenCode/HomeView.swift` lines 10-50) is a `GeometryReader`. Drawer width is `geometry.size.width * 0.75` (line 24). If a session is scoped, the whole screen is `SessionDrawerContainer`, which `.ignoresSafeArea()`. The hosted drawer and card own the full screen. Their own views apply the safe-area insets passed in. Settings is a `.sheet` of `SessionSettingsView`. What's New is `WhatsNewSheet`. Delete is a confirmation dialog. There is no navigation bar on the session screen.

Drawer content (`sessionDrawer`, lines 212-365), a plain `List`, hidden scroll content background, hidden indicators, `defaultMinListRowHeight` 44, width equal to the drawer:

1. Title row "Sessions", `.title2.bold`, header trait. Optional Offline capsule when `connectionStatus == .disconnected`. Insets top 0, leading 16, bottom 4, trailing 16.
2. Search `TextField`, plain style, min height 44, horizontal padding 12, fill `Color.primary.opacity(0.12)`, corner radius 12, label "Search sessions". Row insets use `DrawerMetrics.rowEdge` (16) with top and bottom 4.
3. Shortcut rows only when search is empty. Catch Up, then What's New. Catch Up insets top 4. What's New insets top 4 if it is first, else 0.
4. Optional deletion error, `.footnote` red, min height 44.
5. Section "Pinned", then section "Recents". Empty state is `ContentUnavailableView` with top padding 24.
6. Scroll content margins: top `safeArea.top + 12`, bottom `50 + 24 + safeArea.bottom` (lines 309-313). The 50 is the floating control height. The extra 24 is breathing room. The list ignores the container safe area, so this margin is explicit.
7. Bottom overlay, not a `safeAreaInset`. `HStack` spacing 12, horizontal padding 16, bottom `insets.bottom + 12`. "New Session" glass capsule, `.body.semibold`, horizontal padding 18, min height 50, interactive. Disabled (tertiary label, glass stays opaque) when disconnected. Settings is a 50 by 50 filled-gear glass circle. When search is focused, the overlay fades out over 0.15 s ease-out, loses hit testing, and is accessibility-hidden. The list itself still avoids the keyboard.
8. Background is `Color(.systemBackground)`. Light mode is white. Dark mode is black. Not a grouped gray.

Session rows (lines 367-413): `HStack` spacing 10, title `.body` semibold if selected else regular, one line. Leading and trailing padding 12. Min height 44. Selected fill `Color.primary.opacity(0.14)`, corner radius 12. List row insets are `DrawerMetrics.rowEdge` on both sides, clear background, hidden separator. Recents get a SwiftUI context menu (Pin, Mark as Read or Unread, Delete) and a trailing delete swipe that does not allow a full swipe. Leading swipe is Pin or Unpin, full swipe allowed, tint `.orange`. Pins do not use that menu. They use `PinnedRowInteraction` (lines 656-838), a `UIView` with `UIContextMenuInteraction`, `UIDragInteraction`, `UIDropInteraction`, and a tap recognizer, because SwiftUI's menu and reorder recognizers fight. The lifted preview is a bitmap of the cell, corner radius 12, background `systemBackground` in light and `secondarySystemBackground` in dark. VoiceOver actions on pins include Move Up and Move Down. Those items are not in the visible menu. Pin reorder animation is a spring, duration 0.3, bounce 0. Open and close of the drawer use the same spring (lines 33, 456, 489).

Indicators (`sessionIndicator`, lines 503-518) sit in an 18 by 18 frame. Running is `ActivityRing` (default 14 pt, line width 1.8). Attention is orange `circle.fill` at `.caption2`. Unread is tint (system blue) at the same size. None is the same circle at opacity 0 so the column does not jump. Swap animation is snappy, duration 0.25, unless Reduce Motion.

`ActivityRing` (lines 881-914) is an open arc, trim 0 to 0.72, round cap, period 0.8 s, driven by `TimelineView(.animation)`, not `repeatForever`. Angular gradient stops are tail at 0, secondary at 0.72 and 0.78, tail at 0.9. Under Reduce Transparency or Increase Contrast the tail is full secondary instead of clear, so the arc does not rely on a fade. Reduce Motion freezes rotation at 0. Accessibility label "Running".

`AgentActivityGlyph` (lines 843-875) is a 5 by 5 dot matrix, default size 15. Pitch is `width / 5`. Dot diameter is `pitch * 0.62`. Period is 1.1 s. Opacity is `0.16 + 0.84 * wave^3` where `wave = 0.5 + 0.5 * cos((row + column) / 8 - time) * 2π`. Reduce Motion is one still center dot at `size * 0.3`. Label "Running".

`DrawerShortcutLabel` (lines 614-636): `HStack` spacing 10, symbol width 20 scaled relative to body, leading aligned, tinted, hidden from accessibility. Title one line. Spacer min 4. Accessory min width 18. Horizontal padding 12. Min height 44. Content shape corner radius 12.

Catch Up chrome (`CatchUpChrome`, lines 532-604) is a full-screen `VStack` behind the card. Header height 52. Centered headline, monospaced digits, numeric text transition. Leading chevron 44 by 44, label "Leave Catch Up". Trailing "Undo", body medium, min 44, disabled when nothing was handled. Footer height 74, horizontal padding 16, two equal buttons, "Keep Unread" secondary and "Mark as Read" primary tinted `CatchUpMetrics.readControlColor`. Footer opacity 0 when remaining is 0. Empty state is a 48 pt light checkmark, title3 semibold "You're all caught up", primary Done. The chrome ignores the keyboard. The card owns keyboard avoidance.

### Drawer container and catch-up stack

`SessionDrawerContainer` is a `UIViewControllerRepresentable` (`SessionDrawerContainer.swift` lines 12-86). SwiftUI is not in the drag loop. UIKit transforms the already-rendered card and writes `isPresented` only when the gesture settles.

Z order in `viewDidLoad` (lines 145-243), back to front:

1. Drawer host, leading, top, bottom, width constraint. Hidden and non-interactive until the card has moved.
2. Catch-up chrome host, edge-pinned, background `secondarySystemBackground`, hidden until catch-up, alpha animated.
3. Preview card (`stack.previewCard`), inset constraints, hosting the next session. `safeAreaRegions = .keyboard` only, so rotation does not grow composer padding. User interaction off. Accessibility elements hidden.
4. Front card, `clipsToBounds`, continuous corner curve, `systemBackground`. Full-screen constraints, swapped for inset constraints during catch-up. Hosts `SessionView`. `safeAreaRegions` is `.all` normally and `.keyboard` during catch-up (line 471).
5. Dimming view covers the card. Black. Accessibility label "Close session list", button trait. Tap closes. Tap requires the pan to fail.
6. Catch-up badges are inserted above the session content.

One `DirectionalPanGestureRecognizer` is on the container, one touch, `delaysTouchesBegan = false`, `cancelsTouchesInView = false` until a horizontal pan wins. It must fail before descendant scroll pans (`shouldBeRequiredToFailBy`, lines 551-558). It does not run simultaneously with other pans (lines 560-568). Discrete taps and long presses stay native.

Drawer motion (`apply`, lines 397-410). Offset is clamped to `0...drawerWidth`. Card translates on x. Corner radius is `44 * progress`. Dim alpha is `progress * 0.48` under Increase Contrast, else `progress * 0.35`. At progress 0 the drawer is accessibility-hidden. At progress 1 the container is an accessibility modal. Settle animation is `UIViewPropertyAnimator(duration: 0.3, dampingRatio: 1)` (line 375). Reduce Motion sets the offset with no animation. Release velocity above 250 pt/s settles in the velocity direction. Otherwise the midpoint of the drawer width decides (line 355). A programmatic open from the button uses the same 0.3 s critically damped animator when the SwiftUI transaction has an animation.

Catch-up morph (`setCatchingUp`, lines 459-514) swaps full-screen constraints for inset constraints, animates 0.45 s at damping ratio 1, and fades a snapshot of the outgoing screen over 0.2 s ease-out. Inset card corner radius is `CatchUpMetrics.cornerRadius` (32), continuous. Border width is one pixel (`1 / displayScale`) in `UIColor.separator` while catching up, else 0 (lines 526-531). Entering catch-up forces the drawer closed.

`CatchUpStackController` owns the card transform during catch-up. The live session is the only interactive card. The card behind is a real `SessionView` from an inert store seeded with the cached transcript (`CatchUpPreviewStores`, lines 47-79). Dismissal snapshots the live card, flies the snapshot off, raises the preview, then reveals the live card (already showing that session) over 0.16 s. Undo flies the card back from the side it left.

### Session screen

`SessionView` (`SessionView.swift` lines 733-948) hides the navigation bar and the bottom bar. Hierarchy, back to front:

1. `sessionContent`. Either an error `ContentUnavailableView` ("Couldn't Load Session", Try Again secondary) or `SessionTranscriptView`.
2. Top overlay `SessionHeaderBackdrop`, non-interactive, accessibility-hidden. Height `HeaderBlurParameters.bandHeight` (110), ignores the top safe area.
3. Top overlay of header controls. On iOS 26 with the system edge and without Reduce Transparency, the controls are wrapped in `ScrollEdgeElementContainer` at height `TranscriptMetrics.clustered + 44` (52). Otherwise they are a plain overlay.
4. Bottom `safeAreaInset`, spacing 0, is the composer column. Optional voice failure or ended banners (horizontal 12, bottom 6), then `composerArea`. During catch-up, extra bottom padding of 12 (`CatchUpMetrics.composerClearance`).
5. `backgroundWork` overlay (scrim + panel) and the shell-output sheet.
6. `attachmentMenuOverlay` in the named coordinate space `AttachmentMenuAnchors.space`.
7. Alerts, full-screen image gallery, full-screen `AgentFileViewer`, full-screen camera.

The transcript measures `geometry.safeAreaInsets.bottom` as `composerInset`, then ignores the container safe area on the bottom, and also on the top when the system edge is on (`SessionTranscriptView`, lines 2200-2234). The UIKit list keeps the home-indicator inset itself (`contentInsetAdjustmentBehavior = .always`).

Header controls (lines 950-970): `HStack` spacing 10, horizontal padding 12, top padding 8, top aligned. Leading is the drawer button, or nothing during catch-up. Then the title. Spacer. Trailing controls spacing 8.

Drawer button (lines 782-802): `DrawerGlyph` in a 44 circle of interactive glass. Badge is a 10 pt circle, orange for attention, blue for unread, offset x -3, y 3. Scale plus opacity transition. Spring duration 0.3. Label "Open sessions". Value names the other session's state. The glyph is a template bitmap, not a SwiftUI shape, so glass renders it at full strength (lines 2370-2393). Width 18 scaled relative to body. Stroke 2.5 pt, snapped to pixels. Gap is `width * 0.3`. Long bar is `width`. Short bar is `width * 0.6`. Both leading aligned, round caps.

Title (lines 973-1018): `VStack` spacing 1, min height 44. Title is `.subheadline.semibold`, one line, tail truncation. Project line is `.caption` secondary, omitted for `projectID == "global"`. "Couldn't rename" replaces the project line for 2.2 s (the timer lives in the feature, not the view). Crossfade is 0.2 s ease-in-out. Tapping the title opens the session sheet, except during catch-up, where the title is a header and not a button. Shimmer while resyncing or auto-renaming. Last-known title (refresh failed, not syncing) is secondary.

Trailing: "Back to main session" glass circle when viewing a subagent. During catch-up, a checkmark circle labeled "Mark as read and continue". Otherwise the session menu.

Session menu button (lines 1043-1066): ellipsis, `.body.semibold`, 44 circle, interactive glass. Overlay is `SessionContextRing` at line width 2, track opacity 0, padding 1, so the rim is the gauge and there is no track. Label "More". Value is the context summary. Menu sections match `AGENTS.md` line 35. Pin, Rename, Copy Session ID, Mark as Read or Unread. Then Compact, Fork, Export (Markdown or JSON), Session Info. Then Delete Session, only for list rows, never subagents.

Composer column (`composerArea`, lines 1110-1164): `VStack` spacing 10, horizontal padding 20, top padding 6, bottom 8 while the field is focused and 0 otherwise (the inset already clears the home indicator; the 8 is breathing room against the keyboard, lines 1157-1160). Extra bottom padding is `composerLift` while the photo card is up. Order:

1. Draft setup cards, only for a draft. Hidden, not removed, under an open voice transcript.
2. Pill row, same hide rule.
3. Optional "Couldn't Load Requests" banner.
4. Either the pending-request banner, which replaces the composer, or the composer capsule.

A lone jump button, when there are no pills and no open voice transcript, floats in an overlay at `offset(y: -(44 + 10))` so it occupies the pill row's place without changing list insets (lines 1147-1152). Show and hide is snappy, duration 0.2, scale 0.8 plus opacity.

Pill layout (`composerPillStack`, lines 1184-1218): "Move to Background" is its own leading row when offered. Below it, one row if it fits (`ViewThatFits` horizontal). Background work and the report offer lead. A spacer. Queued messages and jump trail. If the row does not fit, they stack, spacing 10, trailing aligned. Pill insert and remove animation is snappy, duration 0.35.

`ComposerPillLabel` (lines 440-467): `HStack` spacing 8, leading icon frame 18, `.subheadline` medium title, optional chevron `.caption.semibold` secondary. Horizontal padding 14. Min height 34. Interactive glass capsule. Vertical padding 5 so the glass is 34 and the hit target is 44.

`ComposerBanner` (lines 471-503): full width, takes the composer's place. `HStack` spacing 12, icon frame 18, title `.subheadline.medium`, optional detail `.caption` secondary, two lines. Action `.subheadline.semibold` tint. Horizontal padding 18, vertical 8, min height 52. Interactive glass, corner radius 28.

Composer capsule (lines 1383-1416) is one glass shape shared with a voice call. Starting or ending a call uses `.blurReplace` inside a `ZStack`. Animation is smooth 0.32 s, or 0.2 s ease-in-out under Reduce Motion. The recording circle is drawn in an overlay above the glass, positioned by `RecordingCircleAnchorKey`, so the rim does not show through it. Scale 0.6 plus opacity on appear.

Composer body (lines 1419-1527): `VStack` spacing 8, horizontal padding 6, bottom 6, top 6 when collapsed and 0 when expanded (expanded top padding lives inside the text view). Content shape and drop-target stroke use corner radius 28, continuous. Drop stroke is `accentColor`, line width 2, opacity 1 only while targeted. Expansion animates smooth 0.25 s.

`ComposerLayout` (lines 11-40) always places three children so the text view's identity and keyboard focus survive expansion. Collapsed input origin x is 52, width is `width - 56 - trailingWidth`. Expanded input origin x is 16, width is `width - 32`. Controls sit on the vertical center when collapsed, and on the bottom when expanded. Leading proposal width is 44 collapsed, and `width - trailing - 8` expanded. Height of the control row is at least 44. Total expanded height is input height + 12 + controls.

Placeholder "Message" is `.body`, `placeholderText`, hidden from accessibility, top padding 14 when expanded. The real field is `ComposerTextInput`.

Leading controls: attachment `+` (44 target, body medium plus, or a progress view), then, when expanded, the model name (`.footnote.medium`, middle truncation) and a `.caption2.semibold` chevron. Label "Choose model", value is the model name. Secondary mic sits at the leading edge's trailing side when expanded, and beside Send when collapsed.

Trailing control is a 34 pt circle inside a 44 target (`ComposerControlLabel`, lines 127-153). Dynamic type caps at `accessibility1`. Symbol replace unless Reduce Motion. Send, Stop, and Call use `systemBackground` on `Color(.label)`. Disabled send uses `tertiaryLabel` on clear. Mic uses secondary on clear. Stop's glyph is `.caption.bold`. Others are `.body.medium`. During a run, Send is a `Menu` with a primary action (tap steers, hold offers Steer and Queue). A context menu would lose its press when the run redraws the composer (lines 1627-1640). Call replaces Send when the field is empty, Voice is available, and no call is running. Stop replaces both during a run.

Dictation hold (`HoldToDictateButton`, lines 160-228): drag minimum distance 0, high priority so the composer's tap-to-focus does not win. Slide up 70 pt locks. Slide left 110 pt cancels. A press shorter than 0.3 s locks (a tap). The recording bar replaces the row visually but the button stays mounted under it because it owns the finger. Recording circle is 84 pt scaled, `accentColor`, offset x `14 + dragX * 0.25`. Lock chip rises above it. Halo follows microphone level. Timer is monospaced digits with a 9 pt red dot blinking at `0.35 + 0.65 * abs(cos(elapsed * π * 0.8))`, 30 fps. "Slide to cancel" follows the finger at `dragX * 0.8` and fades to 10% opacity at the cancel distance.

Pending attachments are a horizontal scroll, spacing 8, indicators hidden. Images are 76 by 76, continuous corner radius 22. Inline file chips and upload chips are 220 by 76 (`ComposerFileChip.width` and `.height`), continuous corner radius 22, `secondarySystemFill` or, for upload chips, the same. Remove is a 44 circle, `xmark.circle.fill`, palette white on black at 0.65, offset (8, -8). Removal is ease-out 0.2 s. Insertion scale is 0.72 plus opacity.

### Transcript list

`TranscriptListView` is a `UIViewControllerRepresentable` around `TranscriptListViewController`. The list is a plain `UICollectionView` compositional list, no separators, clear background, always bounce vertical, normal deceleration, `keyboardDismissMode = .interactive` (lines 157-184). Accessibility id `transcript-list`.

Top inset is `TranscriptMetrics.headerBand` (60) so text never parks under the floating header. On iOS 26 with the system edge, 52 pt of that (`clustered + 44`) moves into `additionalSafeAreaInsets.top`, and `contentInset.top` becomes `60 - 52 = 8`. `topEdgeEffect.style = .soft` (lines 341-357). Bottom inset is the composer obstruction beyond the automatic safe area, plus trailing space owned by scroll behavior.

Cells are `UIHostingConfiguration` with horizontal padding `contentGutter` (16) and margins 0 (line 469). Row renderers must not add another 16. Cell background is clear. One SwiftUI root, `TranscriptMessageRow`, is used for both a pending inbox row and the delivered user group it promotes into, so promotion keeps identity.

Row kinds: history status, empty state, message, working. Working is appended after pending messages and notices, so a steering promotion does not reorder rows (lines 586-590). Tool rows whose image set changed are reloaded. Streamed text is reconfigured, then reloaded once after 400 ms of idle so accessibility and pixels agree (lines 850-867, and `AGENTS.md` lines 20-21).

`TranscriptEntryView` renders assistant markdown, tools, compaction, info, send failure, and notices. User groups are rendered by `TranscriptUserMessageView` instead. Disclosure state (`toolGroups`, `tools`, `compactions`) lives on the controller, never in cell `@State`.

User bubble (`TranscriptUserMessage.swift` lines 211-274): trailing, max 78% of width. Body, line spacing 2, horizontal padding 16, vertical padding 10, corner radius 18, fill `secondarySystemBackground`. Pending bubbles are text opacity 0.6 and fill opacity 0.55. A "Steering" or "Queued" caption sits above, trailing padding 16, bottom -2, caption secondary. Promotion animates opacity over 0.35 s ease-out. Images are 200 pt if one, 130 pt if several, corner radius 14, horizontal scroll anchored trailing, opacity 0.6 while pending. Voice delegations show a waveform at offset (-18, -10) and a context menu whose preview is 340 wide, max height 480, padding 16, code font.

Tool group (lines 134-186): "Used N" or "Updates" at body, secondary, min height 44, vertical padding -9 so the target overlaps neighboring paragraphs. No caret. Failed appends red "Failed". Expanded rows are a `VStack` spacing -8, leading padding 16, so the visual pitch is about 36 pt while each row keeps a 44 pt target. Summary counts roll with `.numericText()` and snappy 0.25 s, paused when the scene is not active.

Tool row: footnote medium name, `toolFont` one-line subtitle, checkmark when completed, red Failed when errored. Running calls show elapsed time once REST has a start and 5 seconds have passed. Expanded console block has corner radius 10, 0.5 pt separator stroke, output background `secondarySystemBackground` at 0.6. Output folds at 16 lines.

Subagent card: padding 12, min height 44, corner radius 14, `secondarySystemBackground`. Glyph is 18 by 20.

Question history card: padding 16, corner radius 14, headline tint label.

Compaction is a `TranscriptRule`, min height 44, caption label, 1 pt quaternary rules (red at 0.5 if failed). Spacing 12 between rule and label.

Send failure card: horizontal padding 12, vertical 8, corner radius 14, `secondarySystemBackground`.

Copy reply button is 44 by 36, subheadline secondary, leading padding -13 so the glyph aligns with the text. Success sensory feedback. Checkmark lasts 1.5 s.

Working indicator is body medium, secondary, vertical padding 2, shimmer period 1.6 s, band width 65% of the text, travel `phase * 1.9 - 0.65`. Label "Working" or "Needs your input". No shimmer when paused, when input is needed, or under Reduce Motion.

Empty state uses `ContentUnavailableView` with vertical padding 60.

Images in markdown cap at height 360, corner radius 14. Tool mosaics: one image 300 by 210. Two images 148 by 160, spacing 4. Three or more: 148 by 204 beside two 148 by 100 tiles. Extra count is white title2 semibold on black at 0.5. Gallery is black, page style without the system index, position capsule is footnote monospaced digit, white, horizontal 12, vertical 8, ultra thin material, bottom 24. Close is a 44 circle, ultra thin material, padding 16. Pinch 1 to 5. Double tap toggles 1 and 2 with snappy animation.

### Sheets and panels

`SessionSheetView` is a grouped `List`, compact section spacing, medium and large detents, visible drag indicator. On iOS 26 without Reduce Transparency, `SheetGlassBackground` hides the scroll content background so the sheet's own glass shows (lines 242-254). Earlier iOS keeps the grouped background. Title is title3 semibold. Cost is USD currency. Context row shows an 18 pt ring at line width 3 beside "64% of 400K". Rename, failure, delete, and share are `SessionActionPresentations`, also attached to the header when the sheet is not open, so the menu can present them.

`SessionSettingsView` is a standard `List` in a `NavigationStack`, medium and large detents, inline title "Settings", Done in the confirmation slot. Sections are Server, Advanced plus Diagnostics plus problem reports, and What's New. Tint on the server section is primary, not blue. No custom metrics.

`RequestReviewView` is a `NavigationStack` plus `ScrollView`, not a list. Background `systemBackground`, also set as `presentationBackground`. Detents medium and large, selection bound, drag indicator visible, content interaction scrolls. Content padding horizontal 20, vertical 12, spacing 16. Choice cards: padding 12, min height 44, corner radius 10, `secondarySystemBackground`, selected stroke `accentColor` width 2, unselected `separator` width 0.5. Answer field min height 44, same radius, 1 to 4 lines. Footer actions spacing 12, horizontal 20, vertical 12, `systemBackground`, subheadline medium. Primary and secondary button styles. Deny is secondary, not destructive. Allow Once is the only primary permission action.

`BackgroundWorkPanel` grows out of the capsule. Scrim is black at 0.32 in dark and 0.1 in light, ignores safe area, tap dismisses, accessibility-hidden. Panel horizontal padding 20, bottom gap 10 above the capsule, corner radius 28, glass, clipped. Max height is `max(160, capsule.minY - safeTop - 72)`. Open animation is a spring, response 0.38, damping 0.84, scale 0.25 from bottom leading plus opacity. Reduce Motion is 0.2 s ease-in-out opacity. Rows min height 48, horizontal padding 20, icon frame 20. Section headers footnote semibold secondary, horizontal 20, top 6, bottom 2. Divider horizontal 20, vertical 6. Pressed row highlight is primary at 0.08, corner radius 16 continuous, horizontal inset 8. Downward drag follows the finger. Upward drag is `-sqrt(-pull)`. Dismiss if translation exceeds 56 or predicted end exceeds 140. Minimum distance 12. If the content is taller than max height, the drag gesture includes only subviews so the scroll view keeps its drags. Escape dismisses. Modal accessibility trait.

`ShellOutputView` is a sheet, medium and large detents. Title is subheadline semibold monospaced, middle truncation. Output is a `UITextView`, not editable, selectable, clear background, non-contiguous layout, inset top 12, left 16, bottom 24, right 16, line fragment padding 0. Font is 13 pt regular monospaced, scaled with the footnote metrics (`ShellOutputView.swift` lines 267-269). Follows the bottom until the reader is 32 pt or more above it (line 383). Jump button is a 44 glass circle, padding 16, scale 0.8. Status bar min height 56, horizontal 16, vertical 6. Stop is bordered capsule, red tint. On iOS 26 the bars use `safeAreaBar`. Earlier, `safeAreaInset` with `.bar` material and a `Divider`.

`WhatsNewView` is a grouped list, compact section spacing, glass background modifier, medium and large detents when presented as a sheet. Rows are an `HStack` spacing 14, vertical padding 6. Illustration is 48 by 48, corner radius 12, fill primary at 0.06. Title headline. Summary subheadline secondary. Example footnote secondary with a tertiary turn-down arrow, spacing 6, top padding 2. Drawer row reuses `DrawerShortcutLabel` with a tint caption2 dot, height 18, and a context menu Hide. The row's own background is `systemBackground` at corner radius 12 so the lifted menu preview has a surface.

`PairingScannerView` is a dark `NavigationStack`. Camera preview ignores safe area. Viewfinder is a 250 by 250 rounded rect, continuous corner radius 28, white stroke at 0.9 opacity, line width 3, accessibility-hidden. Caption block is headline plus subheadline, white and white at 0.8, horizontal 20, vertical 14, black at 0.6, corner radius 18, outer horizontal 24, bottom 32. Fallback padding 32, spacing 16. Paste and settings buttons use primary (white tint, black text) or secondary glass. A successful scan fires `UINotificationFeedbackGenerator.success` directly, not through `HapticsClient` (line 249). QR only.

Model and project pickers (`SessionView.swift` lines 2038-2182) are navigation stacks, searchable, medium and large detents, plain lists. Current model is tint, semibold, with a checkmark. Favorite star is yellow when filled, 44 by 44. Provider id is footnote tertiary.

### Glass, blur, and the header

`openCodeGlass` (`Glass.swift` lines 21-31): iOS 26 uses `Glass.regular`, optional tint, optional `.interactive()`. Earlier iOS uses `ultraThinMaterial` in the same shape. Do not stack a custom blur on a system glass control.

`openCodePrimaryButton`: iOS 26 `.glassProminent` large. Earlier `.borderedProminent` large. Secondary is `.glass` or `.bordered`, large.

`HeaderBlurParameters.shipped` (all defaults, lines 4-34): band 110, blur radius 12, fade start 0, fade end 1, fade curve 1 (linear), background opacity 0.95 at the top, 0.6 at stop 0.4, 0 at stop 0.9. `systemEdge` is true, so iOS 26 uses UIKit's soft scroll-edge effect instead of the variable blur, unless Reduce Transparency is on.

`SessionHeaderBackdrop` (lines 620-666): system edge and not reducing transparency draws `Color.clear` and lets `ScrollEdgeElementContainer` attach `UIScrollEdgeElementContainerInteraction` to the transcript scroll view. Reduce Transparency, or an unsupported variable blur, draws an opaque `systemBackground` gradient (top and middle opacity forced to 1). Otherwise a `VariableBlurView` plus the gradient. Increase Contrast raises top opacity to at least 0.72 and middle to at least 0.2.

`VariableBlurView` uses the private `CAFilter` type `variableBlur`. It is capability-gated. If the filter is rejected, there is no blur and no tinted fallback (lines 94-100). The mask is a 1 by 256 image, alpha `pow(1 - progress, curve)`. This is an App Store review risk. A port should not copy the private filter. It should copy the visible result, a fade from blur to clear over the 110 pt band.

`SessionContextRing` (lines 5-34): track is primary at 0.2, doubled under Increase Contrast. Trim from 0, butt cap, rotated -90 degrees so 0 is 12 o'clock. Line width default 3.5, padding of half the line width. Tint is primary below 0.8, orange from 0.8, red from 0.95. Animation smooth 0.45 s. Hidden from accessibility. The number lives in the button's value. Percent is `Int((fraction * 100).rounded())`. Summary is "64% of 400K" using compact name notation, or "N tokens" when the limit is unknown. Value string uses the full token count, not compact.

### Markdown and the highlighter

Parsing is `OpenCodeMarkdownBlock.blocks(from:)` in `OpenCodeClient.swift` lines 799-1100, not in the view file. Rendering is `MarkdownBlockView` and `MarkdownBlocksView`.

Fence split is on the literal ` ``` `. Even segments are prose. Odd segments are code. The first line of a code segment is the language hint if more than one line. Empty code is dropped. Highlighted code is cached by source plus language on the previous blocks so a stream does not re-highlight unchanged fences. Only source and language are persisted. Colors are applied at decode time so they stay dynamic.

Prose line rules, in order:

- Blank line flushes a paragraph.
- A whole line matching `![alt](source)` is an image. Angle-bracket destinations and a trailing ` "title"` are stripped. A non-image file with a card-worthy kind becomes a file card instead.
- Lines starting with `>` are a blockquote. One leading space after `>` is stripped.
- A thematic break is three or more of the same `-`, `*`, or `_`, spaces and tabs ignored. Checked before list items so `- - -` is not a bullet.
- A list item is `- `, `* `, `• `, or digits plus `. `. Indent is spaces, tabs count as 4. Nesting follows indent changes, capped at level 3. A task prefix `[ ] ` or `[x] ` (case insensitive) replaces the bullet marker. Numbered items at level 0 use `numberedItem`. Everything else is `listItem`.
- A line starting with `|` whose next line is only `|`, `-`, `:`, and space is a table. Cells split on `|`, edge pipes stripped.
- A heading is 1 to 6 `#` followed by non-empty text.
- Anything else joins the current paragraph with newlines.

Inline text uses `AttributedString` markdown, `inlineOnlyPreservingWhitespace`. Inline code becomes monospaced subheadline. If the code is a file path, it becomes an `opencode-file://` link. Otherwise it is secondary. `MarkdownStyle.styled` then paints non-link inline code primary (or secondary if the task is done) on `tertiarySystemFill`, so the parser's secondary color does not win in the transcript. Web links stay system links. Local file links and `file://` links are rewritten to `opencode-file://open?path=&line=`. The transcript's `openURL` environment handles that scheme and opens `AgentFileViewer`. Other links use the system handler.

File recognition (`AgentFileReference.swift` lines 81-117): a link is a file if it is a relative or absolute path, `~/…`, or `file://` with no host or localhost. Schemes, fragments, and queries are not files, except `path:line` and `path:line:col` and a trailing `#L42`. Inline code is a file only if it is 2 to 512 characters, has no URL scheme, no whitespace or shell metacharacters, does not start with `-` or `@`, and the last component has a known extension or a known file name (`readme`, `dockerfile`, and the set at lines 250-253). `Type.member` with extension `json`, `map`, `log`, `lock`, or `env` and no slash is rejected. Cards are inserted under the first block that names a media or document file. Text and code paths stay inline links. `showsCard` is false for `.text`.

Pull request cards are appended after any paragraph containing `https://github.com/{owner}/{repo}/pull/{n}`. They are not part of the copied markdown.

Spacing (`MarkdownStyle`, lines 107-133): line spacing 4. Paragraph gap 16. List gap 6. Heading above 28, below 6. Block gap 20 for code, table, image, pull request, files, divider. List indent 20 per level, max visual level 3. Heading fonts: level 1 title2 bold, level 2 title3 semibold, else headline.

Blockquote has a 3 pt rounded bar, secondary at 0.5, leading padding 12, vertical padding 4, secondary body text.

Code and tables are full-width bands, not cards (`MarkdownBand`, lines 188-227). Horizontal padding of the row is cancelled with `-16`. Hairlines above and below. Content starts at the prose edge (padding 16 inside the scroller) and can scroll to the screen edge. Fade width 32 (the general modifier default is 40). Band fill is primary at 0.035 in dark and 0.025 in light. Vertical padding 12. Bounce based on size. Indicators hidden.

`HairlineRule` (lines 246-261) is `1 / displayScale` tall, filled with `separator`, then offset so its global min Y lands on a pixel boundary. An unsnapped hairline doubles or vanishes.

Tables (`MarkdownTable`, lines 265-317): `Grid`, horizontal spacing 20 (`turn`), vertical spacing 10 (`bubbleVertical`). Header is subheadline semibold secondary. Body is subheadline. Columns cap at 220 pt and wrap. Rules under the header and between rows are drawn in an overlay across the full band, not inside the scrolling grid. Header rule opacity 1. Body rules opacity 0.6. They stay put while columns scroll.

Task markers render as `square` or `checkmark.square.fill`, body, secondary, min width 20 trailing. Done item text is secondary. Number markers are footnote monospaced digit. Bullets are body. Marker to text spacing is 10.

`SyntaxHighlighter` (`SyntaxHighlighter.swift`) is not a grammar. It walks characters once. Slash comments (`//` and `/* */`) for Swift, JS, TS, and the default. Hash comments for Python, shell, YAML, TOML, and the default. Strings are double quotes, plus single quotes except Swift. Escapes skip one character. Strings do not cross newlines. Numbers are a run of hex digits plus `xXoObB._`. Identifiers that are keywords take the keyword color. Other identifiers whose first character is uppercase take the type color. Unlabelled fences use the union of Swift, JS, and Python keywords, plus both comment styles and single-quote strings. Shell, bash, zsh, yaml, yml, toml use `shellKeywords` and hash comments.

Palette, from `packages/ui` oc-2 tokens, as fractions in the source (lines 112-116):

| Token | Light | Dark |
| --- | --- | --- |
| keyword and number | rgb(200, 61, 139) / 255, about `#C83D8B` | rgb(247, 153, 198) / 255, about `#F799C6` |
| string | rgb(25, 139, 67) / 255, about `#198B43` | rgb(150, 227, 166) / 255, about `#96E3A6` |
| comment | rgb(0.38, 0.40, 0.43), about `#61666E` | rgb(0.55, 0.58, 0.62), about `#8C949E` |
| type | rgb(82, 48, 194) / 255, about `#5230C2` | rgb(158, 153, 247) / 255, about `#9E99F7` |

Colors are `UIColor` dynamic providers, so a cached `AttributedString` still flips with appearance.

### Assets

Fonts: `OpenCode/Fonts/BerkeleyMonoVariable.ttf`, registered in `OpenCode/Info.plist` under `UIAppFonts`. PostScript name used in views is `BerkeleyMonoVariable-Regular`. Used only for onboarding brand marks (setup step numbers, `opencode pair`, the download URL, the scanner caption). Everything else is SF, including code (`Font.monospaced()` and `UIFont.monospacedSystemFont`). Do not use Berkeley Mono in the transcript.

Images, all template-rendering-intent original, light and dark luminosity variants:

- `OpenCode/Assets.xcassets/OpenCodeWordmark.imageset/opencode-wordmark-light.png`
- `OpenCode/Assets.xcassets/OpenCodeWordmark.imageset/opencode-wordmark-dark.png`
- `OpenCode/Assets.xcassets/OpenCodeWordmarkSimple.imageset/opencode-wordmark-simple-light.png`
- `OpenCode/Assets.xcassets/OpenCodeWordmarkSimple.imageset/opencode-wordmark-simple-dark.png`

The connection screen uses `OpenCodeWordmark`, not the simple set. No other imagesets. No color sets.

App icon: `AppIcon.icon/icon.json`. Fill is Display P3 solid `0.075, 0.063, 0.063, 1`. One layer, `logo.svg`, glass enabled, neutral shadow opacity 0.4, translucency 0.4. `logo.svg` is an 820 viewBox drawing of a square ring. Outer path fill white. Inner square `#5A5858`. This is an iOS 26 icon-composer asset, not a set of PNGs.

## Design tokens

### Spacing, radii, and type in `TranscriptMetrics`

`OpenCode/Transcript/TranscriptEntryView.swift` lines 7-32. Rows own outer rhythm. Renderers must use this scale so cell padding and SwiftUI padding do not compound.

| Token | Value | Use |
| --- | --- | --- |
| `compact` | 4 | tool group row insets, blockquote vertical, draft row inner spacing |
| `clustered` | 8 | header top padding, user message stack, image spacing, file chip spacing, tool body spacing |
| `bubbleVertical` | 10 | user bubble vertical padding, table row spacing, quiet row top and bottom |
| `standard` | 12 | rule label spacing, tool group stack, card inner padding, file card horizontal padding |
| `contentGutter` | 16 | cell horizontal padding, bubble horizontal padding, band content inset |
| `turn` | 20 | table column spacing |
| `bubbleCornerRadius` | 18 | user bubbles |
| `cardCornerRadius` | 14 | cards, images, file chips in the transcript, draft rows |
| `controlCornerRadius` | 10 | choices, fields, tool console, queued image thumbs |
| `imageCornerRadius` | 14 | same as card |
| `codeFont` | subheadline monospaced | code bands, request commands, shell titles in the panel. 15 pt at the default content size |
| `toolFont` | footnote monospaced | tool commands, arguments, output. 13 pt at the default content size |
| `maximumUserBubbleFraction` | 0.78 | bubble width cap |
| `rowTop`, `rowBottom` | 10, 10 | single rows and the bottom of a user group |
| `headerBand` | 60 | list top inset, empty-state vertical padding |

User groups that start a turn use top 10. Continuations use top 0. Reply-end copy button uses top 0, bottom 10. Tool groups and pending notices use top 4, bottom 4.

iOS text styles at the default Large content size, which the code uses by name rather than by point size. A port should use these as the sp baseline and scale with font scale.

| Style | Default size | Weight when specified in this UI |
| --- | --- | --- |
| largeTitle | 34 | not used in these screens |
| title | 28 | catch-up badge uses 28 bold, scaled with title1 metrics |
| title2 | 22 | drawer "Sessions" bold. What's New call glyph is not this style |
| title3 | 20 | "You're all caught up" semibold. Sheet title semibold |
| headline | 17 semibold | setup titles, scan button, error titles, question titles, What's New titles |
| body | 17 | transcript prose, drawer rows, composer field, Used N |
| callout | 16 | Berkeley Mono step numbers are 15 relative to callout |
| subheadline | 15 | session title semibold, pills medium, code font |
| footnote | 13 | tool font, errors, captions in cards, shell output explicit 13 mono |
| caption | 12 | project line, pending label, compaction rule |
| caption2 | 11 | drawer dots, model chevron semibold |

`@ScaledMetric` bases: step badge 30 relative to callout, drawer symbol 20 relative to body, drawer glyph width 18 relative to body, composer control diameter 34 relative to body, recording circle 84 relative to body, attachment row height 52 and well 36 relative to body.

Touch target minimum is 44 by 44, stricter than the HIG 28 minimum (`docs/native-ui-guidance.md` lines 26-27). Composer glass is 34 inside that 44. Floating New Session and Settings are 50. Scan button is 52 tall. Credential fields are 50. Banners are 52. Draft setup rows are min 56. Shell status bar is min 56. Background rows are min 48, which is above 44. Copy reply is 44 by 36, a short target. The comment says the glyph aligns to the text and the frame is still the target.

### Drawer, catch-up, header, composer

| Token | Value | Where |
| --- | --- | --- |
| drawer width | 75% of screen width | `HomeView.swift:24` |
| `DrawerMetrics.rowEdge` | 16 | search, shortcuts, session rows |
| drawer corner while open | `44 * progress` | `SessionDrawerContainer.swift:403` |
| drawer dim | black, 0.35 * progress, 0.48 if high contrast | lines 404-405 |
| drawer settle | 0.3 s, damping ratio 1 | line 375 |
| drawer velocity threshold | 250 pt/s, else half width | line 355 |
| pan axis lock | 8 pt (`dx*dx+dy*dy >= 64`), then horizontal if `abs(dx) > abs(dy)` | lines 614-627 |
| selected row fill | primary 0.14, radius 12 | `HomeView.swift:387` |
| search and offline fill | primary 0.12, radius 12 and capsule | lines 229, 249 |
| New Session / Settings | height 50, settings diameter 50 | lines 328, 341 |
| floating control bottom | safe bottom + 12, horizontal 16 | line 351 |
| list bottom margin | 50 + 24 + safe bottom | line 313 |
| list top margin | safe top + 12 | line 309 |
| catch-up header / footer | 52 / 74 | `CatchUpStack.swift:10-11` |
| catch-up side inset | 10 | line 12 |
| catch-up corner | 32, continuous | line 13 |
| catch-up composer clearance | bottom 12 | line 16 |
| catch-up read color | rgb(0.08, 0.57, 0.42) = `#14916B` | line 7 |
| catch-up read control | rgb(0.06, 0.46, 0.34) = `#0F7557` | line 8 |
| catch-up keep color | rgb(0.34, 0.38, 0.52) = `#576185` | line 9 |
| catch-up threshold | 30% of container width | line 287 |
| catch-up flick | 650 pt/s, same direction, translation over 24, finger not rested 0.08 s | lines 352-354 |
| catch-up peek scale | 0.92 + 0.08 * progress | line 292 |
| catch-up peek lift | `(height * 0.04 + 9) * (1 - progress)` | line 294 |
| catch-up drag rotation | `-translation.x / width * 0.16` radians, y travel `* 0.3` | lines 300-301 |
| catch-up exit | `1.5 * width` on x, y 40, rotation 0.32 | lines 306-307 |
| catch-up badge full opacity | at 0.45 * threshold | line 313 |
| preview dim | 0.35 dark, 0.06 light | line 284 |
| snapshot shadow | black, opacity 0.16, radius 24, offset (0, 10) | lines 532-535 |
| header band height | 110 | `HeaderBlurParameters.swift:8` |
| header blur radius | 12 | line 10 |
| header fade | start 0, end 1, curve 1 | lines 12-16 |
| header scrim | 0.95, 0.6 at 0.4, 0 at 0.9 | lines 18-24 |
| context thresholds | orange at 0.8, red at 0.95 | `SessionContextRing.swift:15-17` |
| ring track | primary 0.2, times 2 if increased contrast | line 8, line 24 |
| ring on menu | line width 2, track 0 | `SessionView.swift:1054` |
| ring in sheet | line width 3, frame 18 | `SessionSheetView.swift:184-185` |
| composer outer padding | horizontal 20, top 6, bottom 8 if focused else 0 | `SessionView.swift:1155-1160` |
| composer corner | 28 continuous | line 1503 |
| composer inner padding | horizontal 6, bottom 6, top 6 if collapsed else 0 | lines 1498-1500 |
| expanded text inset | 14, scrolls away | `ComposerTextInput.swift:9` |
| expanded line cap | 6 lines. Collapsed cap is 1 | line 72 |
| control diameter | 34 inside 44 | `SessionView.swift:135, 150` |
| pill glass height | 34, vertical padding 5 | lines 462-465 |
| banner min height | 52, radius 28 | lines 498-499 |
| attachment thumb | 76, radius 22 continuous | lines 1805-1806 |
| file chip | 220 by 76, radius 22 | `FileAttachmentViews.swift:126-127` |
| jump float offset | `-(44 + 10)` | `SessionView.swift:1151` |
| dictation lock / cancel / tap | 70 pt up, 110 pt left, 0.3 s | lines 161-164 |
| recording circle | 84 | line 303 |
| background panel radius | 28 | `BackgroundWorkPanel.swift:104` |
| background scrim | black 0.32 dark, 0.1 light | line 37 |
| background row | min 48, highlight radius 16, press opacity 0.08 | lines 210, 224-225 |
| shell output inset | 12, 16, 24, 16 | `ShellOutputView.swift:280` |
| shell follow threshold | 32 pt | line 383 |
| attachment menu radius | 28 menu, 44 card, inset 8, composer gap 10, padding 8 | `AttachmentMenu.swift:103-108` |
| attachment row / well | 52 / 36, scaled | lines 100-101 |
| scanner frame | 250, radius 28, stroke 3, white 0.9 | `PairingScanner.swift:80-82` |
| connection wordmark | 200 by 36 | `AppView.swift:47` |
| connection padding | 24, section spacing 28 | lines 42, 69 |
| scan button | height 52, radius 16 | lines 289-291 |
| server card / error card | radius 20, padding 16 and 20 | lines 259, 367-368 |
| credential field | height 50, radius 14, horizontal 14 | lines 472-474 |
| step badge | 30 scaled, radius 6 | lines 18, 178 |

### Motion constants in the transcript

`BottomSpring` (`TranscriptListViewController.swift` lines 1552-1564) and `SendFlight` (lines 42-45) and `RowEntrance` (lines 1569-1575):

| Token | Value |
| --- | --- |
| `jumpOmega` | 14. Comment says about 0.5 s. Used for jump, keyboard, and viewport takeover |
| `arrivalOmega` | 20. Comment says about 0.35 s |
| `arrivalSpeed` | 1080 pt/s, which is 18 pt per 60 Hz frame |
| `arrivalReach` | 2.5 screen heights. Farther arrivals and jumps land at once |
| spring timeout | 1.2 s after the target stops moving |
| `openingSettle` | 0.75 s after first layout before arrivals glide |
| settle epsilon | `abs(x) < 0.5` and `abs(v) < 20` |
| frame dt cap | one display-link frame. A hitch is not caught up in one leap |
| send flight omega | 14. Comment says about 0.45 s |
| send flight timeout | 0.8 s |
| send flight done | progress >= 0.995 |
| row entrance duration | 0.25 s |
| row entrance rise | 6 pt |
| row entrance curve | cubic (0.2, 0.8, 0.2, 1) |
| row entrance max rows | 4 |
| row entrance window | 0.5 s if the cell was off screen at insert |
| leave bottom | 120 pt from the bottom or from drag start |
| rest at bottom | within 1 pt |
| earlier-history trigger | `offsetY < visibleHeight` |
| idle repaint | 400 ms after a reconfigure |
| history spinner delay | 400 ms |

The spring step is the exact critically damped solution, not a UIView spring (`stepBottomSpring`, lines 1105-1120):

```
decay = exp(-omega * dt)
x = (x0 + (v0 + omega * x0) * dt) * decay
v = (v0 - omega * (v0 + omega * x0) * dt) * decay
```

Send-flight progress is the same family, `1 - (1 + omega * elapsed) * exp(-omega * elapsed)` (line 963).

Scroll behavior thresholds (`TranscriptScrollBehavior.swift` lines 53-57): `leaveDistance` 120, `restDistance` 1.

### Hardcoded colors beyond system styles

| Name | Light | Dark | Source |
| --- | --- | --- | --- |
| catch-up read wash | `#14916B` | same | `CatchUpStack.swift:7` |
| catch-up Mark as Read button | `#0F7557` | same | line 8 |
| catch-up keep wash | `#576185` | same | line 9 |
| syntax keyword | `#C83D8B` | `#F799C6` | `SyntaxHighlighter.swift:112` |
| syntax string | `#198B43` | `#96E3A6` | line 113 |
| syntax comment | `#61666E` | `#8C949E` | line 115 |
| syntax type | `#5230C2` | `#9E99F7` | line 116 |
| What's New warm | rgb(0.62, 0.45, 0.24) | rgb(0.98, 0.90, 0.78) | `WhatsNewView.swift:139` |
| What's New cool | rgb(0.26, 0.38, 0.58) | rgb(0.78, 0.86, 1.0) | line 143 |
| icon fill | Display P3 0.075, 0.063, 0.063 | same | `AppIcon.icon/icon.json` |
| icon mark | white ring, inner `#5A5858` | same | `logo.svg` |
| markdown band | primary 0.025 | primary 0.035 | `MarkdownBlockView.swift:222` |
| code overflow fade | mask to 0 over 32 pt when content is past the edge | same | line 210 |
| general overflow fade | 40 pt | same | `HorizontalOverflowFade.swift:6` |
| overflow threshold | 1 pt | same | line 25 |

Hex values above are `round(component * 255)` except the syntax comment, which is given as 0-1 floats. Keyword, string, and type use the exact `n/255` fractions in the source. Prefer those fractions over the hex.

### System colors the views name

These are not defined in the repo. They are Apple semantic colors. A matching port should hardcode the iOS 18 values, not Material You.

| SwiftUI / UIKit name | Light | Dark | Used for |
| --- | --- | --- | --- |
| `systemBackground` / `.primary` surface | `#FFFFFF` | `#000000` | screens, drawer, sheets, catch-up cards |
| `secondarySystemBackground` | `#F2F2F7` | `#1C1C1E` | bubbles, cards, catch-up chrome |
| `secondarySystemFill` | rgba(120,120,128,0.16) | rgba(120,120,128,0.32) | composer file chips |
| `tertiarySystemFill` | rgba(118,118,128,0.12) | rgba(118,118,128,0.24) | fields, inline code chip, copy command chip |
| `label` / `.primary` | `#000000` | `#FFFFFF` | text, filled Send circle |
| `secondaryLabel` / `.secondary` | rgba(60,60,67,0.60) | rgba(235,235,245,0.60) | captions, Used N |
| `tertiaryLabel` / `.tertiary` | rgba(60,60,67,0.30) | rgba(235,235,245,0.30) | disabled send, directory lines |
| `placeholderText` | rgba(60,60,67,0.30) | rgba(235,235,245,0.30) | "Message" |
| `separator` | rgba(60,60,67,0.29) | rgba(84,84,88,0.65) | hairlines, card strokes |
| `systemBlue` / `.tint` unless overridden | `#007AFF` | `#0A84FF` | unread, links, What's New dot |
| `systemOrange` | `#FF9500` | `#FF9F0A` | attention, pin swipe, errors that use orange |
| `systemRed` | `#FF3B30` | `#FF453A` | failed, destructive, recording dot |
| `systemGreen` | `#34C759` | `#30D158` | shell exit 0, verified key |
| `systemPurple` | `#AF52DE` | `#BF5AF2` | pull request icon |
| `systemYellow` | `#FFCC00` | `#FFD60A` | favorite star |

Increase Contrast changes some of these opacities in UIKit. The app also doubles the context-ring track and forces the header scrim more opaque. Reproduce those branches, not only the base table.

Glass on iOS 26 is `Glass.regular`, optionally `.interactive()` and `.tint`. It is not a fixed blur radius. The iOS 18 fallback is `ultraThinMaterial`, which is a system material, not a number. Do not paint message, code, image, or answer-card backgrounds with glass (`AGENTS.md` line 19).

### Other animation values

| What | Animation | Reduce Motion |
| --- | --- | --- |
| drawer open and close, pin reorder | spring duration 0.3, bounce 0 | none |
| drawer search chrome fade | ease-out 0.15 | none |
| indicator swap | snappy 0.25 | none |
| catch-up remaining count | snappy, numeric text | identity |
| catch-up enter and exit morph | 0.45 s, damping 1 | instant |
| outgoing snapshot during morph | 0.2 s ease-out fade | none |
| preview reveal | 0.36 s, damping 1 | instant alpha |
| card settle back | 0.45 s, damping 0.78, initial velocity from the finger, capped at 20 | same timing object |
| fling | 0.5 s, damping 1, velocity capped at 12 | 0.25 s, alpha only |
| land swap | 0.16 s ease-in-out | same |
| undo | 0.55 s, damping 0.86 | crossfade 0.25 s |
| rise of a new card | 0.4 s, damping 1 | not separately reduced in `rise()` |
| crossfade of a replaced card | 0.25 s ease-out | used as the reduced undo |
| title blur replace | smooth 0.45 | opacity |
| title shimmer period | 1.4 s, band 55% of width, travel `phase * 1.75 - 0.55`, text opacity 0.4, highlight primary 0.9 | opacity 0.4, no sweep |
| rename-failed crossfade | ease-in-out 0.2 | none |
| context ring | smooth 0.45 | none |
| pill insert and remove | snappy 0.35 | none |
| queued pill removal | move from top plus opacity | the transition is not separately gated |
| jump button | snappy 0.2, scale 0.8 | opacity |
| composer expand | smooth 0.25 | none |
| drop target stroke | ease-out 0.15 | none |
| trailing control swap | ease-out 0.15 | none |
| voice capsule swap | smooth 0.32, blur replace | ease-in-out 0.2 |
| recording row | ease-out 0.18 | none |
| lock chip | spring 0.3 bounce 0.2, finishing spring 0.25 | none |
| attachment remove | ease-out 0.2 | none |
| pending bubble promote | ease-out 0.35 | none |
| tool count | snappy 0.25, numeric text | none |
| copy command chip | snappy | none |
| attachment menu open | spring 0.42 bounce 0.2 | ease-in-out 0.2 |
| attachment menu morph | spring 0.5 bounce 0.12 | ease-in-out 0.22 |
| attachment menu close | spring 0.36 bounce 0 | ease-in-out 0.2 |
| attachment menu drag back | spring 0.35 bounce 0.25 | n/a |
| background panel open | spring response 0.38 damping 0.84, scale 0.25 from bottom leading | ease-in-out 0.2 opacity |
| background panel drag release | spring response 0.3 damping 0.8 | none |
| overflow fade | ease-out 0.15 | not gated |
| gallery double tap | snappy | none |
| activity ring period | 0.8 s | frozen |
| agent glyph period | 1.1 s | one dot |
| working shimmer period | 1.6 s | no sweep |
| copy checkmark reset | 1.5 s | still resets |
| auto-rename failure | 2.2 s, feature timer, not in the view | still shows |

Attachment menu dismiss thresholds: card drag translation over 30 or predicted end over 120 (`AttachmentMenu.swift:273`). Menu drag translation over 50 or predicted end over 160 (line 379). Upward drag rubber-bands with `-sqrt(-y) * 2`.

## Flow

1. Launch builds one `StoreOf<AppFeature>` and shows `AppView`.
2. No saved server, or a connection failure, shows the connection screen. Scan opens `PairingScannerView` as a sheet. A QR string or a pasted link is `pairingCodeScanned`. Manual entry is a sheet, then Connect.
3. A connected server scopes `HomeFeature`. `HomeView` opens the remembered last session, or a draft, inside `SessionDrawerContainer`. The first frame can use a persisted transcript snapshot. That cache lives in the feature, not the view.
4. The session screen is full screen. The drawer is underneath, 75% wide, hidden until the card translates. The drawer button, a rightward pan that wins arbitration, or a leftward pan while the drawer is open and the touch is not on a row, moves the card. A tap on the dimmed card closes it.
5. Selecting a row sends `sessionTapped` and closes the drawer with the 0.3 s spring. The same `SessionView` identity is reused. `.task(id: session.id)` reloads the transcript. `.id(store.session?.id)` on the transcript view resets the list.
6. New Session closes the drawer and sends `newSessionTapped`. A draft shows project and model cards above the composer, not in the list.
7. Settings and What's New are sheets from the drawer. The session sheet, model picker, project picker, and request review are sheets from the session. Shell output is a sheet from the background panel. Image gallery, camera, and file viewer are full-screen covers.
8. Catch Up replaces the drawer interaction with a card stack. The same hosted `SessionView` insets by 10 pt, below a 52 pt header and above a 74 pt footer. Swipe right marks read. Swipe left keeps unread. Buttons call the same fling.
9. Sending does not anchor the bubble at the top. If the reader is following and Reduce Motion is off, the composer text snapshots and flies into the bubble on the same display link that scrolls the list. A browsing reader gets no flight. The draft clear must not replace the `UITextView`, or the keyboard drops.
10. Streaming appends rows. A following reader glides. A browsing reader does not move. Jump to latest sets following and springs if the distance is within 2.5 screens.

## Animations and gestures

Drawer pan arbitration (`DirectionalPanGestureRecognizer`, lines 580-718), in order:

- Wait until the touch has moved 8 pt. Before that, descendant scroll views are required to fail this recognizer, so they do not start.
- If vertical wins, this recognizer fails and the scroll view proceeds.
- Rightward, drawer closed, on content: begin, unless a horizontal scroller under the finger is not at its leading edge (`contentOffset.x > -inset + 0.5`).
- Rightward, drawer open, on a drawer row or the drawer list: fail, so swipe actions and list pans win.
- Leftward, drawer open, on a drawer row: fail, so the trailing delete swipe wins.
- Leftward, drawer open, on drawer chrome or the exposed card: begin, and close.
- Once horizontal wins, `cancelsTouchesInView` becomes true, which cancels a pending button highlight.
- Catch-up uses `cardSwipeDecision`. Either direction begins if the touch is on the card and a horizontal scroller does not still have room that way.

Transcript follow (`TranscriptScrollBehavior` plus the controller):

- Modes are following and browsing. Session open starts following and lands at once for 0.75 s (`openingSettle`).
- `dragBegan` records the start offset only while following.
- A drag whose distance from the bottom, or whose travel from the start, exceeds 120 pt switches to browsing and shows Jump to latest.
- While the finger is down, scrolling up shrinks trailing space to the position floor so held space drains without moving the reader.
- `userSettled` within 1 pt of the bottom, and travel within 1 pt or content arrived during the drag, returns to following and pins. Otherwise a following reader who rested off the bottom becomes browsing.
- Content appended while following drains trailing space and scrolls to the bottom. The controller turns that into a spring when the viewport is unchanged and the distance is within 2.5 screens.
- An end that shrinks under a following reader (a pending row leaving, a pill leaving) sets trailing space to the position floor. Arrivals only move content up. If the spring would move backward, it stops and `settleShrunkEnd` runs. Once idle, the reader settles back onto the end (`SpringKind.settle`).
- A transcript shorter than the viewport has position floor 0. Nothing is held.
- UIKit's own offset changes during updates are undone for a following reader (`scrollViewDidScroll`, around lines 400-411). The keyboard is ridden with `SpringKind.keyboard` at `jumpOmega`.
- The first drag with the keyboard up holds the space the keyboard leaves. The drag drains it. `keyboardDismissMode` is `.interactive`.
- VoiceOver, Switch Control, and a hardware keyboard (`GCKeyboard.coalesced`) count as the reader's scrolls (lines 102-104, 413-421). Status-bar scroll-to-top is `userLeftBottom`.
- Jump to latest stops deceleration, springs if within 2.5 screens, otherwise jumps. It must win the old deceleration (`isHandlingExplicitJump`).
- New tail rows fade and rise 6 pt as layer animations only, at most 4 rows, not on session open, not on a page, not when UIKit is already animating a promotion, not for the sent message that is flying.
- Reduce Motion skips the flight, the glide, and the entrance. Commands still scroll, instantly.

Send flight (`ComposerSendTransition`, lines 104-191): `prepare` snapshots the used text rect into the window before the draft clears. `beginLanding` snapshots the bubble cell, hides `contentView.alpha`, and interpolates frames. The landing frame is re-read every tick so the flight tracks the spring. Source alpha falls. Landing alpha rises. If the bubble is not yet on screen, the list is momentarily moved to the bottom without animation, the snapshot is taken, and the offset is restored (lines 926-939). Missing marker cancels the flight and uses a normal row entrance.

Background panel and attachment menu gestures are in the token tables above. Catch-up drag is the same pan recognizer, forwarded to `CatchUpStackController.handlePan`. A light impact fires when the drag first crosses the threshold (line 329).

`TimelineView(.animation)` drives rings, glyphs, shimmers, and the recording timer so a `repeatForever` animation cannot leak into list layout (`HomeView.swift` lines 877-880). Pause when the scene is inactive or Reduce Motion applies.

## UIKit and why

| Piece | Type | Why it is not SwiftUI |
| --- | --- | --- |
| Transcript list | `UICollectionView` + diffable data source + `UIHostingConfiguration` | One display link must own offset, spring velocity, and the send flight. SwiftUI `ScrollView` fights keyboard, self-sizing, and prepend compensation. Cells stay SwiftUI. |
| Composer field | `UITextView` (`ImagePasteTextView`) | Image paste must be intercepted before an attachment is inserted. The view identity must survive draft clears or the keyboard drops. Return key is `.send`. A single appended `\n` is treated as Return because a vertical field would otherwise insert a newline (`SessionView.swift` lines 1447-1451). Expanded top inset scrolls away instead of clipping. |
| Drawer | `UIViewController` + `UIHostingController`s + `UIPanGestureRecognizer` subclass | Per-frame transforms must not invalidate `HomeView`. Directional failure arbitration against scroll views is a UIKit pattern. |
| Catch-up choreography | `UIView` transforms, snapshots, `UIViewPropertyAnimator` | The live card and the preview card have to swap without a SwiftUI identity change under the finger. |
| Pin reorder | `UIDragInteraction` + `UIContextMenuInteraction` on one `UIView` | SwiftUI list menu and reorder recognizers compete. |
| Header edge on iOS 26 | `UIScrollEdgeElementContainerInteraction` | Soft scroll-edge blur is a UIKit scroll effect. The representable exists only to attach it to the collection view. |
| Variable blur | `UIVisualEffectView` + private `CAFilter` | No public variable-radius blur. Gated. Prefer the iOS 26 system edge. |
| Shell output | `UITextView` | Append-only text storage so a poll does not re-lay out the whole capture. Non-contiguous layout. |
| Pairing camera | `AVCaptureSession` + `AVCaptureVideoPreviewLayer` | One QR symbology. VisionKit's scanner reports availability badly on the simulator. |
| Share sheet | `UIActivityViewController` | System share from inside a sheet. |
| Image gallery | SwiftUI `TabView` page style | Not UIKit. Pinch is SwiftUI `MagnifyGesture`. |

`UIHostingConfiguration` cells can expose new accessibility content while painting the previous surface. The controller reconfigures streamed deltas and reloads image-set changes plus an idle reload of recently changed rows. Disclosure cannot live in cell `@State` because reuse would collapse it. A Compose `LazyColumn` does not have this hosting bug if the item composable is the cell. If the port uses `AndroidView` cells, reload when images appear, and keep disclosure in the list state, not in the cell.

## Accessibility and haptics

Respect Reduce Motion, Reduce Transparency, and Increase Contrast. The views read `accessibilityReduceMotion`, `accessibilityReduceTransparency`, and `colorSchemeContrast`. UIKit catch-up reads `UIAccessibility.isReduceTransparencyEnabled` and `accessibilityContrast`.

Labels name the thing. Values carry state. Decorative glyphs are `accessibilityHidden`. Examples that a port should keep as strings, not invent:

- "Open sessions", value "A session needs your input" or "Unread sessions".
- "Choose model", value is the model name.
- "Scroll to latest".
- "More", value is the context sentence from `SessionContextText.accessibilityValue`.
- "Review requests", value "N pending".
- Used N value "Expanded" or "Collapsed", plus ", Failed".
- Compaction hint "Shows the compaction summary".
- Task markers "Done" and "Not done".
- Drawer rows append "Pinned", "Needs your input", "Working", or "Unread".
- Catch Up value "1 session" or "N sessions", hint "Swipe through unread and waiting sessions".
- Pins expose VoiceOver actions Unpin, Mark as Read or Unread, Delete, Move Up, Move Down.
- Composer field label "Message".
- Steer hint "Steers at the next step. Touch and hold to queue after this response."
- Mic hint "Touch and hold to record, then let go to send", plus an accessibility action that taps the mic for VoiceOver.
- Background panel is modal and has an escape action.
- Drawer dimming view is "Close session list". Escape closes the drawer or leaves catch-up (`accessibilityPerformEscape`, lines 336-344).
- Copy reply announces "Copied" for 1.5 s and fires `.sensoryFeedback(.success)`.

Dynamic type is on for text styles, the composer field (`adjustsFontForContentSizeCategory`), catch-up badge labels, and shell output. Composer controls cap at `accessibility1`. The attachment menu caps at `accessibility2`. Do not clip the 34 pt circle. It grows with the glyph up to that cap.

Selection cues are not color alone. Selected drawer rows are semibold plus a fill. Selected request choices add a stroke and a checkmark and the selected trait. Context uses a ring plus a value string. Failed tools add "Failed" text, not only red.

`HapticsClient` (`HapticsClient.swift`) wraps UIKit generators. `selection` is `UISelectionFeedbackGenerator`. `impact` is light impact. `success` and `error` are notification feedback. Voice haptics are separate and out of scope except the intensities in lines 73-92 (call started medium 0.75, sweep rigid 0.5, connected success, muted soft 0.6, unmuted rigid 0.65, ended soft 0.85).

Call sites in the assigned flow:

| Event | Haptic |
| --- | --- |
| send, including while uploads are pending | light impact. Failed files before send: error |
| interrupt | light impact. Interrupt failure: error |
| move to background | light impact. Failure: error |
| mic tap and mic press began | light impact |
| mic lock and mic cancel | selection |
| dictation failure | error |
| attachment added, files staged | selection |
| upload failure | error |
| request reply started | selection |
| request reply finished | success, or error |
| pin, unpin, mark read, mark unread, pin drop | selection |
| catch-up dismiss and undo | selection |
| delete confirmed | light impact. Delete failure: error |
| share received | success |
| catch-up drag crosses threshold | light impact, local generator, not the client |
| QR scanned | notification success, local generator |
| copy reply | SwiftUI sensory success |

Shell stop confirmed is light impact. Stop failure is error (`ShellOutputFeature.swift` lines 160, 183).

Android analog is `HapticFeedbackConstants` plus `VibrationEffect` for the intensities. There is no exact `UISelectionFeedbackGenerator`. `CLOCK_TICK` or `SEGMENT_TICK` is the closest selection. `CONFIRM` or `CONTEXT_CLICK` is the closest light impact. `CONFIRM` and `REJECT` are the closest success and error. Gate on a user setting if the device has no vibrator. Do not vibrate on every streamed token. These fires are user actions and settled outcomes.

## Android analogs for pieces with no direct equivalent

| iOS | Closest Compose or Android | What will not match if you skip it |
| --- | --- | --- |
| Liquid Glass and `GlassEffectContainer` | A blur behind a translucent shape, plus a specular rim. Libraries such as Haze, or `RenderEffect.createBlurEffect` on API 31. Interactive glass has no API | Controls will look like Material buttons. That is acceptable for iOS 18 fallback (bordered prominent) but not for the iOS 26 look the screenshots show |
| `ultraThinMaterial` | `Modifier.blur` plus a white or black tint at about 70% is a rough stand-in. Real material also saturates | Header and pills will look flatter |
| Variable blur and soft scroll-edge effect | A vertical gradient mask over a blur, or a fading `systemBackground` scrim if you match Reduce Transparency. There is no public radius mask | Simulator screenshots under-render this (`AGENTS.md` line 48). Judge the header on a phone. A flat scrim is the honest fallback |
| `UICollectionView` + display link | `LazyColumn` with a `Choreographer` frame callback that sets scroll offset, or a `RecyclerView` if you need the same compensation. Do not use `animateScrollToItem` for streaming | Streaming will stutter or snap. The spring formula above ports directly |
| Critically damped `UIViewPropertyAnimator(dampingRatio: 1)` | `SpringSpec` with damping ratio 1, or the same closed-form step on a frame clock | Drawer and catch-up will bounce if you use the default Compose spring |
| `.continuous` corner curve | A squircle path. `RoundedCornerShape` is circular arcs and reads tighter | Cards and the composer will look more circular than the iOS screenshots |
| `blurReplace` title transition | Crossfade plus a few points of vertical offset. `RenderEffect` blur on API 31 can approximate it | Title changes will pop |
| `keyboardDismissMode = .interactive` | `WindowInsetsAnimationCompat` and a nested scroll that drags the IME. `imePadding()` does not drag-dismiss | The keyboard will jump instead of tracking the finger |
| Interactive keyboard plus trailing space | Apply the IME inset and the position floor in the same frame as the list snapshot they belong to | A following reader will jump when the keyboard or a pill changes |
| Status bar scroll to top | No system equivalent. Optional. TalkBack scroll-to-position should count as a user scroll | Missing this is acceptable. Treating TalkBack as programmatic scroll is not |
| Inline `PhotosPicker` with no permission | The Android photo picker is an activity, not an inline grid. A `MediaStore` grid needs permission. Partial photo picker (Android 13+) is the closest permission story | The morph from the + button into a photo card cannot be the system picker. Build a grid or accept a picker sheet |
| `UIPasteControl` | A button that reads the clipboard on click. Android 13+ blocks background clipboard reads, which matches the "paste only on tap" rule | Do not read the clipboard to decide whether to show Paste, or the system will toast. iOS shows Paste only when `hasImages` is already known to the app |
| SF Symbols | Material Symbols will not match. Ship a small symbol set, or draw the few custom glyphs (`DrawerGlyph`, the 5 by 5 matrix) | The drawer mark and the activity glyph are the ones users will notice |
| Dynamic Type | `sp` plus `fontScale`, with caps at roughly 1.4 for composer controls (`accessibility1`) and 1.6 for the attachment menu (`accessibility2`) | Uncapped controls overflow the 34 pt circle |
| Increase Contrast and Reduce Transparency | No single system flag maps cleanly. Read `Settings.Secure` high-contrast if present, and offer the same branches the code already has | Header text will fail contrast on a live transcript |
| Context menu plus drag on one row | A long-press that either opens a dropdown or starts a reorder after a movement threshold. Compose has no combined recognizer | Pin reorder will open the menu and never drag, which is the bug this UIKit code exists to avoid |
| `UIActivityViewController` | `Intent.ACTION_SEND` with a file URI | Export share sheet |
| Pixel-snapped hairline | Draw 1 px, not 1 dp, and round the y offset to a pixel | Rules will double on some densities, same bug the iOS comment describes |
| Diffable reconfigure vs reload | `LazyColumn` keys. Stable id must survive pending-to-delivered promotion | The bubble will flash instead of brightening in place |
| Private variable blur | Do not ship a hidden API equivalent | Review risk on iOS is called out in `VariableBlurView.swift` lines 6-10. Match the gradient instead |

`safeAreaBar` (iOS 26, used by shell output) has no Compose name. Use `WindowInsets` plus a blur bar. `docs/native-ui-guidance.md` line 51 says `safeAreaBar` for the composer is worth evaluating and is not adopted. The composer is still a `safeAreaInset`.

## Files read

- `AGENTS.md` lines 1-108, especially 16-69.
- `docs/native-ui-guidance.md` (full, 62 lines).
- `OpenCode/OpenCodeApp.swift`, `AppView.swift`, `Glass.swift`, `HeaderBlurParameters.swift`, `VariableBlurView.swift`, `SessionContextRing.swift`, `HapticsClient.swift`.
- `OpenCode/HomeView.swift`, `SessionDrawerContainer.swift`, `CatchUpStack.swift`.
- `OpenCode/SessionView.swift` (full, in sections), `ComposerTextInput.swift`, `SessionSheetView.swift`, `SettingsView.swift`, `RequestReviewView.swift`.
- `OpenCode/Transcript/` all eight files.
- `OpenCode/BackgroundWorkPanel.swift`, `ShellOutputView.swift`, `FileAttachmentViews.swift`, `PairingScanner.swift`.
- `OpenCode/WhatsNew/WhatsNewView.swift`.
- `OpenCode/SyntaxHighlighter.swift`.
- `OpenCode/OpenCodeClient.swift` lines 621-1100 for the markdown parser.
- `OpenCode/AgentFiles/AgentFileReference.swift` lines 1-254 for file-link rules.
- `OpenCode/AttachmentMenu.swift` lines 1-50 and 90-169 and 360-397 for overlay tokens the session screen hosts. The picker implementation was not read line by line.
- `OpenCode/SessionFeature.swift` and `HomeFeature.swift` only at haptic call sites.
- `OpenCode/Info.plist` font registration, `project.yml` deployment target, asset catalogs, `AppIcon.icon/icon.json`, `AppIcon.icon/Assets/logo.svg`.

## Boundaries

- Voice call UI (`OpenCode/Voice/VoiceView.swift`, `DotScopeView.swift`) is hosted by `composerCapsule` and `voiceCallSurface`. This pass did not read the scope, braid, or caption metrics. `AGENTS.md` lines 42-43 specify 28 pt corners, 6 pt text inset, 14 pt top fade, 16 pt bottom fade, and an unbouncy spring (response 0.36 damping 0.9 opening, response 0.26 damping 1 closing) in `VoiceView.swift` lines 173-176. Treat that file as a separate port slice.
- `AttachmentCameraView.swift` and `AttachmentMenu.swift` beyond the tokens above. The camera is a full-screen AVFoundation still camera. Zoom caps and lens behavior are specified in `AGENTS.md` lines 29-30 and were not re-derived from the camera source in this pass.
- `AgentFileViewer.swift` is the full-screen cover opened from links, tool rows, and sent files. Not read beyond its existence.
- `ProblemReportViews.swift`, `DiagnosticsView.swift`, and the Live Activity widget are settings and system surfaces, not the session canvas.
- Session, home, and client reducers own data, inbox delivery, and context math. This pass did not trace how `contextFraction` is computed beyond the view's thresholds (80% orange, 95% red) and the formula in `AGENTS.md` line 35.
- `FormModels.swift` owns question field types. `RequestReviewView` renders them. Validation strings come from the model, not the view.

## Non-obvious things

- The list's horizontal padding is on the hosting cell, not the row. Adding 16 again in Compose will inset code bands twice, and the bands will no longer reach the screen edge.
- Following and browsing are a policy, not `scrollToItem`. A send is just another arrival. Do not pin the new bubble to the top or reserve space for the reply.
- One frame clock drives the follower and the send flight. A Compose `Animatable` that does not share that clock will detach the flying text from the bubble.
- Arrivals never move the reader backward. Shrinking content is held with trailing space, then filled, then settled after idle. A browsing reader's position is also held when the composer shrinks, including keyboard dismissal.
- The composer `UITextView` must keep its identity when the draft clears. Replacing the field drops the keyboard and cancels the flight.
- Pending steering bubbles and delivered bubbles are the same view, dimmed by opacity, so promotion does not replace a row. Queued messages stay in the pill and lift out on delivery. Working is below both, so promotion does not reorder.
- Disclosure state is controller-owned. Cell reuse must not collapse an open tool group.
- Reconfigure is for streamed text. Reload is for a tool row that gains images, and for an idle repaint 400 ms later. Accessibility can report a hittable image while the cell still paints blank. That is an iOS hosting bug. A Compose cell should not need the idle reload, but a port that wraps views in `AndroidView` will.
- The drawer drag must not activate a row underneath it. The recognizer waits 8 pt, fails vertical, and only then cancels touches. Choosing the axis from the first velocity sample loses the touch. Simultaneous recognition makes the drawer fight the list.
- Pins use UIKit drag plus context menu on one view because SwiftUI's two recognizers cancel each other. The visible menu has no Move Up or Move Down. VoiceOver actions do.
- Glass is for controls only. Code, tables, images, and answer cards use fills and hairlines. A faint band is `primary` at 2.5% or 3.5%, not a card.
- Hairlines are one physical pixel, snapped to the pixel grid. `Divider()` and `1.dp` will be wrong.
- Berkeley Mono is onboarding only. Transcript code is the system monospaced face at subheadline (15) and footnote (13).
- The header blur on iOS 26 is the system soft edge, not `VariableBlurView`. The private filter is a fallback and a review risk. Do not port the private API.
- Simulator screenshots under-render scroll-edge blur and Liquid Glass. Text that looks readable behind the header in a capture is blurred on device (`AGENTS.md` line 48).
- Catch-up preview stores ignore every action except `firstContentFrameRendered`, so the history spacer disappears the same way as the live screen, without loading.
- Inline code that looks like a path becomes a link only under the strict `fromInlineCode` rules. `and/or`, flags, and `Type.member` stay code.
- Task list done items are secondary, including their inline code.
- Table rules are an overlay on the band, not cells in the scrolling grid, so they stay full width while columns move.
- The jump button's floating position is an overlay offset, not extra list inset. Showing it must not move the transcript.
- Send during a run is a menu button with a primary action, not a context menu. The hold has to survive transcript redraws.
- Copy, errors, and titles use the vocabulary in `AGENTS.md` lines 50-69. "Couldn't", curly apostrophes in UI copy, "session" not "chat", "Working" not "Thinking", counts as "1 subagent · 2 shells".

## Open questions

- iOS system colors in the table are Apple's defaults, not literals in this repo. Confirm against a device screenshot if a port must match within a few units, especially `separator` and the fill colors, which change with Increase Contrast.
- `VoiceView.swift` spring and fade numbers were taken from `AGENTS.md` and a grep of lines 173-176, not from a full read. A voice port should read that file before copying the capsule.
- Attachment camera control layout, lens button sizes, and focus square were not read. `AGENTS.md` lines 29-30 are the spec until that file is measured.
- `AgentFileViewer` code viewer metrics (padding, line numbers, header) were not read.
- Whether `OpenCodeWordmarkSimple` is referenced from Swift was not found in the assigned views. It is in the catalog. The connection screen uses `OpenCodeWordmark`.
- The 2.2 s "Couldn't rename" duration is specified in `AGENTS.md` line 35 and was not re-read in the feature timer during this pass.
- Compose `LazyColumn` can likely replace `UICollectionView` if scroll offset is driven from a frame clock and item keys match the stable ids in `TranscriptListViewController.applySnapshot`. That is a judgment, not something this checkout proves.
