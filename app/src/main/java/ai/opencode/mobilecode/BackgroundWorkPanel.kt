@file:PortedFrom("OpenCode/BackgroundWorkPanel.swift", "553a407")

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
}

val LocalBackgroundActivityAnchor = staticCompositionLocalOf<BackgroundActivityAnchor?> { null }

/// Marks the capsule the background-work panel anchors to.
@Composable
fun Modifier.backgroundActivityAnchor(): Modifier {
    val anchor = LocalBackgroundActivityAnchor.current ?: return this
    return onGloballyPositioned { anchor.bounds = it.boundsInRoot() }
}

/// The background-work panel above the composer and the shell output sheet.
@Composable
fun BackgroundWork(
    store: Store<SessionFeature.State, SessionFeature.Action>,
    isPresented: Binding<Boolean>,
) {
    val reducedMotion = LocalReduceMotion.current
    val dark = isSystemInDarkTheme()
    val anchor = LocalBackgroundActivityAnchor.current
    val density = LocalDensity.current
    val safeTop = WindowInsets.statusBars.getTop(density)
    var origin by remember { mutableStateOf(Rect.Zero) }
    val capsule = anchor?.bounds
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot() },
    ) {
        val presented = isPresented.wrappedValue && capsule != null
        val height = with(density) { maxHeight.toPx() }
        AnimatedVisibility(
            visible = presented,
            enter = fadeIn(tween(if (reducedMotion) 200 else 300)),
            exit = fadeOut(tween(if (reducedMotion) 200 else 250)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (dark) 0.32f else 0.1f))
                    .clickable(interactionSource = null, indication = null) { isPresented.wrappedValue = false }
                    .semantics { hideFromAccessibility() },
            )
        }
        val agents = store.observe { activeSubagents }
        val shells = store.observe { backgroundShells }
        val top = (capsule?.top ?: height) - origin.top
        val spring = spring<Float>(dampingRatio = 0.84f, stiffness = 273f)
        AnimatedVisibility(
            visible = presented,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = with(density) { (max(0f, height - top) + 10.dp.toPx()).toDp() }),
            enter = if (reducedMotion) {
                fadeIn(tween(200))
            } else {
                scaleIn(spring, initialScale = 0.25f, transformOrigin = TransformOrigin(0f, 1f)) + fadeIn(spring)
            },
            exit = if (reducedMotion) {
                fadeOut(tween(200))
            } else {
                scaleOut(spring, targetScale = 0.25f, transformOrigin = TransformOrigin(0f, 1f)) + fadeOut(spring)
            },
        ) {
            BackgroundWorkPanel(
                agents = agents,
                shells = shells,
                maxHeight = with(density) { max(160.dp.toPx(), top - safeTop - 72.dp.toPx()).toDp() }.value,
                onOpenSubagent = { id ->
                    isPresented.wrappedValue = false
                    store.send(SessionFeature.Action.familyPicked(id))
                },
                onOpenShell = { id ->
                    isPresented.wrappedValue = false
                    store.send(SessionFeature.Action.shellOutputTapped(id))
                },
                onDismiss = { isPresented.wrappedValue = false },
            )
        }
    }
    val hasWork = store.observe { hasBackgroundWork }
    LaunchedEffect(hasWork) {
        if (!hasWork) isPresented.wrappedValue = false
    }
    LaunchedEffect(store.state.session?.id) { isPresented.wrappedValue = false }
    store.presented(SessionFeature.State::shellOutput, SessionFeature.Action.shellOutput) { shellStore ->
        ShellOutputView(shellStore)
    }
}

/// Running subagents and background shells, full width so titles never wrap.
@Composable
fun BackgroundWorkPanel(
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    /// In points.
    maxHeight: Float,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val reducedMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    var contentHeight by remember { mutableStateOf<Float?>(null) }
    /// Before the first measurement: rows, headers, and padding.
    val estimatedHeight = (agents.size + shells.size) * 48f + (if (agents.isEmpty()) 0f else 30f) + (if (shells.isEmpty()) 0f else 30f) + 20f
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // A list long enough to scroll keeps its drags for scrolling.
    val scrolls = (contentHeight ?: estimatedHeight) > maxHeight
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = min(contentHeight ?: estimatedHeight, maxHeight).dp)
            .offset { IntOffset(0, drag.value.roundToInt()) }
            .openCodeGlass(shape)
            .clip(shape)
            .then(
                if (scrolls) {
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
                                if (pull > 56 || predicted > 140) onDismiss()
                                scope.launch {
                                    if (reducedMotion) drag.snapTo(0f) else drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 439f))
                                }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } },
                        )
                    }
                },
            )
            .semantics {
                paneTitle = "Background work"
                testTag = "background-work-panel"
                customActions = listOf(CustomAccessibilityAction("Close") { onDismiss(); true })
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .onSizeChanged { contentHeight = it.height / density.density }
                .padding(vertical = 10.dp),
        ) {
            if (agents.isNotEmpty()) {
                SectionHeader("Subagents")
                for (agent in agents) {
                    BackgroundWorkRow(
                        title = agent.title,
                        accessibilityHint = "Opens the subagent’s session",
                        onTap = { onOpenSubagent(agent.id) },
                        modifier = Modifier.semantics { testTag = "background-subagent-${agent.id}" },
                    ) {
                        AgentActivityGlyph()
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
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onTap)
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
