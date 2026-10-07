# iOS state architecture (TCA) — port notes

Checkout: `/Users/nxl/code/sandbox/opencode-android/.upstream/opencode-ios`, commit `4fb5e0e`. TCA **1.26.2** (`Package.resolved`). `AGENTS.md` requires `@ObservableState`, directly scoped stores, injected dependencies, cancellable effects, and `ComposableArchitecture2Deprecations`. REST snapshots are authoritative; SSE is an invalidation/projection stream. Protocol decoding stays out of reducers.

Action-case counts below are top-level `enum Action` cases (not associated-value variants, not nested `Delegate` cases). Nested delegates are listed separately. Total top-level cases across the eight features: **221**.

## Components Found

### Feature tree

```
OpenCodeApp.store  (process-lifetime Store, OpenCodeApp.swift:10-15)
└─ AppFeature                         optional child, plain ifLet (not @Presents)
   └─ HomeFeature                     AppFeature.swift:455-457
      ├─ ProblemReportTriggers        reducer only, no State; runs before Home's Reduce
      ├─ ProblemReportsFeature        Scope, always present (HomeFeature.swift:513)
      ├─ WhatsNewFeature              Scope, always present (HomeFeature.swift:514-516)
      └─ SessionFeature               @Presents ifLet (HomeFeature.swift:68, 1111-1113)
         ├─ SessionSheetFeature       plain ifLet, NOT @Presents (SessionFeature.swift:218, 2224-2226)
         ├─ ShellOutputFeature        @Presents ifLet (SessionFeature.swift:374, 2221-2223)
         └─ VoiceFeature              @Presents ifLet (SessionFeature.swift:196, 2227-2229)
            └─ VoiceTimeline          composed ahead of VoiceFeature's Reduce (VoiceFeature.swift:295)
```

`ifLet` (TCA 1.26) runs the **child reducer first, then the parent**, then cancels the child's effects if the child's identity went nil or changed. Parent handlers of child actions therefore see the child's mutations. `HomeFeatureTests.sendingMovesTheSessionToTheTopOfRecentsBeforeTheServerCatchesUp` depends on this: Home's `.sessionDetail(.presented(.sendTapped))` (HomeFeature.swift:604-609) reads `isAwaitingReply` and an already-cleared draft.

`Scope` does not dismiss or cancel on nil; the child is always in state. `ProblemReportsFeature` and `WhatsNewFeature` are `Scope`d, so they are not torn down when sheets close.

`@Presents` wraps the child action in `PresentationAction` (`.presented` / `.dismiss`). Views scope with `action: \.child.presented` (SessionView.swift:876, BackgroundWorkPanel.swift:74).

| Parent field | Child | Operator | Presentation |
| --- | --- | --- | --- |
| `AppFeature.State.home` | `HomeFeature` | `ifLet(\.home, action: \.home)` | not a sheet; `AppView` swaps the whole screen (AppView.swift:22-26) |
| `HomeFeature.State.sessionDetail` | `SessionFeature` | `ifLet(\.$sessionDetail, action: \.sessionDetail)` | not a sheet; drawer keeps one detail in place |
| `HomeFeature.State.problemReports` | `ProblemReportsFeature` | `Scope` | UIKit-hosted sheet via `problemReports(_:)` (ProblemReportViews.swift:151) |
| `HomeFeature.State.whatsNew` | `WhatsNewFeature` | `Scope` | `.sheet(isPresented: $store.isPresented)` (WhatsNewView.swift:9) |
| `SessionFeature.State.sessionSheet` | `SessionSheetFeature` | `ifLet(\.sessionSheet)` plain optional | sheet is `activePicker == .session`, independent of the optional |
| `SessionFeature.State.shellOutput` | `ShellOutputFeature` | `@Presents` | `.sheet(item: $store.scope(\.shellOutput))` (BackgroundWorkPanel.swift:74) |
| `SessionFeature.State.voice` | `VoiceFeature` | `@Presents` | not a sheet; replaces the composer. `.task(id: store.voice != nil)` sends `.start` (SessionView.swift:891-892) |

`SessionFeature.State` is `Identifiable` with `id == navigationID` (SessionFeature.swift:18-19). Home assigns a new `navigationID` per opened detail so TCA cancels the previous session's effects. A live (non-terminal) voice call **reuses** `navigationID` so the call's effects survive a drawer switch or New Session (HomeFeature.swift:137-149, 158-173). A draft with no directory gets a new id and the call ends (HomeFeature.swift:171-173). `familyPicked` does **not** change `navigationID`; it cancels effects by `CancelID` (SessionFeature.swift:1672-1675).

### Reducer composition order (must be preserved)

**AppFeature** (`AppFeature.swift:226-464`):

1. `BindingReducer().onChange(of: \.server)` — a real form edit while `connectionAttempt != nil` cancels the attempt, unless the form is already closed and `manualEntryDraft != nil`, in which case it retries (`connect`).
2. `Reduce` — parent actions. `.home` falls through to `.none` except `.home(.serverBadgeTapped)`, which nils Home.
3. `ifLet(\.home)` — Home runs **before** step 2 for `.home` actions.
4. `onChange` of `home?.voiceCallActivity != nil` — leaving Home ends the Live Activity without Home seeing the call end (AppFeature.swift:460-464).

**HomeFeature** (`HomeFeature.swift:510-1156`):

1. `BindingReducer`
2. `ProblemReportTriggers` — only if `problemReportsEnabled` (test default `false`)
3. `Scope` ProblemReports, then WhatsNew
4. `Reduce` **then** `ifLet(\.$sessionDetail)` — Session runs first on `.sessionDetail` actions
5. `onChange` of `sessionDetail?.voice?.placement` → `.voice(.presented(.targetChanged))` unless only an empty abandon list changed (HomeFeature.swift:1116-1120)
6. `onChange` of `VoiceLiveActivity.Call` → push Live Activity only when `content` changes
7. `onChange` of lab indicators (no-op outside lab)
8. trailing `Reduce` — if `unstartedUploadIDs` is non-empty, `.send(.queuedUploadsStarted)`. Runs after `absorbSharedDrafts` in the main Reduce's `defer`.
9. trailing `Reduce` — `reconcileCatchUp()`, skipping `.sessionDetail`, `.serverEvent(.contentBatch)`, `.serverEvent(.messageInvalidated)`, `.problemReports`

The main Reduce's `defer` always calls `absorbSharedDrafts()` and persists the home snapshot on snapshot/rename/delete/create/delete-response (HomeFeature.swift:519-530).

**SessionFeature** (`SessionFeature.swift:839-2250`):

1. `BindingReducer` — `.binding(\.draft)` stops dictation; other bindings are no-ops in the Reduce
2. `Reduce`
3. `onChange(of: \.foregroundWork)` — 3 s background hint timer
4. `ifLet` shellOutput, sessionSheet, voice (each child-before-parent)
5. `onChange` of `voice?.admittedPromptIDs` → refresh + watchdog
6. `onChange` of `hasPendingRequests` → `voice?.targetNeedsInput`
7. `onChange` of `currentModel` → `voice?.draftModel` while still a draft

**VoiceFeature** (`VoiceFeature.swift:294-295`): `VoiceTimeline()` then `Reduce`. Timeline sees pre-action state and only writes diagnostics.

### Action counts (port sizing)

| Feature | Action cases | Nested Delegate | File |
| --- | ---: | ---: | --- |
| `AppFeature` | 18 | — | `OpenCode/AppFeature.swift:159-179` |
| `HomeFeature` | 39 | — | `OpenCode/HomeFeature.swift:444-487` |
| `SessionFeature` | 95 | 4 | `OpenCode/SessionFeature.swift:531-645` |
| `SessionSheetFeature` | 19 | 6 | `OpenCode/SessionSheetFeature.swift:45-76` |
| `ShellOutputFeature` | 11 | — | `OpenCode/ShellOutputFeature.swift:53-66` |
| `WhatsNewFeature` | 5 | — | `OpenCode/WhatsNew/WhatsNewFeature.swift:32-39` |
| `ProblemReportsFeature` | 17 | — | `OpenCode/ProblemReports/ProblemReportsFeature.swift:91-109` |
| `VoiceFeature` | 17 | 3 | `OpenCode/Voice/VoiceFeature.swift:252-278` |
| **Total** | **221** | **13** | |

`OpenCodeServerEvent` has 16 cases (`OpenCodeClient.swift:1305-1327`). `OpenCodeLiveContentEvent` has 16 cases (`OpenCodeClient.swift:1329-1345`). `VoiceCallEvent` has 14 cases (`VoiceCallClient.swift:9-24`).

### Per-feature contracts

#### AppFeature — 18 actions

