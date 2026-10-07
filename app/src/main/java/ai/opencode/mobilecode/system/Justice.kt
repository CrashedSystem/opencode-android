package ai.opencode.mobilecode.system

import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Kit Langton's justice paragraph engine (github.com/kitlangton/justice, `src/engine.ts` at 86dbc6d),
 * ported declaration for declaration so an upstream diff applies line by line. The library's
 * numbers are reproduced to 1e-6 by `JusticeGoldenTests` against `scripts/justice-golden.ts`.
 *
 * Not ported: `withHyphenation` (no hyphenation dictionary ships in the app, and a generated
 * hyphen would put a glyph on screen that is not in the selectable text) and `static.ts`.
 * Added: [prepare] over source ranges, which measures each whitespace run instead of
 * collapsing it to one space, for text whose gaps are not all the same width.
 */
object Justice {
    enum class Mode { balanced, strict }

    enum class Ending { soft, fit }

    data class Options(
        /** Relative to measured space. Stretch may relax; shrink always stays bounded. */
        val stretch: Double = 0.6,
        val shrink: Double = 0.25,
        val mode: Mode = Mode.balanced,
        /** Absolute pixels per character. */
        val tracking: Double = 0.3,
        /** Cost multiplier for tightening a line rather than opening its spacing. */
        val compressionPenalty: Double = 2.0,
        /** Maximum extra scoring flexibility as a fraction of width, capped at one
         * natural space per gap, when ordinary fitting fails. */
        val emergencyStretch: Double = 0.15,
        /** Fraction of trailing punctuation advance allowed beyond the right margin. */
        val hanging: Double = 1.0,
        /** Optical allowance for a leading quote, as a fraction of its advance. */
        val opening: Double = 0.3,
        /** Strength of supplied font-aware optical margins (0 disables them). */
        val protrusion: Double = 1.0,
        /** Cost when neighbouring line fitness classes differ by more than one. */
        val adjacentPenalty: Double = 100.0,
        /** Preferred fraction of the measure occupied by the last line. */
        val lastLine: Double = 0.33,
        /** Price a short ending by missing width alone, or by its measured flexibility. */
        val ending: Ending = Ending.fit,
        val widowPenalty: Double = 300.0,
        /** Costs for discretionary, consecutive, and penultimate-line hyphens. */
        val hyphenPenalty: Double = 50.0,
        val consecutiveHyphenPenalty: Double = 200.0,
        val finalHyphenPenalty: Double = 200.0,
        /** Cost of breaking after a hyphen already present in the source ("well-known"). */
        val explicitHyphenPenalty: Double = 20.0,
    )

    class Prepared(
        val words: List<String>,
        val widths: DoubleArray,
        val characters: DoubleArray,
        /** Per-word advance of a trailing punctuation run, not a prefix sum. */
        val endHangs: DoubleArray,
        val startHangs: DoubleArray,
        val space: Double,
        /** Optional absolute-pixel optical credits. Start replaces quote-only credit;
         * end combines with punctuation hanging by taking the larger allowance. */
        val startProtrusions: DoubleArray? = null,
        val endProtrusions: DoubleArray? = null,
        /** Optional measured discretionary breaks; ordinary words retain the fast path. */
        val hyphenation: List<WordFragments?>? = null,
        /** Prefix sums of measured gap widths, one entry per word. Null when every gap is
         * one [space], as in the library. */
        val gaps: DoubleArray? = null,
    ) {
        fun copy(
            startProtrusions: DoubleArray? = this.startProtrusions,
            endProtrusions: DoubleArray? = this.endProtrusions,
            hyphenation: List<WordFragments?>? = this.hyphenation,
        ) = Prepared(words, widths, characters, endHangs, startHangs, space, startProtrusions, endProtrusions, hyphenation, gaps)
    }

    class WordFragments(
        /** UTF-16 source offsets, including zero and the word's length. */
        val offsets: IntArray,
        /** Square matrices indexed by start * offsets.size + end. */
        val widths: DoubleArray,
        val hyphenWidths: DoubleArray,
        val characters: DoubleArray,
        /** Leading credit at each source boundary, plus the generated hyphen's end. */
        val startProtrusions: DoubleArray? = null,
        val hyphenProtrusion: Double? = null,
        /** Per boundary: true when the source already ends in a hyphen there, so a
         * break renders no additional glyph. */
        val explicit: BooleanArray? = null,
    )

