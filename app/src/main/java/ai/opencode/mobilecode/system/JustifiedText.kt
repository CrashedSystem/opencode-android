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
import androidx.compose.ui.text.TextLayoutResult
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
import kotlin.math.roundToInt

/**
 * Text set justified by [Justice] at its measured width. The characters stay as they are, so
 * selection, copying, links, and accessibility read the source text. While the text only grows,
 * as a streamed reply does, lines above the last two keep their breaks. Text the engine does not
 * cover (tabs, scripts other than Latin, Greek, and Cyrillic) is set ragged.
 */
@Composable
fun JustifiedText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val fonts = LocalFontFamilyResolver.current
        val justified = remember(style) { Justification.style(style) }
        val widths = remember(justified, density, fonts) { TextWidths(justified, density, fonts) }
        val width = constraints.maxWidth
        val previous = remember(widths) { arrayOfNulls<Justification.Plan>(1) }
        val plan = remember(text, width, widths) {
            val plan = if (constraints.hasBoundedWidth) Justification.plan(text, width, widths, previous = previous[0]) else null
            previous[0] = plan
            plan
        }
        if (plan == null) {
            Text(text, Modifier.fillMaxWidth(), style = style, color = color, onTextLayout = onTextLayout)
        } else {
            Text(
                plan.text,
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints.fixedWidth(plan.layoutWidth))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
                },
                style = justified,
                color = color,
                onTextLayout = onTextLayout,
            )
        }
    }
}

/** Advances of styled pieces of text in one style, in pixels, and how many units Android
 * letter-spaces in each: one per character, except that a ligature counts once. */
class TextWidths(private val style: TextStyle, val density: Density, private val fonts: FontFamily.Resolver) {
    private val cache = HashMap<AnnotatedString, Double>()
    private val clusters = HashMap<AnnotatedString, Int>()
    private val space = AnnotatedString(" ")
    private val pixelsPerEm = HashMap<TextUnit, Float>()

    /** What one em of letter spacing adds per character at [fontSize] (the style's size when
     * unspecified). Hinted text applies it at the size rounded down to whole pixels, so it is
     * measured rather than assumed. The probe is read inside its first and last characters,
     * because Android 15 and later draw only half the spacing on those. */
    fun pixelsPerEm(fontSize: TextUnit): Float = pixelsPerEm.getOrPut(fontSize) {
        val probe = "MMMMMMMMMM"
        val size = SpanStyle(fontSize = fontSize)
        val plain = Paragraph(probe, style, Constraints(), density, fonts, listOf(AnnotatedString.Range(size, 0, probe.length)))
        val spaced = Paragraph(probe, style, Constraints(), density, fonts, listOf(AnnotatedString.Range(size.copy(letterSpacing = 1.em), 0, probe.length)))
        val inner = { laid: Paragraph -> laid.getHorizontalPosition(probe.length - 1, true) - laid.getHorizontalPosition(1, true) }
        (inner(spaced) - inner(plain)) / (probe.length - 2)
    }

    /** The share of the letter spacing on a line's first and last characters that Android
     * counts in the line: all of it before Android 15, then only the half inside the line. */
    val keptAtLineEdge: Float by lazy {
        val plain = Paragraph("MM", style, Constraints(), density, fonts)
        val spaced = Paragraph("MM", style, Constraints(), density, fonts, listOf(AnnotatedString.Range(SpanStyle(letterSpacing = 1.em), 0, 2)))
        (spaced.getHorizontalPosition(1, true) - plain.getHorizontalPosition(1, true)) / pixelsPerEm(TextUnit.Unspecified)
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
        clusters = { a, b -> clusters[text.subSequence(start + a, start + b)] },
        // Minikin never breaks right after a hyphen mid-line, so a break the engine put there could not be drawn.
        breakAfterHyphens = false,
    )

