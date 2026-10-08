@file:PortedFrom("OpenCode/HomeView.swift", "494e433")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassBackdrop
import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemConfirmation
import ai.opencode.mobilecode.system.SystemContextMenu
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenu
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.bold
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.binding
import ai.opencode.mobilecode.tca.presented
import ai.opencode.mobilecode.tca.sheet
import ai.opencode.mobilecode.tca.task
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.launch

@Composable
fun HomeView(store: Store<HomeFeature.State, HomeFeature.Action>, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReduceMotion.current
    var isSessionDrawerPresented by rememberSaveable { mutableStateOf(false) }
    var liftedPinID by remember { mutableStateOf<String?>(null) }
    val catchUpProxy = remember { CatchUpStageProxy() }
    val catchUpPreviews = remember { CatchUpPreviewStores() }
    var drawerSearchFocused by remember { mutableStateOf(false) }
    val drawerPresentation = remember { Binding({ isSessionDrawerPresented }, { isSessionDrawerPresented = it }) }
    val drawerController = remember { SessionDrawerViewController(isPresented = isSessionDrawerPresented) }
    // `withAnimation(reducedMotion ? nil : …)`: the drawer settles at once
    // under Reduce Motion, so every change here may animate.
    val home = HomeActions(store, closeSessionDrawer = { isSessionDrawerPresented = false })

    store.task { store.send(HomeFeature.Action.task) }
    traceUploads(store.scope(HomeFeature.State::traces, HomeFeature.Action.traces)) {
        WhatsNewSheet(store.scope(HomeFeature.State::whatsNew, HomeFeature.Action.whatsNew)) {
            // The window behind the card's rounded corners.
            BoxWithConstraints(modifier.fillMaxSize().background(SystemColors.systemBackground)) {
                val drawerWidth = maxWidth * 0.75f
                val sessionStore = store.scope(HomeFeature.State::sessionDetail, HomeFeature.Action.sessionDetail.presented)
                if (sessionStore != null) {
                    val catchUp = store.state.catchUp
                    SessionDrawerContainer(
                        isPresented = drawerPresentation,
                        width = drawerWidth,
                        reducedMotion = reducedMotion,
                        content = {
                            SessionView(
                                store = sessionStore,
                                onOpenSessions = { isSessionDrawerPresented = true },
                                onCatchUpDone = if (catchUp == null) null else { { catchUpProxy.dismissCard(HomeFeature.CatchUpDisposition.markRead) } },
                                onCatchUpOpen = if (catchUp == null) null else { { store.send(HomeFeature.Action.catchUpOpenTapped) } },
                                listContext = openSessionContext(store.state),
                            )
                        },
                        drawer = {
                            SessionDrawer(
                                store = store,
                                home = home,
                                width = drawerWidth,
                                searchFocused = drawerSearchFocused,
                                onSearchFocusChanged = { drawerSearchFocused = it },
                                liftedPinID = liftedPinID,
                                onLifted = { liftedPinID = it },
                            )
                        },
                        chrome = { CatchUpChrome(store, catchUpProxy) },
                        preview = { CatchUpPreview(store, catchUpPreviews, sessionID = it, locked = drawerController.stack.isPreviewLocked) },
                        catchUp = catchUpStage(store),
                        catchUpProxy = catchUpProxy,
                        onSettled = { store.send(HomeFeature.Action.sessionDrawerSettled(isOpen = it)) },
                        controller = drawerController,
                    )
                }
            }
        }
    }
    val settingsPresented = store.state.activeSheet == HomeFeature.State.Sheet.settings
    sheet(
        isPresented = Binding(
            { settingsPresented },
            { if (!it) store.send(HomeFeature.Action.binding(BindingAction.set(HomeFeature.State::activeSheet, null))) },
        ),
    ) {
        SessionSettingsView(
            serverHost = store.state.server.host,
            connectionStatus = store.state.connectionStatus,
            connectionErrorMessage = store.state.connectionErrorMessage,
            onChangeServer = { store.send(HomeFeature.Action.serverBadgeTapped) },
            onRetryConnection = { store.send(HomeFeature.Action.retryConnectionTapped) },
            whatsNew = store.scope(HomeFeature.State::whatsNew, HomeFeature.Action.whatsNew),
        )
    }
    store.state.pendingDeletion?.let { pending ->
        SystemConfirmation(
            onDismissRequest = { store.send(HomeFeature.Action.deleteSessionCancelled) },
            title = "Delete “" + pending.title + "”?",
            text = "This removes the session from the server for all clients.",
            confirm = "Delete",
            onConfirm = { store.send(HomeFeature.Action.deleteSessionConfirmed) },
        )
    }
    ScenePhaseChanges { phase ->
        DiagnosticsRecorder.shared.recordMark("app_$phase", sessionID = null)
        if (phase == "active") {
            store.send(HomeFeature.Action.retryConnectionTapped)
        } else {
            store.send(HomeFeature.Action.transcriptCacheFlushRequested)
        }
    }
}

