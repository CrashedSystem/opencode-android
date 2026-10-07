package ai.opencode.mobilecode.system

import ai.opencode.mobilecode.system.JusticeGolden.number
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every layout the TypeScript engine produced for `golden.json`, reproduced by the port. */
class JusticeGoldenTests {
    @Test
    fun defaultsMatchTheLibrary() {
        assertEquals(JusticeGolden.options(JusticeGolden.root.getValue("defaults").jsonObject), Justice.defaults)
    }

    @Test
    fun everyLayoutMatchesTheLibrary() {
        val failures = mutableListOf<String>()
        for (case in JusticeGolden.cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val expected = JusticeGolden.prepared.getValue(case.getValue("prepared").jsonPrimitive.content)
            val p = if (case.getValue("source").jsonPrimitive.content == "text") {
                val plain = Justice.prepare(case.getValue("text").jsonPrimitive.content, JusticeGolden.measures.getValue(case.getValue("measure").jsonPrimitive.content))
                val optical = case["optical"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val built = if (optical == null) plain else Justice.withOpticalMargins(plain, JusticeGolden.opticals.getValue(optical))
                failures += preparedDifferences(name, built, expected)
                built
            } else {
                expected
            }
            val layout = Justice.solve(p, JusticeGolden.widths(case.getValue("width")), JusticeGolden.options(case.getValue("policy").jsonObject))
            val want = case.getValue("layout").jsonObject
            failures += layoutDifferences(name, layout, want)
        }
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
        assertEquals(486, JusticeGolden.cases.size)
    }

    private fun layoutDifferences(name: String, layout: Justice.Layout, want: JsonObject): List<String> {
        val out = mutableListOf<String>()
        val lines = want.getValue("lines").jsonArray.map { it.jsonObject }
        if (layout.lines.size != lines.size) return listOf("$name: ${layout.lines.size} lines, expected ${lines.size}")
        if (!close(layout.cost, want.getValue("cost").number(), relative = true)) out += "$name: cost ${layout.cost} != ${want.getValue("cost")}"
        if (layout.candidates != want.getValue("candidates").jsonPrimitive.int) out += "$name: candidates ${layout.candidates} != ${want.getValue("candidates")}"
        layout.lines.zip(lines).forEachIndexed { index, (line, json) ->
            fun exact(field: String, actual: Any?, expected: Any?) {
                if (actual != expected) out += "$name line $index: $field $actual != $expected"
            }
            fun near(field: String, actual: Double, relative: Boolean = false) {
                val expected = json.getValue(field).number()
                if (!close(actual, expected, relative)) out += "$name line $index: $field $actual != $expected"
            }
            exact("start", line.start, json.getValue("start").jsonPrimitive.int)
            exact("end", line.end, json.getValue("end").jsonPrimitive.int)
            exact("startOffset", line.startOffset, json.getValue("startOffset").takeUnless { it is JsonNull }?.jsonPrimitive?.int)
            exact("endOffset", line.endOffset, json.getValue("endOffset").takeUnless { it is JsonNull }?.jsonPrimitive?.int)
            exact("hyphenated", line.hyphenated, json.getValue("hyphenated").jsonPrimitive.boolean)
            exact("last", line.last, json.getValue("last").jsonPrimitive.boolean)
            exact("relaxed", line.relaxed, json.getValue("relaxed").jsonPrimitive.boolean)
            exact("fitness", line.fitness, json.getValue("fitness").jsonPrimitive.int)
            near("width", line.width)
            near("natural", line.natural)
            near("wordSpacing", line.wordSpacing)
            near("tracking", line.tracking)
            near("hanging", line.hanging)
            near("opening", line.opening)
            near("residual", line.residual)
            near("cost", line.cost, relative = true)
        }
        return out
    }

    private fun preparedDifferences(name: String, actual: Justice.Prepared, expected: Justice.Prepared): List<String> {
        val out = mutableListOf<String>()
        fun arrays(field: String, a: DoubleArray?, b: DoubleArray?) {
            if ((a == null) != (b == null) || (a != null && (a.size != b!!.size || a.indices.any { !close(a[it], b[it]) }))) {
                out += "$name: prepared $field ${a?.toList()} != ${b?.toList()}"
            }
        }
        if (actual.words != expected.words) out += "$name: words ${actual.words} != ${expected.words}"
        arrays("widths", actual.widths, expected.widths)
        arrays("characters", actual.characters, expected.characters)
        arrays("endHangs", actual.endHangs, expected.endHangs)
        arrays("startHangs", actual.startHangs, expected.startHangs)
        arrays("startProtrusions", actual.startProtrusions, expected.startProtrusions)
        arrays("endProtrusions", actual.endProtrusions, expected.endProtrusions)
        if (actual.space != expected.space) out += "$name: space ${actual.space} != ${expected.space}"
        val a = actual.hyphenation
        val b = expected.hyphenation
        if ((a == null) != (b == null) || (a != null && a.size != b!!.size)) {
            out += "$name: hyphenation ${a?.size} != ${b?.size}"
        } else if (a != null) {
            a.zip(b!!).forEachIndexed { index, (x, y) ->
                if ((x == null) != (y == null)) out += "$name: fragments of word $index"
                if (x == null || y == null) return@forEachIndexed
                if (!x.offsets.contentEquals(y.offsets)) out += "$name: offsets of word $index ${x.offsets.toList()} != ${y.offsets.toList()}"
                if (!(x.explicit ?: BooleanArray(0)).contentEquals(y.explicit ?: BooleanArray(0))) out += "$name: explicit of word $index"
                arrays("fragment widths $index", x.widths, y.widths)
                arrays("fragment hyphenWidths $index", x.hyphenWidths, y.hyphenWidths)
                arrays("fragment characters $index", x.characters, y.characters)
                arrays("fragment startProtrusions $index", x.startProtrusions, y.startProtrusions)
                if (x.hyphenProtrusion != y.hyphenProtrusion) out += "$name: hyphenProtrusion of word $index"
            }
        }
        return out
    }

    private fun close(actual: Double, expected: Double, relative: Boolean = false): Boolean {
        if (actual == expected) return true
        val tolerance = if (relative) 1e-9 * max(1.0, abs(expected)) else 1e-6
        return abs(actual - expected) <= tolerance
    }
}
