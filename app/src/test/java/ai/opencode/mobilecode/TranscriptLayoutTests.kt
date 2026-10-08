@file:PortedFrom("OpenCodeTests/TranscriptLayoutTests.swift", "16b40f7")

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
        val harness = ListHarness(compose)
        harness.show()
        compose.onNodeWithTag("transcript-list").performScrollToIndex(20)
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread {
            harness.jumpRequest = 1
            Snapshot.sendApplyNotifications()
        }
        repeat(60) {
            // Model the composer's measurement settling during the jump.
            compose.runOnUiThread {
                harness.composerInset -= 1f
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(1_500)
        val info = harness.list.layoutInfo
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

class JustifiedImageRowsTests {
    private val width = 402.0

    @Test
    fun aLoneImageFillsTheWidthAtItsOwnShape() {
        val rows = JustifiedImageRows(listOf(16.0 / 9), width)
        assertEquals(1, rows.frames.size)
        assertTrue(abs(rows.frames[0].width - width) < 0.01)
        assertTrue(abs(rows.frames[0].height - width * 9 / 16) < 0.01)
    }

    @Test
    fun aLoneTallScreenshotStopsAtTheCapWithoutCropping() {
        val aspect = 1179.0 / 2556
        val frame = JustifiedImageRows(listOf(aspect), width).frames[0]
        assertEquals(JustifiedImageRows.singleMaxHeight, frame.height, 0.0)
        assertTrue("same shape, just smaller", abs(frame.width / frame.height - aspect) < 0.001)
        assertEquals(0.0, frame.minX, 0.0)
    }

    @Test
    fun everyImageKeepsItsShapeAndFullRowsFillTheWidth() {
        val aspects = listOf(1.0, 1179.0 / 2556, 4.0 / 3, 4.0, 1.0, 0.75, 16.0 / 9)
        val rows = JustifiedImageRows(aspects, width)
        assertEquals("nothing hidden", aspects.size, rows.frames.size)
        for ((frame, aspect) in rows.frames.zip(aspects)) {
            assertTrue(abs(frame.width / frame.height - aspect) < 0.001)
            assertTrue(frame.maxX <= width + 0.01)
        }
        // Rows: frames sharing a y. Each fills the width, unless capped.
        for (row in rows.frames.groupBy { (it.minY * 100).roundToInt() }.values) {
            val right = row.maxOf { it.maxX }
            val height = row[0].height
            assertTrue("a row shares one height", row.all { abs(it.height - height) < 0.01 })
            assertTrue(abs(right - width) < 0.01 || height >= width * 0.9 - 0.01)
        }
        assertTrue(abs(rows.height - rows.frames.maxOf { it.maxY }) < 0.01)
    }

    @Test
    fun aPanoramaTakesARowToItself() {
        // The four images one step read in the image test session.
        val rows = JustifiedImageRows(listOf(4.0 / 3, 4.0, 1.0, 0.75), width)
        assertTrue(abs(rows.frames[1].width - width) < 0.01)
        assertEquals("icon and portrait share a row", rows.frames[2].minY, rows.frames[3].minY, 0.0)
    }
}