/// HomeView's methods that touch the drawer and the store together.
private class HomeActions(
    val store: Store<HomeFeature.State, HomeFeature.Action>,
    val closeSessionDrawer: () -> Unit,
) {
    val selectedSessionID: String? get() = store.state.sessionDetail?.session?.id

    fun selectSession(sessionID: String) {
        if (selectedSessionID != sessionID) store.send(HomeFeature.Action.sessionTapped(sessionID))
        closeSessionDrawer()
    }

    fun toggleRead(session: OpenCodeSession, unread: Boolean) {
        store.send(if (unread) HomeFeature.Action.markRead(session.id) else HomeFeature.Action.markUnread(session.id))
    }

    fun openSettings() {
        closeSessionDrawer()
        store.send(HomeFeature.Action.settingsTapped)
    }

    fun movePin(id: String, to: String) {
        val target = to
        val pins = store.state.pinnedSessions
        val from = pins.indexOfFirst { it.id == id }
        val destination = pins.indexOfFirst { it.id == target }
        if (from < 0 || destination < 0 || from == destination) return
        store.send(HomeFeature.Action.pinsMoved(fromOffsets = setOf(from), toOffset = if (destination > from) destination + 1 else destination))
    }
}

/// `@Environment(\.scenePhase)`'s changes, from the hosting activity's lifecycle.
@Composable
private fun ScenePhaseChanges(onChange: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnChange by rememberUpdatedState(onChange)
    DisposableEffect(lifecycle) {
        fun phase(state: Lifecycle.State) = when {
            state.isAtLeast(Lifecycle.State.RESUMED) -> "active"
            state.isAtLeast(Lifecycle.State.STARTED) -> "inactive"
            else -> "background"
        }
        var last = phase(lifecycle.currentState)
        val observer = LifecycleEventObserver { _, _ ->
            val next = phase(lifecycle.currentState)
            if (next != last) {
                last = next
                currentOnChange(next)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private fun openSessionContext(state: HomeFeature.State): SessionListContext {
    val session = state.sessionDetail?.session
    val listed = session?.let { open -> state.sessions.firstOrNull { it.id == open.id } }
    return SessionListContext(
        project = SessionListContext.project(session, state.projects),
        isPinned = listed?.let { state.isPinned(it.id) },
        isUnread = listed?.let { state.isUnread(it) } ?: false,
        elsewhere = drawerBadge(state, excluding = session?.id),
    )
}

private fun drawerBadge(state: HomeFeature.State, excluding: String?): DrawerBadge? {
    val openID = excluding
    var badge: DrawerBadge? = null
    for (session in state.sessions) {
        if (session.id == openID) continue
        when (state.indicator(session)) {
            HomeFeature.State.Indicator.attention -> return DrawerBadge.attention
            HomeFeature.State.Indicator.unread -> badge = DrawerBadge.unread
            HomeFeature.State.Indicator.running, HomeFeature.State.Indicator.none -> Unit
        }
    }
    return badge
}

private fun catchUpStage(store: Store<HomeFeature.State, HomeFeature.Action>): CatchUpStage? {
    val catchUp = store.state.catchUp ?: return null
    return CatchUpStage(
        queue = catchUp.queue,
        undoDirection = catchUp.handled.lastOrNull()?.disposition,
        onDismiss = { disposition ->
            val sessionID = store.state.catchUp?.queue?.firstOrNull()
            if (sessionID != null) store.send(HomeFeature.Action.catchUpCardDismissed(sessionID = sessionID, disposition = disposition))
        },
        onUndo = { store.send(HomeFeature.Action.catchUpUndoTapped) },
        onExit = { store.send(HomeFeature.Action.catchUpExitTapped) },
    )
}

/// The real session screen for the card behind the open one, rendered
/// from its cached transcript by an inert store.
@Composable
private fun CatchUpPreview(
    store: Store<HomeFeature.State, HomeFeature.Action>,
    previews: CatchUpPreviewStores,
    sessionID: String?,
    locked: Boolean,
) {
    val session = sessionID?.let { id -> store.state.sessions.firstOrNull { it.id == id } }
    if (session == null) {
        Box(Modifier.fillMaxSize().background(SystemColors.systemBackground))
        return
    }
    val previewStore = previews.store(session, store.state, locked = locked)
    androidx.compose.runtime.key(previewStore) {
        Box(Modifier.semantics { hideFromAccessibility() }) {
            SessionView(store = previewStore, onCatchUpDone = {}, onCatchUpOpen = {})
        }
    }
}

@Composable
private fun CatchUpChrome(store: Store<HomeFeature.State, HomeFeature.Action>, proxy: CatchUpStageProxy) {
    val catchUp = store.state.catchUp ?: return
    CatchUpChrome(
        remaining = catchUp.queue.size,
        canUndo = catchUp.handled.isNotEmpty(),
        onExit = { store.send(HomeFeature.Action.catchUpExitTapped) },
        onUndo = { proxy.undoCard() },
        onDismiss = { proxy.dismissCard(it) },
    )
}

@Composable
private fun SessionDrawer(
    store: Store<HomeFeature.State, HomeFeature.Action>,
    home: HomeActions,
    width: Dp,
    searchFocused: Boolean,
    onSearchFocusChanged: (Boolean) -> Unit,
    liftedPinID: String?,
    onLifted: (String?) -> Unit,
) {
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val reducedMotion = LocalReduceMotion.current
    // Where each pin's slot is, read only while a pin is dragged.
    val pinSlots = remember { HashMap<String, LayoutCoordinates>() }
    val state = store.state
    val pinnedSessions = state.pinnedSessions
    val recentSessions = state.recentSessions
    // Follows the system appearance: white in light mode, black in dark.
    GlassBackdrop(
        modifier = Modifier.width(width).fillMaxHeight().background(SystemColors.systemBackground),
        content = {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .drawerTouchRegion(DrawerTouchRegion.list)
                    .testTag("session-drawer-list"),
                contentPadding = PaddingValues(
                    top = insets.calculateTopPadding() + 12.dp,
                    // The drawer fills the screen, so reserve the floating controls
                    // and the navigation bar explicitly.
                    bottom = 50.dp + 24.dp + insets.calculateBottomPadding(),
                ),
            ) {
                item(key = "title") {
                    DrawerRow(top = 0.dp, bottom = 4.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Sessions", Modifier.semantics { heading() }, style = TextStyles.title2.bold, color = SystemColors.label)
                            Spacer(Modifier.weight(1f))
                            // A reconnect in progress keeps last-known rows and usually settles
                            // in a second; only a confirmed outage earns a badge. Settings still
                            // shows the in-between state.
                            if (state.connectionStatus == HomeFeature.State.ConnectionStatus.disconnected) {
                                OfflineBadge { store.send(HomeFeature.Action.retryConnectionTapped) }
                            }
                        }
                    }
                }
                item(key = "search") {
                    DrawerRow(top = 4.dp, bottom = 4.dp) {
                        DrawerSearchField(
                            search = store.binding(HomeFeature.State::search, HomeFeature.Action::binding),
                            onFocusChanged = onSearchFocusChanged,
                        )
                    }
                }
                if (state.search.isEmpty()) {
                    val catchUpCount = state.catchUpSessions.size
                    val showsCatchUp = catchUpCount > 0
                    if (showsCatchUp) {
                        item(key = "catch-up") {
                            DrawerRow(top = 4.dp, bottom = 0.dp) {
                                CatchUpRow(
                                    count = catchUpCount,
                                    canMarkAllRead = state.catchUpSessions.any { state.indicator(it) == HomeFeature.State.Indicator.unread },
                                    onTap = {
                                        home.closeSessionDrawer()
                                        store.send(HomeFeature.Action.catchUpTapped)
                                    },
                                    onMarkAllRead = { store.send(HomeFeature.Action.catchUpMarkAllReadTapped) },
                                )
                            }
                        }
                    }
                    if (state.whatsNew.showsButton) {
                        item(key = "whats-new") {
                            DrawerRow(top = if (showsCatchUp) 0.dp else 4.dp, bottom = 0.dp) {
                                WhatsNewButton(
                                    action = {
                                        home.closeSessionDrawer()
                                        store.send(HomeFeature.Action.whatsNew(WhatsNewFeature.Action.buttonTapped))
                                    },
                                    onHide = { store.send(HomeFeature.Action.whatsNew(WhatsNewFeature.Action.hideTapped)) },
                                )
                            }
                        }
                    }
                }
                state.deletionError?.let { deletionError ->
                    item(key = "deletion-error") {
                        DrawerRow(top = 0.dp, bottom = 0.dp) {
                            Row(
                                Modifier.heightIn(min = 44.dp).testTag("drawer-deletion-error"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SystemImage("exclamationmark.triangle", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.footnote), tint = SystemColors.systemRed)
                                Text(deletionError, style = TextStyles.footnote, color = SystemColors.systemRed)
                            }
                        }
                    }
                }
                if (pinnedSessions.isNotEmpty()) {
                    item(key = "pinned-header") { DrawerSectionHeader("Pinned") }
                    pinnedSessions.forEach { session ->
                        item(key = "pinned-${session.id}") {
                            PinnedRow(
                                store = store,
                                home = home,
                                session = session,
                                isLifted = liftedPinID == session.id,
                                onLifted = onLifted,
                                pinSlots = pinSlots,
                                reducedMotion = reducedMotion,
                            )
                        }
                    }
                }
                if (recentSessions.isNotEmpty()) {
                    item(key = "recents-header") { DrawerSectionHeader("Recents") }
                    recentSessions.forEach { session ->
                        item(key = "recent-${session.id}") {
                            DrawerSessionRow(store, home, session, isPinnedSection = false, modifier = Modifier.animateItem())
                        }
                    }
                } else if (pinnedSessions.isEmpty() && (state.search.isNotEmpty() || state.hasConnected)) {
                    item(key = "empty") {
                        DrawerRow(top = 24.dp, bottom = 0.dp) {
                            DrawerEmptyState(search = state.search)
                        }
                    }
                }
            }
        },
        overlay = {
            DrawerControls(
                modifier = Modifier.align(Alignment.BottomStart),
                width = width,
                bottomInset = insets.calculateBottomPadding(),
                isDisconnected = state.connectionStatus == HomeFeature.State.ConnectionStatus.disconnected,
                searchFocused = searchFocused,
                reducedMotion = reducedMotion,
                onNewSession = {
                    home.closeSessionDrawer()
                    store.send(HomeFeature.Action.newSessionTapped)
                },
                onSettings = home::openSettings,
            )
        },
    )
}

/// A plain `List` row: `.listRowInsets(top:, leading: 16, bottom:, trailing: 16)`
/// at the 44 pt default minimum row height, centered like a cell's content.
@Composable
private fun DrawerRow(
    top: Dp,
    bottom: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp + top + bottom)
            .drawerTouchRegion(DrawerTouchRegion.row)
            .padding(start = DrawerMetrics.rowEdge, end = DrawerMetrics.rowEdge, top = top, bottom = bottom),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/// A plain list's section header: the list itself under the finger, not a row.
@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        title,
        Modifier
            .fillMaxWidth()
            .padding(start = DrawerMetrics.rowEdge, end = DrawerMetrics.rowEdge, top = 32.dp, bottom = 9.dp)
            .semantics { heading() },
        style = TextStyles.headline,
        color = SystemColors.secondaryLabel,
    )
}

