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
import ai.opencode.feature.transcript.selection.TranscriptReplySelectionMenu
import ai.opencode.feature.transcript.selection.transcriptReplySelectionGestures
import ai.opencode.feature.transcript.selection.transcriptReplySelectionHandles
import ai.opencode.feature.transcript.rows.NewMessagesDivider
import ai.opencode.feature.transcript.user.LocalSendLanding
import ai.opencode.feature.transcript.user.QueuedPillSource
import ai.opencode.feature.transcript.user.TranscriptMessageRow
import ai.opencode.feature.transcript.user.UserMessageRun
import ai.opencode.feature.transcript.user.queuedPillSource
import ai.opencode.server.model.OpenCodeQueuedInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlin.math.roundToInt

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
 * reports the behavior's button visibility.
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
) {
    // A new session is a new list (iOS resets the controller; the draft becoming its session keeps it).
    key(configuration.sessionID ?: "draft", configuration.historyGeneration) {
        TranscriptListContent(configuration, send, modifier, contentPadding, jumpRequest, onShowsJumpButtonChanged, slots, layoutTuning, onScrolledUnderHeader, borrowedDrag)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptListContent(
    configuration: TranscriptConfiguration,
    send: (TranscriptAction) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    jumpRequest: Int,
    onShowsJumpButtonChanged: (Boolean) -> Unit,
    slots: TranscriptSlots,
    layoutTuning: TranscriptLayoutTuning,
    onScrolledUnderHeader: (Float) -> Unit,
    borrowedDrag: TranscriptBorrowedDrag?,
) {
    val scope = rememberCoroutineScope()
    val initialIndex = remember { (configuration.entries.size + configuration.queuedInputs.size + 2).coerceAtLeast(0) }
    val listState = remember {
        LazyListState(
            // Prepare a viewport in the scroll direction rather than only the next row;
            // retain half a viewport behind so a short reversal can reuse measured Markdown.
            cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f),
            firstVisibleItemIndex = initialIndex,
        )
    }
    val controller = remember { TranscriptListController(listState, scope) }
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val currentSend by rememberUpdatedState(send)
    val currentShowsJump by rememberUpdatedState(onShowsJumpButtonChanged)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    controller.send = { currentSend(it) }
    controller.setShowsJumpButton = { currentShowsJump(it) }
    controller.density = density.density
    controller.frameDuration = ai.opencode.core.motion.rememberFrameDurationSeconds()
    controller.reduceMotion = reduceMotion
    controller.isShowingToReader = resumed.isAtLeast(Lifecycle.State.RESUMED)
    controller.sendLanding = ai.opencode.feature.transcript.user.LocalSendLanding.current
    controller.flingBehavior = ScrollableDefaults.flingBehavior()
    // Applied in composition so this frame draws the new rows with their stable keys.
    controller.update(configuration)
    controller.obstructionChanged(contentPadding.calculateBottomPadding().value, WindowInsets.ime.inContainer().getBottom(density) / density.density)
    SideEffect { controller.flushEffects() }
    // iOS `TranscriptTouchSafety`: a hand-off that never lands can't keep the list's touches.
    rememberTouchRecovery(
        owner = TouchRecovery.Owner.Transcript,
        inspect = { controller.touchRecoveryFault() },
        release = { controller.releaseTouchRecoveryFault() },
    )

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
    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE
            else listState.firstVisibleItemScrollOffset / density.density
        }.collect { currentScrolledUnderHeader(it) }
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
    val linkPreviews = remember { LinkPreviewStore() }
    val linkRevisions = remember { mutableStateMapOf<String, Int>() }
    DisposableEffect(linkPreviews) {
        linkPreviews.onArrival = { rows ->
            for (row in rows) {
                controller.imageContentRevised(row)
                linkRevisions[row] = linkPreviews.revision(row)
            }
        }
        onDispose {
            linkPreviews.onArrival = null
            linkPreviews.close()
        }
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
        Box(
            modifier
                .nestedScroll(controller.nestedScrollConnection)
                .onPlaced { replySelection.container = it }
                .transcriptReplySelectionGestures(replySelection)
                .transcriptReplySelectionHandles(replySelection)
                .magnifier(sourceCenter = { replySelection.magnifierCenter }),
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
                        .graphicsLayer { alpha = controller.handoffProgress() }
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
                                // Working handing off to the reply's end has already become it (iOS `handsOff`): no fade.
                                fadeOutSpec = if (item == TranscriptItemID.Empty || !controller.fadesRemovals ||
                                    (item == TranscriptItemID.Working && configuration.workingHandsOffToCopy)
                                ) null else Motion.swap.animation(MotionPhase.Exit) /* a row leaving the screen fades on Motion.swap's exit */,
                            )
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
            // Outside the layout below, so the clip and soft edge sit at the reported (growing) height.
            .graphicsLayer { clip = controller.isClipping(key) }
            .drawWithContent {
                drawContent()
                val edge = controller.softEdge(key) * density
                if (edge > 0.5f) {
                    // The soft edge only ever brightens what it covers (iOS growth mask): the row
                    // fades into the transcript's canvas behind it. Drawn over, not punched out
                    // (DstOut), which needs an offscreen layer chosen in the same frame; a layer
                    // still on Auto the frame growth starts showed the cut as a black band.
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(canvas.copy(alpha = 0f), canvas),
                            startY = size.height - edge,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - edge),
                    )
                }
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val measured = placeable.height / density
                val height = controller.fittedHeight(key, measured, placeable.width / density)
                val px = (height * density).roundToInt().coerceAtLeast(0)
                layout(placeable.width, px) { placeable.place(0, 0) }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        content()
    }
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
