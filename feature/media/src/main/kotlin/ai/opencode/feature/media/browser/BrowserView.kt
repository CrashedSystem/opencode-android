package ai.opencode.feature.media

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.scene.ActiveScene
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.server.OpenCodePairingLink
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import ai.opencode.core.ui.SFSymbol
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.UUID
import kotlin.math.roundToInt

/** Port of iOS `BrowserMetrics` (Android: a top app bar instead of iOS's floating back button). */
object BrowserMetrics {
    /** The controls' touch target (M3's 48 dp). */
    val controlSize = 48.dp
    /** The top app bar's height (M3 small top app bar), under the status bar. */
    val headerClearance = 64.dp
    /** The Ask composer's minimum height. */
    val barHeight = 52.dp
}

/**
 * The page pushed over the session (D-40, Push In). Android lays it out like a Chrome Custom
 * Tab rather than iOS's Safari-style chrome: a top app bar (close, the page's title and host, an
 * overflow menu with Back, Forward, Share and Open in Browser) over the web view, and Ask as an
 * extended FAB at the bottom. Ask raises a composer beside it; the question goes to the session
 * with the page attached, and a capsule shows the reply's progress. Close, or the stack's back
 * gesture (which steps back through the page's history first: see [browserBackAction]), returns
 * to the session. Port of iOS `BrowserPage`: the session
 * passes its scoped browser store and how the reply to the last question is going.
 *
 * @param replyStatus the reply capsule's state (iOS `SessionFeature.State.browserReplyStatus`);
 *   null hides it.
 * @param workingIndicator the session's `WorkingIndicator`, shown in the capsule while the
 *   agent works on the question (`needsInput`: a request waiting on you).
 */