@Composable
private fun OfflineBadge(retry: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = retry)
            .semantics {
                contentDescription = "Offline"
                stateDescription = "Retries the connection"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .heightIn(min = 28.dp)
                .background(SystemColors.label.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemImage("wifi.slash", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.footnote), tint = SystemColors.secondaryLabel)
            Text("Offline", style = TextStyles.footnote.medium, color = SystemColors.secondaryLabel)
        }
    }
}

@Composable
private fun DrawerSearchField(search: Binding<String>, onFocusChanged: (Boolean) -> Unit) {
    val text = search.wrappedValue
    BasicTextField(
        value = text,
        onValueChange = { search.wrappedValue = it },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(SystemColors.label.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .semantics { contentDescription = "Search sessions" },
        singleLine = true,
        textStyle = TextStyles.body.copy(color = SystemColors.label),
        cursorBrush = SolidColor(SystemColors.tint),
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("Search", style = TextStyles.body, color = SystemColors.placeholderText)
                field()
            }
        },
    )
}

/// Waiting sessions stay in Catch Up after Mark All as Read, so the menu
/// is offered only while something is plainly unread.
@Composable
private fun CatchUpRow(
    count: Int,
    canMarkAllRead: Boolean,
    onTap: () -> Unit,
    onMarkAllRead: () -> Unit,
) {
    val label = @Composable {
        DrawerShortcutLabel(
            title = "Catch Up",
            systemImage = "rectangle.stack",
            // The drawer’s own background gives the menu’s lifted preview a surface.
            modifier = Modifier.background(SystemColors.systemBackground, RoundedCornerShape(12.dp)),
        ) {
            Text(
                "$count",
                style = TextStyles.body.copy(fontFeatureSettings = "tnum"),
                color = SystemColors.secondaryLabel,
                maxLines = 1,
            )
        }
    }
    val semantics = Modifier
        .semantics {
            contentDescription = "Catch Up"
            stateDescription = if (count == 1) "1 session" else "$count sessions"
            if (canMarkAllRead) {
                customActions = listOf(CustomAccessibilityAction("Mark All as Read") { onMarkAllRead(); true })
            }
        }
        .testTag("drawer-catch-up")
    if (canMarkAllRead) {
        SystemContextMenu(
            sections = listOf(SystemMenuSection(listOf(SystemMenuItem("Mark All as Read", systemImage = "envelope.open", onClick = onMarkAllRead)))),
            modifier = semantics,
            onClick = onTap,
        ) {
            label()
        }
    } else {
        Box(semantics.clickable(role = Role.Button, onClick = onTap)) { label() }
    }
}

