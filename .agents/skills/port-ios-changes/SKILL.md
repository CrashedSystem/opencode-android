---
name: port-ios-changes
description: Port new commits from the OpenCode iOS app (github.com/anomalyco/opencode-ios) into this Android app. Use when asked to sync with iOS, catch up with upstream, port an iOS commit, feature, or fix, or when scripts/ios-sync.py reports unported upstream commits.
---

# Port iOS changes

The Android app mirrors the iOS app file for file and line for line. Read `docs/port/architecture.md` before editing Kotlin: it has the path, naming, and Swift-to-Kotlin translation rules. `docs/port/deviations.md` lists the Android differences to keep when an iOS diff touches the same code.

1. Run `python3 scripts/ios-sync.py status`. It fetches upstream into `.upstream/opencode-ios` and lists the commits after the synced commit, with the Kotlin file each changed iOS file maps to.
2. Run `python3 scripts/ios-sync.py plan`. It writes `docs/port/sync/plan-<from>-<to>.md`, one checklist per commit, oldest first. Its head is the sync's target: upstream keeps moving, and commits that land during the run belong to the next sync.
3. Port the commits oldest first, one Android commit per iOS commit titled `port: <iOS subject> (<iOS short sha>)`. Combine only trivially small neighbors, and list every SHA in the title. For each port item, read the diff with `git -C .upstream/opencode-ios show <commit> -- <path>` and make the same change at the matching place in the mapped Kotlin file.
   - A new iOS file gets a Kotlin file at the mapped path, starting with `@file:PortedFrom("<ios path>", "<commit>")`.
   - A deleted iOS file means deleting or re-homing its Kotlin file.
   - Port test diffs too. `OpenCodeTests/` files map to `app/src/test/`.
   - A hunk with no Android equivalent (UIKit plumbing, an iOS-only API) gets no code. Say so in the port commit's body.
   - Code inside a ported file that serves a deferred feature (voice plumbing in `SessionFeature`) is ported with the file. Add what it needs to the deferred feature's placeholder, and name the additions in the placeholder's `partial` note.
   - A commit that leaves the iOS tree unbuildable on its own (e686e59 deleted `ProblemReportsFeature.swift` while `HomeFeature.swift` still scoped it) lands with the commit that finishes the change. Name both SHAs in that title, and say why in the earlier commit's body.
4. Record what you leave out in `docs/port/sync.json`, always with a reason:
   - `deferred`: an iOS file that is not tracked. A partial claim on it is a placeholder for callers and stays pinned at the commit it was carved from.
   - `partial`: a file that is tracked but ported in part, with why the rest waits. Its claim is stale-checked like a full one. An entry here also tracks a file that a `deferred` folder covers (`VoiceTranscript.swift`, whose `VoiceHandoff` renders Voice messages).
   - `skipped`: an upstream commit deliberately not ported, such as a feature with no Android delivery path yet. A claim is never stale for a skipped commit's changes.
   - `ignore` or `review`: a new top-level path the plan lists under categorize.
5. For each review item (`AGENTS.md`, `Info.plist`, entitlements, `docs/`, UI tests, fixtures), carry product and UI rule changes into this repo's `AGENTS.md`, the code, or the manifest. The iOS `AGENTS.md` applies by reference, so only conflicts need a change here. The capture scripts run the fixtures from the upstream checkout, so fixture changes arrive on their own.
6. After porting each commit, run `python3 scripts/ios-sync.py mark <commit> <ios or kotlin paths>`. It sets the `@file:PortedFrom` commit of those files to that commit; with no paths it marks every port file the commit changed. Mark only files you brought in line. `check` accepts a marker ahead of the synced commit, so run it any time.
7. Verify with `./gradlew assembleDebug testDebugUnitTest` every few commits. For a UI change, render the affected view with Roborazzi (`./gradlew recordRoborazziDebug --tests '<test>'`; `testDebugUnitTest` writes no images, see `docs/port/screenshots.md`), look at it, and compare it with `docs/port/reference/ios/` when that state exists. Commit re-recorded snapshots with the change that moved them. Refresh the iOS baseline with `scripts/capture-ios-reference.sh` once per sync, not per commit.
8. Run `python3 scripts/ios-sync.py advance <plan head>`, then `python3 scripts/ios-sync.py check`. `advance` refuses while any changed file is still stale or unported. When it refuses only for files another worker is porting, leave the synced commit where it is and report what it listed: `check` accepts markers ahead of the synced commit and skips files the range deleted.
