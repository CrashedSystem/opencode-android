# Point Android at the iOS fixtures

The iOS screenshots in `ios/` were taken against five loopback servers in `.upstream/opencode-ios/scripts` at commit `553a407`. Start those servers, then point the Android app at the host.

## Start the servers

From `.upstream/opencode-ios`, run one command per server.

```sh
python3 scripts/session-lab.py --port 4110 serve
python3 scripts/gauntlet-fixture.py --port 4120 serve
python3 scripts/approval-fixture.py --port 4105
python3 scripts/connection-fixture.py --ports 4161 4162
python3 scripts/family-fixture.py --port 4165
```

Each process binds `127.0.0.1` only. If the port is already that fixture, leave it running. `scripts/capture-ios-reference.sh` does that check, then rebuilds the iOS app and recaptures the PNG set.

Identify a running server before you send it a control post.

| Port | Check | Expected field |
| --- | --- | --- |
| 4110 | `GET /lab/state` | `lab` is `session-rendering-v1` |
| 4120 | `GET /lab/state` | `lab` is `responsiveness-gauntlet-v1` |
| 4105 | `GET /api/info` | `version` is `approval-fixture` |
| 4161 | `GET /api/info` | `version` is `2.0.0-fixture` |
| 4165 | `GET /api/info` | `version` is `family-fixture` |

`GET /api/info` on the session lab reports `pid` 4105. That value is a constant copied from the approval fixture, not the process id. Use `/lab/state` to identify it.

## Reach them from the Android emulator

The emulator's [network address space](https://developer.android.com/studio/run/emulator-networking-address) maps `10.0.2.2` to the host loopback (`127.0.0.1`). A fixture on `127.0.0.1:4110` is `http://10.0.2.2:4110` inside the emulator. This session did not start an Android emulator.

A physical phone cannot use that address. These servers do not listen on the LAN.

## Drive the same states

`scripts/ios-reference/CaptureUITests/CaptureTests.swift` launches `ai.opencode.mobilecode` with `OPENCODE_LAB_SERVER_URL` set to one server. The lab session id is `ses_approval_root`. Gauntlet sessions are `ses_gauntlet_01` through `ses_gauntlet_09`.

### Session lab on port 4110

`POST /lab/select` with `{"checkpoint":"<name>"}` loads a transcript. The screenshots use `intro`, `typography`, `files`, `tool-completed`, `subagent`, `tool-images`, `tool-running`, `long`, and `markdown`.

Two other posts change a loaded session.

- `POST /lab/context` with `percent` and `cost` sets the header ring. The context shot uses `85` and `1.42`.
- `POST /lab/window-stream` with `durationMs` and `intervalMs` grows the latest reply. The streaming shot uses `6000` and `200`.

The app also calls `/api/info`, `/api/project`, `/api/session`, `/api/session/active`, `/api/event`, and the session message routes.

### Gauntlet on port 4120

`POST /lab/act` changes a session. The drawer shot resets, posts `start` on `ses_gauntlet_04`, posts `ask` on `ses_gauntlet_05`, and pins `ses_gauntlet_02` and `ses_gauntlet_03`. The background-work shot posts `shell-start` and `tour-child-start` on `ses_gauntlet_09`.

Titles, in order, are "Investigate slow startup", "Fix composer keyboard overlap", "Review push notification plan", "Add cursor-paged history", "Profile transcript rendering", "Tidy attachment picker", "Design the new session card", "Audit SSE reconnect timing", and "Polish the native feature tour".

The app also calls `/api/info`, `/api/project`, `/api/session`, `/api/session/active`, `/api/model`, `/api/shell`, `/api/form`, `/api/question/request`, and `/api/event`.

### Approval fixture on port 4105

`POST /fixture/reset` sets the mode. The permission shot uses `permission`, the question shot uses `question`, and the error banner uses `initial-fetch-failure`. Pass `emitEvent` true so the open app sees the change. `GET /fixture/state` reads the mode.

Other modes are `normal`, `permission-no-save`, `permission-empty`, `submit-failure`, `expired`, `empty-options`, `empty-question`, `many-options`, and `malformed-form`.

### Connection fixture on ports 4161 and 4162

The current PNG set does not use this server. Port 4161 is server A and port 4162 is server B. `POST /fixture/config` on either port sets `delays_ms` and `statuses`. `{"reset":true}` clears both. `GET /fixture/metrics` returns sanitized request metadata. The session list on A is "Alpha server session". The session list on B is "Beta server session".

### Family fixture on port 4165

The current PNG set does not use this server. It is read-only. One root session, `root`, has 30 children. `GET /api/session?parentID=root` returns the children. Writes return 405. `GET /fixture/metrics` returns the config, including `delayMs` 200. `POST /fixture/reset` restores the set.
