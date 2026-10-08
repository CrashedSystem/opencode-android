@file:PortedFrom("OpenCode/Transcript/TranscriptEntryView.swift", "16b40f7")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.RoundedRectangle
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.URL
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.rememberHaptics
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Store
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.reflect.KMutableProperty1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/// The transcript's complete spacing vocabulary. Rows own their outer rhythm;
/// individual renderers only use values from this scale, so hosting-cell and
/// SwiftUI padding cannot accidentally compound.
object TranscriptMetrics {
    const val compact = 4
    const val clustered = 8
    const val bubbleVertical = 10
    const val standard = 12
    const val contentGutter = 16
    const val turn = 20

    const val bubbleCornerRadius = 18
    /// Content surfaces: code, tables, cards, and images.
    const val cardCornerRadius = 14
    /// Controls inside sheets and cards: choices and fields.
    const val controlCornerRadius = 10
    /// Markdown code blocks and request commands: one mono family, sized to
    /// sit beside 17 pt body text.
    val codeFont: TextStyle get() = TextStyles.code
    /// Denser tool commands, arguments, and output (the desktop app's 13 pt).
    val toolFont: TextStyle get() = TextStyles.tool
    const val maximumUserBubbleFraction = 0.78f
    const val headerBand = 60

    /// Not in iOS: SwiftUI sets `.body` text on SF's 20.3 pt line, and
    /// `lineSpacing` adds to it. A line height of this plus the spacing keeps
    /// the iOS pitch in Roboto.
    const val bodyLine = 20.3f

    /// Rows meet at the sum of their halves, measured between line boxes:
    /// every row's frame is its content, with no hidden padding, and a larger
    /// hit target extends into the row's own halves (`rowHitTarget`).
    ///
    /// Within a reply, paragraphs, Used N, notices, and Working are 16 apart,
    /// like paragraphs inside one Markdown message.
    const val flowHalf = clustered
    /// A user message is 20 from the reply around it.
    const val turnHalf = standard
    /// A compaction is a context boundary: 24 from its neighbours.
    const val sectionHalf = clustered * 2
    /// Between an expanded group's tool rows: with their 18 pt footnote line,
    /// a 36 pt pitch.
    const val toolRowSpacing = 18
}

data class TranscriptRowInsets(val top: Int, val bottom: Int)

val TranscriptEntry.rowInsets: TranscriptRowInsets
    get() = when (this) {
        is TranscriptEntry.userGroup ->
            TranscriptRowInsets(if (startsTurn) TranscriptMetrics.turnHalf else 0, TranscriptMetrics.turnHalf)
        is TranscriptEntry.single ->
            if (item is OpenCodeTranscriptItem.compaction) {
                TranscriptRowInsets(TranscriptMetrics.sectionHalf, TranscriptMetrics.sectionHalf)
            } else {
                TranscriptRowInsets(TranscriptMetrics.flowHalf, TranscriptMetrics.flowHalf)
            }
        is TranscriptEntry.toolGroup -> TranscriptRowInsets(TranscriptMetrics.flowHalf, TranscriptMetrics.flowHalf)
        // Belongs to the reply above it; the next row is a new turn.
        is TranscriptEntry.replyEnd -> TranscriptRowInsets(TranscriptMetrics.flowHalf, TranscriptMetrics.turnHalf)
    }

val OpenCodeQueuedInput.rowInsets: TranscriptRowInsets
    get() {
        if (isCompaction) return TranscriptRowInsets(TranscriptMetrics.sectionHalf, TranscriptMetrics.sectionHalf)
        // A pending notice is a quiet line like Used N, not a turn.
        if (notice != null) return TranscriptRowInsets(TranscriptMetrics.flowHalf, TranscriptMetrics.flowHalf)
        return TranscriptRowInsets(TranscriptMetrics.turnHalf, TranscriptMetrics.turnHalf)
    }

/// Keeps a quiet row's frame at its text's line box (so gaps around it
/// match the paragraphs') while its target fills the row's halves.
fun Modifier.rowHitTarget(
    top: Int = TranscriptMetrics.flowHalf,
    bottom: Int = TranscriptMetrics.flowHalf,
    onClick: () -> Unit,
): Modifier = this
    .layout { measurable, constraints ->
        val vertical = (top + bottom).dp.roundToPx()
        val horizontal = (TranscriptMetrics.flowHalf * 2).dp.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(
                maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + horizontal else constraints.maxWidth,
                minWidth = constraints.minWidth + horizontal,
                minHeight = constraints.minHeight + vertical,
                maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + vertical else constraints.maxHeight,
            ),
        )
        layout(placeable.width - horizontal, placeable.height - vertical) {
            placeable.place(-TranscriptMetrics.flowHalf.dp.roundToPx(), -top.dp.roundToPx())
        }
    }
    .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
    .padding(start = TranscriptMetrics.flowHalf.dp, end = TranscriptMetrics.flowHalf.dp, top = top.dp, bottom = bottom.dp)

/// A labelled horizontal rule, like the TUI's compaction marker.
@Composable
fun TranscriptRule(
    labelStyle: Color,
    modifier: Modifier = Modifier,
    lineStyle: Color = SystemColors.quaternaryLabel,
    label: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(lineStyle))
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides labelStyle,
            androidx.compose.material3.LocalTextStyle provides TextStyles.caption.copy(color = labelStyle),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = label)
        }
        Box(Modifier.weight(1f).height(1.dp).background(lineStyle))
    }
}

