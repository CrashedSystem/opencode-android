@file:PortedFrom("OpenCode/Transcript/TranscriptLayoutProbe.swift", "553a407")

package ai.opencode.mobilecode

import android.os.Looper

object InternalBuild {
    val isEnabled: Boolean get() = BuildConfig.DEBUG
}

class TranscriptLayoutProbe {
    var rowKind = "list"
    var sessionID: String? = null
    private var epoch = 0L
    var depth = 0
        private set
    var passes = 0
        private set
    var maximumDepth = 0
        private set

    fun enter(phase: String) {
        if (!InternalBuild.isEnabled) return
        ensureObserver()
        if (epoch != turn) {
            epoch = turn
            passes = 0
        }
        depth += 1
        passes += 1
        maximumDepth = maxOf(maximumDepth, depth)
        if (depth == 4 || depth == 8 || passes == 128 || passes == 512) {
            LabMarks.mark("transcript_layout_pressure($phase,$rowKind,depth=$depth,passes=$passes)", sessionID)
        }
        if (BuildConfig.DEBUG && System.getProperty("lab-layout-trap") == "YES") {
            check(depth < 16 && passes < 1024) { "Transcript layout did not settle: $phase, $rowKind" }
        }
    }

    fun leave() {
        if (!InternalBuild.isEnabled) return
        depth -= 1
    }

    companion object {
        private var turn = 1L
        private var installed = false

        private fun ensureObserver() {
            if (installed) return
            val looper = Looper.getMainLooper() ?: return
            installed = true
            looper.queue.addIdleHandler {
                turn += 1
                true
            }
        }
    }
}

class TranscriptHostingCell {
    val layoutProbe = TranscriptLayoutProbe()

    fun layoutSubviews() {
        layoutProbe.enter("cell-layout")
        layoutProbe.leave()
    }

    fun preferredLayoutAttributesFitting() {
        layoutProbe.enter("cell-sizing")
        layoutProbe.leave()
    }
}
