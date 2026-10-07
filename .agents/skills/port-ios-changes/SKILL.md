---
name: port-ios-changes
description: Port new commits from the OpenCode iOS app (github.com/anomalyco/opencode-ios) into this Android app. Use when asked to sync with iOS, catch up with upstream, port an iOS commit, feature, or fix, or when scripts/ios-sync.py reports unported upstream commits.
---

# Port iOS changes

The Android app mirrors the iOS app file for file and line for line. Read `docs/port/architecture.md` before editing Kotlin: it has the path, naming, and Swift-to-Kotlin translation rules.

1. Run `python3 scripts/ios-sync.py status`. It fetches upstream into `.upstream/opencode-ios` and lists the commits after the synced commit, with the Kotlin file each changed iOS file maps to.
2. Run `python3 scripts/ios-sync.py plan`. It writes `docs/port/sync/plan-<from>-<to>.md`, one checklist per commit, oldest first. Work through it in order.
3. For each port item, read the diff with `git -C .upstream/opencode-ios show <commit> -- <path>` and make the same change at the matching place in the mapped Kotlin file.
   - A new iOS file gets a Kotlin file at the mapped path, starting with `@file:PortedFrom("<ios path>", "<commit>")`.
   - A deleted iOS file means deleting or re-homing its Kotlin file.
   - Port test diffs too. `OpenCodeTests/` files map to `app/src/test/`.
   - If a change has no Android equivalent, record it under `deferred` in `docs/port/sync.json` with the reason.
4. For each review item (`AGENTS.md`, `Info.plist`, entitlements, `docs/`, UI tests, fixtures), carry product and UI rule changes into this repo's `AGENTS.md`, the code, or the manifest.
5. Set the `@file:PortedFrom` commit of every file you brought in line to the target commit. Leave files you did not touch alone.
6. Verify with `./gradlew assembleDebug testDebugUnitTest`. For UI changes, record the affected views with the Roborazzi harness (`docs/port/screenshots.md`), refresh the iOS baseline with `scripts/capture-ios-reference.sh`, and compare the two.
7. Run `python3 scripts/ios-sync.py advance <commit>`, then `python3 scripts/ios-sync.py check`. `advance` refuses while any changed file is still stale or unported.
8. Commit one Android commit per iOS commit, titled `port: <iOS subject> (<iOS short sha>)`.
