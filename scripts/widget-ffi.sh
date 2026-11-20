#!/usr/bin/env bash
# Copies the widget runner (opencode-widget-ffi, built in anomalyco/rune-desktop by
# crates/opencode-widget-ffi/scripts/build-android.sh) into :feature:transcript:
#   scripts/widget-ffi.sh <ffi-out/android>
# The libraries go to feature/transcript/src/main/jniLibs (not committed); the Kotlin bindings
# replace the committed copy. Without the libraries, widgets show their error card.
set -euo pipefail
cd "$(dirname "$0")/.."
src="${1:?usage: scripts/widget-ffi.sh <ffi-out/android>}"
libs=feature/transcript/src/main/jniLibs
rm -rf "$libs"
mkdir -p "$libs"
cp -R "$src/jniLibs/." "$libs/"
cp "$src/kotlin/ai/opencode/widget/opencode_widget_ffi.kt" feature/transcript/src/main/kotlin/ai/opencode/widget/
ls -l "$libs"/*/
