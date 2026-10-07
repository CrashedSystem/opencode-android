@file:PortedFrom("OpenCode/FileAttachmentViews.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.semibold
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

@Composable
fun FileAttachmentCard(
    name: String,
    symbolName: String,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp? = null,
    detailLines: Int = 1,
    trailingSymbolName: String? = null,
    detail: @Composable () -> Unit,
) {
    Row(
        modifier
            .width(width)
            .then(if (height != null) Modifier.heightIn(min = height) else Modifier)
            .padding(horizontal = TranscriptMetrics.standard.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SystemImage(symbolName, null, Modifier.size(28.dp), tint = SystemColors.secondaryLabel)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = TextStyles.subheadline.medium,
                color = SystemColors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.ProvideTextStyle(TextStyles.caption.copy(color = SystemColors.secondaryLabel)) {
                detail()
            }
        }
        if (trailingSymbolName != null) {
            SystemImage(
                trailingSymbolName,
                null,
                Modifier.size(12.dp),
                tint = SystemColors.tertiaryLabel,
            )
        }
    }
}

@Composable
fun TranscriptFileChips(
    files: List<OpenCodeTranscriptFile>,
    onOpen: ((AgentFileReference) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(files) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp, Alignment.End),
    ) {
        for (file in files) {
            val card = @Composable {
                FileAttachmentCard(name = file.name, symbolName = file.symbolName) {
                    Text(file.typeLabel, maxLines = 1)
                }
            }
            val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius.dp)
            val path = file.path
            if (onOpen != null && path != null) {
                Box(
                    Modifier
                        .clip(shape)
                        .background(SystemColors.secondarySystemBackground)
                        .clickable { onOpen(AgentFileReference(path)) }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Attached file ${file.name}"
                        },
                ) { card() }
            } else {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .clip(shape)
                        .background(SystemColors.secondarySystemBackground)
                        .semantics { contentDescription = "Attached file ${file.name}" },
                ) { card() }
            }
        }
    }
}

@Composable
fun AgentFileCards(
    files: List<AgentFileReference>,
    onOpen: (AgentFileReference) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp),
    ) {
        for (file in files) {
            val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius.dp)
            Box(
                Modifier
                    .clickable { onOpen(file) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Open ${file.name}"
                    },
            ) {
                FileAttachmentCard(
                    name = file.name,
                    symbolName = file.symbolName,
                    width = if (files.size == 1) 260.dp else 220.dp,
                    trailingSymbolName = if (file.kind.isTimeBased) "play.fill" else "chevron.right",
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(shape)
                        .background(SystemColors.secondarySystemBackground),
                ) {
                    Text(file.typeLabel, maxLines = 1)
                }
            }
        }
    }
}

object ComposerFileChip {
    val height = 76.dp
    val width = 220.dp

    @Composable
    operator fun invoke(
        file: ComposerFileAttachment,
        canRetry: Boolean,
        onRetry: () -> Unit,
    ) {
        val shape = RoundedCornerShape(22.dp)
        val card = @Composable {
            FileAttachmentCard(
                name = file.name,
                symbolName = AttachmentFileType.symbolName(file.name, file.mime),
                width = width,
                height = height,
                detailLines = if (file.isFailed) 2 else 1,
                modifier = Modifier.background(SystemColors.secondarySystemFill, shape),
            ) {
                when (val status = file.status) {
                    is ComposerFileAttachment.Status.uploading -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Uploading ${percent(file.progress)}")
                        LinearProgressIndicator(progress = { file.progress.toFloat() }, modifier = Modifier.width(120.dp))
                    }
                    is ComposerFileAttachment.Status.uploaded -> Text(subtitle(file))
                    is ComposerFileAttachment.Status.failed -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SystemImage(
                            if (canRetry) "arrow.clockwise" else "exclamationmark.triangle",
                            null,
                            Modifier.size(12.dp),
                            tint = SystemColors.systemRed,
                        )
                        Text(status.message, color = SystemColors.systemRed, maxLines = 2)
                    }
                }
            }
        }
        val label = if (file.isFailed && canRetry) "Retry uploading ${file.name}" else "File attachment ${file.name}"
        if (file.isFailed && canRetry) {
            Box(Modifier.clickable(onClick = onRetry).semantics { contentDescription = label }) {
                card()
            }
        } else {
            androidx.compose.foundation.layout.Box(Modifier.semantics { contentDescription = label }) { card() }
        }
    }

    fun subtitle(file: ComposerFileAttachment) = AttachmentFileType.subtitle(file.name, file.mime, file.size)

    fun percent(value: Double): String {
        val format = NumberFormat.getPercentInstance()
        format.maximumFractionDigits = 0
        return format.format(value)
    }
}