    data class Line(
        var start: Int = 0,
        var end: Int = 0,
        /** The measure this line was fitted to. */
        var width: Double = 0.0,
        var natural: Double = 0.0,
        var wordSpacing: Double = 0.0,
        var tracking: Double = 0.0,
        /** Available optical margin credit; a ragged last line need not use it. */
        var hanging: Double = 0.0,
        var opening: Double = 0.0,
        var residual: Double = 0.0,
        /** True when finishing used word spacing beyond the preferred limits. */
        var relaxed: Boolean = false,
        var cost: Double = 0.0,
        var last: Boolean = false,
        /** Source offsets within the first/last included word; null for whole words. */
        var startOffset: Int? = null,
        var endOffset: Int? = null,
        /** Render a discretionary '-' after the source text, without adding it to the source. */
        var hyphenated: Boolean = false,
        /** 0 tight, 1 decent, 2 loose, 3 very loose; based on unbounded fitting strain. */
        var fitness: Int = 0,
    )

    data class Layout(val lines: List<Line>, val cost: Double, val candidates: Int)

    data class Edges(val start: Double, val end: Double)

    val defaults = Options()

    /** Measure once; reuse the prepared paragraph across widths and policy changes.
     * ASCII whitespace collapses; NBSP remains inside an indivisible word.
     * Pass each hard-break-delimited segment separately.
     */
    fun prepare(text: String, measure: (String) -> Double): Prepared {
        val cache = HashMap<String, Double>()
        val widthOf = { piece: String -> cache.getOrPut(piece) { measured(measure(piece)) } }
        return prepare(text, widthOf(" "), { start, end -> widthOf(text.substring(start, end)) }, measureGaps = false)
    }

    /** [prepare] over source ranges, for an adapter that measures styled text. Each word,
     * punctuation edge, and hyphen fragment is measured by its range in [text]. With
     * [measureGaps], each whitespace run between words keeps its measured width. */
    fun prepare(text: String, space: Double, measure: (start: Int, end: Int) -> Double, measureGaps: Boolean): Prepared {
        if (!space.isFinite() || space < 0) throw IllegalArgumentException("Invalid measured word width")
        if (space <= 0) throw IllegalArgumentException("Space width must be positive")
        val ranges = wordRanges(text)
        val n = ranges.size / 2
        val words = List(n) { text.substring(ranges[2 * it], ranges[2 * it + 1]) }
        val widths = DoubleArray(n + 1)
        val characters = DoubleArray(n + 1)
        val endHangs = DoubleArray(n)
        val startHangs = DoubleArray(n)
        val gapWidths = DoubleArray(n)
        var uniform = true
        val graphemes = BreakIterator.getCharacterInstance()
        var hyphenation: Array<WordFragments?>? = null
        for (i in 0 until n) {
            val from = ranges[2 * i]
            val word = words[i]
            val widthOf = { start: Int, end: Int -> measured(measure(from + start, from + end)) }
            val w = widthOf(0, word.length)
            val punctuation = trailingPunctuation.find(word)
            if (punctuation != null) endHangs[i] = min(w, widthOf(punctuation.range.first, word.length))
            if (word.isNotEmpty() && word[0] in openingQuotes) startHangs[i] = min(w, widthOf(0, 1))
            widths[i + 1] = widths[i] + w
            characters[i + 1] = characters[i] + graphemeCount(graphemes, word)
            if (measureGaps && i > 0) {
                val gap = measured(measure(ranges[2 * i - 1], from))
                uniform = uniform && gap == space
                gapWidths[i] = gapWidths[i - 1] + gap
            }
            val explicit = explicitHyphens(word)
            if (explicit.isNotEmpty()) {
                if (hyphenation == null) hyphenation = arrayOfNulls(n)
                hyphenation[i] = fragments(word, w, intArrayOf(0, *explicit, word.length), explicit.toSet(), graphemes, widthOf)
            }
        }
        return Prepared(words, widths, characters, endHangs, startHangs, space, hyphenation = hyphenation?.toList(), gaps = if (uniform) null else gapWidths)
    }