@Composable
private fun DrawerControls(
    modifier: Modifier,
    width: Dp,
    bottomInset: Dp,
    isDisconnected: Boolean,
    searchFocused: Boolean,
    reducedMotion: Boolean,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
) {
    // This list avoids the keyboard, but that would also move its overlay.
    // Fade the controls instead of covering pinned rows while search owns
    // the keyboard; the list keeps its normal keyboard inset.
    val opacity by animateFloatAsState(
        targetValue = if (searchFocused) 0f else 1f,
        animationSpec = if (reducedMotion) tween(0) else tween(150, easing = CatchUpStackController.EaseOut),
        label = "drawer-controls",
    )
    Row(
        modifier
            .width(width)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 12.dp)
            .graphicsLayer { alpha = opacity }
            .then(if (searchFocused) Modifier.semantics { hideFromAccessibility() } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .heightIn(min = 50.dp)
                .glassEffect(GlassSurface.Capsule, interactive = true)
                .clickable(enabled = !isDisconnected && !searchFocused, role = Role.Button, onClick = onNewSession)
                .semantics { contentDescription = "New Session" }
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Dim the label, not the glass: a translucent pill lets the list
            // rows scrolling under it read straight through.
            val tint = if (isDisconnected) SystemColors.tertiaryLabel else SystemColors.label
            SystemImage("square.and.pencil", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = tint)
            Text("New Session", style = TextStyles.body.semibold, color = tint)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(50.dp)
                .glassEffect(GlassSurface.Circle, interactive = true)
                .clickable(enabled = !searchFocused, role = Role.Button, onClick = onSettings)
                .semantics { contentDescription = "Settings" },
            contentAlignment = Alignment.Center,
        ) {
            SystemImage("gearshape.fill", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.label)
        }
    }
}

