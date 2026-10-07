@file:PortedFrom("OpenCodeTests/MarkdownBlockTests.swift", "553a407", partial = "highlighter and text reveal; parser, inline cache, and persisted color cases stay with OpenCodeClient")

package ai.opencode.mobilecode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MarkdownBlockTests {
    @Test
    fun highlightingPreservesTextAndColorsKeywords() {
        val source = """
            // add
            func add(a: Int) -> Int { return a + 1 }
        """.trimIndent()
        val highlighted = SyntaxHighlighter.highlight(source, "swift")
        assertEquals(source, highlighted.characters)
        val coloredWords = colored(highlighted)
        assertTrue(coloredWords.contains("func"))
        assertTrue(coloredWords.contains("return"))
        assertTrue(coloredWords.contains("Int"))
        assertTrue(coloredWords.contains("// add"))
        assertTrue(coloredWords.contains("1"))
    }

    @Test
    fun highlightingHandlesStringsAndUnknownLanguage() {
        val source = "print(\"hi there\") # note"
        val highlighted = SyntaxHighlighter.highlight(source, null)
        assertEquals(source, highlighted.characters)
        val coloredWords = colored(highlighted)
        assertTrue(coloredWords.contains("\"hi there\""))
        assertTrue(coloredWords.contains("# note"))
    }

    @Test
    fun highlightingKeepsUnicodeAndPartialTokenBoundaries() {
        val cases = listOf(
            Triple("swift", "🧑🏽‍💻 let café = \"e\u0301 🐈\" // τέλος\nreturn café", listOf("let", "\"e\u0301 🐈\"", "// τέλος", "return")),
            Triple("typescript", "const café = 'it\\'s 🐈'; /* 注釈 */ return 42", listOf("const", "'it\\'s 🐈'", "/* 注釈 */", "return", "42")),
            Triple("python", "def résumé():\n  return '👋' # 끝", listOf("def", "return", "'👋'", "# 끝")),
            Triple("swift", "let value = \"unfinished 🐈", listOf("let", "\"unfinished 🐈")),
            Triple("swift", "", emptyList()),
        )
        for ((language, source, tokens) in cases) {
            val highlighted = SyntaxHighlighter.highlight(source, language)
            assertEquals(source, highlighted.characters)
            assertEquals(tokens, colored(highlighted))
        }
    }

    @Test
    fun plainTextIsASingleParagraph() {
        assertEquals(1, OpenCodeMarkdownBlock.blocks(from = "just text").size)
    }

    @Test
    fun extractsHeadings() {
        val blocks = OpenCodeMarkdownBlock.blocks(from = "## Section\n\nBody text.\n")
        assertEquals(2, blocks.size)
        val heading = blocks[0].kind as OpenCodeMarkdownBlock.Kind.heading
        assertEquals(2, heading.level)
        assertEquals("Section", heading.text.characters)
        assertTrue(blocks[1].kind is OpenCodeMarkdownBlock.Kind.paragraph)
    }

    private fun colored(text: AttributedString) = text.runs.mapNotNull { run ->
        if (run.foregroundColor == null) null else text.characters.substring(run.start, run.end)
    }
}

class TranscriptTextRevealTests {
    @Test
    fun onlyTextThatArrivesAfterTheFirstRenderFades() {
        val reveal = TranscriptTextReveal()
        assertTrue(reveal.arrivals("reply", blocks("Hello"), 0.0).isEmpty())
        assertTrue(reveal.arrivals("reply", blocks("Hello"), 0.01).isEmpty())
        assertEquals(
            mapOf(0 to listOf(TranscriptTextReveal.Arrival(5, 0.05))),
            reveal.arrivals("reply", blocks("Hello there"), 0.05),
        )
        assertEquals(
            mapOf(0 to listOf(TranscriptTextReveal.Arrival(5, 0.05), TranscriptTextReveal.Arrival(11, 0.1))),
            reveal.arrivals("reply", blocks("Hello there, friend"), 0.1),
        )
        assertEquals(
            mapOf(
                0 to listOf(TranscriptTextReveal.Arrival(5, 0.05), TranscriptTextReveal.Arrival(11, 0.1)),
                1 to listOf(TranscriptTextReveal.Arrival(0, 0.2)),
            ),
            reveal.arrivals("reply", blocks("Hello there, friend\n\nNext"), 0.2),
        )
        assertTrue(reveal.arrivals("reply", blocks("Hello there, friend\n\nNext"), 1.0).isEmpty())
    }

    @Test
    fun aBlockChangingKindReplacesItselfWithoutFading() {
        val reveal = TranscriptTextReveal()
        reveal.arrivals("reply", listOf(paragraph(0, "Intro")), 0.0)
        reveal.arrivals("reply", listOf(paragraph(0, "Intro"), paragraph(1, "| a | b |")), 0.5)
        val table = OpenCodeMarkdownBlock(
            1,
            OpenCodeMarkdownBlock.Kind.table(
                listOf(AttributedString("a"), AttributedString("b")),
                listOf(listOf(AttributedString("1"), AttributedString("2"))),
            ),
        )
        assertTrue(reveal.arrivals("reply", listOf(paragraph(0, "Intro"), table), 1.0).isEmpty())
    }

    @Test
    fun aBlockMergedAwayAndStreamedInAgainFades() {
        val reveal = TranscriptTextReveal()
        reveal.arrivals("reply", listOf(paragraph(0, "Hello"), paragraph(1, "World")), 0.0)
        reveal.arrivals("reply", listOf(paragraph(0, "Hello World")), 1.0)
        assertEquals(
            mapOf(1 to listOf(TranscriptTextReveal.Arrival(0, 2.0))),
            reveal.arrivals("reply", listOf(paragraph(0, "Hello World"), paragraph(1, "Next")), 2.0),
        )
    }

    @Test
    fun shrinkingTextDropsArrivalsPastItsEnd() {
        val reveal = TranscriptTextReveal()
        reveal.arrivals("reply", listOf(paragraph(0, "Some bo")), 0.0)
        reveal.arrivals("reply", listOf(paragraph(0, "Some bold")), 0.05)
        val arrivals = reveal.arrivals("reply", listOf(paragraph(0, "Some bold")), 0.06)[0].orEmpty()
        assertTrue(arrivals.all { it.offset < 9 })
    }

    private fun blocks(text: String) = OpenCodeMarkdownBlock.blocks(from = text)

    private fun paragraph(id: Int, text: String) =
        OpenCodeMarkdownBlock(id, OpenCodeMarkdownBlock.Kind.paragraph(AttributedString(text)))
}

class TranscriptTextRevealWaitTests {
    private class Clock {
        var now = 0.0
        var sleeps = 0
    }

    @Test
    fun anEarlyWakeKeepsWaitingUntilTheFadeEnds() = runBlocking {
        val clock = Clock()
        val settled = TranscriptTextReveal.wait(until = 0.2, now = { clock.now }) { duration ->
            clock.sleeps += 1
            val asked = duration.inWholeNanoseconds / 1e9
            clock.now += if (clock.sleeps == 1) asked / 2 else asked
        }
        assertTrue(settled)
        assertEquals(2, clock.sleeps)
        assertTrue(clock.now >= 0.2 - 1e-9)
    }

    @Test
    fun cancellationDoesNotSettle() = runBlocking {
        val settled = TranscriptTextReveal.wait(until = 1.0, now = { 0.0 }) { throw CancellationException() }
        assertFalse(settled)
    }
}