/// The collapsed Used N row: one line whose counts roll like a counter as a
/// live turn grows. The tool list truncates before a failure, so "Failed"
/// stays in view; accessibility sizes wrap it as prose instead.
@Composable
private fun ToolGroupLabel(text: String, failed: Boolean, modifier: Modifier = Modifier) {
    val failure = buildAnnotatedString {
        append(" · ")
        withStyle(SpanStyle(color = SystemColors.systemRed)) { append("Failed") }
    }
    // iOS's accessibility sizes start past xxxLarge, about 1.35×.
    if (LocalDensity.current.fontScale > 1.35f) {
        ToolGroupCounting(if (failed) AnnotatedString(text) + failure else AnnotatedString(text), modifier)
        return
    }
    Row(modifier) {
        ToolGroupCounting(AnnotatedString(text), Modifier.weight(1f, fill = false).alignByBaseline(), maxLines = 1)
        if (failed) Text(failure, Modifier.alignByBaseline(), style = TextStyles.body, color = SystemColors.secondaryLabel, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ToolGroupCounting(label: AnnotatedString, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val reducedMotion = LocalReduceMotion.current
    val digits = TextStyles.body.copy(fontFeatureSettings = "tnum")
    @Composable
    fun line(value: AnnotatedString) =
        Text(value, style = digits, color = SystemColors.secondaryLabel, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    if (reducedMotion) {
        Box(modifier) { line(label) }
        return
    }
    AnimatedContent(
        targetState = label,
        modifier = modifier,
        transitionSpec = {
            (slideInVertically(tween(250)) { it / 2 } + fadeIn(tween(250))) togetherWith
                (slideOutVertically(tween(250)) { -it / 2 } + fadeOut(tween(250))) using SizeTransform(clip = false)
        },
        label = "tool-usage",
    ) { value ->
        line(value)
    }
}

/// Renders one self-sizing transcript list entry without depending on its
/// surrounding scroll container.
///
/// Disclosure lives outside the row so a cell reload or reuse never collapses
/// a group the reader opened.
@Stable
class TranscriptDisclosureState {
    var toolGroups by mutableStateOf<Set<String>>(emptySet())
    var tools by mutableStateOf<Set<String>>(emptySet())
    var compactions by mutableStateOf<Set<String>>(emptySet())
    /// The list's: runs a toggle in a row as one motion (see
    /// `TranscriptListViewController.toggleDisclosure`). Without it the
    /// change lands at once.
    var animateToggle: ((entryID: String, opening: Boolean, toggle: () -> Unit) -> Unit)? = null

    /// Opens or closes `id` in the row for `entryID`.
    fun toggle(set: KMutableProperty1<TranscriptDisclosureState, Set<String>>, id: String, entryID: String) {
        val toggle = {
            val current = set.get(this)
            set.set(this, if (current.contains(id)) current - id else current + id)
        }
        val animate = animateToggle
        if (animate != null) animate(entryID, !set.get(this).contains(id), toggle) else toggle()
    }

    companion object {
        /// `-lab-expand-tools` opens Used N groups by default, for captures.
        val labExpandsTools: Boolean
            get() = ai.opencode.mobilecode.system.ProcessInfo.processInfo.arguments.contains("-lab-expand-tools")
    }
}

/// What a disclosure reveals fades in as the row grows over it, and out
/// (quicker) as the row closes over it.
private object Disclosed {
    /// The list's critically damped edge: 0.3 s opening, 0.24 s closing.
    private fun edge(duration: Double) = spring<androidx.compose.ui.unit.IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = ((2 * PI / duration) * (2 * PI / duration)).toFloat(),
    )

    fun insertion(reducedMotion: Boolean): EnterTransition =
        if (reducedMotion) {
            EnterTransition.None
        } else {
            expandVertically(edge(0.3), expandFrom = Alignment.Top) + fadeIn(tween(220, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }

    fun removal(reducedMotion: Boolean): ExitTransition =
        if (reducedMotion) {
            ExitTransition.None
        } else {
            shrinkVertically(edge(0.24), shrinkTowards = Alignment.Top) + fadeOut(tween(140, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
}

object TranscriptEntryView {
    @Composable
    operator fun invoke(
        entry: TranscriptEntry,
        store: Store<SessionFeature.State, SessionFeature.Action>,
        onPreviewImage: (TranscriptImagePreview) -> Unit,
        onOpenFile: (AgentFileReference) -> Unit = {},
        disclosure: TranscriptDisclosureState,
        modifier: Modifier = Modifier,
    ) {
        // File paths in rendered Markdown carry a private link; web links
        // still open in the browser.
        val system = LocalUriHandler.current
        val openURL = remember(system, onOpenFile) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    val file = AgentFileReference(linkURL = URL(uri)) ?: return system.openUri(uri)
                    onOpenFile(file)
                }
            }
        }
        CompositionLocalProvider(LocalUriHandler provides openURL) {
            Box(modifier.fillMaxWidth()) {
                when (entry) {
                    is TranscriptEntry.single -> ItemView(entry, entry.item, store, onPreviewImage, onOpenFile, disclosure)
                    is TranscriptEntry.userGroup -> Unit
                    is TranscriptEntry.toolGroup -> ToolGroupView(entry, entry.id, entry.tools, store, onPreviewImage, onOpenFile, disclosure)
                    is TranscriptEntry.replyEnd -> ReplyActions(entry.blocks)
                }
            }
        }
    }

    @Composable
    private fun ToolGroupView(
        entry: TranscriptEntry,
        id: String,
        tools: List<OpenCodeTranscriptItem>,
        store: Store<SessionFeature.State, SessionFeature.Action>,
        onPreviewImage: (TranscriptImagePreview) -> Unit,
        onOpenFile: (AgentFileReference) -> Unit,
        disclosure: TranscriptDisclosureState,
    ) {
        var isExpanded = disclosure.toolGroups.contains(id)
        if (BuildConfig.DEBUG && TranscriptDisclosureState.labExpandsTools) isExpanded = !isExpanded
        val images = entry.toolImageAttachments
        val summary = toolGroupSummary(tools)
        val label = if (summary.isEmpty()) "Updates" else "Used $summary"
        val failed = toolGroupFailed(tools)
        val reducedMotion = LocalReduceMotion.current
        Column(verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp)) {
            // Body size like the prose around it, quieter in grey.
            ToolGroupLabel(
                label,
                failed,
                Modifier
                    // Laid out as a line of prose; the target takes the row's halves.
                    .rowHitTarget { disclosure.toggle(TranscriptDisclosureState::toolGroups, id, entry.id) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        stateDescription = (if (isExpanded) "Expanded" else "Collapsed") + (if (failed) ", Failed" else "")
                    },
            )

            if (images.isNotEmpty()) {
                ToolImageMosaic(images) { selectedID ->
                    val preview = TranscriptImagePreview(attachments = images, selectedID = selectedID, label = "Tool image")
                    if (preview != null) onPreviewImage(preview)
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = Disclosed.insertion(reducedMotion),
                exit = Disclosed.removal(reducedMotion),
            ) {
                // Dense, TUI-like rows on a 36 pt pitch; each row's target fills
                // the space around it.
                Column(
                    Modifier.padding(start = 16.dp, top = TranscriptMetrics.compact.dp),
                    verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.toolRowSpacing.dp),
                ) {
                    for (tool in tools) {
                        androidx.compose.runtime.key(tool.id) {
                            ItemView(entry, tool, store, onPreviewImage, onOpenFile, disclosure)
                        }
                    }
                }
            }
        }
    }

    /// A labelled rule like the TUI's: running, completed (with the request's
    /// own token usage), provider compaction, cancelled, or failed with its
    /// error. A completed local summary expands in place.
    @Composable
    private fun CompactionView(
        entry: TranscriptEntry,
        id: String,
        info: OpenCodeCompaction,
        store: Store<SessionFeature.State, SessionFeature.Action>,
        onPreviewImage: (TranscriptImagePreview) -> Unit,
        onOpenFile: (AgentFileReference) -> Unit,
        disclosure: TranscriptDisclosureState,
    ) {
        val expandable = info.status == OpenCodeCompaction.Status.completed && info.summary.isNotEmpty()
        val isExpanded = expandable && disclosure.compactions.contains(id)
        val failed = info.status == OpenCodeCompaction.Status.failed
        val title = when (info.status) {
            OpenCodeCompaction.Status.running -> "Compacting context"
            OpenCodeCompaction.Status.completed -> if (info.isProvider) "Provider compaction" else "Context compacted"
            OpenCodeCompaction.Status.cancelled -> "Compaction cancelled"
            OpenCodeCompaction.Status.failed -> "Compaction failed"
        }
        val input = info.inputTokens
        val output = info.outputTokens
        val usage = if (input != null && output != null) "${formatted(input)} in · ${formatted(output)} out" else null
        val label = listOfNotNull(title, usage).joinToString(", ")
        val reducedMotion = LocalReduceMotion.current
        val rule = @Composable { modifier: Modifier ->
            TranscriptRule(
                labelStyle = if (failed) SystemColors.systemRed else SystemColors.secondaryLabel,
                lineStyle = if (failed) SystemColors.systemRed.copy(alpha = 0.5f) else SystemColors.quaternaryLabel,
                modifier = modifier,
            ) {
                if (info.status == OpenCodeCompaction.Status.running) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = SystemColors.secondaryLabel)
                } else if (failed) {
                    SystemImage("xmark.circle.fill", null, Modifier.symbolSize(TextStyles.caption), tint = SystemColors.systemRed)
                }
                Text(title)
                if (usage != null) Text("· $usage", style = TextStyles.caption.copy(fontFeatureSettings = "tnum"))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard.dp)) {
            if (expandable) {
                rule(
                    Modifier
                        .rowHitTarget(top = TranscriptMetrics.sectionHalf, bottom = TranscriptMetrics.sectionHalf) {
                            disclosure.toggle(TranscriptDisclosureState::compactions, id, entry.id)
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = label
                            stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                            testTag = "transcript-compaction"
                        },
                )
            } else {
                rule(
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = label
                        testTag = "transcript-compaction"
                    },
                )
            }

            val message = info.errorMessage
            if (failed && message != null) {
                SelectionContainer {
                    Text(message, Modifier.fillMaxWidth(), style = TextStyles.footnote, color = SystemColors.systemRed)
                }
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = Disclosed.insertion(reducedMotion),
                exit = Disclosed.removal(reducedMotion),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides SystemColors.secondaryLabel,
                ) {
                    MarkdownBlocksView(
                        blocks = info.summary,
                        server = store.state.server,
                        directory = store.state.session?.directory,
                        onPreviewImage = onPreviewImage,
                        onOpenFile = onOpenFile,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }

    /// "3 Shell, 2 Edit, 1 Webfetch": each tool's own count, in first-use order.
    fun toolGroupSummary(tools: List<OpenCodeTranscriptItem>): String {
        val order = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        for (item in tools) {
            val call = (item as? OpenCodeTranscriptItem.tool)?.call ?: continue
            if (counts[call.displayName] == null) order += call.displayName
            counts[call.displayName] = (counts[call.displayName] ?: 0) + 1
        }
        val parts = order.take(3).map { "${counts[it] ?: 0} $it" }
        return parts.joinToString(", ") + (if (order.size > 3) "…" else "")
    }

    /// Whether any tool or delivered notice in the group failed, which the
    /// collapsed row keeps in view.
    fun toolGroupFailed(tools: List<OpenCodeTranscriptItem>): Boolean =
        tools.any { item ->
            (item is OpenCodeTranscriptItem.tool && item.call.status == OpenCodeToolCall.Status.error) ||
                (item is OpenCodeTranscriptItem.notice && item.isError)
        }

    @Composable
    private fun ItemView(
        entry: TranscriptEntry,
        item: OpenCodeTranscriptItem,
        store: Store<SessionFeature.State, SessionFeature.Action>,
        onPreviewImage: (TranscriptImagePreview) -> Unit,
        onOpenFile: (AgentFileReference) -> Unit,
        disclosure: TranscriptDisclosureState,
    ) {
        when (item) {
            is OpenCodeTranscriptItem.userImages, is OpenCodeTranscriptItem.userFiles, is OpenCodeTranscriptItem.userText -> Unit

            is OpenCodeTranscriptItem.assistantMarkdown ->
                MarkdownBlocksView(
                    blocks = item.blocks,
                    server = store.state.server,
                    directory = store.state.session?.directory,
                    onPreviewImage = onPreviewImage,
                    onOpenFile = onOpenFile,
                    revealItem = item.id,
                    modifier = Modifier.fillMaxWidth(),
                )

            is OpenCodeTranscriptItem.tool -> ToolView(entry, item.id, item.call, store, onOpenFile, disclosure)

            is OpenCodeTranscriptItem.compaction ->
                CompactionView(
                    entry,
                    item.id,
                    item.info ?: OpenCodeCompaction(status = OpenCodeCompaction.Status.completed),
                    store,
                    onPreviewImage,
                    onOpenFile,
                    disclosure,
                )

            is OpenCodeTranscriptItem.info ->
                Text(
                    item.text,
                    Modifier.fillMaxWidth(),
                    style = TextStyles.footnote,
                    color = SystemColors.tertiaryLabel,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

            is OpenCodeTranscriptItem.sendFailure ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SystemColors.secondarySystemBackground, RoundedCornerShape(TranscriptMetrics.cardCornerRadius.dp))
                        .padding(horizontal = TranscriptMetrics.standard.dp, vertical = TranscriptMetrics.clustered.dp)
                        .semantics { testTag = "send-failure" },
                    horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SystemImage(
                        "exclamationmark.circle.fill",
                        null,
                        Modifier.symbolSize(TextStyles.body).semantics { hideFromAccessibility() },
                        tint = SystemColors.systemRed,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Couldn’t send", style = TextStyles.subheadline.semibold, color = SystemColors.label)
                        Text(
                            item.message,
                            style = TextStyles.footnote,
                            color = SystemColors.secondaryLabel,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(TranscriptMetrics.clustered.dp))
                    BorderedSmallButton("Try Again") { store.send(SessionFeature.Action.retrySendTapped(item.id)) }
                }

            is OpenCodeTranscriptItem.notice ->
                Text(
                    item.text,
                    Modifier.fillMaxWidth(),
                    style = TextStyles.footnote,
                    color = if (item.isError) SystemColors.systemRed else SystemColors.secondaryLabel,
                )
        }
    }

    @Composable
    private fun ToolView(
        entry: TranscriptEntry,
        id: String,
        call: OpenCodeToolCall,
        store: Store<SessionFeature.State, SessionFeature.Action>,
        onOpenFile: (AgentFileReference) -> Unit,
        disclosure: TranscriptDisclosureState,
    ) {
        val questions = call.questions
        when {
            call.name == "subagent" || call.name == "task" -> SubagentToolView(call, store)
            questions != null -> QuestionToolView(entry, id, call, questions, store, disclosure)
            else -> GenericToolView(entry, id, call, onOpenFile, disclosure)
        }
    }

    @Composable
    private fun SubagentToolView(call: OpenCodeToolCall, store: Store<SessionFeature.State, SessionFeature.Action>) {
        val childID = call.metadata["sessionID"]?.string
        val child = store.state.familySessions.firstOrNull { it.id == childID }
        val name = child?.agent ?: call.input.`object`?.get("agent")?.string ?: "Agent"
        val running = call.status == OpenCodeToolCall.Status.running || call.status == OpenCodeToolCall.Status.streaming ||
            (childID != null && store.state.activeFamilySessionIDs.contains(childID))
        val description = call.input.`object`?.get("description")?.string
        // Like the desktop card, the glyph alone says this subagent is
        // working; the session's own “Working…” line stays the main run's.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedRectangle(TranscriptMetrics.cardCornerRadius.dp))
                .background(SystemColors.secondarySystemBackground)
                .clickable(enabled = child != null, role = Role.Button) {
                    if (childID != null) store.send(SessionFeature.Action.familyPicked(childID))
                }
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    testTag = "subagent-task"
                    stateDescription = if (call.status == OpenCodeToolCall.Status.error) "Failed" else if (running) "Running" else "Completed"
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(18.dp).height(20.dp).semantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
                AgentActivityGlyph(
                    source = AgentActivitySource.subagent(
                        SubagentActivity(
                            sessionID = childID,
                            phase = when {
                                call.status == OpenCodeToolCall.Status.error -> SubagentActivity.Phase.failed
                                running -> if (call.status == OpenCodeToolCall.Status.streaming || childID == null) SubagentActivity.Phase.spawning else SubagentActivity.Phase.running
                                else -> SubagentActivity.Phase.done
                            },
                            needsInput = childID?.let { store.state.waitingSessionIDs.contains(it) } == true,
                        ),
                    ),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact.dp)) {
                Text(child?.title ?: name, style = TextStyles.subheadline.medium, color = SystemColors.label)
                if (description != null) {
                    Text(description, style = TextStyles.subheadline, color = SystemColors.secondaryLabel)
                }
                if (call.status == OpenCodeToolCall.Status.error) {
                    Text("Failed", style = TextStyles.caption.medium, color = SystemColors.systemRed)
                }
            }
            if (child != null) {
                SystemImage("chevron.right", null, Modifier.symbolSize(TextStyles.caption), tint = SystemColors.tertiaryLabel)
            }
        }
    }

    @Composable
    private fun QuestionToolView(
        entry: TranscriptEntry,
        id: String,
        call: OpenCodeToolCall,
        questions: List<OpenCodeQuestion>,
        store: Store<SessionFeature.State, SessionFeature.Action>,
        disclosure: TranscriptDisclosureState,
    ) {
        val expanded = disclosure.tools.contains(id)
        val sessionID = store.state.session?.id
        val pending = store.state.formRequests.firstOrNull {
            it.isQuestion && it.sessionID == sessionID &&
                it.toolCallID == call.callID && call.messageID != null && it.toolMessageID == call.messageID
        }
        val reducedMotion = LocalReduceMotion.current
        Column(
            Modifier
                .fillMaxWidth()
                .background(SystemColors.secondarySystemBackground, RoundedCornerShape(TranscriptMetrics.cardCornerRadius.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SystemImage("questionmark.bubble.fill", null, Modifier.symbolSize(TextStyles.headline), tint = SystemColors.tint)
                Text(if (pending != null) "Needs your input" else "Question history", style = TextStyles.headline, color = SystemColors.tint)
            }
            if (pending != null) {
                OpenCodeButton("Answer Question", prominent = true) {
                    store.send(SessionFeature.Action.reviewRequestSelected(pending.id))
                }
            } else {
                Text(
                    if (call.status == OpenCodeToolCall.Status.error) {
                        "Question stopped. No answer can be sent from this card."
                    } else {
                        "Transcript only. Open requests appear above the message box."
                    },
                    style = TextStyles.footnote,
                    color = SystemColors.secondaryLabel,
                )
            }
            questions.forEachIndexed { index, question ->
                Column(verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp)) {
                    if (question.header.isNotEmpty()) {
                        Text(question.header, style = TextStyles.caption.semibold, color = SystemColors.secondaryLabel)
                    } else if (questions.size > 1) {
                        Text("Question ${index + 1}", style = TextStyles.caption.semibold, color = SystemColors.secondaryLabel)
                    }
                    Text(question.question, style = TextStyles.body, color = SystemColors.label)
                }
                if (index < questions.size - 1) HairlineRule()
            }
            Text(
                if (expanded) "Hide Raw Input" else "Show Raw Input",
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(interactionSource = null, indication = null, role = Role.Button) {
                        disclosure.toggle(TranscriptDisclosureState::tools, id, entry.id)
                    }
                    .wrapContentHeight(Alignment.CenterVertically),
                style = TextStyles.footnote.medium,
                color = SystemColors.secondaryLabel,
            )
            AnimatedVisibility(
                visible = expanded,
                enter = Disclosed.insertion(reducedMotion),
                exit = Disclosed.removal(reducedMotion),
            ) {
                WrappedCode(call.input.displayText, SystemColors.secondaryLabel)
            }
        }
    }

    @Composable
    private fun GenericToolView(
        entry: TranscriptEntry,
        id: String,
        call: OpenCodeToolCall,
        onOpenFile: (AgentFileReference) -> Unit,
        disclosure: TranscriptDisclosureState,
    ) {
        val expanded = disclosure.tools.contains(id)
        val reducedMotion = LocalReduceMotion.current
        Column(verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .rowHitTarget(top = TranscriptMetrics.toolRowSpacing / 2, bottom = TranscriptMetrics.toolRowSpacing / 2) {
                        disclosure.toggle(TranscriptDisclosureState::tools, id, entry.id)
                    }
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // No leading caret: it read as a shell prompt. The row itself is
                // the disclosure (see the accessibility hint); errors keep a glyph.
                if (call.status == OpenCodeToolCall.Status.error) {
                    SystemImage("exclamationmark.circle.fill", null, Modifier.symbolSize(TextStyles.caption2), tint = SystemColors.systemRed)
                }
                Text(
                    call.displayName,
                    style = TextStyles.footnote.medium,
                    color = if (call.status == OpenCodeToolCall.Status.error) SystemColors.systemRed else SystemColors.label,
                )
                // The desktop app hides the detail once the row is open: the
                // command or arguments below say it in full.
                val subtitle = call.subtitle
                if (!expanded && subtitle != null) {
                    Text(
                        subtitle,
                        Modifier.weight(1f, fill = false),
                        style = TranscriptMetrics.toolFont,
                        color = SystemColors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val started = call.startedAt
                if (call.status == OpenCodeToolCall.Status.running && started != null) {
                    // A long call says how long it has been waiting.
                    RunningTime(started)
                }
                if (call.status == OpenCodeToolCall.Status.completed) {
                    SystemImage("checkmark", "Completed", Modifier.symbolSize(TextStyles.caption), tint = SystemColors.secondaryLabel)
                } else if (call.status == OpenCodeToolCall.Status.error) {
                    Text("Failed", style = TextStyles.caption.medium, color = SystemColors.systemRed)
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = Disclosed.insertion(reducedMotion),
                exit = Disclosed.removal(reducedMotion),
            ) {
                ToolBody(entry, id, call, onOpenFile, disclosure)
            }
        }
    }

    @Composable
    private fun RunningTime(started: Long) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(started) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000 - (now - started) % 1_000)
            }
        }
        val seconds = maxOf(0L, (now - started) / 1_000).toInt()
        if (seconds >= 5) {
            Text(
                if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s",
                style = TextStyles.caption.copy(fontFeatureSettings = "tnum"),
                color = SystemColors.secondaryLabel,
            )
        }
    }

    /// The desktop app's console block: what ran (command or arguments) above
    /// a hairline, then the wrapped result. Long output folds after a screenful.
    @Composable
    private fun ToolBody(
        entry: TranscriptEntry,
        id: String,
        call: OpenCodeToolCall,
        onOpenFile: (AgentFileReference) -> Unit,
        disclosure: TranscriptDisclosureState,
    ) {
        val command = if (call.isShell) call.input.`object`?.get("command")?.string?.ifEmpty { null } else null
        val arguments = call.arguments
        val output = call.output.joinToString("\n").trim('\n', '\r')
        val shape = RoundedCornerShape(TranscriptMetrics.controlCornerRadius.dp)
        Column(
            Modifier
                .padding(end = TranscriptMetrics.compact.dp, bottom = TranscriptMetrics.clustered.dp)
                .fillMaxWidth()
                .clip(shape)
                .border(0.5.dp, SystemColors.separator, shape),
        ) {
            if (command != null || arguments.isNotEmpty()) {
                Column(
                    Modifier.padding(TranscriptMetrics.standard.dp),
                    verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact.dp),
                ) {
                    if (command != null) WrappedCode(command, SystemColors.label)
                    for (argument in arguments) {
                        val secondary = SystemColors.secondaryLabel
                        val primary = SystemColors.label
                        SelectionContainer {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = secondary)) { append(argument.first + "  ") }
                                    withStyle(SpanStyle(color = primary)) { append(argument.second) }
                                },
                                style = TranscriptMetrics.toolFont,
                            )
                        }
                    }
                }
            }
            val error = call.error
            if (output.isNotEmpty() || error != null) {
                if (command != null || arguments.isNotEmpty()) HairlineRule()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(SystemColors.secondarySystemBackground.copy(alpha = 0.6f))
                        .padding(TranscriptMetrics.standard.dp),
                    verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp),
                ) {
                    if (output.isNotEmpty()) {
                        val outputKey = "$id-output"
                        val showsAllOutput = disclosure.tools.contains(outputKey)
                        val outputLines = output.split("\n").size
                        WrappedCode(output, SystemColors.secondaryLabel, maxLines = if (showsAllOutput) Int.MAX_VALUE else foldedOutputLines)
                        if (outputLines > foldedOutputLines) {
                            Text(
                                if (showsAllOutput) "Show Less" else "Show All $outputLines Lines",
                                Modifier
                                    .heightIn(min = 44.dp)
                                    .clickable(interactionSource = null, indication = null, role = Role.Button) {
                                        disclosure.toggle(TranscriptDisclosureState::tools, outputKey, entry.id)
                                    }
                                    .wrapContentHeight(Alignment.CenterVertically),
                                style = TextStyles.footnote.medium,
                                color = SystemColors.secondaryLabel,
                            )
                        }
                    }
                    if (error != null) {
                        SelectionContainer {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SystemImage("exclamationmark.circle.fill", null, Modifier.symbolSize(TranscriptMetrics.toolFont), tint = SystemColors.systemRed)
                                Text(error, style = TranscriptMetrics.toolFont, color = SystemColors.systemRed)
                            }
                        }
                    }
                }
            }
            val file = call.fileReference
            if (file != null && call.status != OpenCodeToolCall.Status.running && call.status != OpenCodeToolCall.Status.streaming) {
                HairlineRule()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { onOpenFile(file) }
                        .padding(horizontal = TranscriptMetrics.standard.dp)
                        .semantics(mergeDescendants = true) { testTag = "tool-open-file" },
                    horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SystemImage(file.symbolName, null, Modifier.symbolSize(TextStyles.footnote), tint = SystemColors.secondaryLabel)
                    Text(
                        "Open ${file.name}",
                        Modifier.weight(1f),
                        style = TextStyles.footnote.medium,
                        color = SystemColors.label,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    SystemImage("chevron.right", null, Modifier.symbolSize(TextStyles.caption), tint = SystemColors.tertiaryLabel)
                }
            }
        }
    }

    private const val foldedOutputLines = 16

    @Composable
    private fun WrappedCode(text: String, style: Color, maxLines: Int = Int.MAX_VALUE) {
        SelectionContainer {
            Text(
                text,
                Modifier.fillMaxWidth(),
                style = TranscriptMetrics.toolFont,
                color = style,
                maxLines = maxLines,
                overflow = TextOverflow.Clip,
            )
        }
    }

    private fun formatted(value: Int): String = NumberFormat.getIntegerInstance().format(value)
}