State (`AppFeature.swift:7-157`): `serverURL`, `username`, `password`, `manualEntryDraft`, `returnServer`, `connection` (`.idle | .pairing | .connecting | .loadingHome | .connected | .failed | .picking`), `connectionGeneration`, `connectionAttempt`, `home?`, `pendingShares`, `shareError`, `isEditingServer`, `editingSavedServer`, `@Shared` `savedServer` / `savedServers` / `cachedHomeSnapshot` / `lastViewedSessionIDs`, `alternateURLs`, `isScanningPairingCode`. `init` synchronously restores a saved server into `home` with `connectionStatus = .reconnecting` and `requestsConnectionIsAvailable = false` (lines 55-65). No `.task` is required for the first cached frame.

Actions, grouped:

- Lifecycle: `task`, `pendingSharesRequested`, `sharesReceived`
- Form/picker: `binding`, `connectButtonTapped`, `addServerTapped`, `editServerTapped`, `cancelEditingTapped`, `serverTapped`, `returnToServerTapped`, `serverDeleted`, `scanPairingCodeTapped`, `scannerDismissed`, `pairingCodeScanned`
- Attempt results (generation-guarded): `pairingRedeemed`, `healthResponse`, `homeResponse`
- Child: `home`

No Delegate. Dependencies: `openCodeClient`, `shareInbox`, `voiceLiveActivity`.

Cancel IDs (`AppFeature.swift:220`): `health`, `home`. Both `cancelInFlight: true`. Pairing redeem shares `CancelID.health` (line 519). `connect` also `.cancel(id: .home)` so a new health probe cannot be completed by an old snapshot (lines 575-578).

`ConnectionAttempt.id` is `connectionGeneration`, incremented per `connect`/`redeem`. Responses apply only when `connectionAttempt?.id == attemptID` **and** the phase matches (`.pairing` / `.connecting` / `.loadingHome`). A late health/home for another attempt is dropped (lines 381-449, tests `lateHealthCannotReplace…`, `lateHomeCannotMix…`).

#### HomeFeature — 39 actions

State fields that are not pure projections (`HomeFeature.swift:10-76`): `server`, `projects`, `sessions`, `activeSessionIDs`, `activeSessionParents`, `connectionStatus` (`.reconnecting | .connected | .disconnected`), `connectionErrorMessage`, `hasConnected`, `isStreaming`, `search`, `transcriptCache`, `attentionSessionIDs`, `runsStartedDuringRefresh`, `locallyViewedMilliseconds`, `localActivity`, `pendingDeletion`, `deletionError`, `recentlyDeletedIDs`, `activeSheet` (`.settings` only), `@Presents sessionDetail`, `catchUp`, `whatsNew`, `sharedDrafts`, `voiceCallActivity`, `problemReports`, `detailGeneration`.

`@Shared`: `cachedHomeSnapshot`, `cachedTranscriptSnapshots`, `pinnedSessionKeys`, `lastViewedSessionIDs`, `sessionReadDates`, `manuallyUnreadSessionKeys`, `initializedInboxServerIDs`.

Computed (do not store): `busySessionIDs`, `projectsByRecency`, `visibleSessions`, `pinnedSessions`, `recentSessions`, `catchUpSessions`, `sessionIndicators`, `credentialsRejected`, `isConnected`, `shareTargets` (used at flush).

Actions, grouped:

- Lifecycle / connection: `task`, `transcriptCacheFlushRequested`, `connectionLost`, `eventStreamFailed`, `serverConnected`, `snapshotFailed`, `retryConnectionTapped`, `serverEvent`, `serverBadgeTapped` (handled by App, Home returns `.none`)
- Drawer: `binding`, `settingsTapped`, `newSessionTapped`, `sessionTapped`, `togglePin`, `pinsMoved`, `movePinUp`, `movePinDown`, `deleteSessionTapped/Cancelled/Confirmed`, `deleteSessionResponse`, `clearDeletionError`, `markRead`, `markUnread`, `viewAcknowledged`
- Catch up: `catchUpTapped`, `catchUpCardDismissed`, `catchUpUndoTapped`, `catchUpExitTapped`
- Snapshot / cache: `snapshotRefreshed`, `attentionRefreshed`, `transcriptPrefetched`, `sessionNavigationReady`, `selectedTranscriptResponse`
- Children: `sessionDetail`, `whatsNew`, `problemReports`
- Other: `sharesReceived`, `voiceCallCommand`

Dependencies: `openCodeClient`, `continuousClock`, `date.now`, `haptics`, `transcriptCachePersistence`, `shareInbox`, `voiceLiveActivity`. `problemReportsEnabled` is read only by `ProblemReportTriggers`.

Cancel IDs (`HomeFeature.swift:489-493`):

| ID | cancelInFlight | Role |
| --- | --- | --- |
| `events` | true | SSE loop; restartable |
| `refresh` | true | 250 ms debounce + home snapshot |
| `attention` | true | 150 ms debounce + attention |
| `prefetch` | true | staggered transcript warm |
| `selectedTranscript` | true | tap-without-cache fetch |
| `deletionError` | true | 3 s error banner |
| `voiceCallCommands` | true | Live Activity button stream |
| `deleteSession(String)` | true | one delete per id |
| `viewReceipt(String)` | true | one receipt per session |

Constants: `prefetchCount = 3`, `transcriptMemoryLimit = 8`, `attentionDirectoryLimit = 8`.

#### SessionFeature — 95 actions

This is the port's bulk. State is `SessionFeature.swift:13-529`. Fields that matter for behavior (not every computed):

- Identity / visit: `navigationID`, `isVisible`, `readsOnView`, `historyGeneration`, `earlierHistoryGeneration`, `hasRenderedFirstContentFrame`
- Server/session: `server`, `session?`, `draftProject`, `projects`, `familySessions`, `familyRootID`, `activeFamilySessionIDs`, `familyTranscriptCache` (max 8, suffix 30), `familyDrafts`
- Transcript: `items`, `isLoading`, `initialTranscriptRequestIsExternal`, `initialTranscriptIsFresh`, `refresh` (id, reason, followup, racedEvents cap 512), `liveText`, `projectedMessageIDs`, `lastProjectedMessageID`, `resync`, `refreshFailed`, `errorMessage`, `visibleLimit` (starts 20, +40 per tap), `browsingWindowStartID`, `earlierHistory`, `earlierHistoryError`, `earlierHistoryRetriesOnReconnect`, `earlierHistoryInsertionID`, `lastSentItemID`, `sendGeneration`
- Composer: `draft`, `pendingAttachments`, `pendingFiles`, `sendAfterUploads`, `unstartedUploadIDs`, `dictationPhase`, `dictationError`, `dictationHold`
- Run: `isAwaitingReply`, `isInterrupting`, `isExternallyActive`, `queuedInputs`, `cancelledInputIDs`, `unconfirmedSendFailures`, `optimisticPromptIDs`, `promptStates`, `nextPromptRequestEpoch`, `promotedPromptIDs` (cap 32), `queuedInputImages`
- Requests: `permissionRequests`, `formRequests`, `formAnswers`, `respondingRequestIDs`, `questionPageIndices`, `requestLoadError`, `requestLoadAttempt`, `requestFailureAwaitingReconnect`, `requestRetryEnabledInTests`, `requestsConnectionIsAvailable`, `requestErrors`, `selectedRequestID`, `requestNotice`, `completedRequestIDs`, `hasLoadedPendingRequests`
- Pickers: `activePicker` (`.model | .project | .requests | .session`), `modelFilter`, `projectFilter`, `models`, `isLoadingModels`, `currentModel`
- Sheet / context: `sessionSheet?` (kept after dismiss), `contextUsage`, `contextNeedsRead`
- Shells: `shells`, `endedShellIDs`, `movableWorkKey`, `movedWorkKeys`, `@Presents shellOutput`
- Voice: `@Presents voice`
- Stream: `streamIsHealthy`, `streamSupportsProjection`, `pendingWorkPollSeconds`, `watchdogEnabledInTests`, `nextRefreshID`
- `@Shared`: `favoriteModelIDs`, `voiceModeEnabled`, `openAIAPIKey`, `uploadUnsupportedServerIDs`

`isRunning` is `isAwaitingReply || isExternallyActive || isRunningVoiceWork` (lines 284-287). `isRunningVoiceWork` is true only when `voice.target.sessionID == session?.id && voice.hasAdmittedWork`. Do not fold subagent activity into `isRunning`; that is `isBusy` / `activeSubagents`.

Actions, grouped:

