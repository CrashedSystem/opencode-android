package ai.opencode.mobilecode

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kotlin writes `Action.delegate` and `Action.Delegate` to `Action$delegate.class` and
 * `Action$Delegate.class`. On a case-insensitive disk (the macOS default) one silently replaces
 * the other. Swift allows a case and a nested type to share a name, so a verbatim port can
 * produce such a pair. Declare the nested type one level up instead, as `SessionSheetFeature.Delegate`.
 */
class DeclarationNameCollisionTest {
    @Test
    fun noTwoClassFilesDifferOnlyInCase() {
        val binaryNames = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> declaredBinaryNames(file.readText()).map { it to file.path } }
            .toList()
        val collisions = binaryNames
            .groupBy({ it.first.lowercase() }, { it })
            .values
            .map { group -> group.distinctBy { it.first } }
            .filter { it.size > 1 }
            .map { group -> group.joinToString(" and ") { "${it.first} (${it.second})" } }
        assertEquals(emptyList<String>(), collisions)
    }

    private fun declaredBinaryNames(source: String): List<String> {
        val text = stripStringsAndComments(source)
        val packageName = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1).orEmpty()
        val declaration = Regex("""\b(?:class|interface|object)\s+`?([A-Za-z_][A-Za-z0-9_]*)""")
        val names = mutableListOf<String>()
        val scope = ArrayDeque<Pair<String, Int>>()
        var depth = 0
        var pending: String? = null
        var index = 0
        while (index < text.length) {
            val match = declaration.find(text, index)
            val nextBrace = text.indexOfAny(charArrayOf('{', '}', ';', '\n'), index).let { if (it < 0) text.length else it }
            if (match != null && match.range.first < nextBrace) {
                val name = match.groupValues[1]
                val path = (scope.map { it.first } + name).joinToString("$")
                names += "$packageName.$path"
                pending = name
                index = match.range.last + 1
                continue
            }
            if (nextBrace >= text.length) break
            when (text[nextBrace]) {
                '{' -> {
                    depth++
                    pending?.let { scope.addLast(it to depth) }
                    pending = null
                }
                '}' -> {
                    if (scope.lastOrNull()?.second == depth) scope.removeLast()
                    depth--
                }
                ';' -> pending = null
                '\n' -> if (pending != null && !text.substring(nextBrace).trimStart().let { it.startsWith("{") || it.startsWith(":") || it.startsWith("(") || it.startsWith(",") }) pending = null
            }
            index = nextBrace + 1
        }
        return names
    }

    private fun stripStringsAndComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) source.length else end + 3
                    out.append("\"\"")
                }
                source[i] == '"' -> {
                    i++
                    while (i < source.length && source[i] != '"') i += if (source[i] == '\\') 2 else 1
                    i++
                    out.append("\"\"")
                }
                source[i] == '\'' && i + 2 < source.length -> {
                    val end = source.indexOf('\'', i + 1 + if (source[i + 1] == '\\') 1 else 0)
                    i = if (end < 0) source.length else end + 1
                    out.append("' '")
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