/// `ContentUnavailableView("No Sessions", …)` and `.search(text:)`, until the
/// system layer has one.
@Composable
private fun DrawerEmptyState(search: String) {
    val searching = search.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("drawer-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SystemImage(
            if (searching) "magnifyingglass" else "bubble.left.and.bubble.right",
            contentDescription = null,
            modifier = Modifier.padding(bottom = 6.dp).symbolSize(40),
            tint = SystemColors.secondaryLabel,
        )
        Text(
            if (searching) "No Results for “$search”" else "No Sessions",
            Modifier.semantics { heading() },
            style = TextStyles.title2.bold,
            color = SystemColors.label,
            textAlign = TextAlign.Center,
        )
        Text(
            if (searching) "Check the spelling or try a new search." else "Start one with New Session.",
            style = TextStyles.body,
            color = SystemColors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DrawerSessionRow(
    store: Store<HomeFeature.State, HomeFeature.Action>,
    home: HomeActions,
    session: OpenCodeSession,
    isPinnedSection: Boolean,
    modifier: Modifier = Modifier,
    interaction: Modifier? = null,
) {
    val selected = home.selectedSessionID == session.id
    val indicator = store.state.indicator(session)
    val label = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(if (selected) SystemColors.label.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session.title,
                Modifier.weight(1f).padding(end = 4.dp),
                style = if (selected) TextStyles.body.semibold else TextStyles.body,
                color = SystemColors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SessionIndicator(indicator)
        }
    }
    val rowSemantics = Modifier.semantics {
        stateDescription = listOf(if (isPinnedSection) "Pinned" else "", statusText(indicator)).filter { it.isNotEmpty() }.joinToString(", ")
        this.selected = selected
    }
    DrawerSwipeRow(
        isPinnedSection = isPinnedSection,
        onTogglePin = { store.send(HomeFeature.Action.togglePin(session.id)) },
        onDelete = { store.send(HomeFeature.Action.deleteSessionTapped(session.id)) },
        modifier = modifier
            .drawerTouchRegion(DrawerTouchRegion.row)
            .testTag("drawer-session-row-${session.id}"),
    ) {
        if (interaction != null) {
            Box(interaction.then(rowSemantics)) { label() }
        } else {
            val unread = store.state.isUnread(session)
            SystemContextMenu(
                sections = listOf(
                    SystemMenuSection(
                        listOf(
                            SystemMenuItem("Pin", systemImage = "pin") { store.send(HomeFeature.Action.togglePin(session.id)) },
                            SystemMenuItem(if (unread) "Mark as Read" else "Mark as Unread", systemImage = if (unread) "envelope.open" else "envelope.badge") {
                                home.toggleRead(session, unread)
                            },
                            SystemMenuItem("Delete", systemImage = "trash", destructive = true) {
                                store.send(HomeFeature.Action.deleteSessionTapped(session.id))
                            },
                        ),
                    ),
                ),
                modifier = rowSemantics,
                onClick = { home.selectSession(session.id) },
            ) {
                label()
            }
        }
    }
}

/// `.swipeActions(edge: .leading, allowsFullSwipe: true)` with Pin or Unpin,
/// and `.swipeActions(edge: .trailing)` with Delete, as the platform's swipe
/// box: a swipe past the threshold acts, then the row springs back (Delete
/// still asks first).
@Composable
private fun DrawerSwipeRow(
    isPinnedSection: Boolean,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val currentTogglePin by rememberUpdatedState(onTogglePin)
    val currentDelete by rememberUpdatedState(onDelete)
    SwipeToDismissBox(
        state = state,
        modifier = modifier.padding(horizontal = DrawerMetrics.rowEdge),
        backgroundContent = {
            val leading = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val trailing = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        when {
                            leading -> SystemColors.systemOrange
                            trailing -> SystemColors.systemRed
                            else -> Color.Transparent
                        },
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = if (leading) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (leading) {
                    SystemImage(if (isPinnedSection) "pin.slash" else "pin", contentDescription = if (isPinnedSection) "Unpin" else "Pin", tint = Color.White)
                } else if (trailing) {
                    SystemImage("trash", contentDescription = "Delete", tint = Color.White)
                }
            }
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentTogglePin()
                SwipeToDismissBoxValue.EndToStart -> currentDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { state.reset() }
        },
    ) {
        Box(Modifier.background(SystemColors.systemBackground)) { content() }
    }
}

