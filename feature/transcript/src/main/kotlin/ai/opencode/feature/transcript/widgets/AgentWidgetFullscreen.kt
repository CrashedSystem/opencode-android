package ai.opencode.feature.transcript.widgets

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Phase
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The widget the list's [AgentWidgetStore] shows full screen, as a cover above the session:
 * the same controller (state, program and canvas clocks carry on), status bar hidden, rotation
 * allowed while it's open, no title bar (the widget supplies its own), one floating close button.
 * Close, swipe down or system back exits; so does the widget closing.
 */
@Composable
fun AgentWidgetFullscreenHost(store: AgentWidgetStore) {
    val open = store.fullscreen ?: return
    val controller = open.controller
    val closed = controller.phase is Phase.Closed
    LaunchedEffect(open, closed) { if (closed) store.exitFullscreen() }
    Dialog(
        onDismissRequest = store::exitFullscreen,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ImmersiveWindow()
        RotationAllowed()
        val threshold = with(LocalDensity.current) { 140.dp.toPx() }
        val scope = rememberCoroutineScope()
        var drag by remember { mutableFloatStateOf(0f) }
        fun settle() {
            if (drag > threshold) {
                store.exitFullscreen()
            } else {
                scope.launch { animate(drag, 0f, animationSpec = MotionRole.Release.animation()) { value, _ -> drag = value } }
            }
        }
        // A swipe down past the top of the content (or on content that doesn't scroll) drags the cover away.
        val connection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (drag <= 0f || available.y >= 0f) return Offset.Zero
                    val used = maxOf(available.y, -drag)
                    drag += used
                    return Offset(0f, used)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    drag += available.y
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (drag <= 0f) return Velocity.Zero
                    settle()
                    return available
                }
            }
        }
        CompositionLocalProvider(LocalAgentWidgetFullscreen provides true) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, drag.roundToInt()) }
                    .background(OpenCodeTheme.colors.canvas)
                    .nestedScroll(connection)
                    .draggable(
                        rememberDraggableState { delta -> drag = maxOf(0f, drag + delta) },
                        Orientation.Vertical,
                        onDragStopped = { settle() },
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                AgentWidgetFrame(controller.phase, open.title, Modifier.fillMaxSize(), controller = controller)
                OpenCodeIconButton(
                    SFSymbol.Xmark,
                    stringResource(CoreR.string.common_close),
                    store::exitFullscreen,
                    Modifier.align(Alignment.TopEnd).padding(4.dp),
                    style = OpenCodeIconButtonStyle.Circle,
                    size = 40.dp,
                    iconSize = 18.dp,
                )
            }
        }
    }
}

/** What a widget's inline row shows while it's full screen, at the height it had. */
@Composable
internal fun AgentWidgetFullscreenPlaceholder(controller: AgentWidgetController, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val height = with(LocalDensity.current) { controller.inlineHeight.toDp() }.coerceAtLeast(72.dp)
    Box(modifier.fillMaxWidth().height(height).openCodeHairline(OpenCodeShape.panel), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OpenCodeIcon(SFSymbol.ArrowUpLeftAndArrowDownRight, contentDescription = null, size = 14.dp, tint = colors.tertiaryText)
            Text(stringResource(R.string.transcript_widget_fullscreen_placeholder), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
        }
    }
}

/** Hides the system bars over the dialog's window; a swipe from the edge shows them for a moment. */
@Composable
private fun ImmersiveWindow() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Lets the activity follow the sensor while the cover is open (landscape for games), then restores it. */
@Composable
private fun RotationAllowed() {
    val activity = LocalContext.current.activity() ?: return
    DisposableEffect(activity) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { activity.requestedOrientation = previous }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