- Lifecycle: `task`, `firstContentFrameRendered`, `visibilityChanged`, `eventStreamConnected`, `transcriptResponse`, `acceptedTranscriptUpdated` (no-op in Session; Home persists cache), `viewAcknowledged`
- Freshness: `refreshRequested`, `refreshResponse`, `resyncDelayElapsed`, `resyncMinimumElapsed`, `messageInvalidated`, `sessionSnapshotUpdated`, `externalRunStateChanged`, `replyCompleted`
- Projection: `contentBatch`
- History: `transcriptBrowsingChanged`, `showEarlierTapped`, `fillEarlierHistory`, `earlierHistoryResponse`
- Send: `sendTapped`, `queueTapped`, `returnKeyTapped`, `retrySendTapped`, `sessionCreated`, `promptFailed`, `promptAdmitted`, `promptEchoResponse`
- Dictation: `micTapped`, `micPressBegan/Released/Locked/Cancelled`, `dictationSendTapped`, `dictationReady`, `dictationUpdated`, `dictationStopped`
- Interrupt / background: `interruptTapped`, `interruptFinished`, `backgroundHintElapsed`, `moveToBackgroundTapped`, `moveToBackgroundFinished`
- Models/projects: `modelButtonTapped`, `modelsResponse`, `modelPicked`, `toggleFavoriteModel`, `switchModelFailed`, `projectButtonTapped`, `projectPicked`
- Family: `familyPicked`, `familyRefreshRequested`, `familyResponse`, `familyRunStateChanged`, `familySessionRenamed`, `familySessionDeleted`
- Shells: `shellsResponse`, `shellStarted`, `shellEnded`, `shellOutputTapped`, `shellOutput`
- Attachments: `attachmentAdded/Removed`, `filesStaged`, `fileUploadProgress/Finished`, `fileRetryTapped`, `fileRemoved`, `queuedUploadsStarted`
- Inbox: `pendingInputsResponse`, `inputQueueChanged`, `inputEnqueued`, `pendingInputSendNowTapped`, `pendingInputCancelTapped`, `pendingInputUpdateFailed`
- Requests: `pendingRequestsResponse`, `requestsConnectionChanged`, `requestsChanged`, `permissionReplyTapped`, `requestResponseFinished`, `reviewRequestsTapped`, `reviewRequestSelected`, `refreshRequestsTapped`, `requestsAutoRetry`, `questionPageChanged`, `formValueChanged`, `formSubmitTapped`, `formCancelTapped`
- Sheet/menu: `sessionInfoTapped`, `sessionMenu`, `sessionSheet`
- Voice: `voiceTapped`, `voiceExitFinished`, `voice`
- Delegate: `delegate`

`Delegate` (`SessionFeature.swift:639-644`): `openSession`, `togglePin`, `toggleRead`, `deleteSession`. Home handles these at lines 952-970. List actions exist only for roots, not subagents (AGENTS.md).

Dependencies: `openCodeClient`, `continuousClock`, `uuid`, `undeliveredPrompts`, `haptics`, `dictation`, `date`.

Cancel IDs (`SessionFeature.swift:726`):

| ID | cancelInFlight | Notes |
| --- | --- | --- |
| `requests` | true | pending-request poll |
| `inbox` | true | queued-input poll |
| `promptEcho` | true | up to 4 message lookups |
| `requestsRetry` | true | 2 s auto-retry |
| `requestsChanged` | true | one-shot refetch |
| `freshness` | **false** | one in-flight read; stale rejected by `requestID`, not by cancelling the sleeper |
| `watchdog` | true | 20 / 3 / 30 s + jitter |
| `earlierHistory` | true | one cursor page |
| `familyRefresh` | true | |
| `shells` | true | |
| `interrupt` | true | |
| `backgroundHint` | true | |
| `moveToBackground` | true | |
| `dictation` | true | |
| `viewReceipt` | true | |
| `resyncDelay` | true | 150 ms before showing cue |
| `resyncMinimum` | true | 300 ms minimum display |
| `voiceExit` | true | `DotScopeSimulation.exitDuration` |
| `upload(UUID)` | true | per file |

Also cancels `SessionSheetFeature.CancelID.autoRenameFailure` on family switch (line 1675).

`RefreshReason` (`SessionFeature.swift:647-674`): `open`, `resume`, `reconnect`, `runState`, `invalidation`, `settlement(messageID)`, `watchdog`, `snapshot`. `requiresFollowup` is true only for `invalidation`, `settlement`, `runState`. While a refresh is in flight, a duplicate lifecycle reason is dropped; a followup-worthy reason is stored once in `refresh.followup` (a terminal `.runState` followup is not downgraded). Same-message settlement during an in-flight settlement is a no-op because the 200 ms pre-GET sleep is the coalesce window (lines 970-989).

#### SessionSheetFeature — 19 actions

State (`SessionSheetFeature.swift:10-26`): `server`, `sessionID`, `renameDraft`, `isRenaming`, `operation` (`.renaming | .compacting | .forking | .autoRenaming | .exporting`), `confirmsDeletion`, `exportedFile`, `failure`, `autoRenameFailed`.

Delegate (`SessionSheetFeature.swift:68-75`): `renamed`, `compacted`, `forked`, `togglePin`, `toggleRead`, `deleteSession`.

Dependencies: `openCodeClient`, `uuid`, `haptics`, `continuousClock`. Cancel ID: `autoRenameFailure` (2.2 s, `cancelInFlight: true`). Rename/compact/fork/export effects are **not** cancellable IDs; they are dropped by nilling `sessionSheet` (family switch) or ignored if `sessionID` no longer matches (`renameFinished` guard, line 131). Fork/export failures still call `fail`, which clears `operation`.

Created by `ensureSessionSheet()` when the menu or title is used (SessionFeature.swift:322-328, 1709-1716). Survives sheet dismiss so an in-flight fork/export can still land.

#### ShellOutputFeature — 11 actions

State (`ShellOutputFeature.swift:22-51`): `server`, `directory`, `shell`, `output`, `startCursor`, `endCursor?`, `retainLimit`, `isLoadingEarlier`, `isConfirmingStop`, `isStopping`, `isUnavailable`, `errorMessage`. `id` is `shell.id`.

No Delegate. Dependencies: `openCodeClient`, `continuousClock`, `haptics`.

Cancel IDs: `poll`, `earlier`, `stop`, all `cancelInFlight: true`. Poll is started only from `.task` (view lifetime). Stop cancels `poll`, reads one last page, then `DELETE`. 404 is "unavailable", not an error banner. Constants: page 64 KB, initial tail 256 KB, retain 1 MB, max 4 MB, poll 1 s, retry 3 s, end-of-capture cursor `9_007_199_254_740_991`.

#### WhatsNewFeature — 5 actions

State: `entries` (default `WhatsNewEntry.all`), `@Shared seenIDs`, `@Shared isButtonEnabled`, `isPresented`. `showsButton = isButtonEnabled && unseen nonempty`. Actions: `binding`, `buttonTapped` (present + mark seen), `hideTapped` (disable button), `buttonEnabledChanged`, `viewed` (mark seen). No effects, no dependencies. Lab launch state is a static once-token (WhatsNewFeature.swift:64-77).

#### ProblemReportsFeature — 17 actions

State (`ProblemReportsFeature.swift:31-89`): `server?`, `projects`, `context`, `composer?`, `offer?`, `lastOfferAt`, `delivering?`, `retryAfterCurrent`, `automaticAttempts`, `deferredIDs`, `projectFilter`, `@Shared destinations`, `@Shared uploadUnsupportedServerIDs`.

Home's `ProblemReportTriggers` copies `server`/`projects` every enabled action and fills `context` on `.reportRequested` / `.troubleDetected`. It turns `.sessionDetail(.presented(.voice(.presented(.callDropped))))` into `.troubleDetected(.callDropped)` only if the call is not already terminal, and `.serverConnected` into `.serverReachable` (HomeFeature+ProblemReports.swift:43-58).

Actions: `task` (purge stale composing reports, restore an offered draft, offer crash if `offersCrashReports && diagnostics.takeUnexpectedEnd()`), `reportRequested`, `troubleDetected`, `composerPrepared`, `offerPrepared`, `offerTapped`, `offerDismissed`, `noteChanged`, `screenshotToggled`, `sendTapped`, `cancelTapped`, `projectPicked`, `projectPickerDismissed`, `serverReachable`, `deliveryFinished`, `composerClosed`.

Dependencies: `problemReportStore`, `diagnostics`, `problemReportCapture`, `openCodeClient`, `date.now`, `uuid`, `continuousClock`.

Cancel IDs: `close` (1 s after sent, 1.6 s after saved), `prepare`. Delivery itself is **not** cancellable; `delivering` ignores a mismatched `deliveryFinished`.

Constants: offer interval 600 s (crash exempt), stall counts only after 120 s foreground, max 5 automatic attempts, store keeps 12 reports.

`problemReportsEnabled` live = `InternalBuild.isEnabled`, test = `false` (HomeFeature+ProblemReports.swift:65-67). Home unit tests therefore never see report actions.

#### VoiceFeature — outer shape only (17 actions)

State (`VoiceFeature.swift:11-250`): `target: VoiceWorkTarget`, `sessionTitle`, `directoryName`, `targetNeedsInput`, `phase` (`.connecting | .live | .endingAfterGoodbye(spoken) | .ended | .failed | .dropped`), `isMuted`, `isAssistantSpeaking`, `pendingWork`, `captions`, `liveAssistantText`, `liveAssistantItemID`, `tail`, `previousEndedAt`, `showsCaptions`, `isAudioInterrupted`, `isReconnecting`, `connectedAt`, `startedTarget`, `abandonedCallIDs`, `draftModel`, `activeEngine`, `adoptedDraftSessionID`. `placement` is `(target, abandonedCallIDs)` and is what Home observes. `@Shared`: `voiceEngine`, `openAIAPIKey` (as `apiKey`), `voiceTranscripts`.

