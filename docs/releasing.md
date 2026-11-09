# Releasing

Mirrors iOS (`opencode-ios/docs/releasing.md`): releases go through GitHub Releases only.
Pushing `main` doesn't release to Play. Push and release only when Ryan asks or approves.

## Cutting a release

From a green `main` commit:

```sh
gh release create "v$(scripts/version.sh <sha>)" --target "$(git rev-parse <sha>)" --generate-notes
```

(`--target` needs the full SHA.) The `v*` tag starts `.github/workflows/android.yml`, which,
after `scripts/check.sh` passes:

1. **Google Play internal testing.** Builds `:app:bundleRelease` signed with the Play upload key
   and uploads it to the internal testing track (`play` job), released to every tester at
   once. Testers get it as a normal Play update.
2. **Google Play open testing** (the counterpart of iOS's External TestFlight group), only when
   the `PLAY_OPEN_TESTING` repo variable is `true`. The same `play` job then puts that bundle
   on the open testing (`beta`) track with `scripts/play-promote.sh`; it doesn't upload it a
   second time. Otherwise it skips with a notice.

Report the tag, the workflow run and the Play version code.

## What a push to main does

Runs `scripts/check.sh` and publishes the debug APK (`ai.opencode.mobilecode.debug`, installs
beside the Play build) as the rolling `main-latest` GitHub pre-release for sideloading. Nothing
goes to Play.

## Versions

`scripts/version.sh` gives every first-parent commit on `main` its own version from git history,
with the same rules as iOS (patch per commit, minor for 400+ changed lines, major only for a
`type!:` or `BREAKING CHANGE` marker) from this repo's baseline (`acc23eab` = 0.1.0). A release
build's `versionName` is its tag without the `v`; other builds are `0.1.0-<short sha>`.
`versionCode` is the workflow run number, so it only goes up (Play rejects a code it has seen).
Never bump either by hand. A re-run keeps its number and Play refuses the duplicate: cut the
release from a new commit instead.

## Google Play

- Account Anomaly Innovations, Inc. (`5599481792709483137`), app **OpenCode**
  `ai.opencode.mobilecode` (same ID as iOS), Play Console app id `4973267939382667246`.
- Play App Signing holds the app signing key. CI signs uploads with the **upload key**, kept
  only in repo secrets (`ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`,
  `ANDROID_UPLOAD_KEY_ALIAS`). If it's lost or leaks, request an upload key reset in Play Console
  (App integrity → App signing) with a new key's certificate; nothing else changes.
- The Play upload uses `PLAY_SERVICE_ACCOUNT_JSON`: a key for
  `play-publisher@opencode-android-play.iam.gserviceaccount.com` (Google Cloud project
  `opencode-android-play` in the anoma.ly org), invited in Play Console with only *View app
  information* and *Release apps to testing tracks* on OpenCode, so it can't publish to
  production. Rotate it with `gcloud iam service-accounts keys create` and `gh secret set`, then
  delete the old key. Without the secret the `play` job skips with a notice and the bundle stays
  a workflow artifact (`opencode-android-aab-<sha>`).
- Internal testing: email list **Anomaly team** (everyone on Anomaly's Slack, up to 100).
  Testers opt in once at https://play.google.com/apps/internaltest/4698009188042349382, then
  install OpenCode from Play. Add people by editing that list in Play Console (Testing →
  Internal testing → Testers).
- Internal testing has no Google review. Closed or open testing (like iOS's External group)
  needs the store listing, content rating, data safety form and privacy policy first.
- Open testing: in Play Console (Testing → Open testing) choose the countries and set up the
  testers page, finish the items above, then `gh variable set PLAY_OPEN_TESTING --body true`.
  Unset it (`gh variable delete PLAY_OPEN_TESTING`) to stop. Google reviews open testing
  releases, so a build can take a while to reach testers; a newer release replaces one still in
  review. Anyone opts in at https://play.google.com/apps/testing/ai.opencode.mobilecode.
- Until Google has reviewed the app once, Play only accepts draft releases outside internal
  testing ("Only releases with status draft may be created on draft app"). `play-promote.sh`
  then leaves the build as the open testing track's draft and the job warns: send it for review
  in Play Console (Publishing overview → Send changes for review). v0.2.2 (33) was the first,
  sent for review on 2026-11-08.