@Composable
private fun LazyItemScope.PinnedRow(
    store: Store<HomeFeature.State, HomeFeature.Action>,
    home: HomeActions,
    session: OpenCodeSession,
    isLifted: Boolean,
    onLifted: (String?) -> Unit,
    pinSlots: MutableMap<String, LayoutCoordinates>,
    reducedMotion: Boolean,
) {
    val unread = store.state.isUnread(session)
    var menu by remember { mutableStateOf(false) }
    var lift by remember { mutableStateOf(0f) }
    // The slot (untransformed) and the row under the finger (lifted).
    val slot = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val row = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentOnLifted by rememberUpdatedState(onLifted)
    Box(
        Modifier
            .animateItem()
            .zIndex(if (isLifted) 1f else 0f)
            .onGloballyPositioned {
                slot[0] = it
                pinSlots[session.id] = it
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Unpin") { store.send(HomeFeature.Action.togglePin(session.id)); true },
                    CustomAccessibilityAction(if (unread) "Mark as Read" else "Mark as Unread") { home.toggleRead(session, unread); true },
                    CustomAccessibilityAction("Delete") { store.send(HomeFeature.Action.deleteSessionTapped(session.id)); true },
                    CustomAccessibilityAction("Move Up") { store.send(HomeFeature.Action.movePinUp(session.id)); true },
                    CustomAccessibilityAction("Move Down") { store.send(HomeFeature.Action.movePinDown(session.id)); true },
                )
            },
    ) {
        DrawerSessionRow(
            store = store,
            home = home,
            session = session,
            isPinnedSection = true,
            modifier = Modifier.graphicsLayer {
                translationY = if (isLifted) lift else 0f
                shadowElevation = if (isLifted) 8.dp.toPx() else 0f
                shape = RoundedCornerShape(12.dp)
            },
            interaction = Modifier.onGloballyPositioned { row[0] = it }.pinnedRowInteraction(
                sessionID = session.id,
                slotTop = { slot[0]?.positionInRoot()?.y },
                fingerY = { position -> row[0]?.localToRoot(position)?.y },
                select = { home.selectSession(session.id) },
                showMenu = { menu = it },
                move = { target -> home.movePin(session.id, to = target) },
                pinAt = { y ->
                    pinSlots.entries.firstOrNull { (_, coordinates) ->
                        val bounds = coordinates.takeIf { it.isAttached }?.boundsInRoot()
                        bounds != null && y >= bounds.top && y < bounds.bottom
                    }?.key
                },
                lifted = { offset ->
                    lift = offset ?: 0f
                    currentOnLifted(if (offset == null) null else session.id)
                },
            ),
        )
        SystemMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            sections = listOf(
                SystemMenuSection(
                    listOf(
                        SystemMenuItem("Unpin", systemImage = "pin.slash") {
                            menu = false
                            store.send(HomeFeature.Action.togglePin(session.id))
                        },
                        SystemMenuItem(if (unread) "Mark as Read" else "Mark as Unread", systemImage = if (unread) "envelope.open" else "envelope.badge") {
                            menu = false
                            home.toggleRead(session, unread)
                        },
                        SystemMenuItem("Delete", systemImage = "trash", destructive = true) {
                            menu = false
                            store.send(HomeFeature.Action.deleteSessionTapped(session.id))
                        },
                    ),
                ),
            ),
        )
    }
    DisposableEffect(session.id) { onDispose { pinSlots.remove(session.id) } }
}

