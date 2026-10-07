package ai.opencode.mobilecode.system

import ai.opencode.mobilecode.system.JusticeGolden.number
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from justice's test/engine.test.ts and test/hyphenation.test.ts. MIT license: see Justice.kt.
class JusticeOracleTests {
    private val measure = JusticeGolden.measures.getValue("eight")
    private val shaped = JusticeGolden.measures.getValue("shaped")
    private val trailing = Regex("[.,;:!?…’”'\"]+$")
    private val leading = Regex("^[“‘\"'«‹]")

    @Test
    fun matchesTheGlobalExhaustiveOptimumAcrossWidthsLimitsAndImpossibleFits() {
        val random = seeded(42)
        val golden = JusticeGolden.cases.filter { it.name().startsWith("engine/oracle/") && it.name().endsWith("/strict") }
        for (i in 0 until 500) {
            val text = List(2 + floor(random() * 8).toInt()) {
                (if (random() < 0.2) "“" else "") + "w".repeat(1 + floor(random() * 14).toInt()) + listOf("", "", ".", ",", ".”")[floor(random() * 5).toInt()]
            }.joinToString(" ")
            if (i < golden.size) assertEquals("the generator matches the library's case $i", golden[i].getValue("text").jsonPrimitive.content, text)
            val original = Justice.prepare(text, measure)
            val p = if (i % 3 != 0) original else Justice.withOpticalMargins(original) { _, index -> Justice.Edges((index % 3) * 1.7, (index % 2) * 2.1) }
            val width = 20.0 + floor(random() * 300)
            val o = Justice.defaults.copy(
                mode = Justice.Mode.strict,
                ending = if (i % 2 != 0) Justice.Ending.soft else Justice.Ending.fit,
                emergencyStretch = if (i % 3 != 0) random() * 0.2 else 0.0,
                opening = random(),
                stretch = random() * 2,
                shrink = random(),
                tracking = if (i % 10 == 0) 10.0 else random(),
                compressionPenalty = random() * 4,
                hanging = random(),
                lastLine = random(),
                widowPenalty = random() * 3000,
            )
            val result = Justice.solve(p, width, o)
            assertClose("case $i", exhaustive(p, listOf(width), o), result.cost, 5)
            assertEquals(text, result.lines.joinToString(" ") { Justice.lineText(p, it) })
            for (l in result.lines) {
                assertTrue(abs(l.tracking) <= o.tracking + 1e-9)
                assertTrue(l.wordSpacing <= p.space * o.stretch + 1e-9)
                assertTrue(l.wordSpacing >= -p.space * o.shrink - 1e-9)
                val rendered = l.natural + l.wordSpacing * (l.end - l.start - 1) + l.tracking * Justice.lineText(p, l).codePointCount()
                if (!l.last || l.natural > width + l.hanging + l.opening) assertClose("case $i fit", width + l.hanging + l.opening, rendered + l.residual, 6)
            }
            val balancedPolicy = o.copy(mode = Justice.Mode.balanced)
            val balanced = Justice.solve(p, width, balancedPolicy)
            assertClose("balanced case $i", exhaustive(p, listOf(width), balancedPolicy), balanced.cost, 5)
            assertEquals(text, balanced.lines.joinToString(" ") { Justice.lineText(p, it) })
            for (l in balanced.lines) {
                assertTrue(abs(l.tracking) <= o.tracking + 1e-9)
                assertTrue(l.wordSpacing >= -p.space * o.shrink - 1e-9)
                val rendered = l.natural + l.wordSpacing * (l.end - l.start - 1) + l.tracking * Justice.lineText(p, l).codePointCount()
                if (!l.last || l.natural > width + l.hanging + l.opening) assertClose("balanced case $i fit", width + l.hanging + l.opening, rendered + l.residual, 6)
                if (!l.last && l.end - l.start > 1) assertTrue(l.residual <= 1e-9)
            }
        }
    }

