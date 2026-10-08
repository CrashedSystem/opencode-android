@file:PortedFrom("OpenCode/BackgroundWorkPanel.swift", "339260d", partial = "all but the -lab-glass-trace overlay and stage log")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.observe
import ai.opencode.mobilecode.tca.presented
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The background-activity capsule's bounds, so the panel can grow out of it.
@Stable
class BackgroundActivityAnchor {
    var bounds by mutableStateOf<Rect?>(null)

    /// The panel's glass is standing in for the capsule.
    var capsuleHidden by mutableStateOf(false)
}

val LocalBackgroundActivityAnchor = staticCompositionLocalOf<BackgroundActivityAnchor?> { null }

/// Marks the capsule the background-work panel anchors to.
@Composable
fun Modifier.backgroundActivityAnchor(): Modifier {
    val anchor = LocalBackgroundActivityAnchor.current ?: return this
    return onGloballyPositioned { anchor.bounds = it.boundsInRoot() }
}

/// What the background-work glass is. One glass shape is the capsule and
/// then the panel: its frame and corners spring from one to the other, so
/// the capsule itself becomes the panel and shrinks back into it.
enum class BackgroundWorkStage {
    /// The capsule in the composer pill row draws its own glass.
    closed,

    /// The shared glass sits exactly over the capsule, standing in for it.
    capsule,
    panel,
}

object BackgroundWorkMotion {
    // SwiftUI's spring(duration: 0.42, bounce: 0.18): stiffness (2π / 0.42)².
    fun open(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) tween(200) else spring(dampingRatio = 0.82f, stiffness = 224f)

    // SwiftUI's spring(duration: 0.36, bounce: 0.08).
    fun close(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) fade else spring(dampingRatio = 0.92f, stiffness = 305f)

    /// Under Reduce Motion, or with no capsule left to land in.
    val fade: AnimationSpec<Float> = tween(200)

    const val capsuleInset = 5f
    const val panelRadius = 28f
}

/// The background-work capsule in the composer pill row. It steps aside
/// while the shared glass stands in for it.
@Composable
fun BackgroundWorkCapsuleLabel(summary: String, activity: AgentActivitySource) {
    val hidden = LocalBackgroundActivityAnchor.current?.capsuleHidden == true
    Box(Modifier.alpha(if (hidden) 0f else 1f)) {
        ComposerPillLabel(title = summary) {
            AgentActivityGlyph(source = activity)
        }
    }
}

