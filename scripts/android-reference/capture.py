#!/usr/bin/env python3
"""Drive the debug app through the iOS reference states and keep one PNG per state.

The Android twin of scripts/ios-reference/CaptureUITests/CaptureTests.swift. The
launch extras carry the same lab environment and arguments the iOS launch does.
scripts/capture-android-reference.sh runs it:

  capture.py APPEARANCE OUTPUT_DIR [testName ...]

ANDROID_SERIAL picks the device. Fixtures listen on the host loopback, which the
emulator reaches at 10.0.2.2.
"""

import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.request

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
SERIAL = os.environ["ANDROID_SERIAL"]
PACKAGE = "ai.opencode.mobilecode"
SESSION_LAB = 4110
GAUNTLET = 4120
APPROVAL = 4105
LAB_SESSION = "ses_approval_root"
HEADING = "The Effect upgrade, change by change"


class CaptureError(Exception):
    pass


class Node:
    def __init__(self, raw):
        self.text = raw.get("text") or ""
        self.desc = raw.get("content-desc") or ""
        self.id = raw.get("resource-id") or ""
        self.cls = raw.get("class") or ""
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", raw.get("bounds", "[0,0][0,0]")))
        self.x1, self.y1, self.x2, self.y2 = x1, y1, x2, y2

    @property
    def center(self):
        return (self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2

    @property
    def height(self):
        return self.y2 - self.y1

    def labels(self):
        return (self.id, self.text, self.desc)

    def __repr__(self):
        return f"Node({self.id or self.text or self.desc!r} [{self.x1},{self.y1}][{self.x2},{self.y2}])"


class App:
    def __init__(self, appearance, output):
        self.appearance = appearance
        self.output = output
        self.width, self.height = screen_size()

    # MARK: Launch

    def launch(self, server=None, extra=None, arguments=()):
        adb("shell", "am", "force-stop", PACKAGE)
        command = ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity",
                   "--es", "-lab-interface-style", self.appearance]
        if server:
            command += ["--es", "OPENCODE_LAB_SERVER_URL", f"http://10.0.2.2:{server}"]
        # adb joins its arguments into one device shell command line.
        for key, value in (extra or {}).items():
            command += ["--es", key, shlex.quote(value)]
        for argument in arguments:
            command += ["--ez", argument, "true"]
        adb(*command)

    def terminate(self):
        adb("shell", "am", "force-stop", PACKAGE)

    # MARK: Elements

    def windows(self):
        for attempt in range(6):
            raw = subprocess.run(["android", "layout", f"--device={SERIAL}", "--no-metrics", "--flat", "--full"],
                                 capture_output=True, text=True).stdout
            try:
                windows = json.loads(raw)
            except json.JSONDecodeError:
                windows = None
            if isinstance(windows, list):
                return windows
            if attempt == 2:
                # The layout helper on the device can wedge; the next call starts a fresh one.
                adb("shell", "am", "force-stop", "com.android.cli.interact.instrumentation")
            time.sleep(0.5)
        raise CaptureError("android layout returned no JSON")

    def nodes(self, keyboard=False, system=False, windows=None):
        found = []
        for window in windows if windows is not None else self.windows():
            kind = window.get("type")
            if (kind == "SYSTEM") != system or (kind == "INPUT_METHOD") != keyboard:
                continue
            found += [Node(raw) for raw in window.get("content", []) if "bounds" in raw]
        return [n for n in found if n.x2 > 0 and n.y2 > 0 and n.x1 < self.width and n.y1 < self.height]

    def find(self, label, nodes=None):
        """Exact test tag, text, or content description."""
        return next((n for n in (nodes or self.nodes()) if label in n.labels()), None)

    def find_containing(self, fragment, nodes=None):
        return next((n for n in (nodes or self.nodes()) if fragment in n.text or fragment in n.desc), None)

    def find_prefix(self, prefix, nodes=None):
        return next((n for n in (nodes or self.nodes()) if n.id.startswith(prefix)), None)

    def field(self, label, timeout=15):
        """The text field a label names, once it stops moving: the field around a
        placeholder, or the first one below a caption."""
        deadline = time.time() + timeout
        previous = None
        while time.time() < deadline:
            nodes = self.nodes()
            caption = self.find(label, nodes)
            fields = [n for n in nodes if n.cls.endswith("EditText")]
            around = [n for n in fields if caption and n.y1 <= caption.y1 and n.y2 >= caption.y2]
            below = sorted((n for n in fields if caption and n.y1 >= caption.y1), key=lambda n: n.y1)
            node = (around or below or [None])[0]
            if node and previous and (node.x1, node.y1, node.x2, node.y2) == previous:
                return node
            previous = (node.x1, node.y1, node.x2, node.y2) if node else None
            time.sleep(0.3)
        raise CaptureError(f"no settled text field for {label!r}")

    def wait(self, label, timeout=8, containing=False):
        lookup = self.find_containing if containing else self.find
        deadline = time.time() + timeout
        while True:
            windows = self.windows()
            node = lookup(label, self.nodes(windows=windows))
            if node:
                return node
            self.dismiss_not_responding(windows)
            if time.time() > deadline:
                raise CaptureError(f"timed out waiting for {label!r}")
            time.sleep(0.3)

    def dismiss_not_responding(self, windows=None):
        """A loaded emulator can miss the 5 s input deadline while the app is idle; keep waiting."""
        windows = windows if windows is not None else self.windows()
        nodes = self.nodes(windows=windows) + self.nodes(system=True, windows=windows)
        if any("isn't responding" in n.text for n in nodes):
            wait = next((n for n in nodes if n.text == "Wait"), None)
            if wait:
                print("  dismissed a not-responding dialog", flush=True)
                self.tap(wait)

    def exists(self, label, timeout=0, containing=False):
        try:
            return self.wait(label, timeout, containing)
        except CaptureError:
            return None

    def wait_until(self, condition, timeout=8, what="condition"):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if condition():
                return
            self.dismiss_not_responding()
            time.sleep(0.3)
        if not condition():
            raise CaptureError(f"timed out waiting for {what}")

    def keyboard_shown(self):
        return bool(self.nodes(keyboard=True))

    # MARK: Input

    def settled(self, label, timeout=10, containing=False):
        """The element once two layouts agree on where it is, so a tap does not land mid-animation."""
        previous = self.wait(label, timeout, containing)
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(0.2)
            node = self.wait(label, timeout, containing)
            if (node.x1, node.y1, node.x2, node.y2) == (previous.x1, previous.y1, previous.x2, previous.y2):
                return node
            previous = node
        return previous

    def tap(self, target, timeout=8, containing=False):
        node = target if isinstance(target, Node) else self.settled(target, timeout, containing)
        x, y = node.center
        adb("shell", "input", "tap", str(x), str(y))

    def tap_point(self, dx, dy):
        adb("shell", "input", "tap", str(int(self.width * dx)), str(int(self.height * dy)))

    def tap_until(self, target, expected, timeout=6):
        """Taps again once when the first tap lands before the view takes input."""
        for attempt in (1, 2):
            self.tap(target)
            if self.exists(expected, timeout=timeout):
                return
        raise CaptureError(f"tapping {target!r} never showed {expected!r}")

    def press(self, target, seconds):
        node = target if isinstance(target, Node) else self.wait(target)
        x, y = node.center
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), str(int(seconds * 1000)))

    def type_text(self, text):
        adb("shell", "input", "text", text.replace(" ", "%s"))

    def drag(self, x, y1, y2):
        """A held drag that ends without a fling, like XCUITest's press-then-drag."""
        steps = 6
        moves = "; ".join(f"input motionevent MOVE {x} {int(y1 + (y2 - y1) * i / steps)}" for i in range(1, steps + 1))
        adb("shell", f"input motionevent DOWN {x} {y1}; {moves}; sleep 0.15; input motionevent UP {x} {y2}")

    # MARK: Shots

    def shot(self, stem):
        top = adb("shell", "dumpsys", "activity", "activities", capture=True)
        resumed = re.search(r"topResumedActivity=.*? (\S+/\S+)", top)
        if not resumed or resumed.group(1) != f"{PACKAGE}/.MainActivity":
            raise CaptureError(f"{stem}: the foreground is {resumed.group(1) if resumed else 'unknown'}, not the app")
        path = os.path.join(self.output, f"{stem}-{self.appearance}.png")
        with open(path, "wb") as file:
            file.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)
        print(f"  saved {os.path.basename(path)}", flush=True)

    def settle(self):
        time.sleep(0.7)


