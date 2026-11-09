package ai.opencode.feature.transcript.list

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import kotlin.math.max

/**
 * Pure decision-maker for transcript scrolling (iOS `TranscriptScrollBehavior`, ported line for
 * line). The list feeds it events and executes the returned commands; it never touches views.
 *
 * The reader either follows the bottom, where new content arrives and is followed, or browses,
 * where nothing but their own hand moves the view. Sending a message returns them to the end.
 */
class TranscriptScrollBehavior {
    enum class Mode { Following, Browsing }

    data class Viewport(
        val offsetY: Float,
        val contentHeight: Float,
        val visibleHeight: Float,
        val trailingSpace: Float,
        val canRequestEarlierHistory: Boolean,
        /** The reader's finger (or its deceleration) owns the list. */
        val isUserScrolling: Boolean = false,
        /**
         * Android: a browsing reader's row, saved across process death, isn't listed yet (its
         * history page is still to load). The list clamped short of it is not the reader at the
         * end, and earlier history is read until the row is there.
         */
        val holdsRestoredRow: Boolean = false,
    )

    sealed interface Event {
        data class DragBegan(val viewport: Viewport) : Event
        data class UserScrolled(val viewport: Viewport) : Event
        /** A drag or its fling came to rest, or an assistive scroll moved the list. */
        data class UserSettled(val viewport: Viewport, val contentArrived: Boolean) : Event
        /** The reader asked to leave the bottom. */
        data object UserLeftBottom : Event
        data class LayoutChanged(val viewport: Viewport) : Event
        /** The list moved the reader with the viewport: not the reader's own travel. */
        data class ViewportRode(val by: Float) : Event
        data class ContentChanged(val anchorDelta: Float, val appended: Boolean, val viewport: Viewport, val layoutReady: Boolean) : Event
        /** The reader opened or closed a row's disclosure, which stays where it is. */
        data class DisclosureToggled(val viewport: Viewport) : Event
        data object EarlierHistoryRequestCompleted : Event
        data object JumpToLatestTapped : Event
        /** The reader sent a message from the composer. */
        data object MessageSent : Event
        /** What arrived while the reader was away slides into view: it moves the list, not the follower. */
        data object RevealBegan : Event
        data class RevealEnded(val atEnd: Boolean) : Event
        data object SessionChanged : Event
    }

    sealed interface Command {
        data class AdjustOffset(val by: Float) : Command
        data class ScrollToBottom(val animated: Boolean) : Command
        data class SetTrailingSpace(val space: Float) : Command
        data object RequestEarlierHistory : Command
        data class ShowJumpButton(val shows: Boolean) : Command
    }

    var mode: Mode = Mode.Following
        private set
    /** Jump to latest's visibility. */
    var showsJumpButton: Boolean = false
        private set
    private var requestedEarlierHistory = false
    /** Put at the New divider with little above it, the reader hasn't asked for older history. */
    private var holdsEarlierHistory = false
    /** Where a following reader's drag began. */
    private var dragStartOffsetY: Float? = null
    private var lastScrolledOffsetY: Float? = null
    private var lastScrolledDistance: Float? = null