Phase: `showsCard` for failed/dropped; `isTerminal` for ended/failed/dropped. `hasAdmittedWork` is any `pendingWork.promptID != nil`. Ending the call clears `pendingWork` so a finished call does not hold `isRunning`.

Delegate: `checkAPIKey` (Home opens Settings, HomeFeature.swift:600-602), `sessionCreated` (Session sends `.sessionCreated` if still a draft), `ended` (Session keeps `.ended` for `exitDuration`, dismisses `.failed`/`.dropped` immediately, SessionFeature.swift:868-879).

Dependencies: `voiceCall`, `realtimePreflight`, `voiceWork`, `haptics`, `date.now`, `uuid`, `continuousClock`. Does **not** take `openCodeClient`; work goes through `VoiceWorkClient`.

Cancel IDs: `call`, `goodbye` (6 s), `work`, `instructions`, `reconnecting` (1 s before showing "Reconnecting"), `workCall(String)`, `lateUtterance(String)`. `start` is `cancelInFlight: true` on `call`.

`VoiceTimeline` is diagnostics-only, composed first, no state of its own.

### Dependency clients

`OpenCodeClient` deliberately does **not** use `@DependencyClient` (`OpenCodeClient.swift:1477-1479`): the macro's unimplemented endpoints throw `any Error`, which does not fit `throws(OpenCodeClientError)`.

`OpenCodeClient.testValue` is `liveValue` with only `contextUsage` stubbed to `.inconclusive` (lines 1596-1600). A test that forgets to override an endpoint performs a real `URLSession` call. Port tests should not copy that default if they want fail-closed stubs; they must copy the override pattern.

| Client | Methods | live | test |
| --- | --- | --- | --- |
| `OpenCodeClient` | `health`, `redeemPairing`, `homeSnapshot`, `homeAttention`, `transcript`, `message`, `sendPrompt`, `sendDeliveredPrompt`, `assistantReply`, `uploadFile` (stream), `waitForIdle`, `interrupt`, `createSession`, `deleteSession`, `sessionFamily`, `modelCatalog`, `switchModel`, `familyPendingRequests`, `pendingInputs`, `setPendingInputDelivery`, `cancelPendingInput`, `replyPermission`, `replyForm`, `cancelForm`, `getSession`, `runningShells`, `shell`, `shellOutput`, `stopShell`, `moveToBackground`, `viewSession`, `contextUsage`, `renameSession`, `compactSession`, `forkSession`, `exportSession`, `historyPage`, `events` | real HTTP/SSE | `liveValue` + inconclusive context |
| `TranscriptCachePersistence` | `identity`, `load`, `schedule`, `flush`, `remove` | Application Support `transcript-snapshots.json`, 500 ms coalesce | in-memory, except lab marks use live |
| `ShareInboxClient` | `consume`, `publishTargets`, `removeTargets` | app-group handoff | empty / no-op |
| `HapticsClient` | `selection`, `impact`, `success`, `error`, `voice` | UIKit generators, `@MainActor` | no-op |
| `DictationClient` | `prepare`, `start` → stream of full transcripts, `stop` | iOS 26 `SpeechAnalyzer`, else unsupported; lab script if `OPENCODE_LAB_DICTATION=1` | finished stream |
| `UndeliveredPrompts` | `keep`, `take`, `discard` | process `Mutex` | **new instance per access** (`testValue` is a computed property, UndeliveredPrompts.swift:38) |
| `DiagnosticsClient` | `voice`, `timeline`, `takeUnexpectedEnd`, `previousRunEvents`, `foregroundSeconds` | `DiagnosticsRecorder.shared` | empty / false |
| `ProblemReportStore` | `save`, `update`, `load`, `all`, `data`, `url`, `shareableFiles`, `delete`, `removeScreenshot` | Application Support `ProblemReports/` | unique temp dir |
| `ProblemReportCapture` | `screenshot` | `WindowSnapshot.jpeg()` | nil |
| `problemReportsEnabled: Bool` | flag | `InternalBuild.isEnabled` | `false` |
| `VoiceLiveActivityClient` | `update`, `endAll`, `commands` | ActivityKit | no-op / finished stream |
| `VoiceCallClient` | `start`, `completeFunction`, `noteProgress`, `setMuted`, `updateInstructions`, `end` | `VoiceCallHost` (Realtime or Live); lab demo if enabled | finished stream |
| `RealtimePreflightClient` | `verify` | `GET https://api.openai.com/v1/models/{model}` | success |
| `RealtimeClient` | `connect` | WebSocket | finished connection |
| `LiveClient` | `connect` | GPT-Live WebSocket | finished connection |
| `VoiceAudioClient` | `start` → session (`capture`, `events`, `enqueue`, `flush`, `setMuted`, `stop`) | `AVAudioEngine` | finished |
| `VoiceWorkClient` | `submit`, `awaitReply`, `recentMessages` | wraps `OpenCodeClient` | `msg_test` / nil / `[]` |
| `KeychainStorage` (`keychainStorage`) | storage behind `@Shared` keychain keys | service `dev.kitlangton.opencode.servers` | in-memory |

`events` does not reconnect. The caller (`HomeFeature.eventStream`) owns reconnect. SSE session timeout is 90 s (`OpenCodeClient.swift:2855-2857`).

### `@Shared` keys

Writes are synchronous `withLock`. They are visible to every feature holding the same key without an action. Tests get isolated in-memory sharing (comment, SharedKeys.swift:263-265). Passwords never use file storage.

| Key | Type | Storage | File | Held by |
| --- | --- | --- | --- | --- |
| `savedServer` | `OpenCodeServer?` | keychain `saved-server` | SharedKeys.swift:272 | App |
| `savedServers` | `[OpenCodeServer]` | keychain `saved-servers` | :280 | App |
| `openAIAPIKey` | `String?` | keychain `openai-api-key` | :320 | Session, Voice, Settings (view-local `@Shared`) |
| `voiceModeEnabled` | `Bool` default false | app storage | :304 | Session, Settings |
| `voiceEngine` | `VoiceEngine` default `.gptLive` | app storage | :312 | Voice, Settings |
| `whatsNewButtonEnabled` | `Bool` default true | app storage | WhatsNewFeature.swift:90 | WhatsNew |
| `cachedHomeSnapshot` | `CachedHomeSnapshot?` | caches `home-snapshot.json` | SharedKeys.swift:333 | App, Home |
| `verifiedOpenAIKeyFingerprint` | `String?` | caches `verified-openai-key.json` | :341 | Settings only (not a feature field) |
| `favoriteModelIDs` | `Set<String>` | documents `favorite-models.json` | :348 | Session |
| `manuallyUnreadSessionKeys` | `Set<String>` | documents `manually-unread-sessions.json` | :352 | Home |
| `initializedInboxServerIDs` | `Set<String>` | documents `initialized-inbox-servers.json` | :356 | Home |
| `whatsNewSeenIDs` | `Set<String>` | documents `whats-new-seen.json` | WhatsNewFeature.swift:83 | WhatsNew |
| `sessionReadDates` | `[String: Date]` | documents `session-read-dates.json` | SharedKeys.swift:371 | Home |
| `lastViewedSessionIDs` | `[String: String]` | documents `last-viewed-sessions.json` | :379 | App, Home |
| `pinnedSessionKeys` | `[String]` | documents `followed-sessions.json` (upgrade name) | :385 | Home |
| `voiceTranscripts` | `[String: VoiceTranscriptRecord]` | documents `voice-transcripts.json` | :327 | Voice |
| `phoneReportsDestinations` | `[String: PhoneReportsDestination]` | documents `phone-reports.json` | PhoneReportsDelivery.swift:15 | ProblemReports |
| `cachedTranscriptSnapshots` | `CachedTranscriptSnapshots` | custom `ProtectedTranscriptCacheKey` | SharedKeys.swift:81-122 | Home |
| `uploadUnsupportedServerIDs` | `Set<String>` | **in-memory** `upload-unsupported-servers` | SharedKeys.swift:365 | Session, ProblemReports |

Session key format is `"\(server.persistenceID)|\(sessionID)"` (HomeFeature.swift:319). Voice transcript key is `"\(server.id):\(sessionID)"` (VoiceFeature.swift:101) — different separator. Pin order is the array order of `pinnedSessionKeys`, not a set.