    @Test
    fun matchesTheExhaustiveOptimumForPerLineMeasuresAndRecordsEachLinesWidth() {
        val random = seeded(7)
        for (i in 0 until 200) {
            val text = List(3 + floor(random() * 8).toInt()) {
                (if (random() < 0.2) "“" else "") + "w".repeat(1 + floor(random() * 12).toInt()) + listOf("", "", ".", ",")[floor(random() * 4).toInt()]
            }.joinToString(" ")
            val p = Justice.prepare(text, measure)
            val widths = List(1 + floor(random() * 4).toInt()) { 30.0 + floor(random() * 200) }
            val o = Justice.defaults.copy(
                mode = if (i % 2 != 0) Justice.Mode.strict else Justice.Mode.balanced,
                emergencyStretch = if (i % 3 != 0) random() * 0.2 else 0.0,
                stretch = random() * 2,
                shrink = random(),
                tracking = random(),
                opening = random(),
                hanging = random(),
                lastLine = random(),
                widowPenalty = random() * 2000,
                adjacentPenalty = if (i % 4 != 0) 100.0 else 0.0,
            )
            val result = Justice.solve(p, widths, o)
            assertClose("case $i", exhaustive(p, widths, o), result.cost, 5)
            assertEquals(text, result.lines.joinToString(" ") { Justice.lineText(p, it) })
            result.lines.forEachIndexed { index, l -> assertEquals(widths[min(index, widths.size - 1)], l.width, 0.0) }
        }
    }

    @Test
    fun matchesExhaustivePartitionsIncludingShapingAdjacencyCostsAndBothFittingPasses() {
        val cases = JusticeGolden.cases.filter { it.name().startsWith("hyphenation/oracle/") }
        assertEquals(100, cases.size)
        for (case in cases) {
            val text = case.getValue("text").jsonPrimitive.content
            val width = case.getValue("width").number()
            val o = JusticeGolden.options(case.getValue("policy").jsonObject)
            val optical = case["optical"]?.takeUnless { it is JsonNull }?.let { JusticeGolden.opticals.getValue(it.jsonPrimitive.content) }
            val p = JusticeGolden.prepared.getValue(case.getValue("prepared").jsonPrimitive.content)
            val result = Justice.solve(p, width, o)
            assertClose(case.name(), exhaustiveHyphenated(text, width, o, false, optical), result.cost, 5)
            val source = StringBuilder()
            result.lines.forEachIndexed { j, line ->
                val visible = Justice.lineText(p, line)
                if (j != 0 && !result.lines[j - 1].hyphenated) source.append(" ")
                source.append(if (line.hyphenated) visible.dropLast(1) else visible)
                assertClose(case.name(), shaped(visible), line.natural, 8)
                assertTrue(abs(line.tracking) <= o.tracking + 1e-9)
                assertTrue(line.wordSpacing >= -4 * o.shrink - 1e-9)
                if (!line.last || line.natural > width + line.hanging + line.opening) {
                    val rendered = line.natural + line.wordSpacing * (line.end - line.start - 1) + line.tracking * visible.codePointCount() + line.residual
                    assertClose(case.name(), width + line.hanging + line.opening, rendered, 7)
                }
            }
            assertEquals(text, source.toString())
        }
    }

