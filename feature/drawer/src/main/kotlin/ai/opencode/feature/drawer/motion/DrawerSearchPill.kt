package ai.opencode.feature.drawer.motion

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Port of `OpenCode/Drawer/DrawerSearchPill.swift`: the drawer's search. A search button at rest
 * that stretches leftward into the search field. Closing it (the field's back arrow, or Escape)
 * clears the query, dismisses the keyboard and folds the field back into the button. Under Reduce
 * Motion the two states cross-fade in place.
 *
 * Liquid Glass and its morph (`GlassEffectContainer`, `glassEffectID`) become a tonal M3
 * capsule whose width springs on [DrawerSearchPillDefaults.morph]. Android divergence: opened,
 * it is an M3 search bar (https://m3.material.io/components/search): a leading back arrow inside
 * the field closes search, where iOS buds a separate round × ("Cancel Search") off its end; at
 * rest it is a standard icon button, filling in as it opens.
 */
object DrawerSearchPillDefaults {
    /** The button at rest and the field's height (`DrawerTunables.searchSize`, at least Android's 48 dp target). */
    val size: Dp get() = maxOf(DrawerTunables.searchSize, OpenCodeMinTapTarget)
    /**
     * The capsule grows from its circle on `MotionRole.PanelOpen` and folds back on its exit;
     * under Reduce Motion the two states cross-fade (iOS `morph(opening:reduceMotion:)`).
     */
    fun <T> morph(opening: Boolean, reduceMotion: Boolean = false): FiniteAnimationSpec<T> =
        MotionRole.PanelOpen.animation(if (opening) MotionPhase.Enter else MotionPhase.Exit, reduceMotion)
}

/**
 * Whether the drawer's search is open or focused (iOS `DrawerSearchState`). Read by the pill and
 * the header/footer fade modifiers in their own layers, so opening and closing animate there
 * without recomposing Home.
 */
@Stable
class DrawerSearchState {
    var isSearching: Boolean by mutableStateOf(false)
    var isFocused: Boolean by mutableStateOf(false)
}

/**
 * The drawer's search pill.
 *
 * @param keyboardHold the drawer's [DrawerKeyboardHold]: search's keyboard belongs to the
 *   drawer, never the peeking card.
 * @param onEnd search closes (Home clears the query).
 */
@Composable
fun DrawerSearchPill(
    state: DrawerSearchState,
    text: String,
    onTextChange: (String) -> Unit,
    reducedMotion: Boolean,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardHold: DrawerKeyboardHold? = null,
    size: Dp = DrawerSearchPillDefaults.size,
) {
    val haptics = remember { dependency(HapticsClient) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val fieldKey = remember { Any() }
    val isSearching = state.isSearching
    /** 0 at rest, 1 open; springs the capsule's width and fill. */
    val open = remember { Animatable(if (isSearching) 1f else 0f) }
    var syncedSearching by remember { mutableStateOf(isSearching) }

    val begin = {
        if (state.isSearching) {
            keyboardHold?.setDrawerSearching(true)
            runCatching { focusRequester.requestFocus() }
            state.isFocused = true
        } else {
            state.isSearching = true
            state.isFocused = true
        }
    }
    val end = { playHaptic: Boolean ->
        if (playHaptic) haptics.play(Haptic.cancel)
        focusManager.clearFocus()
        state.isFocused = false
        state.isSearching = false
        onEnd()
    }

    LaunchedEffect(isSearching) {
        if (syncedSearching != isSearching) {
            syncedSearching = isSearching
            keyboardHold?.setDrawerSearching(isSearching)
            if (isSearching) {
                if (!state.isFocused) state.isFocused = true
            } else if (state.isFocused) {
                state.isFocused = false
            }
        }
        if (isSearching) runCatching { focusRequester.requestFocus() }
        open.animateTo(if (isSearching) 1f else 0f, DrawerSearchPillDefaults.morph(isSearching, reducedMotion))
    }

    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = OpenCodeTextStyles.body.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            modifier = fieldModifier
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    keyboardHold?.drawerFieldFocusChanged(fieldKey, focus.isFocused) { focusManager.clearFocus(force = true) }
                    keyboardHold?.setDrawerSearching(focus.isFocused)
                    if (state.isFocused != focus.isFocused) state.isFocused = focus.isFocused
                }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                        end(false)
                        true
                    } else {
                        false
                    }
                }
                .semantics { contentDescription = "Search sessions" }
                .padding(end = 16.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text("Search", style = OpenCodeTextStyles.body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }

    if (reducedMotion) {
        // A new identity per state, so they cross-fade rather than stretch.
        Crossfade(isSearching, modifier.fillMaxWidth(), animationSpec = Motion.reducedFadeSpec(), label = "drawer-search") { searching ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                SearchCapsule(
                    isSearching = searching,
                    progress = { if (searching) 1f else 0f },
                    size = size,
                    onTap = begin,
                    onClose = { end(true) },
                    modifier = if (searching) Modifier.weight(1f) else Modifier,
                    field = field,
                )
            }
        }
    } else {
        BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            val full = maxWidth
            SearchCapsule(
                isSearching = isSearching,
                progress = { open.value.coerceIn(0f, 1f) },
                size = size,
                onTap = begin,
                onClose = { end(true) },
                modifier = Modifier.animatedWidth { size.value + (full.value - size.value) * open.value },
                field = field,
            )
        }
    }
}