`CachedTranscriptSnapshots`: version 1, max 16 entries, max 4_000_000 bytes, newest first. `validItems` drops entries whose `updatedAt` no longer matches the session (SharedKeys.swift:71-78). `ProtectedTranscriptCacheKey.subscribe` is a no-op (no cross-process observation). Writer coalesces 500 ms via `Task.sleep`, not the clock (SharedKeys.swift:185-188). `OPENCODE_LAB_RESET_TRANSCRIPT_CACHE=1` deletes on load. `OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE=1` skips restore and persist (HomeFeature.swift:194, 217).

`OpenCodeServer.remember(replacing:)` (SharedKeys.swift:290-299) writes `savedServer` and moves the server to the front of `savedServers`. Unchanged values are not rewritten. An edited address replaces the old entry only after sessions load (`AppFeature.homeResponse`, lines 428-430).

### Bindings (`BindableAction` + `BindingReducer`)

SwiftUI `$store.field` sends `binding`. Special cases only:

- App: `onChange(of: \.server)` invalidates or retries the connection attempt. Bound fields: `serverURL`, `username`, `password`, `isEditingServer`, `isScanningPairingCode`, `shareError` (AppView.swift:28, 83, 108, 386-412).
- Home: `search`, `activeSheet` (HomeView.swift:55, 244). No special binding case.
- Session: `draft` stops dictation (SessionFeature.swift:892-895). Also bound: `activePicker`, `projectFilter`, `modelFilter` (SessionView.swift:866, 2039, 2080). Dismissing a picker sets `activePicker = nil` through the binding and does **not** nil `sessionSheet`.
- Session sheet: `isRenaming`, `renameDraft`, `confirmsDeletion` (SessionSheetView.swift:292-310). `failure` is a custom `Binding`, not `$store.failure`.
- Shell: `isConfirmingStop` (ShellOutputView.swift:145).
- WhatsNew: `isPresented`.
- Problem reports: `projectFilter` is state; the note is an action (`noteChanged`), not a binding. Composer sheet presentation is a view-local `ProblemReportPresenter`, not `@Presents`.

Settings binds `@Shared` directly (`voiceModeEnabled`, `voiceEngine`, `openAIAPIKey`), not through a reducer (SettingsView.swift:96-114). A port that only mirrors feature state will miss those toggles unless Settings writes the same keys.

Form answers are **not** bindings. `RequestReviewView` keeps `numberDrafts` in view `@State` and sends `formValueChanged` (RequestReviewView.swift:11, 201-209). Numeric drafts must not round-trip through the reducer on every keystroke.

View-local, not reducer state: attachment menu, jump button, image preview, large-upload alert, queued-messages sheet (`showsQueuedMessages`), background-work panel `isPresented`, catch-up gesture, server-removal confirmation (`serverPendingRemoval` in AppView.swift:14, 371-379).

### Navigation and presentation state

| UI | State | Dismiss |
| --- | --- | --- |
| Connection vs home | `home == nil` | `serverBadgeTapped` nils home, `connection = .picking` so `.task` does not auto-reconnect (AppFeature.swift:76-78, 374-379) |
| Manual entry sheet | `isEditingServer` | `cancelEditingTapped` |
| QR scanner sheet | `isScanningPairingCode` | `scannerDismissed` |
| Remove-server confirm | view `serverPendingRemoval` | sends `serverDeleted` |
| Connection error | `connection == .failed` | not an alert; a card. Copy uses `errorReport` |
| Share error alert | `shareError` binding | |
| Settings sheet | `activeSheet == .settings` | binding dismiss |
| Delete-session confirm | `pendingDeletion != nil` | `deleteSessionCancelled` / confirm (HomeView.swift:70-82) |
| Deletion error | `deletionError` | auto-cleared after 3 s |
| What's New sheet | `whatsNew.isPresented` | binding |
| Session pickers | `activePicker` | binding; `.requests` is `RequestReviewView`, `.session` is `SessionSheetView` |
| Rename alert | `sessionSheet.isRenaming` | binding; buttons send actions |
| Sheet failure alert | `sessionSheet.failure != nil` | `failureDismissed` |
| Sheet delete confirm | `sessionSheet.confirmsDeletion` | `deleteConfirmed` delegates to Home |
| Export share | `sessionSheet.exportedFile` | `exportDismissed` deletes the temp dir |
| Auto-rename failure | `autoRenameFailed` | no alert; 2.2 s then `autoRenameFailureCleared` |
| Shell output sheet | `@Presents shellOutput` | dismiss nils state and cancels `poll` |
| Stop-shell confirm | `isConfirmingStop` | `stopConfirmed` |
| Voice failure/dropped cards | `voice.phase` | not a sheet |
| Report sheet | `problemReports.composer != nil` | view presenter; `cancelTapped` / `composerClosed` |
| Report offer | `problemReports.offer` | `offerDismissed` deletes the draft |
| Project picker for reports | `composer.isPickingProject` | `projectPickerDismissed` |
| Catch up | `catchUp != nil` | `catchUpExitTapped` restores return session + composer |

There is no TCA `AlertState` / `@Presents` alert. Alerts are SwiftUI bindings over optional fields.

## Flow

### Launch

1. `OpenCodeApp` builds one `Store(initialState: AppFeature.State())` and ends leftover Live Activities (`OpenCodeApp.swift:10-18`).
2. `AppFeature.State.init` reads `@Shared` saved server + matching home cache and installs `home` in `.reconnecting` before any action (AppFeature.swift:55-65). Test: `restoresCachedHomeBeforeLaunchTaskWithoutProbing`.
3. `AppView.task` sends `.task` and `.pendingSharesRequested` (AppView.swift:29-31).
4. `.task` connects only if `connection == .idle && home == nil` (AppFeature.swift:247-255). A restored home does not get a second connect; Home's `.task` owns revalidation.
5. `HomeFeature.State.init` calls `restoreTranscriptCache` and `restoreInitialSession`: remembered root, or a draft (HomeFeature.swift:102-120).
6. `HomeView.task` sends `.task` (HomeView.swift:51-53): mark streaming, start SSE, refresh snapshot if not yet connected else refresh attention, subscribe to Live Activity commands.
7. `SessionView.task(id: session.id)` sends `.task`. With a session and no external fetch, that sends `.refreshRequested(.open)`. A draft only loads models, prepares dictation, and restores an undelivered prompt (SessionFeature.swift:1118-1135).
8. First UIKit content frame sends `firstContentFrameRendered`, which loads family, shells, models, and opens a refresh if the transcript is not yet fresh (lines 1137-1152).

Foreground (`scenePhase == .active`): App re-collects shares; Home sends `retryConnectionTapped`, which restarts the SSE loop if `isStreaming` and refreshes the snapshot (HomeView.swift:83-89, HomeFeature.swift:696-706). Background flushes the transcript cache and publishes share targets.

### Connection attempt

`connect` (AppFeature.swift:524-579): phase `.connecting`, **nils `home`**, bumps generation, probes every `candidateURLs` concurrently, keeps the best `reachabilityRank` (public, then Tailscale, then LAN, then loopback), cancels the rest, sends `healthResponse`. Success → `.loadingHome`, `remember()` unless an edit is in flight, then `homeSnapshot`. Success → `HomeFeature.State(server:snapshot:)`, `persistSnapshot()`, `connection = .connected`, deliver pending shares. Failure stores `FailureContext` (host:port only, no credentials).

`picking` is the intentional leave-home phase. `.task` must not treat it as `.idle`.

### SSE consume and fan-out

`subscribeEvents` (`OpenCodeClient.swift:2860-2905`): `GET /api/event`, `bufferingNewest(128)`, one `data:` JSON line per frame, ignore non-`data:` (heartbeats). Frames over 16 MB throw. A `_tag` failure frame throws. `decodeStreamFrame` → `serverEvent(from:)`.

`EventBatcher` (lines 1432-1469), outside the reducer:

- Non-content events `flush()` pending content, then yield immediately.
- Content events accumulate per session, adjacent deltas merged (`appendingDelta`, lines 1395-1410).
- Flush at 75 ms or when pending count ≥ 256.
- Uses `Task.sleep`, not `continuousClock`.

Wire → semantic (`OpenCodeClient.swift:3004-3104`):

| SSE `type` | `OpenCodeServerEvent` |
| --- | --- |
| `server.connected` | `.connected` |
| `session.execution.started` | `.executionStarted` |
| `session.execution.succeeded/failed/interrupted` | `.executionEnded` |
| `session.created` | `.sessionCreated` |
| `session.viewed` | `.sessionViewChanged` |
| `session.renamed` / `.deleted` | rename / delete |
| `session.inbox.enqueued`, `.delivery.changed`, `.cancelled` | `.inputEnqueued` |
| `session.inbox.delivered` | `.inputQueueChanged` |
| `session.step/text/reasoning/tool.*` | `.contentBatch` of one `OpenCodeLiveContentEvent` |
| `session.message.content.updated`, `session.synthetic`, `session.instructions.updated`, `session.agent/model.selected`, `session.moved`, `session.permissions`, `session.retry.scheduled`, `session.compaction.*`, **`session.shell.started/ended`** | `.messageInvalidated` |
| `permission.asked`, `form.created` | `.attentionChanged(askedSessionID:)` unless global |
| `permission.replied`, `form.replied`, `form.cancelled` | `.attentionChanged(nil)` |
| `shell.created` (status running) | `.shellStarted` (`isListed: false`) |
| `shell.exited`, `shell.deleted` | `.shellEnded` |
| anything else | dropped (`nil`) |

