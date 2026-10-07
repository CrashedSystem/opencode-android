# Protocol and data layer

Source: `/Users/nxl/code/sandbox/opencode-android/.upstream/opencode-ios`, commit `4fb5e0e`. The app speaks **current V2 only**. `AGENTS.md` lines 8–9: compare a running server’s `/openapi.json` with these routes when changing them; do not add fallbacks for older routes; never turn a failed request snapshot into empty attention state. REST snapshots are authoritative; SSE is an invalidation and projection stream (`AGENTS.md` lines 6–8).

Almost every HTTP call lives in `OpenCode/OpenCodeClient.swift`. File reads and range downloads bypass `perform` and use `URLRequest`s built by `OpenCodeServer.fileReadRequest`.

## Components Found

| Piece | Type | File | Role |
| --- | --- | --- | --- |
| `OpenCodeClient` | struct of `@Sendable` closures, `DependencyKey` | `OpenCode/OpenCodeClient.swift` 1480–1590, 3326–3330 | The only protocol façade. Not `@DependencyClient` (typed `throws(OpenCodeClientError)`). |
| `OpenCodeServer` | Codable credentials + URL helpers | same, 29–186 | Current URL, alternates, Basic auth, endpoint builder, file-read URLs, reachability rank. |
| `OpenCodePairingLink` | QR/link parser | same, 194–263 | `opencode pair` JSON or `https://…/auth/connect/<code>`. |
| Wire payloads | private `Decodable` structs inside the client extension | same, 2907–3291 | Never leak to features. Mapped to public models before return. |
| `OpenCodeServerEvent` / `OpenCodeLiveContentEvent` | semantic SSE | same, 1305–1411 | What reducers see. Not the raw `type` string. |
| `EventBatcher` | private actor | same, 1432–1470 | 75 ms / 256-event coalesce of content deltas. |
| `OpenCodeReadCache` | private actor | same, 21–27 | In-process model-catalog cache. |
| `OpenCodeClientError` | error | same, 427–437 | `message`, optional `statusCode`, `isUnsupported`. |
| `OpenCodeJSONValue` | JSON tree | same, 439–512 | Tool input, metadata, form-adjacent blobs. |
| `OpenCodeFormRequest` and friends | public Decodable | `OpenCode/FormModels.swift` | Questions arrive as forms. |
| `OpenCodeFileReference` / `OpenCodePromptPresentation` | prompt wire folding | `OpenCode/ComposerFileAttachment.swift` 6–49 | Desktop-compatible attachment metadata. |
| `AgentFileLoader` / `StreamingFileLoader` | file GET | `OpenCode/AgentFiles/AgentFileLoader.swift` | Probe, download, AVFoundation range proxy. |
| `SessionExport` | export unwrap | `OpenCode/SessionExport.swift` | Turns export JSON into shareable Markdown or `SessionTransfer` JSON. |
| `KeychainStorage` / `KeychainKey` | secrets | `OpenCode/Keychain.swift` | `@Shared` over the keychain. |
| `SharedKeys` | preferences and caches | `OpenCode/SharedKeys.swift` | UserDefaults, file JSON, in-memory, transcript file. |
| `UndeliveredPrompts` | process-memory | `OpenCode/UndeliveredPrompts.swift` | Failed sends until a screen shows them. |
| `ShareHandoffStore` | App Group queue | `OpenCode/ShareHandoff.swift` | Share extension never talks to the server. |
| Connection probe | `AppFeature.connect` / `redeem` | `OpenCode/AppFeature.swift` 502–579 | Parallel health by reachability rank; pairing then normal connect. |
| SSE reconnect | `HomeFeature.eventStream` | `OpenCode/HomeFeature.swift` 1223–1267 | Caller-owned reconnect. The client stream throws and stops. |

Public client methods and the private request functions that implement them are at `OpenCodeClient.swift` 1481–1545 and 1603–2752.

## Flow

1. **Pair or type credentials.** Scanner text goes to `OpenCodePairingLink.decode` (`AppFeature.swift` 321–338). A valid non-loopback link is redeemed (`redeem`, 502–519) via `GET /auth/connect/{code}` with no `Authorization`. The token becomes `password`; username is always `"opencode"`. Then the same connect path as a manual server.
2. **Connect.** `AppFeature.connect` (522–579) probes every `candidateURLs` entry with `GET /api/info` in parallel. The winner is the healthiest reachability rank that has finished, not the first response. Winner is remembered in the keychain (`server.remember()`, 396–402) and a home snapshot is loaded.
3. **Home snapshot.** Parallel `GET /api/project` (bare array), `GET /api/session?parentID=null&order=desc&limit=50` (`{data}`), `GET /api/session/active` (`{data}` map). Then metadata-only `GET /api/session/{id}` for active sessions that are not already roots, batches of 6, walking parents. 404 on one ancestor is ignored; any other status fails the snapshot (`1722–1759`).
4. **Event stream.** `HomeFeature.task` starts `eventStream` (612–619). Sequential try of each candidate URL against `GET /api/event`. On `server.connected`, adopt that URL, refresh the snapshot, tell the open session the socket is new. Content frames are batched, then mapped to `SessionFeature` actions. Disconnect retries forever except HTTP 401.
5. **Open a session.** First frame may be the disk transcript cache. Authoritative read is `GET /api/session/{id}/message?order=desc&limit=12` then a 30-message window (`SessionFeature.initialFetchLimit = 30`, `firstFrameFetchLimit = 12`, `SessionFeature.swift` 6–10). Family, inbox, permissions/forms, and running shells load beside it.
6. **Send.** Client mints `msg_` + UUID with hyphens stripped (`SessionFeature.swift` 1332). `POST /api/session/{id}/prompt` with that `id`. A lost response (no HTTP status) is reconciled up to 3 times, 1 s apart, by `GET …/message/{id}` then `GET …/inbox`; a real HTTP error is reconciled once (`2530–2545`). Then `POST …/wait` (600 s) for idle; tokens arrive on SSE, not on wait.
7. **Live projection.** Deltas upsert transcript rows by semantic part id. Durable boundaries (`text.ended`, tool called/succeeded/failed, `step.ended`) schedule a REST settlement. A settlement of a message already in the window is one `GET …/message/{messageID}` after 200 ms, not a full page (`2716–2750`). Page refreshes must not re-append text deltas (`AGENTS.md` line 8; `applyLiveEvent` / `liveText`).
8. **Foreground.** `HomeView` scene `.active` sends `retryConnectionTapped`, which restarts the stream and refreshes the snapshot (`HomeView.swift` 83–88, `HomeFeature.swift` 696–705). Background flushes the transcript cache and publishes share targets.

## HTTP routes

Default timeout is **10 s** unless noted. Success is HTTP 200–299. Body, when present, is `Content-Type: application/json`, `Accept: application/json`, plus `Authorization` when a password is set (`perform`, 2781–2847). No custom headers otherwise. URLSession follows redirects; the client has no redirect delegate.

Query name `location[directory]` is required for V2 location scope. A bare `directory` is ignored by the server (`OpenCodeClient.swift` 138–139, 2428–2430). `URLComponents` percent-encodes spaces as `%20` and brackets in the name (`WireRequestTests.swift` 95–114).

