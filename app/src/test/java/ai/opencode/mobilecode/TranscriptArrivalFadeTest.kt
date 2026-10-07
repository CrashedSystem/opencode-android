package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// Streamed text fades in where it lands, as `ArrivalFade` and `ArrivingText`
/// do on iOS: new characters start clear and settle within the fade, earlier
/// text stays drawn, and a block that arrives whole fades in whole.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-mdpi")
class TranscriptArrivalFadeTest {
    @get:Rule
    val compose = createComposeRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "pw")
    private val earlier = "Earlier words stay put."
    private val newer = " Newer words fade in."

    @Test
    fun arrivingTextAndBlocksFadeInAndHistoryDoesNot() {
        val reveal = TranscriptTextReveal()
        val markdown = mutableStateOf(earlier)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ForcedAppearance(false) {
                SystemTheme {
                    CompositionLocalProvider(LocalTranscriptTextReveal provides reveal) {
                        Column(Modifier.background(SystemColors.systemBackground).padding(16.dp)) {
                            MarkdownBlocksView(
                                blocks = OpenCodeMarkdownBlock.blocks(from = markdown.value),
                                server = server,
                                directory = null,
                                modifier = Modifier.fillMaxWidth(),
                                revealItem = "reply",
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("the reply's first render is history and shows at once", ink(earlier, 0, earlier.length) > 0)

        stream(markdown, earlier + newer)
        assertTrue("earlier characters stay drawn", ink(earlier, 0, earlier.length) > 0)
        assertEquals("arriving characters start clear", 0, ink(newer.trim(), earlier.length + 1, earlier.length + newer.length))

        compose.mainClock.advanceTimeBy(300)
        assertTrue("arriving characters are drawn once their fade has run", ink(newer.trim(), earlier.length + 1, earlier.length + newer.length) > 0)

        val next = "A whole paragraph arrives at once."
        stream(markdown, earlier + newer + "\n\n" + next)
        assertEquals("a block that arrives whole starts clear", 0, ink(next, 0, next.length))
        compose.mainClock.advanceTimeBy(300)
        assertTrue("and is drawn once its fade has run", ink(next, 0, next.length) > 0)
    }

    /// One stream update, drawn in the next frame.
    private fun stream(markdown: MutableState<String>, text: String) {
        markdown.value = text
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /// Dark pixels drawn over `text[start, end)` in the paragraph that shows `fragment`.
    private fun ink(fragment: String, start: Int, end: Int): Int {
        val node = compose.onNode(hasText(fragment, substring = true))
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val area = (start until end).map { layout.getBoundingBox(it) }.reduce { a, b -> a.union(b) }
        val pixels = node.captureToImage().toPixelMap()
        return area.pixels().count { (x, y) -> x < pixels.width && y < pixels.height && pixels[x, y].red < 0.5f }
    }

    private fun Rect.union(other: Rect) = Rect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

    private fun Rect.pixels() = (top.toInt() until bottom.toInt()).flatMap { y -> (left.toInt() until right.toInt()).map { x -> x to y } }
}
