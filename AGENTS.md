# Project guidance

- This is the native Android port of the OpenCode iOS app (github.com/anomalyco/opencode-ios), in Kotlin and Jetpack Compose. The iOS app is the product spec. Keep its UI, UX, copy, and behavior, adapted to Android. Do not redesign, and do not introduce React Native, Flutter, or a web view shell.
- Mirror the iOS code file for file. `docs/port/architecture.md` has the path, naming, and Swift-to-Kotlin rules. Every ported Kotlin file starts with `@file:PortedFrom("<ios path>", "<upstream commit>")`.
- Porting new iOS commits follows the `port-ios-changes` skill (`.agents/skills/port-ios-changes/SKILL.md`). `python3 scripts/ios-sync.py check` must pass before you push.
- `.upstream/opencode-ios` is a read-only checkout managed by `scripts/ios-sync.py`. Never edit it.
- The iOS `AGENTS.md` (Native UI Style, UI Vocabulary) applies here, translated by the system layer in `app/src/main/java/ai/opencode/mobilecode/system/`. Use its colors, text styles, `SystemImage`, glass, sheets, menus, and haptics instead of raw Material defaults. App-specific surfaces keep the iOS look. System chrome (dialogs, menus, sheets, back gesture, keyboard) uses Android widgets with the same content and order.

## Build and test

- `./gradlew assembleDebug testDebugUnitTest` builds and runs the JVM tests.
- Views are verified with Roborazzi screenshots (`docs/port/screenshots.md`) against the iOS captures in `docs/port/reference/ios/`. Refresh those with `scripts/capture-ios-reference.sh`.
- The emulator is `medium_phone` (API 36). Start it with `android emulator start medium_phone`, install with `android run --apks=app/build/outputs/apk/debug/app-debug.apk`, and capture with `android screen capture`.
- An emulator reaches a server on the host at `10.0.2.2`, or at `127.0.0.1` after `adb reverse tcp:<port> tcp:<port>`.
- Never commit server passwords, API keys, or `local.properties`.

## Port records

- `docs/port/PLAN.md` is the plan, `docs/port/decisions.tsv` the decision log, and `docs/port/sync.json` the upstream commit plus what is deliberately left out: deferred files, partly ported files, and skipped commits, each with its reason.
- `docs/port/ios-architecture.md` and `docs/port/explore/*.md` describe the iOS app for porting.