    /** Attach font-aware edge measurements after preparing optional hyphenation.
     * The callback receives full words, continuation suffixes, and the generated '-'.
     * The numerical core never measures or rasterizes a font itself.
     */
    fun withOpticalMargins(p: Prepared, measure: (text: String, wordIndex: Int) -> Edges): Prepared {
        val startProtrusions = DoubleArray(p.words.size)
        val endProtrusions = DoubleArray(p.words.size)
        val edge = { text: String, index: Int ->
            val result = measure(text, index)
            if (!listOf(result.start, result.end).all { it.isFinite() && it >= 0 }) throw IllegalArgumentException("Optical margins must be finite and nonnegative")
            result
        }
        val hyphenation = p.words.mapIndexed { index, word ->
            val margins = edge(word, index)
            startProtrusions[index] = margins.start
            endProtrusions[index] = margins.end
            val fragments = p.hyphenation?.get(index) ?: return@mapIndexed null
            WordFragments(
                fragments.offsets, fragments.widths, fragments.hyphenWidths, fragments.characters,
                startProtrusions = DoubleArray(fragments.offsets.size) { i ->
                    val offset = fragments.offsets[i]
                    if (offset == word.length) 0.0 else edge(word.substring(offset), index).start
                },
                hyphenProtrusion = edge("-", index).end,
                explicit = fragments.explicit,
            )
        }
        return p.copy(startProtrusions = startProtrusions, endProtrusions = endProtrusions, hyphenation = hyphenation)
    }

    /** Exact optimum within the first viable pass: ordinary fitting, then relaxed
     * stretch scoring if necessary. Both passes use admissible overflow pruning. */
    fun solve(p: Prepared, measure: Double, policy: Options = defaults): Layout = solve(p, listOf(measure), policy)

    /** A single column width, or one width per line with the final entry repeating.
     * Use a list for a first-line indent (`[w - indent, w]`) or a drop cap. */
    fun solve(p: Prepared, measure: List<Double>, policy: Options = defaults): Layout {
        val o = policy
        val widths = validate(measure, o)
        if (p.hyphenation?.any { it != null } == true) return solveHyphenated(p, widths, o)
        val n = p.words.size
        val s = Shape(widths, o)
        val costs = DoubleArray((n + 1) * s.states) { Double.POSITIVE_INFINITY }
        val previous = IntArray(costs.size)
        // Keep reachable prefix boundaries in source order. Scanning backward
        // preserves tie-breaking while skipping prefixes this pass cannot compose.
        val reachable = IntArray(n + 1)
        costs[s.origin] = 0.0
        var candidates = 0
        val scratch = Line()
        var emergency = false
        // A longer candidate must not become narrower at maximum compression.
        // Exotic policies (e.g. tracking larger than glyph advances) use full search.
        val maxOpening = p.startProtrusions?.fold(0.0) { best, value -> max(best, value * o.protrusion) }
            ?: p.startHangs.fold(0.0) { best, value -> max(best, value * o.opening) }
        var monotone = p.space * (1 - o.shrink) >= o.tracking
        var i = 0
        while (i < n && monotone) {
            monotone = p.widths[i + 1] - p.widths[i] >= (p.characters[i + 1] - p.characters[i]) * o.tracking &&
                (p.gaps == null || i == 0 || p.gaps[i] - p.gaps[i - 1] - p.space * o.shrink >= o.tracking)
            i++
        }
        for (pass in 0 until 2) {
            emergency = pass == 1
            costs.fill(Double.POSITIVE_INFINITY)
            costs[s.origin] = 0.0
            var count = 1
            reachable[0] = 0
            for (end in 1..n) {
                for (index in count - 1 downTo 0) {
                    val start = reachable[index]
                    var line: Line? = null
                    for (l in 0 until s.lines) {
                        val base = start * s.states + l * s.fitnesses
                        if (!anyFinite(costs, base, s.fitnesses)) continue
                        line = fit(p, start, end, s.width(l), o, scratch, emergency)
                        candidates++
                        val target = end * s.states + s.next(l) * s.fitnesses + (if (s.fitnesses == 4) line.fitness else 0)
                        for (fitness in 0 until s.fitnesses) {
                            val source = base + fitness
                            val cost = costs[source] + line.cost + (if (start != 0 && abs(fitness - line.fitness) > 1) o.adjacentPenalty else 0.0)
                            if (cost < costs[target]) {
                                costs[target] = cost
                                previous[target] = source
                            }
                        }
                    }
                    if (line == null) continue
                    // Prefix costs are nonnegative. Allow the largest possible opening
                    // credit and the widest measure before ruling out all earlier starts.
                    val overflowBound = line.residual + (s.widest - line.width) + maxOpening - line.opening
                    // All earlier overflowing candidates are tight: compare only that state,
                    // never the best of other fitness classes that future lines may need.
                    var bestTight = Double.POSITIVE_INFINITY
                    for (l in 0 until s.lines) bestTight = min(bestTight, costs[end * s.states + l * s.fitnesses])
                    if (monotone && overflowBound < -0.01 && ((!emergency && o.mode == Mode.balanced && o.emergencyStretch > 0) || 1 + overflowCost(overflowBound, o.mode) >= bestTight)) break
                }
                if (anyFinite(costs, end * s.states, s.states)) reachable[count++] = end
            }
            if (anyFinite(costs, n * s.states, s.states)) break
        }
        val lines = mutableListOf<Line>()
        val terminal = cheapest(costs, n * s.states)
        var slot = terminal
        while (slot >= s.states) {
            val source = previous[slot]
            val start = source / s.states
            val end = slot / s.states
            val line = fit(p, start, end, s.width(source % s.states / s.fitnesses), o, scratch, emergency).copy()
            line.cost += if (start != 0 && abs(source % s.fitnesses - line.fitness) > 1) o.adjacentPenalty else 0.0
            lines += line
            slot = source
        }
        return Layout(lines.reversed(), costs[terminal], candidates)
    }