/// `Button(title).buttonStyle(.bordered).controlSize(.small)`: the tint on a
/// faint tint fill, at least 44 pt tall.
@Composable
private fun BorderedSmallButton(title: String, action: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Text(
            title,
            Modifier
                .clip(CircleShape)
                .background(SystemColors.tint.copy(alpha = 0.15f))
                .clickable(role = Role.Button, onClick = action)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            style = TextStyles.subheadline.medium,
            color = SystemColors.tint,
        )
    }
}

data class TranscriptImagePreview(val id: String, val pages: List<Page>) {
    data class Page(val id: String, val image: ImageBitmap, val label: String)

    constructor(id: String, image: ImageBitmap, label: String) : this(id, listOf(Page(id, image, label)))

    companion object {
        operator fun invoke(attachments: List<OpenCodeImageAttachment>, selectedID: String, label: String): TranscriptImagePreview? {
            val decoded = attachments.mapNotNull { attachment ->
                CachedImage.decoded(attachment.id, attachment.data)?.let { attachment.id to it }
            }
            if (decoded.none { it.first == selectedID }) return null
            return TranscriptImagePreview(
                id = selectedID,
                pages = decoded.mapIndexed { index, image ->
                    Page(id = image.first, image = image.second, label = "$label ${index + 1} of ${decoded.size}")
                },
            )
        }
    }
}

