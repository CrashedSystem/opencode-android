package ai.opencode.feature.transcript.list

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.touch.TouchRecovery
import ai.opencode.core.touch.rememberTouchRecovery
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.inContainer
import ai.opencode.feature.media.AgentImageViewport
import ai.opencode.feature.media.AgentImageVisibility
import ai.opencode.feature.media.LinkPreviewStore
import ai.opencode.feature.media.LocalAgentImageRow
import ai.opencode.feature.media.LocalAgentImageViewport
import ai.opencode.feature.media.LocalLinkPreviewRevision
import ai.opencode.feature.media.LocalLinkPreviewStore
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.RowProps
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptDisclosureState
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.TranscriptRowContext
import ai.opencode.feature.transcript.links.LinkPreviewArrivals
import ai.opencode.feature.transcript.markdown.ui.LocalInlineLinkStyle
import ai.opencode.feature.transcript.markdown.ui.LocalLinkPreviewRow
import ai.opencode.feature.transcript.markdown.ui.LocalTranscriptTextReveal
import ai.opencode.feature.transcript.markdown.ui.SharedTextContextMenus
import ai.opencode.feature.transcript.markdown.ui.MarkdownStyle
import ai.opencode.feature.transcript.markdown.ui.TranscriptTextReveal
import ai.opencode.feature.transcript.selection.LocalTranscriptReplySelection
import ai.opencode.feature.transcript.selection.TranscriptReplySelection
import ai.opencode.feature.transcript.selection.TranscriptReplySelectionMenu
import ai.opencode.feature.transcript.user.findActivity
import ai.opencode.feature.transcript.selection.transcriptReplySelectionGestures
import ai.opencode.feature.transcript.selection.transcriptReplySelectionHandles
import ai.opencode.feature.transcript.rows.NewMessagesDivider
import ai.opencode.feature.transcript.user.LocalSendLanding
import ai.opencode.feature.transcript.user.QueuedPillSource
import ai.opencode.feature.transcript.user.TranscriptMessageRow
import ai.opencode.feature.transcript.user.UserMessageRun
import ai.opencode.feature.transcript.user.queuedPillSource
import ai.opencode.server.model.OpenCodeQueuedInput
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.RetainedEffect
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalFoundationApi::class)
private fun transcriptListState(index: Int, offset: Int) = LazyListState(
    // Prepare a viewport in the scroll direction rather than only the next row;
    // retain half a viewport behind so a short reversal can reuse measured Markdown.
    cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f),
    firstVisibleItemIndex = index,
    firstVisibleItemScrollOffset = offset,
)

/**
 * The list's state and, once restored, the reader's row by key with the offset within it: the
 * rows before it can differ after a process death (a shorter history window, a row more or
 * less), so the saved index alone would land on another row. The index stays the fallback.
 */
private class TranscriptListPosition(val state: LazyListState, var anchor: Pair<String, Int>? = null) {
    /**
     * Layouts left to settle a row put back: the composer's height reaches the list's padding a
     * frame late, so the first layout clamps a row near the end, and it's asked for again.
     */
    var settleLayouts = 0
}

/**
 * Android (QA AI-02): while a reply's text is selected, Back ends the selection before the
 * screen's own Back, as it dismisses any platform text selection and as the user bubble's
 * selection does (iOS has no Back; a tap elsewhere ends it there). Its own scope, so the
 * selection starting or ending doesn't recompose the list. Not while the keyboard is up: Back
 * puts the keys away first (A-01), then ends the selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplySelectionBackHandler(selection: TranscriptReplySelection) {
    if (!selection.isSelected || WindowInsets.isImeVisible) return
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, selection) {
        val callback = OnBackInvokedCallback { selection.clear(byReader = true) }
        // At the app's own priority, registered as the selection begins: what opens over it later
        // (the drawer, a menu, an in-window sheet) registers after it and takes Back first.
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

/** V-01: whether the list hides while a row restored after process death loads ([decide]d on the list's first composition). */
private class RestoreHide {
    var decide = true
    var hides by mutableStateOf(false)
}

/** Longest a restoring list stays invisible waiting for its row's page; then it shows where it is and still goes back to the row when it lands. */
private const val RESTORE_HIDE_LIMIT_MS = 2_500L

