package ai.opencode.feature.drawer.list

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.InboxSidebarFeature
import ai.opencode.feature.drawer.InboxSidebarRow
import ai.opencode.feature.drawer.catchUpSessions
import ai.opencode.feature.drawer.inboxSidebarRows
import ai.opencode.feature.drawer.motion.DrawerPanRegions
import ai.opencode.feature.drawer.motion.drawerPanRow
import ai.opencode.feature.drawer.style.DrawerHeaderScrollState
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.style.drawerSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * iOS `InboxSidebarList.swift` (Inbox Sidebar, experimental): the drawer's sessions as an inbox,
 * one item per session. Tapping opens the session; swiping toward the trailing side marks it
 * read (unread rows only), toward the leading side archives it. iOS's `List` swipe actions →
 * M3 `SwipeToDismissBox`, released past its threshold; the row's drag is its own, not the
 * drawer's (`drawerPanRow`, iOS `DirectionalPanGestureRecognizer.rowsOwnSwipes`).
 */
@Composable
internal fun InboxSidebarList(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    selectedSessionID: String?,
    headerScroll: DrawerHeaderScrollState,
    panRegions: DrawerPanRegions,
    contentPadding: PaddingValues,
    select: (String) -> Unit,
    openCatchUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by store.observe { it.inboxSidebarRows() }
    val catchUpCount by store.observe { it.catchUpSessions.size }
    val archiveError by store.observe { it.inboxSidebarStatus.archiveError }
    val snapshotLoaded by store.observe { it.snapshotLoaded }
    // iOS `TimelineView(.everyMinute)`: ages tick over on the minute.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - now.toEpochMilli() % 60_000L)
            now = Instant.now()
        }
    }
    val listState = rememberLazyListState()
    // As in DrawerListView: a list resting at its top stays there when a row arrives above the
    // first one (the archive error, Catch Up, a session that just got busy), as iOS's List keeps
    // its zero offset; LazyColumn would keep the old first row anchored by key and leave the new
    // ones scrolled under the header.
    val restsAtTop = remember { booleanArrayOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { restsAtTop[0] = it }
    }
    val firstKey = when {
        archiveError != null -> "inbox-error"
        catchUpCount > 0 -> "inbox-catch-up"
        else -> rows.firstOrNull()?.id
    }
    val shownFirstKey = remember { arrayOfNulls<String>(1) }
    if (shownFirstKey[0] != firstKey) {
        if (shownFirstKey[0] != null && restsAtTop[0]) listState.requestScrollToItem(0, 0)
        shownFirstKey[0] = firstKey
    }
    val density = LocalDensity.current
    // The header's edge follows how far rows have scrolled beneath the title.
    LaunchedEffect(listState, headerScroll) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, px) ->
            val fade = max(DrawerTunables.fade.value, 1f)
            val scrolled = if (index == 0) min(px / density.density, fade) else fade
            headerScroll.update(((scrolled * 2).roundToInt() / 2f).dp)
        }
    }
    val colors = OpenCodeTheme.colors
    Box(modifier) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            archiveError?.let { error ->
                item(key = "inbox-error") {
                    Text(
                        error,
                        style = OpenCodeTextStyles.footnote,
                        color = colors.destructive,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InboxSidebarMetrics.edge, vertical = 8.dp),
                    )
                }
            }
            if (catchUpCount > 0) {
                item(key = "inbox-catch-up") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClick = openCatchUp)
                            .openCodeTapTarget()
                            .semantics { stateDescription = if (catchUpCount == 1) "1 session" else "$catchUpCount sessions" }
                            .testTag("inbox-catch-up")
                            .padding(horizontal = InboxSidebarMetrics.edge),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OpenCodeIcon(SFSymbol.RectangleStack, contentDescription = null, tint = colors.accent)
                        Text("Catch Up", style = OpenCodeTextStyles.body, color = colors.text, modifier = Modifier.weight(1f))
                        Text("$catchUpCount", style = OpenCodeTextStyles.body.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
                    }
                }
            }
            items(rows, key = { it.id }) { row ->
                InboxSidebarSwipeRow(
                    row = row,
                    now = now,
                    selected = row.id == selectedSessionID,
                    panRegions = panRegions,
                    onSelect = { select(row.id) },
                    onMarkRead = { store.send(HomeFeature.Action.MarkRead(row.id)) },
                    onArchive = {
                        store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveSwiped(sessionID = row.id, title = row.title)))
                    },
                )
            }
        }
        if (rows.isEmpty() && snapshotLoaded) {
            Column(
                Modifier.align(Alignment.Center).padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeIcon(SFSymbol.Tray, contentDescription = null, size = 40.dp, tint = colors.secondaryText)
                Text("No Sessions", style = MaterialTheme.typography.titleMedium, color = colors.text)
            }
        }
    }
}