    private fun exhaustive(p: Justice.Prepared, widths: List<Double>, o: Justice.Options, emergency: Boolean = false): Double {
        var best = Double.POSITIVE_INFINITY
        fun visit(start: Int, total: Double, previousFitness: Int, line: Int) {
            if (start == p.words.size) {
                best = min(best, total)
                return
            }
            val width = widths[min(line, widths.size - 1)]
            for (end in start + 1..p.words.size) {
                val text = p.words.subList(start, end).joinToString(" ")
                val natural = measure(text)
                val suffix = trailing.find(text)?.value ?: ""
                val quote = leading.find(text)?.value ?: ""
                val target = width + max(measure(suffix) * o.hanging, (p.endProtrusions?.get(end - 1) ?: 0.0) * o.protrusion) +
                    (if (p.startProtrusions != null) p.startProtrusions[start] * o.protrusion else measure(quote) * o.opening)
                val delta = if (end == p.words.size && natural <= target) 0.0 else target - natural
                val capacity = (end - start - 1) * p.space * (if (delta < 0) o.shrink else o.stretch) + text.codePointCount() * o.tracking
                val ratio = if (capacity != 0.0) min(1.0, abs(delta) / capacity) else 0.0
                val signed = if (capacity != 0.0) delta / capacity else if (delta == 0.0) 0.0 else sign(delta) * Double.POSITIVE_INFINITY
                val fitness = if (signed < -0.5) 0 else if (signed <= 0.5) 1 else if (signed <= 1) 2 else 3
                var residual = delta - sign(delta) * ratio * capacity
                var cost = 1 + 100 * ratio.pow(3)
                if (o.mode == Justice.Mode.balanced) {
                    val gaps = end - start - 1
                    cost = 1 + 100 * (ratio + abs(residual) / max(max(capacity, gaps * p.space), p.space)).pow(3)
                    if (gaps != 0) {
                        val before = sign(delta) * ratio * p.space * (if (delta < 0) o.shrink else o.stretch)
                        val after = max(-p.space * o.shrink, before + residual / gaps)
                        residual -= (after - before) * gaps
                    }
                }
                if (emergency && delta > 0) {
                    val extra = min(width * o.emergencyStretch, (end - start - 1) * p.space)
                    cost = 1 + 100 * (delta / max(p.space, capacity + extra)).pow(3)
                }
                if (o.mode == Justice.Mode.balanced && o.emergencyStretch > 0 && !emergency && (delta > capacity * cbrt(2.0) + 0.01 || abs(residual) > 0.01)) continue
                if (delta < 0) cost = 1 + (cost - 1) * o.compressionPenalty
                if (abs(residual) > 0.01) cost += if (o.mode == Justice.Mode.balanced && residual < 0) 1e6 + 1e4 * residual.pow(2) else 10000 + 100 * residual.pow(2)
                if (end == p.words.size && start > 0) {
                    val deficit = max(0.0, width * o.lastLine - natural)
                    val flex = (end - start - 1) * p.space * o.stretch + text.codePointCount() * o.tracking
                    cost += if (o.ending == Justice.Ending.soft) o.widowPenalty * (deficit / width).pow(2)
                    else o.widowPenalty / 300 * min(10000.0, 100 * (deficit / max(p.space, flex)).pow(3))
                }
                if (start != 0 && abs(fitness - previousFitness) > 1) cost += o.adjacentPenalty
                visit(end, total + cost, fitness, line + 1)
            }
        }
        visit(0, 0.0, 1, 0)
        return if (best == Double.POSITIVE_INFINITY && !emergency) exhaustive(p, widths, o, true) else best
    }

    private class Break(val end: Int, val next: Int, val hyphen: Boolean)

