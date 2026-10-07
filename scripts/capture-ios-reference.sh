#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
ios="$root/.upstream/opencode-ios"
shots="$root/docs/port/reference/ios"
udid="${CAPTURE_UDID:-}"
only=""
appearances="light dark"

main() {
  parse_args "$@"
  require_checkout
  ensure_simulator
  start_fixtures
  build_and_install
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
  if [[ -n "$only" && "$only" != CaptureUITests/CaptureTests/test* ]]; then
    usage
  fi
}

usage() {
  printf '%s\n' "Usage: scripts/capture-ios-reference.sh [CaptureUITests/CaptureTests/testName] [light|dark]" >&2
  exit 2
}

require_checkout() {
  [[ -d "$ios/OpenCode.xcodeproj" ]] || {
    printf '%s\n' "Missing iOS checkout at $ios" >&2
    exit 1
  }
}

ensure_simulator() {
  defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool false
  if [[ -z "$udid" ]]; then
    udid="$(python3 - <<'PY'
import json, subprocess
data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "-j"]))
for devices in data["devices"].values():
    for device in devices:
        if device.get("name") == "OpenCode Android Port Lab" and device.get("isAvailable", True):
            print(device["udid"])
            raise SystemExit
PY
)"
  fi
  if [[ -z "$udid" ]]; then
    udid="$(xcrun simctl create "OpenCode Android Port Lab" "iPhone 17 Pro Max")"
  fi
  xcrun simctl boot "$udid" >/dev/null 2>&1 || true
  xcrun simctl bootstatus "$udid" -b >/dev/null
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
  xcodebuild build \
    -project "$ios/OpenCode.xcodeproj" \
    -scheme OpenCode \
    -configuration Debug \
    -destination "platform=iOS Simulator,id=$udid" \
    -derivedDataPath "$root/.ios-build" \
    -skipMacroValidation \
    -skipPackagePluginValidation \
    CODE_SIGNING_ALLOWED=NO
  xcrun simctl install "$udid" "$root/.ios-build/Build/Products/Debug-iphonesimulator/OpenCode.app"
}

capture() {
  local appearance="$1"
  local bundle="$root/.ios-capture-build/capture-${appearance}.xcresult"
  xcrun simctl ui "$udid" appearance "$appearance"
  xcrun simctl spawn "$udid" /bin/mkdir -p /private/tmp
  # The UI test runs in the simulator, so this is not the host /private/tmp.
  xcrun simctl spawn "$udid" /bin/sh -c "printf ${appearance} > /private/tmp/opencode-capture-appearance"
  xcrun simctl status_bar "$udid" override \
    --time "9:41" \
    --dataNetwork wifi \
    --wifiMode active \
    --wifiBars 3 \
    --cellularMode active \
    --cellularBars 4 \
    --batteryState charged \
    --batteryLevel 100
  rm -rf "$bundle"
  if [[ -n "$only" ]]; then
    xcodebuild test \
      -project "$root/scripts/ios-reference/Capture.xcodeproj" \
      -scheme Capture \
      -destination "platform=iOS Simulator,id=$udid" \
      -derivedDataPath "$root/.ios-capture-build" \
      -parallel-testing-enabled NO \
      -collect-test-diagnostics never \
      -only-testing:"$only" \
      -resultBundlePath "$bundle" \
      CODE_SIGNING_ALLOWED=NO
  else
    xcodebuild test \
      -project "$root/scripts/ios-reference/Capture.xcodeproj" \
      -scheme Capture \
      -destination "platform=iOS Simulator,id=$udid" \
      -derivedDataPath "$root/.ios-capture-build" \
      -parallel-testing-enabled NO \
      -collect-test-diagnostics never \
      -resultBundlePath "$bundle" \
      CODE_SIGNING_ALLOWED=NO
  fi
  export_pngs "$bundle"
}

export_pngs() {
  local bundle="$1"
  local exported="$root/.ios-capture-build/export-$(basename "$bundle" .xcresult)"
  rm -rf "$exported"
  mkdir -p "$shots"
  xcrun xcresulttool export attachments --path "$bundle" --output-path "$exported"
  python3 - "$exported" "$shots" <<'PY'
import json, shutil, sys
from pathlib import Path
src, dest = Path(sys.argv[1]), Path(sys.argv[2])
copied = 0
for suite in json.loads((src / "manifest.json").read_text()):
    for item in suite["attachments"]:
        name = item["suggestedHumanReadableName"]
        if not name.endswith(".png"):
            continue
        stem = name.split("_0_")[0]
        if not (stem.endswith("-light") or stem.endswith("-dark")):
            continue
        shutil.copyfile(src / item["exportedFileName"], dest / f"{stem}.png")
        copied += 1
print(f"copied {copied} pngs to {dest}")
PY
}

main "$@"
