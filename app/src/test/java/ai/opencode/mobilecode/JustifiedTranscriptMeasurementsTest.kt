package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.AttributedString
import ai.opencode.mobilecode.system.Justice
import ai.opencode.mobilecode.system.Justification
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TextWidths
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
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
@Config(sdk = [33])
class JustifiedTranscriptMeasurementsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val style = TextStyles.body.copy(lineHeight = 26.sp)
    private val density = Density(1f)
    private val fonts = createFontFamilyResolver(ApplicationProvider.getApplicationContext())

    @Test
    fun largestGapPerParagraphRaggedAndroidJustifiedAndJustice() {
        val blocks = TranscriptFixture.referenceBlocks().mapNotNull { block ->
            when (val kind = block.kind) {
                is OpenCodeMarkdownBlock.Kind.paragraph -> kind.text to 0
                is OpenCodeMarkdownBlock.Kind.numberedItem -> kind.text to 30
                is OpenCodeMarkdownBlock.Kind.listItem -> kind.text to 30 + kind.level * MarkdownStyle.listIndent
                is OpenCodeMarkdownBlock.Kind.blockquote -> kind.text to 12
                else -> null
            }
        }
        val texts = annotate(blocks.map { it.first })
        val widths = TextWidths(Justification.style(style), density, fonts)
        val space = widths.width(AnnotatedString(" "))
        for (screen in listOf(440, 320)) {
            var worstRagged = 0.0
            var worstAndroid = 0.0
            var worstJustice = 0.0
            var loose = 0
            var lines = 0
            texts.zip(blocks).forEachIndexed { index, (text, block) ->
                val width = screen - 32 - block.second
                val ragged = laid(text, style, width)
                val android = laid(text, style.copy(textAlign = TextAlign.Justify), width)
                val plan = Justification.plan(text, width, widths)!!
                val justice = laid(plan.text, Justification.style(style), plan.layoutWidth)
                val raggedGap = largestGap(ragged, text, widths, space)
                assertEquals("ragged gaps are natural", 1.0, raggedGap, 0.01)
                val shortfall = shortfall(ragged, text, width) / space
                val androidGap = androidJustifiedGap(android, text, width, space)
                val justiceGap = largestGap(justice, text, widths, space)
                loose += plan.lines.count { it.loose }
                lines += plan.lines.size
                worstRagged = maxOf(worstRagged, shortfall)
                worstAndroid = maxOf(worstAndroid, androidGap)
                worstJustice = maxOf(worstJustice, justiceGap)
                println(
                    "gaps screen=${screen}dp block=$index lines=${ragged.lineCount}/${justice.lineCount} " +
                        "raggedShortfallSpaces=${"%.2f".format(shortfall)} androidJustifyLargestGap=${"%.2f".format(androidGap)} " +
                        "justiceLargestGap=${"%.2f".format(justiceGap)} \"${text.text.take(32)}\"",
                )
            }
            println("gaps screen=${screen}dp worst raggedShortfallSpaces=${"%.2f".format(worstRagged)} androidJustifyLargestGap=${"%.2f".format(worstAndroid)} justiceLargestGap=${"%.2f".format(worstJustice)} justiceLinesSetRagged=$loose/$lines")
            assertTrue("justified gaps stay within the library's whole-word bound", worstJustice <= 3.5)
        }
    }

    @Test
    fun streamingAParagraphToOneHundredTwentyWords() {
        val text = annotate(listOf(AttributedString(paragraph, AttributedString.MarkdownParsingOptions(AttributedString.MarkdownParsingOptions.InterpretedSyntax.inlineOnlyPreservingWhitespace)))).single()
        val words = text.text.split(" ")
        assertEquals(120, words.size)
        val width = 408
        val prefixes = (1..words.size).map { count -> text.subSequence(0, words.take(count).joinToString(" ").length) }

        for (keep in listOf(false, true)) {
            val widths = TextWidths(Justification.style(style), density, fonts)
            var previous: Justification.Plan? = null
            var reflows = 0
            var moved = 0
            for (prefix in prefixes) {
                val plan = Justification.plan(prefix, width, widths, previous = previous.takeIf { keep })!!
                val settled = previous?.lines.orEmpty().dropLast(2)
                val changed = settled.indices.count { it >= plan.lines.size || plan.lines[it].end != settled[it].end }
                if (changed > 0) reflows++
                moved = maxOf(moved, changed)
                previous = plan
            }
            println("streaming reflow keepSettledLines=$keep tokens=${prefixes.size} tokensMovingSettledLines=$reflows mostSettledLinesMoved=$moved finalLines=${previous!!.lines.size}")
            if (keep) assertEquals(0, reflows)
        }

        val prepared = TextWidths(Justification.style(style), density, fonts).prepare(text, 0, text.length).copy(hyphenation = null)
        val solves = LongArray(2000)
        repeat(5000) { Justice.solve(prepared, width.toDouble()) }
        for (i in solves.indices) {
            val started = System.nanoTime()
            val layout = Justice.solve(prepared, width.toDouble())
            solves[i] = System.nanoTime() - started
            check(layout.lines.sumOf { it.end - it.start } == 120)
        }
        val tokens = mutableListOf<Long>()
        repeat(11) { round ->
            val streaming = TextWidths(Justification.style(style), density, fonts)
            var previous = Justification.plan(prefixes[99], width, streaming)
            for (prefix in prefixes.drop(100)) {
                val started = System.nanoTime()
                val plan = Justification.plan(prefix, width, streaming, previous = previous)
                val elapsed = System.nanoTime() - started
                check(plan!!.text.text == prefix.text)
                previous = plan
                if (round > 0) tokens += elapsed
            }
        }
        solves.sort()
        tokens.sort()
        println(
            "per-token solve120 us median=${solves[solves.size / 2] / 1000.0} p10=${solves[solves.size / 10] / 1000.0} p90=${solves[solves.size * 9 / 10] / 1000.0} n=${solves.size}; " +
                "plan per token (new word measured, settled lines kept) us median=${tokens[tokens.size / 2] / 1000.0} p10=${tokens[tokens.size / 10] / 1000.0} p90=${tokens[tokens.size * 9 / 10] / 1000.0} n=${tokens.size}",
        )
    }

    private fun annotate(texts: List<AttributedString>): List<AnnotatedString> {
        var annotated: List<AnnotatedString> = emptyList()
        composeRule.setContent { annotated = texts.map { annotated(MarkdownStyle.styled(it), SystemColors.label) } }
        composeRule.waitForIdle()
        return annotated
    }

    private fun laid(text: AnnotatedString, style: TextStyle, width: Int) =
        Paragraph(text.text, style, Constraints(maxWidth = width), density, fonts, text.spanStyles)

    private fun largestGap(layout: Paragraph, text: AnnotatedString, widths: TextWidths, space: Double): Double {
        var largest = 0.0
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = true)
            var index = layout.getLineStart(line)
            while (index < end) {
                if (!Justice.isSeparator(text[index]) || index == layout.getLineStart(line)) {
                    index++
                    continue
                }
                var next = index
                while (next < end && Justice.isSeparator(text[next])) next++
                val rendered = layout.getHorizontalPosition(next, true) - layout.getHorizontalPosition(index, true)
                val natural = widths.width(text.subSequence(index, next))
                largest = maxOf(largest, 1 + (rendered - natural) / space)
                index = next
            }
        }
        return largest
    }

    /** Android's inter-word justification adds the same width to every space on a line; its
     * reported positions leave that width out, so the gap is worked out from the line's width. */
    private fun androidJustifiedGap(layout: Paragraph, text: AnnotatedString, width: Int, space: Double): Double =
        (0 until layout.lineCount).filterNot { endsSegment(layout, text, it) }.maxOfOrNull { line ->
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line, true)
            val spaces = (start until end).count { text[it] == ' ' }
            val natural = layout.getHorizontalPosition(end, true) - layout.getHorizontalPosition(start, true)
            if (spaces == 0) 1.0 else 1 + (width - natural) / spaces / space
        } ?: 1.0

    private fun shortfall(layout: Paragraph, text: AnnotatedString, width: Int): Double =
        (0 until layout.lineCount).filterNot { endsSegment(layout, text, it) }.maxOfOrNull { width - layout.getLineRight(it).toDouble() } ?: 0.0

    private fun endsSegment(layout: Paragraph, text: AnnotatedString, line: Int) =
        text.text.substring(layout.getLineEnd(line, visibleEnd = true)).substringBefore('\n').isBlank()

    private val paragraph = "**Why:** the session runner reloads projected history before every durable continuation, so a step " +
        "that resumes after a crash sees exactly what the model saw. The first version kept an in-memory tool loop, which " +
        "meant a restart in the middle of a long edit replayed tool calls the user had approved, and in two " +
        "cases wrote the same file twice. Moving the loop into `SessionRunner` and recording each physical attempt as its " +
        "own event fixed both problems without slowing the common path: the projection is already warm, and the reload is " +
        "a single indexed read. The remaining cost is one extra query per step, which the benchmark puts well under a " +
        "millisecond on a cold cache, so we kept it."
}