    fun lineText(p: Prepared, line: Line): String {
        val words = p.words.subList(line.start, line.end).toMutableList()
        if (words.isEmpty()) return ""
        line.endOffset?.let { words[words.lastIndex] = words.last().substring(0, it) }
        line.startOffset?.let { if (it != 0) words[0] = words[0].substring(it) }
        return words.joinToString(" ") + if (line.hyphenated) "-" else ""
    }

    private val trailingPunctuation = Regex("[.,;:!?…’”'\"]+$")
    private const val openingQuotes = "“‘\"'«‹"
    private val explicitHyphen = Regex("(?<=[\\p{L}\\p{N}])[-\\u2010](?=[\\p{L}\\p{N}])")

    /** Source ranges of [prepare]'s words, as start and end pairs, splitting where
     * `text.trim().split(/[ \t\r\n\f]+/)` does. */
    fun wordRanges(text: String): IntArray {
        var from = 0
        var to = text.length
        while (from < to && isTrimmed(text[from])) from++
        while (to > from && isTrimmed(text[to - 1])) to--
        val ranges = ArrayList<Int>()
        var index = from
        while (index < to) {
            while (index < to && isSeparator(text[index])) index++
            if (index == to) break
            val start = index
            while (index < to && !isSeparator(text[index])) index++
            ranges += start
            ranges += index
        }
        return ranges.toIntArray()
    }