@Composable
private fun InboxSidebarSwipeRow(
    row: InboxSidebarRow,
    now: Instant,
    selected: Boolean,
    panRegions: DrawerPanRegions,
    onSelect: () -> Unit,
    onMarkRead: () -> Unit,
    onArchive: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val swipe = rememberSwipeToDismissBoxState()
    val colors = OpenCodeTheme.colors
    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.drawerPanRow(panRegions).testTag("inbox-row-${row.id}"),
        enableDismissFromStartToEnd = row.unread,
        backgroundContent = {
            val toArchive = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            // iOS tints Mark as Read blue and Archive indigo with white labels; Android uses
            // the theme's primary and tertiary roles with their on-colours.
            val scheme = MaterialTheme.colorScheme
            val fill = if (toArchive) scheme.tertiary else scheme.primary
            val onFill = if (toArchive) scheme.onTertiary else scheme.onPrimary
            Row(
                Modifier
                    .fillMaxSize()
                    .background(fill)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = if (toArchive) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeIcon(if (toArchive) SFSymbol.Archivebox else SFSymbol.EnvelopeOpen, null, size = 20.dp, tint = onFill)
                Spacer(Modifier.width(6.dp))
                Text(if (toArchive) "Archive" else "Mark as Read", color = onFill, style = OpenCodeTextStyles.subheadline)
            }
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onMarkRead()
                    scope.launch { swipe.reset() }
                }
                // Settle before archiving. The row leaves at once, which cancels an animated
                // reset, and the swipe state is saveable per row key: if the archive fails the row
                // returns still dismissed, and SwipeToDismissBox calls onDismiss again for it, so
                // a failing archive retried forever, buzzing an error haptic each time.
                SwipeToDismissBoxValue.EndToStart -> scope.launch {
                    swipe.snapTo(SwipeToDismissBoxValue.Settled)
                    onArchive()
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) {
        Box(
            Modifier
                .background(drawerSurface())
                .background(if (selected) colors.text.copy(alpha = 0.08f) else Color.Transparent)
                .clickable(role = Role.Button, onClick = onSelect)
                .openCodeTapTarget(),
        ) {
            InboxSidebarRowView(row, now)
        }
    }
}

/** iOS `InboxSidebarRowView`: dot, title and age, project, then what the session needs. */
@Composable
internal fun InboxSidebarRowView(row: InboxSidebarRow, now: Instant, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val age = InboxSidebarRow.age(row.updatedAt, now)
    val description = buildList {
        row.project?.let(::add)
        when (val line = row.line) {
            InboxSidebarRow.Line.NeedsInput -> add("Needs your input")
            InboxSidebarRow.Line.Working -> add("Working")
            is InboxSidebarRow.Line.Reply -> add(line.text)
            InboxSidebarRow.Line.None -> Unit
        }
        if (row.unread) add("Unread")
        add(age)
    }.joinToString(", ")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = InboxSidebarMetrics.edge, vertical = 10.dp)
            .clearAndSetSemantics {
                contentDescription = row.title
                stateDescription = description
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(8.dp)
                .background(if (row.unread) colors.accent else Color.Transparent, CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // iOS: Text + Spacer(minLength: 8) + age. The title takes all the width the age
            // leaves (a second weighted spacer would cap it at half), so the age stays trailing.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    style = OpenCodeTextStyles.body,
                    fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(age, style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
            }
            row.project?.let { project ->
                Text(project, style = OpenCodeTextStyles.subheadline, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (val line = row.line) {
                InboxSidebarRow.Line.NeedsInput -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OpenCodeIcon(SFSymbol.ExclamationmarkCircleFill, null, size = 16.dp, tint = InboxSidebarMetrics.needsInputTint)
                    Text("Needs your input", style = OpenCodeTextStyles.subheadline, fontWeight = FontWeight.Medium, color = colors.text)
                }
                InboxSidebarRow.Line.Working -> Text("Working", style = OpenCodeTextStyles.subheadline, color = colors.secondaryText)
                is InboxSidebarRow.Line.Reply -> Text(line.text, style = OpenCodeTextStyles.subheadline, color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InboxSidebarRow.Line.None -> Unit
            }
        }
    }
}

/** The Inbox Sidebar switch as the drawer reads it (off by default). */
@Composable
internal fun rememberInboxSidebarOn(): Boolean {
    val feature: ExperimentalFeature = InboxSidebarFeature.experiment
    val stored by remember(feature) { shared(feature.key).flow }.collectAsState()
    return feature.isActive(stored)
}

private object InboxSidebarMetrics {
    val edge = 20.dp
    /** iOS `.orange` beside Needs your input. */
    val needsInputTint = Color(0xFFFF9F0A)
}
