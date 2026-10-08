@file:PortedFrom("OpenCode/Transcript/TranscriptUserMessage.swift", "8adf650")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.RoundedRectangle
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemContextMenu
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Store
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor

/// One user message, whether still pending (steering or queued, from the
/// inbox) or delivered (a transcript user group). Both render through
/// `TranscriptUserMessageView` so a pending row promotes in place: the same
/// view brightens and its label fades, instead of one row replacing another.
data class TranscriptUserMessage(
    var id: String,
    var text: String,
    var images: List<OpenCodeImageAttachment>,
    var files: List<OpenCodeTranscriptFile>,
    /// Set while the message waits in the inbox.
    var pending: OpenCodeDelivery?,
    /// The voice agent sent it on the user's behalf: a Voice message, with the
    /// prompt one long press away.
    var voiceDelegation: VoiceHandoff.Delegation? = VoiceHandoff.parse(text),
    /// A location shared from the + menu, drawn as a card above the text.
    var location: SharedLocation? = SharedLocation.parse(text)?.location,
    /// The text to show: the prompt without its location block.
    var displayText: String = SharedLocation.parse(text)?.remainder ?: text,
) {
    companion object {
        /// A user group made of exactly one prompt's text, images, and files.
        operator fun invoke(entry: TranscriptEntry): TranscriptUserMessage? {
            val group = entry as? TranscriptEntry.userGroup ?: return null
            val id = entry.userMessageID ?: return null
            var text = ""
            var images = emptyList<OpenCodeImageAttachment>()
            var files = emptyList<OpenCodeTranscriptFile>()
            for (item in group.items) {
                if (TranscriptEntry.promptID(forItem = item) != id) return null
                when (item) {
                    is OpenCodeTranscriptItem.userText -> text = item.text
                    is OpenCodeTranscriptItem.userImages -> images = item.images
                    is OpenCodeTranscriptItem.userFiles -> files = item.files
                    else -> return null
                }
            }
            return TranscriptUserMessage(id = id, text = text, images = images, files = files, pending = null)
        }

        /// A plain pending message; compaction and notices keep their own rows.
        operator fun invoke(queued: OpenCodeQueuedInput): TranscriptUserMessage? {
            if (queued.isCompaction || queued.notice != null) return null
            return TranscriptUserMessage(
                id = queued.id,
                text = queued.text,
                images = queued.images,
                files = queued.files.mapIndexed { index, file ->
                    OpenCodeTranscriptFile(id = "${queued.id}-file-$index", name = file.name, mime = file.mime, path = file.path)
                },
                pending = queued.delivery,
            )
        }
    }
}

@Composable
fun TranscriptUserMessageView(
    message: TranscriptUserMessage,
    onPreviewImage: (TranscriptImagePreview) -> Unit = {},
    onOpenFile: ((AgentFileReference) -> Unit)? = null,
) {
    val delegation = message.voiceDelegation
    if (delegation != null) {
        TranscriptVoiceMessageView(delegation = delegation, prompt = message.text, pending = message.pending)
    } else {
        UserMessage(message, onPreviewImage, onOpenFile)
    }
}

@Composable
private fun UserMessage(
    message: TranscriptUserMessage,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
    onOpenFile: ((AgentFileReference) -> Unit)?,
) {
    val reducedMotion = LocalReduceMotion.current
    val isPending = message.pending != null
    // Opacity rather than a secondary style, so promotion can animate it.
    val dimming by animateFloatAsState(
        if (isPending) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(350, easing = EaseOut),
        label = "pending",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { stateDescription = message.pending?.pendingLabel ?: "" },
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp),
        horizontalAlignment = Alignment.End,
    ) {
        val pending = message.pending
        AnimatedVisibility(
            visible = pending != null,
            enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(350, easing = EaseOut)),
            exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(350, easing = EaseOut)),
        ) {
            if (pending != null) {
                TranscriptPendingLabel(
                    pending,
                    Modifier
                        // Its text lines up with the bubble's.
                        .padding(end = TranscriptMetrics.contentGutter.dp)
                        .trimBottom(2),
                )
            }
        }
        if (message.images.isNotEmpty()) UserImages(message.images, isPending, dimming, onPreviewImage)
        if (message.files.isNotEmpty()) {
            Box(Modifier.alpha(1f - 0.4f * dimming)) {
                TranscriptFileChips(files = message.files, onOpen = if (isPending) null else onOpenFile)
            }
        }
        val location = message.location
        if (location != null) {
            SharedLocationCard(location = location, isPending = isPending)
        }
        if (message.displayText.isNotEmpty()) {
            TranscriptUserBubble(
                text = message.displayText,
                dimming = dimming,
                itemID = if (isPending) null else message.id,
            )
        }
    }
}

