@file:PortedFrom("OpenCode/Transcript/TranscriptListRows.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ContentUnavailableView
import ai.opencode.mobilecode.system.RoundedRectangle
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.onAppear
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

@Composable
fun TranscriptHistoryStatusView(isLoading: Boolean, error: String?, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = TranscriptMetrics.standard.dp),
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            // Every open pages once to learn whether history exists; healthy
            // round trips should not flash activity into a settled transcript.
            DelayedProgressView(Modifier.semantics { contentDescription = "Loading earlier messages" })
        } else if (error != null) {
            Text(error, style = TextStyles.footnote, color = SystemColors.secondaryLabel)
            Text(
                "Retry",
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = retry)
                    .wrapContentHeight(Alignment.CenterVertically),
                style = TextStyles.footnote,
                color = SystemColors.label,
            )
        } else {
            Box(Modifier.height(1.dp).semantics { hideFromAccessibility() })
        }
    }
}

/// A spinner that only appears once a wait has lasted long enough to notice.
@Composable
fun DelayedProgressView(modifier: Modifier = Modifier, delay: Duration = 400.milliseconds) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delay)
        isVisible = true
    }
    CircularProgressIndicator(
        modifier.size(20.dp).alpha(if (isVisible) 1f else 0f),
        strokeWidth = 2.dp,
        color = SystemColors.secondaryLabel,
    )
}

@Composable
fun TranscriptEmptyView() {
    ContentUnavailableView(
        "No messages yet",
        systemImage = "bubble.left.and.bubble.right",
        description = "Start the conversation below.",
        modifier = Modifier.padding(vertical = TranscriptMetrics.headerBand.dp),
    )
}

@Composable
fun TranscriptDraftSetupView(store: Store<SessionFeature.State, SessionFeature.Action>) {
    val draftProject = store.state.draftProject
    val currentModelName = store.state.currentModelName
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp),
    ) {
        SetupRow(
            "Project",
            systemImage = "folder",
            value = draftProject?.name ?: "Choose a project",
            detail = draftProject?.directory,
            isPlaceholder = draftProject == null,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Project"
                stateDescription = draftProject?.name ?: "None chosen"
            },
        ) { store.send(SessionFeature.Action.projectButtonTapped) }

        SetupRow(
            "Model",
            systemImage = "cpu",
            value = currentModelName,
            detail = null,
            isPlaceholder = store.state.currentModel == null,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Model"
                stateDescription = currentModelName
            },
        ) { store.send(SessionFeature.Action.modelButtonTapped) }
    }
    store.onAppear { LabMarks.mark("draft_first_frame") }
}

@Composable
private fun SetupRow(
    title: String,
    systemImage: String,
    value: String,
    detail: String?,
    isPlaceholder: Boolean,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val shape = RoundedRectangle(TranscriptMetrics.cardCornerRadius.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(SystemColors.secondarySystemBackground)
            .clickable(role = Role.Button, onClick = action)
            .padding(horizontal = 14.dp, vertical = TranscriptMetrics.standard.dp),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            SystemImage(systemImage, null, Modifier.symbolSize(TextStyles.body), tint = SystemColors.secondaryLabel)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.compact.dp)) {
            Text(title, style = TextStyles.footnote, color = SystemColors.secondaryLabel)
            Text(
                value,
                style = TextStyles.body.medium,
                color = if (isPlaceholder) SystemColors.tint else SystemColors.label,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = TextStyles.caption,
                    color = SystemColors.tertiaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        SystemImage("chevron.up.chevron.down", null, Modifier.symbolSize(TextStyles.caption.semibold), tint = SystemColors.tertiaryLabel)
    }
}

/// Pending compaction and server notices; plain pending messages render
/// through `TranscriptUserMessageView`.
@Composable
fun TranscriptQueuedInputView(input: OpenCodeQueuedInput) {
    val notice = input.notice
    if (input.isCompaction) {
        // Admitted but not started; the durable row takes over while running.
        TranscriptRule(
            labelStyle = SystemColors.tertiaryLabel,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Compaction queued" },
        ) {
            SystemImage("diamond", null, Modifier.symbolSize(TextStyles.caption2), tint = SystemColors.tertiaryLabel)
            Text("Compaction queued")
        }
    } else if (notice != null) {
        Text(
            notice,
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = notice
                    stateDescription = "Queued"
                },
            style = TextStyles.footnote,
            color = SystemColors.secondaryLabel,
        )
    }
}
