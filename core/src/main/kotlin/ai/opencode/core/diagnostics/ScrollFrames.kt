package ai.opencode.core.diagnostics

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.tuning.DeveloperMode
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScrollSurface { Sidebar, Transcript }

/** Main-thread only, including the window listener and the export snapshot. */
class ScrollFrames(enabled: Boolean = false) {
    internal class Counts {
        var scrolls = 0L
        var frames = 0L
        var late = 0L
        var worstNs = 0L

        fun add(totalNs: Long, deadlineNs: Long) {
            if (totalNs <= 0 || deadlineNs <= 0) return
            frames++
            if (totalNs > deadlineNs) late++
            worstNs = maxOf(worstNs, totalNs)
        }
    }

    private fun newRecording() = ScrollSurface.entries.associateWith { Counts() }
    private val active = MutableStateFlow(if (enabled) newRecording() else null)
    internal val recordings = active.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (enabled != (active.value != null)) active.value = if (enabled) newRecording() else null
    }

    fun exportText(): String? = active.value?.let { recording ->
        buildString {
            appendLine("surface,scrolls,total_frames,late_frames,worst_frame_ns")
            for ((surface, c) in recording) appendLine("${surface.name},${c.scrolls},${c.frames},${c.late},${c.worstNs}")
        }
    }

    companion object Key : DependencyKey<ScrollFrames> {
        override val liveValue by lazy { ScrollFrames(DeveloperMode.isEnabled) }
        override val testValue get() = ScrollFrames()
    }
}

@Composable
fun ScrollFrameEffect(surface: ScrollSurface, list: LazyListState) {
    val recorder = remember { dependency(ScrollFrames) }
    val recording by recorder.recordings.collectAsState()
    val counts = recording?.get(surface) ?: return
    if (!list.isScrollInProgress) return
    val window = LocalView.current.context.activityWindow() ?: return
    DisposableEffect(counts, list, window) {
        var observing = true
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (observing && recorder.recordings.value?.get(surface) === counts && list.isScrollInProgress) {
                counts.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.DEADLINE))
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        counts.scrolls++
        onDispose {
            observing = false
            window.removeOnFrameMetricsAvailableListener(listener)
        }
    }
}

private tailrec fun Context.activityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.activityWindow()
    else -> null
}
