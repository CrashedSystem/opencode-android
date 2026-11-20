#!/usr/bin/env bash
# The PsychoScript runner agent widgets run on (anomalyco/psychoscript, branch `mobile`, crate
# rust/crates/psychoscript-mobile), pinned to REV, built into vendor/psychoscript/ (gitignored),
# which :feature:transcript links as its jniLibs:
#
#   scripts/psychoscript.sh                     fetch REV into .psychoscript/ (gitignored) and build
#   scripts/psychoscript.sh --prebuilt <dir>    use a build of REV made elsewhere (<dir>/jniLibs, kotlin, REVISION)
#
# Building needs the NDK (r28+; ANDROID_NDK_HOME, else the newest under $ANDROID_HOME/ndk),
# `cargo install cargo-ndk` and `rustup target add --toolchain 1.100.0 aarch64-linux-android
# x86_64-linux-android` (the toolchain psychoscript's rust/rust-toolchain.toml names).
# The generated Kotlin bindings are committed (src/main/kotlin/com/anomalyco/psychoscript), so builds
# without Rust compile; this script refreshes them. Without the libraries, widgets show "The widget
# runtime isn't available".
set -euo pipefail
REV=2ce274a74a58bcc3b52ce21e23dc1bd0e2a16b0b
cd "$(dirname "$0")/.."
out=vendor/psychoscript
bindings=feature/transcript/src/main/kotlin/com/anomalyco/psychoscript

if [[ "${1:-}" == "--prebuilt" ]]; then
  src="${2:?usage: scripts/psychoscript.sh --prebuilt <dir>}"
  grep -q "$REV" "$src/REVISION" || { echo "$src is $(cat "$src/REVISION" 2>/dev/null || echo unknown), not $REV" >&2; exit 1; }
  rm -rf "$out"
  mkdir -p "$out"
  cp -R "$src/jniLibs" "$src/kotlin" "$src/REVISION" "$out/"
  rm -f "$out"/jniLibs/*/libopencode_widget_ffi.so
  rm -rf "$out/kotlin/ai"
else
  if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -n 1)"
    export ANDROID_NDK_HOME
  fi
  [[ -d "$ANDROID_NDK_HOME" ]] || { echo "No NDK: set ANDROID_NDK_HOME (r28 or later)" >&2; exit 1; }
  export PATH="$HOME/.cargo/bin:$PATH"
  [[ -d .psychoscript ]] || git clone https://github.com/anomalyco/psychoscript .psychoscript
  git -C .psychoscript fetch -q origin "$REV"
  git -C .psychoscript checkout -q --detach "$REV"
  .psychoscript/rust/crates/psychoscript-mobile/scripts/build-android.sh "$PWD/$out"
fi

grep -q "$REV" "$out/REVISION" || { echo "Built $(cat "$out/REVISION"), not $REV" >&2; exit 1; }
if ! cmp -s "$out/kotlin/com/anomalyco/psychoscript/psychoscript_mobile.kt" "$bindings/psychoscript_mobile.kt"; then
  mkdir -p "$bindings"
  cp "$out/kotlin/com/anomalyco/psychoscript/psychoscript_mobile.kt" "$bindings/"
  echo "Updated the committed bindings in $bindings: commit them with the REV bump."
fi
echo "psychoscript $(cat "$out/REVISION")"
ls -l "$out"/jniLibs/*/
