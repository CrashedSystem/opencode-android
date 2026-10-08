@file:PortedFrom("OpenCode/Transcript/TranscriptScrollBehavior.swift", "d50ae98")

package ai.opencode.mobilecode

class TranscriptScrollBehavior {
    sealed class Mode {
        data object following : Mode()
        data object browsing : Mode()
    }

    data class Viewport(
        val offsetY: Float,
        val contentHeight: Float,
        val visibleHeight: Float,
        val trailingSpace: Float,
        val canRequestEarlierHistory: Boolean,
        val isUserScrolling: Boolean = false,
    )

    sealed class Event {
        data class dragBegan(val viewport: Viewport) : Event()
        data class userScrolled(val viewport: Viewport) : Event()
        data class userSettled(val viewport: Viewport, val contentArrived: Boolean) : Event()
        data object userLeftBottom : Event()
        data class layoutChanged(val viewport: Viewport) : Event()
        data class contentChanged(
            val anchorDelta: Float,
            val appended: Boolean,
            val viewport: Viewport,
            val layoutReady: Boolean,
        ) : Event()
        data class endShrank(val viewport: Viewport) : Event()
        data class disclosureToggled(val viewport: Viewport) : Event()
        data object earlierHistoryRequestCompleted : Event()
        data object jumpToLatestTapped : Event()
        data object sessionChanged : Event()
    }

    sealed class Command {
        data class adjustOffset(val by: Float) : Command()
        data class scrollToBottom(val animated: Boolean) : Command()
        data class setTrailingSpace(val space: Float) : Command()
        data object requestEarlierHistory : Command()
        data class showJumpButton(val show: Boolean) : Command()
    }

    var mode: Mode = Mode.following
        private set

    private var requestedEarlierHistory = false
    private var dragStartOffsetY: Float? = null

