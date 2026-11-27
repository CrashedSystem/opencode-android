#!/usr/bin/env bash
# Merges anomalyco/opencode-android's dev into this fork, waits for CI, installs the APK.
#   scripts/follow-upstream.sh            # merge, push, wait, install
#   scripts/follow-upstream.sh --dry-run  # report only, change nothing
#
# Exits non-zero when the merge conflicts or CI fails, so the caller reports it instead of
# installing a broken build.
set -uo pipefail
cd "$(dirname "$0")/.."

REPO=CrashedSystem/opencode-android
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1
PKG=ai.opencode.mobilecode.debug
APK_DIR=$HOME/tmp

step() { printf '\n== %s\n' "$1"; }

step "upstream"
git fetch --quiet https://github.com/anomalyco/opencode-android.git dev || { echo "fetch failed"; exit 2; }
behind=$(git rev-list --count HEAD..FETCH_HEAD)
echo "commits behind: $behind"
if [ "$behind" = "0" ]; then
  echo "nothing to merge"
  exit 0
fi
git log --oneline HEAD..FETCH_HEAD | head -30

[ "$DRY_RUN" = "1" ] && { echo "dry run: stopping before the merge"; exit 0; }

step "merge"
if [ -n "$(git status --porcelain)" ]; then
  echo "working tree is not clean; refusing to merge"
  git status --short | head -10
  exit 2
fi
if ! git -c user.name=CrashedSystem -c user.email=wonjin03101@gmail.com merge --no-edit FETCH_HEAD; then
  echo "CONFLICT: resolve by hand"
  git diff --name-only --diff-filter=U
  exit 3
fi

step "fork features"
missing=0
check() { if grep -rq -- "$2" $3 2>/dev/null; then echo "ok    $1"; else echo "GONE  $1"; missing=$((missing+1)); fi }
check "fixed debug signing"    "forkDebug"                  app/build.gradle.kts
check "own relay host"         "indev.asuscomm.com"         feature/system/src/main/kotlin
check "session vibration"      "setVibrationPattern"        feature/system/src/main/kotlin
check "firebase resources"     "google_app_id"              app/src/main/res/values/strings.xml
check "english+korean only"    'listOf("en", "ko")'         app/build.gradle.kts
check "reasoning shown"        "AssistantReasoning"         server/src/main/kotlin
check "reasoning toggle"       "showReasoning"              core/src/main/kotlin
check "project groups"         "collapsedProjectIDs"        feature/drawer/src/main/kotlin
check "group show more"        "GROUP_ROWS_SHOWN"           feature/drawer/src/main/kotlin
[ "$missing" -gt 0 ] && echo "WARNING: $missing fork feature(s) missing after the merge"

step "translations"
python3 scripts/translations.py all 2>&1 | tail -2

step "push"
if ! git push origin dev 2>&1 | tail -2; then
  echo "push failed"
  exit 4
fi
sha=$(git rev-parse --short=7 HEAD)
echo "pushed $sha"

step "CI"
run=""
for _ in $(seq 1 20); do
  run=$(gh api "repos/$REPO/actions/runs?per_page=5" \
    --jq "[.workflow_runs[] | select(.head_sha | startswith(\"$sha\"))][0].id" 2>/dev/null)
  [ -n "$run" ] && [ "$run" != "null" ] && break
  sleep 20
done
if [ -z "$run" ] || [ "$run" = "null" ]; then
  echo "no CI run started for $sha (Actions disabled?)"
  exit 5
fi
echo "run $run"
for _ in $(seq 1 60); do
  state=$(gh api "repos/$REPO/actions/runs/$run" --jq '.status+" "+(.conclusion//"-")')
  case "$state" in
    completed\ success) echo "CI passed"; break ;;
    completed*) echo "CI $state"; exit 6 ;;
  esac
  sleep 45
done

step "install"
mkdir -p "$APK_DIR"
apk=$APK_DIR/follow-upstream.apk
rm -f "$apk"
curl -sL --retry 5 --retry-all-errors -o "$apk" \
  "https://github.com/$REPO/releases/download/dev-latest/opencode-android.apk" || { echo "download failed"; exit 7; }
size=$(stat -c %s "$apk")
want=$(gh api "repos/$REPO/releases/tags/dev-latest" --jq '.assets[0].size')
echo "apk $size / $want"
[ "$size" != "$want" ] && { echo "truncated download"; exit 7; }

cp "$apk" /sdcard/Download/opencode-android.apk || { echo "copy to /sdcard failed"; exit 7; }
rish -c "cp /sdcard/Download/opencode-android.apk /data/local/tmp/oc-follow.apk && pm install -r /data/local/tmp/oc-follow.apk" 2>&1 | tail -2
rish -c "dumpsys package $PKG | grep -E 'versionName|firstInstallTime'" 2>&1 | head -3
