# OpenCode Android: agent rules

A native Android port of the OpenCode iOS app (Kotlin, Jetpack Compose, Material 3 Expressive).
Lives at github.com/anomalyco/opencode-android (private). Push only when Ryan asks.
CI (`.github/workflows/android.yml`) runs `scripts/check.sh` on every PR and push; a push to
`main` only publishes the debug APK as the `main-latest` pre-release. As on iOS, releases go
through GitHub Releases only: `gh release create "v$(scripts/version.sh <sha>)" --target
"$(git rev-parse <sha>)" --generate-notes` ships to Google Play internal testing (and open
testing once `PLAY_OPEN_TESTING` is on). Release only when Ryan asks
([docs/releasing.md](docs/releasing.md)).

## The iOS app is the source of truth

- iOS repo: `/Users/vogel/dev/opencode-ios` (read-only; never modify it). Read the Swift, not
  just the docs: docs drift, the code doesn't.
- Behave exactly like iOS: features, flows, states, copy (UI vocabulary, “Couldn’t …” error copy
  with the typographic apostrophe, Title Case rules), server contract, retry policy, logging
  privacy, haptic vocabulary, timings, thresholds and constants. Port logic file by file,
  translating Swift idioms to idiomatic Kotlin; keep iOS type and function names where they
  still make sense so the two codebases diff side by side.
- Track every iOS file in `docs/parity/<area>.md` (see [docs/parity/README.md](docs/parity/README.md))
  and update the row in the same commit.
- Visuals are Android-native: Material 3 Expressive components and surfaces (Liquid Glass →
  M3 surfaces/tonal elevation, SF Symbols → Material Symbols via `OpenCodeIcons`, SF → Roboto;
  BerkeleyMono only for onboarding brand marks). No glass/blur imitation, no web views as the
  app shell, no React Native/Flutter/KMP UI.

## Modules (fixed; never edit `settings.gradle.kts`)

| Module | Ports (iOS) | Depends on |
| --- | --- | --- |
| `:app` | `OpenCode/App/` (Application, MainActivity, `AppFeature`, `AppView`, connection screen, manual server form) | everything |
| `:core` | `OpenCode/Core/` (store, dependencies, shared keys, `AppLog`, haptics, design system, motion) | — |
| `:server` | `OpenCode/Server/` + `Core/ServerID*.swift` + server-typed shared keys | `:core` |
| `:feature:media` | `OpenCode/Media/`, `OpenCode/Browser/` (in-app browser), `Core/DecodedImageCache.swift` | core, server |
| `:feature:voice` | `OpenCode/Voice/`, `Core/AudioSessionOwner.swift`, voice haptic cues | core, server |
| `:feature:system` | `OpenCode/System/`, `OpenCodeShare/`, `OpenCodeWidgets/` (Glance), `OpenCodeNotifications/` (FCM bridge behind an interface) | core, server |
| `:feature:transcript` | `OpenCode/Transcript/` | media |
| `:feature:composer` | `OpenCode/Composer/` | media, voice |
| `:feature:settings` | `OpenCode/Settings/` (incl. Onboarding, What’s New) | voice, system, devtools |
| `:feature:session` | `OpenCode/Session/` | transcript, composer, voice, media, system |
| `:feature:drawer` | `OpenCode/Drawer/` (`HomeFeature`, drawer, Catch Up) | session, settings, system |
| `:devtools` | Android-native developer tools (not a port of iOS `Tuning/`/`Diagnostics/`) | core, server |

iOS is one module, so its files reference each other freely. Here the graph is acyclic: if you
need a type from a module above you, move the type down to the lowest module that needs it
(usually `:core` or `:server`) and note it in both parity files. Never add an upward edge.
Each module's public entry point (`<Feature>`, `<Feature>.State/Action`, `<Feature>View`) is
already wired by its parent; keep those names.

## Architecture (mirrors the iOS rules in its `docs/architecture.md`)

- **Store (`ai.opencode.core.store`)** is TCA as iOS uses it: `Reducer<S, A>.reduce(state, action)
  → Reduced(state, effect)`; `Effect.none/run/send/merge/concatenate`, `.cancellable(id,
  cancelInFlight)`, `Effect.cancel(id)`, `.debounce`; `scope`, `combine`, `ifLet` (presentation,
  `PresentationAction`, cancels child effects on dismissal), `forEach`, `onChange`; `Store`,
  `store.scope`, `collectState()`/`observe {}` in Compose; exhaustive `TestStore`. State is an
  immutable `data class`, actions a `sealed interface`. Never call `reduce` recursively; use
  private helpers returning `Reduced`. Durable state, navigation and cancellable I/O live in
  reducers.
