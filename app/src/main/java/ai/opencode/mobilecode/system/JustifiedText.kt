package ai.opencode.mobilecode.system

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import kotlin.math.ceil

/**
 * Text set justified by [Justice] at its measured width. The characters stay as they are, so
 * selection, copying, links, and accessibility read the source text. Spacing is letter spacing:
 * each line's tracking on every character, plus its word spacing on the first character of each
 * gap. A line breaks where the engine broke it because the space there is scaled too wide for
 * the next word to fit; a trailing space hangs past the margin and draws nothing. Text the
 * engine does not cover (tabs, scripts other than Latin, Greek, and Cyrillic) is set ragged.
 */
@Composable
fun JustifiedText(text: AnnotatedString, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val fonts = LocalFontFamilyResolver.current
        val justified = remember(style) { Justification.style(style) }
        val widths = remember(justified, density, fonts) { TextWidths(justified, density, fonts) }
        val width = constraints.maxWidth
        val plan = remember(text, width, widths) {
            if (constraints.hasBoundedWidth) Justification.plan(text, width, widths) else null
        }
        if (plan == null) {
            Text(text, Modifier.fillMaxWidth(), style = style, color = color)
        } else {
            Text(
                plan.text,
                Modifier.layout { measurable, constraints ->
                    // Hanging punctuation draws past the margin, so the text lays out wider than it reports.
                    val placeable = measurable.measure(Constraints.fixedWidth(plan.width))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
                },
                style = justified,
                color = color,
            )
        }
    }
}

/** Advances of styled pieces of text in one style, in pixels. Misses are measured together in
 * one paragraph, one piece per line, and kept, so a streamed token measures only what it changed. */
class TextWidths(private val style: TextStyle, val density: Density, private val fonts: FontFamily.Resolver) {
    private val cache = HashMap<AnnotatedString, Double>()
    private val space = AnnotatedString(" ")
    private val pixelsPerEm = HashMap<TextUnit, Float>()

    /** What one em of letter spacing adds per character at [fontSize] (the style's size when
     * unspecified). Hinted text applies it at the size rounded down to whole pixels, so it is
     * measured rather than assumed. */
    fun pixelsPerEm(fontSize: TextUnit): Float = pixelsPerEm.getOrPut(fontSize) {
        val probe = "MMMMMMMMMM"
        val size = SpanStyle(fontSize = fontSize)
        val plain = Paragraph(probe, style, Constraints(), density, fonts, listOf(AnnotatedString.Range(size, 0, probe.length)))
        val spaced = Paragraph(probe, style, Constraints(), density, fonts, listOf(AnnotatedString.Range(size.copy(letterSpacing = 1.em), 0, probe.length)))
        (spaced.getHorizontalPosition(probe.length, true) - plain.getHorizontalPosition(probe.length, true)) / probe.length
    }

    fun width(piece: AnnotatedString): Double {
        cache[piece]?.let { return it }
        measure(listOf(piece))
        return cache.getValue(piece)
    }

    /** [Justice.prepare] for `text[start, end)` in dp, the units the library's policy is tuned
     * for, measuring every piece it asks for in one batch. */
    fun prepare(text: AnnotatedString, start: Int, end: Int): Justice.Prepared {
        val missing = LinkedHashSet<AnnotatedString>()
        val draft = prepare(text, start, end) { piece -> cache[piece] ?: 1.0.also { missing += piece } }
        if (missing.isEmpty()) return draft
        measure(missing)
        return prepare(text, start, end) { cache.getValue(it) }
    }

    private fun prepare(text: AnnotatedString, start: Int, end: Int, width: (AnnotatedString) -> Double) = Justice.prepare(
        text.text.substring(start, end),
        width(space) / density.density,
        { a, b -> width(text.subSequence(start + a, start + b)) / density.density },
        measureGaps = true,
    )

    private fun measure(pieces: Collection<AnnotatedString>) {
        val joined = AnnotatedString.Builder()
        pieces.forEachIndexed { index, piece ->
            if (index > 0) joined.append('\n')
            joined.append(piece)
        }
        val text = joined.toAnnotatedString()
        val paragraph = Paragraph(text.text, style, Constraints(), density, fonts, text.spanStyles)
        var start = 0
        for (piece in pieces) {
            val end = start + piece.length
            cache[piece] = (paragraph.getHorizontalPosition(end, true) - paragraph.getHorizontalPosition(start, true)).toDouble()
            start = end + 1
        }
    }
}

object Justification {
    /** [text] with its spacing spans, and the width in pixels to lay it out at: the paragraph's
     * width plus room for punctuation hanging past the right margin. */
    class Plan(val text: AnnotatedString, val width: Int, val lines: List<Line>)

    /** A line as the engine set it: its characters, where its last character ends, and how far
     * right it may draw, in pixels. */
    data class Line(val start: Int, val end: Int, val right: Double, val reach: Double)

    /** [style] as justified text draws and measures it. The breaker is greedy, so it takes each
     * planned line whole. Standard ligatures are off because Android letter-spaces a ligature
     * once, while the engine spaces every character. */
    fun style(style: TextStyle) = style.copy(
        fontFeatureSettings = listOfNotNull(style.fontFeatureSettings, "liga 0, clig 0").joinToString(", "),
        lineBreak = LineBreak.Simple,
        textAlign = TextAlign.Left,
    )

    /** A hard-break-delimited run `text[start, end)`, its word ranges relative to [start], and the
     * width in dp of any whitespace it opens with. */
    private class Segment(val start: Int, val end: Int, val ranges: IntArray, val indent: Double, val prepared: Justice.Prepared?)

