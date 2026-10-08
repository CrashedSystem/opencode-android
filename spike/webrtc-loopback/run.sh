#!/usr/bin/env bash
# Installs the loopback and runs it once per mode, each in a fresh process.
#   ANDROID_SERIAL=emulator-5558 [WEBRTC=livekit|stream] spike/webrtc-loopback/run.sh [udp] [tcp] [apm] [chunk20]
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
package=ai.opencode.spike.webrtc
cd "$(dirname "$0")/../.."
./gradlew -q :webrtc-loopback:assembleDebug -Pwebrtc="${WEBRTC:-livekit}"
"$adb" install -r -t spike/webrtc-loopback/build/outputs/apk/debug/webrtc-loopback-debug.apk >/dev/null

modes=("$@")
[ ${#modes[@]} -eq 0 ] && modes=(udp tcp apm)
for mode in "${modes[@]}"; do
  case "$mode" in
    udp) extras=() ;;
    tcp) extras=(--ez tcp true) ;;
    apm) extras=(--ez apm true) ;;
    chunk20) extras=(--ei chunkMs 20) ;;
  esac
  "$adb" shell am force-stop "$package"
  "$adb" shell am start -W -n "$package/.LoopbackActivity" ${extras[@]+"${extras[@]}"} --ei seconds "${SECONDS_PER_RUN:-6}" >/dev/null
  for _ in $(seq 1 $(( ${SECONDS_PER_RUN:-6} + 40 ))); do
    sleep 1
    if "$adb" shell run-as "$package" cat files/loopback.txt 2>/dev/null | grep -q '^DONE'; then break; fi
  done
  echo "== $mode"
  "$adb" shell run-as "$package" cat files/loopback.txt
done
