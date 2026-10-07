#!/usr/bin/env python3
"""Drive the debug app through its main flows on the iOS fixtures, and browse a live server read-only.

  e2e.py OUTPUT_DIR [flow ...]

Each flow drives the app on ANDROID_SERIAL, checks the result on screen and in the
fixture's own state, and keeps a screenshot. `liveServerReadOnly` reads the password
from `opencode service get password` at run time and clears the app's data when it is
done. It never prompts, creates, renames, or deletes anything there; opening a session
sends that session's read receipt, as any client does.
"""

import json
import os
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(__file__))
from capture import (
    APPROVAL, GAUNTLET, LAB_SESSION, PACKAGE, SESSION_LAB, App, CaptureError,
    adb, label_within, open_drawer, open_fixture_session, open_lab_session, open_review, post, select,
)

ADB_PATH = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
LIVE_PORT = 49374
PAIRING_PORT = 4171
PAIRING_CODE = "e2e-pair-code"


def check(condition, message):
    if not condition:
        raise CaptureError(message)


def state(port, path="/lab/state"):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=8) as response:
        return json.load(response)


def eventually(read, timeout=10, what="the fixture"):
    deadline = time.time() + timeout
    while True:
        value = read()
        if value:
            return value
        if time.time() > deadline:
            raise CaptureError(f"timed out waiting for {what}")
        time.sleep(0.5)


def drawer_rows(app):
    return [n for n in app.nodes() if n.id.startswith("drawer-session-row-")]


def row_state(app, id):
    """What a drawer row shows: its section, a running spinner, and the colour of its dot."""
    nodes = app.nodes()
    row = app.find(f"drawer-session-row-{id}", nodes)
    check(row, f"{id} is in the drawer")
    pinned_header = app.find("Pinned", nodes)
    recents_header = app.find("Recents", nodes)
    pinned = bool(pinned_header and pinned_header.y1 < row.y1 and (not recents_header or row.y1 < recents_header.y1))
    running = any(n.desc == "Running" and row.y1 <= n.y1 and n.y2 <= row.y2 for n in nodes)
    return {"pinned": pinned, "running": running, "dot": dot_colour(row.x2 - 200, row.y1, row.x2, row.y2)}