/// UIKit coordinates a context menu and a drag when both interactions belong
/// to the same view. Here one pointer stream reads all three: a tap selects,
/// a hold opens the menu, and moving after the hold lifts the pin.
///
/// The reorder is live, like a native list: entering another pin moves the
/// pin there at once, and the row lands wherever the finger lifts.
private fun Modifier.pinnedRowInteraction(
    sessionID: String,
    slotTop: () -> Float?,
    fingerY: (Offset) -> Float?,
    select: () -> Unit,
    showMenu: (Boolean) -> Unit,
    move: (String) -> Unit,
    pinAt: (Float) -> String?,
    lifted: (Float?) -> Unit,
): Modifier = pointerInput(sessionID) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        // Released, or claimed by a scroll or a swipe, before the hold.
        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var outcome: Boolean? = null
            while (outcome == null) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                outcome = when {
                    change == null || change.isConsumed || (change.position - down.position).getDistance() > slop -> false
                    !change.pressed -> {
                        change.consume()
                        true
                    }
                    else -> null
                }
            }
            outcome
        }
        if (early == true) select()
        if (early != null) return@awaitEachGesture
        showMenu(true)
        val start = slotTop() ?: return@awaitEachGesture
        val startFinger = fingerY(down.position) ?: return@awaitEachGesture
        var target: String? = sessionID
        var dragging = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            val top = slotTop() ?: break
            val finger = fingerY(change.position) ?: break
            if (!change.pressed) {
                change.consume()
                break
            }
            if (!dragging && kotlin.math.abs(finger - startFinger) > slop) {
                // A drag grew out of the menu.
                dragging = true
                showMenu(false)
            }
            if (!dragging) continue
            change.consume()
            lifted(start + (finger - startFinger) - top)
            // Entering, not every move: the store moves before the list lays
            // out again, so a stale position over the row that just moved
            // would swap it straight back.
            val under = pinAt(finger)
            if (under == sessionID) target = sessionID
            if (under != null && under != target) {
                target = under
                move(under)
            }
        }
        if (dragging) lifted(null)
    }
}

@Composable
private fun SessionIndicator(indicator: HomeFeature.State.Indicator) {
    val reducedMotion = LocalReduceMotion.current
    Box(Modifier.size(18.dp).semantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
        when (indicator) {
            HomeFeature.State.Indicator.running -> ActivityRing()
            HomeFeature.State.Indicator.attention, HomeFeature.State.Indicator.unread, HomeFeature.State.Indicator.none -> {
                val color by animateColorAsState(
                    if (indicator == HomeFeature.State.Indicator.attention) SystemColors.systemOrange else SystemColors.tint,
                    animationSpec = if (reducedMotion) tween(0) else tween(250),
                    label = "indicator-color",
                )
                val opacity by animateFloatAsState(
                    if (indicator == HomeFeature.State.Indicator.none) 0f else 1f,
                    animationSpec = if (reducedMotion) tween(0) else tween(250),
                    label = "indicator-opacity",
                )
                SystemImage("circle.fill", contentDescription = null, modifier = Modifier.alpha(opacity).symbolSize(TextStyles.caption2), tint = color)
            }
        }
    }
}

private fun statusText(indicator: HomeFeature.State.Indicator): String = when (indicator) {
    HomeFeature.State.Indicator.attention -> "Needs your input"
    HomeFeature.State.Indicator.running -> "Working"
    HomeFeature.State.Indicator.unread -> "Unread"
    HomeFeature.State.Indicator.none -> ""
}