    private fun exhaustiveHyphenated(text: String, width: Double, o: Justice.Options, emergency: Boolean, optical: ((String, Int) -> Justice.Edges)?): Double {
        val breaks = mutableListOf(Break(0, 0, false))
        for (match in Regex("[^ ]+").findAll(text)) {
            val parts = match.value.chunked(3)
            var offset = match.range.first
            for (part in parts.dropLast(1)) {
                offset += part.length
                breaks += Break(offset, offset, true)
            }
            val end = match.range.last + 1
            breaks += Break(end, min(text.length, end + 1), false)
        }
        var best = Double.POSITIVE_INFINITY
        fun visit(start: Int, total: Double, previousFitness: Int) {
            if (start == breaks.size - 1) {
                best = min(best, total)
                return
            }
            for (end in start + 1 until breaks.size) {
                val a = breaks[start]
                val b = breaks[end]
                val visible = text.substring(a.next, b.end) + if (b.hyphen) "-" else ""
                val last = end == breaks.size - 1
                val gaps = visible.split(" ").size - 1
                val chars = visible.codePointCount()
                val natural = shaped(visible)
                val firstIndex = text.substring(0, a.next).split(" ").size - 1
                val lastIndex = text.substring(0, b.end).split(" ").size - 1
                val hanging = max(
                    if (b.hyphen) 0.0 else shaped(trailing.find(visible)?.value ?: "") * o.hanging,
                    (optical?.invoke(visible.split(" ").last(), lastIndex)?.end ?: 0.0) * o.protrusion,
                )
                val opening = if (optical != null) optical(visible.split(" ")[0], firstIndex).start * o.protrusion
                else shaped(leading.find(visible)?.value ?: "") * o.opening
                val target = width + hanging + opening
                val delta = if (last && natural <= target) 0.0 else target - natural
                val capacity = gaps * 4 * (if (delta < 0) o.shrink else o.stretch) + chars * o.tracking
                val ratio = if (capacity != 0.0) min(1.0, abs(delta) / capacity) else 0.0
                val signed = if (capacity != 0.0) delta / capacity else if (delta == 0.0) 0.0 else sign(delta) * Double.POSITIVE_INFINITY
                val fitness = if (signed < -0.5) 0 else if (signed <= 0.5) 1 else if (signed <= 1) 2 else 3
                var residual = delta - sign(delta) * ratio * capacity
                var strain = 100 * ratio.pow(3)
                if (o.mode == Justice.Mode.balanced) {
                    strain = 100 * (ratio + abs(residual) / max(max(capacity, gaps * 4.0), 4.0)).pow(3)
                    if (gaps != 0) {
                        val before = sign(delta) * ratio * 4 * (if (delta < 0) o.shrink else o.stretch)
                        residual -= (max(-4 * o.shrink, before + residual / gaps) - before) * gaps
                    }
                }
                if (emergency && delta > 0) {
                    val extra = min(width * o.emergencyStretch, gaps * shaped(" "))
                    strain = 100 * (delta / max(shaped(" "), capacity + extra)).pow(3)
                }
                if (o.mode == Justice.Mode.balanced && o.emergencyStretch > 0 && !emergency && (delta > capacity * cbrt(2.0) + 0.01 || abs(residual) > 0.01)) continue
                var cost = 1 + strain * (if (delta < 0) o.compressionPenalty else 1.0)
                if (abs(residual) > 0.01) cost += if (o.mode == Justice.Mode.balanced && residual < 0) 1e6 + 1e4 * residual.pow(2) else 1e4 + 100 * residual.pow(2)
                if (last && start > 0) {
                    val missing = max(0.0, width * o.lastLine - natural)
                    cost += if (o.ending == Justice.Ending.soft) o.widowPenalty * (missing / width).pow(2)
                    else o.widowPenalty / 300 * min(10000.0, 100 * (missing / max(4.0, gaps * 4 * o.stretch + chars * o.tracking)).pow(3))
                }
                cost += if (b.hyphen) o.hyphenPenalty + (if (a.hyphen) o.consecutiveHyphenPenalty else 0.0)
                else if (last && a.hyphen) o.finalHyphenPenalty else 0.0
                if (start != 0 && abs(fitness - previousFitness) > 1) cost += o.adjacentPenalty
                visit(end, total + cost, fitness)
            }
        }
        visit(0, 0.0, 1)
        return if (best == Double.POSITIVE_INFINITY && !emergency) exhaustiveHyphenated(text, width, o, true, optical) else best
    }

    /** The tests' linear congruential generator, `Math.imul(seed, 1664525) + 1013904223 >>> 0`. */
    private fun seeded(start: Int): () -> Double {
        var seed = start
        return {
            seed = seed * 1664525 + 1013904223
            (seed.toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    private fun assertClose(message: String, expected: Double, actual: Double, digits: Int) {
        if (expected == actual) return
        assertTrue("$message: $actual is not close to $expected", abs(expected - actual) < 10.0.pow(-digits) / 2)
    }

    private fun String.codePointCount() = codePointCount(0, length)

    private fun kotlinx.serialization.json.JsonObject.name() = getValue("name").jsonPrimitive.content
}