    fun handle(event: Event): List<Command> {
        val commands = mutableListOf<Command>()
        when (event) {
            is Event.DragBegan -> {
                holdsEarlierHistory = false
                dragStartOffsetY = if (mode == Mode.Following) event.viewport.offsetY else null
                lastScrolledOffsetY = event.viewport.offsetY
                lastScrolledDistance = distanceFromBottom(event.viewport)
            }

            is Event.UserScrolled -> {
                var viewport = event.viewport
                val positionFloor = positionFloor(viewport)
                if (positionFloor < viewport.trailingSpace) {
                    viewport = viewport.copy(trailingSpace = positionFloor)
                    commands += Command.SetTrailingSpace(positionFloor)
                }
                // Under a following finger only its own travel leaves.
                val leaving = dragStartOffsetY?.let { it - viewport.offsetY } ?: distanceFromBottom(viewport)
                if (mode == Mode.Following && leaving > LEAVE_DISTANCE) {
                    mode = Mode.Browsing
                    setJumpButton(true, commands)
                }
                val distance = distanceFromBottom(viewport)
                val arrived = lastScrolledDistance?.let { it > REST_DISTANCE } ?: true
                val towardEnd = lastScrolledOffsetY?.let { viewport.offsetY > it + 0.5f } ?: true
                val towardTop = lastScrolledOffsetY?.let { viewport.offsetY < it - 0.5f } ?: false
                lastScrolledOffsetY = viewport.offsetY
                lastScrolledDistance = distance
                if (distance <= REST_DISTANCE && viewport.trailingSpace == 0f && (arrived || towardEnd)) {
                    setJumpButton(false, commands)
                } else if (mode == Mode.Browsing && distance > LEAVE_DISTANCE) {
                    setJumpButton(true, commands)
                }
                // Only a browsing reader's way up: a following one nudging the list or
                // bouncing back off the end isn't heading for older history.
                var lookahead = 1f
                if (mode == Mode.Browsing && towardTop && distance > REST_DISTANCE) {
                    lookahead = UPWARD_PAGING_LOOKAHEAD
                }
                requestEarlierHistoryIfNeeded(viewport, commands, lookahead)
            }

            is Event.UserSettled -> {
                val viewport = event.viewport
                holdsEarlierHistory = false
                val travelled = dragStartOffsetY?.let { it - viewport.offsetY } ?: distanceFromBottom(viewport)
                dragStartOffsetY = null
                lastScrolledOffsetY = null
                lastScrolledDistance = null
                // Only the bottom itself is following. Content arriving keeps a short drag following.
                val keepsFollowing = travelled <= REST_DISTANCE || (event.contentArrived && mode == Mode.Following)
                if (distanceFromBottom(viewport) <= REST_DISTANCE) {
                    if (mode == Mode.Browsing) {
                        mode = Mode.Following
                        setJumpButton(false, commands)
                    }
                    commands += Command.ScrollToBottom(animated = false)
                } else if (mode == Mode.Following && keepsFollowing) {
                    commands += Command.ScrollToBottom(animated = false)
                } else if (mode == Mode.Following) {
                    mode = Mode.Browsing
                    setJumpButton(true, commands)
                }
                // At rest the button says what the mode is.
                setJumpButton(mode == Mode.Browsing, commands)
                requestEarlierHistoryIfNeeded(viewport, commands)
            }

            Event.UserLeftBottom -> {
                holdsEarlierHistory = false
                dragStartOffsetY = null
                lastScrolledOffsetY = null
                lastScrolledDistance = null
                mode = Mode.Browsing
                setJumpButton(true, commands)
            }

            is Event.ViewportRode -> {
                dragStartOffsetY = dragStartOffsetY?.plus(event.by)
                lastScrolledOffsetY = lastScrolledOffsetY?.plus(event.by)
            }

            is Event.LayoutChanged -> {
                val viewport = event.viewport
                if (viewport.isUserScrolling) {
                    holdPosition(viewport, commands)
                    requestEarlierHistoryIfNeeded(viewport, commands)
                } else if (mode == Mode.Following) {
                    commands += Command.ScrollToBottom(animated = false)
                } else {
                    followIfPastTheEnd(viewport, commands)
                    if (mode == Mode.Browsing) requestEarlierHistoryIfNeeded(viewport, commands)
                }
            }

            is Event.ContentChanged -> {
                if (!event.layoutReady) return commands
                val viewport = event.viewport
                val anchorDelta = event.anchorDelta
                if (anchorDelta != 0f) {
                    dragStartOffsetY = dragStartOffsetY?.plus(anchorDelta)
                    lastScrolledOffsetY = lastScrolledOffsetY?.plus(anchorDelta)
                    commands += Command.AdjustOffset(anchorDelta)
                    if (anchorDelta > 0) requestedEarlierHistory = false
                    if (mode == Mode.Browsing && !viewport.isUserScrolling) {
                        followIfPastTheEnd(viewport.copy(offsetY = viewport.offsetY + anchorDelta), commands)
                    }
                } else if (viewport.isUserScrolling) {
                    holdPosition(viewport, commands)
                } else if (mode == Mode.Following) {
                    if (event.appended) commands += Command.ScrollToBottom(animated = false)
                } else {
                    followIfPastTheEnd(viewport, commands)
                }
            }

            is Event.DisclosureToggled -> {
                val atBottom = distanceFromBottom(event.viewport) <= REST_DISTANCE
                if (mode == Mode.Following && !atBottom) {
                    holdsEarlierHistory = true
                    mode = Mode.Browsing
                } else if (mode == Mode.Browsing && atBottom) {
                    holdsEarlierHistory = false
                    mode = Mode.Following
                }
                setJumpButton(mode == Mode.Browsing, commands)
            }

            Event.EarlierHistoryRequestCompleted -> {
                // Completion re-arms paging; never request synchronously here.
                requestedEarlierHistory = false
            }

            Event.MessageSent, Event.JumpToLatestTapped -> {
                // Following, a sent message arrives like any other row.
                if (event == Event.MessageSent && mode != Mode.Browsing) return commands
                holdsEarlierHistory = false
                mode = Mode.Following
                dragStartOffsetY = null
                lastScrolledOffsetY = null
                lastScrolledDistance = null
                showsJumpButton = false
                return listOf(Command.ShowJumpButton(false), Command.ScrollToBottom(animated = true))
            }

            Event.RevealBegan -> {
                dragStartOffsetY = null
                lastScrolledOffsetY = null
                lastScrolledDistance = null
                mode = Mode.Browsing
            }

            is Event.RevealEnded -> {
                holdsEarlierHistory = !event.atEnd
                mode = if (event.atEnd) Mode.Following else Mode.Browsing
                setJumpButton(!event.atEnd, commands)
            }

            Event.SessionChanged -> {
                holdsEarlierHistory = false
                mode = Mode.Following
                dragStartOffsetY = null
                lastScrolledOffsetY = null
                lastScrolledDistance = null
                requestedEarlierHistory = false
                showsJumpButton = false
                return listOf(Command.SetTrailingSpace(0f), Command.ShowJumpButton(false))
            }
        }
        return commands
    }