    fun handle(event: Event): List<Command> {
        val commands = mutableListOf<Command>()
        when (event) {
            is Event.dragBegan -> dragStartOffsetY = if (mode == Mode.following) event.viewport.offsetY else null
            is Event.userScrolled -> {
                var viewport = event.viewport
                val positionFloor = positionFloor(viewport)
                if (positionFloor < viewport.trailingSpace) {
                    viewport = viewport.copy(trailingSpace = positionFloor)
                    commands += Command.setTrailingSpace(positionFloor)
                }
                // Under a following finger only its own travel leaves: rows arriving
                // or a follower still gliding put the end farther off, not the reader.
                val leaving = dragStartOffsetY?.let { it - viewport.offsetY } ?: distanceFromBottom(viewport)
                if (mode == Mode.following && leaving > leaveDistance) {
                    mode = Mode.browsing
                    commands += Command.showJumpButton(true)
                }
                requestEarlierHistoryIfNeeded(viewport, commands)
            }
            is Event.userSettled -> {
                // Without a following drag (an assistive scroll, or a reader already
                // browsing), how far up they rest is their distance from the bottom.
                val travelled = dragStartOffsetY?.let { it - event.viewport.offsetY } ?: distanceFromBottom(event.viewport)
                dragStartOffsetY = null
                // Content arriving keeps a short drag following, but not one that
                // already left: held space would otherwise call any rest the bottom.
                val keepsFollowing = travelled <= restDistance || event.contentArrived && mode == Mode.following
                if (distanceFromBottom(event.viewport) <= restDistance && keepsFollowing) {
                    if (mode == Mode.browsing) {
                        mode = Mode.following
                        commands += Command.showJumpButton(false)
                    }
                    commands += Command.scrollToBottom(false)
                } else if (mode == Mode.following && keepsFollowing) {
                    // Short of an end that moved on (the follower was still gliding to
                    // it), a drag that carried the reader nowhere up rides on.
                    commands += Command.scrollToBottom(false)
                } else if (mode == Mode.following) {
                    mode = Mode.browsing
                    commands += Command.showJumpButton(true)
                }
                requestEarlierHistoryIfNeeded(event.viewport, commands)
            }
            Event.userLeftBottom -> {
                dragStartOffsetY = null
                if (mode == Mode.following) {
                    mode = Mode.browsing
                    commands += Command.showJumpButton(true)
                }
            }
            is Event.layoutChanged -> {
                when {
                    mode == Mode.following && event.viewport.isUserScrolling -> holdPosition(event.viewport, commands)
                    mode == Mode.following -> {
                        drainTrailingSpace(event.viewport, commands)
                        commands += Command.scrollToBottom(false)
                    }
                    else -> holdPosition(event.viewport, commands)
                }
                requestEarlierHistoryIfNeeded(event.viewport, commands)
            }
            is Event.contentChanged -> {
                if (event.layoutReady) {
                    if (event.anchorDelta != 0f) {
                        commands += Command.adjustOffset(event.anchorDelta)
                        if (event.anchorDelta > 0f) requestedEarlierHistory = false
                    } else if (event.appended) {
                        when (mode) {
                            Mode.following -> {
                                drainTrailingSpace(event.viewport, commands)
                                commands += Command.scrollToBottom(false)
                            }
                            Mode.browsing -> holdPosition(event.viewport, commands)
                        }
                    }
                }
            }
            is Event.endShrank -> {
                if (mode == Mode.following) {
                    val positionFloor = positionFloor(event.viewport)
                    if (positionFloor > event.viewport.trailingSpace) commands += Command.setTrailingSpace(positionFloor)
                }
            }
            is Event.disclosureToggled -> {
                var viewport = event.viewport
                val held = minOf(viewport.trailingSpace, positionFloor(viewport))
                if (held != viewport.trailingSpace) {
                    viewport = viewport.copy(trailingSpace = held)
                    commands += Command.setTrailingSpace(held)
                }
                val atBottom = distanceFromBottom(viewport) <= restDistance
                if (mode == Mode.following && !atBottom) {
                    mode = Mode.browsing
                    commands += Command.showJumpButton(true)
                } else if (mode == Mode.browsing && atBottom) {
                    mode = Mode.following
                    commands += Command.showJumpButton(false)
                }
            }
            Event.earlierHistoryRequestCompleted -> requestedEarlierHistory = false
            Event.jumpToLatestTapped -> {
                mode = Mode.following
                dragStartOffsetY = null
                return listOf(Command.setTrailingSpace(0f), Command.showJumpButton(false), Command.scrollToBottom(true))
            }
            Event.sessionChanged -> {
                mode = Mode.following
                dragStartOffsetY = null
                requestedEarlierHistory = false
                return listOf(Command.setTrailingSpace(0f), Command.showJumpButton(false))
            }
        }
        return commands
    }

    private fun requestEarlierHistoryIfNeeded(viewport: Viewport, commands: MutableList<Command>) {
        if (!viewport.canRequestEarlierHistory || viewport.offsetY >= viewport.visibleHeight || requestedEarlierHistory) return
        requestedEarlierHistory = true
        commands += Command.requestEarlierHistory
    }

    companion object {
        const val leaveDistance = 120f
        const val restDistance = 1f

        fun positionFloor(viewport: Viewport): Float =
            maxOf(0f, viewport.offsetY - maxOf(0f, viewport.contentHeight - viewport.visibleHeight))

        fun distanceFromBottom(viewport: Viewport): Float =
            maxOf(0f, viewport.contentHeight + viewport.trailingSpace - viewport.offsetY - viewport.visibleHeight)

        private fun holdPosition(viewport: Viewport, commands: MutableList<Command>) {
            val positionFloor = positionFloor(viewport)
            if (positionFloor != viewport.trailingSpace) commands += Command.setTrailingSpace(positionFloor)
        }

        private fun drainTrailingSpace(viewport: Viewport, commands: MutableList<Command>) {
            if (viewport.trailingSpace <= 0f) return
            val drained = minOf(viewport.trailingSpace, positionFloor(viewport))
            if (drained != viewport.trailingSpace) commands += Command.setTrailingSpace(drained)
        }
    }
}