/// Every image a tool read, whole and in order, so it can be read without
/// opening it: justified rows (`JustifiedImageRows`) where each row's images
/// share a height and together fill the width, nothing cropped or hidden.
/// Tapping one opens them all in the native gallery.
@Composable
private fun ToolImageMosaic(images: List<OpenCodeImageAttachment>, onPreview: (String) -> Unit) {
    val decoded = images.map { CachedImage.decoded(it.id, it.data) }
    JustifiedImagesLayout(
        aspects = decoded.map { image ->
            if (image == null || image.width <= 0 || image.height <= 0) 1.0 else image.width.toDouble() / image.height
        },
        modifier = Modifier.semantics { testTag = "tool-image-mosaic" },
    ) {
        images.forEachIndexed { index, attachment ->
            key(attachment.id) { ToolImageTile(attachment, decoded[index], index, images.size, onPreview) }
        }
    }
}

@Composable
private fun ToolImageTile(attachment: OpenCodeImageAttachment, image: ImageBitmap?, index: Int, count: Int, onPreview: (String) -> Unit) {
    val shape = RoundedRectangle(JustifiedImageRows.cornerRadius.dp)
    Box(
        Modifier
            .clickable(role = Role.Button) { onPreview(attachment.id) }
            .semantics { contentDescription = "Open tool image ${index + 1} of $count" }
            .clip(shape)
            // Behind transparent images, and while one decodes.
            .background(SystemColors.secondarySystemBackground)
            // Keeps a white image's edge visible on a white transcript.
            .border(0.5.dp, SystemColors.separator.copy(alpha = 0.6f), shape),
    ) {
        if (image != null) {
            MediaThumbnail(
                image = image,
                ids = listOf(attachment.id),
                modifier = Modifier.fillMaxSize(),
                fits = true,
                cornerRadius = JustifiedImageRows.cornerRadius.dp,
            )
        }
    }
}

