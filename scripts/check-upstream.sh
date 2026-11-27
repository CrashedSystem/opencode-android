#!/usr/bin/env bash
# Daily check: is anomalyco/opencode-android's dev ahead of this fork, and is anything the fork
# relies on missing from the merged tree? Prints a short report; exits 0 either way so a cron job
# never fails on "nothing new".
#
# Reads only; makes no commits and pushes nothing.
set -uo pipefail
cd "$(dirname "$0")/.."

REPO_DIR=$PWD
UPSTREAM=https://github.com/anomalyco/opencode-android.git

echo "== fork state"
git log --oneline -1 HEAD
echo

echo "== upstream dev"
upstream_sha=$(git ls-remote "$UPSTREAM" refs/heads/dev 2>/dev/null | cut -f1)
if [ -z "$upstream_sha" ]; then
  echo "could not read upstream dev (network?)"
  exit 0
fi
git fetch --quiet "$UPSTREAM" dev 2>/dev/null || { echo "fetch failed"; exit 0; }
echo "sha: ${upstream_sha:0:10}"

behind=$(git rev-list --count HEAD..FETCH_HEAD 2>/dev/null || echo "?")
echo "commits behind: $behind"
if [ "$behind" != "0" ] && [ "$behind" != "?" ]; then
  echo
  echo "== new upstream commits"
  git log --oneline HEAD..FETCH_HEAD | head -30
fi
echo

echo "== fork features still present (grep the tree, not history)"
check() { if grep -rq -- "$2" $3 2>/dev/null; then echo "ok    $1"; else echo "GONE  $1"; fi }
check "fixed debug signing"      "forkDebug"                     app/build.gradle.kts
check "own relay host"           "indev.asuscomm.com"            feature/system/src/main/kotlin
check "session vibration"        "setVibrationPattern"           feature/system/src/main/kotlin
check "firebase resources"       "google_app_id"                 app/src/main/res/values/strings.xml
check "english+korean only"      'listOf("en", "ko")'            app/build.gradle.kts
check "reasoning shown"          "AssistantReasoning"            server/src/main/kotlin
check "reasoning toggle"         "showReasoning"                 core/src/main/kotlin
check "project groups"           "collapsedProjectIDs"           feature/drawer/src/main/kotlin
check "group show more"          "GROUP_ROWS_SHOWN"              feature/drawer/src/main/kotlin
check "home folder tile guard"   "projectTile"                   feature/drawer/src/main/kotlin
echo

echo "== tree clean?"
if [ -n "$(git status --porcelain)" ]; then
  git status --short | head -10
else
  echo "clean"
fi
