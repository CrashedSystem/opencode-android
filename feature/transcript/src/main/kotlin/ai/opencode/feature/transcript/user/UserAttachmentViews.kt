package ai.opencode.feature.transcript.user

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.mediaZoomSource
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.TranscriptAction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.FileAttachmentCard
import ai.opencode.feature.media.FileAttachmentDetail
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.PastedText
import ai.opencode.server.model.PastedTextSummary
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The fill iOS gives cards (`secondarySystemBackground`): the user bubble's colour. */
@Composable
internal fun cardFill(): Color = OpenCodeTheme.colors.userBubble

/**
 * Sent files on a user message, trailing like the message's images. An uploaded file opens in
 * the viewer from the server's copy (iOS `Composer/FileAttachmentViews.swift`
 * `TranscriptFileChips`, with media's `FileAttachmentCard`).
 */
@Composable
internal fun TranscriptFileChips(
    files: List<OpenCodeTranscriptFile>,
    onOpen: ((AgentFileReference) -> Unit)?,
    trailingCorners: UserSurfaceCorners = UserSurfaceCorners.round,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true),
            horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
        ) {
            for (file in files) {
                val corners = if (file.id == files.last().id) trailingCorners else UserSurfaceCorners.round
                val text = file.text
                val summary = file.pasted
                if (text != null && summary != null) {
                    PastedTextCard(file.id, text, summary, corners)
                } else if (file.isUnavailable || file.mediaHash != null) {
                    TranscriptMediaFileChip(file, corners)
                } else {
                    FileChip(file, onOpen, corners)
                }
            }
        }
    }
}

@Composable
private fun FileChip(file: OpenCodeTranscriptFile, onOpen: ((AgentFileReference) -> Unit)?, corners: UserSurfaceCorners) {
    val typeLabel = AttachmentFileType.label(file.name, file.mime)
    val path = file.path
    val shape = corners.shape(TranscriptMetrics.cardCornerRadius)
    val open = if (onOpen != null && path != null) ({ onOpen(AgentFileReference(path)) }) else null
    FileAttachmentCard(
        name = file.name,
        symbolName = AttachmentFileType.symbolName(file.name, file.mime),
        modifier = Modifier
            .then(
                if (path != null && open != null) {
                    Modifier.transcriptDragSource(
                        TranscriptDragPayload.AgentFile(AgentFileReference(path), mime = file.mime, displayName = file.name),
                        cornerRadius = TranscriptMetrics.cardCornerRadius,
                    )
                } else Modifier,
            )
            .clip(shape)
            .background(cardFill(), shape)
            .then(if (open != null) Modifier.clickable(onClickLabel = "Open the file", onClick = open) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "Attached file ${file.name}"
                stateDescription = typeLabel
            },
    ) { FileAttachmentDetail(typeLabel) }
}

/**
 * A long paste's card: its name, length and first lines fading out (iOS `PastedTextCard`). A tap
 * opens the whole text in the Pasted Text viewer, zooming out of the card (its zoom source is
 * `pasted-<file id>`); the session presents it for [TranscriptAction.OpenPastedText].
 */