    private fun setJumpButton(shows: Boolean, commands: MutableList<Command>) {
        if (shows == showsJumpButton) return
        showsJumpButton = shows
        commands += Command.ShowJumpButton(shows)
    }

    /**
     * [lookahead]: how many screens above the reader an earlier page is asked for. One at rest;
     * more while the reader scrolls up toward it ([UPWARD_PAGING_LOOKAHEAD]), so the page lands
     * before they reach the top.
     */
    private fun requestEarlierHistoryIfNeeded(viewport: Viewport, commands: MutableList<Command>, lookahead: Float = 1f) {
        if (!viewport.canRequestEarlierHistory || holdsEarlierHistory || (viewport.offsetY >= viewport.visibleHeight * lookahead && !viewport.holdsRestoredRow) ||
            requestedEarlierHistory
        ) {
            return
        }
        requestedEarlierHistory = true
        commands += Command.RequestEarlierHistory
    }

    private fun holdPosition(viewport: Viewport, commands: MutableList<Command>) {
        val floor = positionFloor(viewport)
        if (floor != viewport.trailingSpace) commands += Command.SetTrailingSpace(floor)
    }

    /** A browsing reader the end came up to is at the bottom now: they follow again. */
    private fun followIfPastTheEnd(viewport: Viewport, commands: MutableList<Command>) {
        if (viewport.holdsRestoredRow || distanceFromBottom(viewport) > REST_DISTANCE) return
        mode = Mode.Following
        setJumpButton(false, commands)
        commands += Command.ScrollToBottom(animated = false)
    }

    companion object {
        /**
         * For the list's `rememberSaveable`: a browsing reader stays browsing when the activity is
         * recreated, where iOS's list only redraws, and Jump to latest stays as it was (the screen
         * saves what it showed too, so the pill is there from the first frame, F-38). The rest
         * belongs to one gesture and starts over.
         */
        val Saver: Saver<TranscriptScrollBehavior, Any> = listSaver(
            save = { listOf(it.mode == Mode.Browsing, it.showsJumpButton) },
            restore = { saved ->
                TranscriptScrollBehavior().apply {
                    if (saved[0] as Boolean) mode = Mode.Browsing
                    showsJumpButton = saved[1] as Boolean
                }
            },
        )

        /** A drag farther than this from the bottom, or from where a following reader started it, leaves the bottom. */
        const val LEAVE_DISTANCE = 120f
        /** Coming to rest within this of the bottom is following again. */
        const val REST_DISTANCE = 1f
        /** While the reader scrolls up, earlier history is asked for this many screens before the top. */
        const val UPWARD_PAGING_LOOKAHEAD = 2.5f

        /** How far past the end of the content the reader stands. */
        fun positionFloor(viewport: Viewport): Float =
            max(0f, viewport.offsetY - max(0f, viewport.contentHeight - viewport.visibleHeight))

        fun distanceFromBottom(viewport: Viewport): Float =
            max(0f, viewport.contentHeight + viewport.trailingSpace - viewport.offsetY - viewport.visibleHeight)
    }
}
