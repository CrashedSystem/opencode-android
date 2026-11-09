package ai.opencode.feature.voice

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * What the card and the full sheet show, laid out at the size it's given (iOS
 * `FloatingCallStageContent`): the session and duration above the bloom, the call's details (full
 * sheet only) and status below it, the live transcript, and the controls. The shape's surface and
 * dot field are drawn by the floating call's surface. Sizes in dp.
 *
 * @param fullness 0 laid out as the card, 1 as the full sheet.
 * @param bloom the bloom's centre and radius at this layout.
 */
@Composable
fun FloatingCallStageContent(
    store: VoiceStoreOf,
    width: Float,
    height: Float,
    fullness: Float,
    bloom: FloatingCallStageGeometry.Bloom,
    status: String,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val full = fullness
    val below = (bloom.y + bloom.radius).toFloat()
    val statusTop = below + lerp(8f, 34f, full)
    val transcriptTop = statusTop + 26
    val controlsBottom = lerp(14f, 40f, full)
    val diameter = lerp(54f, 62f, full)
    val row = max(44f, diameter)
    val transcriptHeight = max(0f, height - controlsBottom - row - 12 - transcriptTop)
    val details by store.observe { "${it.resolvedEngine.title} · ${it.directoryName}" }
    Box(modifier.requiredSize(width.dp, height.dp)) {
        // It drags: up to the sheet, down to the orb.
        Box(
            Modifier
                .offset(x = ((width - 36) / 2).dp, y = 6.dp)
                .size(36.dp, 5.dp)
                .background(OpenCodeTheme.colors.tertiaryText, CircleShape)
                .clearAndSetSemantics {},
        )
        FloatingCallStageHeader(
            store = store,
            status = status,
            onMinimize = onMinimize,
            modifier = Modifier
                .offset(y = lerp(14f, 16f, full).dp)
                .requiredSize(width.dp, 48.dp),
        )
        Text(
            details,
            style = OpenCodeTextStyles.footnote,
            color = OpenCodeTheme.colors.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset(y = (below + 10).dp)
                .width(width.dp)
                // In once the status has moved down clear of it.
                .alpha(smooth(0.7f, 1f, full))
                .then(if (full < 0.5f) Modifier.semantics { hideFromAccessibility() } else Modifier),
        )
        Text(
            status,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
            color = OpenCodeTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .offset(y = statusTop.dp)
                .width(width.dp)
                .testTag("floating-call-status"),
        )
        FloatingCallTranscript(
            store,
            Modifier
                .offset(x = 24.dp, y = transcriptTop.dp)
                .requiredSize(max(0f, width - 48).dp, transcriptHeight.dp),
        )
        FloatingCallStageControls(
            store = store,
            diameter = diameter,
            modifier = Modifier
                .offset(x = 10.dp, y = (height - controlsBottom - row).dp)
                .requiredSize(max(0f, width - 20).dp, row.dp),
        )
    }
}