/// Lays out images by `JustifiedImageRows`, each at its exact frame.
@Composable
fun JustifiedImagesLayout(aspects: List<Double>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth / density.toDouble() else 360.0
        val rows = JustifiedImageRows(aspects, width)
        fun px(points: Double) = (points * density).roundToInt()
        val placeables = measurables.zip(rows.frames) { measurable, frame ->
            measurable.measure(Constraints.fixed(px(frame.maxX) - px(frame.minX), px(frame.maxY) - px(frame.minY)))
        }
        layout(px(width), px(rows.height)) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(px(rows.frames[index].minX), px(rows.frames[index].minY))
            }
        }
    }
}

/// Justified rows: images keep their shapes, a row's images share one
/// height and fill the width, and the breaks between rows are chosen (in
/// order) to keep every row near a comfortable height. A wide panorama
/// gets a row to itself; a tall screenshot pairs with something square.
/// A row that can't fill the width without growing past the cap (a lone
/// tall screenshot) stays at the cap, leading-aligned.
class JustifiedImageRows(raw: List<Double>, width: Double) {
    data class Frame(val minX: Double, val minY: Double, val width: Double, val height: Double) {
        val maxX: Double get() = minX + width
        val maxY: Double get() = minY + height
    }

    val frames: List<Frame>
    val height: Double

