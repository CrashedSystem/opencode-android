package ai.opencode.mobilecode.system

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library's behavior tests (`test/engine.test.ts`, `refinements.test.ts`,
 * `mobile-spacing.test.ts`) that do not need a hyphenation dictionary. */
class JusticeTests {
    private val measure = JusticeGolden.measures.getValue("eight")
    private val mobile = JusticeGolden.prepared.getValue("mobile")

    @Test
    fun fitsAFirstLineIndentAndADropCapByNarrowingOnlyTheAffectedLines() {
        val p = Justice.prepare("one two three four five six seven eight nine ten eleven twelve", measure)
        val indented = Justice.solve(p, listOf(200.0 - 40, 200.0), Justice.defaults.copy(tracking = 0.0))
        assertEquals(160.0, indented.lines[0].width, 0.0)
        assertTrue(indented.lines.drop(1).all { it.width == 200.0 })
        assertTrue(indented.lines[0].natural <= 160 + indented.lines[0].hanging)
        val dropCap = Justice.solve(p, listOf(120.0, 120.0, 200.0), Justice.defaults.copy(tracking = 0.0))
        assertEquals(listOf(120.0, 120.0, 200.0, 200.0).take(dropCap.lines.size), dropCap.lines.map { it.width })
        assertThrows(IllegalArgumentException::class.java) { Justice.solve(p, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Justice.solve(p, listOf(100.0, 0.0)) }
    }

    @Test
    fun breaksAfterASourceHyphenWithoutAddingAGlyph() {
        val p = Justice.prepare("a well-known state-of-the-art thing", measure)
        assertEquals(listOf(0, 5, 10), p.hyphenation?.get(1)?.offsets?.toList())
        assertEquals(listOf(false, true, false), p.hyphenation?.get(1)?.explicit?.toList())
        assertEquals(listOf(0, 6, 9, 13, 16), p.hyphenation?.get(2)?.offsets?.toList())
        assertNull(p.hyphenation?.get(0))
        val narrow = Justice.solve(p, 60.0, Justice.defaults.copy(tracking = 0.0))
        val texts = narrow.lines.map { Justice.lineText(p, it) }
        assertTrue(texts.toString(), "a well-" in texts)
        assertTrue(narrow.lines.none { it.hyphenated })
        val source = narrow.lines.mapIndexed { i, l ->
            Justice.lineText(p, l) + if (l.endOffset != null || i == narrow.lines.lastIndex) "" else " "
        }.joinToString("")
        assertEquals("a well-known state-of-the-art thing", source)
        assertTrue(narrow.lines.filter { it.endOffset != null }.all { Justice.lineText(p, it).endsWith("-") })
        val cheap = Justice.solve(p, 60.0, Justice.defaults.copy(tracking = 0.0, explicitHyphenPenalty = 0.0)).cost
        val dear = Justice.solve(p, 60.0, Justice.defaults.copy(tracking = 0.0, explicitHyphenPenalty = 500.0)).cost
        assertTrue(dear > cheap)
        assertNull(Justice.prepare("-5 to 5- and -- or a-", measure).hyphenation)
    }

    @Test
    fun finishesLooseLinesAndModestOverflowWithoutStretchingRaggedEndings() {
        val p = Justice.prepare("aaa bbb ccccccc", measure)
        val strict = Justice.solve(p, 70.0, Justice.defaults.copy(mode = Justice.Mode.strict, tracking = 0.0))
        val balanced = Justice.solve(p, 70.0, Justice.defaults.copy(tracking = 0.0))
        assertTrue(strict.lines[0].residual > 0)
        assertEquals(0.0, balanced.lines[0].residual, 0.0)
        assertTrue(balanced.lines[0].relaxed)
        assertEquals(0.0, balanced.lines.last().wordSpacing, 0.0)
        val tight = Justice.prepare("aaa bbb", measure)
        assertEquals(-1.0, Justice.solve(tight, 50.0, Justice.defaults.copy(mode = Justice.Mode.strict, tracking = 0.0)).lines[0].residual, 0.0)
        val finished = Justice.solve(tight, 50.0, Justice.defaults.copy(tracking = 0.0, shrink = 0.5)).lines[0]
        assertEquals(-2.0, finished.wordSpacing, 0.0)
        assertEquals(0.0, finished.residual, 0.0)
        assertEquals(0.0, finished.tracking, 1e-9)
    }

    @Test
    fun reportsUnavoidableOverflowWithoutBreakingOrLosingAWord() {
        val p = Justice.prepare("tiny extraordinarilylongword tiny", measure)
        val result = Justice.solve(p, 40.0, Justice.defaults.copy(tracking = 0.0))
        assertTrue(result.lines.any { it.residual < 0 })
        assertEquals(p.words.joinToString(" "), result.lines.joinToString(" ") { Justice.lineText(p, it) })
    }

    @Test
    fun hangsAMeasuredPeriodWithoutExpandingItsLettersOrCountingOpticalCreditAsOverflow() {
        val p = Justice.prepare("word.", measure)
        val off = Justice.solve(p, 32.0, Justice.defaults.copy(tracking = 0.0, hanging = 0.0)).lines[0]
        val half = Justice.solve(p, 32.0, Justice.defaults.copy(tracking = 0.0, hanging = 0.5)).lines[0]
        val on = Justice.solve(p, 32.0, Justice.defaults.copy(tracking = 0.0, hanging = 1.0)).lines[0]
        assertEquals(listOf(-8.0, -4.0, 0.0), listOf(off.residual, half.residual, on.residual))
        assertEquals(8.0, on.hanging, 0.0)
        assertEquals(40.0, on.natural, 0.0)
        assertEquals(0.0, on.wordSpacing, 0.0)
        assertEquals("word.", Justice.lineText(p, on))
    }

    @Test
    fun onlyCreditsTrailingPunctuationIncludingAClosingQuoteAndLeavesRaggedEndingsNatural() {
        val p = Justice.prepare("“one” can’t v1.2 done.”", measure)
        assertEquals(listOf(8.0, 0.0, 0.0, 16.0), p.endHangs.toList())
        val line = Justice.solve(p, 1000.0).lines[0]
        assertEquals(0.0, line.wordSpacing, 0.0)
        assertEquals(0.0, line.tracking, 0.0)
        assertEquals(0.0, line.residual, 0.0)
    }

    @Test
    fun keepsNonbreakingSpacesInsideATokenAndCollapsesOrdinaryWhitespace() {
        val p = Justice.prepare("  walk\t10\u00a0km\n today  ", measure)
        assertEquals(listOf("walk", "10\u00a0km", "today"), p.words)
        assertEquals("walk 10\u00a0km today", Justice.solve(p, 20.0).lines.joinToString(" ") { Justice.lineText(p, it) })
    }

    @Test
    fun reusesMeasurementAcrossMultipleSolvesAndMeasuresDuplicateWordsOnce() {
        var calls = 0
        val p = Justice.prepare("one two one two") { calls++; measure(it) }
        assertEquals(3, calls)
        Justice.solve(p, 40.0)
        Justice.solve(p, 100.0)
        Justice.solve(p, 80.0, Justice.defaults.copy(stretch = 1.0))
        assertEquals(3, calls)
    }

    @Test
    fun handlesEmptyParagraphsAndRejectsInvalidDimensionsOrPolicies() {
        val p = Justice.prepare(" \n\t", measure)
        assertEquals(Justice.Layout(emptyList(), 0.0, 0), Justice.solve(p, 100.0))
        for (width in listOf(0.0, -1.0, Double.POSITIVE_INFINITY, Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { Justice.solve(p, width) }
        }
        assertThrows(IllegalArgumentException::class.java) { Justice.solve(p, 100.0, Justice.defaults.copy(shrink = 2.0)) }
        assertThrows(IllegalArgumentException::class.java) { Justice.solve(p, 100.0, Justice.defaults.copy(tracking = -1.0)) }
        assertThrows(IllegalArgumentException::class.java) { Justice.solve(p, 100.0, Justice.defaults.copy(hanging = 1.1)) }
        assertThrows(IllegalArgumentException::class.java) { Justice.prepare("word") { Double.NaN } }
        assertThrows(IllegalArgumentException::class.java) { Justice.solve(p, 100.0, Justice.defaults.copy(widowPenalty = Double.NaN)) }
    }

    @Test
    fun retainsCompetingFitnessHistoriesAndChoosesASmootherCompleteParagraph() {
        val text = "A quiet paragraph can become much more comfortable when its lines share a reasonably even rhythm of spaces instead of alternating between very tight and very loose arrangements."
        val p = Justice.prepare(text, measure)
        fun jumps(layout: Justice.Layout) = layout.lines.drop(1).filterIndexed { i, line -> abs(line.fitness - layout.lines[i].fitness) > 1 }.size
        val independent = Justice.solve(p, 300.0, Justice.defaults.copy(adjacentPenalty = 0.0))
        val adjacent = Justice.solve(p, 300.0)
        assertEquals(3, jumps(independent))
        assertEquals(1, jumps(adjacent))
        assertTrue(adjacent.cost < independent.cost + jumps(independent) * Justice.defaults.adjacentPenalty)
        assertEquals(text, adjacent.lines.joinToString(" ") { Justice.lineText(p, it) })
        assertEquals(adjacent.cost, adjacent.lines.sumOf { it.cost }, 1e-8)
    }

    @Test
    fun combinesMeasuredMarginsWithPunctuationAndCanDisableOnlyTheOpticalModel() {
        val p = Justice.withOpticalMargins(Justice.prepare("Thus.", measure)) { _, _ -> Justice.Edges(2.0, 3.0) }
        val on = Justice.solve(p, 100.0).lines[0]
        assertEquals(listOf(2.0, 8.0), listOf(on.opening, on.hanging))
        val off = Justice.solve(p, 100.0, Justice.defaults.copy(protrusion = 0.0)).lines[0]
        assertEquals(listOf(0.0, 8.0), listOf(off.opening, off.hanging))
        val half = Justice.solve(p, 100.0, Justice.defaults.copy(protrusion = 0.5, hanging = 0.0)).lines[0]
        assertEquals(listOf(1.0, 1.5), listOf(half.opening, half.hanging))
        assertThrows(IllegalArgumentException::class.java) { Justice.withOpticalMargins(p) { _, _ -> Justice.Edges(-1.0, 0.0) } }
        assertThrows(IllegalArgumentException::class.java) { Justice.withOpticalMargins(p) { _, _ -> Justice.Edges(0.0, Double.POSITIVE_INFINITY) } }
        assertThrows(IllegalArgumentException::class.java) { Justice.solve(p, 100.0, Justice.defaults.copy(protrusion = 1.1)) }
    }

    @Test
    fun doesNotPreferEightfoldMobileSpaces() {
        for (hyphenated in listOf(false, true)) {
            // The reported 350px measure selected three words, then stretched its two
            // spaces from 4.84px to 39.37px, despite fitting "models," on that line.
            val p = if (hyphenated) mobile else mobile.copy(hyphenation = null)
            val lines = Justice.solve(p, 350.0).lines
            assertEquals("OpenCode’s environment (its models,", Justice.lineText(p, lines[0]))
            assertTrue(p.space + lines[0].wordSpacing + lines[0].tracking < p.space * 1.5)
            for (line in lines) {
                assertTrue(p.space + line.wordSpacing + line.tracking < p.space * (if (hyphenated) 3.0 else 3.5))
                assertTrue(abs(line.residual) < 0.01)
            }
        }
    }

    @Test
    fun preservesSourceAndExactFittingThroughoutTheMobileWidthRange() {
        for (width in 268..448) {
            val lines = Justice.solve(mobile, width.toDouble()).lines
            val source = lines.mapIndexed { index, line ->
                val text = Justice.lineText(mobile, line)
                (if (index != 0 && lines[index - 1].endOffset == null) " " else "") + if (line.hyphenated) text.dropLast(1) else text
            }.joinToString("")
            assertEquals(mobile.words.joinToString(" "), source)
            assertTrue(lines.all { abs(it.residual) < 0.01 })
            assertTrue(lines.last().wordSpacing <= 0)
        }
    }

    @Test
    fun measuredGapsKeepTheirWidthsWhileUniformGapsSolveLikeTheLibrary() {
        val text = "aa  bb cc"
        val ranged = { start: Int, end: Int -> measure(text.substring(start, end)) }
        val uniform = Justice.prepare(text, 4.0, ranged, measureGaps = false)
        val measured = Justice.prepare(text, 4.0, ranged, measureGaps = true)
        assertNull(uniform.gaps)
        assertEquals(listOf(0.0, 8.0, 12.0), measured.gaps?.toList())
        assertEquals(60.0, Justice.solve(measured, 100.0).lines.single().natural, 0.0)
        assertEquals(56.0, Justice.solve(uniform, 100.0).lines.single().natural, 0.0)
        val single = "aa bb cc"
        assertNull(Justice.prepare(single, 4.0, { start, end -> measure(single.substring(start, end)) }, measureGaps = true).gaps)
    }

    @Test
    fun suppliedClusterCountsReplaceGraphemesWhereGiven() {
        val text = "first fish"
        val ranged = { start: Int, end: Int -> measure(text.substring(start, end)) }
        val ligature = Justice.prepare(text, 4.0, ranged, measureGaps = false) { start, end -> if (text.substring(start, end) == "first") 4 else null }
        assertEquals(listOf(0.0, 4.0, 8.0), ligature.characters.toList())
        assertEquals(listOf(0.0, 5.0, 9.0), Justice.prepare(text, 4.0, ranged, measureGaps = false).characters.toList())
    }
}