- **Plain objects** (not reducers) own per-frame motion, audio meters, scroll physics and byte
  batching (`EventBatcher`), sending reducers coarse semantic events or bounded batches.
- **Dependencies (`ai.opencode.core.dependencies`)**: every service is a client with a
  `companion object Key : DependencyKey<T>` (`liveValue`, inert `testValue`), read with
  `dependency(Key)` / `by Dependency(Key)`, overridden with `withDependencies {}` or
  `TestStore(..) { this[Key] = .. }`. No business singletons; a process-wide instance stays
  private behind its key. Hot loops resolve a client once. Time/IDs come from `Clock`,
  `DateClient`, `UuidClient`; Android `Context` from `AppContext` (live values only).
- **Shared keys (`ai.opencode.core.sharing`)**: `shared(SharedKeys.x)` → `Shared<T>`
  (`.value`, `.flow`, `.set`, `.update`). Same names and defaults as iOS `SharedKeys.swift`.
  Mutate only from reducers, only when the value changes. Secrets use `SharedKey.keychain`.
- **Server**: every request goes through `OpenCodeClient` + `NetworkRetry` (one retry layer).
  REST snapshots are authoritative; SSE invalidates and projects. See `server/` KDoc and
  `docs/parity/server.md`.
- **No side effects in composables**: clipboard, URL opening, file I/O and network go through
  actions and dependencies. Composables get immutable values and an action lambda (or a scoped
  store); list rows never hold stores.

## Logging, haptics, UI rules

- Log only through `AppLog` (`send`, `network`, `stream`, `voice`, `app`, `perf`). Log IDs,
  counts, lengths, statuses, timings. Never tokens, credentials, server addresses, pairing codes,
  message text or file contents. Never call `android.util.Log` directly.
- Haptics: one vocabulary (`Haptic.tap, selection, confirm, cancel, threshold, snap, attention,
  success, warning, error`), played through `HapticsClient` (reducers) or `HapticEffect`
  (composables). Same rules as iOS (answer the press; navigation and closing stay quiet).
- Design system (`ai.opencode.core.ui`, `ai.opencode.core.motion`; details in
  `docs/parity/core-ui.md`): wrap roots in `OpenCodeAppTheme {}`; iOS semantic colours via
  `OpenCodeTheme.colors.*`, M3 roles via `MaterialTheme.colorScheme`; iOS text styles via
  `OpenCodeTextStyles.*` or `MaterialTheme.typography`; `OpenCodeShape`/`OpenCodeRadius`/
  `OpenCodeSpacing`; iOS springs via `Motion.*.spec()` (respects reduce motion).
- Icons: `OpenCodeIcon(SFSymbol.X, contentDescription)` or `OpenCodeIcon("sf.name", …)`. To add
  one: a line in `scripts/material-symbols.txt`, run `python3 scripts/fetch-material-symbols.py`,
  add the `SFSymbol` entry and the core-ui.md row (the coordinator commits shared files).
- Buttons: `OpenCodePrimaryButton` (Next/Send), `OpenCodeSecondaryButton` (Back/Skip); red only
  for destructive roles. Touch targets ≥ 48dp (`Modifier.openCodeTapTarget`, after `clickable`),
  `OpenCodeIconButton` for icon controls.
- Unfinished features stay behind Experimental, off by default, as on iOS.

## Dependencies

All coordinates live in `gradle/libs.versions.toml`; modules reference catalog entries only.
Core set: kotlinx.coroutines, kotlinx.serialization, OkHttp (+ okhttp-sse), DataStore, Coil 3,
Compose/M3 (+ adaptive), Navigation3. Pre-declared for areas: Glance + WorkManager (system),
CameraX (composer), Media3 + androidx.browser/webkit (media), Stream WebRTC (voice; iOS uses
Google's libwebrtc), Highlights + jlatexmath (transcript; iOS's Markdown and highlighter are
self-written, so port them first and use these only where iOS used a library, e.g. SwiftMath →
jlatexmath), Firebase Messaging (push; keep it behind an interface until credentials exist).
Add a new library only with a one-line justification here.
- `com.google.zxing:core` (settings): decodes the pairing QR from CameraX frames, as iOS's
  AVFoundation QR metadata output does; pure Java, no Play services (ML Kit would need them).