/// The background-work panel above the composer and the shell output sheet.
@Composable
fun BackgroundWork(
    store: Store<SessionFeature.State, SessionFeature.Action>,
    isPresented: Binding<Boolean>,
) {
    val reducedMotion = LocalReduceMotion.current
    val anchor = LocalBackgroundActivityAnchor.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(BackgroundWorkStage.closed) }
    var contentHeight by remember { mutableStateOf<Float?>(null) }
    var retained by remember { mutableStateOf(Retained()) }
    /// The session whose work the glass shows.
    var glassSession by remember { mutableStateOf<String?>(null) }
    /// 0 the capsule's frame, 1 the panel's.
    val morph = remember { Animatable(0f) }
    var origin by remember { mutableStateOf(Rect.Zero) }
    val hasWork = store.observe { hasBackgroundWork }

    fun open() {
        glassSession = store.state.session?.id
        if (reducedMotion || !hasWork || stage != BackgroundWorkStage.closed) {
            stage = BackgroundWorkStage.panel
            scope.launch { morph.animateTo(1f, BackgroundWorkMotion.open(reducedMotion)) }
            return
        }
        // The glass takes the capsule's place without animating, then grows into
        // the panel on the next frame, from where the capsule is.
        stage = BackgroundWorkStage.capsule
        scope.launch {
            morph.snapTo(0f)
            withFrameNanos {}
            if (!isPresented.wrappedValue || stage != BackgroundWorkStage.capsule) return@launch
            stage = BackgroundWorkStage.panel
            morph.animateTo(1f, BackgroundWorkMotion.open(reduceMotion = false))
        }
    }

    /// The drawer swaps sessions in place: nothing of the last session's
    /// glass, mid-morph or measured, carries over to the next.
    fun reset() {
        isPresented.wrappedValue = false
        stage = BackgroundWorkStage.closed
        contentHeight = null
        scope.launch { morph.snapTo(0f) }
        retained = Retained()
        glassSession = null
    }

    /// The panel shrinks back into the capsule's shape, then hands the glass
    /// back to the capsule. Without a capsule to land in, it fades.
    fun close() {
        if (stage == BackgroundWorkStage.closed) return
        // Opening a subagent swaps the session before the close begins.
        if (store.state.session?.id != glassSession) return reset()
        if (reducedMotion || !hasWork) {
            stage = BackgroundWorkStage.closed
            scope.launch { morph.snapTo(0f) }
            return
        }
        if (BuildConfig.DEBUG) LabMarks.mark("background_work_close")
        stage = BackgroundWorkStage.capsule
        scope.launch {
            morph.animateTo(0f, BackgroundWorkMotion.close(reduceMotion = false))
            if (isPresented.wrappedValue || stage != BackgroundWorkStage.capsule) return@launch
            stage = BackgroundWorkStage.closed
        }
    }

    val sessionID = store.state.session?.id
    var shownSessionID by remember { mutableStateOf(sessionID) }
    if (sessionID != shownSessionID) {
        shownSessionID = sessionID
        reset()
    }
    // A call takes the composer's place; its transcript covers the pills.
    val inCall = store.observe { voice != null }
    LaunchedEffect(inCall) {
        if (inCall) isPresented.wrappedValue = false
    }
    val showsCallTranscript = store.observe { voice?.showsCaptions == true }
    LaunchedEffect(showsCallTranscript) {
        if (showsCallTranscript) isPresented.wrappedValue = false
    }
    val presented = isPresented.wrappedValue
    LaunchedEffect(presented) {
        if (presented) open() else close()
    }
    LaunchedEffect(stage) {
        anchor?.capsuleHidden = stage != BackgroundWorkStage.closed
    }
    LaunchedEffect(hasWork) {
        if (hasWork) return@LaunchedEffect
        // Open, the binding's change closes it; mid-close, close fades it.
        if (isPresented.wrappedValue) {
            isPresented.wrappedValue = false
        } else {
            close()
        }
    }

    /// The capsule's frame, or while the glass is up, where it last was.
    val live = anchor?.bounds?.takeIf { hasWork }
    if (live != null) retained.capsule = live
    val capsule = live ?: (if (stage == BackgroundWorkStage.closed) null else retained.capsule)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot() },
    ) {
        if (capsule != null) {
            BackgroundWorkSurface(
                store = store,
                stage = stage,
                morph = morph.value,
                capsule = with(density) {
                    Rect(
                        (capsule.left - origin.left).toDp().value,
                        (capsule.top - origin.top).toDp().value,
                        (capsule.right - origin.left).toDp().value,
                        (capsule.bottom - origin.top).toDp().value,
                    )
                },
                width = maxWidth.value,
                safeTop = with(density) { WindowInsets.statusBars.getTop(density).toDp().value },
                contentHeight = contentHeight,
                onContentHeight = { contentHeight = it },
                isPresented = isPresented,
                retained = retained,
                showsCallTranscript = showsCallTranscript,
            )
        }
    }
    store.presented(SessionFeature.State::shellOutput, SessionFeature.Action.shellOutput) { shellStore ->
        ShellOutputView(shellStore)
    }
}

