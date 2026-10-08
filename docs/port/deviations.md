# Deviations from iOS

These are Android behaviors that differ from the iOS app on purpose. When you port an iOS change to a file listed here, keep the deviation. The parity review should treat these differences as expected.

## Justified assistant prose

Assistant replies and compaction summaries set their paragraphs, list items, and blockquotes justified. iOS sets them ragged right. On 2026-10-07 the user asked for paragraph justification that matches the [justice](https://github.com/kitlangton/justice) library, because the ragged paragraphs looked uneven.

- `system/Justice.kt` ports the library's engine (`src/engine.ts` at 86dbc6d). `JusticeGoldenTests` checks it against the TypeScript engine's layouts in `app/src/test/resources/justice/golden.json`. To regenerate that file, clone the library into `.justice-reference` and run `bun scripts/justice-golden.ts`.
- `system/JustifiedText.kt` draws it in Compose. The text keeps its characters, so selection, copying, links, and accessibility read the source.
- `MarkdownBlockView.kt` passes `justified = true` to `ArrivingText` for paragraphs, list items, and blockquotes. When you sync `MarkdownBlockView.swift`, keep those three arguments. Headings, user bubbles, code, tables, and tool rows match iOS.
- The blockquote bar is drawn behind the text instead of sized with `IntrinsicSize.Min`. Justified text measures through `BoxWithConstraints`, which has no intrinsic size. The bar keeps its iOS geometry.

The rendering differs from the library in these ways:

- Lines break only between words. Android's line breaker never breaks right after a hyphen in mid-line, so the library's breaks after source hyphens ("well-known") are not used. The engine keeps them, and the golden test covers them. No hyphenation dictionary ships, so `withHyphenation` is not ported.
- A line that would need gaps wider than 3.5 spaces keeps its break but is set ragged. That is the library's own test bound for whole-word mobile layouts. It applies before a word too long to share a line, such as an inline path in a narrow list item.
- While a reply streams in, lines above the last two keep their breaks, so new tokens do not move text the user is reading. A paragraph that is drawn again later solves from scratch and can break differently.
- An opening quote hangs about half as far as the library places it on Android 14 and earlier, and not at all on Android 15 and later, which draw only the half of a line's first letter spacing that falls inside the line. Compose letter spacing cannot move one glyph left of the line start. From the line's first gap on, the text sits where the engine put it.

## Shared locations

The + menu's Location (upstream 083cd94) sends the same `<shared-location>` block as iOS, so either app reads a location the other sent.

- The card draws no map. iOS renders a MapKit snapshot. Android has no system map renderer, and a map SDK needs an API key the app does not have, so the card keeps the placeholder iOS shows until its snapshot loads: the fill and the red pin.
- A tap opens the phone's maps app through a `geo:` link, and the Apple Maps link only when nothing handles `geo:`. The block's `Map:` line stays the Apple Maps link.
- The address comes from Android's `Geocoder` without the country, and `Address.featureName` stands in for MapKit's place name. A phone without a geocoder shares the coordinates alone.

## Features not on Android yet

- **Turn-finished notifications.** iOS sends them through a notify plugin on the server and an APNs relay (upstream d0ed2a9 and neighbors). Android would need an FCM relay that the server side does not have, so `SessionNotifications.swift` is deferred and Settings has no Notifications row. The iOS baseline at d809b1e shows that row; the parity review should expect its absence.
- **iOS-only lab and UI-test bridges.** The lab pasteboard bridge and lab color scheme in `OpenCodeApp.swift` have Android equivalents as debug intent extras instead.