    fun plan(text: AnnotatedString, width: Int, widths: TextWidths, options: Justice.Options = Justice.defaults): Plan? {
        val source = text.text
        if (width <= 0 || !justifiable(source)) return null
        val scale = widths.density.density.toDouble()
        val segments = buildList {
            var start = 0
            while (start <= source.length) {
                val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
                val ranges = Justice.wordRanges(source.substring(start, end))
                // Whitespace after a hard break stays, as an indent on that first line.
                val indent = if (ranges.isNotEmpty() && ranges[0] > 0) widths.width(text.subSequence(start, start + ranges[0])) / scale else 0.0
                if (indent >= width / scale) return null
                add(Segment(start, end, ranges, indent, if (ranges.isEmpty()) null else widths.prepare(text, start, end)))
                start = end + 1
            }
        }
        val spacing = FloatArray(source.length)
        val breaks = BooleanArray(source.length)
        val lines = segments.flatMap { set(text, it, width / scale, scale, options, spacing, breaks) }
        // Room for the hanging punctuation, plus the letter spacing Android trims or estimates
        // at line edges. A line too long even for that wraps where Android breaks it.
        val layoutWidth = ceil(lines.fold(width.toDouble()) { widest, line -> maxOf(widest, line.reach) } + 2 * options.tracking * scale + 1).toInt()
        val stripped = text.flatMapAnnotations { range ->
            if (range.item !is SpanStyle || (range.start until range.end).none { breaks[it] }) return@flatMapAnnotations listOf(range)
            // A break space hangs invisibly; a background or strikethrough on it would run off the screen.
            val pieces = mutableListOf<AnnotatedString.Range<out AnnotatedString.Annotation>>()
            var from = range.start
            for (index in range.start..range.end) {
                if (index < range.end && !breaks[index]) continue
                if (index > from) pieces += AnnotatedString.Range(range.item, from, index)
                from = index + 1
            }
            pieces
        }
        val builder = AnnotatedString.Builder(stripped)
        // Scaled rather than letter-spaced, so the break space leaves Minikin's estimate of
        // letter spacing at line edges alone.
        val wide = SpanStyle(textGeometricTransform = TextGeometricTransform(scaleX = (2 * layoutWidth / widths.width(AnnotatedString(" "))).toFloat()))
        for (index in source.indices) if (breaks[index]) builder.addStyle(wide, index, index + 1)
        val pixelsPerEm = pixelsPerEm(text, widths)
        var run = 0
        for (index in 1..source.length) {
            val em = spacing[run] / pixelsPerEm[run]
            if (index < source.length && spacing[index] / pixelsPerEm[index] == em) continue
            if (em != 0f) builder.addStyle(SpanStyle(letterSpacing = em.em), run, index)
            run = index
        }
        return Plan(builder.toAnnotatedString(), layoutWidth, lines)
    }

    /** Solves [segment] at [measure] dp and writes each character's letter spacing in pixels into
     * [spacing] and each break space into [breaks]. Android's line breaker never breaks right
     * after a hyphen mid-line, so the segment solves with whole words. */
    private fun set(
        text: AnnotatedString,
        segment: Segment,
        measure: Double,
        scale: Double,
        options: Justice.Options,
        spacing: FloatArray,
        breaks: BooleanArray,
    ): List<Line> {
        val p = segment.prepared?.copy(hyphenation = null) ?: return emptyList()
        val layout = Justice.solve(p, if (segment.indent > 0) listOf(measure - segment.indent, measure) else listOf(measure), options)
        val source = text.text
        return layout.lines.mapIndexed { index, line ->
            val first = segment.start + segment.ranges[2 * line.start]
            val last = segment.start + segment.ranges[2 * line.end - 1]
            for (i in first until last) {
                spacing[i] = (scale * when {
                    !Justice.isSeparator(source[i]) -> line.tracking
                    Justice.isSeparator(source[i - 1]) -> 0.0
                    else -> line.wordSpacing + line.tracking
                }).toFloat()
            }
            // The opening quote's advance shrinks so the rest of the line starts where the engine hung it.
            if (line.opening > 0) spacing[first] = (scale * (line.tracking - line.opening)).toFloat()
            if (index < layout.lines.lastIndex) breaks[last] = true
            val ragged = line.last && line.natural <= line.width + line.hanging + line.opening
            val right = (if (index == 0) segment.indent else 0.0) + if (ragged) line.natural - line.opening else line.width + line.hanging - line.residual
            // Android 15 trims the opening quote's negative spacing at the line edge, which moves
            // the line right by up to half the opening.
            val reach = minOf(right, measure + line.hanging) + line.opening / 2
            Line(if (index == 0) segment.start else first, last, right * scale, reach * scale)
        }
    }

    private fun pixelsPerEm(text: AnnotatedString, widths: TextWidths): FloatArray {
        val sizes = FloatArray(text.length) { widths.pixelsPerEm(TextUnit.Unspecified) }
        for (range in text.spanStyles) {
            val size = range.item.fontSize
            if (!size.isSpecified) continue
            val px = widths.pixelsPerEm(size)
            for (i in range.start until range.end) sizes[i] = px
        }
        return sizes
    }

    private fun justifiable(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint == '\t'.code) return false
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.LATIN,
                Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC -> {}
                else -> return false
            }
            index += Character.charCount(codePoint)
        }
        return true
    }
}