- `androidx.media3:media3-exoplayer` (voice): plays an agent's audio on a call (iOS `CallAudioPlayer` uses AVAudioPlayerNode on the call's engine); ExoPlayer streams and decodes what MediaPlayer can't, on the call's voice-communication attributes.
- `androidx.profileinstaller:profileinstaller` (app): installs the packaged app and library Baseline Profiles for ART compilation, including sideloaded builds.

## Check and run

- One check: `scripts/check.sh` (assembleDebug + lintDebug + testDebugUnitTest for every module;
  `scripts/check.sh :feature:composer` for one module). Gradle memory is capped in
  `gradle.properties` because ~10 agents build concurrently; don't raise it, don't run
  `./gradlew clean` on the whole project.
- JDK 17: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`. SDK: `~/Library/Android/sdk`.
- Emulator: AVD `OpenCode_API_36` (Pixel 8, API 36). `scripts/emu.sh boot [port]` (headless,
  read-only, so several agents can each run one on their own even port), `scripts/emu.sh
  install [port]`, `scripts/emu.sh shot out.png [port]`, `scripts/emu.sh kill [port]`. Never use
  or modify `Rebase_API_36`.
- Fixture server: `python3 /Users/vogel/dev/opencode-ios/scripts/session-lab.py serve` (REST/SSE
  on 127.0.0.1:4110, control page at http://127.0.0.1:4110, see its `scripts/SESSION_LAB.md`).
  From the emulator connect to `http://10.0.2.2:4110` via Enter a Server Manually (cleartext
  is allowed for 10.0.2.2/localhost in debug builds only). Stop servers you start.

## Commits

- Commit every completed, verified change on `main`, one coherent change per commit. The
  message says what changed, why, and what was verified (commands run, emulator checks); call
  out removed or replaced behaviour.
- In this shared checkout stage only the files you own (`git add <paths>`), never `git add -A`.
  Don't amend, rebase, stash, or reset other agents' work.
- Never commit secrets, `local.properties`, `google-services.json`, keystores, or private
  screenshots/logs.

## Tests

- Port the iOS unit tests (`/Users/vogel/dev/opencode-ios/OpenCodeTests/`) into each module's
  `src/test` (JVM, exhaustive `TestStore` like iOS's `TestStore`), keeping iOS test names so the
  suites diff side by side. A ported test that fails is a parity bug: fix the code, not the test,
  unless the iOS behaviour itself can't exist on Android (then delete it and note why in the
  parity row).
- Other new test files (beyond ports of iOS tests) still need the coordinator's approval.
- Isolation (iOS `TestIsolationGuard`): under JVM unit tests `DependencyValues.root` resolves
  `testValue`s, so even code outside a `TestStore` never reaches live storage or the network.
  Shared test support lives in Gradle test fixtures; feature modules get it through the
  convention plugin, `:core`/`:server` through their own `testFixtures`:
  - `ai.opencode.core.testing` (`:core` fixtures): `TestIsolationRule` (`@get:Rule val
    isolation = TestIsolationRule()`) fails a test that leaks process-wide state
    (`AnimationTimeScale.shared`, `TuningStore.shared`, shared keys written through the root,
    open `WireFixture`s, blocked network) and resets it; `TestIsolationGuard` (probes,
    `capturingViolations`, `Plugin`s via `META-INF/services`); `eventually { }`.
  - `ai.opencode.server.testing` (`:server` fixtures): `WireFixture` (iOS `WireFixture`: scripted
    replies for a unique `wire-<uuid>.invalid` host, captured requests). Every
    `OpenCodeTransport.testValue` pool runs behind `TestNetworkBlock`: fixture hosts are answered,
    loopback (`MockWebServer`) passes, anything else fails as an unreachable host and is reported.
  - Requests are sent once under tests (`NetworkRetry`) unless a test sets a policy.
  - Opt-in live checks (iOS `liveServerReadContract`) run only when `OPENCODE_LIVE_SERVER`,
    `OPENCODE_LIVE_PASSWORD`, `OPENCODE_LIVE_SESSION_ID` and `OPENCODE_LIVE_DIRECTORY` are set
    (e.g. against session-lab); otherwise they're skipped, so `scripts/check.sh` stays offline.