@Composable
private fun BackgroundWorkSurface(
    store: Store<SessionFeature.State, SessionFeature.Action>,
    stage: BackgroundWorkStage,
    morph: Float,
    /// In the surface's points, like every frame here.
    capsule: Rect,
    width: Float,
    safeTop: Float,
    contentHeight: Float?,
    onContentHeight: (Float) -> Unit,
    isPresented: Binding<Boolean>,
    retained: Retained,
    showsCallTranscript: Boolean,
) {
    val reducedMotion = LocalReduceMotion.current
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    /// The rows the work has, or once it has ended, the rows it last had.
    val liveAgents = store.observe { activeSubagents }
    val liveShells = store.observe { backgroundShells }
    val hasWork = liveAgents.isNotEmpty() || liveShells.isNotEmpty()
    if (hasWork) {
        retained.agents = liveAgents
        retained.shells = liveShells
    }
    val agents = if (hasWork || stage == BackgroundWorkStage.closed) liveAgents else retained.agents
    val shells = if (hasWork || stage == BackgroundWorkStage.closed) liveShells else retained.shells
    val pill = Rect(capsule.left, capsule.top + BackgroundWorkMotion.capsuleInset, capsule.right, capsule.bottom - BackgroundWorkMotion.capsuleInset)
    val maxHeight = max(160f, capsule.top - safeTop - 72)
    val height = min(contentHeight ?: BackgroundWorkPanel.estimatedHeight(agents, shells), maxHeight)
    val panel = Rect(20f, capsule.top - 10 - height, 20f + max(0f, width - 40), capsule.top - 10)
    val isPanel = stage == BackgroundWorkStage.panel
    val rect = if (reducedMotion) (if (stage == BackgroundWorkStage.closed) pill else panel) else lerp(pill, panel, morph)
    val radius = if (reducedMotion) BackgroundWorkMotion.panelRadius else androidx.compose.ui.util.lerp(pill.height / 2, BackgroundWorkMotion.panelRadius, morph)
    val shape = RoundedCornerShape(max(0f, radius).dp)
    val drag = remember { Animatable(0f) }
    // A list long enough to scroll keeps its drags for scrolling.
    val scrolls = (contentHeight ?: 0f) > maxHeight
    // Rows arrive once the glass has mostly grown, and leave first.
    val rowsAlpha by animateFloatAsState(
        if (isPanel) 1f else 0f,
        if (isPanel) tween(240, delayMillis = if (reducedMotion) 0 else 100) else tween(100),
        label = "rows",
    )
    val capsuleAlpha by animateFloatAsState(
        if (isPanel) 0f else 1f,
        if (isPanel) tween(100) else tween(200, delayMillis = if (reducedMotion) 0 else 120),
        label = "capsule",
    )
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(isPanel, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (dark) 0.32f else 0.1f))
                    .clickable(interactionSource = null, indication = null) { isPresented.wrappedValue = false }
                    .semantics { hideFromAccessibility() },
            )
        }
        AnimatedVisibility(
            stage != BackgroundWorkStage.closed,
            enter = if (reducedMotion) fadeIn(tween(200)) else EnterTransition.None,
            // Landed in the capsule, the glass is the capsule's; with nothing
            // to land in, it fades where it is.
            exit = if (reducedMotion || !hasWork) fadeOut(tween(200)) else ExitTransition.None,
        ) {
            Box(
                Modifier
                    .offset { IntOffset(with(density) { rect.left.dp.roundToPx() }, with(density) { rect.top.dp.roundToPx() } + if (isPanel) drag.value.roundToInt() else 0) }
                    .size(rect.width.coerceAtLeast(0f).dp, rect.height.coerceAtLeast(0f).dp)
                    .openCodeGlass(shape)
                    .clip(shape)
                    // Like the pills it grows from, it hides under the call's
                    // transcript. Only the glass: on the full-screen surface this
                    // would cover the session for accessibility hit-testing.
                    .hiddenUnderVoiceTranscript(showsCallTranscript)
                    .then(
                        if (!isPanel || scrolls) {
                            Modifier
                        } else {
                            Modifier.pointerInput(Unit) {
                                val tracker = VelocityTracker()
                                var pull = 0f
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        pull = 0f
                                        tracker.resetTracking()
                                    },
                                    onVerticalDrag = { change, amount ->
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        pull += amount / density.density
                                        // Downward follows the finger; upward resists.
                                        val shown = if (pull > 0) pull else -sqrt(-pull)
                                        scope.launch { drag.snapTo(shown * density.density) }
                                    },
                                    onDragEnd = {
                                        val predicted = pull + tracker.calculateVelocity().y / density.density * 0.25f
                                        if (pull > 56 || predicted > 140) isPresented.wrappedValue = false
                                        scope.launch {
                                            if (reducedMotion) drag.snapTo(0f) else drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 439f))
                                        }
                                    },
                                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                                )
                            }
                        },
                    )
                    .then(
                        if (isPanel) {
                            Modifier.semantics {
                                paneTitle = "Background work"
                                testTag = "background-work-panel"
                                customActions = listOf(CustomAccessibilityAction("Close") { isPresented.wrappedValue = false; true })
                            }
                        } else {
                            Modifier.clearAndSetSemantics {}
                        },
                    ),
                contentAlignment = Alignment.BottomStart,
            ) {
                Box(
                    Modifier
                        .requiredSize(panel.width.dp, panel.height.dp)
                        .alpha(rowsAlpha),
                ) {
                    BackgroundWorkPanel(
                        agents = agents,
                        waitingSessionIDs = store.state.waitingSessionIDs,
                        shells = shells,
                        onContentHeight = onContentHeight,
                        onOpenSubagent = { id ->
                            isPresented.wrappedValue = false
                            store.send(SessionFeature.Action.familyPicked(id))
                        },
                        onOpenShell = { id ->
                            isPresented.wrappedValue = false
                            store.send(SessionFeature.Action.shellOutputTapped(id))
                        },
                        enabled = isPanel,
                    )
                }
                Box(
                    Modifier
                        .requiredSize(pill.width.dp, pill.height.dp)
                        // One layer, so its text fades with it.
                        .graphicsLayer { alpha = capsuleAlpha }
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.CenterStart,
                ) {
                    ComposerPillContent(title = BackgroundWorkPanel.summary(agents, shells)) {
                        AgentActivityGlyph(source = AgentActivitySource.summary(agents = agents, shells = shells, waiting = store.state.waitingSessionIDs))
                    }
                }
            }
        }
    }
}