# MARK: Tests

def testConnectScreens(app):
    adb("shell", "pm", "clear", PACKAGE)
    app.launch(server=None)
    app.wait("Scan Pairing Code", timeout=20)
    app.settle()
    app.shot("01-first-launch")

    app.tap("Enter a Server Manually")
    app.field("Server address")
    app.settle()
    app.shot("02-server-manual")

    app.tap(app.field("Server address"))
    app.type_text("http://127.0.0.1:9")
    app.tap("Connect")
    app.wait("Copy Error Report", timeout=20)
    hide_keyboard(app)
    app.settle()
    app.shot("03-connect-error")


def testDrawerCatchUpAndWhatsNew(app):
    post(GAUNTLET, "/lab/act", {"action": "reset"})
    post(GAUNTLET, "/lab/act", {"action": "start", "sessionID": "ses_gauntlet_04"})
    post(GAUNTLET, "/lab/act", {"action": "ask", "sessionID": "ses_gauntlet_05"})
    app.launch(server=GAUNTLET, extra={
        "OPENCODE_LAB_LAST_SESSION_ID": "-",
        "OPENCODE_LAB_WHATS_NEW": "unseen",
        "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    })
    open_drawer(app)
    # Pins persist across launches. A pinned row's menu offers Unpin instead.
    for id in ["ses_gauntlet_02", "ses_gauntlet_03"]:
        row = app.wait(f"drawer-session-row-{id}")
        app.press(row, 0.8)
        app.wait_until(lambda: app.find("Pin") or app.find("Unpin"), timeout=3, what="the row menu")
        if app.find("Pin"):
            app.tap("Pin")
        else:
            adb("shell", "input", "keyevent", "4")
        app.wait_until(lambda: not app.find("Pin") and not app.find("Unpin"), timeout=4, what="the menu to close")
    app.wait("drawer-catch-up")
    app.wait("drawer-whats-new")
    app.settle()
    app.shot("04-drawer")

    app.tap("drawer-catch-up")
    app.wait("catch-up-done")
    app.settle()
    app.shot("29-catch-up")
    app.terminate()

    app.launch(server=GAUNTLET, extra={
        "OPENCODE_LAB_LAST_SESSION_ID": "-",
        "OPENCODE_LAB_WHATS_NEW": "unseen",
    })
    open_drawer(app)
    app.tap(app.field("Search sessions"))
    app.type_text("composer")
    app.wait("drawer-session-row-ses_gauntlet_02", timeout=5)
    app.settle()
    app.shot("05-drawer-search")
    app.terminate()

    app.launch(server=GAUNTLET, extra={
        "OPENCODE_LAB_LAST_SESSION_ID": "-",
        "OPENCODE_LAB_WHATS_NEW": "unseen",
    })
    open_drawer(app)
    app.tap("drawer-whats-new")
    app.wait("whats-new-list")
    app.settle()
    app.shot("30-whats-new")


def testDraftComposerAndMenus(app):
    select("intro")
    app.launch(server=SESSION_LAB, extra=lab_session_environment())
    app.wait("Open sessions", timeout=20)
    open_drawer(app)
    app.tap("New Session")
    app.wait("Project")
    app.settle()
    app.shot("06-draft")

    open_drawer(app)
    app.tap(f"drawer-session-row-{LAB_SESSION}")
    app.field("Message")
    app.settle()
    app.shot("35-composer-empty")

    app.tap(app.field("Message"))
    app.type_text("Check the composer with a short note")
    app.wait_until(app.keyboard_shown, timeout=5, what="the keyboard")
    app.settle()
    app.shot("17-composer-text")
    app.terminate()

    select("intro")
    app.launch(server=SESSION_LAB, extra=lab_session_environment(), arguments=["-lab-pasteboard-image"])
    app.field("Message", timeout=20)
    app.tap_until("Add attachment", "Camera")
    app.settle()
    app.shot("26-attachment-menu")
    if app.exists("Paste", timeout=3):
        time.sleep(0.5)
        app.tap("Paste")
        app.wait("pending-attachment-0", timeout=6)
        app.settle()
        app.shot("18-composer-attachments")

    if app.exists("Message"):
        app.tap(app.field("Message"))
    app.wait("Choose model", timeout=4)
    app.tap_until("Choose model", "Model")
    app.settle()
    app.shot("25-model-picker")


def testTranscript(app):
    select("typography")
    open_lab_session(app)
    # Only rows on screen are in the layout, and the transcript opens at the bottom.
    app.wait("All three ship together in rc.118", timeout=20, containing=True)
    app.settle()
    app.shot("09-transcript-table")
    reveal(app, "the core tool runtime")
    app.shot("08-transcript-tasks")
    park_heading(app, HEADING)
    app.shot("07-transcript-markdown")
    app.terminate()

    select("files")
    open_lab_session(app)
    app.wait("recorded the drawer", timeout=20, containing=True)
    app.settle()
    app.shot("10-transcript-links")
    app.terminate()

    select("tool-completed")
    open_lab_session(app)
    used = app.wait("Used 1 Shell", timeout=20)
    app.settle()
    app.shot("11-tools-collapsed")
    app.tap(used)
    app.wait_until(lambda: app.find_containing("swift test --filter SessionLayoutTests") or app.find_containing("2 tests passed"),
                   timeout=5, what="the shell output")
    app.settle()
    app.shot("12-tools-expanded")
    app.terminate()

    select("subagent")
    open_lab_session(app)
    app.tap("Used 1 Subagent", timeout=20)
    app.wait("subagent-task", timeout=6)
    app.settle()
    app.shot("13-subagent")
    app.terminate()

    select("tool-images")
    open_lab_session(app)
    app.wait("tool-image-mosaic", timeout=20)
    app.settle()
    app.shot("14-images")
    app.terminate()

    select("tool-running")
    open_lab_session(app)
    app.wait("Working", timeout=20)
    app.settle()
    app.shot("15-running")

    app.tap(app.field("Message"))
    app.type_text("Steer this")
    send = app.wait("Steer message", timeout=5)
    app.press(send, 0.9)
    if not app.exists("Queue", timeout=2):
        app.press(send, 1.1)
    app.wait("Steer", timeout=4)
    time.sleep(0.6)
    app.shot("19-composer-steer-menu")
    app.terminate()

    select("long")
    open_lab_session(app)
    # iOS streams for 6 s. A screencap here takes about 2 s, so stream longer to land mid-stream.
    post(SESSION_LAB, "/lab/window-stream", {"durationMs": 12000, "intervalMs": 200})
    app.wait("Window stream update", timeout=8, containing=True)
    app.settle()
    app.shot("16-streaming")


def testRequestsHeaderAndSheet(app):
    post(APPROVAL, "/fixture/reset", {"mode": "permission", "emitEvent": True})
    open_fixture_session(app, APPROVAL, LAB_SESSION)
    open_review(app)
    app.wait("Allow Once")
    app.settle()
    app.shot("20-permission")
    app.terminate()

    post(APPROVAL, "/fixture/reset", {"mode": "question", "emitEvent": True})
    open_fixture_session(app, APPROVAL, LAB_SESSION)
    open_review(app)
    app.wait("Use native controls")
    app.settle()
    app.shot("21-question")
    app.terminate()

    select("markdown")
    post(SESSION_LAB, "/lab/context", {"percent": 85, "cost": 1.42})
    open_lab_session(app)
    app.wait("session-menu", timeout=20)
    app.wait_until(lambda: "85%" in label_within(app, "session-menu"), timeout=12, what="the 85% context ring")
    app.settle()
    app.shot("22-header-context")
    app.tap_until("session-menu", "Session Info")
    app.settle()
    app.shot("23-session-menu")
    adb("shell", "input", "keyevent", "4")
    app.wait_until(lambda: not app.find("Session Info"), timeout=4, what="the menu to close")

    app.tap("session-title")
    app.wait("session-sheet", timeout=6)
    app.settle()
    app.shot("24-session-sheet")


def testBackgroundShellSettingsAndError(app):
    post(GAUNTLET, "/lab/act", {"action": "reset"})
    post(GAUNTLET, "/lab/act", {"action": "shell-start", "sessionID": "ses_gauntlet_09", "text": "bun dev --port 3000", "bytes": 3000})
    post(GAUNTLET, "/lab/act", {"action": "tour-child-start", "sessionID": "ses_gauntlet_09"})
    app.launch(server=GAUNTLET, extra={
        "OPENCODE_LAB_LAST_SESSION_ID": "-",
        "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    })
    open_drawer(app)
    app.tap("drawer-session-row-ses_gauntlet_09")
    app.wait("background-activity", timeout=20)
    app.wait_until(lambda: "shell" in label_within(app, "background-activity"), timeout=8, what="the shell in the capsule")
    app.tap("background-activity")
    app.wait("background-work-panel", timeout=5)
    app.settle()
    app.shot("27-background-work")
    app.tap("background-shell-sh_gauntlet_1")
    app.wait("shell-output", timeout=6)
    app.settle()
    app.shot("28-shell-output")


def testSettingsDiagnosticsAndError(app):
    select("markdown")
    app.launch(server=SESSION_LAB, extra=lab_session_environment())
    open_drawer(app)
    app.tap("Settings")
    app.wait_until(lambda: navigation_title(app, "Settings"), timeout=6, what="Settings")
    app.settle()
    app.shot("31-settings")
    title = navigation_title(app, "Settings")
    for _ in range(4):
        if app.find("Diagnostics"):
            break
        app.drag(app.width // 2, int(app.height * 0.85), int(app.height * 0.6))
    app.tap("Diagnostics")
    app.wait_until(lambda: navigation_title(app, "Diagnostics", near=title), timeout=6, what="Diagnostics")
    app.settle()
    app.shot("32-diagnostics")
    app.terminate()

    post(APPROVAL, "/fixture/reset", {"mode": "initial-fetch-failure", "emitEvent": True})
    app.launch(server=APPROVAL, extra={
        "OPENCODE_LAB_LAST_SESSION_ID": "-",
        "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    })
    open_drawer(app)
    app.tap(f"drawer-session-row-{LAB_SESSION}")
    app.wait("request-load-error", timeout=20)
    app.settle()
    app.shot("33-error-banner")


TESTS = [
    testBackgroundShellSettingsAndError,
    testConnectScreens,
    testDraftComposerAndMenus,
    testDrawerCatchUpAndWhatsNew,
    testRequestsHeaderAndSheet,
    testSettingsDiagnosticsAndError,
    testTranscript,
]


# MARK: Steps

def lab_session_environment():
    return {
        "OPENCODE_LAB_LAST_SESSION_ID": LAB_SESSION,
        "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    }


def open_lab_session(app):
    open_fixture_session(app, SESSION_LAB, LAB_SESSION)


def open_fixture_session(app, server, id):
    app.launch(server=server, extra={
        "OPENCODE_LAB_LAST_SESSION_ID": "-",
        "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    })
    open_drawer(app)
    app.tap(f"drawer-session-row-{id}")
    # A request sheet can open with the session, and UI Automator does not see behind it.
    app.wait_until(lambda: any(app.find(label, nodes) for nodes in [app.nodes()] for label in SESSION_READY),
                   timeout=12, what="the session")


SESSION_READY = ["session-menu", "request-review-close", "Allow Once", "Use native controls"]


def open_drawer(app):
    app.wait_until(lambda: app.find_prefix("drawer-session-row-") or app.find("Open sessions"), timeout=20,
                   what="the drawer or its button")
    if not app.find_prefix("drawer-session-row-"):
        app.tap("Open sessions")
    app.wait_until(lambda: app.find_prefix("drawer-session-row-"), timeout=12, what="the drawer rows")
    time.sleep(0.4)


def open_review(app):
    def ready():
        nodes = app.nodes()
        return any(app.find(label, nodes) for label in ["request-review-close", "Allow Once", "Use native controls"]) or \
            any(n.desc.startswith("Review") or n.text.startswith("Review") for n in nodes)
    app.wait_until(ready, timeout=15, what="the review entry")
    if app.find("Allow Once") or app.find("Use native controls"):
        return
    if not app.find("request-review-close"):
        nodes = app.nodes()
        review = app.find("Review requests", nodes) or next(n for n in nodes if n.desc.startswith("Review") or n.text.startswith("Review"))
        app.tap(review)
    app.wait("request-review-close", timeout=8)


def hide_keyboard(app):
    if app.keyboard_shown():
        adb("shell", "input", "keyevent", "111")
        app.wait_until(lambda: not app.keyboard_shown(), timeout=4, what="the keyboard to hide")


def label_within(app, id):
    """The text a tagged element reads as: its own and every node drawn inside it."""
    nodes = app.nodes()
    outer = app.find(id, nodes)
    if not outer:
        return ""
    inside = [n for n in nodes if n.x1 >= outer.x1 and n.x2 <= outer.x2 and n.y1 >= outer.y1 and n.y2 <= outer.y2]
    return " ".join(f"{n.text} {n.desc}" for n in inside)


def navigation_title(app, title, near=None):
    """A sheet's inline title, or the one in the same place as an earlier title."""
    return next((n for n in app.nodes() if n.text == title and n.cls.endswith("TextView")
                 and (near is None or abs(n.y1 - near.y1) < 60)), None)


def reveal(app, fragment):
    """The open transcript follows the bottom. Short drags stop on the row instead of jumping past it."""
    for _ in range(24):
        nodes = app.nodes()
        target = app.find_containing(fragment, nodes)
        if in_band(app, target):
            return
        heading = app.find_containing(HEADING, nodes)
        nudge(app, drag_for(app, target, heading))
        time.sleep(0.28)
    if not in_band(app, app.find_containing(fragment)):
        raise CaptureError(f"could not reveal {fragment!r}")


def park_heading(app, fragment):
    for _ in range(16):
        heading = app.find_containing(fragment)
        if heading and 100 * 3 < heading.y1 < 220 * 3:
            return
        dy = -0.14 if heading and heading.y1 > 220 * 3 else 0.14
        nudge(app, dy)
        time.sleep(0.28)
    if not app.find_containing(fragment):
        raise CaptureError(f"could not park {fragment!r}")


def drag_for(app, target, heading):
    if target and target.height > 1:
        return 0.12 if (target.y1 + target.y2) / 2 < 220 * 3 else -0.12
    if heading and heading.y1 < 500 * 3:
        return -0.14
    return 0.14


def nudge(app, dy):
    start = 0.40 if dy > 0 else 0.64
    app.drag(app.width // 2, int(app.height * start), int(app.height * (start + dy)))


def in_band(app, node):
    return bool(node and node.height > 1 and node.y1 > 160 * 3 and node.y2 < app.height - 170 * 3)


# MARK: Fixture

def select(checkpoint):
    post(SESSION_LAB, "/lab/select", {"checkpoint": checkpoint})


def post(port, path, body):
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=8) as response:
        if response.status != 200:
            raise CaptureError(f"{port}{path} returned {response.status}")


# MARK: Device

def adb(*args, capture=False):
    result = subprocess.run([ADB, *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise CaptureError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout if capture else None


def keep_failure(name):
    """The screen when a step failed, beside the run rather than in the reference set."""
    folder = os.path.join(tempfile.gettempdir(), "android-capture-failures")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, f"{name}.png")
    with open(path, "wb") as file:
        file.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print(f"  screen kept at {path}", flush=True)


def screen_size():
    size = adb("shell", "wm", "size", capture=True)
    width, height = re.search(r"(\d+)x(\d+)", size.splitlines()[-1]).groups()
    return int(width), int(height)


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("light", "dark"):
        sys.exit(__doc__)
    appearance, output = sys.argv[1], sys.argv[2]
    only = set(sys.argv[3:])
    unknown = only - {test.__name__ for test in TESTS}
    if unknown:
        sys.exit(f"unknown tests: {', '.join(sorted(unknown))}")
    os.makedirs(output, exist_ok=True)
    app = App(appearance, output)
    failed = []
    for test in TESTS:
        if only and test.__name__ not in only:
            continue
        for attempt in (1, 2):
            print(f"{test.__name__} ({appearance}, attempt {attempt})", flush=True)
            try:
                test(app)
                break
            except Exception as error:
                print(f"  failed: {error}", flush=True)
                if not isinstance(error, CaptureError):
                    traceback.print_exc()
                keep_failure(f"{test.__name__}-{appearance}-{attempt}")
                if attempt == 2:
                    failed.append(test.__name__)
    app.terminate()
    if failed:
        sys.exit(f"failed: {', '.join(failed)}")


if __name__ == "__main__":
    main()