/**
 * The button at rest, the search bar open. [progress] (0 rest, 1 open) fills the capsule and turns
 * the magnifier into the back arrow; it is read in draw, so the morph never recomposes.
 */
@Composable
private fun SearchCapsule(
    isSearching: Boolean,
    progress: () -> Float,
    size: Dp,
    onTap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    field: @Composable (Modifier) -> Unit,
) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier
            .height(size)
            .clip(OpenCodeShape.capsule)
            .drawBehind { drawRect(fill.copy(alpha = fill.alpha * progress())) }
            // The button at rest; opened, a tap on the bar focuses the field.
            .clickable(role = if (isSearching) null else Role.Button, onClick = onTap)
            .then(
                if (isSearching) {
                    Modifier.testTag("drawer-search-pill")
                } else {
                    Modifier
                        .clearAndSetSemantics { contentDescription = "Search sessions" }
                        .testTag("drawer-search")
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
            // The leading slot: the magnifier at rest, the back arrow that closes search open.
            Box(
                Modifier
                    .size(size)
                    .then(
                        if (isSearching) {
                            Modifier
                                .clip(OpenCodeShape.circle)
                                .clickable(role = Role.Button, onClick = onClose)
                                .semantics { contentDescription = "Cancel Search" }
                                .testTag("drawer-search-cancel")
                        } else {
                            Modifier.clearAndSetSemantics {}
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                OpenCodeIcon(SFSymbol.Magnifyingglass, contentDescription = null, tint = tint, modifier = Modifier.graphicsLayer { alpha = 1f - progress() })
                OpenCodeIcon(SFSymbol.ChevronBackward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.graphicsLayer { alpha = progress() })
            }
            // iOS `.transition(.opacity)` inside the morph's `withAnimation`: the field fades on the morph spring.
            AnimatedVisibility(
                isSearching,
                enter = fadeIn(DrawerSearchPillDefaults.morph(opening = true)),
                exit = fadeOut(DrawerSearchPillDefaults.morph(opening = false)),
                modifier = Modifier.weight(1f),
            ) {
                field(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Lays the element out at [widthDp], read at layout so springing it never recomposes. */
private fun Modifier.animatedWidth(widthDp: () -> Float): Modifier = layout { measurable, constraints ->
    val width = (widthDp().coerceAtLeast(0f) * density).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(width, placeable.height) { placeable.place(0, 0) }
}

/** Swallows touches in the `Initial` pass while [block], so nothing beneath reacts (iOS `.allowsHitTesting(false)`). */
private fun Modifier.blockingTouches(block: Boolean): Modifier = if (!block) {
    this
} else {
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * The drawer's title fades out beneath the opening search (swap's exit, quicker than the capsule
 * grows) and back on its entrance, and stops taking touches and TalkBack focus (iOS
 * `DrawerSearchTitleFade`).
 */
fun Modifier.drawerSearchTitleFade(state: DrawerSearchState): Modifier = composed {
    val searching = state.isSearching
    val alpha by animateFloatAsState(
        if (searching) 0f else 1f,
        MotionRole.Swap.animation(if (searching) MotionPhase.Exit else MotionPhase.Enter),
        label = "drawer-search-title",
    )
    graphicsLayer { this.alpha = alpha }
        .blockingTouches(searching)
        .then(if (searching) Modifier.clearAndSetSemantics {} else Modifier)
}

/** The drawer's floating bottom controls fade out of the way while search owns the keyboard (iOS `DrawerSearchFocusFade`). */
fun Modifier.drawerSearchFocusFade(state: DrawerSearchState, reducedMotion: Boolean): Modifier = composed {
    val focused = state.isFocused
    val alpha by animateFloatAsState(
        if (focused) 0f else 1f,
        // iOS 03adf07a: fades on swap (a cross-fade under Reduce Motion) instead of popping.
        MotionRole.Swap.animation(if (focused) MotionPhase.Exit else MotionPhase.Enter, reducedMotion),
        label = "drawer-search-focus",
    )
    graphicsLayer { this.alpha = alpha }
        .blockingTouches(focused)
        .then(if (focused) Modifier.clearAndSetSemantics {} else Modifier)
}

/** The raised bottom controls stop taking touches while search owns the keyboard (iOS `DrawerSearchFocusHitTesting`). */
fun Modifier.drawerSearchFocusHitTesting(state: DrawerSearchState): Modifier = composed {
    blockingTouches(state.isFocused)
}
