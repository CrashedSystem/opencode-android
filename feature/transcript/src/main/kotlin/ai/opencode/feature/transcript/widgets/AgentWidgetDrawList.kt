package ai.opencode.feature.transcript.widgets

/**
 * A draw step's `{ commands }`, read straight from its JSON text into flat arrays (no JSON tree,
 * no object per command) with colours resolved against the theme, so a frame of 2,000 commands
 * decodes without garbage beyond its strings. The renderer reads the streams back in order.
 *
 * Per command: an op in [ints], its numbers in [floats], its texts in [strings]; a paint follows
 * a shape's numbers as `fill kind, fill colour | stop count, stroke kind, stroke colour, cap` in
 * [ints] and `width` (plus a gradient's geometry and stop offsets) in [floats], a gradient's stop
 * colours in [ints].
 */
class AgentWidgetDrawList {
    var ints = IntArray(1024)
        private set
    var floats = FloatArray(4096)
        private set
    val strings = ArrayList<String>()
    var intCount = 0
        private set
    var floatCount = 0
        private set
    var commandCount = 0
        private set
    /** More than [AgentWidgetDrawList.MAX_COMMANDS] came: the rest were dropped. */
    var truncated = false
        private set

    fun clear() {
        intCount = 0
        floatCount = 0
        commandCount = 0
        truncated = false
        strings.clear()
    }

    internal fun int(value: Int) {
        if (intCount == ints.size) ints = ints.copyOf(ints.size * 2)
        ints[intCount++] = value
    }

    internal fun setInt(at: Int, value: Int) {
        ints[at] = value
    }

    internal fun counted() {
        commandCount++
    }

    internal fun truncatedMark() {
        truncated = true
    }

    /** Drops a command that turned out unreadable. */
    internal fun rewind(ints: Int, floats: Int, strings: Int) {
        intCount = ints
        floatCount = floats
        while (this.strings.size > strings) this.strings.removeAt(this.strings.lastIndex)
    }

    internal fun float(value: Float) {
        if (floatCount == floats.size) floats = floats.copyOf(floats.size * 2)
        floats[floatCount++] = value
    }

    /** Theme colours a command may name instead of a value, in [Token] order, as ARGB. */
    class Palette(val tokens: IntArray)

    enum class Token(val wire: String) { Text("text"), Muted("muted"), Accent("accent"), Bg("bg"), Surface("surface"), Border("border"), Success("success"), Warning("warning"), Error("error") }