| Method | Path | Query | Body | Response the client decodes | Timeout | Caller |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/info` | none | none | Health: `{version: string, pid: int}` required (`StatusPayload` 3132–3137). Also read as `InfoPayload` `{urls?: [string], paths?: {tmp: string}}` (3143–3147). Extra fields ignored. | 10 s (health); upload path same | `AppFeature.connect` via `health`. Pairing follow-up and uploads via `InfoPayload`. |
| GET | `/auth/connect/{code}` | none | none | **Unwrapped** `{token: string}` (`PairingSessionPayload` 3139–3141). No `Authorization`. | 5 s | `requestRedeemPairing` 1608–1661. `AppFeature.redeem`. |
| GET | `/api/project` | none | none | **Bare array**, not `{data}`: `{id, canonical, name?}` (`ProjectPayload` 3149–3153; call at 1686). Name fallback is last path component of `canonical`. | 10 s | `homeSnapshot`. |
| GET | `/api/session` | `parentID=null`, `order=desc`, `limit=50` | none | `{data: [Session]}` | 10 s | `homeSnapshot` (1687–1695). |
| GET | `/api/session` | `parentID={id}`, `order=desc`, `limit=100` | none | `{data: [Session]}` | 10 s | `sessionFamily` (2356–2364). `SessionFeature.refreshFamily`. |
| GET | `/api/session/active` | none | none | `{data: {sessionID: {type: string}}}` (`ActiveSessionPayload` 3198–3200). `type` is required and unread. Keys are the active set. | 10 s | `homeSnapshot`, `sessionFamily`. |
| GET | `/api/session/{id}` | none | none | `{data: Session}` | 10 s | Ancestry (`1738`), `getSession`, `requestRootSessionID` (2532). 404 during ancestry is skipped; elsewhere it is an error. |
| POST | `/api/session` | none | `{location: {directory: string}}` (2315–2318) | `{data: Session}` | 10 s | `createSession`: `SessionFeature.deliver`, `VoiceWorkClient.submit`, `PhoneReportsDelivery`. |
| PATCH | `/api/session/{id}` | none | `{title: string}` (2263) | body ignored | 60 s if title is `""` (server generates a title); else 10 s | `SessionSheetFeature` rename and auto-rename. |
| DELETE | `/api/session/{id}` | none | none | body ignored | 10 s | `HomeFeature` delete confirmation. Removes the session for every client. |
| POST | `/api/session/{id}/view` | none | `{idle: int64}` exact millisecond watermark (`2305–2307`, test `OpenCodeClientDecodingTests.swift` 55–68) | body ignored (204 in tests) | 10 s | `HomeFeature.acknowledgeView`, `SessionFeature.acknowledgeViewed`. Only when `idle` is known and newer than `viewed`. |
| POST | `/api/session/{id}/prompt` | none | `PromptBody` below | body ignored | 30 s | `sendPrompt` (composer), `sendDeliveredPrompt` (voice; client mints `msg_` + UUID), `PhoneReportsDelivery` (queue). |
| GET | `/api/session/{id}/message` | `order=desc`, `limit=N` | none | `{data: [Message], cursor?: {next?: string}}`. Server order is newest first; transcript decode **reverses** to chronological (`1816–1818`). | 10 s | `transcript` (limit 12 or 30), `assistantReply` (limit 40), `contextUsage` (limit 8, **not** reversed). |
| GET | `/api/session/{id}/message` | `limit=40` plus either `cursor` **or** `order=desc`, never both (`1806–1811`) | none | same page shape; `cursor.next` kept opaque (`OpenCodeClientDecodingTests.swift` 341–346) | 10 s | `historyPage`. `SessionFeature.loadEarlierHistory`. |
| GET | `/api/session/{id}/message/{messageID}` | none | none | `{data: Message}` one message, expanded to transcript items (`1786–1798`) | 10 s | Settlement, prompt echo, lost-POST reconcile. |
| POST | `/api/experimental/session/{id}/wait` | none | none | body ignored | **600 s** | After a composer send (`SessionFeature.swift` 2568–2570) and `VoiceWorkClient.awaitReply`. |
| POST | `/api/session/{id}/interrupt` | none | none | body ignored | 10 s | `SessionFeature` stop. |
| POST | `/api/session/{id}/compact` | none | `{}` (`emptyJSONObject`, 2091, 2279) | body ignored | 10 s | `SessionSheetFeature`. Queues compaction after the current run. |
| POST | `/api/session/{id}/fork` | none | `{}` | `{data: Session}` | 10 s | `SessionSheetFeature`. New root copy. |
| GET | `/api/experimental/session/{id}/export` | none | none | raw bytes, not decoded by the client (`2297–2301`). `SessionExport` expects `{data: {info, messages}}` (`SessionExport.swift` 9–13). | 60 s | `SessionSheetFeature`, then share sheet. |
| POST | `/api/session/{id}/model` | none | `{model: {id, providerID}}` (2455–2460). `id` is the API model id, not `catalogID`. | body ignored | 10 s | `switchModel` after create (failure swallowed with `try?`) and model picker. |
| GET | `/api/model` | `location[directory]` if a directory is known | none | `{data: [ModelInfo]}` | 20 s | `modelCatalog`. |
| GET | `/api/model/default` | same location query | none | `{data: ModelInfo or null}` | 20 s | Same call. **Any failure is swallowed** (`try?`, 2437–2442). Missing route leaves the catalog usable with `defaultModel == nil`. |
| GET | `/api/permission/request` | `location[directory]` | none | `{data: [Permission]}` | **60 s** (`locationBootTimeout`, 2477) | `familyPendingRequests`, `homeAttention`. |
| GET | `/api/form` | `location[directory]` | none | `{data: [Form]}` | 60 s | Same. Questions are forms with `metadata.kind == "question"`. |
| POST | `/api/session/{id}/permission/{requestID}/reply` | none | `{decision: "once"\|"always"\|"reject"}` (`2712–2724`, test 213–222) | body ignored | 10 s | `SessionFeature` permission sheet. `id` is the **owning** session, which may be a child. |
| POST | `/api/session/{id}/form/{formID}/reply` | none | `{answer: {key: string\|number\|bool\|[string]}}` (2727–2738) | body ignored | 10 s | Form / question submit. |
| DELETE | `/api/session/{id}/form/{formID}` | none | none | body ignored | 10 s | Form cancel. There is no permission-cancel route in this client. |
| GET | `/api/session/{id}/inbox` | none | none | `{data: [InboxItem]}` (`PendingInputsPayload` 2907–2922) | 10 s | `pendingInputs`. Polled with permissions while a session is open. |
| PATCH | `/api/session/{id}/inbox/{inputID}` | none | `{delivery: "steer"\|"queue"}` (2567–2578) | body ignored | 10 s | Send Now (switch to steer). |
| DELETE | `/api/session/{id}/inbox/{inputID}` | none | none | body ignored | 10 s | Cancel pending message. Already-delivered is a server no-op (`1516`). |
| GET | `/api/shell` | `location[directory]` | none | `{data: [ShellInfo]}`. Client keeps `status == "running"` only (`2592–2601`). | 10 s | `runningShells`. Open session directory only. On open, reconnect, family switch (`AGENTS.md` line 39). |
| GET | `/api/shell/{id}` | `location[directory]` | none | `{data: ShellInfo}` including exited | 10 s | `ShellOutputFeature` poll. |
| GET | `/api/shell/{id}/output` | `location[directory]`, `cursor`, `limit` | none | `{data: {output, cursor, size}}`. Extra `truncated` ignored (`WireRequestTests.swift` 62–76). | 20 s | `ShellOutputFeature`. Page 64 KiB. Tail probe uses cursor `9007199254740991` (`2^53-1`) and limit 1 (`ShellOutputFeature.swift` 8–19). |
| DELETE | `/api/shell/{id}` | `location[directory]` | none | body ignored | 10 s | Stop Shell. Discards server capture; UI keeps what is shown. |
| POST | `/api/session/{id}/background` | none | none | body ignored | 10 s | Move to Background (`SessionFeature`). |
| POST | `/api/experimental/fs/write` | `location[directory]` if known, `path={tmp}/uploads/{uuid-lower}/{sanitizedName}` (`2097–2101`, 2142) | raw file bytes, `Content-Type: application/octet-stream`, `Accept: application/json`, idle timeout 120 s | `{data: {path: string}}` (`2089`, 2182) | request timeout 120 s | `uploadFile` stream: `.progress(sent)` then `.finished(path)`. `SessionFeature` composer files; `PhoneReportsDelivery`. |
| GET | `/api/fs/read/{relativePath}` | `location[directory]={location}` | none | raw file bytes. **Not** JSON. Auth header set. No `Accept: application/json`. | URLSession default | `OpenCodeServer.fileReadRequest` (138–146). Images in the open session directory, `AgentFileLoader` probe/download, `StreamingFileLoader` ranges. |
| GET | `/api/event` | none | none | SSE, not JSON body. `Accept: text/event-stream`. | session `timeoutIntervalForRequest = 90` (2852–2857) | `HomeFeature.eventStream` only. |

### Prompt body

`PromptBody` (`2046–2084`). Absent optionals are omitted by default `Encodable` (test: voice body has no `files` key, `WireRequestTests.swift` 26–35).

```json
{
  "id": "msg_…",
  "text": "typed text\nAttached file: `/abs/path`",
  "files": [{ "uri": "data:image/png;base64,…", "name": "optional" }],
  "delivery": "steer",
  "metadata": {
    "displayText": "typed text",
    "comments": [],
    "attachments": [{ "name": "…", "mime": "…", "path": "…" }]
  }
}
```

- `text` for path files is `OpenCodePromptPresentation.modelText`: trimmed-empty typed text is dropped, then one line `Attached file: \`path\`` per file (`ComposerFileAttachment.swift` 16–23).
- `metadata` is sent only when there is at least one path attachment. `comments` is always `[]` in that case because desktop only reads `displayText` when `comments` is an array (`2051–2054`).
- Inline images/PDFs are `files[].uri` data URLs: `data:{mime};base64,{standard base64, no line breaks}` (`OpenCodeOutgoingAttachment.dataURL`, 1119–1121). `name` omitted when nil.
- `delivery` is `"steer"` or `"queue"`. Omitted when the voice helper passes `nil` (`1979–1987`). Composer always sends one (`1958–1975`).
- Display of a stored prompt strips those exact reference lines rather than trusting `displayText` (`ComposerFileAttachment.swift` 38–48). `metadata.attachments` entries missing `name`, `mime`, or `path` are skipped.

