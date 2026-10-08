# Glass look: frosted glass tuned to the iOS 27 captures

Android glass stays a Haze blur. `system/Glass.kt` now paints iOS 27 regular glass over it: a near-opaque veil, a hairline that darkens the sides in light, a specular line and a glow on the top and bottom edges, and a faint shadow in light. A second candidate refracted the backdrop near the rim like Liquid Glass does. At a glance it matched the iOS captures no better, and it ran a little slower at p90, so it was dropped.

## Measured against the iOS captures

The numbers are 0 to 255 gray, read from the 35, 07, and 27 captures in `docs/port/reference/ios` and from the same states on `emulator-5554` (medium_phone, 1080 by 2400). Each value is the minimum to maximum of one probe box, or its mean after a tilde.

| Probe | iOS light | Android light before | Android light after | iOS dark | Android dark before | Android dark after |
| --- | --- | --- | --- | --- | --- | --- |
| Composer fill over white or black | 253 | 248 to 255 | 251 | 32 to 33 | 36 to 45 | 31 |
| Side rim, darkest or brightest pixel | 176 | 253, no rim | 173 | 31, no rim | 98 | 31, no rim |
| Top rim | 231 | 253 | 228 | 90 | 126 | 81 |
| Shadow just below the composer | 252 | 255 | 249 to 252 | none | none | none |
| Transcript text under the composer | 245 to 253 | 164 to 252 | 247 to 250 | 33 to 53 | 41 to 165 | 35 to 43 |
| Background-work panel between rows | 244 to 246 | 157 to 236 | 250 to 251 | 33 to 36 | 37 to 126 | 30 to 31 |

Before this change the transcript read through every control and through the background-work panel. `hazeBlur` drew its blurred copy over a transparent background, so the sharp text under it stayed visible, and the white veil was only 28% to 34% opaque. The blur now composites over `systemBackground`, and the veil turns from the control veil to an 88% panel veil as a surface grows from 64 dp to 176 dp, so a panel diffuses the transcript fully.

Dark glass lifts what is under it instead of covering it, which is what makes the Copy Error Report capsule and a sheet's Done capsule read lighter than the card or sheet below them on iOS. Glass inside a `SystemSheet` no longer samples the session behind the sheet. `GlassWindow` clears the backdrop there, so the capsule lifts the sheet itself.

## The two candidates

- **A, tuned frosted glass.** The shipped Haze blur with an opaque background, the veil, rim, specular line, glow, and shadow above, all drawn on every API level.
- **B, lens glass.** Kyant0/AndroidLiquidGlass (`io.github.kyant0:backdrop:2.0.1`, Apache-2.0) on API 33 and later: a 6 dp blur, `lens` with a 12 dp band and 16 dp depth, and an AGSL veil that thins to 70% at the rim so the bent content shows. API 31 and 32 used A. B kept A's rim, glow, and shadow, so the two differed only in how they sampled the backdrop.

Over white the two were identical, because refraction has nothing to bend. Over transcript text, B drew blurred, bent text along the composer's top edge. The iOS captures show faint, evenly ghosted text there and no visible refraction, so B moved further from the reference in exactly the place it was meant to help.

## Frame timing

Each run streams a scripted reply for 20 s in `SessionLabActivity` on the `benchmark` build and reads `dumpsys gfxinfo` for the stream. Runs alternate between the sides. `emulator-5554` ran with `-gpu host` on an Apple M4. Two other emulators and an iOS simulator shared the host, so its load average swung from 3.6 to 21.9.

Lens against frosted, five runs per side, dark appearance, `--compare-mode --mode lens`:

| | Janky frames | p50 | p90 | p99 | Frames | GPU p90 |
| --- | --- | --- | --- | --- | --- | --- |
| A, frosted | 0.08% (0 to 4.17) | 23 ms (22 to 29) | 25 ms (25 to 44) | 31 ms (29 to 81) | 1280 (1200 to 1288) | 22 ms (21 to 23) |
| B, lens | 0.08% (0 to 8.47) | 23 ms (23 to 34) | 27 ms (26 to 48) | 32 ms (29 to 89) | 1288 (1122 to 1291) | 21 ms (20 to 24) |

The first pair ran at a load of about 20, and both sides jumped there. In the four calmer pairs, B's p90 was 1 to 3 ms above A's every time, and janky frames stayed at or below 0.16% on both sides. That gap is about as wide as the spread inside each side, so B's cost is small, but it never once came out ahead.

The shipped glass at `caf1af9` against the chosen glass, five runs per side, alternated by reinstalling the APK before each run:

| | Janky frames | p50 | p90 | p99 | GPU p90 |
| --- | --- | --- | --- | --- | --- |
| Shipped, dark | 0.39% (0.08 to 3.06) | 23 ms (22 to 27) | 26 ms (24 to 40) | 32 ms (29 to 69) | 21 ms (20 to 23) |
| Chosen, dark | 0.15% (0.08 to 0.23) | 23 ms (22 to 23) | 26 ms (24 to 27) | 31 ms (29 to 32) | 21 ms (20 to 21) |
| Shipped, light | 0.32% (0.08 to 6.63) | 23 ms (22 to 31) | 27 ms (25 to 46) | 34 ms (29 to 89) | 22 ms (21 to 25) |
| Chosen, light | 0.55% (0.08 to 3.46) | 24 ms (23 to 26) | 28 ms (25 to 38) | 48 ms (30 to 69) | 22 ms (21 to 23) |

Every range overlaps, and no side won every pair, so the chosen glass costs nothing measurable here. Only light mode draws the drop shadow. The display paces these frames, as `glass-blur.md` found, so this host cannot resolve a GPU cost of a few milliseconds. No phone was measured. The frosted build in the lens comparison differs from the committed one only in the shadow's radius and alpha.

The raw runs are in `glass-look-lens-host-gpu.jsonl` and `glass-look-shipped-host-gpu.jsonl`. In the second file, `side` names the build and `appearance` the theme.

## What still differs from iOS

- iOS light glass shades its fill from 249 near the edges to 253 in the middle. Android paints one flat 251.
- iOS refracts the backdrop at the rim on a device. Android does not, and the simulator captures barely show it.
- Interactive glass does not respond to a press. No caller passes `interactionSource`.
- The shadow does not grow with the surface. The iOS background-work panel casts a wider one.
- The header has no scroll-edge blur. `SessionHeaderBackdrop` stays the opaque fade iOS draws under Reduce Transparency.
- Robolectric does not run Haze's blur, so the Roborazzi snapshots show the panel veil over sharp text.