    companion object {
        const val MAX_COMMANDS = 2_000

        const val SAVE = 1
        const val RESTORE = 2
        const val TRANSLATE = 3
        const val ROTATE = 4
        const val SCALE = 5
        const val ALPHA = 6
        const val RECT = 7
        const val RRECT = 8
        const val CIRCLE = 9
        const val LINE = 10
        const val PATH = 11
        const val TEXT = 12
        const val IMAGE = 13

        const val FILL_NONE = 0
        const val FILL_COLOR = 1
        const val FILL_LINEAR = 2
        const val FILL_RADIAL = 3

        const val SEG_M = 1
        const val SEG_L = 2
        const val SEG_Q = 3
        const val SEG_C = 4
        const val SEG_Z = 5

        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2

        /** Reads [json] (`{ "commands": [...] }` or a bare array) into [into]; throws on malformed JSON. */
        fun decode(json: String, palette: Palette, into: AgentWidgetDrawList) {
            into.clear()
            DrawReader(json, palette, into).read()
        }

        /** `#rgb`, `#rrggbb`, `#rrggbbaa` or a theme token, as ARGB; null when it's neither. */
        fun color(text: String, palette: Palette): Int? {
            if (text.startsWith('#')) {
                val hex = text.substring(1)
                fun h(i: Int) = Character.digit(hex[i], 16)
                if (hex.any { Character.digit(it, 16) < 0 }) return null
                return when (hex.length) {
                    3 -> argb(255, h(0) * 17, h(1) * 17, h(2) * 17)
                    4 -> argb(h(3) * 17, h(0) * 17, h(1) * 17, h(2) * 17)
                    6 -> argb(255, h(0) * 16 + h(1), h(2) * 16 + h(3), h(4) * 16 + h(5))
                    8 -> argb(h(6) * 16 + h(7), h(0) * 16 + h(1), h(2) * 16 + h(3), h(4) * 16 + h(5))
                    else -> null
                }
            }
            val token = Token.entries.firstOrNull { it.wire == text } ?: return null
            return palette.tokens[token.ordinal]
        }

        private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** A small pull reader over JSON text, driven by the command grammar. */
private class DrawReader(private val s: String, private val palette: AgentWidgetDrawList.Palette, private val out: AgentWidgetDrawList) {
    private var i = 0
    private val buffer = StringBuilder()

    fun read() {
        skipSpace()
        if (peek() == '{') {
            i++
            var found = false
            while (true) {
                skipSpace()
                if (peek() == '}') { i++; break }
                val key = string()
                colon()
                if (key == "commands") { commands(); found = true } else skipValue()
                comma()
            }
            if (!found) return
        } else {
            commands()
        }
    }

    private fun commands() {
        skipSpace()
        if (peek() == 'n') { skipValue(); return }
        expect('[')
        while (true) {
            skipSpace()
            if (peek() == ']') { i++; return }
            if (out.commandCount >= AgentWidgetDrawList.MAX_COMMANDS) {
                out.truncatedMark()
                skipValue()
            } else {
                command()
            }
            comma()
        }
    }

    private fun command() {
        skipSpace()
        if (peek() != '[') { skipValue(); return }
        i++
        skipSpace()
        val op = string()
        val start = out.intCount
        val startFloats = out.floatCount
        val startStrings = out.strings.size
        try {
            when (op) {
                "save" -> out.int(AgentWidgetDrawList.SAVE)
                "restore" -> out.int(AgentWidgetDrawList.RESTORE)
                "translate" -> { out.int(AgentWidgetDrawList.TRANSLATE); numbers(2) }
                "rotate" -> { out.int(AgentWidgetDrawList.ROTATE); numbers(1) }
                "scale" -> { out.int(AgentWidgetDrawList.SCALE); numbers(2) }
                "alpha" -> { out.int(AgentWidgetDrawList.ALPHA); numbers(1) }
                "rect" -> { out.int(AgentWidgetDrawList.RECT); numbers(4); paint() }
                "rrect" -> { out.int(AgentWidgetDrawList.RRECT); numbers(5); paint() }
                "circle" -> { out.int(AgentWidgetDrawList.CIRCLE); numbers(3); paint() }
                "line" -> { out.int(AgentWidgetDrawList.LINE); numbers(4); paint(stroke = true) }
                "path" -> { out.int(AgentWidgetDrawList.PATH); segments(); paint() }
                "text" -> text()
                "image" -> {
                    out.int(AgentWidgetDrawList.IMAGE)
                    argument()
                    out.strings += string()
                    numbers(4)
                }
                else -> throw Skip()
            }
            out.counted()
        } catch (_: Skip) {
            out.rewind(start, startFloats, startStrings)
        }
        // Whatever is left of the command (extra arguments, or the rest of one skipped).
        while (true) {
            skipSpace()
            if (peek() == ']') { i++; return }
            if (peek() == ',') i++
            skipValue()
        }
    }

    private fun numbers(count: Int) {
        repeat(count) {
            argument()
            out.float(number())
        }
    }

    private fun argument() {
        skipSpace()
        if (peek() != ',') throw Skip()
        i++
        skipSpace()
        if (peek() == ']') throw Skip()
    }

    private fun segments() {
        argument()
        expect('[')
        val countAt = out.intCount
        out.int(0)
        var count = 0
        while (true) {
            skipSpace()
            if (peek() == ']') { i++; break }
            expect('[')
            skipSpace()
            val kind = when (string()) {
                "M" -> AgentWidgetDrawList.SEG_M
                "L" -> AgentWidgetDrawList.SEG_L
                "Q" -> AgentWidgetDrawList.SEG_Q
                "C" -> AgentWidgetDrawList.SEG_C
                "Z" -> AgentWidgetDrawList.SEG_Z
                else -> 0
            }
            val needs = when (kind) {
                AgentWidgetDrawList.SEG_M, AgentWidgetDrawList.SEG_L -> 2
                AgentWidgetDrawList.SEG_Q -> 4
                AgentWidgetDrawList.SEG_C -> 6
                else -> 0
            }
            if (kind != 0) {
                out.int(kind)
                numbers(needs)
                count++
            }
            while (true) {
                skipSpace()
                if (peek() == ']') { i++; break }
                if (peek() == ',') i++
                skipValue()
            }
            comma()
        }
        out.setInt(countAt, count)
    }

    /** Fill kind, fill colour | stop count, stroke kind, stroke colour, cap; then width. A line strokes by default. */
    private fun paint(stroke: Boolean = false) {
        skipSpace()
        var fillKind = AgentWidgetDrawList.FILL_NONE
        var fillColor = 0
        var strokeColor: Int? = null
        var width = 1f
        var cap = 0
        var gradient: FloatArray? = null
        var stopOffsets: FloatArray? = null
        var stopColors: IntArray? = null
        if (peek() == ',') {
            i++
            skipSpace()
            when (peek()) {
                '"' -> {
                    val color = AgentWidgetDrawList.color(string(), palette)
                    if (stroke) strokeColor = color else if (color != null) { fillKind = AgentWidgetDrawList.FILL_COLOR; fillColor = color }
                }
                '{' -> {
                    i++
                    while (true) {
                        skipSpace()
                        if (peek() == '}') { i++; break }
                        val key = string()
                        colon()
                        skipSpace()
                        when (key) {
                            "fill" -> if (peek() == '"') {
                                AgentWidgetDrawList.color(string(), palette)?.let { fillKind = AgentWidgetDrawList.FILL_COLOR; fillColor = it }
                            } else if (peek() == '{') {
                                i++
                                while (true) {
                                    skipSpace()
                                    if (peek() == '}') { i++; break }
                                    val gkey = string()
                                    colon()
                                    when (gkey) {
                                        "linear" -> { fillKind = AgentWidgetDrawList.FILL_LINEAR; gradient = floatList(4) }
                                        "radial" -> { fillKind = AgentWidgetDrawList.FILL_RADIAL; gradient = floatList(3) }
                                        "stops" -> {
                                            val offsets = ArrayList<Float>()
                                            val colors = ArrayList<Int>()
                                            skipSpace(); expect('[')
                                            while (true) {
                                                skipSpace()
                                                if (peek() == ']') { i++; break }
                                                expect('['); skipSpace()
                                                val offset = number()
                                                skipSpace(); expect(','); skipSpace()
                                                val color = AgentWidgetDrawList.color(string(), palette) ?: 0
                                                skipSpace(); expect(']')
                                                offsets += offset
                                                colors += color
                                                comma()
                                            }
                                            stopOffsets = offsets.toFloatArray()
                                            stopColors = colors.toIntArray()
                                        }
                                        else -> skipValue()
                                    }
                                    comma()
                                }
                            } else skipValue()
                            "stroke" -> strokeColor = if (peek() == '"') AgentWidgetDrawList.color(string(), palette) else { skipValue(); null }
                            "width" -> width = if (peek() == '-' || peek().isDigit()) number() else { skipValue(); 1f }
                            "cap" -> cap = if (peek() == '"' && string() == "round") 1 else 0
                            else -> skipValue()
                        }
                        comma()
                    }
                }
                else -> skipValue()
            }
        } else if (stroke) {
            strokeColor = palette.tokens[AgentWidgetDrawList.Token.Text.ordinal]
        } else {
            fillKind = AgentWidgetDrawList.FILL_COLOR
            fillColor = palette.tokens[AgentWidgetDrawList.Token.Text.ordinal]
        }
        val geometry = gradient
        val offsets = stopOffsets
        val colors = stopColors
        if ((fillKind == AgentWidgetDrawList.FILL_LINEAR || fillKind == AgentWidgetDrawList.FILL_RADIAL) &&
            (geometry == null || offsets == null || colors == null || offsets.size < 2)
        ) {
            fillKind = AgentWidgetDrawList.FILL_NONE
        }
        out.int(fillKind)
        out.int(if (fillKind == AgentWidgetDrawList.FILL_COLOR) fillColor else colors?.size ?: 0)
        out.int(if (strokeColor != null) 1 else 0)
        out.int(strokeColor ?: 0)
        out.int(cap)
        out.float(width)
        if (fillKind == AgentWidgetDrawList.FILL_LINEAR || fillKind == AgentWidgetDrawList.FILL_RADIAL) {
            for (v in geometry!!) out.float(v)
            for (v in offsets!!) out.float(v)
            for (c in colors!!) out.int(c)
        }
    }

    private fun text() {
        out.int(AgentWidgetDrawList.TEXT)
        numbers(2)
        argument()
        out.strings += if (peek() == '"') string() else { skipValue(); "" }
        var size = 13f
        var weight = 400
        var align = AgentWidgetDrawList.ALIGN_LEFT
        var color = palette.tokens[AgentWidgetDrawList.Token.Text.ordinal]
        var mono = 0
        skipSpace()
        if (peek() == ',') {
            i++
            skipSpace()
            if (peek() == '{') {
                i++
                while (true) {
                    skipSpace()
                    if (peek() == '}') { i++; break }
                    val key = string()
                    colon()
                    skipSpace()
                    when (key) {
                        "size" -> size = number()
                        "weight" -> weight = number().toInt()
                        "align" -> align = when (string()) {
                            "center" -> AgentWidgetDrawList.ALIGN_CENTER
                            "right" -> AgentWidgetDrawList.ALIGN_RIGHT
                            else -> AgentWidgetDrawList.ALIGN_LEFT
                        }
                        "color" -> color = (if (peek() == '"') AgentWidgetDrawList.color(string(), palette) else { skipValue(); null }) ?: color
                        "mono" -> mono = if (peek() == 't') { i += 4; 1 } else { skipValue(); 0 }
                        else -> skipValue()
                    }
                    comma()
                }
            } else skipValue()
        }
        out.float(size.coerceIn(1f, 200f))
        out.int(weight)
        out.int(align)
        out.int(color)
        out.int(mono)
    }

    private fun floatList(count: Int): FloatArray {
        val values = FloatArray(count)
        skipSpace()
        expect('[')
        var at = 0
        while (true) {
            skipSpace()
            if (peek() == ']') { i++; break }
            val v = number()
            if (at < count) values[at] = v
            at++
            comma()
        }
        return values
    }

    // --- JSON primitives ---

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    private fun skipSpace() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else return
        }
    }

