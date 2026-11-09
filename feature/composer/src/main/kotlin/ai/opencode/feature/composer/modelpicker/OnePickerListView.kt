package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

// Port of iOS `Composer/ModelPicker/OnePickerListView.swift`: One Picker's model list. iOS backs
// it with a UIKit compositional collection view (for frame rate and native swipe actions); here
// it's a `LazyColumn` of immutable row snapshots (no store in a row), with the same rows,
// sections, footer, swipe and long-press favorites, level menu, and the pull-down-from-the-top
// sheet drag reported to the panel through [OnePickerListActions].

/** iOS `OnePickerRowItem`: one row's immutable snapshot. */
data class OnePickerRowItem(
    val sectionID: String,
    val idPrefix: String,
    val model: OpenCodeModel,
    val shortName: String,
    val providerTitle: String,
    val providerSubtitle: String?,
    val isCurrent: Boolean,
    val isFavorite: Boolean,
    val inFavoritesSection: Boolean,
    val isAdjustable: Boolean,
    val shownLevel: String?,
    val levelLabel: String,
    val hasParsedSearchLevel: Boolean,
    val showsArc: Boolean = false,
    val rowHeight: Double,
    val levelAlpha: Double,
) {
    val diffID: String get() = "$sectionID::${model.id}"

    val accessibilityValue: String
        get() = buildList {
            add(providerTitle)
            if (isAdjustable) add(levelLabel)
            if (isFavorite) add("Favorite")
        }.joinToString(", ")
}

/** iOS `OnePickerSectionSnapshot`. */
data class OnePickerSectionSnapshot(val id: String, val title: String, val rows: List<OnePickerRowItem>)

/** iOS `OnePickerScrollEdges`: whether rows overflow above or below the visible list. */
data class OnePickerScrollEdges(val topOverflow: Boolean = false, val bottomOverflow: Boolean = false)

/** iOS `OnePickerListView.OnePickerFooterSnapshot`: the Show All Models row. */
data class OnePickerFooterSnapshot(val hiddenCount: Int, val showsAll: Boolean)

/**
 * iOS `OnePickerListActions`: the list's callbacks, held stable across redraws and refreshed by
 * the panel, so the list always calls the latest ones.
 */
@Stable
class OnePickerListActions {
    var onPickModel: (OnePickerRowItem) -> Unit = {}
    var onPickLevel: (OpenCodeModel, String?) -> Unit = { _, _ -> }
    var onToggleFavorite: (OpenCodeModel) -> Unit = {}
    var onToggleShowAllModels: () -> Unit = {}
    var onBeginSheetDrag: () -> Unit = {}
    var onUpdateSheetDrag: (Double) -> Unit = {}
    var onEndSheetDrag: (Double, Double) -> Unit = { _, _ -> }
    var onEdgesChanged: (OnePickerScrollEdges) -> Unit = {}
}

/**
 * iOS `OnePickerListView`. A new [openGeneration] scrolls back to the top and drops a pull in
 * progress; [scrollDisabled] freezes the list (mid-morph). Structural changes (favoriting)
 * animate, except while searching, as iOS's diffable snapshot does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnePickerListView(
    sections: List<OnePickerSectionSnapshot>,
    openGeneration: Int,
    isSearching: Boolean,
    scrollDisabled: Boolean,
    bottomFooterRow: OnePickerFooterSnapshot?,
    actions: OnePickerListActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val pull = rememberOnePickerPullDown(listState, actions)

    LaunchedEffect(openGeneration) {
        listState.scrollToItem(0)
        pull.cancel()
    }
    DisposableEffect(pull) { onDispose { pull.cancel() } }
    LaunchedEffect(listState) {
        snapshotFlow { OnePickerScrollEdges(listState.canScrollBackward, listState.canScrollForward) }
            .distinctUntilChanged()
            .collect { actions.onEdgesChanged(it) }
    }

    LazyColumn(
        state = listState,
        userScrollEnabled = !scrollDisabled,
        modifier = modifier
            .pointerInput(listState) {
                // Whether this drag began with the list at its top (iOS `startedAtTop`).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pull.startedAtTop = !listState.canScrollBackward
                    // A gesture torn down before its release (iOS `.cancelled`) settles the pull.
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } catch (cancelled: CancellationException) {
                        pull.cancel()
                        throw cancelled
                    }
                }
            }
            .nestedScroll(pull.connection),
    ) {
        sections.forEachIndexed { index, section ->
            item(key = "spacer-top-${section.id}", contentType = "inset") {
                Spacer(Modifier.height(if (section.title.isEmpty()) 6.dp else 2.dp))
            }
            if (section.title.isNotEmpty()) {
                item(key = "header-${section.id}", contentType = "header") {
                    OnePickerSectionHeader(section.title, isFirst = index == 0)
                }
            }
            items(section.rows, key = { it.diffID }, contentType = { "row" }) { item ->
                OnePickerRowCell(
                    item = item,
                    actions = actions,
                    modifier = if (isSearching) Modifier else Modifier.animateItem(),
                )
            }
            item(key = "spacer-bottom-${section.id}", contentType = "inset") { Spacer(Modifier.height(8.dp)) }
        }
        if (bottomFooterRow != null && bottomFooterRow.hiddenCount > 0) {
            item(key = "footer", contentType = "footer") {
                OnePickerShowAllRow(bottomFooterRow, onTap = { actions.onToggleShowAllModels() })
            }
        }
    }
}

/**
 * iOS's `scrollViewWillBeginDragging`/`DidScroll`/`WillEndDragging` sheet pull: a drag that
 * began at the top and pulls down moves the sheet instead of the list, reporting its translation
 * (dp) until released (with its velocity) or pushed back up past its anchor (which cancels).
 */