### Session object (wire)

`SessionPayload` (3155–3165) → `OpenCodeSession` (2399–2412):

| Wire | Public | Notes |
| --- | --- | --- |
| `id`, `projectID` | same | required |
| `title?` | `title` | blank/missing → `"Untitled session"` |
| `location.directory` | `directory` | required object |
| `time.updated` | `updatedAt` | **Unix ms as JSON number (Double)** → `Date(timeIntervalSince1970: ms/1000)` |
| `time.idle?`, `time.viewed?` | `idleMilliseconds`, `viewedMilliseconds` | **Int64 ms**, echoed back unchanged on `/view` |
| `model?` `{id, providerID}` | `OpenCodeModelRef` | |
| `parentID?`, `agent?`, `cost?` | same | `cost` is USD for this session alone |

### Model object

`ModelInfoPayload` (3167–3185). Kept only when `enabled == true` and `status == "active"`. `capabilities.input` → `inputs` set (`"image"`, `"pdf"`, …). `limit.context` kept only if `> 0`. `id` stored as `catalogID` only when it differs from `modelID`. Message and session model refs name the catalog key (`286–293`, `matches`).

### Shell object

`ShellInfo` (2929–2955) → `OpenCodeShell`. `metadata.sessionID` string, `metadata.background == true` means user `!` command. Unknown `status` becomes `.exited`, never `.running` (2949–2950). `exit` is a JSON number decoded as `Double?` then `Int`. `time.started` / `time.completed` are Unix ms Doubles. Event-created shells have `isListed = false`; REST-listed ones `true`.

### Message → transcript items

`transcriptItems(for:)` (1830–1927). Unknown `type` → no rows. Server pages are newest-first; the client reverses before this mapping.

| `type` | Rows |
| --- | --- |
| `user` | images (`mime` prefix `image/`, base64 `data`) as `.userImages` id `{id}-images`, attachment id `{id}-img-{index}`; path refs from `metadata.attachments` plus inline non-image files (`FilePayload.isAttached`, 3250–3256) as `.userFiles` id `{id}-files`; remaining trimmed text as `.userText` id = message id. |
| `assistant` | `content[]` of `text` and `tool` only. **`reasoning` is dropped.** Text ordinal increments even when the part is empty and omitted (1864–1869), so ids stay `{id}-text-{ordinal}`. Tool id `{id}-tool-{part.id ?? "unknown"}`. Message `error` appends `.info` id `{id}-error`. |
| `shell` | one `.tool` named `shell`, call id `shellID ?? id`. Status: `running` → running; `exited` with nil or 0 exit → completed; else error. `killed` → error `"Shell was killed"`. Nonzero exit → `"Command exited with code N"`. |
| `skill` | completed tool named `skill`, input `{name}` or null. |
| `compaction` | `.compaction` with `OpenCodeCompaction` (2660–2680). |
| `synthetic` | one `.notice` or nothing. See synthetic rules below. |

Assistant run error text (`1821–1827`): `"Run failed: {type} — {human}"` where `human` is the message before the first `{`. Empty message → `"Run failed: {type}"`. Missing type → `"error"`.

Tool state (`1871–1884`, `609–617`): unknown `status` → `.completed`. Output is `content` entries with `type == "text"`, each truncated. `startedAt` is `time.ran ?? time.created` as Unix ms. Input and metadata are bounded (string 20_000 chars, collections 100; object subset is sorted keys).

Tool images (`1930–1954`): `content` `type == "file"`, mime prefix `image/`, URI must be `data:{mime};base64,…` (header compared lowercased). Remote `https:` URIs and PDFs are not images (`OpenCodeClientDecodingTests.swift` 302–330). Caps: 16 images, 20_000_000 decoded bytes each, 32_000_000 total. Id `{partID}-image-{contentIndex}` using the content-array index, not a dense image index.

Compaction (`2660–2680`, test 364–387):

| Wire `status` | Public |
| --- | --- |
| `running` | `.running` |
| `failed` and `error.type == "aborted"` | `.cancelled` |
| `failed` | `.failed`, `errorMessage` = trimmed `error.message` |
| anything else, including missing | `.completed` |

`isProvider` when completed and `providerContext` is present and not JSON null. Summary markdown is parsed at decode, bounded to 20_000 chars. Token fields on a non-running compaction with nonzero usage: input = `input + cache.read + cache.write`, output = `output + reasoning` (`OpenCodeClient.swift` 2667–2668). Context-usage accounting is different (below).

Synthetic (`2683–2689`, 1920–1923, test 116–129):

- No usable `description` → drop the row entirely (inbox and transcript).
- `metadata.source == "shell"`: `"Shell failed"` if `state == "error"`, else `"Shell finished"`.
- `source == "subagent"`: `"Agent failed"` / `"Agent finished"`.
- else `"Session update"`.
- Transcript text is `"{notice} · {description}"`, `isError` when `metadata.state == "error"`.
- Inbox synthetic stores `text: ""` and `notice` = the short label **without** the description (`2696–2698`).
- Inbox `type == "move"` is dropped (`2694`).
- Inbox `delivery` missing or unknown → `.steer` (`2706`).
- Inbox compaction → `isCompaction = true`, text still taken from payload.

`FilePayload.isAttached` (3250–3256): false if `mention` is present. If `source` is absent, attached when `data != nil`. Otherwise attached when `source.type == "inline"` or `source.uri` has prefix `data:`.

### Context usage

`decodeContextUsage` (2231–2254). Window is newest-first, limit 8. Walk in that order:

- `type == "compaction"` and `status == "completed"` → `.empty`.
- `type == "assistant"` with both `tokens` and `model`: total = `Int(input + output + reasoning + cache.read + cache.write)`. `> 0` → `.measured`; else `.empty`.
- If the walk ends and `cursor.next == nil` → `.empty`; if `cursor.next` is present → `.inconclusive` (keep whatever the UI already shows).

`TokensPayload` requires `input`, `output`, `reasoning`, and `cache.read` / `cache.write` as Doubles (3228–3233). A partial tokens object fails the message decode and therefore the whole page.

### Forms and permissions

Decoded with the same `JSONDecoder()` as everything else. Unknown keys ignored. Unknown enum raw values **fail the decode of that value** and therefore the whole `{data}` list. A failed permission or form read is a failed pending snapshot, never an empty list (`OpenCodeClientDecodingTests.swift` 189–204). `familyPendingRequests` filters permissions to the family session ids; forms also keep `sessionID == "global"` (`2464–2474`). `homeAttention` drops `"global"` and walks each pending session to its root (`2498–2539`). Directories are queried **sequentially**; permission and form for one directory are parallel. Cap is 8 directories (`HomeFeature.attentionDirectoryLimit`, 1344–1366): running roots, already-flagged roots, and the open session, newest first.

`OpenCodeFormField.CodingKeys` maps JSON `default` → `defaultValue` (`FormModels.swift` 82–86). Field `type` is a closed set: `string`, `number`, `integer`, `boolean`, `multiselect`, `external`. Condition `op` is `eq` or `neq`. `OpenCodeFormValue` decode order is Bool, then Double, then String, then `[String]` (3–14). No null. Encode is the matching JSON scalar or array. Visibility is progressive: a hidden field clears its answer for later `when` checks (`150–157`). External fields default to `false` and must be `true` to pass validation (`101–102`, `144–147`).

Permission wire: `id`, `sessionID`, `action`, `resources: [string]`, optional `save: [string]` (`1151–1156`). Reply raw values `once`, `always`, `reject`.

### Export

