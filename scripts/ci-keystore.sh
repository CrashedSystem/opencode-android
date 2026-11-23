#!/usr/bin/env bash
# Decodes OPENCODE_DEBUG_KEYSTORE (base64 PKCS12) to the file app/build.gradle.kts signs with.
# Absent secret (fork pull requests): leaves no file, and debug builds fall back to the runner's
# own generated key, which is fine because those artifacts are never installed over a build.
set -euo pipefail
cd "$(dirname "$0")/.."
target="app/build/fork-debug.jks"
if [[ -z "${OPENCODE_DEBUG_KEYSTORE:-}" ]]; then
  echo "no OPENCODE_DEBUG_KEYSTORE; the debug APK won't update an installed build" >&2
  exit 0
fi
mkdir -p "$(dirname "$target")"
printf '%s' "$OPENCODE_DEBUG_KEYSTORE" | base64 -d > "$target"
chmod 600 "$target"
echo "debug signing key written to $target"