@Composable
private fun UserImages(
    images: List<OpenCodeImageAttachment>,
    isPending: Boolean,
    dimming: Float,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
) {
    val side = if (images.size > 1) 130.dp else 200.dp
    val scroll = rememberScrollState()
    LaunchedEffect(images.size) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(1f - 0.4f * dimming)
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp, Alignment.End),
    ) {
        images.forEachIndexed { index, attachment ->
            Box(
                Modifier
                    .size(side)
                    .clip(RoundedCornerShape(TranscriptMetrics.cardCornerRadius.dp))
                    .clickable(enabled = !isPending, role = Role.Button) {
                        val preview = TranscriptImagePreview(attachments = images, selectedID = attachment.id, label = "Attached image")
                        if (preview != null) onPreviewImage(preview)
                    }
                    .semantics {
                        contentDescription = if (images.size == 1) "Open attached image" else "Open attached image ${index + 1} of ${images.size}"
                    },
            ) {
                CachedImage.decoded(attachment.id, attachment.data)?.let { image ->
                    MediaThumbnail(
                        image = image,
                        ids = listOf(attachment.id),
                        modifier = Modifier.size(side),
                        cornerRadius = TranscriptMetrics.cardCornerRadius.dp,
                    )
                }
            }
        }
    }
}

/// A transcript entry or a pending inbox row. A plain user message from
/// either source takes the same branch, so promotion animates in place.
@Composable
fun TranscriptMessageRow(
    entry: TranscriptEntry?,
    queued: OpenCodeQueuedInput?,
    store: Store<SessionFeature.State, SessionFeature.Action>,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
    onOpenFile: (AgentFileReference) -> Unit,
    disclosure: TranscriptDisclosureState,
) {
    val userMessage = if (entry != null) TranscriptUserMessage(entry) else queued?.let { TranscriptUserMessage(it) }
    val insets = entry?.rowInsets ?: queued?.rowInsets ?: TranscriptRowInsets(TranscriptMetrics.turnHalf, TranscriptMetrics.turnHalf)
    TopAnchoredRow {
        Box(Modifier.padding(top = insets.top.dp, bottom = insets.bottom.dp)) {
            when {
                userMessage != null ->
                    TranscriptUserMessageView(message = userMessage, onPreviewImage = onPreviewImage, onOpenFile = onOpenFile)
                entry != null ->
                    TranscriptEntryView(
                        entry = entry,
                        store = store,
                        onPreviewImage = onPreviewImage,
                        onOpenFile = onOpenFile,
                        disclosure = disclosure,
                    )
                queued != null -> TranscriptQueuedInputView(input = queued)
            }
        }
    }
}

/// Lays a row out at its own height and pins it to the top of its cell.
/// While the list animates a cell to a new height, the hosted content is
/// already laid out for the new one; centred in the old frame it would jump
/// up and slide back. Pinned, it stays put while the cell's edge moves.
@Composable
private fun TopAnchoredRow(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minHeight = 0)) ?: return@Layout layout(0, 0) {}
        // Self-sizing asks with no height; rendering proposes the cell's.
        val height = if (constraints.hasBoundedHeight && constraints.minHeight == constraints.maxHeight) constraints.maxHeight else placeable.height
        layout(if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width, height) {
            placeable.place(0, 0)
        }
    }
}

/// "↗ Steering" or "Queued" above a pending message.
@Composable
private fun TranscriptPendingLabel(delivery: OpenCodeDelivery, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SystemImage(delivery.symbolName, null, Modifier.symbolSize(TextStyles.caption), tint = SystemColors.secondaryLabel)
        Text(delivery.pendingLabel, style = TextStyles.caption, color = SystemColors.secondaryLabel, maxLines = 1)
    }
}

/// Places the bubble at the trailing edge within `fraction` of the width.
@Composable
private fun TrailingFractionLayout(fraction: Float, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val available = constraints.maxWidth
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, maxWidth = (available * fraction).toInt()))
        layout(available, placeable.height) {
            placeable.place(available - placeable.width, 0)
        }
    }
}

@Composable
private fun TranscriptUserBubble(
    text: String,
    /// Dimmed while the message waits in the inbox: 1 pending, 0 delivered.
    dimming: Float = 0f,
    itemID: String? = null,
) {
    TrailingFractionLayout(TranscriptMetrics.maximumUserBubbleFraction) {
        SelectionContainer { Bubble(text, dimming, itemID) }
    }
}

@Composable
private fun Bubble(text: String, dimming: Float, itemID: String?) {
    val shape = RoundedRectangle(TranscriptMetrics.bubbleCornerRadius.dp)
    Box(
        Modifier
            .then(if (itemID != null) Modifier.sendLandingProbe(itemID) else Modifier)
            .background(SystemColors.secondarySystemBackground.copy(alpha = 1f - 0.45f * dimming), shape)
            .padding(horizontal = TranscriptMetrics.contentGutter.dp, vertical = TranscriptMetrics.bubbleVertical.dp),
    ) {
        BubbleText(text, Modifier.alpha(1f - 0.4f * dimming))
    }
}