Client returns the HTTP body unchanged. `SessionExport.content` (`SessionExport.swift` 9–22) requires `data.info` and `data.messages` objects. JSON share is `data` pretty-printed, sorted keys, no escaped slashes, plus a trailing newline — **not** the `{data:…}` envelope. Markdown walks `user`, `shell`, `assistant` only; assistant parts `text` and `tool`; reasoning omitted. Timestamps in the header are Unix ms Doubles (`53–56`).

### File read URL rules

`imageRequest` (`118–136`, tests 107–120):

- `http`/`https` source → that URL, **no** Authorization.
- otherwise needs a session directory.
- `file://` → `URL.path` (percent-decoded).
- `sandbox:` → strip the prefix.
- path under `directory + "/"` → relative suffix.
- other absolute path → nil (do not request).
- relative → used as-is.
- request path `/api/fs/read/` + relative, query `location[directory]`.

Outside the session directory, `AgentFileLoader` is only called on an explicit tap (`AGENTS.md` line 45). A location-scoped read boots that folder’s service graph on the server.

Probe (`AgentFileLoader.probe`, 96–112): `Range: bytes=0-1`, `cachePolicy = reloadIgnoringLocalCacheData`, cancel the body once headers arrive. 206 → ranges supported, size from `Content-Range` total after `/`. 416 → size from `Content-Range` or 0, ranges not marked supported (`AgentFileProbe.init`, 58–70). 200 means the server ignored Range and is sending the whole file; the caller cancels it. `Content-Range` forms: `bytes 0-1/48213` and `bytes */48213`; `*` total is unknown (`81–84`).

Download revalidation: sidecar `{entityTag, lastModified}` next to the file as `name.validators`. Sends `If-None-Match` / `If-Modified-Since`. 304 reuses the cached file (`130–158`).

Streaming (`StreamingFileLoader`, 265–305): only if the probe was 206. AVFoundation cannot set Authorization, so the asset URL scheme is rewritten to `opencode-stream` and each range is fetched with the real request plus `Range: bytes={offset}-{offset+length-1}` or `bytes={offset}-` (`301–304`). Non-206 fails that load.

## SSE / event stream

Endpoint: `GET /api/event` (`2860–2904`).

- Dedicated `URLSession` with `timeoutIntervalForRequest = 90`. No call-wide timeout. Apple resets the request timeout when bytes arrive, so a `: heartbeat` comment every 15 s (client comment, 2852–2853) keeps the socket alive. 90 s of silence is a stall.
- `Accept: text/event-stream`. Basic auth if a password is set. No `Last-Event-ID`. No resume cursor. The stream is volatile; every reconnect rehydrates from REST (`HomeFeature.swift` 1223–1226).
- Framing (2884–2894): one line per event. Only lines with prefix `data:` are parsed. The remainder, including a leading space, is one JSON value (`OpenCodeEventDecodingTests.swift` 61–66). No `event:` / `id:` fields, no multi-line `data:` joining. `: heartbeat` and blank lines are skipped, but they still reset the read timeout.
- A `data:` payload whose UTF-8 length (line length minus 5) exceeds 16 MiB throws and ends the stream (2889–2890).
- `decodeStreamFrame` (2995–3001): decode `EventEnvelope` (`type` required, `data` optional, `_tag` optional). If `_tag` is set on the envelope **or** on a frame that is not an envelope, throw `"The event stream reported a server failure."` Unknown `type`, missing required fields, and non-JSON are **nil** and do not kill the stream (test 61–73).
- Stream end (server closed) throws `"The event stream ended."` (2897). HTTP 401 throws and **stops the reconnect loop** (`HomeFeature.swift` 1252–1254). Other HTTP statuses throw with the status code and are retried.
- Consumer buffer is `bufferingNewest(128)` (2863). A slow subscriber drops the oldest semantic events.

### Batching before the reducer

`EventBatcher` (1432–1470), an actor:

- Non-content events flush pending content, then yield immediately (ordering vs deltas is preserved).
- Content events append per session, coalescing **adjacent** deltas of the same part (`appendingDelta`, 1395–1409; test 76–103). Only text, reasoning, and tool-input deltas merge. A non-delta between them blocks the merge.
- Flush after 75 ms, or immediately when pending events across sessions reach 256.
- Flush iteration order of sessions is Dictionary order (not insertion order). Same-session event order is preserved.

### Wire `type` → semantic event

Payload field names are camelCase. Missing a required field yields nil (ignored), not an error.

| Wire `type` | Requires | Semantic |
| --- | --- | --- |
| `server.connected` | — | `.connected` |
| `session.execution.started` | `data.sessionID` | `.executionStarted` |
| `session.execution.succeeded`, `.failed`, `.interrupted` | `sessionID` | `.executionEnded` (no success/fail distinction) |
| `session.created` | — | `.sessionCreated` |
| `session.viewed` | — | `.sessionViewChanged` |
| `session.renamed` | `sessionID`, `title` | `.sessionRenamed` |
| `session.deleted` | `sessionID` | `.sessionDeleted` |
| `session.inbox.enqueued`, `.delivery.changed`, `.cancelled` | `sessionID` | `.inputEnqueued` |
| `session.inbox.delivered` | `sessionID` | `.inputQueueChanged` |
| `session.step.started` | `sessionID`, `assistantMessageID` | content `.stepStarted` |
| `session.step.streamed` | same | `.stepStreamed` |
| `session.step.ended`, `session.step.failed` | same | `.stepEnded` |
| `session.text.started` | + `ordinal` | `.textStarted` |
| `session.text.delta` | + `ordinal`, `delta` | `.textDelta` |
| `session.text.ended` | + `ordinal`, `text` | `.textEnded` |
| `session.reasoning.started` / `.delta` / `.ended` | same shape as text | reasoning cases. **Not projected into rows.** |
| `session.tool.input.started` | `sessionID`, `assistantMessageID`, `id`, `name` | `.toolInputStarted` |
| `session.tool.input.delta` | tool `id`; `delta` defaults to `""` | `.toolInputDelta` |
| `session.tool.input.ended` | tool `id`; `text` defaults to `""` | `.toolInputEnded` |
| `session.tool.called` | tool `id`; `input` defaults to `{}` | `.toolCalled` |
| `session.tool.progress` | tool `id`; `metadata` defaults to `{}` | `.toolProgress` |
| `session.tool.success` | tool `id`; text content blocks; metadata default `{}` | `.toolSucceeded` |
| `session.tool.failed` | tool `id`; `error.message` default `"Tool failed"` | `.toolFailed` |
| `session.message.content.updated`, `session.synthetic`, `session.instructions.updated`, `session.agent.selected`, `session.model.selected`, `session.moved`, `session.permissions`, `session.retry.scheduled`, `session.compaction.started`, `.delta`, `.ended`, `.failed`, `session.shell.started`, `session.shell.ended` | `sessionID` | `.messageInvalidated` (REST refresh, do not project) |
| `permission.asked`, `form.created` | `data.sessionID` or `data.form.sessionID`, not `"global"` | `.attentionChanged(askedSessionID:)` |
| `permission.replied`, `form.replied`, `form.cancelled` | `data.sessionID` present | `.attentionChanged(nil)` |
| `shell.created` | `data.info`, `info.status == "running"` | `.shellStarted` (`isListed: false`) |
| `shell.exited`, `shell.deleted` | `data.id` (not `info.id`) | `.shellEnded` |
| anything else | | ignored |

Part ids used by both REST and live upserts (`1367–1380`): text `{messageID}-text-{ordinal}`, tool `{messageID}-tool-{toolID}`. Step and reasoning have no transcript item id.

### How events become actions

`HomeFeature.serverEvent` (708–795), then `SessionFeature`:

| Semantic | Home | Session |
| --- | --- | --- |
| `.connected` | refresh snapshot (250 ms debounce, 1324–1341); if a session is open, also `familyRefreshRequested` | `eventStreamConnected` (900–918): new URL, clear delta accumulators but keep unfinished row protection, mark listed shells stale, `refreshRequested(.reconnect)` unless a refresh is already in flight, restart shell list, retune pending-work poll, restart watchdog |
| `.sessionCreated` | same as connected | family refresh only (via Home) |
| `.sessionViewChanged` | snapshot refresh | — |
| `.executionStarted` | insert into `activeSessionIDs`; if the id is in the open family, `familyRunStateChanged(true)`; if it is not a known root, snapshot refresh (new child) | run indicator |
| `.executionEnded` | remove from active; snapshot refresh; family gets `familyRunStateChanged(false)` | stop working, pull reply via refresh |
| `.sessionRenamed` / `.sessionDeleted` | apply to the list immediately | family rename/delete; deleting the open root replaces it with a draft |
| `.contentBatch` | only if it is the open session | `applyLiveEvent` (2882–2936); durable row-projecting boundary → `refreshRequested(.settlement(messageID))` after coalescing; `stepEnded` sets `contextNeedsRead` |
| `.messageInvalidated` | open session only | `refreshRequested(.invalidation)` → full page + `getSession` |
| `.inputEnqueued` / `.inputQueueChanged` | open session only | re-read inbox |
| `.attentionChanged` | if the asked id is already a listed root, light it now; always `homeAttention` (150 ms debounce); open session `requestsChanged` | re-read family permissions/forms |
| `.shellStarted` / `.shellEnded` | open session (ended: any open session) | shell capsule |