@Stable
internal class OnePickerPullDown(
    private val listState: LazyListState,
    private val density: () -> Float,
    /** Refreshed every composition, so a pull settles through the latest callbacks. */
    var actions: OnePickerListActions,
) {
    var startedAtTop = true
    private var pulling = false
    private var pullPx = 0f

    /** A cancelled pull (or a new opening) settles the sheet where it was, once. */
    fun cancel() {
        if (pulling) end(0.0, 0.0)
        pullPx = 0f
    }

    private fun end(dy: Double, vy: Double) {
        pulling = false
        pullPx = 0f
        actions.onEndSheetDrag(dy, vy)
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!pulling || source != NestedScrollSource.UserInput) return Offset.Zero
            pullPx += available.y
            if (pullPx < -2f * density()) {
                end(0.0, 0.0)
                return Offset.Zero
            }
            actions.onUpdateSheetDrag(max(0f, pullPx) / density().toDouble())
            return available
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 4f * density()) startedAtTop = false
            if (pulling || !startedAtTop || available.y <= 2f) return Offset.Zero
            // iOS anchors the pull where the list first overscrolls: the sheet starts from 0.
            pulling = true
            pullPx = 0f
            actions.onBeginSheetDrag()
            actions.onUpdateSheetDrag(0.0)
            return available
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!pulling) return Velocity.Zero
            end(max(0f, pullPx) / density().toDouble(), available.y / density().toDouble())
            return available
        }
    }
}

@Composable
private fun rememberOnePickerPullDown(listState: LazyListState, actions: OnePickerListActions): OnePickerPullDown {
    val density = LocalDensity.current
    val pull = remember(listState) { OnePickerPullDown(listState, { density.density }, actions) }
    pull.actions = actions
    return pull
}

/** iOS's section header: footnote semibold on the 22 pt spine, not pinned. */
@Composable
private fun OnePickerSectionHeader(title: String, isFirst: Boolean) {
    Text(
        title,
        style = OpenCodeTextStyles.footnote,
        fontWeight = FontWeight.SemiBold,
        color = OpenCodeTheme.colors.secondaryText,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = if (isFirst) 2.dp else 12.dp, bottom = 4.dp)
            .semantics { heading() }
            .testTag("model-section-${title.lowercase().replace(" ", "-")}"),
    )
}