/** `LazyListState.Saver` for a state with the list's cache window (Compose keeps that saver internal), plus the first row's key. */
private val transcriptListPositionSaver = listSaver<TranscriptListPosition, Any>(
    save = { position ->
        val state = position.state
        // A row not reached yet (its history still loading) stays the one to go back to.
        position.anchor?.let { (key, offset) -> return@listSaver listOf(state.firstVisibleItemIndex, offset, key) }
        val key = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }?.key as? String
        listOfNotNull(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, key)
    },
    restore = { saved ->
        val offset = saved[1] as Int
        TranscriptListPosition(transcriptListState(saved[0] as Int, offset), (saved.getOrNull(2) as? String)?.let { it to offset })
    },
)

/**
 * Slots for rows whose views live above this module (iOS reads them directly from one module):
 * the Working line (`Session/SessionTranscriptView.swift` `WorkingLineRow` with its activity orb).
 * Null draws a plain fallback.
 */
class TranscriptSlots(
    val workingLine: (@Composable () -> Unit)? = null,
)

/**
 * The transcript list (iOS `TranscriptListView` + `TranscriptListViewController`): a LazyColumn
 * with stable keys and content types, opening at the bottom and following it while the reader
 * is there, browsing otherwise. [contentPadding] is the header above and the composer's own
 * obstruction below (iOS `contentInset`); the keyboard is the session's `imePadding`.
 * [jumpRequest] increments when the reader taps Jump to latest; [onShowsJumpButtonChanged]
 * reports the behavior's button visibility. [onHidesChanged] reports the list staying invisible
 * while a row saved across process death loads (V-01): the button keeps its state meanwhile and
 * should be drawn only once the rows are (AI-01).
 */