    /** The whitespace that separates words; NBSP and other spaces stay inside a word. */
    fun isSeparator(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000C'

    /** JavaScript's `String.prototype.trim` set, which unlike Kotlin's includes NBSP. */
    private fun isTrimmed(c: Char) = c in "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF" || c in '\u2000'..'\u200A'

    private fun graphemeCount(iterator: BreakIterator, text: String): Int {
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    private fun measured(width: Double): Double {
        if (!width.isFinite() || width < 0) throw IllegalArgumentException("Invalid measured word width")
        return width
    }

    /** Offsets just after a hyphen joining two letters or digits; browsers and TeX
     * both allow a break there without adding a glyph. */
    private fun explicitHyphens(word: String): IntArray {
        if (word.contains('\u00A0')) return IntArray(0)
        return explicitHyphen.findAll(word).map { it.range.first + 1 }.toList().toIntArray()
    }

    private fun fragments(
        word: String,
        wordWidth: Double,
        offsets: IntArray,
        explicitAt: Set<Int>,
        graphemes: BreakIterator,
        widthOf: (start: Int, end: Int) -> Double,
    ): WordFragments {
        val n = offsets.size
        val widths = DoubleArray(n * n)
        val hyphenWidths = DoubleArray(n * n)
        val characters = DoubleArray(n * n)
        val explicit = BooleanArray(n) { offsets[it] in explicitAt }
        for (from in 0 until n - 1) for (to in from + 1 until n) {
            val cell = from * n + to
            widths[cell] = if (from == 0 && to == n - 1) wordWidth else widthOf(offsets[from], offsets[to])
            // A generated hyphen only arises from dictionary breaks, which this port does not add.
            if (to < n - 1) hyphenWidths[cell] = widths[cell]
            characters[cell] = graphemeCount(graphemes, word.substring(offsets[from], offsets[to])).toDouble()
        }
        return WordFragments(offsets, widths, hyphenWidths, characters, explicit = if (explicit.any { it }) explicit else null)
    }

    private fun validate(measure: List<Double>, o: Options): List<Double> {
        if (measure.isEmpty() || measure.any { !it.isFinite() || it <= 0 }) throw IllegalArgumentException("Measure must be positive")
        val numeric = listOf(
            o.stretch, o.shrink, o.tracking, o.compressionPenalty, o.emergencyStretch, o.hanging, o.opening, o.protrusion,
            o.adjacentPenalty, o.lastLine, o.widowPenalty, o.hyphenPenalty, o.consecutiveHyphenPenalty, o.finalHyphenPenalty,
            o.explicitHyphenPenalty,
        )
        if (numeric.any { !it.isFinite() || it < 0 }) throw IllegalArgumentException("Policy values must be finite and nonnegative")
        if (o.shrink > 1 || o.lastLine > 1 || o.hanging > 1 || o.opening > 1 || o.protrusion > 1) {
            throw IllegalArgumentException("Shrink, lastLine, hanging, opening, and protrusion must be at most 1")
        }
        return measure
    }

    private fun cube(value: Double) = value * value * value

    private class Fragment(
        val natural: Double,
        val chars: Double,
        val last: Boolean,
        val hanging: Double,
        val opening: Double,
        val continued: Boolean,
        val startProtrusion: Double?,
        val endProtrusion: Double?,
    )

    /** The natural width of the gaps between words [start] and [end]. */
    private fun Prepared.spaces(start: Int, end: Int): Double = gaps?.let { it[end - 1] - it[start] } ?: ((end - start - 1) * space)

    private fun fit(
        p: Prepared,
        start: Int,
        end: Int,
        width: Double,
        o: Options,
        out: Line = Line(),
        emergency: Boolean = false,
        fragment: Fragment? = null,
    ): Line {
        val gaps = end - start - 1
        val chars = fragment?.chars ?: (p.characters[end] - p.characters[start] + gaps)
        val natural = fragment?.natural ?: (p.widths[end] - p.widths[start] + p.spaces(start, end))
        val last = fragment?.last ?: (end == p.words.size)
        val hanging = max(
            (fragment?.hanging ?: p.endHangs[end - 1]) * o.hanging,
            (if (fragment != null) fragment.endProtrusion ?: 0.0 else p.endProtrusions?.get(end - 1) ?: 0.0) * o.protrusion,
        )
        val opticalStart = if (fragment != null) fragment.startProtrusion else p.startProtrusions?.get(start)
        val opening = if (opticalStart == null) (fragment?.opening ?: p.startHangs[start]) * o.opening else opticalStart * o.protrusion
        val target = width + hanging + opening
        val delta = if (last && natural <= target) 0.0 else target - natural
        val spaceBudget = gaps * p.space * (if (delta >= 0) o.stretch else o.shrink)
        val trackBudget = chars * o.tracking
        val capacity = spaceBudget + trackBudget
        val ratio = if (capacity != 0.0) min(1.0, abs(delta) / capacity) else 0.0
        val sign = sign(delta)
        val wordSpacing = if (gaps != 0) sign * ratio * spaceBudget / gaps else 0.0
        val tracking = sign * ratio * o.tracking
        val residual = delta - sign * ratio * capacity
        // Price preferred-limit strain, but distinguish a fillable loose line from
        // a real overflow. Otherwise a narrow column can choose protruding words
        // over a feasible line simply because the latter needs wider spaces.
        // Emergency credit must reflect the spaces that can carry it. A width-only
        // allowance makes two enormous gaps cheaper than a normally spaced paragraph
        // with a hyphen. A natural-space floor keeps indivisible lines finite too.
        val strain = if (emergency && delta > 0) {
            100 * cube(delta / max(p.space, capacity + min(width * o.emergencyStretch, gaps * p.space)))
        } else if (o.mode == Mode.balanced) {
            100 * cube(ratio + abs(residual) / max(max(capacity, gaps * p.space), p.space))
        } else {
            ratio * ratio * ratio * 100
        }
        val missing = max(0.0, o.lastLine * width - natural)
        // A one-word tail has little flexibility: missing half the target should not
        // cost less than a modestly tightened body line merely because the page is wide.
        val ending = if (last && (fragment?.continued ?: (start > 0))) {
            if (o.ending == Ending.fit) {
                o.widowPenalty / 300 * min(10000.0, 100 * cube(missing / max(p.space, gaps * p.space * o.stretch + trackBudget)))
            } else {
                o.widowPenalty * Math.pow(missing / width, 2.0)
            }
        } else {
            0.0
        }
        out.start = start
        out.end = end
        out.width = width
        out.natural = natural
        out.wordSpacing = wordSpacing
        out.tracking = tracking
        out.hanging = hanging
        out.opening = opening
        out.residual = residual
        out.relaxed = false
        out.last = last
        val unbounded = if (capacity != 0.0) delta / capacity else if (delta == 0.0) 0.0 else sign * Double.POSITIVE_INFINITY
        out.fitness = if (unbounded < -0.5) 0 else if (unbounded <= 0.5) 1 else if (unbounded <= 1) 2 else 3
        finish(out, p, o)
        val remaining = out.residual
        val failure = if (abs(remaining) > 0.01) overflowCost(remaining, if (remaining < 0) o.mode else Mode.strict) else 0.0
        out.cost = 1 + strain * (if (delta < 0) o.compressionPenalty else 1.0) + failure + ending
        if (o.mode == Mode.balanced && o.emergencyStretch > 0 && !emergency && (delta > capacity * cbrt(2.0) + 0.01 || abs(remaining) > 0.01)) {
            out.cost = Double.POSITIVE_INFINITY
        }
        return out
    }

    private fun overflowCost(residual: Double, mode: Mode) =
        if (mode == Mode.balanced) 1e6 + residual * residual * 1e4 else 1e4 + residual * residual * 100

    /** Finish each candidate before scoring its actual residual.
     * Tracking remains bounded. Spaces can expand to complete a line, or contract
     * only within the configured shrink limit.
     * Single tokens and genuinely unfit material retain their reported residual.
     */
    private fun finish(line: Line, p: Prepared, o: Options): Line {
        val gaps = line.end - line.start - 1
        if (o.mode == Mode.strict || gaps == 0 || line.residual == 0.0) return line
        val spacing = max(-p.space * o.shrink, line.wordSpacing + line.residual / gaps)
        val adjustment = spacing - line.wordSpacing
        line.relaxed = abs(adjustment) > 1e-9
        line.residual -= adjustment * gaps
        if (abs(line.residual) < 1e-9) line.residual = 0.0
        line.wordSpacing = spacing
        return line
    }

    /** Dynamic-programming state per break: which measure the next line takes
     * (line index, clamped once widths repeat) × the finished line's fitness class. */
    private class Shape(private val widths: List<Double>, o: Options) {
        val fitnesses = if (o.adjacentPenalty != 0.0) 4 else 1
        val lines = widths.size
        val states = fitnesses * lines
        val origin = if (fitnesses == 4) 1 else 0
        val widest = widths.max()

        fun width(l: Int) = widths[l]

        fun next(l: Int) = min(l + 1, lines - 1)
    }

    private fun anyFinite(costs: DoubleArray, from: Int, count: Int): Boolean {
        for (i in from until from + count) if (costs[i] != Double.POSITIVE_INFINITY) return true
        return false
    }

    private fun cheapest(costs: DoubleArray, from: Int): Int {
        var best = from
        for (i in from + 1 until costs.size) if (costs[i] < costs[best]) best = i
        return best
    }

    private class Node(val word: Int, val part: Int, val offset: Int, val explicit: Boolean)

    private class Piece(val width: Double, val chars: Double)

    /** A break node also records whether the preceding line hyphenated. This makes
     * adjacency penalties part of the optimum rather than a post-layout repair.
     */
    private fun solveHyphenated(p: Prepared, widths: List<Double>, o: Options): Layout {
        val hyphenation = p.hyphenation!!
        val nodes = mutableListOf(Node(0, 0, 0, false))
        p.words.indices.forEach { word ->
            val prepared = hyphenation[word]
            if (prepared != null) {
                for (index in 1 until prepared.offsets.size - 1) {
                    nodes += Node(word, index, prepared.offsets[index], prepared.explicit?.get(index) ?: false)
                }
            }
            nodes += Node(word + 1, 0, 0, false)
        }
        val s = Shape(widths, o)
        val costs = DoubleArray(nodes.size * s.states)
        val previous = IntArray(costs.size)
        var candidates = 0
        var emergency = false
        val scratch = Line()
        val fragment = { word: Int, from: Int, to: Int?, hyphen: Boolean ->
            val prepared = hyphenation[word]
            if (prepared == null) {
                Piece(p.widths[word + 1] - p.widths[word], p.characters[word + 1] - p.characters[word])
            } else {
                val cell = from * prepared.offsets.size + (to ?: (prepared.offsets.size - 1))
                val generated = hyphen && prepared.explicit?.get(to!!) != true
                Piece(if (hyphen) prepared.hyphenWidths[cell] else prepared.widths[cell], prepared.characters[cell] + if (generated) 1 else 0)
            }
        }
        val candidate = { start: Int, end: Int, width: Double ->
            val a = nodes[start]
            val b = nodes[end]
            val hyphen = b.offset > 0
            val generated = hyphen && !b.explicit
            val lastWord = if (hyphen) b.word else b.word - 1
            val natural: Double
            val chars: Double
            if (a.word == lastWord) {
                val part = fragment(a.word, a.part, if (hyphen) b.part else null, hyphen)
                natural = part.width
                chars = part.chars
            } else {
                val first = fragment(a.word, a.part, null, false)
                val last = fragment(lastWord, 0, if (hyphen) b.part else null, hyphen)
                val gaps = lastWord - a.word
                natural = first.width + p.widths[lastWord] - p.widths[a.word + 1] + last.width + p.spaces(a.word, lastWord + 1)
                chars = first.chars + p.characters[lastWord] - p.characters[a.word + 1] + last.chars + gaps
            }
            val line = fit(
                p, a.word, lastWord + 1, width, o, scratch, emergency,
                Fragment(
                    natural, chars, last = end == nodes.size - 1, continued = start > 0,
                    hanging = if (hyphen) 0.0 else p.endHangs[lastWord],
                    opening = if (a.offset != 0) 0.0 else p.startHangs[a.word],
                    startProtrusion = if (a.offset != 0) hyphenation[a.word]?.startProtrusions?.get(a.part) else p.startProtrusions?.get(a.word),
                    endProtrusion = if (hyphen) hyphenation[lastWord]?.hyphenProtrusion else p.endProtrusions?.get(lastWord),
                ),
            )
            line.startOffset = if (a.offset != 0) a.offset else null
            line.endOffset = if (b.offset != 0) b.offset else null
            line.hyphenated = generated
            val afterGenerated = a.offset > 0 && !a.explicit
            line.cost += if (generated) {
                o.hyphenPenalty + if (afterGenerated) o.consecutiveHyphenPenalty else 0.0
            } else if (hyphen) {
                o.explicitHyphenPenalty
            } else if (line.last && afterGenerated) {
                o.finalHyphenPenalty
            } else {
                0.0
            }
            line
        }
        for (pass in 0 until 2) {
            emergency = pass == 1
            costs.fill(Double.POSITIVE_INFINITY)
            costs[s.origin] = 0.0
            for (end in 1 until nodes.size) for (start in end - 1 downTo 0) {
                for (l in 0 until s.lines) {
                    val base = start * s.states + l * s.fitnesses
                    if (!anyFinite(costs, base, s.fitnesses)) continue
                    val line = candidate(start, end, s.width(l))
                    candidates++
                    val target = end * s.states + s.next(l) * s.fitnesses + (if (s.fitnesses == 4) line.fitness else 0)
                    for (fitness in 0 until s.fitnesses) {
                        val source = base + fitness
                        val cost = costs[source] + line.cost + (if (start != 0 && abs(fitness - line.fitness) > 1) o.adjacentPenalty else 0.0)
                        if (cost < costs[target]) {
                            costs[target] = cost
                            previous[target] = source
                        }
                    }
                }
            }
            if (anyFinite(costs, (nodes.size - 1) * s.states, s.states)) break
        }
        val lines = mutableListOf<Line>()
        val terminal = cheapest(costs, (nodes.size - 1) * s.states)
        var slot = terminal
        while (slot >= s.states) {
            val source = previous[slot]
            val start = source / s.states
            val end = slot / s.states
            val line = candidate(start, end, s.width(source % s.states / s.fitnesses)).copy()
            line.cost += if (start != 0 && abs(source % s.fitnesses - line.fitness) > 1) o.adjacentPenalty else 0.0
            lines += line
            slot = source
        }
        return Layout(lines.reversed(), costs[terminal], candidates)
    }
}
