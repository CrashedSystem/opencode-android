package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.transitionBlurEffect
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.RollingNumberAlignment
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.feature.drawer.DrawerListFooter
import ai.opencode.feature.drawer.catchup.DrawerCatchUpFinishedLabel
import ai.opencode.feature.drawer.motion.DrawerMotionTunables
import ai.opencode.feature.drawer.motion.DrawerShortcutMotion
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerSectionBreak
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.feature.drawer.style.DrawerSectionTitle
import ai.opencode.feature.drawer.style.DrawerShortcutLabel
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.style.RecentRowMenu
import ai.opencode.feature.drawer.style.drawerSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Port of iOS `DrawerListSupportCells.swift`: the list's rows that aren't sessions: the Pinned and
 * Recents headers (or a gap or hairline between sections), Catch Up and What’s New above them
 * collapsing on the drawer's shortcut spring, no sessions / no results, and a failed deletion.
 * iOS hosts the SwiftUI ones in collection view cells (`DrawerListHostedCell`, whose safe-area
 * override has no Compose counterpart); here they are plain items of the same `LazyColumn`.
 */

/** Pinned or Recents, as a header row or a gap or hairline between sections (iOS `DrawerListHeaderCell`). */
@Composable
fun DrawerListHeaderCell(kind: DrawerSectionHeaderKind, pinDrag: DrawerPinDrag, modifier: Modifier = Modifier) {
    val title = if (kind == DrawerSectionHeaderKind.Pinned) "Pinned" else "Recents"
    val key = if (kind == DrawerSectionHeaderKind.Pinned) DrawerPinDrag.ItemKey.PinnedHeader else DrawerPinDrag.ItemKey.RecentsHeader
    val density = LocalDensity.current
    val tag = if (kind == DrawerSectionHeaderKind.Pinned) "drawer-section-pinned" else "drawer-section-recents"
    val moved = modifier
        .fillMaxWidth()
        .graphicsLayer {
            translationY = pinDrag.translation(key) * density.density
            alpha = (if (kind == DrawerSectionHeaderKind.Pinned) pinDrag.pinnedHeaderAlpha else 1f) * pinDrag.alpha(key)
        }
    when (DrawerTunables.sectionBreak) {
        DrawerSectionBreak.Header -> Box(
            moved
                .padding(top = DrawerTunables.sectionAbove, bottom = DrawerTunables.sectionBelow)
                .heightIn(min = max(20f, DrawerTunables.sectionHeight.value).dp)
                .padding(start = DrawerMetrics.leadingEdge, end = DrawerMetrics.rowEdge)
                .testTag(tag)
                .clearAndSetSemantics {
                    contentDescription = title
                    heading()
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            DrawerSectionTitle(title)
        }
        DrawerSectionBreak.Gap, DrawerSectionBreak.Hairline -> {
            val hairline = DrawerTunables.sectionBreak == DrawerSectionBreak.Hairline
            val border = OpenCodeTheme.colors.border
            Box(
                moved
                    .height(max(DrawerTunables.sectionGap.value, 1f).dp)
                    .testTag(tag)
                    .clearAndSetSemantics {
                        contentDescription = title
                        heading()
                    }
                    .drawBehind {
                        if (!hairline) return@drawBehind
                        val line = 1f // one physical pixel
                        val start = DrawerMetrics.leadingEdge.toPx()
                        val end = size.width - DrawerMetrics.rowEdge.toPx()
                        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                        val y = ((size.height - line) / 2).roundToInt().toFloat()
                        val (x0, x1) = if (rtl) DrawerMetrics.rowEdge.toPx() to size.width - start else start to end
                        drawLine(border, Offset(x0, y + line / 2), Offset(x1, y + line / 2), strokeWidth = line)
                    },
            )
        }
    }
}

/** Which shortcut a [DrawerListShortcutRow] is. */
enum class DrawerListShortcutKind { CatchUp, WhatsNew }

/**
 * Catch Up or What’s New above the sections, collapsing on the drawer's shortcut spring
 * ([DrawerShortcutMotion]): height, insets, scale and blur. The row reads the motion in its
 * layout and layer blocks, so the spring never recomposes the list (iOS: never reaches the
 * collection view as anything but a size change).
 */
@Composable
fun DrawerListShortcutRow(
    kind: DrawerListShortcutKind,
    motion: DrawerShortcutMotion,
    shortcuts: DrawerListShortcuts,
    reducedMotion: Boolean,
    openCatchUp: () -> Unit,
    markAllRead: () -> Unit,
    openWhatsNew: () -> Unit,
    hideWhatsNew: () -> Unit,
    modifier: Modifier = Modifier,
    returnFocus: AccessibilityFocusRequest? = null,
) {
    val showsCatchUp = shortcuts.showsCatchUp
    val showsWhatsNew = shortcuts.showsWhatsNew
    val catchUpProgress = { motion.catchUpProgress(fallback = showsCatchUp) }
    val whatsNewProgress = { motion.whatsNewProgress(fallback = showsWhatsNew) }
    when (kind) {
        DrawerListShortcutKind.CatchUp -> {
            val count = if (shortcuts.catchUpCount > 0) shortcuts.catchUpCount else max(motion.displayedCatchUpCount, 1)
            val canMarkAllRead = if (shortcuts.catchUpCount > 0) shortcuts.canMarkAllRead else motion.displayedCanMarkAllRead
            // Finished, it stays All Caught Up while it rests and folds away.
            val finished = shortcuts.catchUpFinished || (shortcuts.catchUpCount == 0 && motion.finishStart != null)
            Collapse(
                progress = catchUpProgress,
                wantsOpen = motion.catchUpWantsOpen(fallback = showsCatchUp),
                top = { DrawerTunables.shortcutsTop.value * catchUpProgress() },
                bottom = { DrawerTunables.shortcutsBelow.value * (1 - whatsNewProgress()) * catchUpProgress() },
                reducedMotion = reducedMotion,
                modifier = modifier,
            ) {
                if (finished) {
                    DrawerCatchUpFinishedLabel(
                        start = motion.finishStart,
                        fromCount = count,
                        reducedMotion = reducedMotion,
                        cornerRadius = DrawerMetrics.rowCornerRadius,
                    )
                } else {
                    CatchUpButton(count, canMarkAllRead, openCatchUp, markAllRead, returnFocus)
                }
            }
        }
        DrawerListShortcutKind.WhatsNew -> Collapse(
            progress = whatsNewProgress,
            wantsOpen = motion.whatsNewWantsOpen(fallback = showsWhatsNew),
            top = {
                val c = catchUpProgress()
                (DrawerTunables.shortcutsGap.value * c + DrawerTunables.shortcutsTop.value * (1 - c)) * whatsNewProgress()
            },
            bottom = { DrawerTunables.shortcutsBelow.value * whatsNewProgress() },
            reducedMotion = reducedMotion,
            modifier = modifier,
        ) {
            DrawerWhatsNewButton(action = openWhatsNew, onHide = hideWhatsNew)
        }
    }
}

@Composable
private fun CatchUpButton(
    count: Int,
    canMarkAllRead: Boolean,
    openCatchUp: () -> Unit,
    markAllRead: () -> Unit,
    returnFocus: AccessibilityFocusRequest?,
) {
    val shape = RoundedCornerShape(DrawerMetrics.rowCornerRadius)
    val canvas = drawerSurface()
    RecentRowMenu(
        enabled = canMarkAllRead,
        onClick = openCatchUp,
        modifier = Modifier
            .testTag("drawer-catch-up")
            // Ahead of the clearing below, which would drop its marker.
            .accessibilityFocusRequest(returnFocus)
            .clearAndSetSemantics {
                contentDescription = "Catch Up"
                stateDescription = if (count == 1) "1 session" else "$count sessions"
                role = Role.Button
                onClick(label = "Swipe through unread and waiting sessions") { openCatchUp(); true }
                if (canMarkAllRead) customActions = listOf(CustomAccessibilityAction("Mark All as Read") { markAllRead(); true })
            },
        items = { dismiss ->
            DropdownMenuItem(
                text = { Text("Mark All as Read") },
                leadingIcon = { OpenCodeIcon(SFSymbol.EnvelopeOpen, contentDescription = null) },
                onClick = {
                    dismiss()
                    markAllRead()
                },
            )
        },
    ) {
        DrawerShortcutLabel(
            title = "Catch Up",
            systemImage = "rectangle.stack",
            modifier = Modifier.background(canvas, shape),
        ) {
            RollingNumber(
                value = count,
                style = DrawerListFonts.monospacedDigits(DrawerTunables.countFont.font),
                color = OpenCodeTheme.colors.secondaryText,
                alignment = RollingNumberAlignment.End,
            )
        }
    }
}

/**
 * The What’s New row (iOS `WhatsNewButton` in `Settings/WhatsNew/WhatsNewView.swift`): a gift, the
 * title and an accent dot; holding it offers Hide. Drawn here because the drawer owns the row.
 */
@Composable
fun DrawerWhatsNewButton(action: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(DrawerMetrics.rowCornerRadius)
    val canvas = drawerSurface()
    RecentRowMenu(
        enabled = true,
        onClick = action,
        modifier = modifier
            .testTag("drawer-whats-new")
            .clearAndSetSemantics {
                contentDescription = "What’s New"
                role = Role.Button
                onClick { action(); true }
                customActions = listOf(CustomAccessibilityAction("Hide") { onHide(); true })
            },
        items = { dismiss ->
            DropdownMenuItem(
                text = { Text("Hide") },
                leadingIcon = { OpenCodeIcon(SFSymbol.EyeSlash, contentDescription = null) },
                onClick = {
                    dismiss()
                    onHide()
                },
            )
        },
    ) {
        DrawerShortcutLabel(title = "What’s New", systemImage = "gift", modifier = Modifier.background(canvas, shape)) {
            OpenCodeIcon(
                SFSymbol.CircleFill,
                contentDescription = null,
                size = (11 * LocalDensity.current.fontScale).dp,
                tint = OpenCodeTheme.colors.accent,
                modifier = Modifier.height(18.dp),
            )
        }
    }
}

/**
 * The shortcut's collapse (iOS `DrawerListShortcutRow.collapse`): scale toward the top
 * ([DrawerMotionTunables.SHORTCUT_SCALE]), `MotionBlurReveal` (fade, blur up to
 * [DrawerMotionTunables.shortcutBlur]), height `rowHeight × progress` until open, insets, and no
 * touches unless it wants to be open. Everything per frame is read in layout and layer blocks.
 */
@Composable
private fun Collapse(
    progress: () -> Float,
    wantsOpen: Boolean,
    top: () -> Float,
    bottom: () -> Float,
    reducedMotion: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val minScale = DrawerMotionTunables.SHORTCUT_SCALE
    val maxBlur = if (reducedMotion) 0.dp else DrawerMotionTunables.shortcutBlur
    Box(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val p = progress().coerceIn(0f, 1f)
                val topPx = (top() * density).roundToInt()
                val bottomPx = (bottom() * density).roundToInt()
                val inner = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
                val placeable = measurable.measure(inner)
                val height = if (p >= 0.999f) placeable.height else max(0, (DrawerMetrics.rowHeight.toPx() * p).roundToInt())
                layout(placeable.width, topPx + height + bottomPx) { placeable.place(0, topPx) }
            }
            .padding(horizontal = DrawerMetrics.rowEdge)
            .graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                val scale = if (reducedMotion) 1f else minScale + (1 - minScale) * p
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
                alpha = if (p <= 0.02f) 0f else p
                val radius = if (reducedMotion || p >= 0.98f) 0f else (1 - p) * maxBlur.toPx()
                renderEffect = transitionBlurEffect(radius)
            }
            .then(if (wantsOpen) Modifier else Modifier.blockingTouches()),
    ) {
        content()
    }
}

