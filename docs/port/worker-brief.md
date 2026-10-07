# Porting worker brief

Every porting worker follows this brief plus its own scope. If `docs/port/architecture.md` exists in your worktree, it is the authority and overrides anything here.

## Goal

The Android app keeps the iOS UI, UX, copy, and behavior, adapted to Android, and it must be fast. Later iOS commits must port line by line, so the Kotlin mirrors the Swift file for file and declaration for declaration.

## Rules

- `OpenCode/<rel>.swift` maps to `app/src/main/java/ai/opencode/mobilecode/<rel>.kt`, and `OpenCodeTests/<rel>.swift` to `app/src/test/java/ai/opencode/mobilecode/<rel>.kt`. All product code is in the one flat package `ai.opencode.mobilecode`.
- Keep Swift type, nested type, property, function, parameter, and enum case names verbatim, including lower-camel enum cases. Keep declaration order.
- The first line of each ported file is `@file:PortedFrom("<ios path>", "<upstream commit>")`. If you port only part of an iOS file, add `partial = "<what is ported>"` and say so in your report. Never mark a partial port as complete.
- `.upstream/opencode-ios` is read-only. The upstream commit is in `docs/port/sync.json`.
- Use the system layer in `app/src/main/java/ai/opencode/mobilecode/system/` (`SystemColors`, `TextStyles`, `SystemImage` for SF Symbols, `glassEffect`, sheets, menus, haptics) so the Compose reads like the SwiftUI. Do not use raw Material colors or typography on app surfaces.
- Existing ported code lives in the same package. Read it before you add a type: `OpenCodeClient.kt` already holds the wire models, `OpenCodeMarkdownBlock`, and the client. Extend files your scope owns; do not edit files outside it except to fix a compile error you report.
- Stand-ins for Apple framework types (Foundation, SwiftUI, UIKit) live only in the system layer. Search it before writing one. If one you need is missing, list it in your report instead of defining a private copy: two workers once defined conflicting `AttributedString`s in parallel.
- Never put test-only logic in a fixture that the product path lacks. A fixture that parses markdown itself hides a missing product parser.
- Port the iOS tests that cover your files. A ported test reads like the Swift test and asserts the same literals.
- Performance is part of the spec. Stable keys, no work in composition that iOS caches, no per-frame allocation in hot paths.

## Verify before reporting

- `./gradlew assembleDebug testDebugUnitTest` passes in your worktree.
- Views are rendered with the Roborazzi harness (`docs/port/screenshots.md`) in light and dark and compared by eye with the matching iOS captures in `docs/port/reference/ios/` (see `MANIFEST.md`). Look at the PNGs yourself and list visible differences.
- `python3 scripts/ios-sync.py progress` shows your files as ported or partial as intended.

## Report

Commit in small conventional commits on your branch. Your final message is at most 15 lines: commits, Swift and Kotlin line counts, tests, screenshots compared with the differences you saw, partial ports, and anything blocked.