@Composable
fun BrowserPage(
    store: StoreOf<BrowserFeature.State, BrowserFeature.Action>,
    replyStatus: BrowserReplyStatus?,
    workingIndicator: @Composable (needsInput: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var bottomHeight by remember { mutableStateOf(BrowserMetrics.barHeight) }
    val live by store.observe { it.snapshot }
    // The visit as it was last open: closing clears the store at once, while the page still
    // slides off the stack showing it.
    var closingSnapshot by remember { mutableStateOf(store.state.snapshot) }
    LaunchedEffect(live) {
        val snapshot = live
        if (snapshot != null) closingSnapshot = snapshot
    }
    val page = BrowserFeature.State.Snapshot.displayed(live = live, closing = closingSnapshot)
    val isAsking = page?.isAsking == true

    fun close() {
        if (!store.state.isOpen) return
        focusManager.clearFocus()
        store.send(BrowserFeature.Action.CloseTapped)
    }

    // The safe area (the keyboard's too, as iOS's bottom safe area is while it shows).
    val safeArea = WindowInsets.safeDrawing.asPaddingValues()
    val safeTop = safeArea.calculateTopPadding()
    val canvas = OpenCodeTheme.colors.canvas
    Box(
        modifier
            .fillMaxSize()
            .background(canvas)
            .onPreviewKeyEvent { event ->
                // iOS's ⌘[ on the back button.
                val back = event.type == KeyEventType.KeyDown && event.key == Key.LeftBracket && (event.isMetaPressed || event.isCtrlPressed)
                if (back) close()
                back
            }
            .testTag("browser"),
    ) {
        if (page != null) {
            // Edge to edge; the top app bar above and Ask below are the page's obscured
            // insets, so it scrolls under them and fixed elements' first/last pixels clear them.
            BrowserWebViewHost(
                pageID = page.pageID,
                url = page.openedURL,
                insets = BrowserInsets(
                    top = (safeTop + BrowserMetrics.headerClearance).value,
                    left = safeArea.calculateLeftPadding(LayoutDirection.Ltr).value,
                    bottom = (safeArea.calculateBottomPadding() + bottomHeight + 8.dp).value,
                    right = safeArea.calculateRightPadding(LayoutDirection.Ltr).value,
                ),
                onEvent = { store.send(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        TopBar(store, page, onClose = ::close)
        // Ask and the reply rise with the keyboard; the page stays put beneath.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onSizeChanged { bottomHeight = with(density) { it.height.toDp() } }
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomStart) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = replyStatus != null && !isAsking,
                    enter = appear(reduceMotion),
                    exit = disappear(reduceMotion),
                ) {
                    if (replyStatus != null) ReplyCapsule(replyStatus, ::close, workingIndicator)
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = isAsking,
                    enter = appear(reduceMotion),
                    exit = disappear(reduceMotion),
                ) {
                    if (page != null) AskComposer(store, page, onSend = { focusManager.clearFocus() })
                }
            }
            AskButton(isAsking) {
                store.send(if (isAsking) BrowserFeature.Action.AskDismissed else BrowserFeature.Action.AskTapped)
            }
        }
    }
}

/**
 * What the stack's back gesture does on this page: Ask goes away first, then the page's own
 * history, then the page leaves (iOS: the escape action puts Ask away, the web view's back swipe
 * steps its history, and the stack's swipe pops).
 */
fun browserBackAction(browser: BrowserFeature.State): BrowserFeature.Action = when {
    browser.isAsking -> BrowserFeature.Action.AskDismissed
    browser.canGoBack -> BrowserFeature.Action.BackTapped
    else -> BrowserFeature.Action.CloseTapped
}

/**
 * The page's place in the session's stack: stable for one visit, and never a detail's identity
 * (iOS `BrowserPage.stackID`: the UUID's first eight bytes with bit 62 set).
 */
fun browserStackID(pageID: UUID): Long = pageID.mostSignificantBits or (1L shl 62)

/**
 * iOS `appear`: Ask and the reply capsule rise from the bottom as panels (panel open) and fold
 * away on panel close; under Reduce Motion, the reduced cross-fade.
 */
private fun appear(reduceMotion: Boolean): EnterTransition {
    if (reduceMotion) return fadeIn(MotionRole.PanelOpen.animation(reduceMotion = true))
    val role = MotionRole.PanelOpen
    return slideInVertically(role.animation(visibilityThreshold = IntOffset.VisibilityThreshold)) { it } + fadeIn(role.animation())
}

private fun disappear(reduceMotion: Boolean): ExitTransition {
    if (reduceMotion) return fadeOut(MotionRole.PanelOpen.animation(MotionPhase.Exit, reduceMotion = true))
    val role = MotionRole.PanelOpen
    return slideOutVertically(role.animation(MotionPhase.Exit, visibilityThreshold = IntOffset.VisibilityThreshold)) { it } +
        fadeOut(role.animation(MotionPhase.Exit))
}

/**
 * The Custom Tab bar (Android in place of iOS's floating back button and bottom bar): close,
 * the page's title and host, and an overflow menu with the page's Back, Forward, Share and Open
 * in Browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    store: StoreOf<BrowserFeature.State, BrowserFeature.Action>,
    page: BrowserFeature.State.Snapshot?,
    onClose: () -> Unit,
) {
    TopAppBar(
        title = { Address(page, onTap = { store.send(BrowserFeature.Action.AddressTapped) }, Modifier.fillMaxWidth()) },
        navigationIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag("browser-pop")) {
                OpenCodeIcon(SFSymbol.Xmark, contentDescription = "Back to Session")
            }
        },
        actions = {
            if (page != null) PageMenu(store, page)
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun PageMenu(store: StoreOf<BrowserFeature.State, BrowserFeature.Action>, page: BrowserFeature.State.Snapshot) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("browser-menu")) {
            OpenCodeIcon(SFSymbol.Ellipsis, contentDescription = "More Options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuItem(SFSymbol.ChevronBackward, "Back", "browser-back", enabled = page.canGoBack) {
                expanded = false
                store.send(BrowserFeature.Action.BackTapped)
            }
            MenuItem(SFSymbol.ArrowRight, "Forward", "browser-forward", enabled = page.canGoForward) {
                expanded = false
                store.send(BrowserFeature.Action.ForwardTapped)
            }
            // iOS `ShareLink`: the system's share sheet.
            MenuItem(SFSymbol.SquareAndArrowUp, "Share", "browser-share", enabled = true) {
                expanded = false
                dependency(LinkShareSheet).present(page.url)
            }
            MenuItem(SFSymbol.ArrowUpForwardApp, "Open in Browser", "browser-safari", enabled = true) {
                expanded = false
                store.send(BrowserFeature.Action.SafariTapped)
            }
        }
    }
}

@Composable
private fun MenuItem(symbol: SFSymbol, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { OpenCodeIcon(symbol, contentDescription = null) },
        enabled = enabled,
        modifier = Modifier.testTag(tag),
    )
}

/** Ask as an extended FAB; while asking it folds to a Cancel FAB beside the composer. */
@Composable
private fun AskButton(isAsking: Boolean, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        text = { Text("Ask") },
        icon = {
            // iOS `.contentTransition(.symbolEffect(.replace))`.
            Crossfade(isAsking, animationSpec = tween((Motion.fadeStandard * 1000).toInt()), label = "browser-ask") { asking ->
                OpenCodeIcon(if (asking) SFSymbol.Xmark else SFSymbol.TextBubble, contentDescription = null)
            }
        },
        onClick = onClick,
        expanded = !isAsking,
        modifier = Modifier
            .semantics { contentDescription = if (isAsking) "Cancel" else "Ask About This Page" }
            .testTag("browser-ask"),
    )
}

