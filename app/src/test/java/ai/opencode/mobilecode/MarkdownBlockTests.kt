@file:PortedFrom("OpenCodeTests/MarkdownBlockTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.AttributedString

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
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

    @Test
    fun fenceScanPreservesTheOriginalUTF8Segments() {
        val boundaries = listOf("", "`", "``", "```", "````", "\r\n", "\u0000", "é", "e\u0301", "\u0301", "\u200D", "🧑🏽‍💻", "\u1FEF")
        for (prefix in boundaries) {
            for (middle in boundaries) {
                for (suffix in boundaries) {
                    val source = prefix + "```" + middle + "```" + suffix
                    if (source.any { it.code >= 0x80 }) continue
                    assertEquals(asciiFenceSplit(source).map { it.toByteArray(Charsets.UTF_8).toList() }, OpenCodeMarkdownBlock.codeFenceSegments(source).map { it.toByteArray(Charsets.UTF_8).toList() })
                }
            }
        }
        assertEquals(listOf("é```\u200D"), OpenCodeMarkdownBlock.codeFenceSegments("é```\u200D"))
        assertEquals(listOf("", "e\u0301```\u0301"), OpenCodeMarkdownBlock.codeFenceSegments("```e\u0301```\u0301"))
    }

    @Test
    fun codeFencesRespectUnicodeGraphemeBoundaries() {
        val source = "let café = \"🧑🏽‍💻\""
        for (suffix in listOf("\u200D", "\u0301")) {
            val blocks = OpenCodeMarkdownBlock.blocks(from = "```swift\n$source\n```$suffix")
            val code = (blocks.first().kind as OpenCodeMarkdownBlock.Kind.code).code
            assertEquals(source + "\n```" + suffix, code.source)
        }
        val blocks = OpenCodeMarkdownBlock.blocks(from = "```swift\n$source\n```\n\nTail e\u0301")
        assertEquals(2, blocks.size)
        val code = (blocks[0].kind as OpenCodeMarkdownBlock.Kind.code).code
        assertEquals(source.toByteArray(Charsets.UTF_8).toList(), code.source.toByteArray(Charsets.UTF_8).toList())
        assertEquals("Tail e\u0301", blocks[1].markdown)
    }

    @Test
    fun inlineReuseMatchesFreshParsingAtEveryStreamingBoundary() {
        val cache = OpenCodeMarkdownBlock.InlineCache()
        var previous = emptyList<OpenCodeMarkdownBlock>()
        val documents = listOf(
            "## Heading\n\n1. **First**\n   - `Sources/One.swift`\n\n> Quote *two*\n> still quoted\n\nAfter 🧑‍💻 e\u0301",
            "Before\n\n```swift\nlet value = \"hello\"\n```\n\n| File | State |\n|---|---|\n| [report](reports/one.pdf) | **Ready** |\n\nAfter",
            "[one](reports/one.pdf)\n\n[again](reports/one.pdf)\n\n![clip](clips/one.mp4)\n\n---\n\nhttps://github.com/example/project/pull/42",
            "Escaped \\*text\\*, &amp; entities, <https://example.com>, ~~removed~~ and `a\\b`.\n\n- _Unfinished",
            "- [ ] Open **task**\n  - [x] Done `Sources/One.swift`\n- [X] Uppercase\n\nSame **task**",
        )
        for (document in documents) {
            val lengths = (0..document.length) + (0..document.length).reversed()
            for (length in lengths) {
                val text = document.substring(0, length)
                val fresh = OpenCodeMarkdownBlock.blocks(from = text, inlineCache = null)
                previous = OpenCodeMarkdownBlock.blocks(from = text, inlineCache = cache, reusing = previous)
                assertEquals(fresh, previous)
                assertEquals(highlightedCode(fresh), highlightedCode(previous))
            }
        }
    }

    @Test
    fun codeReuseTracksLanguageSourceAndReplacement() {
        val code = "const value = 'hello' // note"
        val documents = listOf(
            "```swift\n$code\n```",
            "```typescript\n$code\n```",
            "```\n$code\n```",
            "```python\n# comment\n```",
            "```swift\n# comment\n```",
            "```swift\nlet café = 1\n```",
            "```swift\nlet cafe\u0301 = 1\n```",
            "```swift\nlet value = 1\n```\n\n```swift\nlet value = 1\n```",
            "```swift\nlet value = 2\n```",
            "No code remains",
        )
        var previous = emptyList<OpenCodeMarkdownBlock>()
        for (document in documents) {
            val fresh = OpenCodeMarkdownBlock.blocks(from = document, inlineCache = null)
            previous = OpenCodeMarkdownBlock.blocks(from = document, reusing = previous)
            assertEquals(fresh, previous)
            assertEquals(highlightedCode(fresh), highlightedCode(previous))
            assertEquals(
                OpenCodeMarkdownBlock.markdown(fresh).toByteArray(Charsets.UTF_8).toList(),
                OpenCodeMarkdownBlock.markdown(previous).toByteArray(Charsets.UTF_8).toList(),
            )
        }
    }

    @Test
    fun inlineReuseDistinguishesCanonicallyEquivalentSourceBytes() {
        val cache = OpenCodeMarkdownBlock.InlineCache()
        for (source in listOf("café", "cafe\u0301", "`café.swift`", "`cafe\u0301.swift`")) {
            val cached = OpenCodeMarkdownBlock.blocks(from = source, inlineCache = cache)
            val fresh = OpenCodeMarkdownBlock.blocks(from = source, inlineCache = null)
            assertEquals(fresh, cached)
            assertEquals(
                OpenCodeMarkdownBlock.markdown(fresh).toByteArray(Charsets.UTF_8).toList(),
                OpenCodeMarkdownBlock.markdown(cached).toByteArray(Charsets.UTF_8).toList(),
            )
        }
    }

    @Test
    fun inlineReuseHasHardEntryAndUTF8SourceBounds() {
        val cache = OpenCodeMarkdownBlock.InlineCache(maximumEntries = 3, maximumSourceBytes = 64)
        for (index in 0 until 30) {
            val source = "**$index** 🧑‍💻"
            assertEquals(
                OpenCodeMarkdownBlock.blocks(from = source, inlineCache = null),
                OpenCodeMarkdownBlock.blocks(from = source, inlineCache = cache),
            )
            assertTrue(cache.entryCount <= 3)
            assertTrue(cache.retainedSourceBytes <= 64)
        }
        val count = cache.entryCount
        val bytes = cache.retainedSourceBytes
        val oversized = "😀".repeat(100)
        assertEquals(
            OpenCodeMarkdownBlock.blocks(from = oversized, inlineCache = null),
            OpenCodeMarkdownBlock.blocks(from = oversized, inlineCache = cache),
        )
        assertEquals(count, cache.entryCount)
        assertEquals(bytes, cache.retainedSourceBytes)
        assertEquals(
            OpenCodeMarkdownBlock.blocks(from = "**0** 🧑‍💻", inlineCache = null),
            OpenCodeMarkdownBlock.blocks(from = "**0** 🧑‍💻", inlineCache = cache),
        )
        val disabled = OpenCodeMarkdownBlock.InlineCache(maximumEntries = 0)
        OpenCodeMarkdownBlock.blocks(from = "No storage", inlineCache = disabled)
        assertEquals(0, disabled.entryCount)
        assertEquals(0, disabled.retainedSourceBytes)
    }

    @Test
    fun concurrentInlineCacheMissesKeepResultsAndAccountingConsistent() = runBlocking {
        val cache = OpenCodeMarkdownBlock.InlineCache()
        val texts = (0 until 8).map { "Value **$it** [file](files/$it.pdf)" }
        val expected = texts.map { OpenCodeMarkdownBlock.blocks(from = it, inlineCache = null) }
        val matches = coroutineScope {
            (0 until 64).map { index ->
                async(Dispatchers.Default) {
                    OpenCodeMarkdownBlock.blocks(from = texts[index % 8], inlineCache = cache) == expected[index % 8]
                }
            }.awaitAll().all { it }
        }
        assertTrue(matches)
        assertEquals(texts.size, cache.entryCount)
        assertEquals(texts.sumOf { it.toByteArray(Charsets.UTF_8).size }, cache.retainedSourceBytes)
    }

    @Test
    fun thematicBreaksRenderAsDividersNotText() {
        fun kinds(text: String) = OpenCodeMarkdownBlock.blocks(from = text).map { block ->
            when (block.kind) {
                OpenCodeMarkdownBlock.Kind.divider -> "divider"
                is OpenCodeMarkdownBlock.Kind.paragraph -> "paragraph"
                is OpenCodeMarkdownBlock.Kind.table -> "table"
                else -> "other"
            }
        }
        assertEquals(listOf("paragraph", "divider", "paragraph"), kinds("First answer.\n\n---\n\nSecond answer."))
        assertEquals(listOf("paragraph", "divider", "paragraph", "divider", "paragraph"), kinds("A\n\n***\n\nB\n\n_ _ _\n\nC"))
        assertEquals(listOf("paragraph", "divider", "paragraph"), kinds("A\n\n- - -\n\nB"))
        assertEquals(listOf("paragraph", "paragraph"), kinds("A\n\n--"))
        assertEquals(listOf("table"), kinds("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertFalse(kinds("- item").contains("divider"))
        assertEquals("A\n\n---\n\nB", OpenCodeMarkdownBlock.markdown(OpenCodeMarkdownBlock.blocks(from = "A\n\n---\n\nB")))
    }

    @Test
    fun numberedMarkersAreSeparateFromWrappedContent() {
        val blocks = OpenCodeMarkdownBlock.blocks(from = "9. A **bold** item\n   with a continuation\n10. Next item\n\nAfter")
        assertEquals(3, blocks.size)
        val first = blocks[0].kind as OpenCodeMarkdownBlock.Kind.numberedItem
        val second = blocks[1].kind as OpenCodeMarkdownBlock.Kind.numberedItem
        assertTrue(blocks[2].kind is OpenCodeMarkdownBlock.Kind.paragraph)
        assertEquals("9.", first.marker)
        assertEquals("10.", second.marker)
        assertTrue(first.text.characters.contains("with a continuation"))
        assertTrue(first.text.runs.any { AttributedString.Intent.stronglyEmphasized in it.inlinePresentationIntent })
    }

    @Test
    fun blockquoteSeparatesProseAndPreservesInlineFormatting() {
        val blocks = OpenCodeMarkdownBlock.blocks(from = "Before\n> A **quoted** line\n> continuation\n>\n> Second paragraph\nAfter")
        assertEquals(3, blocks.size)
        val quote = blocks[1].kind as OpenCodeMarkdownBlock.Kind.blockquote
        assertEquals("A quoted line\ncontinuation\n\nSecond paragraph", quote.text.characters)
        assertTrue(quote.text.runs.any { AttributedString.Intent.stronglyEmphasized in it.inlinePresentationIntent })
    }

    @Test
    fun splitsParagraphsAndFencedCode() {
        val blocks = OpenCodeMarkdownBlock.blocks(from = "Here is **bold** text.\n\n```swift\nlet x = 1\n```\n\nTrailing paragraph.\n")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].kind is OpenCodeMarkdownBlock.Kind.paragraph)
        val code = (blocks[1].kind as OpenCodeMarkdownBlock.Kind.code).code
        assertEquals("let x = 1", code.source)
        assertEquals("let x = 1", code.text.characters)
        assertTrue(blocks[2].kind is OpenCodeMarkdownBlock.Kind.paragraph)
    }

    @Test
    fun standaloneMarkdownImagesBecomeViewableBlocks() {
        val blocks = OpenCodeMarkdownBlock.blocks(from = "Before\n\n![Build graph](artifacts/build-graph.png)\n\nAfter\n")
        assertEquals(3, blocks.size)
        val image = blocks[1].kind as OpenCodeMarkdownBlock.Kind.image
        assertEquals("Build graph", image.alt)
        assertEquals("artifacts/build-graph.png", image.source)
        val decoded = protocolJson.decodeFromString<OpenCodeMarkdownBlock>(protocolJson.encodeToString(blocks[1]))
        assertEquals(blocks[1], decoded)
    }

    @Test
    fun cachedCodeBlocksPersistSourceOnlyAndRehighlight() {
        val block = OpenCodeMarkdownBlock(0, OpenCodeMarkdownBlock.Kind.code(OpenCodeMarkdownBlock.HighlightedCode("let x = 1", "swift")))
        val json = protocolJson.encodeToString(block)
        assertTrue(json.contains("\"source\""))
        assertFalse(json.contains("ForegroundColor"))
        assertFalse(json.contains("foregroundColor"))
        val decoded = protocolJson.decodeFromString<OpenCodeMarkdownBlock>(json)
        assertEquals(block, decoded)
        val code = (decoded.kind as OpenCodeMarkdownBlock.Kind.code).code
        assertEquals(SyntaxHighlighter.highlight("let x = 1", "swift"), code.text)
    }

    @Test
    fun blankLinesAndListItemsSplitBlocks() {
        val blocks = OpenCodeMarkdownBlock.blocks(from = "First paragraph.\n\nSecond paragraph.\n- item one\n- item two\n")
        assertEquals(4, blocks.size)
        val item = blocks[2].kind as OpenCodeMarkdownBlock.Kind.listItem
        assertEquals("•", item.marker)
        assertEquals(0, item.level)
        assertEquals("item one", item.text.characters)
    }

    @Test
    fun nestedListItemsKeepTheirLevel() {
        fun shape(text: String) = OpenCodeMarkdownBlock.blocks(from = text).map { block ->
            when (val kind = block.kind) {
                is OpenCodeMarkdownBlock.Kind.numberedItem -> "${kind.marker}0"
                is OpenCodeMarkdownBlock.Kind.listItem -> "${kind.marker}${kind.level}"
                is OpenCodeMarkdownBlock.Kind.paragraph -> "p"
                else -> "other"
            }
        }
        assertEquals(listOf("1.0", "2.0", "•1", "•1", "3.0"), shape("1. **Architecture:** thin\n2. **Routing:** focus first\n   - The focused session\n   - `HOME` is bound\n3. Last"))
        assertEquals(listOf("•0", "•1", "•2", "•0"), shape("- a\n  - b\n    - c\n- d"))
        assertEquals(listOf("•0", "•1", "•0"), shape("- a\n    - b\n- c"))
        assertEquals(listOf("•0", "1.1", "2.1"), shape("- a\n  1. b\n  2. c"))
        assertEquals(listOf("•0", "•1", "p", "•0"), shape("- a\n  - b\n\nAfter\n\n  - c"))
    }

    @Test
    fun extractsPullRequestCards() {
        val blocks = OpenCodeMarkdownBlock.blocks(from = "PR: https://github.com/anomalyco/opencode/pull/41189 — all green.\n")
        assertEquals(2, blocks.size)
        val card = blocks[1].kind as OpenCodeMarkdownBlock.Kind.pullRequest
        assertEquals("anomalyco/opencode #41189", card.title)
        assertEquals("https://github.com/anomalyco/opencode/pull/41189", card.url)
    }

    @Test
    fun parsesPipeTables() {
        val blocks = OpenCodeMarkdownBlock.blocks(from = "Intro.\n\n| Runtime | Notes |\n|---|---|\n| gVisor | default |\n| runc | weaker isolation |\n\nAfter.\n")
        assertEquals(3, blocks.size)
        val table = blocks[1].kind as OpenCodeMarkdownBlock.Kind.table
        assertEquals(listOf("Runtime", "Notes"), table.header.map { it.characters })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("runc", "weaker isolation"), table.rows[1].map { it.characters })
    }

    @Test
    fun pipeTableAppearsAndAddsRowsBeforeTheStreamEnds() {
        var streamed = ""
        val chunks = listOf(
            "| Fruit | Color |\n",
            "|---|---|\n",
            "| Apple | Red |\n",
            "| Pear | Gre",
            "en |\n",
        )
        val rowCounts = listOf(0, 0, 1, 2, 2)
        for ((index, chunk) in chunks.withIndex()) {
            streamed += chunk
            val blocks = OpenCodeMarkdownBlock.blocks(from = streamed)
            if (index == 0) {
                assertTrue(blocks.none { it.kind is OpenCodeMarkdownBlock.Kind.table })
                continue
            }
            val table = blocks.first { it.kind is OpenCodeMarkdownBlock.Kind.table }.kind as OpenCodeMarkdownBlock.Kind.table
            assertEquals(listOf("Fruit", "Color"), table.header.map { it.characters })
            assertEquals(rowCounts[index], table.rows.size)
            if (index == 3) assertEquals("Gre", table.rows[1][1].characters)
            if (index == 4) assertEquals("Green", table.rows[1][1].characters)
        }
    }

    private fun colored(text: AttributedString) = text.runs.mapNotNull { run ->
        if (run.foregroundColor == null) null else text.characters.substring(run.start, run.end)
    }

    private fun highlightedCode(blocks: List<OpenCodeMarkdownBlock>) = blocks.mapNotNull { block ->
        (block.kind as? OpenCodeMarkdownBlock.Kind.code)?.code?.text
    }

    private fun asciiFenceSplit(text: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var index = text.indexOf("```")
        while (index >= 0) {
            parts += text.substring(start, index)
            start = index + 3
            index = text.indexOf("```", start)
        }
        parts += text.substring(start)
        return parts
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
        reveal.arrivals("reply", blocks("Intro"), 0.0)
        reveal.arrivals("reply", blocks("Intro\n\n| a | b |"), 0.5)
        assertTrue(reveal.arrivals("reply", blocks("Intro\n\n| a | b |\n| --- | --- |\n| 1 | 2 |"), 1.0).isEmpty())
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
        reveal.arrivals("reply", blocks("Some **bo"), 0.0)
        reveal.arrivals("reply", blocks("Some **bold"), 0.05)
        val arrivals = reveal.arrivals("reply", blocks("Some **bold**"), 0.06)[0].orEmpty()
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