Live apply rules that a port must keep (`2880–2933`):

- Text deltas accumulate in `liveText` and re-parse markdown. `text.ended` replaces the row with the full text and clears the accumulator. Do not replay deltas onto a REST refresh.
- Tool input is writable only while status is `.streaming`. `tool.called` moves streaming → running and replaces input. Progress must not restart a completed/errored tool. Success/failure overwrite status, output, metadata, and error regardless.
- Reasoning and step events do not create rows. `step.ended` only marks context for a REST read.
- While a refresh is in flight, raced events except `textDelta` are kept (cap 512) and reapplied by part id (`920–933`, `2769–2771`).

Watchdog (`2593–2618`), separate from SSE stall detection: if the stream has delivered a content event, reconcile every 20 s; if a run is active but projection is unproven, every 3 s; otherwise every 30 s. Jitter is `abs(sessionID.hashValue % (seconds/5 + 1))` seconds. Pending requests and inbox poll every 12 s when the stream is healthy, else every 2 s (`2673–2674`).

### Reconnect and stall

`HomeFeature.eventStream` (1227–1266). The client does not reconnect.

- Addresses are tried **sequentially**, primary first. No sleep between addresses.
- `server.connected` resets the backoff counter and sends `serverConnected`, which stores `server.at(url:)` (winner becomes primary, others become alternates, 75–81). The in-flight loop keeps the candidate list captured when the effect started. A later restart (`retryConnectionTapped`, including every foreground, `HomeView.swift` 83–86) uses the updated server.
- Any error after a `server.connected` on that socket whose `DiagnosticsErrorCategory` is `.timeout` (URLError timed out, or POSIX `ETIMEDOUT`) sends `problemReports(.troubleDetected(.streamStalled))` (`1246–1248`).
- HTTP 401: set `connectionErrorMessage`, status `.disconnected`, **return** (no more URLs, no retry) (`677–679`, `1252–1254`).
- Other failures: `connectionLost`. If status was `.connected`, it becomes `.reconnecting` and rows stay; a second loss without a new `.connected` becomes `.disconnected` (`682–689`). Cancels in-flight snapshot and attention refreshes.
- After every candidate has been tried: `attempt = connectedThisPass ? 1 : min(attempt + 1, 5)`, sleep `min(15000, 1000 << (attempt - 1)) + abs(persistenceID.hashValue % 401)` milliseconds (`1260–1263`). So 1 s, 2 s, 4 s, 8 s, then 15 s, plus 0–400 ms jitter. A pass that achieved `.connected` sleeps only 1 s + jitter before the next full cycle. `String.hashValue` is **process-randomized**; the port needs some stable 0–400 jitter, not Swift’s hash.

## Family

A family is one root plus descendants, not a server concept with its own route (`OpenCodeSessionFamily`, 345–348).

Discovery (`requestSessionFamily`, 2337–2396; tests in `OpenCodeClientFamilyTests.swift`):

- Start with the root the caller already has. Do not re-fetch it.
- Parallel with child discovery: `GET /api/session/active`. The returned active set is the **server-wide** set, including unrelated ids (test line 32). Callers filter.
- BFS. Each parent: `GET /api/session?parentID={id}&order=desc&limit=100`.
- At most **6** child requests in flight. Responses are committed in queue order, not completion order.
- Visited-set skips duplicate ids and cycles. The first parent in traversal order wins; the child’s own `parentID` is what is stored (test 57–61: shared child title `a/shared`, parent `a`).
- Stop **starting** a new parent batch once `sessions.count >= 100` (root counts). The response that crosses 100 is kept in full, including grandchildren already inside that response (test 87–94, 96–118). Speculative requests in the same batch past the cap are cancelled; an error that belongs past the cap is not applied (test 110–114).
- HTTP or decode failure from any required request fails the whole family. In-flight siblings and the active request are cancelled (test 145–161). No partial family.

Home active ancestry is a different, smaller walk (`1722–1759`): only active ids not already in the root page, batches of 6, `GET /api/session/{id}`, follow `parentID`, 404 → nil, other errors fail the snapshot. Result is `activeSessionParents: [child: parent]`, omitted from the snapshot when empty (optional for older on-disk snapshots, 388–389). `SessionActivity.ancestors` (392–404) marks a root busy if any descendant is active, with a visited-set so cycles stop.

## Pairing

Two encodings (`OpenCodePairingLink`, 188–262; tests `AppFeatureTests.swift` 292–349).

**QR / `opencode pair` JSON**, text trimmed, must start with `{`:

```json
{"code":"Ab3_x-9Q","urls":["http://192.168.1.10:4096","http://100.64.0.3:4096"]}
```

- `code`: non-empty ASCII base64url, `[A-Za-z0-9_-]` only (`258–261`). `/`, `+`, `=`, spaces, non-ASCII reject the whole payload.
- `urls`: non-empty array. **One malformed URL rejects the whole code.** Each URL must be `http`/`https`, have a host, no user/password, no query, no fragment, path empty or `/`, port 1–65535 (`244–254`, `3307–3315`). Scheme lowercased. Path stripped. Duplicates removed, order kept. First origin is `origin`; the rest are `alternateOrigins`.
- `isLoopback` is true only when **every** candidate is loopback.

**HTTPS link** (older servers and pasted links): exactly `/auth/connect/{code}` (split on `/` with empty subsequences kept, so the path must be `/auth/connect/{code}` and nothing more). Query, fragment, userinfo, extra segments, wrong scheme, and percent-encoded slashes in the code are rejected (`233–241`, test arguments 300–308). Origin is scheme+host+port only.

**App refuses all-loopback before spending the code** (`AppFeature.swift` 333–337, message at 582): “That server only listens on localhost, so this phone can’t reach it. Run `opencode service set hostname 0.0.0.0` on your computer, then run `opencode pair` again.” Invalid text: “That isn’t an OpenCode pairing code. …” with phase Pair and **no** server target in the copyable report (`ConnectionErrorReportTests.swift` 110–120).

**Redeem** (`1608–1661`):

- If any candidate is non-loopback, loopback URLs are not requested. If all are loopback, they are tried (the app normally never calls this).
- Parallel `GET {origin}/auth/connect/{code}` as `OpenCodeServer(url: origin, username: "opencode", password: "")` so no Authorization header. Timeout 5 s. First success wins; the rest are cancelled. The code is single-use, so losers should see 401 or a network error.
- Success password is `session.token`. Username stays `"opencode"`.
- Then optional `GET /api/info`. `urls` from the server are merged with the candidates that were tried. Trailing `/` stripped. Loopback dropped. Invalid origins dropped. Winner URL removed. Stored as `alternateURLs`. An older server without `urls` still pairs.
- Failure ranking (`1666–1679`): any 401 → “This pairing code has expired or was already used. Run `opencode pair` again and scan the new code.” (status 401). Else any 404 → “This server doesn’t support pairing links. Update OpenCode on your computer, or enter the server manually.” Else the last error, or “Couldn’t reach the OpenCode server”.

After redeem, `AppFeature` runs the normal health probe and home load. The paired server is saved only after health succeeds (`396–402`).

`usesPairingToken` (93–97) is classification only: password matches `^[0-9]{10,}\.[A-Za-z0-9_-]{20,}$`. It does not change request headers.

## Auth headers

`basicAuthorization` (112–116): nil when `password` is empty (no header, not `Basic` of `user:`). Otherwise `Basic ` + standard base64 of UTF-8 `username:password`. No charset parameter. Test vector: user `u`, password `p` (`WireRequestTests.swift` 100).

Default username in the form is `"opencode"` (`AppFeature.swift` 9). Debug builds prefill `OPENCODE_PASSWORD` (`584–589`); release prefills empty.

The same header is set on SSE, uploads, and file reads. It is **not** set on remote `http(s)` image URLs (`118–123`).