    /** Each piece is laid out twice between unspaced spaces, plain and at [probe] em of letter
     * spacing, which stays below the 0.03 em at which Minikin turns ligatures off. The difference,
     * read through the space after each copy, counts the spaced units. The spaces keep the piece
     * off the line's edges, where Android 15 and later draw half its spacing, and a position read
     * at a style change leaves out half the spacing of the character before it. */
    private fun measure(pieces: Collection<AnnotatedString>) {
        val joined = AnnotatedString.Builder()
        for (piece in pieces) {
            joined.append(" ")
            joined.append(piece)
            joined.append(" \n ")
            val start = joined.length
            joined.append(piece)
            joined.addStyle(SpanStyle(letterSpacing = probe.em), start, joined.length)
            joined.append(" \n")
        }
        val text = joined.toAnnotatedString()
        val paragraph = Paragraph(text.text, style, Constraints(), density, fonts, text.spanStyles)
        val x = { offset: Int -> paragraph.getHorizontalPosition(offset, true) }
        var start = 1
        for (piece in pieces) {
            val spacedStart = start + piece.length + 3
            val plain = x(start + piece.length) - x(start)
            val spacing = x(spacedStart + piece.length + 1) - x(spacedStart) - (x(start + piece.length + 1) - x(start))
            start = spacedStart + piece.length + 3
            cache[piece] = plain.toDouble()
            val size = piece.spanStyles.singleOrNull { it.start == 0 && it.end == piece.length && it.item.fontSize.isSpecified }?.item?.fontSize
            if (size != null || piece.spanStyles.none { it.item.fontSize.isSpecified }) {
                clusters[piece] = (spacing / (probe * pixelsPerEm(size ?: TextUnit.Unspecified))).roundToInt()
            }
        }
    }

    private companion object {
        const val probe = 0.02f
    }
}

object Justification {
    /** [text] with its spacing spans, and the width in pixels to lay it out at: the paragraph's
     * width plus room for punctuation hanging past the right margin. */
    class Plan(
        val text: AnnotatedString,
        val layoutWidth: Int,
        val lines: List<Line>,
        internal val source: AnnotatedString,
        internal val paragraphWidth: Int,
        internal val spacing: FloatArray,
        internal val breaks: BooleanArray,
    ) {
        internal fun resumeAt(next: AnnotatedString, width: Int): Int {
            if (width != paragraphWidth || lines.size < 3 || next.length < source.length) return 0
            if (next.subSequence(0, source.length) != source) return 0
            return lines[lines.size - 2].start
        }
    }

    /** A line as the engine set it: its characters, where Android ends its last character, and
     * how far right it may draw, in pixels. A [loose] line would have needed gaps wider than the
     * bound and is set ragged. */
    data class Line(val start: Int, val end: Int, val right: Double, val reach: Double, val loose: Boolean)

    /** [style] as justified text draws and measures it. The breaker is greedy, so it takes each
     * planned line whole. */
    fun style(style: TextStyle) = style.copy(lineBreak = LineBreak.Simple, textAlign = TextAlign.Left)

    private class Segment(val start: Int, val end: Int, val ranges: IntArray, val indent: Double, val prepared: Justice.Prepared?)

    fun plan(text: AnnotatedString, width: Int, widths: TextWidths, options: Justice.Options = Justice.defaults, previous: Plan? = null): Plan? {
        val source = text.text
        if (width <= 0 || !justifiable(source)) return null
        // Text that ignores letter spacing, as in Robolectric's legacy graphics, could not draw a plan.
        if (widths.pixelsPerEm(TextUnit.Unspecified) <= 0f) return null
        val scale = widths.density.density.toDouble()
        val resume = previous?.resumeAt(text, width) ?: 0
        val segments = buildList {
            var start = resume
            while (start <= source.length) {
                val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
                val ranges = Justice.wordRanges(source.substring(start, end))
                val indent = if (ranges.isNotEmpty() && ranges[0] > 0) widths.width(text.subSequence(start, start + ranges[0])) / scale else 0.0
                if (indent >= width / scale) return null
                add(Segment(start, end, ranges, indent, if (ranges.isEmpty()) null else widths.prepare(text, start, end)))
                start = end + 1
            }
        }
        val spacing = FloatArray(source.length)
        val breaks = BooleanArray(source.length)
        previous?.spacing?.copyInto(spacing, endIndex = resume)
        previous?.breaks?.copyInto(breaks, endIndex = resume)
        val kept = previous?.lines?.filter { it.start < resume }.orEmpty()
        val lines = kept + segments.flatMap { solveInto(spacing, breaks, text, it, width / scale, widths, options) }
        // Room for the hanging punctuation, plus the spacing Android's line breaker counts on a
        // line's first character but does not draw. A line too long even for that wraps where
        // Android breaks it.
        val layoutWidth = ceil(lines.fold(width.toDouble()) { widest, line -> maxOf(widest, line.reach) } + 2 * options.tracking * scale + 1).toInt()
        val builder = AnnotatedString.Builder(text.withoutSpanStylesOn(breaks))
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
        return Plan(builder.toAnnotatedString(), layoutWidth, lines, text, width, spacing, breaks)
    }

