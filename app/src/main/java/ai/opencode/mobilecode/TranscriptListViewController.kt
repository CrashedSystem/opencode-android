@file:PortedFrom("OpenCode/Transcript/TranscriptListViewController.swift", "7fa8e81", partial = "all but the scroll trace's follow measurements (arrive, chase, lag, jump, stray)")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Store
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The transcript list. The UIKit table becomes a `LazyColumn` with stable
/// keys and content types; one frame-clock driven follower (the motion link)
/// owns every scroll the list makes itself, as on iOS.
class TranscriptListViewController(
    private val store: Store<SessionFeature.State, SessionFeature.Action>,
    configuration: Configuration,
    composerInset: () -> Float,
    private val scrollEdgeBridge: SessionScrollEdgeBridge,
    private var usesSystemEdge: Boolean,
    private var reduceMotion: Boolean,
    var setShowsJumpButton: (Boolean) -> Unit,
    private val dismissKeyboard: () -> Unit,
    var onPreviewImage: (TranscriptImagePreview) -> Unit,
    /// Nanoseconds on the frame clock's time base.
    private val now: () -> Long = System::nanoTime,
) {
    private val layoutProbe = TranscriptLayoutProbe()
    private var snapshotTrace = TranscriptSnapshotTrace()

    data class Configuration(
        var sessionID: String?,
        var historyGeneration: Int,
        var entries: List<TranscriptEntry>,
        var hasEarlierItems: Boolean,
        var isLoadingEarlier: Boolean,
        var earlierHistoryError: String?,
        var isLoading: Boolean,
        var isRunning: Boolean,
        var hasPendingRequests: Boolean,
        var isDraft: Boolean,
        var queuedInputs: List<OpenCodeQueuedInput>,
        var sentItemID: String?,
        var sendGeneration: Int,
    )

    private sealed interface ItemID {
        data object history : ItemID
        data object empty : ItemID
        data class message(val stableID: String) : ItemID
        data object working : ItemID

        /// The lazy list key: saveable, and distinct for every row.
        val key: String
            get() = when (this) {
                history -> "history"
                empty -> "empty"
                working -> "working"
                is message -> "message:$stableID"
            }
    }

    @Immutable
    private sealed interface MessageRow {
        data class entry(val value: TranscriptEntry) : MessageRow
        data class queued(val value: OpenCodeQueuedInput) : MessageRow
    }

    /// A sent message's flight from the composer into its bubble, driven by the
    /// motion link so it lands on the bubble wherever the follower has carried it.
    private data class SendFlight(
        val item: ItemID,
        val itemID: String,
        var start: Long = 0,
        var deadline: Long = 0,
        var landed: Boolean = false,
    ) {
        companion object {
            /// About 0.45 s, like the send animation it replaces.
            const val omega = 14.0
            const val timeout = 0.8
        }
    }

    private var configuration = configuration
    private var behavior = TranscriptScrollBehavior()
    private var items: List<ItemID> = emptyList()
    private var messageRowsByStableID: Map<String, MessageRow> = emptyMap()
    var disclosure = TranscriptDisclosureState()
        private set
    private var textReveal = TranscriptTextReveal()
    private var agentReveal = TranscriptAgentMessageReveal()
    /// A disclosure's motion is running; its layout reports itself.
    private var isAnimatingDisclosure = false
    private var disclosureMotion = 0
    private var entryStableIDs: Map<String, String> = emptyMap()
    private var queuedStableIDs: Map<String, String> = emptyMap()

    val listState: LazyListState = LazyListState()

    /// Seeded at creation: the request counter outlives a swapped-in session.
    var lastJumpRequest = 0
    var onOpenFile: (AgentFileReference) -> Unit = {}

    /// The one frame-clock loop for transcript motion: the follower (every
    /// animated move to the live bottom: jump to latest, a pill growing above
    /// the composer, rows arriving while following; see `springToBottom`) and
    /// a sent message's flight from the composer. It stops when both settle.
    private var motionLink: Job? = null
    private var springActive = false
    private var springKind = SpringKind.arrival
    private var bottomSpringOmega = BottomSpring.jumpOmega
    private var bottomSpringVelocity = 0.0
    private var bottomSpringTime = 0L
    private var bottomSpringDeadline = 0L
    /// Where the end was expected after the last step: a different end is a
    /// retarget, which extends the deadline.
    private var bottomSpringExpected = Float.NaN

    /// Viewport geometry when a following reader last rested on the bottom.
    /// Content growing under the same geometry glides; a pin under new
    /// geometry (the keyboard, the trailing space) lands at once as before.
    private var pinnedGeometry: PinGeometry? = null

    /// A session opens on its bottom: the self-sizing and refresh that follow
    /// its first content frame are settling, not arrivals, and land at once.
    private var arrivalsGlideAfter = Long.MAX_VALUE

    /// Rows inserted at the tail; they fade in when first displayed.
    private val pendingEntrances = HashMap<String, Long>()
    private var lastLayoutViewport: TranscriptScrollBehavior.Viewport? = null
    private var hasLaidOutContent = false
    private var isExecutingCommands = false
    private var sendFlight: SendFlight? = null
    private var isMovingOffset = false

    /// Content that joined the end while the reader's finger held the list.
    private var contentArrivedDuringDrag = false
    private var isDragging = false
    private var wasUserScrolling = false
    private var lastOffsetSignature = Long.MIN_VALUE

    /// Set only while the jump-to-latest command executes, so its bottom scroll
    /// may interrupt native deceleration where automatic pins must not.
    private var isHandlingExplicitJump = false

    /// The composer's obstruction, measured by the session screen in the
    /// same layout pass the list reads it in. Trailing space that holds the
    /// reader's place is additive and remains owned by the scroll behavior.
    private var composerInset: () -> Float = composerInset
    private var keyboardIsMoving: () -> Boolean = { false }
    private var lastAppliedComposerPart = Float.NaN

    /// Held space, in pixels: the behavior's, and the follower's own beyond
    /// the end (an offset past the end is bottom padding the spring drains).
    private var trailingSpace = 0f
    private var motionSpace = 0f
    private val paddingVersion = mutableIntStateOf(0)

    private var density = 1f
    private var topInset = 0f
    private var frameInterval = 16_666_667L
    private var scope: CoroutineScope? = null
    private var reconcileScheduled = false
    private var pending: PendingUpdate? = null
    private var pendingApply: PendingApply? = null
    private val itemSizes = HashMap<String, Int>()

    /// Forwarders with a stable identity, so rows skip when only the
    /// session screen's closures changed.
    private val previewImage: (TranscriptImagePreview) -> Unit = { onPreviewImage(it) }
    private val openFile: (AgentFileReference) -> Unit = { onOpenFile(it) }

    init {
        installDisclosureMotion()
        applySnapshot(configuration, previousConfiguration = null, isInitial = true)
        if (items.isNotEmpty()) landAtEnd()
    }

    /// The list. Its padding reads the composer and held space during
    /// measure, so an inset lands in the same frame as its snapshot. Never
    /// skipped: its rows are plain fields `update` just replaced.
    @NonSkippableComposable
    @Composable
    fun View(modifier: Modifier = Modifier) {
        val composeDensity = LocalDensity.current
        density = composeDensity.density
        frameInterval = (1_000_000_000.0 / (LocalView.current.display?.refreshRate?.takeIf { it > 0f } ?: 60f)).toLong()
        val coroutineScope = rememberCoroutineScope()
        scope = coroutineScope
        val rows = items
        val messages = messageRowsByStableID
        val disclosure = disclosure
        val padding = remember(this) { ListPadding() }
        CompositionLocalProvider(
            LocalTranscriptTextReveal provides textReveal,
            LocalComposerSendTransition provides scrollEdgeBridge.sendTransition,
        ) {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .semantics { testTag = "transcript-list" }
                    .then(scrollTrace?.modifier ?: Modifier),
                state = listState,
                contentPadding = padding,
                flingBehavior = ScrollableDefaults.flingBehavior(),
            ) {
                items(
                    count = rows.size,
                    key = { rows[it].key },
                    contentType = { contentType(rows[it], messages) },
                ) { index ->
                    val item = rows[index]
                    val entrance = remember(item.key) { takeEntrance(item.key) }
                    val row = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TranscriptMetrics.contentGutter.dp)
                        .then(if (entrance) Modifier.rowEntrance(reduceMotion) else Modifier)
                    when (item) {
                        is ItemID.message -> {
                            val message = messages[item.stableID] ?: return@items
                            MessageItem(message, store, previewImage, openFile, disclosure, row)
                        }
                        ItemID.history -> {
                            val configuration = configuration
                            androidx.compose.foundation.layout.Box(row) {
                                TranscriptHistoryStatusView(
                                    isLoading = configuration.isLoadingEarlier,
                                    error = configuration.earlierHistoryError,
                                    retry = { store.send(SessionFeature.Action.showEarlierTapped) },
                                )
                            }
                        }
                        ItemID.empty -> androidx.compose.foundation.layout.Box(row) { TranscriptEmptyView() }
                        ItemID.working -> {
                            val needsInput = configuration.hasPendingRequests
                            LaunchedEffect(Unit) { LabMarks.mark("session_working_visible", configuration.sessionID) }
                            androidx.compose.foundation.layout.Box(row.padding(vertical = TranscriptMetrics.flowHalf.dp)) {
                                WorkingIndicator(needsInput = needsInput)
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(this) {
            snapshotFlow { LayoutSignature(listState.layoutInfo, listState.isScrollInProgress) }.collect { reconcile() }
        }
        LaunchedEffect(this) {
            listState.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        isDragging = true
                        scrollViewWillBeginDragging()
                    }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> {
                        isDragging = false
                        scheduleReconcile()
                    }
                }
            }
        }
        DisposableEffect(this) {
            scrollTrace?.start(coroutineScope)
            onDispose { viewDidDisappear() }
        }
        SideEffect { didUpdate() }
    }

    private class LayoutSignature(val info: LazyListLayoutInfo, val scrolling: Boolean) {
        override fun equals(other: Any?) = other is LayoutSignature && other.info === info && other.scrolling == scrolling
        override fun hashCode() = System.identityHashCode(info) * 31 + scrolling.hashCode()
    }

    private inner class ListPadding : PaddingValues {
        override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
        override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
        override fun calculateTopPadding(): Dp = (topInset / density).dp
        override fun calculateBottomPadding(): Dp = (writeBottomInset() / density).dp
    }

    private fun viewDidDisappear() {
        stopBottomSpring()
        finishSendFlight()
        motionLink?.cancel()
        motionLink = null
        scrollTrace?.stop()
        scope = null
    }

    fun update(
        configuration: Configuration,
        composerInset: () -> Float,
        keyboardIsMoving: () -> Boolean,
        topInset: Float,
        usesSystemEdge: Boolean,
        reduceMotion: Boolean,
        jumpRequest: Int,
    ) {
        // Android has no system scroll edge: the list always takes the whole
        // header band as padding, as iOS does without it.
        this.usesSystemEdge = usesSystemEdge
        this.composerInset = composerInset
        this.keyboardIsMoving = keyboardIsMoving
        if (this.topInset != topInset) {
            this.topInset = topInset
            invalidatePadding()
        }
        val newConfiguration = configuration
        val sessionChanged = newConfiguration.sessionID != this.configuration.sessionID ||
            newConfiguration.historyGeneration != this.configuration.historyGeneration
        val sendChanged = newConfiguration.sendGeneration != this.configuration.sendGeneration

        if (sessionChanged) {
            stopBottomSpring()
            finishSendFlight()
            scrollEdgeBridge.sendTransition.cancel()
            behavior = TranscriptScrollBehavior()
            hasLaidOutContent = false
            lastLayoutViewport = null
            pinnedGeometry = null
            arrivalsGlideAfter = Long.MAX_VALUE
            pendingEntrances.clear()
            entryStableIDs = emptyMap()
            queuedStableIDs = emptyMap()
            disclosure = TranscriptDisclosureState()
            textReveal = TranscriptTextReveal()
            agentReveal = TranscriptAgentMessageReveal()
            installDisclosureMotion()
            itemSizes.clear()
        }
        // Changing Reduce Motion only affects how later commands animate; the
        // reader's scroll mode and paging state carry on.
        this.reduceMotion = reduceMotion

        val oldConfiguration = this.configuration
        this.configuration = newConfiguration
        if (oldConfiguration != newConfiguration) {
            applySnapshot(
                newConfiguration,
                previousConfiguration = if (sessionChanged) null else oldConfiguration,
                isInitial = false,
            )
        }
        val earlierWindowAdvanced = !sessionChanged &&
            !newConfiguration.isLoadingEarlier &&
            newConfiguration.hasEarlierItems &&
            oldConfiguration.entries.firstOrNull()?.id != newConfiguration.entries.firstOrNull()?.id
        val change = pending ?: PendingUpdate()
        pending = change.copy(
            sessionChanged = change.sessionChanged || sessionChanged,
            earlierHistoryCompleted = change.earlierHistoryCompleted ||
                (oldConfiguration.isLoadingEarlier && !newConfiguration.isLoadingEarlier) || earlierWindowAdvanced,
            // Held space only bridges a running turn's row swaps; once it ends, the
            // reader settles onto the end.
            runEnded = change.runEnded || (oldConfiguration.isRunning && !newConfiguration.isRunning && !sessionChanged),
            send = if (sendChanged) newConfiguration.sentItemID?.let { TranscriptEntry.entryID(containing = it, entries = newConfiguration.entries) } ?: "" else change.send,
            jump = change.jump || jumpRequest != lastJumpRequest,
        )
        lastJumpRequest = jumpRequest
    }

    private data class PendingUpdate(
        val sessionChanged: Boolean = false,
        val earlierHistoryCompleted: Boolean = false,
        val runEnded: Boolean = false,
        /// The sent message's entry, "" for a send with no row, nil for none.
        val send: String? = null,
        val jump: Boolean = false,
    )

    /// The parts of `update` that move the list, once the update composed.
    private fun didUpdate() {
        pendingScrollRequest?.let {
            pendingScrollRequest = null
            it()
        }
        val change = pending ?: return
        pending = null
        if (change.sessionChanged) {
            execute(behavior.handle(TranscriptScrollBehavior.Event.sessionChanged))
            if (items.isNotEmpty()) landAtEnd()
        }
        if (change.earlierHistoryCompleted) execute(behavior.handle(TranscriptScrollBehavior.Event.earlierHistoryRequestCompleted))
        if (change.runEnded) releaseHeldSpace()
        // A send is an arrival like any other: it is followed only if the reader
        // already follows the bottom, and never pulls back a reader browsing above.
        change.send?.let { landSentMessage(it.ifEmpty { null }) }
        if (change.jump) {
            isHandlingExplicitJump = true
            val previousMode = behavior.mode
            execute(behavior.handle(TranscriptScrollBehavior.Event.jumpToLatestTapped))
            isHandlingExplicitJump = false
            notifyBrowsingChange(previousMode)
        }
        if (pendingApply != null) scheduleReconcile()
    }

    private fun scrollViewWillBeginDragging() {
        stopBottomSpring()
        isHandlingExplicitJump = false
        contentArrivedDuringDrag = false
        // Recorded before the keyboard leaves, which holds the reader in place.
        handleBehavior(TranscriptScrollBehavior.Event.dragBegan(viewport()))
        dismissKeyboard()
    }

    /// The reader's scroll came to rest. Back at the bottom with nothing
    /// running, space held under their drag lets go.
    private fun settleUserScroll(contentArrived: Boolean) {
        handleBehavior(TranscriptScrollBehavior.Event.userSettled(viewport(), contentArrived = contentArrived))
        if (behavior.mode == TranscriptScrollBehavior.Mode.following && !configuration.isRunning) releaseHeldSpace()
    }

    /// Every layout pass the list completes: the reader's scrolls (drags,
    /// flings, and TalkBack's, which never reach the follower) and layout
    /// changes the follower or the reader's anchor must answer.
    private fun reconcile() {
        reconcileScheduled = false
        if (scope == null) return
        layoutProbe.sessionID = configuration.sessionID
        layoutProbe.enter("list-layout")
        try {
            recordItemSizes()
            val becameLayoutReady = establishContentLayoutIfNeeded()
            pendingApply?.let { apply ->
                pendingApply = null
                finishApply(apply, becameLayoutReady)
                return
            }
            if (isAnimatingDisclosure || isExecutingCommands) return
            val scrolling = isUserScrolling
            val signature = offsetSignature()
            if (scrolling && signature != lastOffsetSignature && hasLaidOutContent) {
                handleBehavior(TranscriptScrollBehavior.Event.userScrolled(viewport()))
            }
            lastOffsetSignature = signature
            if (wasUserScrolling && !scrolling) {
                wasUserScrolling = false
                settleUserScroll(contentArrivedDuringDrag)
            }
            wasUserScrolling = scrolling
            if (hasLaidOutContent && (becameLayoutReady || layoutMetricsChanged(viewport()))) reportLayoutChanged()
        } finally {
            layoutProbe.leave()
        }
    }

    private fun scheduleReconcile() {
        if (reconcileScheduled) return
        val scope = scope ?: return
        reconcileScheduled = true
        scope.launch {
            withFrameNanos {}
            if (reconcileScheduled) reconcile()
        }
    }

    private fun contentType(item: ItemID, rows: Map<String, MessageRow>): String = when (item) {
        ItemID.history -> "history"
        ItemID.empty -> "empty"
        ItemID.working -> "working"
        is ItemID.message -> when (val row = rows[item.stableID]) {
            is MessageRow.entry -> row.value.layoutKind
            // One root view for both kinds of row, so a pending message and the
            // delivered message it promotes into keep their identity.
            is MessageRow.queued -> if (row.value.isCompaction || row.value.notice != null) "pending" else "user"
            null -> "message"
        }
    }

    /// One root view for both kinds of row, so a pending message and the
    /// delivered message it promotes into keep their identity.
    @Composable
    private fun MessageItem(
        row: MessageRow,
        store: Store<SessionFeature.State, SessionFeature.Action>,
        onPreviewImage: (TranscriptImagePreview) -> Unit,
        onOpenFile: (AgentFileReference) -> Unit,
        disclosure: TranscriptDisclosureState,
        modifier: Modifier,
    ) {
        val entry = (row as? MessageRow.entry)?.value
        val queued = (row as? MessageRow.queued)?.value
        androidx.compose.foundation.layout.Box(modifier) {
            TranscriptMessageRow(
                entry = entry,
                queued = queued,
                store = store,
                onPreviewImage = onPreviewImage,
                onOpenFile = onOpenFile,
                disclosure = disclosure,
                revealArrival = (entry?.userMessageID ?: queued?.id)?.let { agentReveal.arrival(of = it) },
            )
        }
    }

    private fun applySnapshot(configuration: Configuration, previousConfiguration: Configuration?, isInitial: Boolean) {
        val previousEntries = previousConfiguration?.entries ?: emptyList()
        val oldItemIDs = previousEntries.flatMap { it.itemIDs }.toSet()
        val oldMessageRowsByStableID = messageRowsByStableID
        val oldItems = items.toSet()
        val anchor = capturePositionAnchor()
        val identityPlan = TranscriptRowIdentityPlan.make(
            previousEntries = previousEntries,
            previousQueued = previousConfiguration?.queuedInputs ?: emptyList(),
            previousEntryStableIDs = entryStableIDs,
            previousQueuedStableIDs = queuedStableIDs,
            entries = configuration.entries,
            queued = configuration.queuedInputs,
        )
        entryStableIDs = identityPlan.entryStableIDs
        queuedStableIDs = identityPlan.queuedStableIDs
        // Stable ids are the list's keys, so a collision here would crash the
        // lazy list (TestFlight build 81 died this way on duplicate row
        // identity). A stable id is a function of the entry id, so this also
        // covers duplicate entries. Entries win over queued rows and the first
        // occurrence wins; the log keeps the bug visible without taking the
        // app down for a tester.
        val entryRows = configuration.entries.map { entry -> (entryStableIDs[entry.id] ?: entry.id) to MessageRow.entry(entry) }
        val queuedRows = configuration.queuedInputs
            .filter {
                (it.isCompaction || it.notice != null || it.text.isNotEmpty() || it.images.isNotEmpty() || it.files.isNotEmpty()) &&
                    !identityPlan.supersededQueuedIDs.contains(it.id)
            }
            .map { input -> (queuedStableIDs[input.id] ?: input.id) to MessageRow.queued(input) }
        val rowsByStableID = LinkedHashMap<String, MessageRow>()
        val entryItems = mutableListOf<ItemID>()
        val queuedItems = mutableListOf<ItemID>()
        for ((stableID, row) in entryRows) {
            if (rowsByStableID.containsKey(stableID)) continue
            rowsByStableID[stableID] = row
            entryItems += ItemID.message(stableID)
        }
        for ((stableID, row) in queuedRows) {
            if (rowsByStableID.containsKey(stableID)) continue
            rowsByStableID[stableID] = row
            queuedItems += ItemID.message(stableID)
        }
        if (entryItems.size + queuedItems.size != entryRows.size + queuedRows.size) {
            val duplicates = (entryRows.map { it.first } + queuedRows.map { it.first }).groupBy { it }.filter { it.value.size > 1 }.keys.sorted()
            Log.e("TranscriptList", "Duplicate transcript row stable ids: $duplicates")
        }
        messageRowsByStableID = rowsByStableID

        val newItems = mutableListOf<ItemID>()
        if (configuration.hasEarlierItems) newItems += ItemID.history
        // A draft's setup sits above the composer instead.
        if (configuration.entries.isEmpty() && !configuration.isRunning && !configuration.isLoading && !configuration.isDraft) {
            newItems += ItemID.empty
        }
        newItems += entryItems
        // Working closes the transcript, below pending messages and notices as
        // in the web app, so a steering message promotes without moving.
        newItems += queuedItems
        if (configuration.isRunning) newItems += ItemID.working

        val changedItems = newItems.filter { item ->
            if (!oldItems.contains(item)) return@filter false
            when (item) {
                is ItemID.message -> oldMessageRowsByStableID[item.stableID] != messageRowsByStableID[item.stableID]
                ItemID.history -> previousConfiguration?.isLoadingEarlier != configuration.isLoadingEarlier ||
                    previousConfiguration?.earlierHistoryError != configuration.earlierHistoryError
                ItemID.working -> previousConfiguration?.hasPendingRequests != configuration.hasPendingRequests
                ItemID.empty -> false
            }
        }
        val newItemSet = newItems.toSet()
        LabMarks.mark(
            "snapshot_apply",
            configuration.sessionID,
            mapOf(
                "changed" to changedItems.size,
                "inserted" to newItemSet.count { !oldItems.contains(it) },
                "removed" to oldItems.count { !newItemSet.contains(it) },
            ),
        )
        if (BuildConfig.DEBUG && LabMarks.isEnabled) traceSnapshot(newItems)

        val appendedEntry = TranscriptEntry.tail(of = configuration.entries).any { entry ->
            entry.itemIDs.any { !oldItemIDs.contains(it) }
        }
        val appendedQueued = queuedItems.any { !oldItems.contains(it) }
        val agentArrivals = recordAgentArrivals(
            items = newItems,
            inserted = newItemSet.filter { !oldItems.contains(it) }.toSet(),
            oldItems = oldItems,
            isLive = !isInitial && previousConfiguration != null && hasLaidOutContent && now() >= arrivalsGlideAfter,
        )
        val promoted = !reduceMotion && identityPlan.promotedEntryIDs.isNotEmpty()
        // Rows that join the end of a transcript already on screen fade in: after
        // the last entry the reader already had (Working and pending messages
        // below them don't count). Not a session's first rows, a page of them,
        // or a sent message, which lands from the composer.
        val entrances: List<ItemID> = run {
            if (isInitial || reduceMotion || promoted || !hasLaidOutContent || now() < arrivalsGlideAfter) return@run emptyList()
            if (previousConfiguration == null || previousConfiguration.sendGeneration != configuration.sendGeneration) return@run emptyList()
            val lastKept = newItems.indexOfLast { item ->
                item is ItemID.message && oldItems.contains(item) && messageRowsByStableID[item.stableID] is MessageRow.entry
            }
            if (lastKept < 0) return@run emptyList()
            val inserted = newItemSet.filter { !oldItems.contains(it) }.toSet()
            val tail = newItems.drop(lastKept + 1).filter { inserted.contains(it) }
            if (tail.size != inserted.size || tail.size > RowEntrance.maximumRows || tail.any { it == ItemID.history || it == ItemID.empty }) {
                return@run emptyList()
            }
            // An agent's message fades in with its own reveal instead.
            tail.filter { !agentArrivals.contains(it) }
        }
        holdRemovedTail(oldItems, newItemSet, configuration)
        val prepended = anchorFirstMessage(newItems)
        items = newItems
        if (!isInitial) {
            pendingApply = PendingApply(
                anchor = anchor,
                appended = appendedEntry || appendedQueued,
                contentHeightBefore = estimatedContentHeight(),
                prepended = prepended,
            )
        }
        beginEntrances(entrances)
    }

    /// Agents' messages joining the end of a transcript already on screen: each
    /// records its arrival once, which starts its reveal. Any other first
    /// sighting (a session opening, a page of history) has no reveal.
    private fun recordAgentArrivals(items: List<ItemID>, inserted: Set<ItemID>, oldItems: Set<ItemID>, isLive: Boolean): Set<ItemID> {
        if (!isLive || inserted.isEmpty()) return emptySet()
        val lastKept = items.indexOfLast { item ->
            item is ItemID.message && oldItems.contains(item) && messageRowsByStableID[item.stableID] is MessageRow.entry
        }
        val time = now()
        val arrivals = mutableSetOf<ItemID>()
        items.forEachIndexed { index, item ->
            if (!inserted.contains(item) || index <= lastKept) return@forEachIndexed
            val message = (item as? ItemID.message)?.let { messageRowsByStableID[it.stableID] }?.let(::userMessage) ?: return@forEachIndexed
            if (!message.isFromAgent || !agentReveal.arrive(message.id, at = time)) return@forEachIndexed
            arrivals += item
        }
        return arrivals
    }

    private fun userMessage(row: MessageRow): TranscriptUserMessage? = when (row) {
        is MessageRow.entry -> TranscriptUserMessage(row.value)
        is MessageRow.queued -> TranscriptUserMessage(row.value)
    }

    private fun traceSnapshot(newItems: List<ItemID>) {
        val ids = newItems.mapNotNull { (it as? ItemID.message)?.stableID }
        val previousIDs = snapshotTrace.previous
        val change = snapshotTrace.record(configuration.sessionID, ids, System.currentTimeMillis() / 1000.0)
        val rows = ids.mapNotNull { id ->
            when (val row = messageRowsByStableID[id]) {
                is MessageRow.entry -> row.value.userMessageID?.let { promptID ->
                    mapOf("promptID" to promptID, "rowID" to id, "entryID" to row.value.id, "phase" to "delivered")
                }
                is MessageRow.queued -> if (!row.value.isCompaction && row.value.notice == null) {
                    mapOf("promptID" to row.value.id, "rowID" to id, "entryID" to row.value.id, "phase" to "pending")
                } else {
                    null
                }
                null -> null
            }
        }
        LabMarks.transcriptSnapshot(
            sessionID = configuration.sessionID,
            ids = ids,
            vanished = change.vanished,
            reappeared = change.reappeared,
            rows = rows,
            inserted = ids.filter { !previousIDs.contains(it) },
        )
    }

    /// The browsing row to hold in place, captured before the rows change.
    private data class PositionAnchor(
        val layoutReady: Boolean,
        val followedBottom: Boolean,
    )

    private fun capturePositionAnchor(): PositionAnchor {
        val info = listState.layoutInfo
        val layoutReady = hasLaidOutContent && info.viewportSize.width > 0 && info.viewportSize.height > 0
        return PositionAnchor(
            layoutReady = layoutReady,
            followedBottom = layoutReady && behavior.mode == TranscriptScrollBehavior.Mode.following &&
                abs(offsetPastEnd()) < density,
        )
    }

    private data class PendingApply(
        val anchor: PositionAnchor,
        val appended: Boolean,
        val contentHeightBefore: Float,
        val prepended: Boolean,
    )

    /// The lazy list keeps the first visible row in place itself. A page of
    /// history lands below the history row, though, which the list would
    /// keep at the top: hold the first visible message instead.
    private fun anchorFirstMessage(newItems: List<ItemID>): Boolean {
        if (!hasLaidOutContent) return false
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return false
        if (first.key != ItemID.history.key) return false
        val message = info.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("message:") == true } ?: return false
        val index = newItems.indexOfFirst { it.key == message.key }
        if (index < 0 || index == message.index) return false
        val offset = message.offset
        pendingScrollRequest = { listState.requestScrollToItem(index, -offset) }
        return true
    }

    private var pendingScrollRequest: (() -> Unit)? = null

    /// Rows leaving the end under a following reader while a turn runs (a
    /// pending row leaving before its delivered message lands) would let the
    /// list clamp the reader down: their space is held, and arriving content
    /// fills it before the view moves on.
    private fun holdRemovedTail(oldItems: Set<ItemID>, newItems: Set<ItemID>, configuration: Configuration) {
        if (!hasLaidOutContent || behavior.mode != TranscriptScrollBehavior.Mode.following || !configuration.isRunning) return
        if (abs(offsetPastEnd()) > density) return
        val removed = oldItems.filter { !newItems.contains(it) }.map { it.key }.toSet()
        if (removed.isEmpty()) return
        val held = listState.layoutInfo.visibleItemsInfo.filter { removed.contains(it.key) }.sumOf { it.size }
        if (held <= 0) return
        trailingSpace += held
        invalidatePadding()
    }

    /// After the layout that follows an update: the reader's scroll mode
    /// answers what changed, and the follower moves only a following reader.
    private fun finishApply(change: PendingApply, becameLayoutReady: Boolean) {
        val grew = estimatedContentHeight() > change.contentHeightBefore + 0.5f * density
        if (isUserScrolling && (change.appended || grew)) contentArrivedDuringDrag = true
        // A following reader is moved by the follower alone.
        if (change.anchor.layoutReady && behavior.mode == TranscriptScrollBehavior.Mode.following && !isUserScrolling) {
            settleShrunkEnd()
        }
        handleBehavior(
            TranscriptScrollBehavior.Event.contentChanged(
                anchorDelta = 0f,
                appended = change.appended,
                viewport = viewport(),
                layoutReady = change.anchor.layoutReady,
            ),
        )
        // Report the compensated layout. A following reader whose end grew
        // needs the follower even if a glide had them short of the bottom.
        if (becameLayoutReady || change.anchor.followedBottom || behavior.mode == TranscriptScrollBehavior.Mode.following && grew) {
            reportLayoutChanged()
        } else if (hasLaidOutContent && layoutMetricsChanged(viewport())) {
            lastLayoutViewport = viewport()
        }
    }

    /// The end now lies above a following reader (rows or the composer
    /// shrank). While a turn runs this is a swap in flight (a pending row
    /// leaving before its delivered message lands, the copy button leaving as
    /// a run starts): hold them, and arriving content fills the space before
    /// the view moves on. Otherwise they settle onto the end. Keyboard motion
    /// moves the end itself and is the follower's to ride.
    private fun settleShrunkEnd() {
        if (behavior.mode != TranscriptScrollBehavior.Mode.following || offsetPastEnd() <= 0.5f * density) return
        if (springActive && (springKind == SpringKind.keyboard || springKind == SpringKind.jump)) return
        val keyboardMoving = keyboardIsMoving() || pinnedGeometry?.let { it.height != currentPinGeometry.height } == true
        if (configuration.isRunning && !keyboardMoving) {
            handleBehavior(TranscriptScrollBehavior.Event.endShrank(viewport()))
        } else if (!reduceMotion) {
            springToBottom(if (keyboardMoving) SpringKind.keyboard else SpringKind.settle)
        } else {
            setMotionSpace(0f)
            pinnedGeometry = currentPinGeometry
        }
    }

    /// Held space lets go when a turn ends: the reader glides down onto the end.
    private fun releaseHeldSpace() {
        if (trailingSpace <= 0f || behavior.mode != TranscriptScrollBehavior.Mode.following || !hasLaidOutContent) return
        val held = trailingSpace
        val atEnd = remainingScroll()?.let { it <= 0.5f * density } == true
        execute(listOf(TranscriptScrollBehavior.Command.setTrailingSpace(0f)))
        if (!atEnd) return
        if (reduceMotion) return
        // The reader stays where they were, past the new end, and glides down.
        setMotionSpace(motionSpace + held)
        springToBottom(SpringKind.settle)
    }

    private fun installDisclosureMotion() {
        disclosure.animateToggle = { entryID, opening, toggle -> toggleDisclosure(entryID, opening, toggle) }
    }

    /// The reader opened or closed something in a row. The row stays exactly
    /// where it is and its edge moves: opening, the new content is revealed
    /// downward from under the row as it fades in; closing, the reverse,
    /// quicker. The rows below travel with the edge in the same critically
    /// damped motion, and nothing above moves. It is the reader's change, so
    /// the follower never chases it: a following reader whose end now lies
    /// below the screen is browsing (Jump to latest offers the way back).
    private fun toggleDisclosure(entryID: String, opening: Boolean, toggle: () -> Unit) {
        val key = ItemID.message(entryStableIDs[entryID] ?: entryID).key
        val scope = scope
        if (scope == null || !hasLaidOutContent || listState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
            toggle()
            return
        }
        stopBottomSpring()
        scope.launch { listState.stopScroll() }
        isAnimatingDisclosure = true
        disclosureMotion += 1
        val motion = disclosureMotion
        toggle()
        // Closing near the end: the view settles onto the new end in the same
        // motion rather than leaving space below it, held space included.
        if (!opening && trailingSpace > 0f) {
            trailingSpace = 0f
            invalidatePadding()
        }
        scope.launch {
            if (!reduceMotion) delay((DisclosureMotion.duration(opening) * 1_000).toLong())
            withFrameNanos {}
            if (motion != disclosureMotion) return@launch
            isAnimatingDisclosure = false
            handleBehavior(TranscriptScrollBehavior.Event.disclosureToggled(viewport()))
            reportLayoutChanged()
        }
    }

    private fun beginEntrances(entrances: List<ItemID>) {
        val time = now()
        pendingEntrances.entries.removeAll { time - it.value >= RowEntrance.window }
        for (item in entrances) pendingEntrances[item.key] = time
    }

    /// A row composed for the first time fades in if it joined the end lately.
    private fun takeEntrance(key: String): Boolean {
        val inserted = pendingEntrances.remove(key) ?: return false
        return now() - inserted < RowEntrance.window
    }

    private fun execute(commands: List<TranscriptScrollBehavior.Command>) {
        if (commands.isEmpty()) return
        val wasExecutingCommands = isExecutingCommands
        isExecutingCommands = true
        try {
            for (command in commands) {
                when (command) {
                    // The lazy list keeps its first visible row in place on its own.
                    is TranscriptScrollBehavior.Command.adjustOffset -> Unit

                    is TranscriptScrollBehavior.Command.scrollToBottom ->
                        scrollToBottom(animated = command.animated && !reduceMotion, interruptingDeceleration = isHandlingExplicitJump)

                    is TranscriptScrollBehavior.Command.setTrailingSpace -> {
                        val space = command.space * density
                        // Space the follower held past the end becomes the behavior's.
                        if (space > trailingSpace && motionSpace > 0f) {
                            motionSpace = max(0f, motionSpace - (space - trailingSpace))
                        }
                        trailingSpace = space
                        invalidatePadding()
                    }

                    TranscriptScrollBehavior.Command.requestEarlierHistory ->
                        store.send(SessionFeature.Action.showEarlierTapped)

                    is TranscriptScrollBehavior.Command.showJumpButton -> setShowsJumpButton(command.show)
                }
            }
        } finally {
            isExecutingCommands = wasExecutingCommands
        }
    }

    /// The composer's text flies into the sent bubble while the follower
    /// glides the list up to it: one motion, because the flight lands on the
    /// bubble wherever the list has carried it this frame. A browsing reader
    /// stays where they are, so their message simply joins the end.
    private fun landSentMessage(entryID: String?) {
        val transition = scrollEdgeBridge.sendTransition
        finishSendFlight()
        val itemID = configuration.sentItemID
        if (entryID == null || itemID == null || !transition.isPrepared || reduceMotion ||
            behavior.mode != TranscriptScrollBehavior.Mode.following
        ) {
            transition.cancel()
            return
        }
        val item = ItemID.message(entryStableIDs[entryID] ?: entryID)
        if (!items.contains(item)) {
            transition.cancel()
            return
        }
        // The bubble lays out this frame; the flight starts once it has a place.
        transition.hide(itemID)
        sendFlight = SendFlight(item = item, itemID = itemID, deadline = now() + (SendFlight.timeout * 1e9).toLong())
        startMotionLink()
    }

    private fun stepSendFlight(frameTime: Long) {
        val flight = sendFlight ?: return
        val transition = scrollEdgeBridge.sendTransition
        if (!flight.landed) {
            if (transition.beginLanding(flight.itemID)) {
                flight.landed = true
                flight.start = frameTime
            } else if (frameTime > flight.deadline) {
                // No flight: the bubble enters like any other new row.
                sendFlight = null
                transition.cancel()
                return
            } else {
                return
            }
        }
        val elapsed = (frameTime - flight.start + frameInterval) / 1e9
        val omega = SendFlight.omega
        val progress = 1 - (1 + omega * elapsed) * exp(-omega * elapsed)
        if (progress >= 0.995 || elapsed >= SendFlight.timeout) {
            finishSendFlight()
            return
        }
        transition.stepLanding(progress.toFloat())
    }

    private fun finishSendFlight() {
        if (sendFlight == null) return
        sendFlight = null
        scrollEdgeBridge.sendTransition.finishLanding()
    }

    private fun scrollToBottom(animated: Boolean, interruptingDeceleration: Boolean = false) {
        if (!hasLaidOutContent || listState.layoutInfo.viewportSize.height <= 0) return
        // A running spring already lands on the live bottom.
        if (springActive && !interruptingDeceleration) return
        val height = listState.layoutInfo.viewportSize.height.toFloat()
        if (interruptingDeceleration) {
            // A tap is a destination, not a best-effort animated offset. Stop the
            // old gesture, then align the measured tail above the composer.
            stopBottomSpring()
            scope?.launch { listState.stopScroll() }
            isHandlingExplicitJump = false
            // Nearby, travel there with a spring; a long way off, jump.
            val distance = -offsetPastEnd()
            if (animated && distance > density && distance <= height * 2.5f) {
                springToBottom(SpringKind.jump)
                return
            }
            landAtEnd()
            pinnedGeometry = currentPinGeometry
            return
        }
        // Never compete with a finger. Automatic pins also yield to native
        // deceleration (the fling's end performs one final pin), but an
        // explicit jump halts the inertia and takes over.
        if (isUserScrolling) return
        val distance = abs(offsetPastEnd())
        // The keyboard (or the safe area) resizing the viewport under a pinned
        // reader: ride along with it.
        val pinned = pinnedGeometry
        if (!animated && !reduceMotion && hasLaidOutContent && now() >= arrivalsGlideAfter &&
            pinned != null && pinned != currentPinGeometry && distance > 0.5f * density && distance <= height
        ) {
            springToBottom(SpringKind.keyboard)
            return
        }
        // New rows or a growing reply under an unchanged viewport: glide up to
        // them instead of snapping by their height in one frame.
        if (!animated && glidesArrivals && distance > 0.5f * density && distance <= height * BottomSpring.arrivalReach) {
            springToBottom(SpringKind.arrival)
            return
        }
        if (animated) {
            springToBottom(SpringKind.jump)
            return
        }
        landAtEnd()
        pinnedGeometry = currentPinGeometry
    }

    /// Whether a following reader's bottom moving now is content arriving
    /// (glide) rather than the viewport changing or a session opening (land).
    private val glidesArrivals: Boolean
        get() = !reduceMotion && hasLaidOutContent && now() >= arrivalsGlideAfter &&
            currentPinGeometry == pinnedGeometry && !isUserScrolling

    /// A critically damped spring toward the bottom, retargeted every frame so
    /// rows that self-size along the way never cause a snap at the end. Frame
    /// by frame (not an animation) so every row between renders as it passes.
    /// The spring keeps its velocity across retargets: while a reply streams,
    /// each new line bends one continuous motion instead of restarting it.
    private fun springToBottom(kind: SpringKind) {
        val time = now()
        bottomSpringDeadline = time + (BottomSpring.timeout * 1e9).toLong()
        // Already running: it reads the live bottom on its next frame, now at
        // the keyboard's or the reader's pace if either took over.
        if (springActive) {
            if (kind == SpringKind.keyboard || kind == SpringKind.jump) {
                springKind = kind
                bottomSpringOmega = BottomSpring.jumpOmega
                // Velocity carried away from the new bottom (an arrival's upward
                // glide as the keyboard leaves) would overshoot it.
                val toward = -offsetPastEnd()
                if (bottomSpringVelocity * toward < 0) bottomSpringVelocity = 0.0
            }
            return
        }
        springActive = true
        springKind = kind
        bottomSpringOmega = if (kind == SpringKind.arrival) BottomSpring.arrivalOmega else BottomSpring.jumpOmega
        bottomSpringVelocity = 0.0
        // The clock starts on the first frame, so that frame moves one step.
        bottomSpringTime = 0L
        bottomSpringExpected = Float.NaN
        startMotionLink()
    }

    private fun startMotionLink() {
        if (motionLink != null) return
        val scope = scope ?: return
        motionLink = scope.launch {
            try {
                while (springActive || sendFlight != null) {
                    withFrameNanos(stepMotion)
                }
            } finally {
                motionLink = null
            }
        }
    }

    private val stepMotion: (Long) -> Unit = { frameTime ->
        if (springActive) stepBottomSpring(frameTime)
        if (sendFlight != null) stepSendFlight(frameTime)
    }

    private fun stepBottomSpring(frameTime: Long) {
        // A reader who took over (browsing again) owns the offset now.
        if (behavior.mode != TranscriptScrollBehavior.Mode.following || isUserScrolling) {
            stopBottomSpring()
            return
        }
        if (bottomSpringTime == 0L) bottomSpringTime = frameTime - frameInterval
        // A hitch is not caught up in one leap: at most one frame of motion per frame.
        val dt = min(max(frameTime - bottomSpringTime, 0L), frameInterval) / 1e9
        bottomSpringTime = frameTime
        // Exact critically damped step from the current offset, so any other
        // offset change (a compensated row above) is carried, not undone.
        val x0 = offsetPastEnd().toDouble()
        if (!bottomSpringExpected.isNaN() && abs(x0 - bottomSpringExpected) > 0.01) {
            bottomSpringDeadline = now() + (BottomSpring.timeout * 1e9).toLong()
        }
        val omega = bottomSpringOmega
        // Arrivals only ever move content forward: if the end now lies behind
        // the reader (it shrank, or the follower carried them past it), the
        // shrunk end is settled like any other.
        if (springKind == SpringKind.arrival && x0 >= 0) {
            stopBottomSpring()
            pinnedGeometry = currentPinGeometry
            if (x0 > 0.5 * density) settleShrunkEnd()
            return
        }
        val v0 = bottomSpringVelocity
        val decay = exp(-omega * dt)
        var x = (x0 + (v0 + omega * x0) * dt) * decay
        var v = (v0 - omega * (v0 + omega * x0) * dt) * decay
        // Arrivals travel no faster than `arrivalSpeed`, so a tall row glides in
        // rather than leaping, and never overshoot: content moves only forward.
        val cap = BottomSpring.arrivalSpeed * density * dt
        if ((springKind == SpringKind.arrival || springKind == SpringKind.viewport || springKind == SpringKind.settle) &&
            dt > 0 && abs(x - x0) > cap
        ) {
            x = x0 + (if (x - x0 > 0) 1 else -1) * cap
            v = (x - x0) / dt
        }
        // Never overshoot: content does not pass the end and come back.
        if (x0 < 0 && x > 0 || x0 > 0 && x < 0) {
            x = 0.0
            v = 0.0
        }
        // The offset lands on whole pixels, so a smaller step is rounded away and
        // the tail would stall short of the end until the timeout: move at least
        // a pixel, and land once within one.
        val pixel = 1.0
        if (abs(x - x0) < pixel && dt > 0) {
            if (abs(x0) <= pixel) {
                x = 0.0
                v = 0.0
            } else {
                x = x0 + (if (x0 < 0) pixel else -pixel)
                v = (x - x0) / dt
            }
        }
        bottomSpringVelocity = v
        // Within a pixel, the last step lands exactly on the end: stopping short
        // would leave a stray pixel for the next pin to snap.
        if (abs(x) <= pixel && abs(v) < 20 * density || now() > bottomSpringDeadline) {
            stopBottomSpring()
            landAtEnd()
            pinnedGeometry = currentPinGeometry
            return
        }
        moveOffset(x0, x)
        bottomSpringExpected = x.toFloat()
    }

    private fun stopBottomSpring() {
        springActive = false
    }

    /// What the follower is moving for: content arriving (gentle and capped),
    /// the composer growing under the reader, or the reader's own jump.
    private enum class SpringKind(val rawValue: String) {
        arrival("glide"),
        viewport("viewport"),
        /// Idle, the reader settles back down onto an end that moved up.
        settle("settle"),
        keyboard("keyboard"),
        jump("jump"),
    }

    private data class PinGeometry(val height: Int, val bottomInset: Float)

    private val currentPinGeometry: PinGeometry
        // Held space only holds the reader's place; it is not the viewport.
        get() = PinGeometry(listState.layoutInfo.viewportSize.height, composerPart)

    private val composerPart: Float get() = max(0f, if (lastAppliedComposerPart.isNaN()) composerInset() else lastAppliedComposerPart)

    /// How far the list could still scroll toward its end, in pixels; nil when
    /// the last row isn't laid out.
    private fun remainingScroll(): Float? {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        if (last.index != info.totalItemsCount - 1) return null
        return max(0, last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
    }

    /// UIKit's `contentOffset.y - upperBound`: negative above the end, positive
    /// past it (space the follower holds beyond the end).
    private fun offsetPastEnd(): Float {
        val remaining = remainingScroll() ?: estimatedRemaining()
        return motionSpace - remaining
    }

    private fun estimatedRemaining(): Float {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        var below = (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
        for (index in last.index + 1 until items.size) below += estimatedSize(items[index].key)
        return max(0f, below)
    }

    /// Every offset change the list makes itself: forward through the list's
    /// remaining scroll, past the end through the follower's held space.
    private fun moveOffset(from: Double, to: Double) {
        isMovingOffset = true
        try {
            val transition = scrollEdgeBridge.sendTransition
            if (to > 0) {
                setMotionSpace(to.toFloat())
            } else {
                if (motionSpace > 0f) setMotionSpace(0f)
                val delta = (to - min(from, 0.0)).toFloat()
                if (delta != 0f) {
                    transition.listMoved(delta)
                    listState.dispatchRawDelta(delta)
                }
            }
        } finally {
            isMovingOffset = false
        }
    }

    /// Lands on the list's end now: the measured tail sits above the composer.
    private fun landAtEnd() {
        if (motionSpace > 0f) setMotionSpace(0f)
        val remaining = remainingScroll()
        if (remaining != null && listState.layoutInfo.totalItemsCount > 0) {
            if (remaining > 0f) {
                isMovingOffset = true
                scrollEdgeBridge.sendTransition.listMoved(remaining)
                listState.dispatchRawDelta(remaining)
                isMovingOffset = false
            }
            return
        }
        if (items.isEmpty()) return
        listState.requestScrollToItem(items.lastIndex, LandingOffset)
    }

    private fun setMotionSpace(space: Float) {
        if (space == motionSpace) return
        motionSpace = space
        invalidatePadding()
    }

    private fun invalidatePadding() {
        paddingVersion.intValue += 1
    }

    /// The bottom padding: the composer's obstruction plus held space. Read
    /// during measure, so the composer shrinking (a pill leaving, the keyboard
    /// leaving under a browsing reader) is held in the same pass that would
    /// otherwise clamp the reader down.
    private fun writeBottomInset(): Float {
        paddingVersion.intValue
        val composer = max(0f, composerInset())
        val previous = lastAppliedComposerPart
        lastAppliedComposerPart = composer
        if (!previous.isNaN() && hasLaidOutContent) {
            val moved = composer - previous
            if (moved < -0.5f * density) {
                applyComposerShrink(-moved)
            } else if (moved > 0.5f * density) {
                scheduleReconcile()
            }
        }
        return composer + trailingSpace + motionSpace
    }

    private fun applyComposerShrink(amount: Float) {
        val follows = behavior.mode == TranscriptScrollBehavior.Mode.following && !isUserScrolling
        if (follows) {
            // The keyboard leaving is ridden down with it; a pill leaving holds
            // the reader until the end settles (or fills, while a turn runs).
            if (keyboardIsMoving()) return
            if ((remainingScroll() ?: Float.MAX_VALUE) > amount) return
            motionSpace += amount
        } else {
            // A composer that shrinks under a browsing reader (the keyboard
            // dismissing as a drag begins) must not clamp the view.
            trailingSpace += amount
        }
        scheduleReconcile()
    }

    private val isUserScrolling: Boolean get() = isDragging || listState.isScrollInProgress

    private val viewportVisibleHeight: Float
        get() = listState.layoutInfo.viewportSize.height - topInset - composerPart

    /// The reader's place in points, as the behavior measures it.
    private fun viewport(): TranscriptScrollBehavior.Viewport {
        val info = listState.layoutInfo
        val offsetY = estimatedOffset()
        val last = info.visibleItemsInfo.lastOrNull()
        val below = when {
            last == null -> 0f
            last.index == info.totalItemsCount - 1 -> (last.offset + last.size).toFloat()
            else -> (last.offset + last.size).toFloat() + (last.index + 1 until items.size).sumOf { estimatedSize(items[it].key).toDouble() }.toFloat()
        }
        return TranscriptScrollBehavior.Viewport(
            offsetY = offsetY / density,
            contentHeight = (offsetY + below) / density,
            visibleHeight = viewportVisibleHeight / density,
            trailingSpace = trailingSpace / density,
            canRequestEarlierHistory = configuration.hasEarlierItems &&
                !configuration.isLoadingEarlier &&
                configuration.earlierHistoryError == null,
            isUserScrolling = isUserScrolling,
        )
    }

    private fun estimatedOffset(): Float {
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0f
        var above = 0f
        for (index in 0 until min(first.index, items.size)) above += estimatedSize(items[index].key)
        return above - first.offset
    }

    private fun estimatedContentHeight(): Float {
        var total = 0f
        for (item in items) total += estimatedSize(item.key)
        return total
    }

    private fun estimatedSize(key: String): Float {
        itemSizes[key]?.let { return it.toFloat() }
        if (itemSizes.isEmpty()) return 120f * density
        return itemSizes.values.sum().toFloat() / itemSizes.size
    }

    private fun recordItemSizes() {
        for (item in listState.layoutInfo.visibleItemsInfo) itemSizes[item.key as String] = item.size
    }

    private fun offsetSignature(): Long {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return 0L
        return first.index.toLong() * 1_000_000_000L - first.offset
    }

    private fun layoutMetricsChanged(viewport: TranscriptScrollBehavior.Viewport): Boolean {
        val previous = lastLayoutViewport ?: return true
        return previous.contentHeight != viewport.contentHeight ||
            previous.visibleHeight != viewport.visibleHeight ||
            previous.trailingSpace != viewport.trailingSpace
    }

    /// Updates may race ahead of the list's first non-zero layout. Only a real
    /// layout with a message row laid out can enable compensation and
    /// scrolling; that moment is also the session's first content frame.
    private fun establishContentLayoutIfNeeded(): Boolean {
        val info = listState.layoutInfo
        if (hasLaidOutContent || info.viewportSize.width <= 0 || info.viewportSize.height <= 0) return false
        val laidOut = info.visibleItemsInfo.any { visible ->
            val key = visible.key as? String ?: return@any false
            key.startsWith("message:") && messageRowsByStableID[key.removePrefix("message:")] is MessageRow.entry
        }
        if (!laidOut) return false
        hasLaidOutContent = true
        arrivalsGlideAfter = now() + (BottomSpring.openingSettle * 1e9).toLong()
        LabMarks.mark("session_first_frame", configuration.sessionID)
        LabMarks.mark("session_layout_done", configuration.sessionID)
        scope?.launch { store.send(SessionFeature.Action.firstContentFrameRendered) }
        return true
    }

    private fun reportLayoutChanged() {
        val viewport = viewport()
        lastLayoutViewport = viewport
        handleBehavior(TranscriptScrollBehavior.Event.layoutChanged(viewport))
    }

    private fun notifyBrowsingChange(previousMode: TranscriptScrollBehavior.Mode) {
        val wasBrowsing = previousMode == TranscriptScrollBehavior.Mode.browsing
        val isBrowsing = behavior.mode == TranscriptScrollBehavior.Mode.browsing
        if (wasBrowsing == isBrowsing) return
        store.send(SessionFeature.Action.transcriptBrowsingChanged(isBrowsing))
    }

    private fun handleBehavior(event: TranscriptScrollBehavior.Event) {
        val previousMode = behavior.mode
        execute(behavior.handle(event))
        notifyBrowsingChange(previousMode)
    }

    /// Frame-level lab instrumentation, gated because a frame loop and
    /// per-frame bookkeeping do not belong in normal runs. It measures what
    /// the reader sees: the on-screen movement of the first visible row per
    /// frame. Following, content may only move up; browsing, it may not move
    /// at all. The worst cases are published as the list's state description.
    internal var scrollTrace: TranscriptScrollTrace? =
        if (BuildConfig.DEBUG && TranscriptScrollTrace.isEnabled) TranscriptScrollTrace(this) else null

    internal val traceProbe: TranscriptScrollTrace.Probe
        get() {
            val info = listState.layoutInfo
            val anchor = info.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("message:") == true }
            return TranscriptScrollTrace.Probe(
                anchorID = anchor?.key as? String,
                minY = { key -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toFloat() },
                isFollowing = behavior.mode == TranscriptScrollBehavior.Mode.following,
                trailingSpace = trailingSpace / density,
                isExempt = reduceMotion || !hasLaidOutContent || now() < arrivalsGlideAfter || keyboardIsMoving() ||
                    springActive && (springKind == SpringKind.jump || springKind == SpringKind.keyboard),
                isUserScrolling = isUserScrolling,
                density = density,
            )
        }

    internal fun frameNow(): Long = now()

    companion object {
        /// Far enough past any row that the list clamps back onto its end.
        private const val LandingOffset = 1_000_000

        /// Lays the list out at its end for the first frame: a session opens
        /// on its bottom, landing at once.
        fun initialState(itemCount: Int): LazyListState =
            LazyListState(firstVisibleItemIndex = max(0, itemCount - 1), firstVisibleItemScrollOffset = if (itemCount > 0) LandingOffset else 0)
    }
}

/// A row opening or closing: critically damped, so nothing passes its
/// place and settles back.
private object DisclosureMotion {
    /// Closing is quicker than opening.
    fun duration(opening: Boolean): Double = if (opening) 0.3 else 0.24
}

private object BottomSpring {
    /// Jump to latest, the keyboard, and pills above the composer: about 0.4 s.
    const val jumpOmega = 18.0
    /// Rows arriving at the bottom: about 0.2 s. A stream is ridden about a
    /// line behind its end; softer, it trails by several and feels slow.
    const val arrivalOmega = 40.0
    /// Arrivals move at most 18 pt per 60 Hz frame.
    const val arrivalSpeed = 1_080.0
    /// Arrivals within this many screens glide; farther, they land at once.
    const val arrivalReach = 2.5f
    /// Lands on the bottom if the target stops moving and the spring has not.
    const val timeout = 1.2
    const val openingSettle = 0.75
}

/// A new row at the end of the transcript fades and rises a few points into
/// place. Presentation-only layer changes: the row's layout never changes,
/// so composition, measurement, and the follower are unaffected.
internal object RowEntrance {
    const val duration = 0.25
    const val rise = 6
    /// Larger insertions are a refresh or a page, not arrivals.
    const val maximumRows = 4
    /// How long a row inserted off screen may still fade in when it appears.
    const val window = 500_000_000L
    val easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

@Composable
private fun Modifier.rowEntrance(reduceMotion: Boolean): Modifier {
    if (reduceMotion) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween((RowEntrance.duration * 1_000).toInt(), easing = RowEntrance.easing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * RowEntrance.rise.dp.toPx()
    }
}

/// Frame-level lab instrumentation. It is intentionally gated because a frame
/// loop and per-frame bookkeeping do not belong in normal runs.
///
/// It measures what the reader sees: the on-screen movement of the first
/// visible row, per 60 Hz frame. Its frame callback is registered before any
/// motion, so it samples each frame before motion steps. Following, content
/// may only move up; browsing, it may not move at all. Motion the reader
/// starts (dragging, jump to latest), the session opening, the keyboard, and
/// Reduce Motion are exempt. The worst cases are published as the list's
/// state description.
internal class TranscriptScrollTrace(private val controller: TranscriptListViewController) {
    data class Probe(
        val anchorID: String?,
        /// Where a row is now, so a row followed from the previous frame is
        /// measured even after another becomes the first visible one.
        val minY: (String) -> Float?,
        val isFollowing: Boolean,
        val trailingSpace: Float,
        val isExempt: Boolean,
        val isUserScrolling: Boolean,
        val density: Float,
    )

    private var job: Job? = null
    private var lastTimestamp = 0L
    private var lastUserDriven = Long.MIN_VALUE / 2
    private var lastAnchor: Pair<String, Float>? = null
    private var frame = 0
    var maxFollowStep = 0f
        private set
    var maxBackward = 0f
        private set
    var maxBrowseDrift = 0f
        private set
    private val published = androidx.compose.runtime.mutableStateOf("")

    val modifier: Modifier
        get() = Modifier.semantics { stateDescription = published.value }

    val summary: String
        get() = "follow %.1f; back %.1f; drift %.1f".format(maxFollowStep, maxBackward, maxBrowseDrift)

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            while (true) withFrameNanos(tick)
        }
        Log.i("ScrollTrace", "started")
    }

    fun stop() {
        val running = job ?: return
        running.cancel()
        job = null
        Log.i("ScrollTrace", "stopped $summary")
    }

    private val tick: (Long) -> Unit = { frameTime ->
        frame += 1
        val probe = controller.traceProbe
        if (probe.isUserScrolling) lastUserDriven = frameTime
        // A fling settles a frame after its scroll reports it has ended.
        val userDriven = frameTime - lastUserDriven < 100_000_000L
        val frames = if (lastTimestamp > 0) max(1.0, (frameTime - lastTimestamp) / 16_666_667.0) else 1.0
        lastTimestamp = frameTime
        val anchorID = probe.anchorID
        val minY = anchorID?.let(probe.minY)
        if (anchorID != null && minY != null) {
            val previous = lastAnchor
            val lastMinY = previous?.let { probe.minY(it.first) }
            if (previous != null && lastMinY != null) {
                val visual = (lastMinY - previous.second) / probe.density
                if (!userDriven && !probe.isExempt) {
                    if (probe.isFollowing) {
                        maxFollowStep = max(maxFollowStep, (abs(visual) / frames).toFloat())
                        if (visual > 0.5f) maxBackward = max(maxBackward, visual)
                    } else {
                        maxBrowseDrift = max(maxBrowseDrift, abs(visual))
                    }
                }
            }
            lastAnchor = anchorID to minY
        } else {
            lastAnchor = null
        }
        val value = "Trailing space: ${probe.trailingSpace.toInt()} pt; $summary"
        if (value != published.value) {
            published.value = value
            latest = value
        }
    }

    companion object {
        /// The lab's `-lab-scroll-trace`, set by the debug launcher.
        @Volatile
        var isEnabled = false

        /// The last published worst cases, for the lab's log.
        @Volatile
        var latest = ""
    }
}