`endpoint` (101–109): trim the saved address, require `http`/`https` and a non-empty host, **replace** path, query, and fragment. A saved URL with a path or `?token=` still calls `/api/event` at the origin (`WireRequestTests.swift` 95–104).

## Connection error reporting

`perform` status mapping (2816–2846):

| Condition | `OpenCodeClientError` |
| --- | --- |
| not an HTTP response | `"The server returned an invalid response."`, no status |
| 401 | `"The server rejected these credentials."`, status 401 |
| other non-2xx | `"The server returned HTTP {status}."`, status set |
| URLSession throw, including cancellation | `"Couldn’t reach the OpenCode server"` (no period), status nil. Upload path rethrows `CancellationError` instead (2162–2165). |
| JSON decode failure | `"The {name} does not match the current OpenCode V2 contract."` Default name is `"server response"` (`2759–2765`). |
| JSON encode failure | `"The {name} could not be encoded."` |

Upload-specific (`2126–2180`): `GET /api/info` 404, missing `paths.tmp`, or write 404 whose body does **not** contain the substring `Location` → `uploadsUnsupported` (`isUnsupported = true`, message “This server doesn’t support file attachments. Update OpenCode on your computer.”). A 404 that mentions `Location` is a normal HTTP error (missing location, not a missing route). Upload transport message has a trailing period: `"Couldn’t reach the OpenCode server."` `SessionFeature` records the server’s `persistenceID` in in-memory `uploadUnsupportedServerIDs` so the + menu hides Files until next launch (`SessionFeature.swift` 409, 1887).

`DiagnosticsErrorCategory` (`Diagnostics.swift` 5–43) is for the journal and stall detection, not the user string: 401 unauthorized, 404 notFound, other HTTP `server(n)`, `URLError.timedOut` timeout, not-connected / connection-lost / roaming / data-not-allowed offline, cancel cancelled, else transport. POSIX `ETIMEDOUT` timeout; `ENOTCONN`, `ECONNRESET`, `ECONNABORTED`, `EPIPE`, `ENETDOWN`, `ENETUNREACH`, `EHOSTDOWN`, `EHOSTUNREACH` offline.

Copyable setup failure (`AppFeature.FailureContext`, 188–217; report 141–156; tests in `ConnectionErrorReportTests.swift`):

```
OpenCode connection error
App: OpenCode {version} ({build})
Report generated at: {ISO8601}
Phase: Pair|Connect|Load sessions
Server: {host or host:port}
Attempt: {n}

Message:
{error.message}
```

Invalid pairing codes omit Phase/Server/Attempt. `safeTarget` keeps only alphanumerics plus `.-:[]`. Anything else, including userinfo, query, fragment, `file:`, bad port, newline, or `@` in the host, becomes `"Unavailable (invalid address)"`. IPv6 hosts get brackets. No truncation of long hostnames. The report is generated at copy time from the attempt that failed, not from later form edits (`ConnectionErrorReportTests.swift` 63–107).

Connect probe (`AppFeature.swift` 522–579) does not surface per-URL errors. It keeps the last failure and reports that if every candidate fails. Winner selection waits until every strictly better rank has finished, then takes the first success at the best finished rank and cancels the rest.

Reachability rank (`OpenCodeServer.reachabilityRank`, 155–185): `scope * 2 + (https ? 0 : 1)`. Lower wins.

| Scope | Hosts |
| --- | --- |
| 0 public | anything else parseable |
| 1 tailnet | suffix `.ts.net`, prefix `fd7a:115c:a1e0:`, IPv4 `100.64.0.0/10` |
| 2 private | `10/8`, `172.16–31`, `192.168/16`, `169.254/16`, suffix `.local`, host prefix `fc` or `fd`, prefix `fe80:` |
| 3 loopback | `localhost`, `127.0.0.1`, `::1`, `[::1]` (`150–152`) |
| 8 | unparseable |

`persistenceID` (61) is `username` plus sorted `candidateURLs` joined by `|`. It survives a change of winning address. `id` is the current URL. `supersedes` (65–67) matches persistenceID **or** equal current URL, so an edited address replaces the old list entry rather than duplicating it. Editing the URL clears alternates so a stale pairing address is not probed (`AppFeature.swift` 283–286).

## Persistence

`@Shared` keys. Passwords never go to files (`SharedKeys.swift` 263–268). JSON files use default `JSONEncoder` / `JSONDecoder` unless noted: `Date` is **not** ISO-8601 and **not** Unix time. Swift’s default is a number of seconds since 2001-01-01 UTC. Wire timestamps are unrelated (Unix milliseconds, converted before they are stored as `Date`).

### Keychain

Service `dev.kitlangton.opencode.servers` (`Keychain.swift` 136–138). Generic password, account = key, `kSecAttrAccessibleAfterFirstUnlock`. Update in place; add only if missing (105–111). Values are JSON `Data`. Decode failure loads the default. Encode failure skips the write (43–47). Tests use an in-memory mutex dictionary, never the real keychain.

| Account | Type | Default |
| --- | --- | --- |
| `saved-server` | `OpenCodeServer?` | nil |
| `saved-servers` | `[OpenCodeServer]` most recent first | `[]` |
| `openai-api-key` | `String?` | nil. Never sent to the OpenCode server. |

`OpenCodeServer` decode: `alternateURLs` missing → `[]` (46–54). `remember` (286–298) skips the keychain write when the value is unchanged.

### UserDefaults (standard)

| Key | Type | Default |
| --- | --- | --- |
| `voice-mode-enabled` | Bool | false |
| `voice-engine` | `VoiceEngine` raw: `"gpt-live"` or `"realtime"` (`LiveProtocol.swift` 4–6) | `gpt-live` |
| `whats-new-button-enabled` | Bool | true (`WhatsNewFeature.swift` 88–91) |
| `diagnostics.enabled` | Bool | unset means on for debug/TestFlight (`Diagnostics.swift` 110, 162–163) |
| `diagnostics.includeTranscripts` | Bool | same default as recording |

### Files

Paths are app-container relative unless noted. `swift-sharing` file storage.

| Path | Key / type | Notes |
| --- | --- | --- |
| Documents `voice-transcripts.json` | `[String: VoiceTranscriptRecord]` | Comment says `serverID:sessionID`. **Actual key is `"\(server.url):\(sessionID)"`** because `OpenCodeServer.id` is the URL (`VoiceFeature.swift` 101). A URL change orphans the record. Log capped at 400 lines (`VoiceTranscript.swift` 216–230). |
| Documents `favorite-models.json` | `Set<String>` | model picker pins |
| Documents `manually-unread-sessions.json` | `Set<String>` | values are `"{persistenceID}\|{sessionID}"` (`HomeFeature.sessionKey`, 319) |
| Documents `initialized-inbox-servers.json` | `Set<String>` | persistence IDs whose read-dates were seeded from the first snapshot (185–190) |
| Documents `session-read-dates.json` | `[String: Date]` | same `persistenceID\|sessionID` keys. Unread when missing or date `< session.updatedAt` (322–326). |
| Documents `last-viewed-sessions.json` | `[String: String]` | persistenceID → root session id |
| Documents `followed-sessions.json` | `[String]` ordered | **pins**, filename kept for upgrade (`SharedKeys.swift` 384–388). Values are `sessionKey`s. |
| Documents `phone-reports.json` | `[String: PhoneReportsDestination]` | persistenceID → `{projectID, projectName, directory, sessionID?}` |
| Documents `whats-new-seen.json` | `Set<String>` | entry ids |
| Caches `home-snapshot.json` | `CachedHomeSnapshot?` `{serverID, snapshot}` | `serverID` is persistenceID. Restored only when it matches the saved server (`AppFeature.swift` 56–61). `activeSessionParents` optional. |
| Caches `verified-openai-key.json` | `String?` | SHA-256 of the last accepted OpenAI key. Not a credential. |
| Application Support `OpenCode/transcript-snapshots.json` | `CachedTranscriptSnapshots` | See below. Complete file protection (`SharedKeys.swift` 242–243). |
| Application Support `OpenCode/Diagnostics/` | journal | `current.jsonl`, `current.1.jsonl`, `previous.jsonl`, `previous.1.jsonl`, `foreground` marker. 768 KiB segments. JSON lines. Debug/TestFlight only (`DiagnosticsJournal.swift` 31–89). |
| Application Support `OpenCode/ProblemReports/{uuid}/report.json` | `ProblemReport` | ISO-8601 dates (`ProblemReportStore.swift` 75–85). Also screenshot/timeline files. Max 12 reports. |
| Caches `AgentFiles/{24 hex}/{sanitized name}` | file bytes | Key is first 12 bytes of SHA-256 of `"{persistenceID}\|{absolutePath}"` as hex (`AgentFileLoader.swift` 121–127). Sidecar `name.validators`. |
| Temp `ComposerFiles/{uuid}/{name}` | staged uploads | `ComposerFileStaging` (`ShareHandoff.swift` 162–165). |
| App Group `group.ai.opencode.mobilecode` / `share-targets.json` | `ShareTargets` version 1 | No credentials. Written when leaving foreground (`ShareHandoff.swift` 9–14, 87–88; `HomeFeature.swift` 645–647). |
| App Group `Shares/{uuid}/share.json` | `ShareHandoff` version 1 | Atomic rename from `.staging-`. Consume renames to `.claimed-` first. Max age 24 h. Abandoned staging/claimed dirs older than 1 h deleted (`ShareHandoff.swift` 211–281). |