/// `Text(text).font(.body).lineSpacing(2)`. Like SwiftUI's wrapped text, it
/// is as wide as its widest line rather than as wide as it may be, so the
/// bubble around it fits the words.
@Composable
private fun BubbleText(text: String, modifier: Modifier = Modifier) {
    // Written while the text measures and read right after, in the same pass.
    val laid = remember { arrayOfNulls<TextLayoutResult>(1) }
    Text(
        text,
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val lines = laid[0]?.takeIf { it.lineCount > 0 } ?: return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            val left = floor((0 until lines.lineCount).minOf { lines.getLineLeft(it) }).toInt()
            val right = ceil((0 until lines.lineCount).maxOf { lines.getLineRight(it) }).toInt()
            layout((right - left).coerceIn(constraints.minWidth, placeable.width), placeable.height) { placeable.place(-left, 0) }
        },
        style = TextStyles.body.copy(lineHeight = (TranscriptMetrics.bodyLine + 2).sp),
        color = SystemColors.label,
        onTextLayout = { laid[0] = it },
    )
}

/// A Voice message's bubble. The Voice indicator above it is what sets it
/// apart; backgrounds that also differ were compared
/// (`prototypes/voice-delegation-display/#voice-message-variants`) and set
/// aside, so the treatment stays in this one place.
enum class VoiceMessageStyle {
    /// The user's own bubble: same fill, no outline.
    standard,

    /// No fill, a hairline outline.
    outlined,
    ;

    companion object {
        val current = standard
    }
}

@Composable
private fun Modifier.voiceMessageBackground(style: VoiceMessageStyle, dimming: Float): Modifier {
    val shape = RoundedRectangle(TranscriptMetrics.bubbleCornerRadius.dp)
    return when (style) {
        // Matches `TranscriptUserBubble`, pending dimming included.
        VoiceMessageStyle.standard -> background(SystemColors.secondarySystemBackground.copy(alpha = 1f - 0.45f * dimming), shape)
        VoiceMessageStyle.outlined -> border(1.dp, SystemColors.separator.copy(alpha = 1f - 0.45f * dimming), shape)
    }
}

/// The voice agent prompting the session on the user's behalf, in its own
/// framing: the user's bubble (`VoiceMessageStyle`) under a small Voice
/// indicator, which is all that marks it. The prompt itself (the user's words
/// and the call's transcript) is a long press away.
@Composable
fun TranscriptVoiceMessageView(
    delegation: VoiceHandoff.Delegation,
    /// The full prompt as the session received it.
    prompt: String,
    pending: OpenCodeDelivery? = null,
    style: VoiceMessageStyle = VoiceMessageStyle.current,
) {
    val reducedMotion = LocalReduceMotion.current
    val context = LocalContext.current
    val dimming by animateFloatAsState(
        if (pending != null) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(350, easing = EaseOut),
        label = "pending",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Voice message"
                stateDescription = listOfNotNull(delegation.displayText, pending?.pendingLabel).joinToString(", ")
                testTag = "voice-message"
                customActions = listOf(
                    CustomAccessibilityAction("Copy") { copy(context, delegation.displayText); true },
                    CustomAccessibilityAction("Copy Full Message") { copy(context, prompt); true },
                )
            },
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            Modifier.padding(end = TranscriptMetrics.contentGutter.dp),
            horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceIndicatorLabel()
            AnimatedVisibility(
                visible = pending != null,
                enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(350, easing = EaseOut)),
                exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(350, easing = EaseOut)),
            ) {
                if (pending != null) TranscriptPendingLabel(pending)
            }
        }
        TrailingFractionLayout(TranscriptMetrics.maximumUserBubbleFraction) {
            // Long press opens the menu, not text selection.
            SystemContextMenu(
                sections = listOf(
                    SystemMenuSection(
                        listOf(
                            SystemMenuItem("Copy", systemImage = "square.on.square") { copy(context, delegation.displayText) },
                            SystemMenuItem("Copy Full Message", systemImage = "doc.plaintext") { copy(context, prompt) },
                        ),
                    ),
                ),
            ) {
                Box(
                    Modifier
                        .voiceMessageBackground(style, dimming)
                        .padding(horizontal = TranscriptMetrics.contentGutter.dp, vertical = TranscriptMetrics.bubbleVertical.dp),
                ) {
                    BubbleText(delegation.displayText, Modifier.alpha(1f - 0.4f * dimming))
                }
            }
        }
    }
}

/// The glyph a touch smaller than its word, as in the pending label.
@Composable
private fun VoiceIndicatorLabel() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SystemImage("waveform", null, Modifier.symbolSize(TextStyles.caption2.semibold), tint = SystemColors.secondaryLabel)
        Text("Voice", style = TextStyles.caption, color = SystemColors.secondaryLabel, maxLines = 1)
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
}

/// SwiftUI's negative bottom padding: the next view sits `points` closer.
private fun Modifier.trimBottom(points: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, maxOf(0, placeable.height - points.dp.roundToPx())) { placeable.place(0, 0) }
}