    private fun expect(c: Char) {
        skipSpace()
        if (peek() != c) throw IllegalArgumentException("expected '$c' at $i")
        i++
    }

    private fun colon() = expect(':')

    private fun comma() {
        skipSpace()
        if (peek() == ',') i++
    }

    private fun number(): Float {
        skipSpace()
        val start = i
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit() || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++ else break
        }
        if (start == i) {
            // null (or anything else) reads as 0, as `NaN` would draw nothing.
            skipValue()
            return 0f
        }
        return parseFloat(start, i)
    }

    /** Integers and plain decimals without allocating; anything else goes through the JDK. */
    private fun parseFloat(start: Int, end: Int): Float {
        var at = start
        var negative = false
        if (s[at] == '-') { negative = true; at++ }
        var whole = 0L
        var digits = 0
        while (at < end && s[at].isDigit()) {
            whole = whole * 10 + (s[at] - '0')
            at++
            digits++
            if (digits > 15) return s.substring(start, end).toFloat()
        }
        var value = whole.toDouble()
        if (at < end && s[at] == '.') {
            at++
            var scale = 0.1
            while (at < end && s[at].isDigit()) {
                value += (s[at] - '0') * scale
                scale *= 0.1
                at++
            }
        }
        if (at < end) return s.substring(start, end).toFloat()
        return (if (negative) -value else value).toFloat()
    }

    private fun string(): String {
        skipSpace()
        if (peek() != '"') throw Skip()
        i++
        val start = i
        while (i < s.length && s[i] != '"' && s[i] != '\\') i++
        if (i < s.length && s[i] == '"') {
            val text = s.substring(start, i)
            i++
            return text
        }
        buffer.setLength(0)
        buffer.append(s, start, i)
        while (i < s.length) {
            val c = s[i++]
            when (c) {
                '"' -> return buffer.toString()
                '\\' -> {
                    val e = s[i++]
                    when (e) {
                        'n' -> buffer.append('\n')
                        't' -> buffer.append('\t')
                        'r' -> buffer.append('\r')
                        'b' -> buffer.append('\b')
                        'f' -> buffer.append('\u000C')
                        'u' -> { buffer.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> buffer.append(e)
                    }
                }
                else -> buffer.append(c)
            }
        }
        throw IllegalArgumentException("unterminated string")
    }

    private fun skipValue() {
        skipSpace()
        when (peek()) {
            '"' -> string()
            '[', '{' -> {
                var depth = 0
                while (i < s.length) {
                    when (s[i]) {
                        '"' -> { string(); continue }
                        '[', '{' -> depth++
                        ']', '}' -> { depth--; if (depth == 0) { i++; return } }
                    }
                    i++
                }
            }
            else -> while (i < s.length && s[i] != ',' && s[i] != ']' && s[i] != '}') i++
        }
    }

    private class Skip : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
}