/// The stage around the catch-up card: progress and Undo above it, the two
/// dispositions below it. The card itself (the live session) sits on top.
@Composable
private fun CatchUpChrome(
    remaining: Int,
    canUndo: Boolean,
    onExit: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: (HomeFeature.CatchUpDisposition) -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
        Box(Modifier.fillMaxWidth().height(CatchUpMetrics.headerHeight)) {
            Text(
                if (remaining == 0) "All Caught Up" else "$remaining Left",
                Modifier.align(Alignment.Center).semantics { heading() }.testTag("catch-up-remaining"),
                style = TextStyles.headline.copy(fontFeatureSettings = "tnum"),
                color = SystemColors.label,
            )
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable(role = Role.Button, onClick = onExit)
                        .semantics { contentDescription = "Leave Catch Up" },
                    contentAlignment = Alignment.Center,
                ) {
                    SystemImage("chevron.left", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.tint)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .widthIn(min = 44.dp)
                        .heightIn(min = 44.dp)
                        .clickable(enabled = canUndo, role = Role.Button, onClick = onUndo),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Undo", style = TextStyles.body.medium, color = if (canUndo) SystemColors.tint else SystemColors.tertiaryLabel)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        if (remaining == 0) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SystemImage("checkmark.circle", contentDescription = null, modifier = Modifier.symbolSize(48), tint = SystemColors.secondaryLabel)
                Text("You’re all caught up", style = TextStyles.title3.semibold, color = SystemColors.label)
                Box(
                    Modifier
                        .openCodePrimaryButton()
                        .clickable(role = Role.Button, onClick = onExit)
                        .padding(horizontal = 24.dp)
                        .testTag("catch-up-finished-done"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Done", style = TextStyles.body.semibold, color = Color.White)
                }
            }
        }
        Spacer(Modifier.weight(1f))

        Row(
            Modifier
                .fillMaxWidth()
                .height(CatchUpMetrics.footerHeight)
                .padding(horizontal = 16.dp)
                .alpha(if (remaining == 0) 0f else 1f)
                .then(if (remaining == 0) Modifier.semantics { hideFromAccessibility() } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .openCodeSecondaryButton()
                    .clickable(enabled = remaining > 0, role = Role.Button) { onDismiss(HomeFeature.CatchUpDisposition.keepUnread) },
                contentAlignment = Alignment.Center,
            ) {
                Text("Keep Unread", style = TextStyles.body, color = SystemColors.label)
            }
            // `.openCodePrimaryButton().tint(CatchUpMetrics.readControlColor)`
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 50.dp)
                    .glassEffect(GlassSurface.Capsule, interactive = true, tint = CatchUpMetrics.readControlColor)
                    .clickable(enabled = remaining > 0, role = Role.Button) { onDismiss(HomeFeature.CatchUpDisposition.markRead) },
                contentAlignment = Alignment.Center,
            ) {
                Text("Mark as Read", style = TextStyles.body, color = Color.White)
            }
        }
    }
}

/// Search, the shortcut rows, and the session rows share one edge.
object DrawerMetrics {
    val rowEdge = 16.dp
}

/// A drawer shortcut row in the session rows’ style: a tinted symbol, the
/// title, and an accessory in the session indicators’ column.
@Composable
fun DrawerShortcutLabel(
    title: String,
    systemImage: String,
    modifier: Modifier = Modifier,
    accessory: @Composable () -> Unit,
) {
    val symbolWidth = with(LocalDensity.current) { 20.sp.toDp() }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading-aligned so each symbol starts on the session titles’ edge.
        Box(Modifier.width(symbolWidth), contentAlignment = Alignment.CenterStart) {
            SystemImage(systemImage, contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.tint)
        }
        Text(title, style = TextStyles.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Box(Modifier.widthIn(min = 18.dp), contentAlignment = Alignment.Center) {
            accessory()
        }
    }
}

/// The desktop app's subagent progress glyph: a 5×5 dot matrix with a
/// diagonal wave. Reduce Motion shows one still center dot.
@Composable
fun AgentActivityGlyph(modifier: Modifier = Modifier, size: Dp = 15.dp) {
    if (LocalReduceMotion.current) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.3f).background(LocalContentColor.current, CircleShape).semantics { contentDescription = "Running" })
        }
        return
    }
    val time by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "agent-activity",
    )
    val color = LocalContentColor.current
    Canvas(modifier.size(size).semantics { contentDescription = "Running" }) {
        val pitch = this.size.width / 5f
        val dot = pitch * 0.62f
        for (row in 0 until 5) {
            for (column in 0 until 5) {
                val wave = 0.5 + 0.5 * cos(((row + column) / 8.0 - time) * 2 * PI)
                val alpha = (0.16 + 0.84 * wave * wave * wave).toFloat()
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = dot / 2f,
                    center = Offset(column * pitch + pitch / 2f, row * pitch + pitch / 2f),
                )
            }
        }
    }
}

/// ChatGPT/Gemini-style activity ring: an open arc in constant rotation.
/// The rotation is read in the draw phase, so it never relayouts the row.
@Composable
private fun ActivityRing(modifier: Modifier = Modifier, size: Dp = 14.dp, lineWidth: Dp = 1.8.dp) {
    val reducedMotion = LocalReduceMotion.current
    val secondary = SystemColors.secondaryLabel
    val tail = secondary.copy(alpha = if (LocalIncreasedContrast.current) secondary.alpha else 0f)
    val rotation = if (reducedMotion) {
        null
    } else {
        rememberInfiniteTransition().animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
            label = "activity-ring",
        )
    }
    // The round cap at the tail reaches back past angle 0 and samples the
    // gradient's far end; fade that end out again so the tail has no solid
    // dot. The head's cap still gets full color just past 0.72.
    val brush = remember(secondary, tail) {
        Brush.sweepGradient(0f to tail, 0.72f to secondary, 0.78f to secondary, 0.9f to tail)
    }
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation?.value ?: 0f }
            .semantics { contentDescription = "Running" },
    ) {
        val stroke = lineWidth.toPx()
        drawArc(
            brush = brush,
            startAngle = 0f,
            sweepAngle = 0.72f * 360f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