/** iOS `.allowsHitTesting(false)`: takes the touches so nothing in the row reacts. */
private fun Modifier.blockingTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** No sessions, or no results for a search, on the text edge (iOS `DrawerListEmptyRow`). */
@Composable
fun DrawerListEmptyRow(search: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .padding(start = DrawerMetrics.leadingEdge, end = DrawerMetrics.rowEdge)
            .testTag("drawer-empty")
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val colors = OpenCodeTheme.colors
        if (search.isEmpty()) {
            Text("No Sessions", style = OpenCodeTextStyles.headline, color = colors.text)
            Text("Start one with New Session.", style = OpenCodeTextStyles.body, color = colors.secondaryText)
        } else {
            Text("No Results for “$search”", style = OpenCodeTextStyles.headline, color = colors.text)
            Text("Check the spelling or try a new search.", style = OpenCodeTextStyles.body, color = colors.secondaryText)
        }
    }
}

/**
 * Under the last row: a spinner while the next page is read, or the read that failed with Try
 * Again (iOS `DrawerListFooterRow`; Material's progress indicator and text button).
 */
@Composable
fun DrawerListFooterRow(footer: DrawerListFooter, retry: () -> Unit, modifier: Modifier = Modifier) {
    when (footer) {
        DrawerListFooter.loading -> Box(
            modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.rowHeight)
                .testTag("drawer-load-more")
                .semantics { contentDescription = "Loading more sessions" },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OpenCodeTheme.colors.secondaryText)
        }
        DrawerListFooter.failed -> Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.rowHeight)
                .padding(start = DrawerMetrics.leadingEdge, end = DrawerMetrics.rowEdge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Couldn’t load more sessions",
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = retry, modifier = Modifier.testTag("drawer-load-more-retry")) {
                Text("Try Again", style = OpenCodeTextStyles.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A deletion that failed, above the sections (iOS `DrawerListDeletionErrorRow`). */
@Composable
fun DrawerListDeletionErrorRow(message: String, modifier: Modifier = Modifier) {
    val red = OpenCodeTheme.colors.destructive
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerMetrics.rowEdge)
            .heightIn(min = 44.dp)
            .padding(start = DrawerMetrics.textInset)
            .testTag("drawer-deletion-error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeIcon(SFSymbol.ExclamationmarkTriangle, contentDescription = null, tint = red)
        Text(message, style = OpenCodeTextStyles.footnote, color = red)
    }
}