@Composable
internal fun PastedTextCard(id: String, text: String, summary: PastedTextSummary, corners: UserSurfaceCorners) {
    val shape = corners.shape(TranscriptMetrics.cardCornerRadius)
    val send = LocalTranscriptRowContext.current.send
    val sourceID = "pasted-$id"
    val open = { send(TranscriptAction.OpenPastedText(text, summary, sourceID)) }
    Column(
        Modifier
            .transcriptDragSource(TranscriptDragPayload.PastedText(text, PastedText.NAME), cornerRadius = TranscriptMetrics.cardCornerRadius)
            .width(240.dp)
            .clip(shape)
            .background(cardFill(), shape)
            .mediaZoomSource(sourceID, TranscriptMetrics.cardCornerRadius)
            .clickable(onClickLabel = "Open the text", onClick = open)
            .padding(horizontal = TranscriptMetrics.standard, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = PastedText.NAME
                stateDescription = summary.summary
                role = Role.Button
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon("doc.plaintext", contentDescription = null, size = smallGlyph(OpenCodeTextStyles.subheadline) + 2.dp, tint = OpenCodeTheme.colors.secondaryText)
            Text(
                PastedText.NAME,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f).width(6.dp))
            Text(
                summary.length,
                style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"),
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
            )
        }
        // The text goes on past the card.
        Column(
            Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Black.copy(alpha = 0.25f)),
                        blendMode = BlendMode.DstIn,
                    )
                },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            for (line in summary.preview) {
                Text(
                    line,
                    style = OpenCodeTextStyles.caption2.copy(fontFamily = FontFamily.Monospace),
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A shared location's card: a map, its place and address, and its coordinates (iOS
 * `SharedLocationCard`, the sent card only). Tapping opens the maps link.
 */
@Composable
internal fun SharedLocationCard(
    location: SharedLocation,
    isPending: Boolean,
    corners: UserSurfaceCorners,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = location.place ?: location.address ?: "Shared Location"
    val subtitle = if (location.place != null) location.address else null
    val shape = corners.shape(TranscriptMetrics.cardCornerRadius)
    val blue = Color(0xFF0A84FF)
    Column(
        modifier
            .width(260.dp)
            .graphicsLayer { alpha = if (isPending) 0.6f else 1f }
            .clip(shape)
            .background(cardFill())
            .clickable(onClickLabel = "Open in Maps") { onOpen(location.mapsURL) }
            .clearAndSetSemantics {
                contentDescription = "Location: $title${subtitle?.let { ", $it" } ?: ""}"
                role = Role.Button
                onClick("Open in Maps") { onOpen(location.mapsURL); true }
            },
    ) {
        // iOS `SharedLocationMap`: a still map under the pin. Android has no MapKit snapshot (the
        // composer's `LocationClient.snapshot` seam is empty too), so the tile and pin alone.
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onError, CircleShape))
            OpenCodeIcon(SFSymbol.MappinCircleFill, contentDescription = null, size = OpenCodeIconSize.large, tint = MaterialTheme.colorScheme.error)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 0.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OpenCodeIcon("location.fill", contentDescription = null, size = smallGlyph(OpenCodeTextStyles.caption1), tint = blue)
                Text(
                    title,
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = OpenCodeTheme.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(subtitle, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                location.coordinateText + (location.accuracyText?.let { " · $it" } ?: ""),
                style = OpenCodeTextStyles.caption2.copy(fontFamily = FontFamily.Monospace),
                color = OpenCodeTheme.colors.tertiaryText,
                maxLines = 1,
            )
        }
    }
}

/**
 * A message's images, trailing: 200 alone, 130 each when several; up to three fit the column,
 * more scroll from the trailing end (iOS `TranscriptUserMessageView.images`).
 */
@Composable
internal fun UserMessageImages(
    messageID: String?,
    images: List<OpenCodeImageAttachment>,
    isPending: Boolean,
    trailing: UserSurfaceCorners,
    onPreview: (TranscriptImagePreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val side = if (images.size > 1) 130.dp else 200.dp
    val strip: @Composable (Modifier) -> Unit = { stripModifier ->
        Row(stripModifier, horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered)) {
            // By position: a steering message's images are the phone's copies (local IDs) until
            // it's delivered, and keeping the views through that swap lets them undim and move.
            images.forEachIndexed { index, attachment ->
                // Only the strip's trailing image meets the run's other surfaces.
                val corners = if (index == images.size - 1) trailing else UserSurfaceCorners.round
                UserImageAttachmentTile(messageID, images, attachment, index, side, corners, isPending, onPreview)
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (isPending) 0.6f else 1f },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (images.size <= 3) strip(Modifier) else strip(Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true))
    }
}