    init {
        val aspects = raw.map { if (it.isFinite() && it > 0) min(max(it, 0.2), 8.0) else 1.0 }
        if (aspects.isEmpty() || width <= 0) {
            frames = emptyList()
            height = 0.0
        } else if (aspects.size == 1) {
            val h = min(width / aspects[0], singleMaxHeight)
            frames = listOf(Frame(0.0, 0.0, min(width, h * aspects[0]), h))
            height = h
        } else {
            val target = width * 0.6
            val maxHeight = width * 0.9

            /// A row's height when it fills the width, and its cost: how far it is
            /// from the target, and a steep price for a row that can't fill.
            fun rowHeight(range: IntRange): Double {
                val gaps = spacing * (range.count() - 1)
                return (width - gaps) / range.sumOf { aspects[it] }
            }
            fun cost(range: IntRange): Double {
                val h = rowHeight(range)
                var c = (h - target) * (h - target)
                if (h > maxHeight) c += (h - maxHeight) * (h - maxHeight) * 20
                if (range.count() > 4) c += 1e9
                return c
            }
            // Best breaks for the first i images, then walk back.
            val n = aspects.size
            val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val from = IntArray(n + 1)
            best[0] = 0.0
            for (end in 1..n) {
                for (start in max(0, end - 4) until end) {
                    if (best[start] == Double.POSITIVE_INFINITY) continue
                    val total = best[start] + cost(start until end)
                    if (total < best[end]) {
                        best[end] = total
                        from[end] = start
                    }
                }
            }
            val ranges = ArrayDeque<IntRange>()
            var end = n
            while (end > 0) {
                ranges.addFirst(from[end] until end)
                end = from[end]
            }
            val laidOut = mutableListOf<Frame>()
            var y = 0.0
            for (range in ranges) {
                val h = min(rowHeight(range), maxHeight)
                var x = 0.0
                for (index in range) {
                    val w = h * aspects[index]
                    laidOut += Frame(x, y, w, h)
                    x += w + spacing
                }
                y += h + spacing
            }
            frames = laidOut
            height = max(0.0, y - spacing)
        }
    }

