#!/usr/bin/env bash
# Installs the PsychoScript runner (anomalyco/psychoscript, crate rust/crates/psychoscript-mobile) that
# agent widgets run on, into :feature:transcript:
#   scripts/psychoscript.sh <dir>   a built output (<dir>/jniLibs, <dir>/kotlin, <dir>/REVISION)
# The libraries go to feature/transcript/src/main/jniLibs (not committed); the Kotlin bindings replace the
# committed copy. Without the libraries, widgets show "The widget runtime isn't available".
set -euo pipefail
cd "$(dirname "$0")/.."
src="${1:?usage: scripts/psychoscript.sh <psychoscript-mobile build output>}"
libs=feature/transcript/src/main/jniLibs
rm -rf "$libs"
for abi in arm64-v8a x86_64; do
  mkdir -p "$libs/$abi"
  cp "$src/jniLibs/$abi/libpsychoscript_mobile.so" "$libs/$abi/"
done
cp "$src/kotlin/com/anomalyco/psychoscript/psychoscript_mobile.kt" feature/transcript/src/main/kotlin/com/anomalyco/psychoscript/
echo "psychoscript $(cat "$src/REVISION" 2>/dev/null || echo unknown)"
ls -l "$libs"/*/