In-memory only: `upload-unsupported-servers` (`InMemoryKey`, persistence IDs). `UndeliveredPrompts` mutex, key `"{server.persistenceID}\|{sessionID ?? "new-session"}"` (`UndeliveredPrompts.swift` 31–32). Lost on process death. Model catalog cache is an actor keyed by `"{persistenceID}\|{directory}"` (`2419–2422`).

### Transcript snapshot cache

`CachedTranscriptSnapshots` (`SharedKeys.swift` 12–78):

- `version` must be `1` or the file is treated as empty.
- At most 16 entries globally, newest `updatedAt` first, and at most 4_000_000 encoded bytes. Encoding splices pre-encoded entries into `"entries":[]` so a large snapshot is not re-encoded while trimming (41–64).
- A cached page is valid only when `serverID == persistenceID` and `session.updatedAt` equals the stored `updatedAt` (71–77).
- Disk write is debounced 500 ms on a detached task (`TranscriptCacheWriter`, 166–222). Background flushes immediately (`HomeView.swift` 87–88).
- What is written: the open session plus the top 3 priority sessions (attention or running, then newest), each truncated to the last 30 items (`HomeFeature.swift` 216–229, 497). Memory holds up to 8 (`transcriptMemoryLimit`).
- Items are `OpenCodeTranscriptItem` **Swift Codable**, not wire messages. Enum case names are the JSON keys. `compaction` info and tool `images` are optional so older files still decode (`OpenCodeClientDecodingTests.swift` 332–338, 390–392). `HighlightedCode` persists `source` and `language` only; colors are re-highlighted on decode (`OpenCodeClient.swift` 659–666).
- Lab env `OPENCODE_LAB_RESET_TRANSCRIPT_CACHE=1` deletes the file on load. `OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE=1` skips restore and save.

## Concurrency

| Mechanism | Where | What runs there |
| --- | --- | --- |
| `@concurrent` on the `request*` / `perform` functions (1602, 2768, 2781, and the other request methods) | Swift 6.2: always the cooperative thread pool, not the caller’s actor | URLSession calls and `JSONDecoder` of protocol bodies. Transcript markdown parse happens inside `transcriptItems`, so it is off the main actor too. |
| `URLSession.shared` | system | All REST unless lab metrics are on. |
| `OpenCodeClient.requestSession` | `URLSessionConfiguration.default` (1549) | Only when `LabMarks.needsNetworkMetrics`. |
| `eventStreamSession` | own session, 90 s request timeout (2854–2857) | SSE only. |
| `EventBatcher` actor | 1432 | Coalesce. The byte loop is an unstructured `Task` inside `AsyncThrowingStream` (2864). |
| `OpenCodeReadCache` actor | 21–27 | Model catalogs. |
| `Mutex` (`Synchronization`) | `InlineCache` (753), upload/download progress (1127, 188), keychain test memory (127), transcript writer (174), undelivered prompts (15), file-storage memory (255) | Short critical sections. Not actors. |
| `UploadProgressDelegate` | URLSession’s queue (1124) | Progress. Reports at most about once per percent, or every 64 KiB (1140–1147). |
| `DownloadProgressDelegate` | its own `URLSession`, delegate queue nil (200) | One session per download, invalidated after. |
| `StreamingFileLoader` | serial queue `ai.opencode.streaming-file-loader` (270–283) | Range fetches. Ephemeral session. |
| `TranscriptCacheWriter` | `Task.detached` (185) | 500 ms debounce, then file write. |
| Share target publish | `Task.detached(priority: .utility)` (`ShareInbox.swift` 20) | JSON write to the App Group. |
| `DiagnosticsJournal` | serial `DispatchQueue` `opencode.diagnostics.journal` | JSONL append. |
| TCA `.run` effects | not the main actor | Home’s reconnect loop, snapshot fetches. Actions are sent back to the store. |
| Reducers | main actor (TCA) | They must not decode wire JSON or scan SSE bytes (`AGENTS.md` line 6). |

Cancellation: TCA `cancelInFlight` cancels the effect task, which cancels the `URLSession` task. `perform` turns that into a generic reachability `OpenCodeClientError`. Callers that check `Task.isCancelled` before sending the error (`homeSnapshot` refresh, history page) drop it. Do not show that string for a cancelled call. Family discovery uses `withThrowingTaskGroup` and cancels siblings on failure (`OpenCodeClientFamilyTests.swift` 145–174).

Lost-POST reconcile is in `SessionFeature.deliver`, not the client (`2534–2545`): if `statusCode == nil`, up to 3 tries, 1 s apart, `GET message/{clientID}` then inbox contains that id. If a status code exists, one try. Accepted → treat as sent. Otherwise restore the draft. A user retry mints a **new** `msg_` id (comment 2535–2537). The server treats a repeated inbox id as idempotent (`AGENTS.md` in the server repo); this client relies on that only for the reconcile of the same id, not for Try Again.

## Files Read

- `AGENTS.md` (protocol rules, lines 1–45 especially)
- `OpenCode/OpenCodeClient.swift` (entire protocol surface, 3331 lines)
- `OpenCode/SharedKeys.swift`
- `OpenCode/Keychain.swift`
- `OpenCode/FormModels.swift`
- `OpenCode/UndeliveredPrompts.swift`
- `OpenCode/ShareInbox.swift`
- `OpenCode/ShareHandoff.swift`
- `OpenCode/ComposerFileAttachment.swift`
- `OpenCode/AgentFiles/AgentFileLoader.swift`
- `OpenCode/AgentFiles/AgentFileReference.swift` (path recognition only; not the HTTP contract)
- `OpenCode/SessionExport.swift`
- `OpenCode/AppFeature.swift` (connect, redeem, failure report)
- `OpenCode/HomeFeature.swift` (event loop, snapshot, attention, cache)
- `OpenCode/SessionFeature.swift` (send, refresh, live apply, history paging, watchdog)
- `OpenCode/ShellOutputFeature.swift` (cursors and limits)
- `OpenCode/Voice/VoiceWorkClient.swift`
- `OpenCode/Voice/VoiceFeature.swift` (transcript key)
- `OpenCode/Voice/VoiceTranscript.swift`
- `OpenCode/Voice/LiveProtocol.swift` (`VoiceEngine` raw values)
- `OpenCode/Diagnostics.swift` (error categories, redaction)
- `OpenCode/DiagnosticsJournal.swift`
- `OpenCode/ProblemReports/ProblemReportStore.swift`
- `OpenCode/ProblemReports/PhoneReportsDelivery.swift`
- `OpenCode/RequestReviewView.swift` (permission title strings only)
- `OpenCode/HomeView.swift` / `OpenCode/AppView.swift` (foreground restart, share consume)
- `OpenCodeTests/OpenCodeClientDecodingTests.swift`
- `OpenCodeTests/OpenCodeEventDecodingTests.swift`
- `OpenCodeTests/WireRequestTests.swift`
- `OpenCodeTests/WireFixture.swift`
- `OpenCodeTests/OpenCodeClientFamilyTests.swift`
- `OpenCodeTests/ConnectionErrorReportTests.swift`
- `OpenCodeTests/AppFeatureTests.swift` (pairing decode and redeem)

## Boundaries