    companion object {
        const val spacing = 4.0
        const val cornerRadius = 14f

        /// A lone image may stand this tall (a phone screenshot would otherwise
        /// run to two screens).
        const val singleMaxHeight = 520.0
    }
}

@Composable
fun MarkdownImage(
    source: String,
    alt: String,
    server: OpenCodeServer,
    directory: String?,
    onPreview: (TranscriptImagePreview) -> Unit,
) {
    /// A `data:` source can be megabytes, so the key is built once per view value.
    val cacheKey = remember(server, directory, source) { "${server.persistenceID}|${directory ?: ""}|$source" }
    var image by remember(cacheKey) { mutableStateOf(MarkdownImageCache.shared.get(cacheKey)) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    LaunchedEffect(cacheKey) {
        if (image != null) return@LaunchedEffect
        val loaded = loadMarkdownImage(source, server, directory)
        if (loaded == null) {
            failed = true
            return@LaunchedEffect
        }
        MarkdownImageCache.shared.put(cacheKey, loaded)
        image = loaded
    }
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius.dp)
    val loaded = image
    when {
        loaded != null -> Box(
            Modifier
                .fillMaxWidth()
                .background(SystemColors.secondarySystemBackground, shape)
                .clip(shape)
                .clickable(role = Role.Button) {
                    onPreview(TranscriptImagePreview(id = cacheKey, image = loaded, label = alt.ifEmpty { "Image" }))
                }
                .semantics { contentDescription = "Open ${alt.ifEmpty { "image" }}" },
            contentAlignment = Alignment.Center,
        ) {
            MediaThumbnail(image = loaded, ids = listOf(cacheKey), fits = true, maxHeight = 360.dp, cornerRadius = TranscriptMetrics.cardCornerRadius.dp)
        }
        failed -> Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .background(SystemColors.secondarySystemBackground, shape),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemImage("photo.badge.exclamationmark", null, Modifier.symbolSize(TextStyles.footnote), tint = SystemColors.secondaryLabel)
            Text(alt.ifEmpty { "Image unavailable" }, style = TextStyles.footnote, color = SystemColors.secondaryLabel)
        }
        else -> Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .background(SystemColors.secondarySystemBackground, shape),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SystemColors.secondaryLabel) }
    }
}

