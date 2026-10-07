package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Store
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/// Stands in for the session screen until `SessionView.kt` (the session
/// worker's port of `SessionView.swift`) merges; delete this file then. It
/// has the real signature and draws the header, the transcript's text, and
/// the composer's place from the store, sending nothing, so Home's drawer and
/// Catch Up can host a card meanwhile.
@Composable
fun SessionView(
    store: Store<SessionFeature.State, SessionFeature.Action>,
    modifier: Modifier = Modifier,
    onOpenSessions: () -> Unit = {},
    onCatchUpDone: (() -> Unit)? = null,
    onCatchUpOpen: (() -> Unit)? = null,
    listContext: SessionListContext = SessionListContext(),
) {
    val state = store.state
    val session = state.session
    Column(
        modifier
            .fillMaxSize()
            .background(SystemColors.systemBackground)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCatchUpDone == null) DrawerControl(listContext.elsewhere, onOpenSessions)
            if (session != null && session.title.isNotEmpty()) {
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .then(if (onCatchUpOpen != null) Modifier.clickable(role = Role.Button, onClick = onCatchUpOpen) else Modifier),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(session.title, Modifier.weight(1f, fill = false), style = TextStyles.headline, color = SystemColors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (onCatchUpOpen != null) {
                            SystemImage("chevron.right", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.caption2), tint = SystemColors.tertiaryLabel)
                        }
                    }
                    listContext.project?.let { Text(it.name, style = TextStyles.caption, color = SystemColors.secondaryLabel, maxLines = 1) }
                }
            }
            if (session == null || session.title.isEmpty()) Spacer(Modifier.weight(1f))
            if (onCatchUpDone != null) {
                FloatingControl("checkmark", "Mark as read and continue", onCatchUpDone)
            } else if (session != null) {
                FloatingControl("ellipsis", "More") {}
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                when (item) {
                    is OpenCodeTranscriptItem.userText -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            item.text,
                            Modifier
                                .widthIn(max = 300.dp)
                                .background(SystemColors.secondarySystemBackground, RoundedCornerShape(24.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            style = TextStyles.body,
                            color = SystemColors.label,
                        )
                    }
                    is OpenCodeTranscriptItem.assistantMarkdown ->
                        MarkdownBlocksView(blocks = item.blocks, server = state.server, directory = session?.directory)
                    is OpenCodeTranscriptItem.info -> Text(item.text, style = TextStyles.body, color = SystemColors.secondaryLabel)
                    else -> Unit
                }
            }
        }
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = if (onCatchUpDone == null) 0.dp else CatchUpMetrics.composerClearance.calculateBottomPadding())
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .glassEffect(GlassSurface.Capsule, interactive = true)
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemImage("plus", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.label)
            Text("Message", style = TextStyles.body, color = SystemColors.placeholderText)
        }
    }
}

/// The drawer button: two bars in a glass circle, with a dot when another
/// session needs input or is unread.
@Composable
private fun DrawerControl(badge: DrawerBadge?, onOpenSessions: () -> Unit) {
    val label = SystemColors.label
    Box(
        Modifier
            .size(44.dp)
            .glassEffect(GlassSurface.Circle, interactive = true)
            .clickable(role = Role.Button, onClick = onOpenSessions)
            .semantics { contentDescription = "Open sessions" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 18.dp, height = 10.4.dp)) {
            val stroke = 2.5.dp.toPx()
            val long = size.width
            drawRoundRect(label, Offset.Zero, Size(long, stroke), CornerRadius(stroke / 2))
            drawRoundRect(label, Offset(0f, size.height - stroke), Size(long * 0.6f, stroke), CornerRadius(stroke / 2))
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-3).dp, y = 3.dp)
                    .size(10.dp)
                    .background(if (badge == DrawerBadge.attention) SystemColors.systemOrange else SystemColors.systemBlue, CircleShape),
            )
        }
    }
}

@Composable
private fun FloatingControl(systemImage: String, label: String, action: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .glassEffect(GlassSurface.Circle, interactive = true)
            .clickable(role = Role.Button, onClick = action)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        SystemImage(systemImage, contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body.semibold), tint = SystemColors.label)
    }
}