    private fun solveInto(
        spacing: FloatArray,
        breaks: BooleanArray,
        text: AnnotatedString,
        segment: Segment,
        measure: Double,
        widths: TextWidths,
        options: Justice.Options,
    ): List<Line> {
        val p = segment.prepared ?: return emptyList()
        val layout = Justice.solve(p, if (segment.indent > 0) listOf(measure - segment.indent, measure) else listOf(measure), options)
        val source = text.text
        val scale = widths.density.density.toDouble()
        val dropped = 1 - widths.keptAtLineEdge
        return layout.lines.mapIndexed { index, line ->
            val first = segment.start + segment.ranges[2 * line.start]
            val start = if (index == 0) segment.start else first
            val last = segment.start + segment.ranges[2 * line.end - 1]
            val gap = (first until last).firstOrNull { Justice.isSeparator(source[it]) }
            val loose = p.space + line.wordSpacing + line.tracking > p.space * maxGapInSpaces
            if (!loose) {
                for (i in first until last) {
                    spacing[i] = (scale * when {
                        !Justice.isSeparator(source[i]) -> line.tracking
                        Justice.isSeparator(source[i - 1]) -> 0.0
                        else -> line.wordSpacing + line.tracking
                    }).toFloat()
                }
                if (line.opening > 0) {
                    // Spaced apart from its word, the quote loses any kerning with the next letter.
                    val word = text.subSequence(first, segment.start + segment.ranges[2 * line.start + 1])
                    val unkerned = widths.width(word.subSequence(0, 1)) + widths.width(word.subSequence(1, word.length)) - widths.width(word)
                    spacing[first] = (scale * (line.tracking - line.opening) - unkerned).toFloat()
                }
                // The first gap takes the share of the first character's spacing that Android
                // leaves out. Moving it onto the character would split it from its word, and
                // the split drops their kerning.
                if (gap != null) spacing[gap] += dropped * spacing[start]
            }
            if (index < layout.lines.lastIndex) breaks[last] = true
            val ragged = loose || line.last && line.natural <= line.width + line.hanging + line.opening
            val right = (if (index == 0) segment.indent else 0.0) + when {
                loose -> line.natural
                ragged -> line.natural - line.opening
                else -> line.width + line.hanging - line.residual
            }
            // Minikin's greedy breaker subtracts half the spacing of the run it started the line
            // in, the first gap or a paragraph's first character, as its guess at what the edges
            // trim. A negative run makes the line look wider than it is.
            val startRun = if (start == 0 || source[start - 1] == '\n') spacing[start] else gap?.let { spacing[it] } ?: 0f
            val reach = minOf(right, measure + line.hanging) + maxOf(0.0, -startRun / scale) / 2
            // Android leaves the same share of the last character's spacing out of the line, and
            // of the first character's when no gap took it.
            val edges = dropped * (spacing[last - 1] + if (gap == null) spacing[start] else 0f) / scale
            Line(start, last, (right - edges) * scale, reach * scale, loose)
        }
    }

    private const val maxGapInSpaces = 3.5

    private fun AnnotatedString.withoutSpanStylesOn(breaks: BooleanArray) = flatMapAnnotations { range ->
        if (range.item !is SpanStyle || (range.start until range.end).none { breaks[it] }) return@flatMapAnnotations listOf(range)
        val pieces = mutableListOf<AnnotatedString.Range<out AnnotatedString.Annotation>>()
        var from = range.start
        for (index in range.start..range.end) {
            if (index < range.end && !breaks[index]) continue
            if (index > from) pieces += AnnotatedString.Range(range.item, from, index)
            from = index + 1
        }
        pieces
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
