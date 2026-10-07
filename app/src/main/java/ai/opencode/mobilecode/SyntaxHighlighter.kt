@file:PortedFrom("OpenCode/SyntaxHighlighter.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.AttributedString
import java.text.BreakIterator
import java.util.Locale

object SyntaxHighlighter {
    fun highlight(source: String, language: String?): AttributedString {
        if (source.isEmpty()) return AttributedString("")
        val cached = cache(source, language)
        if (cached != null) return cached
        val highlighted = tokenize(source, Language(language))
        store(source, language, highlighted)
        return highlighted
    }

    private fun tokenize(source: String, language: Language): AttributedString {
        val ends = graphemeEnds(source)
        val runs = ArrayList<AttributedString.Run>()
        var cursor = 0
        while (cursor < ends.size) {
            val start = startOf(ends, cursor)
            val character = source.substring(start, ends[cursor])
            val next = graphemeAt(source, ends, cursor + 1)
            if (character == "/" && language.slashComments && next == "/") {
                val line = lineEnd(source, start)
                runs += colored(start, line, AttributedString.SyntaxRole.comment)
                cursor = cursorAt(ends, line)
                continue
            }
            if (character == "#" && language.hashComments) {
                val line = lineEnd(source, start)
                runs += colored(start, line, AttributedString.SyntaxRole.comment)
                cursor = cursorAt(ends, line)
                continue
            }
            if (character == "/" && language.slashComments && next == "*") {
                var scan = cursor + 2
                while (scan < ends.size) {
                    val current = graphemeAt(source, ends, scan)
                    if (current == "*" && graphemeAt(source, ends, scan + 1) == "/") {
                        scan += 2
                        break
                    }
                    scan += 1
                }
                runs += colored(start, endOf(ends, scan, source.length), AttributedString.SyntaxRole.comment)
                cursor = scan
                continue
            }
            if (character == "\"" || (character == "'" && language.singleQuoteStrings)) {
                val quote = character
                var scan = cursor + 1
                while (scan < ends.size) {
                    val current = graphemeAt(source, ends, scan)
                    if (current == quote || current == "\n") break
                    if (current == "\\" && scan + 1 < ends.size) scan += 1
                    scan += 1
                }
                if (scan < ends.size && graphemeAt(source, ends, scan) == quote) scan += 1
                runs += colored(start, endOf(ends, scan, source.length), AttributedString.SyntaxRole.string)
                cursor = scan
                continue
            }
            if (isDigit(character)) {
                var scan = cursor + 1
                while (scan < ends.size && isNumberContinue(graphemeAt(source, ends, scan))) scan += 1
                runs += colored(start, ends[scan - 1], AttributedString.SyntaxRole.number)
                cursor = scan
                continue
            }
            if (isIdentStart(character)) {
                var scan = cursor + 1
                while (scan < ends.size && isIdentContinue(graphemeAt(source, ends, scan))) scan += 1
                val word = source.substring(start, ends[scan - 1])
                if (word in language.keywords) {
                    runs += colored(start, ends[scan - 1], AttributedString.SyntaxRole.keyword)
                } else if (Character.isUpperCase(word.codePointAt(0))) {
                    runs += colored(start, ends[scan - 1], AttributedString.SyntaxRole.type)
                }
                cursor = scan
                continue
            }
            cursor += 1
        }
        return AttributedString(source, runs)
    }

    private fun colored(start: Int, end: Int, role: AttributedString.SyntaxRole) =
        AttributedString.Run(start, end, AttributedString.Foreground.syntax(role))

    private fun graphemeEnds(text: String): IntArray {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val ends = ArrayList<Int>()
        var index = iterator.next()
        while (index != BreakIterator.DONE) {
            ends += index
            index = iterator.next()
        }
        if (ends.isEmpty() || ends.last() != text.length) ends += text.length
        return ends.toIntArray()
    }

    private fun startOf(ends: IntArray, cursor: Int) = if (cursor == 0) 0 else ends[cursor - 1]

    private fun endOf(ends: IntArray, cursor: Int, length: Int) = if (cursor >= ends.size) length else ends[cursor - 1]

    private fun cursorAt(ends: IntArray, index: Int): Int {
        if (index <= 0) return 0
        val previous = ends.indexOf(index)
        return if (previous < 0) ends.size else previous + 1
    }

    private fun graphemeAt(code: String, ends: IntArray, cursor: Int): String? {
        if (cursor < 0 || cursor >= ends.size) return null
        return code.substring(startOf(ends, cursor), ends[cursor])
    }

    private fun lineEnd(code: String, from: Int): Int {
        val newline = code.indexOf('\n', from)
        return if (newline < 0) code.length else newline
    }

    private fun isDigit(grapheme: String): Boolean {
        val point = grapheme.codePointAt(0)
        return Character.isDigit(point) && grapheme.length == Character.charCount(point)
    }

    private fun isNumberContinue(grapheme: String?): Boolean {
        if (grapheme == null || grapheme.length != 1) return false
        val character = grapheme[0]
        return character.isDigit() || character in 'a'..'f' || character in 'A'..'F' || character in "xXoObB._"
    }

    private fun isIdentStart(grapheme: String): Boolean =
        grapheme == "_" || Character.isLetter(grapheme.codePointAt(0))

    private fun isIdentContinue(grapheme: String?): Boolean {
        if (grapheme == null) return false
        if (grapheme == "_") return true
        val point = grapheme.codePointAt(0)
        return Character.isLetter(point) || Character.isDigit(point)
    }

    private val cache = object : LinkedHashMap<String, AttributedString>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AttributedString>?) = size > 64
    }

    private fun cacheKey(source: String, language: String?) = "${language ?: ""}\u0000$source"

    private fun cache(source: String, language: String?): AttributedString? = synchronized(cache) {
        cache[cacheKey(source, language)]
    }

    private fun store(source: String, language: String?, value: AttributedString) {
        synchronized(cache) { cache[cacheKey(source, language)] = value }
    }

    private class Language(hint: String?) {
        val keywords: Set<String>
        val slashComments: Boolean
        val hashComments: Boolean
        val singleQuoteStrings: Boolean

        init {
            when (hint?.lowercase()) {
                "swift" -> {
                    keywords = swiftKeywords
                    slashComments = true
                    hashComments = false
                    singleQuoteStrings = false
                }
                "js", "jsx", "ts", "tsx", "javascript", "typescript" -> {
                    keywords = jsKeywords
                    slashComments = true
                    hashComments = false
                    singleQuoteStrings = true
                }
                "py", "python" -> {
                    keywords = pythonKeywords
                    slashComments = false
                    hashComments = true
                    singleQuoteStrings = true
                }
                "sh", "bash", "zsh", "shell", "yaml", "yml", "toml" -> {
                    keywords = shellKeywords
                    slashComments = false
                    hashComments = true
                    singleQuoteStrings = true
                }
                else -> {
                    keywords = allKeywords
                    slashComments = true
                    hashComments = true
                    singleQuoteStrings = true
                }
            }
        }
    }

    private val swiftKeywords = setOf(
        "func", "let", "var", "if", "else", "guard", "return", "for", "while", "switch", "case",
        "break", "continue", "struct", "class", "enum", "protocol", "extension", "import", "init",
        "self", "static", "private", "public", "internal", "final", "throws", "async", "await",
        "try", "catch", "do", "defer", "in", "where", "nil", "true", "false", "some", "any",
        "typealias", "inout", "mutating", "override", "default",
    )

    private val jsKeywords = setOf(
        "function", "const", "let", "var", "if", "else", "return", "for", "while", "switch",
        "case", "break", "continue", "class", "extends", "import", "export", "from", "default",
        "new", "this", "static", "async", "await", "try", "catch", "finally", "throw", "typeof",
        "instanceof", "in", "of", "null", "undefined", "true", "false", "interface", "type",
        "enum", "implements", "readonly", "yield",
    )

    private val pythonKeywords = setOf(
        "def", "class", "if", "elif", "else", "return", "for", "while", "break", "continue",
        "import", "from", "as", "with", "try", "except", "finally", "raise", "lambda", "pass",
        "yield", "global", "nonlocal", "in", "is", "not", "and", "or", "None", "True", "False",
        "async", "await", "self",
    )

    private val allKeywords = swiftKeywords + jsKeywords + pythonKeywords

    private val shellKeywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "local", "export", "return", "echo", "exit", "set", "source",
    )
}