`session.shell.*` invalidates the transcript. The shell capsule listens to `shell.created/exited/deleted`, not those session events.

Home loop (`HomeFeature.swift:1227-1266`): for each candidate URL, iterate the stream. `.connected` → `.serverConnected(candidate)` (adopts the winning address) and resets attempt. Any other event → `.serverEvent`. 401 → `.eventStreamFailed` and **stop** (credentials rejected; retry is a no-op, line 697). Other errors → `.connectionLost`, next address, then backoff `min(15s, 1s << (attempt-1)) + hash jitter`, attempt capped at 5. A timeout after a live connect also `.problemReports(.troubleDetected(.streamStalled))`. `cancelInFlight` makes foreground retry restart the loop.

Fan-out (`HomeFeature.swift:708-795`):

| Event | Home mutation | Forwarded to open session |
| --- | --- | --- |
| `.connected` (via `serverConnected`) | `hasConnected`, clear error | `.eventStreamConnected(server)` |
| `.sessionViewChanged`, `.sessionCreated`, `.executionEnded` | snapshot refresh (250 ms) | created/connected also `.familyRefreshRequested`; ended also `.familyRunStateChanged` if in family |
| `.executionStarted` | insert active id + `runsStartedDuringRefresh` | `.familyRunStateChanged(true)` if known in family; else snapshot refresh if the id is not a listed root |
| `.contentBatch` | none | only if `sessionDetail.session.id == sessionID` |
| `.messageInvalidated` | none | same id guard → `.refreshRequested(.invalidation)` |
| `.inputQueueChanged` / `.inputEnqueued` | none | same id guard |
| `.attentionChanged` | optimistic insert if the asked id is a listed root | `.requestsChanged` if a session is open; always `refreshAttention` |
| `.shellStarted` | none | only if `shell.sessionID` is the open session |
| `.shellEnded` | none | if any detail is open |
| `.sessionRenamed` | patch list title | `.familySessionRenamed` if in family or voice target |
| `.sessionDeleted` | `purgeSession` | draft if it was the root; else `.familySessionDeleted` |

Content for a session that is not the open one is dropped. Recovery is the next `.connected` → snapshot, not a local queue. Subagent token streams are not projected into the parent transcript.

### REST vs SSE transcript reconciliation

Rules, in the order the code applies them. Pure merge is `SessionFeature.mergeTranscript` (lines 746-821). Live apply is `applyLiveEvent` (lines 2882-2937). Authoritative apply is `applyAuthoritative` / `applyAuthoritativeMessage` (lines 2766-2844).

1. **Part identity.** Text row id is `"\(messageID)-text-\(ordinal)"`. Tool row id is `"\(messageID)-tool-\(toolID)"`. Reasoning and step events do not project rows (`projectsRows == false`, OpenCodeClient.swift:1383-1392). `messageID(for:)` parses those suffixes back (SessionFeature.swift:823-832).

2. **Deltas append once, in `liveText`, not in the row.** `textDelta` does `liveText[id] += delta` then `upsertLiveText`, which **replaces** the markdown row with `blocks(from: fullString, reusing: previous)`. A refresh must not replay those deltas. `contentBatch` stores raced events excluding `.textDelta` (lines 926-930). After a page/message read, `applyAuthoritative` re-upserts `liveText` by id (replace). Replaying deltas doubles words until `textEnded`. Pinned by `textDeltaBatchProjectsWithoutREST` and `endedBoundaryReplacesDeltaTextAndSettlesOnce`.

3. **`textEnded` replaces and clears `liveText`.** The ended string wins over the accumulated deltas. Then a settlement refresh is requested for that message.

4. **Durable boundary.** `isDurableBoundary`: step/text/reasoning ended, tool input ended, tool called/succeeded/failed (OpenCodeClient.swift:1358-1364). A batch prefers the last boundary that `projectsRows`; otherwise the last boundary, and the message id falls back to `lastProjectedMessageID` (SessionFeature.swift:946-955). That id is `.settlement(messageID)`. `stepEnded` also sets `contextNeedsRead`.

5. **Settlement GET vs page GET.** If the message is already in `items`, `authoritativeRefresh` sleeps **200 ms** (clock), then `openCodeClient.message` (one message) plus optional `contextUsage`. If the message is not in the window, it GETs the newest page (`transcript` limit `initialFetchLimit` = 30) and `getSession`. Context is always read on a page refresh; on a message refresh only if `contextNeedsRead` (lines 2728-2750). Stale responses require matching `sessionID` and `refresh.id`.

6. **Projected rows survive a REST window that omits them.** While `preserveProjection` (`isRunning || refresh.followup == .runState`), `mergeTranscript` keeps items in the window span whose ids are absent from REST but whose message id is in `projectedMessageIDs` (lines 757-760, 783-820). They are reinserted before the next surviving row, not appended, so a later steering echo does not jump ahead of them. When the run ends, `resetLiveProjection` clears `liveText`, `projectedMessageIDs`, and `lastProjectedMessageID`, and the next REST window may drop them.

7. **Reconnect does not clear projected ids.** `eventStreamConnected` clears `liveText` and `lastProjectedMessageID`, sets `streamSupportsProjection = false`, and keeps row protection until REST echoes the parts or the run ends (lines 900-918). If a refresh is already in flight, it is not cancelled; later events buffer into `racedEvents`.

8. **Raced non-text events replay only onto parts REST replaced or never returned.** `retainedIDs = previous ∩ current − fetched`. Replay skips events whose `transcriptItemID` is in `retainedIDs` (lines 1044-1050). Tool input mutations no-op unless `status == .streaming`. Progress no-ops unless streaming or running. Success/failure always overwrite (lines 2894-2933). A REST-ahead tool is not restarted by a raced `input.started`.

9. **Optimistic user rows.** Idle send appends `.userImages` / `.userFiles` / `.userText` with ids derived from `msg_<uuid>` and records them in `optimisticPromptIDs` (lines 1348-1373). Mid-run send does **not** append a bubble; it appends `queuedInputs` with the chosen delivery (lines 1348-1353). `mergeTranscript` keeps client rows whose prompt id is still optimistic, plus `info-*` rows (lines 761-765). `reconcilePromptEchoes` subtracts prompt ids present in the authoritative items and moves them into `promotedPromptIDs` (cap 32).

10. **Inbox vs transcript.** `pendingInputsResponse` drops ids in `promotedPromptIDs`, in the transcript (except still-optimistic), or in `cancelledInputIDs`. Local `promptStates` rows missing from the server inbox are kept until the transcript echoes them (lines 1230-1238). If an admitted local prompt is missing from inbox, up to 4 `message` GETs run under `CancelID.promptEcho`. A hit replaces that message in place (`applyAuthoritativeMessage`); it is not appended. Empty inbox alone never means delivered.

11. **Unconfirmed send.** `deliver` runs the POST in an unstructured `Task` so leaving the screen cancels reporting, not delivery (lines 2512-2550). A transport failure (nil status) retries the echo 3 times, 1 s apart, via `message` then `pendingInputs`. If still missing, `promptFailed` restores the draft, appends `.sendFailure(id: "info-send-\(messageID)")`, and records `unconfirmedSendFailures`. A later transcript or inbox echo calls `settleUnconfirmedSends`, which removes the notice and strips the restored draft only if the user has not edited it (lines 2804-2817). HTTP errors do not enter that map. If the screen is gone, `UndeliveredPrompts.keep` restores on the next `.task`.

12. **Earlier history is a different read.** Pages are 40 messages (`requestHistoryPage`, OpenCodeClient.swift:1809). Cursor chain: `.unknownEdge` → `.seekingOverlap` until an id overlaps, then `.paging`, then `.exhausted`. Failure does not set exhausted (retryable). No HTTP status sets `earlierHistoryRetriesOnReconnect` and stays quiet. Overlap pages insert missing ids before the anchor; they never overwrite existing ids (SessionFeature.swift:2134-2150). `historyGeneration` resets the visit; `earlierHistoryGeneration` resets only the cursor chain (gap repair must not reset UIKit position). A newest-page refresh does not complete or cancel an earlier-history request (separate CancelID). Cache must overlap a fresh window before paging starts (`hasEarlierItems`, lines 519-527).

13. **Window limits.** First paint fetch is 12 (`firstFrameFetchLimit`); authoritative and cache suffix are 30 (`initialFetchLimit`). Home list is 50 newest roots (`requestHomeSnapshot`, line 1692). Open or pinned roots missing from that page are carried, not deleted (HomeFeature.swift:848-854). Deletion is only `session.deleted` or a confirmed DELETE.

