package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// The follower, measured from the list's own layout frame by frame: a
/// following reader rides a streaming reply without a jump larger than one
/// frame of `arrivalSpeed`, and a browsing reader is never moved.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class TranscriptFollowTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    private val bridge = SessionScrollEdgeBridge()
    private var showsJumpButton by mutableStateOf(false)
    private val density = 3f
    private val composerInset = 120f * density
    private val list: LazyListState get() = bridge.scrollView!!

    /// One frame of `arrivalSpeed` (1,080 pt/s) at the test clock's 16 ms frames, in pixels.
    private val frameCap = 1_080f * 0.016f * density

    @Test
    fun followingReaderRidesSixHundredTokensWithoutJumping() {
        val base = SessionViewFixture.longConversation(streamUpdates = 0)
        var reply = "Streaming reply."
        val store = streamingStore(base + streamed(reply))
        show(store)
        val start = remaining()
        assertTrue("The session opens on its bottom: $start", start <= 1f)

        var anchor = firstVisible()
        var maxStep = 0f
        var maxBackward = 0f
        var maxRemaining = 0f
        repeat(600) { index ->
            reply += token(index)
            compose.runOnUiThread { store.send(update(base + streamed(reply))) }
            compose.mainClock.advanceTimeByFrame()
            val step = movement(anchor)
            maxStep = max(maxStep, abs(step))
            maxBackward = max(maxBackward, step)
            maxRemaining = max(maxRemaining, remaining())
            anchor = firstVisible()
        }
        compose.mainClock.advanceTimeBy(2_000)

        assertTrue("A frame moved the reader ${maxStep / density} pt, more than ${frameCap / density}", maxStep <= frameCap + 1f)
        assertTrue("Content moved back down ${maxBackward / density} pt", maxBackward <= 0.5f * density)
        assertTrue("The follower fell ${maxRemaining / density} pt behind the end", maxRemaining <= 80f * density)
        assertEquals("Pinned to the bottom once the stream ends", 0f, remaining(), 1f)
        assertTrue("Following readers see no jump button", !showsJumpButton)
    }

    @Test
    fun aTallArrivalGlidesInCappedPerFrame() {
        val base = SessionViewFixture.longConversation(streamUpdates = 0)
        val store = streamingStore(base + streamed("Short reply."))
        show(store)
        var anchor = firstVisible()
        val tall = "Short reply.\n\n" + (1..30).joinToString("\n\n") { "Line $it of a chunk that arrives in one batch." }
        compose.runOnUiThread { store.send(update(base + streamed(tall))) }
        var maxStep = 0f
        var frames = 0
        while (frames < 240 && (frames < 2 || remaining() > 1f)) {
            compose.mainClock.advanceTimeByFrame()
            maxStep = max(maxStep, abs(movement(anchor)))
            anchor = firstVisible()
            frames += 1
        }
        assertTrue("Glided ${maxStep / density} pt in one frame", maxStep <= frameCap + 1f)
        assertTrue("Took $frames frames, so it glided rather than landed", frames > 10)
        assertEquals(0f, remaining(), 1f)
    }

    @Test
    fun browsingReaderIsNotMovedByTheStream() {
        val base = SessionViewFixture.longConversation(streamUpdates = 0)
        var reply = "Streaming reply."
        val store = streamingStore(base + streamed(reply))
        show(store)
        compose.onNodeWithTag("transcript-list").performTouchInput { swipeDown(durationMillis = 300) }
        compose.mainClock.advanceTimeBy(1_500)
        assertTrue("Scrolling up shows Jump to Latest", showsJumpButton)

        val anchor = firstVisible()
        repeat(120) { index ->
            reply += token(index)
            compose.runOnUiThread { store.send(update(base + streamed(reply))) }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("The browsing reader stayed put at token $index", 0f, movement(anchor), 0.5f)
        }
    }

    @Test
    fun reduceMotionLandsArrivalsAtOnce() {
        val base = SessionViewFixture.longConversation(streamUpdates = 0)
        val store = streamingStore(base + streamed("Short reply."))
        show(store, reduceMotion = true)
        val tall = "Short reply.\n\n" + (1..30).joinToString("\n\n") { "Line $it of a chunk that arrives in one batch." }
        compose.runOnUiThread { store.send(update(base + streamed(tall))) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0f, remaining(), 1f)
    }

    private fun streamingStore(items: List<OpenCodeTranscriptItem>) =
        Store(initialState = { SessionViewFixture.state(items, isRunning = true) }) {
            // Stands in for the reducer's stream projection: the transcript it accepted.
            Reduce<SessionFeature.State, SessionFeature.Action> { action ->
                if (action is SessionFeature.Action.acceptedTranscriptUpdated) state.items = action.items
                Effect.none
            }
        }

    private fun update(items: List<OpenCodeTranscriptItem>) =
        SessionFeature.Action.acceptedTranscriptUpdated(SessionViewFixture.session.id, items)

    private fun streamed(text: String) = SessionViewFixture.assistant("msg_stream", text)

    private fun token(index: Int): String = when {
        index % 48 == 47 -> "\n\n"
        else -> listOf(" the", " follower", " glides", " as", " each", " token", " lands")[index % 7]
    }

    private fun show(store: Store<SessionFeature.State, SessionFeature.Action>, reduceMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(
                    LocalReduceMotion provides reduceMotion,
                    LocalTranscriptClock provides { compose.mainClock.currentTime * 1_000_000 },
                ) {
                    Box(Modifier.fillMaxSize().background(SystemColors.systemBackground)) {
                        SessionTranscriptView(
                            store = store,
                            scrollEdgeBridge = bridge,
                            dismissKeyboard = {},
                            onPreviewImage = {},
                            onOpenFile = {},
                            showsJumpButton = Binding({ showsJumpButton }, { showsJumpButton = it }),
                            jumpRequest = 0,
                            composerInset = { composerInset },
                            keyboardIsMoving = { false },
                        )
                    }
                }
            }
        }
        // The opening settle passes before arrivals glide.
        compose.mainClock.advanceTimeBy(1_500)
    }

    /// How far the list can still scroll toward its end; the last row
    /// beyond the viewport is far.
    private fun remaining(): Float {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.last()
        if (last.index != info.totalItemsCount - 1) return Float.MAX_VALUE
        return max(0, last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
    }

    private fun firstVisible(): Pair<Any, Int> {
        val first = list.layoutInfo.visibleItemsInfo.first()
        return first.key to first.offset
    }

    /// The on-screen movement of the row first visible last frame.
    private fun movement(anchor: Pair<Any, Int>): Float {
        val now = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.first } ?: return 0f
        return (now.offset - anchor.second).toFloat()
    }
}
