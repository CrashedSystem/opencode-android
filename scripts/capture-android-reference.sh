#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
ios="$root/.upstream/opencode-ios"
shots="$root/docs/port/reference/android"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
avd="iphone_size"
only=""
appearances="light dark"

main() {
  parse_args "$@"
  require_checkout
  ensure_emulator
  start_fixtures
  build_and_install
  freeze_status_bar
  for appearance in $appearances; do
    capture "$appearance"
  done
}

parse_args() {
  if [[ $# -gt 2 ]]; then
    usage
  fi
  if [[ "${1:-}" == light || "${1:-}" == dark ]]; then
    appearances="$1"
  elif [[ -n "${1:-}" ]]; then
    only="$1"
  fi
  if [[ -n "${2:-}" ]]; then
    case "$2" in
      light | dark) appearances="$2" ;;
      *) usage ;;
    esac
  fi
  if [[ -n "$only" && "$only" != test* ]]; then
    usage
  fi
}

usage() {
  printf '%s\n' "Usage: scripts/capture-android-reference.sh [testName] [light|dark]" >&2
  exit 2
}

require_checkout() {
  [[ -d "$ios/scripts" ]] || {
    printf '%s\n' "Missing iOS checkout at $ios" >&2
    exit 1
  }
}

# The iOS captures are 440 by 956 points at 3x. This AVD is 440 by 956 dp at density 480.
ensure_emulator() {
  if [[ ! -f "$HOME/.android/avd/$avd.ini" ]]; then
    create_avd
  fi
  export ANDROID_SERIAL="$(running_serial)"
  if [[ -z "$ANDROID_SERIAL" ]]; then
    android emulator start "$avd"
    export ANDROID_SERIAL="$(running_serial)"
  fi
  [[ -n "$ANDROID_SERIAL" ]] || {
    printf '%s\n' "Emulator $avd did not start" >&2
    exit 1
  }
  "$adb" wait-for-device
  [[ "$("$adb" shell wm size | tail -1)" == *1320x2868 ]] || {
    printf '%s\n' "$ANDROID_SERIAL is not 1320x2868" >&2
    exit 1
  }
}

create_avd() {
  local dir="$HOME/.android/avd/$avd.avd"
  mkdir -p "$dir"
  printf '%s\n' "avd.ini.encoding=UTF-8" "path=$dir" "path.rel=avd/$avd.avd" "target=android-36" >"$HOME/.android/avd/$avd.ini"
  cat >"$dir/config.ini" <<EOF
AvdId=$avd
PlayStore.enabled=false
abi.type=arm64-v8a
avd.ini.displayname=iPhone size (440x956 dp)
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
hw.audioInput=no
hw.camera.back=emulated
hw.camera.front=emulated
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.initialOrientation=portrait
hw.keyboard=no
hw.lcd.density=480
hw.lcd.height=2868
hw.lcd.width=1320
hw.mainKeys=no
hw.ramSize=3072
hw.sdCard=no
image.sysdir.1=system-images/android-36/google_apis/arm64-v8a/
showDeviceFrame=no
skin.dynamic=yes
skin.name=1320x2868
skin.path=_no_skin
tag.display=Google APIs
tag.id=google_apis
target=android-36
EOF
}

running_serial() {
  local serial
  for serial in $("$adb" devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1}'); do
    if [[ "$("$adb" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')" == "$avd" ]]; then
      printf '%s\n' "$serial"
      return
    fi
  done
}

start_fixtures() {
  mkdir -p /tmp/ios-fixtures
  ensure_fixture 4110 session-rendering-v1 /lab/state lab \
    scripts/session-lab.py --port 4110 serve
  ensure_fixture 4120 responsiveness-gauntlet-v1 /lab/state lab \
    scripts/gauntlet-fixture.py --port 4120 serve
  ensure_fixture 4105 approval-fixture /api/info version \
    scripts/approval-fixture.py --port 4105
  ensure_fixture 4161 2.0.0-fixture /api/info version \
    scripts/connection-fixture.py --ports 4161 4162
  ensure_fixture 4165 family-fixture /api/info version \
    scripts/family-fixture.py --port 4165
}

ensure_fixture() {
  local port="$1" expect="$2" path="$3" key="$4"
  shift 4
  if listening "$port"; then
    identity "$port" "$path" "$key" "$expect" || {
      printf '%s\n' "Port $port is open but is not $expect" >&2
      exit 1
    }
    return
  fi
  (cd "$ios" && python3 "$@") >"/tmp/ios-fixtures/${port}.log" 2>&1 &
  printf '%s\n' "$!" >"/tmp/ios-fixtures/${port}.pid"
  local tries=50
  while ((tries > 0)); do
    if identity "$port" "$path" "$key" "$expect"; then
      return
    fi
    sleep 0.2
    tries=$((tries - 1))
  done
  printf '%s\n' "Fixture on $port did not become $expect. See /tmp/ios-fixtures/${port}.log" >&2
  exit 1
}

listening() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

identity() {
  curl -sf "http://127.0.0.1:${1}${2}" | python3 -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if d.get(sys.argv[1])==sys.argv[2] else 1)' "$3" "$4"
}

build_and_install() {
  (cd "$root" && ./gradlew assembleDebug --console=plain -q)
  "$adb" install -r "$root/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
}

# The iOS captures override the status bar to 9:41 with full bars and battery.
freeze_status_bar() {
  "$adb" shell settings put global sysui_demo_allowed 1
  demo enter
  demo clock -e hhmm 0941
  demo battery -e level 100 -e plugged false
  demo network -e wifi show -e level 4 -e fully true
  demo network -e mobile show -e datatype none -e level 4 -e fully true
  demo notifications -e visible false
}

demo() {
  local command="$1"
  shift
  "$adb" shell am broadcast -a com.android.systemui.demo -e command "$command" "$@" >/dev/null
}

capture() {
  local appearance="$1"
  if [[ "$appearance" == dark ]]; then
    "$adb" shell cmd uimode night yes >/dev/null
  else
    "$adb" shell cmd uimode night no >/dev/null
  fi
  if [[ -n "$only" ]]; then
    python3 "$root/scripts/android-reference/capture.py" "$appearance" "$shots" "$only"
  else
    python3 "$root/scripts/android-reference/capture.py" "$appearance" "$shots"
  fi
}

main "$@"