def dot_colour(x1, y1, x2, y2):
    """Blue for unread, orange for attention, read from the pixels the way a person sees them."""
    png = subprocess.run([ADB_PATH, "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    ppm = subprocess.run(["magick", "png:-", "-crop", f"{x2 - x1}x{y2 - y1}+{x1}+{y1}", "-depth", "8", "ppm:-"],
                         input=png, capture_output=True, check=True).stdout
    pixels = ppm.split(b"\n", 3)[3]
    rgb = [tuple(pixels[i:i + 3]) for i in range(0, len(pixels) - 2, 3)]
    blue = sum(1 for r, g, b in rgb if b > 200 and r < 90 and 90 < g < 190)
    orange = sum(1 for r, g, b in rgb if r > 220 and 110 < g < 190 and b < 80)
    if max(blue, orange) < 20:
        return None
    return "blue" if blue > orange else "orange"


def send_from_composer(app, text):
    app.tap(app.field("Message"))
    app.type_text(text)
    app.tap_until("Send message", text, timeout=8)


# MARK: Flows

def connectManually(app):
    adb("shell", "pm", "clear", PACKAGE)
    app.launch(server=None)
    app.tap("Enter a Server Manually", timeout=20)
    app.tap(app.field("Server address"))
    app.type_text(f"http://10.0.2.2:{GAUNTLET}")
    app.tap("Connect")
    open_drawer(app)
    check(app.find("drawer-session-row-ses_gauntlet_01"), "the gauntlet's sessions are listed")
    app.shot("e2e-01-connect-manual")


def connectWithPairingLink(app):
    with PairingServer() as pairing:
        adb("shell", "pm", "clear", PACKAGE)
        adb("shell", "pm", "grant", PACKAGE, "android.permission.CAMERA")
        app.launch(server=None)
        # Put the link on the clipboard the way a person would: type it, select it, copy it.
        app.tap("Enter a Server Manually", timeout=20)
        app.tap(app.field("Server address"))
        app.type_text(f"http://10.0.2.2:{PAIRING_PORT}/auth/connect/{PAIRING_CODE}")
        adb("shell", "input", "keycombination", "113", "29")
        adb("shell", "input", "keycombination", "113", "31")
        app.tap("Cancel")
        app.tap("Scan Pairing Code")
        app.tap("Paste", timeout=10)
        open_drawer(app)
        check(pairing.redeemed == [PAIRING_CODE], f"the app redeemed the code once, saw {pairing.redeemed}")
        check(pairing.authorized, "later requests carry the redeemed token")
        check(app.find("drawer-session-row-ses_gauntlet_01"), "the paired server's sessions are listed")
        app.shot("e2e-02-connect-pairing-link")


def drawer(app):
    post(GAUNTLET, "/lab/act", {"action": "reset"})
    post(GAUNTLET, "/lab/act", {"action": "start", "sessionID": "ses_gauntlet_04"})
    post(GAUNTLET, "/lab/act", {"action": "ask", "sessionID": "ses_gauntlet_05"})
    adb("shell", "pm", "clear", PACKAGE)
    app.launch(server=GAUNTLET, extra={"OPENCODE_LAB_LAST_SESSION_ID": "-"})
    button = app.wait("Open sessions", timeout=20)
    app.wait_until(lambda: dot_colour(button.x2 - 50, max(0, button.y1 - 10), button.x2 + 10, button.y1 + 50) == "orange",
                   timeout=15, what="the attention badge on the drawer button")
    open_drawer(app)
    check(row_state(app, "ses_gauntlet_05")["dot"] == "orange", "the asking session shows the attention dot")
    check(row_state(app, "ses_gauntlet_04")["running"], "the started session shows the running spinner")
    check(row_state(app, "ses_gauntlet_01")["dot"] == "blue", "an unviewed session shows the unread dot")

    app.press(app.wait("drawer-session-row-ses_gauntlet_02"), 0.8)
    app.tap("Pin", timeout=3)
    app.wait_until(lambda: row_state(app, "ses_gauntlet_02")["pinned"], timeout=6, what="the pin")
    app.shot("e2e-03-drawer-pins-dots")

    app.tap(app.field("Search sessions"))
    app.type_text("composer")
    app.wait_until(lambda: [n.id for n in drawer_rows(app)] == ["drawer-session-row-ses_gauntlet_02"], timeout=8,
                   what="search to leave only the matching session")
    app.shot("e2e-04-drawer-search")

    app.launch(server=GAUNTLET, extra={"OPENCODE_LAB_LAST_SESSION_ID": "-"})
    open_drawer(app)
    app.tap("drawer-catch-up")
    app.wait("catch-up-done", timeout=8)
    before = app.wait(" Left", containing=True).text
    app.tap("Mark as Read")
    app.wait_until(lambda: (n := app.find_containing(" Left")) and n.text != before, timeout=8, what="Catch Up to advance")
    after = app.find_containing(" Left").text
    check(int(after.split()[0]) == int(before.split()[0]) - 1, f"Catch Up went from {before!r} to {after!r}")
    app.shot("e2e-05-catch-up")


def sendPrompt(app):
    post(GAUNTLET, "/lab/act", {"action": "reset"})
    open_fixture_session(app, GAUNTLET, "ses_gauntlet_01")
    text = "Hello from the Android e2e run"
    send_from_composer(app, text)
    posted = eventually(lambda: [p for p in state(GAUNTLET)["posts"] if p.get("text") == text], what="the prompt POST")
    check(len(posted) == 1, f"one prompt delivered, saw {len(posted)}")
    app.wait(f"Reply to: {text}", timeout=10, containing=True)
    app.shot("e2e-06-prompt-delivered")


def streamingFollowAndBrowse(app):
    select("long")
    open_lab_session(app)
    post(SESSION_LAB, "/lab/window-stream", {"durationMs": 16000, "intervalMs": 200})
    app.wait("Window stream update 010", timeout=10, containing=True)
    followed = latest_update(app)
    time.sleep(1.5)
    check(latest_update(app) > followed, "the view follows the stream to the bottom")
    app.shot("e2e-07-stream-following")

    app.drag(app.width // 2, int(app.height * 0.35), int(app.height * 0.75))
    time.sleep(0.6)
    parked = top_text(app)
    time.sleep(2)
    check(top_text(app) == parked, "browsing up holds the reader's place while the stream grows")
    check(app.find("Scroll to latest"), "Scroll to latest shows while browsing")
    app.shot("e2e-08-stream-browsing")
    eventually(lambda: not state(SESSION_LAB)["windowStreamActive"], timeout=20, what="the stream to end")
    app.tap("Scroll to latest")
    app.wait_until(lambda: latest_update(app) >= 75, timeout=6, what="the last update after jumping")
    app.shot("e2e-09-stream-ended")


def latest_update(app):
    numbers = [int(n.text.split("Window stream update ")[1][:3]) for n in app.nodes()
               if "Window stream update " in n.text]
    return max(numbers, default=0)


def top_text(app):
    texts = sorted((n for n in app.nodes() if n.text and 300 < n.y1 < app.height * 0.5), key=lambda n: n.y1)
    return (texts[0].text, texts[0].y1) if texts else None


def steerAndQueue(app):
    select("tool-running")
    open_lab_session(app)
    app.wait("Working", timeout=20)
    for text, delivery in [("Steer this from Android", "Steer"), ("Queue this from Android", "Queue")]:
        app.tap(app.field("Message"))
        app.type_text(text)
        send = app.wait("Steer message", timeout=5)
        app.press(send, 0.9)
        if not app.exists(delivery, timeout=2):
            app.press(send, 1.1)
        app.tap(delivery, timeout=4)
        # The lab admits a prompt sent mid-run after 2 s and delivers it 2 s later.
        pending = eventually(lambda: [i for i in lab_inbox() if i["payload"]["text"] == text], timeout=10,
                             what=f"{text!r} in the lab's inbox")
        check(pending[0]["delivery"] == delivery.lower(), f"{text!r} went out as {pending[0]['delivery']}")
        eventually(lambda: any(text in json.dumps(m) for m in lab_messages()), timeout=10, what=f"{text!r} to be delivered")
        app.wait(text, timeout=8)
    app.shot("e2e-10-steer-and-queue")


def lab_inbox():
    return get(SESSION_LAB, f"/api/session/{LAB_SESSION}/inbox")["data"]


def lab_messages():
    return get(SESSION_LAB, f"/api/session/{LAB_SESSION}/message?limit=200")["data"]


def get(port, path):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=8) as response:
        return json.load(response)


def answerPermission(app):
    post(APPROVAL, "/fixture/reset", {"mode": "permission", "emitEvent": True})
    open_fixture_session(app, APPROVAL, LAB_SESSION)
    open_review(app)
    app.tap("Allow Once")
    replied = eventually(lambda: not state(APPROVAL, "/fixture/state")["pendingPermissionIDs"], what="the permission reply")
    check(replied, "the permission is answered")
    posts = state(APPROVAL, "/fixture/state")["posts"]
    check(any("once" in json.dumps(p) for p in posts), f"the reply was Allow Once: {posts}")
    app.shot("e2e-11-permission-answered")


def answerQuestion(app):
    post(APPROVAL, "/fixture/reset", {"mode": "question", "emitEvent": True})
    open_fixture_session(app, APPROVAL, LAB_SESSION)
    open_review(app)
    app.tap("Use native controls")
    app.tap("Next")
    app.tap(app.field("Your answer"))
    app.type_text("The drawer and the composer")
    app.tap("Send Answers")
    eventually(lambda: not state(APPROVAL, "/fixture/state")["pendingFormIDs"], timeout=10, what="the question reply")
    answer = json.dumps(state(APPROVAL, "/fixture/state")["posts"])
    check("Use native controls" in answer or "native" in answer, f"the choice went out: {answer[:200]}")
    check("The drawer and the composer" in answer, f"the written answer went out: {answer[:200]}")
    app.wait_until(lambda: not app.find("Send Answers"), timeout=6, what="the question sheet to close")
    app.shot("e2e-12-question-answered")


def sessionMenu(app):
    select("markdown")
    adb("shell", "pm", "clear", PACKAGE)
    open_lab_session(app)
    app.tap_until("session-menu", "Rename")
    app.tap("Rename")
    app.wait("Rename Session", timeout=6)
    app.tap(next(n for n in app.nodes() if n.cls.endswith("EditText")))
    adb("shell", "input", "keyevent", "123", *["67"] * 60)
    app.type_text("Renamed from Android")
    app.tap("Rename")
    eventually(lambda: state(SESSION_LAB)["title"] == "Renamed from Android", what="the rename")
    app.wait("Renamed from Android", timeout=6)

    app.tap_until("session-menu", "Pin")
    app.tap("Pin")
    app.tap_until("session-menu", "Mark as Unread")
    app.tap("Mark as Unread")
    open_drawer(app)
    row = row_state(app, LAB_SESSION)
    check(row["pinned"] and row["dot"] == "blue", f"the drawer row is pinned and unread: {row}")
    app.shot("e2e-13-session-menu")

    app.tap(f"drawer-session-row-{LAB_SESSION}")
    app.tap_until("session-title", "session-sheet")
    check(app.find("Renamed from Android") and app.find("Unpin"), "the sheet shows the new title and the pin")
    app.shot("e2e-14-session-sheet")


def deleteSession(app):
    post(GAUNTLET, "/lab/act", {"action": "reset"})
    open_fixture_session(app, GAUNTLET, "ses_gauntlet_08")
    app.tap_until("session-menu", "Delete Session")
    app.tap("Delete Session")
    app.wait("This removes the session from the server for all clients.", timeout=4)
    app.shot("e2e-15-delete-confirmation")
    buttons = [n for n in app.nodes() if n.text in ("Delete", "Delete Session")]
    app.tap(buttons[-1])
    eventually(lambda: all(s["id"] != "ses_gauntlet_08" for s in state(GAUNTLET)["sessions"]), what="the delete")
    open_drawer(app)
    check(not app.find("drawer-session-row-ses_gauntlet_08"), "the deleted session left the drawer")
    app.shot("e2e-16-deleted")


def backgroundWorkAndShell(app):
    post(GAUNTLET, "/lab/act", {"action": "reset"})
    post(GAUNTLET, "/lab/act", {"action": "shell-start", "sessionID": "ses_gauntlet_09", "text": "bun dev --port 3000", "bytes": 3000})
    open_fixture_session(app, GAUNTLET, "ses_gauntlet_09")
    app.wait_until(lambda: "shell" in label_within(app, "background-activity"), timeout=15, what="the shell capsule")
    app.tap("background-activity")
    app.tap("background-shell-sh_gauntlet_1", timeout=6)
    app.wait("shell-output", timeout=6)
    app.wait("Running", timeout=6, containing=True)
    check(not app.find("Scroll to latest"), "the running output follows its tail")
    app.shot("e2e-17-shell-output")
    app.tap("Stop Shell")
    app.wait("Stop This Shell?", timeout=4)
    app.tap([n for n in app.nodes() if n.text == "Stop Shell"][-1])
    eventually(lambda: all(s["status"] != "running" for s in shells(GAUNTLET)), timeout=10, what="the shell to stop")
    app.wait("Stopped", timeout=8, containing=True)
    app.shot("e2e-18-shell-stopped")


def shells(port):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/shell?location%5Bdirectory%5D=/fixture/gauntlet/api", timeout=8) as response:
        return json.load(response).get("data", [])


def settingsAndDiagnostics(app):
    select("markdown")
    app.launch(server=SESSION_LAB, extra={"OPENCODE_LAB_LAST_SESSION_ID": LAB_SESSION})
    open_drawer(app)
    app.tap("Settings")
    app.wait("Change Server", timeout=6)
    app.tap("Diagnostics")
    app.wait("Record diagnostics", timeout=6)
    app.shot("e2e-19-diagnostics")


def liveServerReadOnly(app):
    password = subprocess.run(["opencode", "service", "get", "password"], capture_output=True, text=True, check=True).stdout.strip()
    try:
        adb("shell", "pm", "clear", PACKAGE)
        app.launch(server=LIVE_PORT, extra={"OPENCODE_LAB_LAST_SESSION_ID": "-", "OPENCODE_PASSWORD": password})
        open_drawer(app)
        rows = drawer_rows(app)
        check(len(rows) >= 3, f"the live drawer lists sessions, saw {len(rows)}")
        app.shot("e2e-20-live-drawer")
        for index in range(3):
            open_drawer(app)
            row = drawer_rows(app)[index]
            app.tap(row)
            app.wait("session-menu", timeout=20)
            time.sleep(1.5)
            for _ in range(4):
                app.drag(app.width // 2, int(app.height * 0.3), int(app.height * 0.75))
                time.sleep(0.5)
            check(app.find("session-menu"), "the transcript scrolls without leaving the session")
            app.shot(f"e2e-{21 + index}-live-session")
    finally:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "pm", "clear", PACKAGE)


FLOWS = [
    connectManually,
    connectWithPairingLink,
    drawer,
    sendPrompt,
    streamingFollowAndBrowse,
    steerAndQueue,
    answerPermission,
    answerQuestion,
    sessionMenu,
    deleteSession,
    backgroundWorkAndShell,
    settingsAndDiagnostics,
    liveServerReadOnly,
]


# MARK: Pairing

class PairingServer:
    """`opencode pair` on a loopback port: redeems one code, then forwards to the gauntlet."""

    def __init__(self):
        self.redeemed = []
        self.authorized = False
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.forward()

            def do_POST(self):
                self.forward()

            def forward(self):
                if self.path.startswith("/auth/connect/"):
                    outer.redeemed.append(self.path.rsplit("/", 1)[1])
                    body = json.dumps({"token": "paired-token"}).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                if "Basic" in (self.headers.get("Authorization") or ""):
                    outer.authorized = True
                length = int(self.headers.get("Content-Length") or 0)
                request = urllib.request.Request(f"http://127.0.0.1:{GAUNTLET}{self.path}", method=self.command,
                                                 data=self.rfile.read(length) if length else None,
                                                 headers={k: v for k, v in self.headers.items() if k.lower() != "host"})
                try:
                    upstream = urllib.request.urlopen(request, timeout=60)
                except urllib.error.HTTPError as error:
                    upstream = error
                self.send_response(upstream.status)
                for key, value in upstream.headers.items():
                    if key.lower() not in ("transfer-encoding", "connection", "content-length"):
                        self.send_header(key, value)
                self.send_header("Connection", "close")
                self.end_headers()
                try:
                    while chunk := upstream.read1(65536):
                        self.wfile.write(chunk)
                        self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", PAIRING_PORT), Handler)
        self.server.daemon_threads = True

    def __enter__(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    output = sys.argv[1]
    only = set(sys.argv[2:])
    unknown = only - {flow.__name__ for flow in FLOWS}
    if unknown:
        sys.exit(f"unknown flows: {', '.join(sorted(unknown))}")
    os.makedirs(output, exist_ok=True)
    app = App("light", output)
    results = []
    for flow in FLOWS:
        if only and flow.__name__ not in only:
            continue
        print(flow.__name__, flush=True)
        try:
            flow(app)
            results.append((flow.__name__, "pass", ""))
        except Exception as error:
            if not isinstance(error, CaptureError):
                traceback.print_exc()
            results.append((flow.__name__, "fail", str(error)))
            try:
                app.shot(f"e2e-failed-{flow.__name__}")
            except Exception:
                pass
    app.terminate()
    for name, result, detail in results:
        print(f"{result.upper():4}  {name}  {detail}")
    if any(result == "fail" for _, result, _ in results):
        sys.exit(1)


if __name__ == "__main__":
    main()