14. **Runs that start during a snapshot.** `runsStartedDuringRefresh` is unioned onto `activeSessionIDs` when the snapshot lands, then cleared (lines 821-825). A run that ends mid-request triggers its own refresh, so only starts are recorded.

15. **Local send ordering.** Home's `noteLocalActivity` bumps `updatedAt` to `date.now` and marks read. A snapshot older than `localActivity.serverUpdatedAt` keeps the local time; a newer server time clears it (lines 827-834). Same for `locallyViewedMilliseconds` vs `viewedMilliseconds` (lines 836-841).

16. **Pending requests.** `completedRequestIDs` are filtered out of snapshots so a slow poll cannot restore an answered request. `respondingRequestIDs` and `requestErrors` keys are protected from removal. Requests whose owner is not in the family (and not `"global"`) are also kept (lines 1913-1930). Failed request snapshots are never turned into empty attention (AGENTS.md). Attention reads are bounded to 8 hot directories (`attentionDirectories`, HomeFeature.swift:1344-1367).

### Presentation flow that is state, not views

- Drawer tap with cache: build detail immediately, cancel prefetch and selected-transcript (HomeFeature.swift:1003-1009).
- Drawer tap without cache: cancel prefetch, send `sessionNavigationReady` (empty detail, `externalTranscript: true`) then `selectedTranscriptResponse`. Session `.task` does not start its own open refresh when `initialTranscriptRequestIsExternal` (line 1132). The response calls `acceptWindow`.
- New Session: `makeDraft()`, unless `connectionStatus == .disconnected`.
- Catch up: queue of unread/attention ids, `readsOnView = false`, swipe disposition `.markRead` or `.keepUnread`, undo cancels that session's view receipt. Leaving restores `returnComposer` via `restoreDraft` (uploads that were in flight come back failed).
- Family pick: cache outgoing transcript (suffix 30), swap items, reset projection/history/shells/inbox, abandon refresh, open refresh, restart polls. Terminal voice is cleared; a live call stays (identity unchanged) and Home's placement `onChange` tells it.
- Voice start: `.voiceTapped` builds `VoiceFeature.State` and stops dictation. The view's `.task` sends `.start`. End from the bar waits `exitDuration` if phase is `.ended`.

## Files Read

- `AGENTS.md` (full; state-relevant rules at lines 5-9, 32-43)
- `docs/architecture-review.md` (lines 1-215; historical, Home list described there is gone)
- `OpenCode/OpenCodeApp.swift` (full)
- `OpenCode/AppFeature.swift` (full)
- `OpenCode/HomeFeature.swift` (full)
- `OpenCode/SessionFeature.swift` (full: state, actions, merge, reducer, effects)
- `OpenCode/SessionSheetFeature.swift` (full)
- `OpenCode/ShellOutputFeature.swift` (full)
- `OpenCode/WhatsNew/WhatsNewFeature.swift` (full)
- `OpenCode/ProblemReports/ProblemReportsFeature.swift` (full)
- `OpenCode/ProblemReports/HomeFeature+ProblemReports.swift` (full)
- `OpenCode/ProblemReports/ProblemReportStore.swift`, `PhoneReportsDelivery.swift` (shared key + store API)
- `OpenCode/ProblemReports/ProblemReportViews.swift` (presentation modifier, not the sheet layout)
- `OpenCode/Transcript/TranscriptEntry.swift` (full)
- `OpenCode/Voice/VoiceFeature.swift` (state, actions, dependencies, cancel IDs, start/finish; not the work-delegation body past line 449 except finish/late-utterance)
- `OpenCode/Voice/VoiceTimeline.swift` (head)
- `OpenCode/SharedKeys.swift` (full)
- `OpenCode/OpenCodeClient.swift` (client surface 1477-1600, SSE 1301-1470, 2860-3130, snapshot/history limits)
- Client structs: `HapticsClient.swift`, `DictationClient.swift`, `ShareInbox.swift`, `UndeliveredPrompts.swift`, `DiagnosticsClient.swift`, `VoiceLiveActivityClient.swift`, `VoiceWorkClient.swift`, `VoiceCallClient.swift`, `RealtimeClient.swift`, `LiveClient.swift`, `VoiceAudioClient.swift`, `Keychain.swift` (dependency key)
- Presentation call sites: `AppView.swift`, `HomeView.swift` (sheets/alerts), `SessionView.swift` (pickers, task, voice task), `SessionSheetView.swift` (alerts), `ShellOutputView.swift`, `BackgroundWorkPanel.swift`, `SettingsView.swift` (`@Shared` toggles), `WhatsNewView.swift`
- Tests skimmed for names and pinning style: `OpenCodeTests/AppFeatureTests.swift`, `HomeFeatureTests.swift`, `SessionFeatureTests.swift` (head + reconciliation tests), `SessionSheetTests.swift`, `WhatsNewTests.swift`, `ProblemReportTests.swift`, `TranscriptEntryTests.swift`

## Boundaries

- **Reducers own interaction state. `OpenCodeClient` owns HTTP/SSE decoding.** Features never see raw SSE JSON. A Kotlin port should keep a client that yields the same `OpenCodeServerEvent` / `OpenCodeLiveContentEvent` enums, including the batcher, and not parse frames inside the session reducer.
- **`TranscriptEntry` is a projection, not state.** `build(from:isRunning:)` groups items for the list. `replyEnd` is appended only when `isRunning` is false (TranscriptEntry.swift:93-155). `TranscriptRowIdentityPlan` is pure and owned by the view controller, not the reducer (lines 174-274). Do not put stable row ids in `SessionFeature.State`.
- **UIKit transcript vs SwiftUI composer.** High-frequency `items` updates are observed by a list controller. SwiftUI observation of the store must not be what paints each token. `@ObservableState` is per-property; a single Compose `mutableStateOf(SessionState)` will recompose the composer on every delta unless the port splits reads.
- **Voice does not call `OpenCodeClient`.** `VoiceWorkClient` is the seam (`submit` / `awaitReply` / `recentMessages`). Session still owns transcript projection of those prompts via `admittedPromptIDs` → `.refreshRequested(.runState)`.
- **Settings writes `@Shared` outside any feature.** Voice availability (`voiceModeEnabled && key`) is read by Session; the toggle lives in Settings.
- **Share inbox and undelivered prompts are process services**, not feature state. Shares wait in `AppFeature.pendingShares` until Home exists, then `HomeFeature.sharedDrafts`, then `absorb` into the composer. Uploads start in a trailing reducer, not inside `absorb`.
- **Live Activity is pushed from Home `onChange`, ended from App `onChange` when Home disappears.** Voice does not talk to ActivityKit.
- **Problem reports are a Home child gated by a dependency flag**, not a session child. Context is derived (`problemReportContext`) at request time, not stored on every event.
- **`sessionSheet` lifetime ≠ sheet visibility.** In-flight rename/compact/fork/export must keep running after the sheet closes, and must be dropped on family switch (`sessionSheet = nil`).
- **Effect cancellation scope is the child's `Identifiable` id**, not a global bus. Preserving `navigationID` across a drawer move is what keeps a call alive. A Kotlin `viewModelScope` tied to the session screen will cancel the call unless the call owner is Home (or an id-stable session store).
- **`OpenCodeClient.testValue` is live.** Not a safe stub boundary.

### How tests pin behavior

Swift Testing `@Test` + `TestStore`, `@MainActor`. Most stores set `exhaustivity = .off(showSkippedAssertions: false)` and assert with `#expect` after `send`, because watchdog/poll/prefetch effects are noisy. Exhaustive `receive` is used for connection phases (`AppFeatureTests.connectsToServer` asserts generation, saved server, and cache write).

Patterns a port's tests should keep:

- Generation guards: late health/home/refresh/history/prompt-echo ignored (`requestID`, `attemptID`, `historyGeneration`, `earlierHistoryGeneration`, `promptStates[id].requestEpoch`).
- Clocks: `ImmediateClock` / `TestClock` for watchdog, resync, snapshot debounce, auto-rename, deletion banner. **Pending-work poll uses `Task.sleep`, not `clock`** (SessionFeature.swift:2634). It will not advance with `TestClock`. Watchdog is suppressed when `isTesting` unless `watchdogEnabledInTests`. Request auto-retry is suppressed unless `requestRetryEnabledInTests`.
- Reconciliation tests in `SessionFeatureTests` (lines 110-501 and later) are the contract for deltas, settlement coalesce, raced tools, reconnect row protection, followup queue, and watchdog cadence. `TranscriptEntryTests` pins grouping and `TranscriptRowIdentityPlan`. `HomeFeatureTests` pins snapshot carry of open/pinned rows, local send ordering vs server time, deletion backup/restore, attention directory cap, and SSE fan-out into the open session.
- `gatedHomeStore` holds the snapshot response so tests can assert the loading phase before home arrives.