@Composable
fun TranscriptList(
    configuration: TranscriptConfiguration,
    send: (TranscriptAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    jumpRequest: Int = 0,
    onShowsJumpButtonChanged: (Boolean) -> Unit = {},
    slots: TranscriptSlots = TranscriptSlots(),
    layoutTuning: TranscriptLayoutTuning = LocalTranscriptLayoutTuning.current,
    onScrolledUnderHeader: (Float) -> Unit = {},
    borrowedDrag: TranscriptBorrowedDrag? = null,
    onHidesChanged: (Boolean) -> Unit = {},
) {
    // A new session is a new list (iOS resets the controller; the draft becoming its session keeps it).
    key(configuration.sessionID ?: "draft", configuration.historyGeneration) {
        TranscriptListContent(configuration, send, modifier, contentPadding, jumpRequest, onShowsJumpButtonChanged, slots, layoutTuning, onScrolledUnderHeader, borrowedDrag, onHidesChanged)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptListContent(
    rawConfiguration: TranscriptConfiguration,
    send: (TranscriptAction) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    jumpRequest: Int,
    onShowsJumpButtonChanged: (Boolean) -> Unit,
    slots: TranscriptSlots,
    layoutTuning: TranscriptLayoutTuning,
    onScrolledUnderHeader: (Float) -> Unit,
    borrowedDrag: TranscriptBorrowedDrag?,
    onHidesChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val touchExploration = rememberTouchExploration()
    val configuration = rememberPresentedConfiguration(rawConfiguration, touchExploration)
    val initialIndex = remember { (configuration.entries.size + configuration.queuedInputs.size + 2).coerceAtLeast(0) }
    // Like disclosure, where the reader is outlives the activity being recreated (a dark mode
    // switch, font size, process death); a following reader still opens on the bottom.
    // Saved under the session's own key, not the composition path: the session screen keys the list
    // by a draft's visit until it's restored as its session after process death (F-72).
    val saveKey = "transcript-list|${configuration.server?.persistenceID}|${configuration.sessionID}"
    val position = rememberSaveable(saver = transcriptListPositionSaver, key = "$saveKey|position") { TranscriptListPosition(transcriptListState(initialIndex, 0)) }
    val listState = position.state
    val behavior = rememberSaveable(saver = TranscriptScrollBehavior.Saver, key = "$saveKey|behavior") { TranscriptScrollBehavior() }
    val disclosure = rememberSaveable(saver = TranscriptDisclosureState.Saver, key = "$saveKey|disclosure") { TranscriptDisclosureState() }
    val selection = rememberSaveable(saver = TranscriptReplySelection.Saver, key = "$saveKey|selection") { TranscriptReplySelection() }
    val controller = remember { TranscriptListController(listState, scope, disclosure, behavior, selection) }
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val currentSend by rememberUpdatedState(send)
    val currentShowsJump by rememberUpdatedState(onShowsJumpButtonChanged)
    val currentHidesChanged by rememberUpdatedState(onHidesChanged)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    controller.send = { currentSend(it) }
    controller.restoringRowKey = { position.anchor?.first }
    // V-01: a reader whose row, saved across process death, isn't listed yet (its page is still
    // loading) would see the list clamped at the end and then jump. It stays invisible until the
    // row is put back, the restore ends, or RESTORE_HIDE_LIMIT_MS passes; header and composer show.
    val restoreHide = remember { RestoreHide() }
    // While it hides, the jump button's changes still go out, and the screen draws the button only
    // with the rows ([onHidesChanged]), so it's at rest on the frame the list shows (AI-01).
    controller.setShowsJumpButton = { currentShowsJump(it) }
    // The screen may start the pill as it last was (F-38); once laid out, this list says what its
    // own restored state shows, so the two can't disagree.
    LaunchedEffect(controller) { currentShowsJump(behavior.showsJumpButton) }
    controller.density = density.density
    controller.frameDuration = ai.opencode.core.motion.rememberFrameDurationSeconds()
    controller.reduceMotion = reduceMotion
    controller.touchExploration = touchExploration
    controller.isShowingToReader = resumed.isAtLeast(Lifecycle.State.RESUMED)
    controller.sendLanding = ai.opencode.feature.transcript.user.LocalSendLanding.current
    controller.flingBehavior = ScrollableDefaults.flingBehavior()
    val graphics = LocalGraphicsContext.current
    val rowExits = remember(controller, graphics) { TranscriptRowExits(graphics) }
    controller.rowExits = rowExits
    DisposableEffect(controller, rowExits) {
        controller.rowExits = rowExits
        onDispose {
            if (controller.rowExits === rowExits) controller.rowExits = null
            rowExits.release()
        }
    }
    // Applied in composition so this frame draws the new rows with their stable keys.
    controller.update(configuration)
    if (restoreHide.decide) {
        restoreHide.decide = false
        val key = position.anchor?.first
        restoreHide.hides = key != null && behavior.mode == TranscriptScrollBehavior.Mode.Browsing &&
            controller.snapshot.items.none { it.key == key }
        if (restoreHide.hides) currentHidesChanged(true)
    }
    val endRestoreHide = {
        if (restoreHide.hides) {
            restoreHide.hides = false
            currentHidesChanged(false)
        }
    }
    controller.obstructionChanged(contentPadding.calculateBottomPadding().value, WindowInsets.ime.inContainer().getBottom(density) / density.density)
    SideEffect { controller.flushEffects() }
    // iOS `TranscriptTouchSafety`: a hand-off that never lands can't keep the list's touches.
    rememberTouchRecovery(
        owner = TouchRecovery.Owner.Transcript,
        inspect = { controller.touchRecoveryFault() },
        release = { controller.releaseTouchRecoveryFault() },
    )
    // A restored browsing reader goes back to their row by its key once the rows hold it (a
    // following reader lands on the bottom instead); the reader moving the list first ends it.
    if (position.anchor != null) {
        SideEffect {
            val (key, offset) = position.anchor ?: return@SideEffect
            if (behavior.mode != TranscriptScrollBehavior.Mode.Browsing) {
                position.anchor = null
                endRestoreHide()
                return@SideEffect
            }
            if (position.settleLayouts > 0) return@SideEffect
            val index = controller.snapshot.items.indexOfFirst { it.key == key }
            if (index < 0) {
                // All of the history is listed and the row isn't in it: nothing to go back to. (Not
                // `!hasEarlierItems`: a restored cache says that before its first read lands, F-65.)
                if (configuration.listsAllHistory) {
                    position.anchor = null
                    endRestoreHide()
                }
                return@SideEffect
            }
            position.settleLayouts = 3
            listState.requestScrollToItem(index, offset)
            // This frame lays the row out where it was: the list shows from it.
            endRestoreHide()
            // The controller may hold the row the list showed before (rows were prepended): not now.
            controller.forgetReadingAnchor()
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo }.collect {
                val (key, offset) = position.anchor ?: return@collect
                if (position.settleLayouts <= 0) return@collect
                val index = controller.snapshot.items.indexOfFirst { item -> item.key == key }
                if (index < 0 || (listState.firstVisibleItemIndex == index && listState.firstVisibleItemScrollOffset == offset) || --position.settleLayouts == 0) {
                    position.anchor = null
                    endRestoreHide()
                } else {
                    listState.requestScrollToItem(index, offset)
                    controller.forgetReadingAnchor()
                }
            }
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.first { it }
            position.anchor = null
            endRestoreHide()
        }
    }
    if (restoreHide.hides) {
        LaunchedEffect(restoreHide) {
            delay(RESTORE_HIDE_LIMIT_MS)
            endRestoreHide()
        }
    }

    // iOS `TranscriptListViewController+TextSelection`: one selection across a reply's rows.
    val replySelection = controller.replySelection
    val listSelection = remember(controller) { TranscriptListSelection(controller, listState, scope) }
    listSelection.configuration = configuration
    listSelection.style = MarkdownStyle(layoutTuning, OpenCodeTheme.colors)
    listSelection.density = density
    listSelection.linkStyle = LocalInlineLinkStyle.current
    listSelection.topPadding = with(density) { contentPadding.calculateTopPadding().toPx() }
    listSelection.bottomPadding = with(density) { contentPadding.calculateBottomPadding().toPx() }
    replySelection.density = density.density
    DisposableEffect(listSelection) {
        replySelection.host = listSelection
        onDispose {
            // iOS: the list leaving clears the selection.
            replySelection.clear()
            if (replySelection.host === listSelection) replySelection.host = null
        }
    }
    // After the controller's effects: a recreated activity's selection comes back once its reply is listed.
    SideEffect { replySelection.restorePending() }
    ReplySelectionBackHandler(replySelection)

    LaunchedEffect(resumed) { controller.visibilityChanged() }
    // iOS `SessionView+Header` registers the transcript's knobs as the session draws.
    SideEffect { ai.opencode.feature.transcript.TranscriptTunables.register() }

    LaunchedEffect(controller) {
        snapshotFlow { listState.layoutInfo }.collect { controller.onLayout(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect(controller::scrollInProgressChanged)
    }
    // iOS `isUserScrolling` read by the send flights: a reader moving the list lets a send's words go.
    val sendLanding = controller.sendLanding
    if (sendLanding != null) {
        DisposableEffect(sendLanding, controller) {
            val scrolling = { controller.isUserGestureActive || controller.isBorrowedDragActive }
            sendLanding.isUserScrolling = scrolling
            onDispose {
                if (sendLanding.isUserScrolling === scrolling) sendLanding.isUserScrolling = { false }
            }
        }
    }
    // iOS `scrollEdgeBridge.onBorrowedDrag`: drags on the controls over the list scroll it.
    if (borrowedDrag != null) {
        DisposableEffect(borrowedDrag, controller) {
            val handler: (TranscriptBorrowedDrag.Event) -> Unit = controller::handleBorrowedDrag
            borrowedDrag.onEvent = handler
            onDispose {
                if (borrowedDrag.onEvent === handler) borrowedDrag.onEvent = null
                controller.cancelBorrowedDrag()
            }
        }
    }
    // iOS `viewWillAppear`/`viewDidDisappear` + the token's `deinit`: one list per session here.
    // iOS `TranscriptMotionTrace.shared.attach(self)` / `detach(self)` (Motion Watch).
    val motionCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val motionSource = remember(controller) { TranscriptMotionTrace.Source(controller) { motionCoordinates[0] } }
    DisposableEffect(motionSource) {
        TranscriptMotionTrace.attach(motionSource)
        onDispose { TranscriptMotionTrace.detach(motionSource) }
    }
    DisposableEffect(controller) {
        PerfTimeline.transcriptAppeared(controller.perfRows)
        onDispose {
            PerfTimeline.transcriptDisappeared(controller.perfRows)
            controller.perfRows?.release()
        }
    }
    // iOS `reportHeaderGeometry`: how far content sits above its resting top (dp), for the
    // session header's backdrop reveal. Past the first row it's simply "far".
    val currentScrolledUnderHeader by rememberUpdatedState(onScrolledUnderHeader)
    val scrolledUnderHeader = {
        if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE
        else listState.firstVisibleItemScrollOffset / density.density
    }
    // Also reported as the list is first applied, before its first frame draws, so the header's
    // bar is drawn with the rows on a cold launch rather than a frame later (G-01).
    DisposableEffect(listState) {
        currentScrolledUnderHeader(scrolledUnderHeader())
        onDispose {}
    }
    LaunchedEffect(listState) {
        snapshotFlow { scrolledUnderHeader() }.collect { currentScrolledUnderHeader(it) }
    }
    var lastJump by remember { androidx.compose.runtime.mutableIntStateOf(jumpRequest) }
    LaunchedEffect(jumpRequest) {
        if (jumpRequest != lastJump) {
            lastJump = jumpRequest
            controller.jumpToLatest()
        }
    }

    val snapshot = controller.snapshot
    val rowContext = remember(controller.disclosure, configuration.server, configuration.sessionID, configuration.directory) {
        TranscriptRowContext(
            server = configuration.server,
            sessionID = configuration.sessionID,
            directory = configuration.directory,
            disclosure = controller.disclosure,
            send = { action ->
                currentSend(action)
            },
        )
    }
    val gutter = layoutTuning.contentGutter.dp
    val direction = LocalLayoutDirection.current
    val listPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(direction),
        bottom = contentPadding.calculateBottomPadding() + controller.trailingSpace.dp,
    )

    // One per list, so a row LazyColumn recreates never fades its text again (iOS controller-owned).
    val textReveal = remember(controller.disclosure) { TranscriptTextReveal() }
    if (controller.runEnded) textReveal.settleAll()
    // The list owns the session's link previews (iOS `TranscriptListViewController.linkPreviews`,
    // made anew for each session): an arrival bumps only its rows' revisions, which redraws them.
    // Retained like iOS's controller, which a trait change only redraws: a recreated activity
    // (dark mode, font size, locale) keeps what loaded; leaving the session retires it.
    val linkPreviews = retain { LinkPreviewStore() }
    RetainedEffect(linkPreviews) { onRetire { linkPreviews.close() } }
    val linkRevisions = remember { mutableStateMapOf<String, Int>() }
    DisposableEffect(linkPreviews) {
        linkPreviews.onArrival = { rows ->
            for (row in rows) {
                controller.imageContentRevised(row)
                linkRevisions[row] = linkPreviews.revision(row)
            }
        }
        onDispose { linkPreviews.onArrival = null }
    }
    remember(linkPreviews) { LinkPreviewArrivals(linkPreviews) }.update(snapshot, configuration.isRunning, controller.hasLaidOutContent)
    // The list is its image previews' viewport (iOS `TranscriptListViewController:
    // AgentImageViewport`): previews read and show only between the header and the composer.
    val imageViewport = remember(controller) { TranscriptImageViewport(controller) }
    imageViewport.topInset = with(density) { contentPadding.calculateTopPadding().toPx() }
    imageViewport.bottomInset = with(density) { contentPadding.calculateBottomPadding().toPx() }
    val imageVisibility = remember { dependency(AgentImageVisibility) }
    DisposableEffect(imageViewport) {
        imageVisibility.register(imageViewport)
        onDispose { imageVisibility.unregister(imageViewport) }
    }
    LaunchedEffect(imageViewport) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.size to listState.firstVisibleItemScrollOffset }
            .collect { imageVisibility.setNeedsUpdate() }
    }
    CompositionLocalProvider(
        LocalAgentImageViewport provides imageViewport,
        LocalTranscriptRowContext provides rowContext,
        LocalTranscriptLayoutTuning provides layoutTuning,
        LocalTranscriptTextReveal provides textReveal,
        LocalLinkPreviewStore provides linkPreviews,
        LocalTranscriptReplySelection provides replySelection,
        // A covering screen isn't composed and a stopped activity's frame clock pauses, so
        // only the lifecycle below RESUMED is left to gate (iOS `emptyPulsePaused`).
        LocalTranscriptTimelinePaused provides TranscriptTimelineGate.pauses(
            listHidden = !resumed.isAtLeast(Lifecycle.State.RESUMED),
            sceneInBackground = false,
            covered = false,
            drawn = true,
        ),
    ) {
        val showsMagnifier by remember(replySelection) { derivedStateOf { replySelection.magnifierCenter.isSpecified } }
        Box(
            modifier
                .nestedScroll(controller.nestedScrollConnection)
                .onPlaced { replySelection.container = it }
                .transcriptReplySelectionGestures(replySelection)
                .transcriptReplySelectionHandles(replySelection)
                .then(if (showsMagnifier) Modifier.magnifier(sourceCenter = { replySelection.magnifierCenter }) else Modifier),
        ) {
            SharedTextContextMenus {
                TranscriptReplySelectionMenu(
                    replySelection,
                    isScrolling = { listState.isScrollInProgress },
                    onCopy = { currentSend(TranscriptAction.CopyTextTapped(it)) },
                    onQuote = { currentSend(TranscriptAction.QuoteTextTapped(it)) },
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("transcript-list")
                        .semantics { customActions = listOf(CustomAccessibilityAction("Scroll to top", controller::scrollToTop)) }
                        .graphicsLayer { alpha = if (restoreHide.hides) 0f else controller.handoffProgress() }
                        .drawRowExits(
                            exits = rowExits,
                            startPaddingPx = with(density) { contentPadding.calculateStartPadding(direction).toPx() },
                            layoutInfo = { listState.layoutInfo },
                            now = ::transcriptNow,
                        )
                        .bottomScrollEdge(contentPadding.calculateBottomPadding(), OpenCodeTheme.colors.canvas)
                        .then(if (controller.emptyHandoff != null) Modifier.clearAndSetSemantics { } else Modifier)
                        .onGloballyPositioned {
                            motionCoordinates[0] = it
                            val bounds = it.boundsInWindow()
                            imageViewport.bounds = bounds
                            imageVisibility.setNeedsUpdate()
                            controller.sendLanding?.listEnd = bounds.copy(bottom = bounds.bottom - with(density) { listPadding.calculateBottomPadding().toPx() })
                        },
                    contentPadding = listPadding,
                ) {
                    items(snapshot.items, key = { it.key }, contentType = { it.contentType }) { item ->
                        // Centred at the readable width on a wider window (iOS `readableColumn`).
                        val rowModifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                placementSpec = controller.placementSpring?.spec(),
                                fadeOutSpec = null,
                            )
                            .then(if (item == TranscriptItemID.Empty) Modifier else Modifier.rowExitSource(rowExits, item.key))
                            .fillMaxWidth()
                            .wrapContentWidth()
                            .widthIn(max = TranscriptMetrics.readableColumnWidth)
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                        when (item) {
                            TranscriptItemID.History -> TranscriptHistoryStatusView(
                                isLoading = configuration.isLoadingEarlier,
                                error = configuration.earlierHistoryError,
                                retry = { currentSend(TranscriptAction.ShowEarlierTapped) },
                                modifier = rowModifier,
                            )
                            TranscriptItemID.Empty -> TranscriptEmptyView(
                                isLoading = configuration.isLoading,
                                showsEmptyState = configuration.showsEmptyState,
                                loadingSince = controller.emptyLoadingSince,
                                pulsePaused = !controller.isShowingToReader,
                                modifier = rowModifier.fillParentMaxHeight(),
                            )
                            TranscriptItemID.NewDivider -> NewMessagesDivider(
                                rowModifier
                                    .graphicsLayer {
                                        alpha = controller.dividerAlpha()
                                        clip = true
                                    }
                                    .layout { measurable, constraints ->
                                        // Its motion fits the row (iOS `fittedDividerHeight`); the rule stays centred.
                                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                                        val height = controller.fittedDividerHeight(placeable.height)
                                        layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
                                    },
                                glowStart = controller.dividerGlowStart,
                            )
                            TranscriptItemID.Working -> GrowingRow(controller, item.key, rowModifier) {
                                val working = slots.workingLine
                                if (working != null) {
                                    working()
                                } else {
                                    WorkingLineFallback(Modifier.padding(vertical = TranscriptMetrics.flowHalf))
                                }
                            }
                            is TranscriptItemID.Message -> {
                                val row = snapshot.rows[item.stableID] ?: return@items
                                val props = snapshot.props[item.stableID] ?: RowProps()
                                val run = snapshot.runs[item.stableID] ?: UserMessageRun.alone
                                // A pending row keeps its drawing for a flight into the queued pill (iOS `flyIntoQueuedPill`).
                                val pending = (row as? MessageRow.Queued)?.input?.takeUnless { it.isCompaction }
                                val pillSource = if (pending != null) rememberQueuedPillSource(controller, item.stableID, pending) else null
                                val stableID = item.stableID
                                GrowingRow(controller, item.key, rowModifier.queuedPillSource(pillSource) { controller.isDepartingToPill(stableID) }) {
                                    CompositionLocalProvider(
                                        LocalAgentImageRow provides item.stableID,
                                        ai.opencode.feature.media.LocalAgentImageRowGrowth provides controller.imageGrowth(item.stableID),
                                        LocalLinkPreviewRevision provides (linkRevisions[item.stableID] ?: 0),
                                        LocalLinkPreviewRow provides item.stableID,
                                    ) {
                                        if (row is MessageRow.Queued && row.input.isCompaction) {
                                            TranscriptQueuedInputView(row.input, Modifier.padding(vertical = TranscriptMetrics.sectionHalf))
                                        } else {
                                            // Keep one call site: a pending row's remembered presentation
                                            // changes in place while animateItem moves that same row.
                                            val entry = row as? MessageRow.Entry
                                            val queued = row as? MessageRow.Queued
                                            TranscriptMessageRow(
                                                rowID = item.stableID,
                                                entry = entry?.entry,
                                                queued = queued?.input,
                                                failure = entry?.failure,
                                                deliveryNote = entry?.note ?: queued?.note,
                                                props = props,
                                                run = run,
                                                revealArrival = (entry?.entry?.userMessageID ?: queued?.input?.id)?.let(controller.agentReveal::arrival),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            controller.emptyHandoff?.let { leaving ->
                TranscriptEmptyView(
                    isLoading = leaving.loading,
                    showsEmptyState = leaving.showsEmpty,
                    loadingSince = leaving.since,
                    pulsePaused = !controller.isShowingToReader,
                    modifier = Modifier.fillMaxSize().padding(contentPadding)
                        .graphicsLayer { alpha = 1f - controller.handoffProgress() }
                        .clearAndSetSemantics { }
                        .pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                        },
                )
            }
        }
    }
}

/**
 * iOS's soft bottom scroll edge under the floating composer: rows fade into the canvas from just
 * above the composer, so nothing shows around its rounded edges or under the navigation bar.
 */
private fun Modifier.bottomScrollEdge(obstruction: Dp, canvas: Color): Modifier = drawWithContent {
    drawContent()
    val bottom = obstruction.toPx()
    if (bottom <= 0f) return@drawWithContent
    val top = (size.height - bottom - 8.dp.toPx()).coerceAtLeast(0f)
    val solid = size.height - bottom + 20.dp.toPx()
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, canvas), startY = top, endY = solid),
        topLeft = Offset(0f, top),
        size = Size(size.width, size.height - top),
    )
}

/** [pending]'s drawing for its flight into the queued pill, while the session hosts flights ([LocalSendLanding]). */
@Composable
private fun rememberQueuedPillSource(controller: TranscriptListController, stableID: String, pending: OpenCodeQueuedInput): QueuedPillSource? {
    if (LocalSendLanding.current == null) return null
    val graphics = LocalGraphicsContext.current
    val source = remember(controller, stableID, graphics) {
        QueuedPillSource(graphics, ai.opencode.feature.transcript.user.TranscriptUserMessage.from(pending)?.id)
    }
    DisposableEffect(source) {
        controller.registerPillSource(stableID, source)
        onDispose {
            controller.unregisterPillSource(stableID, source)
            source.dispose()
        }
    }
    return source
}

/**
 * A message or Working row as the list hosts it: laid out at its measured height, but reported at
 * the height the controller grows it to (iOS `fittedHeight` + `TopAnchoredRow`), clipped
 * top-anchored behind a soft edge while it grows, fading in when it arrives.
 */
@Composable
private fun GrowingRow(controller: TranscriptListController, key: String, modifier: Modifier, content: @Composable () -> Unit) {
    val canvas = OpenCodeTheme.colors.canvas
    Box(
        modifier
            .rowEntrance { controller.entrance(key) }
            // Drawn in a pure draw modifier (not `graphicsLayer`, which adds a semantics node to
            // every LazyColumn item root and multiplies scroll subtree events per visible row),
            // outside `topAnchoredRow` so the clip and soft edge sit at the reported height.
            .drawWithContent {
                val clips = controller.isClipping(key)
                val edge = controller.softEdge(key) * density
                fun drawRow() {
                    this@drawWithContent.drawContent()
                    if (edge > 0.5f) {
                        // The soft edge only ever brightens what it covers (iOS growth mask): the row
                        // fades into the transcript's canvas behind it. Drawn over, not punched out
                        // (DstOut), which needs an offscreen layer chosen in the same frame; a layer
                        // still on Auto the frame growth starts showed the cut as a black band.
                        this@drawWithContent.drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(canvas.copy(alpha = 0f), canvas),
                                startY = size.height - edge,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height - edge),
                        )
                    }
                }
                if (clips) {
                    clipRect(0f, 0f, size.width, size.height) { drawRow() }
                } else {
                    drawRow()
                }
            }
            .topAnchoredRow(controller, key),
        contentAlignment = Alignment.TopStart,
    ) {
        content()
    }
}

private const val TALKBACK_TAIL_STREAM_INTERVAL_MS = 1_500L

/**
 * Under TalkBack touch exploration, coalesces in-place tail `AssistantMarkdown` streaming updates
 * ([TranscriptConfiguration.differsOnlyInTailAssistantMarkdown]) to [TALKBACK_TAIL_STREAM_INTERVAL_MS]
 * so per-chunk text and scroll updates don't saturate TalkBack's `ProcessorEventQueue`. The first
 * chunk of a reply and any structural change (run start, Stop / completion, tool rows, sends) apply
 * immediately; sighted (`!touchExploration`) returns [configuration] untouched.
 */
@Composable
private fun rememberPresentedConfiguration(
    configuration: TranscriptConfiguration,
    touchExploration: Boolean,
): TranscriptConfiguration {
    if (!touchExploration) return configuration
    val state = remember { PresentedConfigurationState(configuration) }
    val nowMs = SystemClock.uptimeMillis()
    val current = state.presented
    if (configuration == current) {
        state.pending = null
        return current
    }
    if (!configuration.differsOnlyInTailAssistantMarkdown(current)) {
        state.presented = configuration
        state.pending = null
        state.lastTailApplyUptimeMs = nowMs
        return configuration
    }
    val elapsed = nowMs - state.lastTailApplyUptimeMs
    if (elapsed >= TALKBACK_TAIL_STREAM_INTERVAL_MS) {
        state.presented = configuration
        state.pending = null
        state.lastTailApplyUptimeMs = nowMs
        return configuration
    }
    state.pending = configuration
    val waitMs = (TALKBACK_TAIL_STREAM_INTERVAL_MS - elapsed).coerceAtLeast(16L)
    LaunchedEffect(state, state.lastTailApplyUptimeMs, state.pending != null) {
        if (state.pending == null) return@LaunchedEffect
        delay(waitMs)
        val next = state.pending ?: return@LaunchedEffect
        state.pending = null
        state.lastTailApplyUptimeMs = SystemClock.uptimeMillis()
        state.presented = next
    }
    return state.presented
}

private class PresentedConfigurationState(initial: TranscriptConfiguration) {
    var presented by mutableStateOf(initial)
    var pending: TranscriptConfiguration? = null
    var lastTailApplyUptimeMs: Long = 0L
}

@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        enabled = manager.isTouchExplorationEnabled
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** Until the session supplies its Working line (activity orb + title), a quiet label. */
@Composable
private fun WorkingLineFallback(modifier: Modifier = Modifier) {
    Text("Working", modifier, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
}

/**
 * The transcript list as its image previews' viewport (iOS
 * `TranscriptListViewController+ImagePreviews.swift`): nothing counts as on screen while the
 * session opens, then the list's bounds between the header and the composer. A covered or
 * backgrounded list holds no previews.
 */
private class TranscriptImageViewport(private val controller: TranscriptListController) : AgentImageViewport {
    var bounds: Rect = Rect.Zero
    var topInset = 0f
    var bottomInset = 0f
    private var imagesMayShow = false

    override val imageViewportVisibleRect: Rect
        get() {
            if (!imagesMayShow && imageViewportIsSettling) return Rect.Zero
            imagesMayShow = true
            return Rect(bounds.left, bounds.top + topInset, bounds.right, (bounds.bottom - bottomInset).coerceAtLeast(bounds.top + topInset))
        }

    override val imageViewportIsSettling: Boolean get() = !imagesMayShow && controller.imagesSettling

    override val imageViewportIsVisible: Boolean get() = controller.isShowingToReader

    override fun imageContentRevised(row: Any?) {
        (row as? String)?.let(controller::imageContentRevised)
    }
}