private suspend fun loadMarkdownImage(source: String, server: OpenCodeServer, directory: String?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (source.startsWith("data:image/")) {
            val comma = source.indexOf(',')
            if (comma < 0 || !source.substring(0, comma).contains(";base64")) return@withContext null
            val data = runCatching { java.util.Base64.getMimeDecoder().decode(source.substring(comma + 1)) }.getOrNull()
                ?: return@withContext null
            return@withContext android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
        }
        val request = server.imageRequest(source, directory) ?: return@withContext null
        runCatching {
            val builder = Request.Builder().url(request.url.absoluteString)
            request.value("Authorization")?.let { builder.header("Authorization", it) }
            MarkdownImageCache.client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body.bytes()
                if (bytes.size > 25 * 1024 * 1024) return@use null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }.getOrNull()
    }

private object MarkdownImageCache {
    val shared = LruCache<String, ImageBitmap>(64)
    val client = OkHttpClient()
}

/// Under a finished reply: copy the whole reply as Markdown.
@Composable
private fun ReplyActions(blocks: List<OpenCodeMarkdownBlock>) {
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_500)
        copied = false
    }
    Box(
        Modifier
            // The glyph, not its tap target, lines up with the reply's text.
            .offset(x = (-13).dp)
            .width(44.dp)
            .rowHitTarget(top = TranscriptMetrics.flowHalf, bottom = TranscriptMetrics.turnHalf) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Reply", OpenCodeMarkdownBlock.markdown(blocks)))
                if (!copied) haptics.impact()
                copied = true
            }
            .semantics(mergeDescendants = true) {
                contentDescription = if (copied) "Copied" else "Copy reply"
                testTag = "reply-copy"
            },
        contentAlignment = Alignment.Center,
    ) {
        SystemImage(
            if (copied) "checkmark" else "square.on.square",
            null,
            Modifier.symbolSize(TextStyles.subheadline),
            tint = SystemColors.secondaryLabel,
        )
    }
}