## Non-Obvious Things

1. **`ifLet` child-then-parent is load-bearing.** Home's send handler, Session's voice-ended handler, and App's `serverBadgeTapped` all assume the child already reduced. A parent-first MVI loop will miss `noteLocalActivity` and will nil a child before it can handle dismiss.

2. **`freshness` is not `cancelInFlight`.** A second refresh is not started; it is stored as one `followup`. The in-flight effect must be allowed to finish its 200 ms sleep and GET. Cancelling it on every new settlement (a natural Kotlin `Job.cancel()` on the same key) drops the coalesce and can lose the followup if you also clear `refresh`.

3. **Text deltas are excluded from `racedEvents` on purpose.** `liveText` is the accumulator. Replaying deltas after merge doubles text. `textEnded` replaces. Reasoning deltas are applied to nothing (`applyLiveEvent` breaks on reasoning/step).

4. **`projectedMessageIDs` outlive `liveText` across reconnect**, and outlive a REST page that lacks unfinished parts, but not a terminal run. `preserveProjection` is also true when `followup == .runState`, so a refresh that completes in the gap before the terminal read must not strip rows.

5. **`EventBatcher` flushes non-content events immediately, and the stream is `bufferingNewest(128)`.** A slow collector drops old frames. The 75 ms / 256 coalesce is the supported lossy boundary for tokens. An unbounded Android channel will diverge under a token flood (more work, different batching) and a too-small buffer will drop `execution.ended`.

6. **`session.shell.started/ended` are transcript invalidations. `shell.created/exited/deleted` are the capsule.** Mapping both to one shell event will either miss output or refresh the transcript on every shell tick.

7. **Inbox event split:** enqueued/changed/cancelled only refetch inbox (`inputEnqueued`). Delivered refetches inbox and invalidates history (`inputQueueChanged`). Promotion is not applied from the event payload; the payload is not even decoded into the event.

8. **Optimistic idle bubbles vs mid-run queue.** `wasRunning` at tap time chooses the path. A queued send must not insert a user row. Steering mid-run is a pending input with `.steer`, shown as a dim row by the view from `queuedInputs`, not from `items`. `OpenCodeQueuedInput.waitsInQueuedPill` is `!compaction && notice == nil && delivery == .queue` (OpenCodeClient.swift:1298).

9. **`deliver` detaches the POST from effect cancellation** via an unstructured `Task`, then `await delivery.value`. Screen change must not cancel create+send. The port's coroutine scope must not be the session screen for that call. Three echo retries on transport failure use `clock.sleep`, so they are test-controllable; the POST itself is not cancelled by the effect.

10. **`UndeliveredPrompts.testValue` constructs a new instance on every read** (line 38). Tests that set the dependency once share it; tests that read `testValue` twice do not. Live value is one process object. Key is `serverPersistenceID|sessionID` or `…|new-session`.

11. **Pending-work poll uses `Task.sleep`.** Watchdog, resync, home debounce, attention debounce, background hint, auto-rename, and settlement coalesce use `continuousClock`. A single test scheduler will not match iOS tests unless the poll is special-cased. Cadence: 2 s until `streamIsHealthy`, then 12 s (`pendingWorkPollSeconds`, lines 2673-2675). Retune cancels and restarts both `requests` and `inbox`.

12. **Watchdog cadence** (`SessionFeature.swift:2595-2618`): suppressed under `isTesting` unless opted in. If healthy and projection proven, 20 s; else if running, 3 s; else 30 s. Plus `abs(session.id.hashValue % (seconds/5+1))` jitter. Cancelled when `session == nil`. Restarted on content batches, run-state changes, and voice admission changes.

13. **Resync cue is delayed and sticky.** 150 ms before show, then 300 ms minimum once shown (lines 2373-2391). A fast refresh never shows it. Visibility false abandons the refresh and cancels both timers. Duplicate `visibilityChanged(true)` must not restart freshness (`duplicateVisibleNotificationDoesNotRestartFreshness`).

14. **Home snapshot debounce is cancel-in-flight sleep-then-fetch (250 ms), attention is 150 ms.** That is the only debounce. There is no `.debounce` operator. Restarting the effect drops the previous response (`guard !Task.isCancelled`). `runsStartedDuringRefresh` is cleared when a refresh **starts**, then refilled by events during the request, then unioned on response. Clearing it at start is required or a previous request's starts leak.

15. **`navigationID` is the effect-cancel token.** New detail id cancels polls, dictation, uploads, voice. Reusing it for a live call is the only reason a drawer switch does not drop the WebSocket. Family switch keeps the id and must cancel the listed IDs itself, including the sheet's auto-rename timer.

16. **`@Shared` is not reducer state.** `withLock` during a reduce does not emit an action. Other features observing the key update immediately. `uploadUnsupportedServerIDs` is in-memory only so a server upgrade is retried next launch. Pin file name is the legacy `followed-sessions.json`.

17. **Deletion is two-phase.** `hideSession` removes the row and remembers a `DeletionBackup` (index, active, attention, open items, replacement draft id). Phone-local pins/reads/cache stay until success, then `purgeSession`. Failure restores the row only if the reader is still on the untouched replacement draft (HomeFeature.swift:1196-1199). A snapshot that still contains a `recentlyDeletedIDs` session must not resurrect it.

18. **Catch-up read is the swipe, not the appearance.** `readsOnView = false` suppresses view receipts and the auto-present of already-waiting requests (`presentsArrivals`, SessionFeature.swift:1944-1948). New requests that arrive while the card is open still present. Undo of mark-read cancels `viewReceipt(sessionID)`.

19. **Request dock replaces the composer.** `sendTapped` no-ops when `hasPendingRequests` (line 1301). `completedRequestIDs` is the stale-poll fence. First request failure while connected retries once after 2 s unless testing; reconnect failure surfaces `requestLoadError` only after the reconnect attempt settles (`settledRequestLoadError`, lines 477-480).

20. **`BindingReducer` on the server form can restart a connection.** Unchanged bindings must not (test `unchangedFormBindingsDoNotCancelConnection`). A flush after Connect, form already closed, retries with the completed entry (`lateFieldFlushAfterConnectRetriesWithTheCompletedEntry`). A Kotlin `TextField` `onValueChange` that fires on first composition will cancel the attempt.

21. **Transcript cache invalidation is `updatedAt` equality**, not a generation. `publishTranscript` no-ops if the suffix-30 items are unchanged. Prefetch will not run while the open session has `refresh != nil` or `!initialTranscriptIsFresh` (HomeFeature.swift:1276-1280). Prefetch fills empty slots only.

22. **`OpenCodeLiveContentEvent.appendingDelta` only merges adjacent events for the same part.** A tool event between two text deltas prevents merging. The reducer still applies them in order. Do not sort or group by part before apply.

23. **Global forms.** `permission.asked` / `form.created` with session id `"global"` do not light a Home row (`askedSessionID` guard). Request reconcile still keeps owners outside the family, including `"global"` (`coveredOwners`, SessionFeature.swift:1920).

24. **Context read is not a poll.** `contextNeedsRead` is set on `stepEnded` and cleared on `.measured` or `.empty`. `.inconclusive` keeps the previous fraction. Family switch nils `contextUsage`. The ring is hidden when the model catalog has no `limit.context` for that model (`contextFraction`, lines 230-233).

25. **Observation granularity.** TCA 1.26 `@ObservableState` plus `ComposableArchitecture2Deprecations` means a view re-renders only for properties it read. The transcript list does not use that for row bodies (UIKit). Porting "the store" as one `StateFlow` collected at the screen root will not match frame behavior during streaming. Split at least composer draft, transcript items, and chrome.

## Open Questions

- `VoiceFeature`'s delegation/retry body after line 449 was not fully traced (work-call cancel, instruction refresh, rejoin). Outer actions, dependencies, cancel IDs, and the Session/Home integration were. A voice-specific port note should read `delegateWork`, `targetChanged`, and `rejoinTapped` before implementing the call loop.
- `isTesting` is a swift-dependencies flag. Its exact detection (TestStore vs any test host) was not re-read in the TCA package. The Session reducer treats it as "inside TestStore" (watchdog and request-retry gates).
- Whether `Task.sleep` in `pollPendingWork` is intentional or an oversight relative to `clock` is not commented. Behavior on device matches a clock; test behavior does not.
- `EventBatcher` yields per session from a dictionary. Flush order across sessions is dictionary order, not arrival order. Only one session is open, so this may not be user-visible. Not verified against a multi-session event burst.
- `HomeFeature.shareTargets` is used at flush (`HomeFeature.swift:647`) but its construction was not read. Share delivery into `sharedDrafts` was.
- Request-review field validation (`OpenCodeFormRequest.validatedAnswer`) lives outside this reducer. The reducer only stores answers and sends the validated map. The form schema itself was not read.
- No Android-side state doc existed under `docs/port/` besides `decisions.tsv`. This file does not compare to an existing Kotlin draft.
