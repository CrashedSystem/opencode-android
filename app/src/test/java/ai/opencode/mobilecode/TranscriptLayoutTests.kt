@file:PortedFrom("OpenCodeTests/TranscriptLayoutTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.AttributedString
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class TranscriptLayoutTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    /// A layout pass that changes the viewport while a scroll command is
    /// carrying the list must not issue another scroll command from inside
    /// the layout: the composer inset changes every frame of a jump, and the
    /// jump still lands on the measured tail.
    @Test
    fun layoutDuringScrollCommandDoesNotRecursivelyScroll() {
        val items = (0 until 60).map { OpenCodeTranscriptItem.userText(id = "m$it", text = "Message $it") }
        val store = Store(initialState = { SessionViewFixture.state(items).apply { visibleLimit = items.size } }) {
            Reduce<SessionFeature.State, SessionFeature.Action> { _ -> Effect.none }
        }
        val bridge = SessionScrollEdgeBridge()
        var jumpRequest by mutableIntStateOf(0)
        var inset by mutableFloatStateOf(240f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalTranscriptClock provides { compose.mainClock.currentTime * 1_000_000 }) {
                    Box(Modifier.fillMaxSize().background(SystemColors.systemBackground)) {
                        SessionTranscriptView(
                            store = store,
                            scrollEdgeBridge = bridge,
                            dismissKeyboard = {},
                            onPreviewImage = {},
                            onOpenFile = {},
                            showsJumpButton = Binding({ false }, {}),
                            jumpRequest = jumpRequest,
                            composerInset = { inset },
                            keyboardIsMoving = { false },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("transcript-list").performScrollToIndex(20)
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread {
            jumpRequest = 1
            Snapshot.sendApplyNotifications()
        }
        repeat(60) {
            // Model the composer's measurement settling during the jump.
            compose.runOnUiThread {
                inset -= 1f
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(1_500)
        val info = bridge.scrollView!!.layoutInfo
        val last = info.visibleItemsInfo.last()
        assertEquals("The jump lands on the tail", info.totalItemsCount - 1, last.index)
        assertEquals(0, maxOf(0, last.offset + last.size + info.afterContentPadding - info.viewportEndOffset))
    }

    @Test
    fun reconfiguredTableSettlesAtFractionalOffsets() {
        var count by mutableIntStateOf(1)
        var offset by mutableFloatStateOf(0f)
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            SystemTheme {
                state = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    item(key = "table") {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            MarkdownTable(
                                header = listOf(AttributedString("Agent"), AttributedString("Status"), AttributedString("Next action")),
                                rows = (0 until count).map { i ->
                                    listOf(
                                        AttributedString("Agent $i"),
                                        AttributedString("A long wrapped status with several words that fills the capped column"),
                                        AttributedString("Continue"),
                                    )
                                },
                            )
                        }
                    }
                }
                androidx.compose.runtime.LaunchedEffect(offset) { state.scrollToItem(0, offset.roundToInt()) }
            }
        }
        for (iteration in 0 until 300) {
            count = 1 + iteration % 30
            offset = (iteration % 200) + 0.13f
            compose.waitForIdle()
        }
        val height = state.layoutInfo.visibleItemsInfo.first().size
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(height, state.layoutInfo.visibleItemsInfo.first().size)
        assertTrue("A 30-row table is taller than a screen: $height", height / 3f > 1_000)
    }

    @Test
    fun hairlineSnapsWithoutMovingItsMeasurement() {
        for (scale in listOf(2f, 3f)) {
            var y = -100f
            while (y <= 100f) {
                val offset = pixelOffset(y, scale)
                assertTrue(abs(offset) <= 0.5f / scale + 0.0001f)
                val snapped = (y + offset) * scale
                assertTrue(abs(snapped - snapped.roundToInt()) < 0.001f)
                y += 0.13f
            }
        }
    }
}
