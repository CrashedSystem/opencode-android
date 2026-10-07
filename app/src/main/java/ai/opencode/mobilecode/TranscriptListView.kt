@file:PortedFrom("OpenCode/Transcript/TranscriptListView.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.Store
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/// The transcript's clock in nanoseconds, on the frame clock's time base.
val LocalTranscriptClock = staticCompositionLocalOf<() -> Long> { System::nanoTime }

class SessionScrollEdgeBridge {
    val sendTransition = ComposerSendTransition()
    var scrollView: LazyListState? = null
        set(value) {
            field = value
            onScrollViewChange?.invoke(value)
        }
    var onScrollViewChange: ((LazyListState?) -> Unit)? = null
}

@Composable
fun TranscriptListView(
    store: Store<SessionFeature.State, SessionFeature.Action>,
    configuration: TranscriptListViewController.Configuration,
    showsJumpButton: Binding<Boolean>,
    jumpRequest: Int,
    /// The composer's obstruction in pixels, read during the list's measure.
    composerInset: () -> Float,
    keyboardIsMoving: () -> Boolean,
    /// The status bar plus the header band, in pixels.
    topInset: Float,
    scrollEdgeBridge: SessionScrollEdgeBridge,
    usesSystemEdge: Boolean,
    dismissKeyboard: () -> Unit,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFile: (AgentFileReference) -> Unit = {},
) {
    val reduceMotion = LocalReduceMotion.current
    val now = LocalTranscriptClock.current
    val setShowsJumpButton: (Boolean) -> Unit = { value ->
        if (showsJumpButton.wrappedValue != value) showsJumpButton.wrappedValue = value
    }
    val controller = remember {
        TranscriptListViewController(
            store = store,
            configuration = configuration,
            composerInset = composerInset,
            scrollEdgeBridge = scrollEdgeBridge,
            usesSystemEdge = usesSystemEdge,
            reduceMotion = reduceMotion,
            setShowsJumpButton = setShowsJumpButton,
            dismissKeyboard = dismissKeyboard,
            onPreviewImage = onPreviewImage,
            now = now,
        ).also {
            it.lastJumpRequest = jumpRequest
            it.onOpenFile = onOpenFile
            scrollEdgeBridge.scrollView = it.listState
        }
    }
    controller.setShowsJumpButton = setShowsJumpButton
    controller.onPreviewImage = onPreviewImage
    controller.onOpenFile = onOpenFile
    controller.update(
        configuration = configuration,
        composerInset = composerInset,
        keyboardIsMoving = keyboardIsMoving,
        topInset = topInset,
        usesSystemEdge = usesSystemEdge,
        reduceMotion = reduceMotion,
        jumpRequest = jumpRequest,
    )
    controller.View(modifier)
}