/** iOS `OnePickerRowCell`: provider mark, name (and provider), favorite badge, level menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnePickerRowCell(item: OnePickerRowItem, actions: OnePickerListActions, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val bounce = remember { Animatable(1f) }
    var holds by remember { mutableIntStateOf(0) }
    LaunchedEffect(holds) {
        // The shared press: down at once, let go on its release.
        if (holds == 0 || reduceMotion) return@LaunchedEffect
        bounce.snapTo(MotionTunables.pressScale.value)
        bounce.animateTo(1f, Motion.press.animation(MotionPhase.Exit, reduceMotion = false))
    }
    val toggleFavorite = { actions.onToggleFavorite(item.model) }
    // Android divergence: a ripple in the row's rounded inset answers the press (iOS tints it),
    // and the current model sits on M3's selected list item container (secondaryContainer).
    val highlight = if (item.isCurrent) MaterialTheme.colorScheme.secondaryContainer else null
    val nameColor = if (item.isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else colors.text
    val panelColor = OnePickerSurfaceDefaults.color

    FavoriteSwipeBox(
        isFavorite = item.isFavorite,
        onToggle = toggleFavorite,
        tint = OnePickerFavoriteTint,
        containerColor = panelColor,
        swipesFromStart = true,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = max(44.0, item.rowHeight).dp)
                .graphicsLayer { scaleX = bounce.value; scaleY = bounce.value },
        ) {
            if (highlight != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(horizontal = 12.dp, vertical = 1.dp)
                        .background(highlight, RoundedCornerShape(16.dp))
                        .let { if (colors.highContrast) it.border(1.dp, colors.secondaryText, RoundedCornerShape(16.dp)) else it },
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .padding(horizontal = 12.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .indication(interaction, ripple()),
            )
            Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalViewConfiguration provides rowHoldConfiguration(LocalViewConfiguration.current)) {
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .heightIn(min = max(44.0, item.rowHeight).dp)
                            .combinedClickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { actions.onPickModel(item) },
                                onLongClick = {
                                    holds += 1
                                    toggleFavorite()
                                },
                            )
                            .clearAndSetSemantics {
                                contentDescription = item.model.name
                                stateDescription = item.accessibilityValue
                                role = Role.Button
                                selected = item.isCurrent
                                onClick { actions.onPickModel(item); true }
                                customActions = listOf(
                                    CustomAccessibilityAction(if (item.isFavorite) "Remove from Favorites" else "Add to Favorites") {
                                        toggleFavorite(); true
                                    },
                                )
                            }
                            .testTag("${item.idPrefix}-${item.model.id}")
                            .padding(start = 22.dp, end = if (item.isAdjustable) 4.dp else 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OnePickerProviderMark(
                            item.model.providerID,
                            canonical = item.model.canonicalProviderID,
                            size = 20.dp,
                            tint = if (item.isCurrent) nameColor else colors.secondaryText,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                item.shortName,
                                style = OpenCodeTextStyles.body,
                                fontWeight = if (item.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = nameColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.providerSubtitle?.let {
                                Text(it, style = OpenCodeTextStyles.footnote, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (item.isFavorite && !item.inFavoritesSection) {
                            Box(Modifier.padding(start = 6.dp).size(14.dp), contentAlignment = Alignment.Center) {
                                OpenCodeIcon(SFSymbol.StarFill, null, size = 11.dp, tint = colors.tertiaryText)
                            }
                        }
                        Spacer(Modifier.weight(0.0001f))
                    }
                }
                if (item.isAdjustable) OnePickerLevelButton(item, onPickLevel = { actions.onPickLevel(item.model, it) })
            }
        }
    }
}

/** iOS's 0.35 s row hold (Android's long press is a little longer by default). */
private fun rowHoldConfiguration(base: ViewConfiguration): ViewConfiguration = object : ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = 350L
}

/**
 * iOS's level button: the level word (a menu of every level, highest first) at the row's end.
 * Android: an M3 assist chip with a drop-down arrow, the menu's anchor, so it reads as a control.
 */
@Composable
private fun OnePickerLevelButton(item: OnePickerRowItem, onPickLevel: (String?) -> Unit) {
    val scale = ModelLevelScale(item.model.variants)
    val currentIndex = scale.index(item.shownLevel)
    var menuOpen by remember { mutableStateOf(false) }
    // A level read from the search query stands out; a remembered one is quieter.
    val labelColor = if (item.hasParsedSearchLevel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val step = { by: Int -> onPickLevel(scale.stepped(item.shownLevel, by)) }
    Box(Modifier.padding(start = 8.dp, end = 16.dp)) {
        AssistChip(
            onClick = { menuOpen = true },
            label = {
                Text(
                    item.levelLabel,
                    fontWeight = if (item.hasParsedSearchLevel) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            },
            modifier = Modifier
                .semantics {
                    contentDescription = "${item.shortName} thinking level"
                    stateDescription = item.levelLabel
                    customActions = listOf(
                        CustomAccessibilityAction("Higher Thinking Level") { step(1); true },
                        CustomAccessibilityAction("Lower Thinking Level") { step(-1); true },
                    )
                }
                .testTag("${item.idPrefix}-level-${item.model.id}"),
            leadingIcon = if (item.showsArc) ({ AnimatedLevelArc(scale, item.shownLevel, size = 14.dp, color = labelColor) }) else null,
            trailingIcon = { OpenCodeIcon(SFSymbol.ArrowtriangleDownFill, null, size = AssistChipDefaults.IconSize) },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = labelColor,
                trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (index in (0 until scale.count).reversed()) {
                val option = scale.level(index)
                DropdownMenuItem(
                    text = { Text(ModelLevelScale.label(option)) },
                    leadingIcon = { AnimatedLevelArc(scale, option, size = 15.dp) },
                    trailingIcon = if (index == currentIndex) ({ OpenCodeIcon(SFSymbol.Checkmark, null) }) else null,
                    onClick = {
                        menuOpen = false
                        onPickLevel(option)
                    },
                    modifier = Modifier.testTag("model-stack-level-${option ?: "default"}"),
                )
            }
        }
    }
}

/** iOS `OnePickerShowAllCell`: a neutral Show All / Show Fewer row with the hidden count. */
@Composable
private fun OnePickerShowAllRow(footer: OnePickerFooterSnapshot, onTap: () -> Unit) {
    val title = if (footer.showsAll) "Show Fewer Models" else "Show All Models"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onTap)
            .semantics { contentDescription = title; stateDescription = "${footer.hiddenCount} hidden" }
            .testTag("model-show-all")
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = OpenCodeTextStyles.subheadline, fontWeight = FontWeight.Medium, color = OpenCodeTheme.colors.secondaryText, modifier = Modifier.weight(1f))
        Text("${footer.hiddenCount} hidden", style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.tertiaryText)
    }
}