/// What the glass last showed. When the work ends, the capsule and its
/// rows leave the session at once; the glass finishes closing over them.
class Retained {
    var capsule: Rect? = null
    var agents: List<OpenCodeSession> = emptyList()
    var shells: List<OpenCodeShell> = emptyList()
}

private fun lerp(from: Rect, to: Rect, fraction: Float) = Rect(
    androidx.compose.ui.util.lerp(from.left, to.left, fraction),
    androidx.compose.ui.util.lerp(from.top, to.top, fraction),
    androidx.compose.ui.util.lerp(from.right, to.right, fraction),
    androidx.compose.ui.util.lerp(from.bottom, to.bottom, fraction),
)

/// Running subagents and background shells, full width so titles never wrap.
/// The presentation supplies its glass and frame.
object BackgroundWorkPanel {
    /// “1 subagent · 2 shells”, as the capsule reads.
    fun summary(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>): String = listOfNotNull(
        if (agents.isEmpty()) null else if (agents.size == 1) "1 subagent" else "${agents.size} subagents",
        if (shells.isEmpty()) null else if (shells.size == 1) "1 shell" else "${shells.size} shells",
    ).joinToString(" · ")

    /// Before the first measurement: rows, headers, and padding.
    fun estimatedHeight(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>): Float =
        (agents.size + shells.size) * 48f + (if (agents.isEmpty()) 0f else 30f) + (if (shells.isEmpty()) 0f else 30f) + 20f
}