/** The page's title over its host; a tap scrolls to the top. */
@Composable
private fun Address(page: BrowserFeature.State.Snapshot?, onTap: () -> Unit, modifier: Modifier) {
    val title = page?.displayTitle ?: ""
    val url = page?.url
    Column(
        modifier
            .heightIn(min = BrowserMetrics.controlSize)
            .clickable(onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = url ?: ""
                role = Role.Button
                onClick(label = "Scroll to the top of the page") {
                    onTap()
                    true
                }
            }
            .testTag("browser-address"),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            val secondary = OpenCodeTheme.colors.secondaryText
            if (page?.isLoading == true) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = secondary)
            } else if (url?.startsWith("https:", ignoreCase = true) == true) {
                OpenCodeIcon("lock.fill", contentDescription = null, size = 11.dp, tint = secondary)
            }
            Text(page?.host ?: "", style = OpenCodeTextStyles.caption1, color = secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

@Composable
private fun AskComposer(
    store: StoreOf<BrowserFeature.State, BrowserFeature.Action>,
    page: BrowserFeature.State.Snapshot,
    onSend: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val canSend = page.canSendAsk
    fun send() {
        if (!store.state.canSendAsk) return
        onSend()
        store.send(BrowserFeature.Action.AskSent)
    }
    Surface(
        shape = RoundedCornerShape(BrowserMetrics.barHeight / 2),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().heightIn(min = BrowserMetrics.barHeight),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            TextField(
                value = page.askDraft,
                onValueChange = { text ->
                    // A multi-line field's Send key types a newline: that's Send.
                    if (text == page.askDraft + "\n") {
                        send()
                    } else if (store.state.isOpen) {
                        store.send(BrowserFeature.Action.AskDraftChanged(text))
                    }
                },
                placeholder = { Text("Ask about this page") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .testTag("browser-ask-field"),
            )
            val colors = OpenCodeTheme.colors
            Box(
                Modifier
                    .padding(end = 2.dp, bottom = 4.dp)
                    .clickable(enabled = canSend, role = Role.Button, onClick = ::send)
                    .openCodeTapTarget(BrowserMetrics.controlSize)
                    .semantics { contentDescription = "Send" }
                    .testTag("browser-ask-send"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        // iOS `Color.primary` / `Color.secondary.opacity(0.4)` under `systemBackground`.
                        .background(
                            if (canSend) colors.text else colors.secondaryText.copy(alpha = colors.secondaryText.alpha * 0.4f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon("arrow.up", contentDescription = null, tint = colors.canvas)
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (store.state.isOpen) focus.requestFocus() }
}

/**
 * What the agent is doing with the question asked here; once its reply lands, a tap goes back
 * to the session to read it.
 */
@Composable
private fun ReplyCapsule(
    status: BrowserReplyStatus,
    close: () -> Unit,
    workingIndicator: @Composable (needsInput: Boolean) -> Unit,
) {
    val goesBack = status.goesBack
    val label = when (status) {
        BrowserReplyStatus.Ready -> "Reply ready"
        BrowserReplyStatus.NeedsInput -> "Needs your input"
        BrowserReplyStatus.Working -> "Working"
    }
    Surface(
        shape = OpenCodeShape.capsule,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .heightIn(min = BrowserMetrics.controlSize)
            .then(if (goesBack) Modifier.clickable(role = Role.Button, onClickLabel = "Go back to the session", onClick = close) else Modifier)
            .clearAndSetSemantics {
                contentDescription = label
                if (goesBack) {
                    role = Role.Button
                    onClick(label = "Go back to the session") { close(); true }
                }
            }
            .testTag("browser-reply"),
    ) {
        Box(Modifier.padding(horizontal = 14.dp).heightIn(min = BrowserMetrics.controlSize), contentAlignment = Alignment.CenterStart) {
            if (status != BrowserReplyStatus.Ready) {
                // A request waiting on you (a permission, a question) is answered in the session.
                workingIndicator(status == BrowserReplyStatus.NeedsInput)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val secondary = OpenCodeTheme.colors.secondaryText
                    OpenCodeIcon("text.bubble", contentDescription = null, tint = secondary, size = 17.dp)
                    Text("Reply ready", style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    Text("· Back to read it", style = OpenCodeTextStyles.subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Hosts the page's [android.webkit.WebView] from [BrowserWebViews], which outlives this view,
 * so a rebuilt page keeps its history and scroll position. Also shows the hold menu for links
 * held in the page. Port of iOS `BrowserWebViewHost`.
 */
@Composable
fun BrowserWebViewHost(
    pageID: UUID,
    url: String,
    /** The top app bar above and Ask below; the page scrolls under them, clear at either end. */
    insets: BrowserInsets,
    onEvent: (BrowserFeature.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    class Coordinator(var pageID: UUID) {
        /** The window the page was last seen in, so a move to another is sent once. */
        var sceneID: String? = null
    }
    val coordinator = remember { Coordinator(pageID) }
    val isDark = OpenCodeTheme.colors.isDark
    var held by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    val pages = BrowserWebViews.shared

    // Made once per page, on first show and when the page changes: a page discarded later (the
    // page closing) isn't made again while the view slides away (iOS mounts in `makeUIView` and
    // on a page change only).
    fun mount(container: FrameLayout) {
        val webView = pages.webView(pageID, url, isDark)
        if (webView.parent !== container) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            container.removeAllViews()
            container.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        pages.resume(pageID)
    }

    // On every update: the page's handlers, appearance and insets, and the window it's in (iOS
    // `bindCallbacks`: `noteScene`, and `.sceneChanged` when the page moves to another window).
    fun bind(container: FrameLayout) {
        pages.setHostContext(pageID, container.context)
        // The app's appearance, live (iOS: the web view follows its trait collection).
        pages.setAppearance(pageID, isDark)
        pages.setObscuredInsets(pageID, insets)
        pages.setEventHandler(pageID, onEvent)
        pages.setLinkHeldHandler(pageID) { link, x, y -> held = Triple(link, x, y) }
        val sceneID = ActiveScene.sceneID(container.context)
        pages.noteScene(pageID, sceneID)
        if (sceneID != null && sceneID != coordinator.sceneID) {
            coordinator.sceneID = sceneID
            // After this update, as iOS's `Task { @MainActor in … }`.
            container.post { onEvent(BrowserFeature.Action.SceneChanged(sceneID)) }
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    clipChildren = true
                    mount(this)
                }
            },
            update = { container ->
                if (coordinator.pageID != pageID) {
                    pages.setEventHandler(coordinator.pageID, null)
                    pages.setLinkHeldHandler(coordinator.pageID, null)
                    coordinator.pageID = pageID
                    coordinator.sceneID = null
                    mount(container)
                }
                bind(container)
            },
            // Off screen, the page plays nothing and keeps nothing of the screen.
            onRelease = { container ->
                container.removeAllViews()
                pages.pauseMedia(coordinator.pageID)
                pages.setEventHandler(coordinator.pageID, null)
                pages.setLinkHeldHandler(coordinator.pageID, null)
                pages.setHostContext(coordinator.pageID, null)
            },
            modifier = Modifier.fillMaxSize().testTag("browser-page"),
        )
        held?.let { (link, x, y) ->
            val previewURL = OpenCodePairingLink.shareableURL(link) ?: link
            Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(1.dp)) {
                LinkMenuPopup(
                    url = previewURL,
                    actions = pages.linkMenuActions(link),
                    expanded = true,
                    onAction = { action ->
                        held = null
                        pages.performLinkMenu(pageID, action, link)
                    },
                    onOpen = {
                        held = null
                        pages.commitLinkPreview(pageID, link)
                    },
                    onDismiss = { held = null },
                )
            }
        }
    }
}