- **Wire JSON vs public models.** Features never see `SessionPayload` or `EventEnvelope`. Android should keep the same split: kotlinx wire DTOs, then a mapper to UI models. Do not decode wire messages with the disk-cache schema.
- **Wire timestamps vs stored dates.** Wire is Unix milliseconds (Double or Int64). Stored `Date` in snapshots uses Swift’s reference-date encoding. A Kotlin port of the app does not need to read iPhone files; do not “fix” wire dates to ISO-8601.
- **REST vs SSE.** SSE may omit durable fields and may include text the latest REST window does not have yet. Keep projected rows until a settlement. Never append a delta on top of a refresh that already contains that text.
- **Client vs reconnect policy.** `events()` is one socket. Backoff, 401 stop, candidate order, and rehydrate live in `HomeFeature`. Port both, or the socket will look fine and the list will go stale.
- **Location scope.** `location[directory]` boots a server location, including MCP processes. Home attention is capped at 8 hot directories, sequential. Do not fan out over session history. File reads outside the open directory only on tap.
- **Auth boundary.** Password empty → no header. Pairing connect is anonymous. Remote image URLs are anonymous. File reads and SSE are authenticated.
- **Share extension.** It writes the App Group. It does not call the API. The app consumes on launch and on becoming active (`AppView.swift` 29–34).
- **OpenAI voice** (`RealtimeClient`, `LiveClient`) is a different protocol and is not this server. The only OpenCode calls from voice are `VoiceWorkClient` (create, switch model, prompt steer, wait, transcript).
- **This document does not cover** transcript row layout, markdown rendering, or TCA navigation. Those consume the models above.

## Non-Obvious Things

- `GET /api/project` is a bare JSON array. Almost every other list is `{data: …}`. Pairing `{token}` and export body are also unwrapped at the HTTP layer. Getting this wrong fails health-adjacent home load with the V2-contract error.
- `GET /api/session/active` values must contain `type`, but the string is ignored. `"busy"` and `"running"` both count as active (tests use both).
- History paging sends `cursor` **instead of** `order=desc`, not in addition (`1809–1810`). The cursor is opaque and may contain `/`, `+`, `=`.
- Text part ordinals count empty text parts that produce no row. Live `ordinal` must match that, not “nth visible paragraph”.
- Bool is decoded before Double in `OpenCodeJSONValue` and `OpenCodeFormValue`. JSON `true` must not become `1.0`. There is no null form value.
- Unknown tool status becomes completed; unknown shell status becomes exited; unknown compaction status becomes completed; unknown inbox delivery becomes steer; unknown message types and unknown SSE types are dropped. Unknown form field `type` or condition `op` fails the entire forms response. Do not use a kotlinx `coerceInputValues` default for forms.
- A decode failure or HTTP failure of `/api/form` or `/api/permission/request` must not become an empty pending list (`OpenCodeClientDecodingTests.swift` 189–204).
- `/api/model/default` failure is ignored. `/api/model` failure is not.
- Compaction token split (input includes cache, output includes reasoning) is not the context-usage total (all five buckets summed).
- Provider compaction is `providerContext != null`, not a status string.
- Inbox synthetic notices omit the description; transcript notices append ` · {description}`.
- Prompt idempotency id is client-generated `msg_` + 32 hex chars (UUID, hyphens removed, **uppercase** because `UUID.uuidString` is uppercase). Upload directory UUIDs are lowercased (`2101`). Do not mix the two.
- `POST` with a body always sends JSON, including compact and fork which send `{}`. `POST` interrupt, wait, background, and view’s siblings that have no body send **no** `Content-Type`.
- Upload 404 is unsupported only when the body does not contain `Location`. A tagged location error must stay a normal HTTP error.
- SSE: ignore bad frames; throw only on `_tag`, oversize, HTTP error, or EOF. Do not require an `event:` line. Do not concatenate multi-line `data:`.
- OkHttp read timeout must be 90 s and must reset on comment heartbeats. A call timeout would kill a healthy stream. There is no `Last-Event-ID`.
- Health probes are parallel and rank-based. SSE and pairing-redeem address tries are different: pairing is parallel first-success; SSE is sequential. Loopback is skipped for pairing redeem when any other URL exists, but health will still probe a loopback alternate if it is in `candidateURLs`.
- `String.hashValue` jitter is not portable. Use any stable mod 401.
- One 401 on the event stream stops reconnect entirely, even if another URL has not been tried. Same password on every URL, so that is intentional.
- `persistenceID` sorts URLs. Saving a server after a different address wins must not create a second keychain entry (`supersedes`).
- Voice transcript map key uses the current URL, not `persistenceID`, despite the comment in `SharedKeys.swift` 326.
- File-read paths are appended then passed through `URLComponents.path`, which percent-encodes spaces, non-ASCII, and `%`, but not `/`. Session ids are interpolated raw. Kotlin should encode path segments the way `URLComponents` does, not form-encode the query with `+` for spaces.
- Query parameter **name** is the five characters `location` `[` `directory` `]`. Some HTTP builders reject or nest brackets. The server ignores a bare `directory`.
- `URLSession` adds `Accept-Encoding: gzip` and decompresses. The live transfer test expects gzip on large transcripts (`OpenCodeClientDecodingTests.swift` 47). OkHttp does this by default; do not disable it.
- Default `JSONDecoder` does not convert snake_case. Every wire key in this client is camelCase (`sessionID`, `providerID`, `assistantMessageID`, `parentID`, `projectID`).
- Extra JSON fields are ignored (`truncated` on shell output, `executed` on tool events, `reason` on compaction). Missing required fields fail the whole response.
- Shell output “cursor past the end returns only `size`” is claimed in `AGENTS.md` line 40. The Decodable page requires `output`, `cursor`, and `size` (`376–381`). A size-only body would fail decode. The tail probe sends cursor `9007199254740991`. Match the struct unless a live server proves the size-only shape; see Open Questions.
- REST window during a run may omit SSE-only text. Keep those rows. Re-appending deltas doubles words until `text.ended` (`AGENTS.md` line 8).
- Global forms (`sessionID == "global"`) show in the open session’s pending list and do not light a home row.
- Attention reads are not polled on a timer at home; they run on connect, snapshot refresh, and attention SSE events, debounced 150 ms, and only for the hot directory set.
- Foreground always restarts the SSE effect (`retryConnectionTapped` → `eventStream` with `cancelInFlight`). A socket that survived sleep is not trusted.
- Diagnostics redact `/auth/connect/<code>` to `/auth/connect/<redacted>` and `/api/fs/read/…` to `/api/fs/read/<redacted>`, and replace every query value (`Diagnostics.swift` 417–435). Connection reports never include the password, full URL, or raw body.

## Open Questions

- **Shell output past EOF.** `AGENTS.md` says a cursor past the end returns only `size`. `OpenCodeShellOutputPage` requires `output` and `cursor` too, and no test covers a size-only body. The tail probe depends on a successful decode. Confirm against a live `/openapi.json` or a real shell before special-casing OkHttp parsing.
- **Redirects.** Live `URLSession` follows redirects with platform defaults (Authorization dropped on cross-host redirect). No test asserts this. Not specified for the port beyond “don’t invent a policy the iOS app doesn’t have.”
- **`/api/info` shape drift.** Health requires `version` and `pid`. Upload requires `paths.tmp`. Pairing alternates want `urls`. A server can pass health and still be `uploadsUnsupported`. Whether `pid` stays an integer (not a float) is assumed from `StatusPayload`.
- **Active session `type` values.** The client accepts any string. It does not interpret `busy` vs `running` vs a future value. If the server omits `type`, the whole active map fails.
- **Hash jitter.** Exact millisecond jitter cannot be reproduced across processes. UX only needs 0–400 ms and the 1/2/4/8/15 s ladder.
- **Voice transcript key.** Using `server.url` rather than `persistenceID` looks accidental (`VoiceFeature.swift` 101 vs `SharedKeys.swift` 326). A faithful port copies the quirk; a cleaned port would orphan nothing the iOS app can already lose when the winning address changes.
- **Live server contract test** `liveServerReadContract` (`OpenCodeClientDecodingTests.swift` 7–23) is opt-in via `OPENCODE_LIVE_SERVER` and was not run here. It checks health, home snapshot (roots have no parent), getSession directory, family pending, inbox, and context usage against whatever server the environment points at.
- **Event types not in the decoder.** Anything absent from `serverEvent(from:)` (3104–3104) is ignored. A future server event will not reach Android until this switch is updated. The list in this document is the full switch as of `4fb5e0e`.
- **Cancellation vs reachability.** `perform` collapses `URLError.cancelled` into “Couldn’t reach the OpenCode server”. Effects usually discard that because the task is cancelled. A Kotlin port that maps cancellation to a user-visible error will show false failures whenever a snapshot is superseded (`cancelInFlight`).