@Composable
private fun FloatingCallStageHeader(store: VoiceStoreOf, status: String, onMinimize: () -> Unit, modifier: Modifier = Modifier) {
    val title by store.observe { it.sessionTitle }
    val connectedAt by store.observe { it.connectedAt }
    Row(
        modifier.padding(start = 18.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text("Talking in", style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText)
            Text(
                title,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FloatingCallDuration(connectedAt, status, Modifier.padding(end = 4.dp))
        // Android: an M3 icon button with Material's collapse glyph instead of iOS's chevron.
        IconButton(onClick = onMinimize, modifier = Modifier.semantics { contentDescription = "Minimize" }) {
            OpenCodeIcon(SFSymbol.ArrowDownRightAndArrowUpLeft, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One row of equal circles on one baseline; names only for TalkBack. */
@Composable
private fun FloatingCallStageControls(store: VoiceStoreOf, diameter: Float, modifier: Modifier = Modifier) {
    val isMuted by store.observe { it.isMuted }
    val isLive by store.observe { it.phase == VoiceFeature.State.Phase.Live }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FloatingCallControl(
            // Android: a toggle named "Mute" that TalkBack reads as on/off (iOS relabels it Unmute).
            name = "Mute",
            symbol = if (isMuted) SFSymbol.MicSlashFill else SFSymbol.MicFill,
            diameter = diameter,
            isOn = isMuted,
            isToggle = true,
            isEnabled = isLive,
        ) { store.send(VoiceFeature.Action.MuteTapped) }
        FloatingCallControl(name = "Pause", symbol = SFSymbol.PauseFill, diameter = diameter, isPlaceholder = true) {}
        FloatingCallControl(name = "Camera", symbol = SFSymbol.VideoFill, diameter = diameter, isPlaceholder = true) {}
        FloatingCallControl(name = "Add", symbol = SFSymbol.Plus, diameter = diameter, isPlaceholder = true) {}
        FloatingCallControl(name = "End voice call", symbol = SFSymbol.PhoneDownFill, diameter = diameter, isDestructive = true) {
            store.send(VoiceFeature.Action.EndPressed)
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun smooth(low: Float, high: Float, value: Float): Float {
    val t = min(1f, max(0f, (value - low) / (high - low)))
    return t * t * (3 - 2 * t)
}

/** Monotonic seconds: the clock the transcript's reveal runs on (and Compose's frame time). */
private fun transcriptNow(): Double = System.nanoTime() / 1e9

/**
 * The call so far, you and the agent, newest at the bottom (iOS `FloatingCallTranscript`): the
 * column is anchored to the bottom and older lines rise out under a fade. The agent's words
 * arrive one by one as they stream ([CaptionWordRenderer]). It never scrolls, so a vertical drag
 * anywhere moves the sheet.
 */
@Composable
fun FloatingCallTranscript(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val targets by store.observe { it.captionTargets }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Opening onto a call in progress shows what's there from the first frame.
    var reveal by remember {
        val opening = CaptionReveal().sync(store.state.captionTargets, revealed = true)
        mutableStateOf(opening.trim(force = true) ?: opening)
    }
    var now by remember { mutableDoubleStateOf(transcriptNow()) }
    val queueChanged = remember { Channel<Unit>(Channel.CONFLATED) }
    val latestReveal by rememberUpdatedState(reveal)

    val animationEnd = reveal.lastReveal + CaptionWordRenderer.duration
    val isActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(animationEnd, isActive) {
        // Paused when settled or in the background; a paused timeline's time is stale, so
        // settled drawing reads the real time.
        now = transcriptNow()
        if (!animationEnd.isFinite() || !isActive) return@LaunchedEffect
        while (now < animationEnd) withFrameNanos { now = it / 1e9 }
        now = transcriptNow()
        // Settled: the lines that have risen out of the column are dropped, so a long call
        // never re-lays out, or re-matches, everything said so far.
        reveal.trim()?.let { reveal = it }
    }
    // Reveals queued words for the call's lifetime, waking when lines change.
    LaunchedEffect(Unit) {
        queueChanged.trySend(Unit)
        for (signal in queueChanged) {
            while (true) {
                val wait = latestReveal.nextRevealDelay(transcriptNow()) ?: break
                if (wait > 0) delay((wait * 1000).roundToInt().toLong().coerceAtLeast(1))
                reveal = reveal.revealNext(transcriptNow())
            }
        }
    }
    val synced = remember { mutableStateOf(targets) }
    LaunchedEffect(targets) {
        if (synced.value == targets) return@LaunchedEffect
        synced.value = targets
        reveal = reveal.sync(targets)
        queueChanged.trySend(Unit)
    }

    // A line takes space from its first word, so lines still queued behind another leave no gap.
    val lines = reveal.lines.filter { it.revealedAt.isNotEmpty() }.takeLast(16)
    Box(
        modifier
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 24.dp.toPx()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fade),
                    blendMode = BlendMode.DstIn,
                )
            }
            .testTag("floating-call-transcript"),
        contentAlignment = Alignment.BottomStart,
    ) {
        // The column keeps its own height, hung from the bottom of the room it's given.
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (lines.isEmpty()) {
                Text("Say something to your session.", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
            }
            for (line in lines) {
                androidx.compose.runtime.key(line.id) {
                    Column {
                        if (line.isUser) {
                            Text(
                                "You",
                                style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold),
                                color = OpenCodeTheme.colors.tertiaryText,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                        FloatingCallCaptionLine(line, { now }, reduceMotion)
                    }
                }
            }
        }
    }
}

/**
 * One line of the transcript, laid out as its words appear (one view per word), so an appended
 * word never moves an earlier one. TalkBack hears “You:” before yours.
 */
@Composable
private fun FloatingCallCaptionLine(line: CaptionReveal.Line, now: () -> Double, reduceMotion: Boolean) {
    val style = OpenCodeTextStyles.body
    val measurer = rememberTextMeasurer()
    val spaceWidth = remember(style, measurer) { measurer.measure(" ", style).size.width }
    val color = if (line.isUser) OpenCodeTheme.colors.secondaryText else OpenCodeTheme.colors.text
    val density = LocalDensity.current.density
    val composedAt = transcriptNow()
    Layout(
        modifier = Modifier.clearAndSetSemantics { contentDescription = if (line.isUser) "You: ${line.text}" else line.text },
        content = {
            for (index in line.revealedAt.indices) {
                val at = line.revealedAt[index]
                // Only words still revealing redraw frame by frame.
                val settled = at + CaptionWordRenderer.duration <= composedAt
                Text(
                    line.words[index],
                    style = style,
                    color = color,
                    modifier = if (settled) Modifier else Modifier.graphicsLayer {
                        val eased = CaptionWordRenderer.eased(at, now())
                        alpha = eased
                    },
                )
            }
        },
        measurePolicy = floatingCallWordFlow(spaceWidth),
    )
}

/** Words wrapped greedily, one view each (iOS `CaptionWordFlow`): a word never moves once placed. */
private fun floatingCallWordFlow(spaceWidth: Int) = MeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth
    var x = 0
    var rowY = 0
    var rowHeight = 0
    val placed = measurables.map { measurable ->
        var placeable = measurable.measure(Constraints())
        if (placeable.width > width) {
            // A word wider than the line wraps within its own row.
            placeable = measurable.measure(Constraints(maxWidth = width))
        }
        if (x > 0 && x + spaceWidth + placeable.width > width) {
            rowY += rowHeight
            rowHeight = 0
            x = 0
        } else if (x > 0) {
            x += spaceWidth
        }
        val position = IntOffset(x, rowY)
        x += placeable.width
        rowHeight = max(rowHeight, placeable.height)
        placeable to position
    }
    val maxX = placed.maxOfOrNull { (p, pos) -> pos.x + p.width } ?: 0
    val maxY = placed.maxOfOrNull { (p, pos) -> pos.y + p.height } ?: 0
    layout(maxX.coerceIn(constraints.minWidth, constraints.maxWidth), maxY.coerceAtLeast(constraints.minHeight)) {
        for ((p, pos) in placed) p.place(pos.x, pos.y)
    }
}
