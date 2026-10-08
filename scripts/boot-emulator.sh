#!/usr/bin/env bash
# Boot an AVD detached from the calling shell and wait until Android finishes booting.
# An agent's shell tool waits for its command to exit, and the emulator never exits,
# so never start the emulator in the foreground.
#
#   scripts/boot-emulator.sh medium_phone 5554
#   scripts/boot-emulator.sh iphone_size 5556
set -euo pipefail

avd="$1"
port="$2"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
serial="emulator-$port"
log="${TMPDIR:-/tmp}/emulator-$avd.log"

if "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
  echo "$serial ($avd) is already booted"
  exit 0
fi

nohup "$sdk/emulator/emulator" -avd "$avd" -port "$port" -gpu host -no-window -no-snapshot-load -no-boot-anim \
  >"$log" 2>&1 </dev/null &
disown

for _ in $(seq 1 120); do
  if "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
    echo "$serial ($avd) booted; log at $log"
    exit 0
  fi
  sleep 2
done
echo "$serial ($avd) did not boot in 240 s; see $log" >&2
exit 1
