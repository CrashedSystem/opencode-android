#!/usr/bin/env python3
"""Drive the app on the emulator by visible text or content description.

  scripts/ui.py find [TEXT]          list on-screen elements, optionally filtered
  scripts/ui.py tap TEXT [--nth N]   tap the element whose text or description contains TEXT
  scripts/ui.py type TEXT            type into the focused field (spaces allowed)
  scripts/ui.py key NAME             press a key: back, enter, tab, del
  scripts/ui.py swipe X1 Y1 X2 Y2 [MS]
  scripts/ui.py shot PATH            save a screenshot
  scripts/ui.py wait TEXT [SECONDS]  wait until TEXT is on screen
"""

import json
import os
import subprocess
import sys
import time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
KEYS = {"back": "4", "enter": "66", "tab": "61", "del": "67"}


def elements():
    raw = subprocess.run(["android", "layout", "--no-metrics"], capture_output=True, text=True, check=True).stdout
    found = []

    def walk(node):
        if isinstance(node, list):
            for child in node:
                walk(child)
            return
        label = node.get("text") or node.get("content-desc") or ""
        if label and "center" in node:
            x, y = node["center"].strip("[]").split(",")
            found.append((label, int(x), int(y)))
        walk(node.get("content", []))

    for window in json.loads(raw):
        if window.get("type") == "APPLICATION" or window.get("window-title"):
            walk(window.get("content", []))
    return found


def matching(text):
    return [e for e in elements() if text.lower() in e[0].lower()]


def main():
    command, args = sys.argv[1], sys.argv[2:]
    if command == "find":
        for label, x, y in (matching(args[0]) if args else elements()):
            print(f"{x:5} {y:5}  {label[:90]!r}")
    elif command == "tap":
        nth = int(args[args.index("--nth") + 1]) if "--nth" in args else 0
        hits = matching(args[0])
        if len(hits) <= nth:
            sys.exit(f"no element matching {args[0]!r}")
        _, x, y = hits[nth]
        subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)], check=True)
    elif command == "type":
        subprocess.run([ADB, "shell", "input", "text", " ".join(args).replace(" ", "%s")], check=True)
    elif command == "key":
        subprocess.run([ADB, "shell", "input", "keyevent", KEYS[args[0]]], check=True)
    elif command == "swipe":
        subprocess.run([ADB, "shell", "input", "swipe", *args], check=True)
    elif command == "shot":
        subprocess.run(["android", "screen", "capture", "--no-metrics", f"--output={args[0]}"], check=True, capture_output=True)
        print(args[0])
    elif command == "wait":
        deadline = time.time() + (float(args[1]) if len(args) > 1 else 20)
        while time.time() < deadline:
            if matching(args[0]):
                return
            time.sleep(0.5)
        sys.exit(f"timed out waiting for {args[0]!r}")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
