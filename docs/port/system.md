# System layer

Ported Compose views should read like the SwiftUI they came from. `SystemColors.secondaryLabel`, `TextStyles.headline`, `SystemImage("gearshape.fill")`, and `Modifier.glassEffect()` are the iOS names. App surfaces keep that look. System chrome does not.

## What stays iOS

Labels, backgrounds, fills, separators, and system tints are Apple's light and dark values, resolved from the current appearance. SwiftUI `.primary` is `label`. `.secondary` is `secondaryLabel`. `.tint` is `systemBlue`. Text styles are Dynamic Type at the Large size, in sp, on the platform font. Code uses `FontFamily.Monospace`. Berkeley Mono is only for onboarding marks.

`SystemImage` takes the SF Symbol string the Swift used. `scripts/gen-symbols.py` reads `docs/port/symbols.json` and writes a vector drawable per name from the official Material Symbols Rounded SVGs. Rerun the script when the table changes. Do not edit `SymbolNames.kt` or the `sf_*.xml` files by hand.

Glass is backdrop blur plus a tint and a rim, tuned toward iOS 26 controls rather than a Material surface. The screen root calls `GlassBackdrop`. The `content` lambda is the backdrop. Glass controls go in `overlay`, and they only add `Modifier.glassEffect()`. Haze blurs a sibling of the source, not a child of it. Capsule, circle, and rounded panel are the shapes the app already uses. Elsewhere, `RoundedRectangle(cornerRadius)` is SwiftUI's `.rect(cornerRadius:)`, with the continuous corners iOS draws. Compose's `RoundedCornerShape` draws circular ones.

Lists and navigation keep SwiftUI's shape. `GroupedList`, `Section`, and `ForEach` are the inset grouped `List`, and `Modifier.listRowSeparatorLeading()` marks where a row's separator starts. `NavigationStack` draws the bar that `NavigationTitle` and `ToolbarItem` declare anywhere in its root, with toolbar items in glass capsules. `SystemButton` is a text `Button`. `Modifier.symbolSize` sizes a `SystemImage` like an SF Symbol at a point size or in a text style.

## What stays Android

Dialogs, menus, sheets, and swipe-to-delete are Material 3 widgets. The wrapper keeps the iOS content and order. It does not draw an iOS menu or an iOS sheet.

`SystemSheet` offers the medium and large detents. Drag between them is the platform sheet. Its surface stands in for iOS 26 glass, the grouped background in light and the elevated background in dark, under an iOS-sized grabber. It provides `LocalDismiss`, SwiftUI's `@Environment(\.dismiss)`, which animates the sheet away. `SystemMenu` and `SystemContextMenu` take sections. A destructive item uses `systemRed`. `SystemAlert` and `SystemConfirmation` are the platform dialog. The confirm action sits in Android's confirm slot, not in an iOS action-sheet stack. `SwipeToDeleteRow` is the platform trailing swipe. It dismisses the row. iOS session rows only reveal Delete. That reveal policy belongs to the session row, not to this wrapper.

Haptics follow `HapticsClient`: selection, light impact, success, error, and the six voice kinds. They play through `VibrationEffect` while the lifecycle is resumed.

The keyboard is `adjustResize`. Back dismisses a sheet or dialog through the widget's own dismiss callback. There is no custom back gesture.

## Shape that lost

Mapping these tokens onto `MaterialTheme.colorScheme` would hide the Swift names. A later iOS diff would not land line by line. XML night colors would also drop the call-site names, and they have no contrast qualifier. The public values stay composable properties over one light table and one dark table.

## Tradeoffs

Robolectric does not render the Haze blur. The catalog PNG shows the shape, tint, and rim. Blur is checked on a device.

Eight SF Symbols have no close Material icon. The table still points them at the nearest glyph so a call site is not blank. See the `match` field in `docs/port/symbols.json`.

`confirmValueChange` on the swipe box is deprecated. The wrapper still uses it so a partial swipe does not delete. A later pass can move that veto into the anchor set.