@Composable
fun BackgroundWorkPanel(
    agents: List<OpenCodeSession>,
    waitingSessionIDs: Set<String> = emptySet(),
    shells: List<OpenCodeShell>,
    onContentHeight: (Float) -> Unit,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { onContentHeight(it.height / density.density) }
                .padding(vertical = 10.dp),
        ) {
            if (agents.isNotEmpty()) {
                SectionHeader("Subagents")
                for (agent in agents) {
                    BackgroundWorkRow(
                        title = agent.title,
                        accessibilityHint = "Opens the subagent’s session",
                        onTap = { onOpenSubagent(agent.id) },
                        enabled = enabled,
                        modifier = Modifier.semantics { testTag = "background-subagent-${agent.id}" },
                    ) {
                        AgentActivityGlyph(
                            source = AgentActivitySource.subagent(
                                SubagentActivity(sessionID = agent.id, phase = SubagentActivity.Phase.running, needsInput = waitingSessionIDs.contains(agent.id)),
                            ),
                        )
                    }
                }
            }
            if (agents.isNotEmpty() && shells.isNotEmpty()) {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { HairlineRule() }
            }
            if (shells.isNotEmpty()) {
                SectionHeader("Shells")
                for (shell in shells) {
                    BackgroundWorkRow(
                        title = shell.title,
                        isCommand = true,
                        startedAt = shell.startedAt,
                        accessibilityHint = "Shows the shell’s output",
                        onTap = { onOpenShell(shell.id) },
                        enabled = enabled,
                        modifier = Modifier.semantics { testTag = "background-shell-${shell.id}" },
                    ) {
                        SystemImage("terminal", null, Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.secondaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 6.dp, bottom = 2.dp)
            .semantics { heading() },
        style = TextStyles.footnote.semibold,
        color = SystemColors.secondaryLabel,
    )
}

@Composable
private fun BackgroundWorkRow(
    title: String,
    accessibilityHint: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isCommand: Boolean = false,
    startedAt: Long? = null,
    leading: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = title },
        contentAlignment = Alignment.CenterStart,
    ) {
        // A pressed row lifts a soft highlight inset from the panel's edges.
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = 8.dp)
                .background(SystemColors.label.copy(alpha = if (pressed) 0.08f else 0f), RoundedCornerShape(16.dp)),
        )
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A concrete color: the glyph's Canvas can't resolve hierarchical styles.
            Box(Modifier.width(20.dp).semantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides SystemColors.secondaryLabel) { leading() }
            }
            Text(
                title,
                Modifier.weight(1f),
                style = if (isCommand) TranscriptMetrics.codeFont else TextStyles.body,
                color = SystemColors.label,
                maxLines = 1,
                overflow = if (isCommand) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            )
            if (startedAt != null) {
                CompositionLocalProvider(LocalContentColor provides SystemColors.secondaryLabel) {
                    ShellElapsedTime(since = startedAt)
                }
            }
            SystemImage("chevron.right", null, Modifier.symbolSize(TextStyles.footnote.semibold), tint = SystemColors.tertiaryLabel)
        }
    }
}

/// Running time as a clock (`0:42`, `12:03`, `1:02:03`), ticking each second.
@Composable
fun ShellElapsedTime(since: Long, until: Long? = null, modifier: Modifier = Modifier) {
    if (until != null) {
        ElapsedLabel(since, until, modifier)
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000 - (now - since).mod(1_000L))
        }
    }
    ElapsedLabel(since, now, modifier)
}

@Composable
private fun ElapsedLabel(since: Long, at: Long, modifier: Modifier) {
    val seconds = maxOf(0, ((at - since) / 1_000).toInt())
    Text(
        elapsedClock(seconds),
        modifier.semantics { contentDescription = elapsedAccessibility(seconds) },
        style = TextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
        color = LocalContentColor.current,
    )
}

internal fun elapsedClock(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    if (hours > 0) return "%d:%02d:%02d".format(hours, minutes, rest)
    return "%d:%02d".format(minutes, rest)
}

private fun elapsedAccessibility(seconds: Int): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return listOfNotNull(
        if (hours > 0) "$hours ${if (hours == 1) "hour" else "hours"}" else null,
        if (minutes > 0) "$minutes ${if (minutes == 1) "minute" else "minutes"}" else null,
        "$rest ${if (rest == 1) "second" else "seconds"}",
    ).joinToString(", ")
}
