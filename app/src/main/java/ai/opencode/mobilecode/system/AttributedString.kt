package ai.opencode.mobilecode.system

import kotlinx.serialization.Serializable
import org.commonmark.ext.autolink.AutolinkType
import org.commonmark.ext.autolink.internal.AutolinkPostProcessor
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.internal.InlineParserImpl
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.InlineParserContext
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.commonmark.parser.SourceLines

@Serializable
data class AttributedString(
    val characters: String,
    val runs: List<Run> = emptyList(),
) {
    @Serializable
    data class Run(
        val start: Int,
        val end: Int,
        val foregroundColor: Foreground? = null,
        val link: URL? = null,
        val inlinePresentationIntent: Set<Intent> = emptySet(),
        val codeBackground: Boolean = false,
    )

    @Serializable
    enum class Intent { code, stronglyEmphasized, emphasized, strikethrough }

    @Serializable
    sealed class Foreground {
        @Serializable data object primary : Foreground()
        @Serializable data object secondary : Foreground()
        @Serializable data object link : Foreground()
        @Serializable data object clear : Foreground()
        @Serializable data class syntax(val role: SyntaxRole) : Foreground()
    }

    @Serializable
    enum class SyntaxRole { keyword, string, comment, type, number }

    class MarkdownParsingOptions(val interpretedSyntax: InterpretedSyntax) {
        enum class InterpretedSyntax { full, inlineOnly, inlineOnlyPreservingWhitespace }
    }

    fun slice(start: Int, end: Int): String = characters.substring(start, end.coerceAtMost(characters.length))

    companion object {
        operator fun invoke(markdown: String, options: MarkdownParsingOptions): AttributedString {
            if (options.interpretedSyntax != MarkdownParsingOptions.InterpretedSyntax.inlineOnlyPreservingWhitespace) {
                throw IllegalArgumentException(options.interpretedSyntax.name)
            }
            return InlineMarkdown.parse(markdown)
        }
    }
}

private object InlineMarkdown {
    private val context: InlineParserContext = run {
        var captured: InlineParserContext? = null
        Parser.builder()
            .extensions(listOf(StrikethroughExtension.create()))
            .inlineParserFactory { inlineContext ->
                captured = inlineContext
                InlineParserImpl(inlineContext)
            }
            .build()
            .parse("")
        captured ?: error("inline parser context")
    }

    fun parse(markdown: String): AttributedString {
        val sentinel = sentinel(markdown)
        val block = Paragraph()
        InlineParserImpl(context).parse(sourceLines(markdown, sentinel), block)
        AutolinkPostProcessor(setOf(AutolinkType.URL, AutolinkType.EMAIL, AutolinkType.WWW)).process(block)
        val out = Runs(sentinel)
        walk(block, emptySet(), null, false, out)
        return out.build()
    }

    private fun sentinel(text: String): Char {
        var code = 0xE000
        while (text.indexOf(code.toChar()) >= 0) code++
        return code.toChar()
    }

    // The document parser drops blank lines and leading spaces. Feed the inline
    // parser the raw lines so whitespace survives the way Foundation keeps it.
    private fun sourceLines(text: String, sentinel: Char): SourceLines {
        val normalized = text.replace("\u0000", "\uFFFD").replace("\r\n", "\n").replace("\r", "\n")
        val lines = SourceLines()
        var start = 0
        var index = 0
        while (true) {
            if (index == normalized.length || normalized[index] == '\n') {
                lines.addLine(SourceLine.of(protect(normalized.substring(start, index), sentinel), null))
                if (index == normalized.length) break
                start = index + 1
            }
            index++
        }
        return lines
    }

    // Two trailing spaces are a hard break in Commonmark and kept characters in
    // Foundation. A sentinel stops the trim. A trailing backslash stays a break.
    private fun protect(line: String, sentinel: Char): String {
        if (line.isEmpty() || line.last() == '\\') return line
        if (line.last() == ' ' || line.last() == '\t') return line + sentinel
        return line
    }

    private fun walk(node: Node, intents: Set<AttributedString.Intent>, link: URL?, flat: Boolean, out: Runs) {
        when (node) {
            is Text -> out.emit(node.literal, intents, link)
            is Code -> out.emit(node.literal, if (flat) intents else intents + AttributedString.Intent.code, link)
            is SoftLineBreak, is HardLineBreak -> out.emit("\n", intents, link)
            is HtmlInline -> out.emit(node.literal, intents, link)
            is Link -> {
                val destination = node.destination
                if (destination == null) {
                    walkChildren(node, intents, link, flat, out)
                } else {
                    var child = node.firstChild
                    while (child != null) {
                        walk(child, intents, URL(destination), true, out)
                        child = child.next
                    }
                }
            }
            is Image -> {
                var child = node.firstChild
                while (child != null) {
                    walk(child, intents, null, true, out)
                    child = child.next
                }
            }
            is Emphasis -> walkChildren(node, if (flat) intents else intents + AttributedString.Intent.emphasized, link, flat, out)
            is StrongEmphasis -> walkChildren(node, if (flat) intents else intents + AttributedString.Intent.stronglyEmphasized, link, flat, out)
            is Strikethrough -> walkChildren(node, if (flat) intents else intents + AttributedString.Intent.strikethrough, link, flat, out)
            else -> walkChildren(node, intents, link, flat, out)
        }
    }

    private fun walkChildren(node: Node, intents: Set<AttributedString.Intent>, link: URL?, flat: Boolean, out: Runs) {
        var child = node.firstChild
        while (child != null) {
            walk(child, intents, link, flat, out)
            child = child.next
        }
    }

    private class Runs(private val sentinel: Char) {
        private val characters = StringBuilder()
        private val runs = mutableListOf<AttributedString.Run>()

        fun emit(text: String, intents: Set<AttributedString.Intent>, link: URL?) {
            val cleaned = if (text.indexOf(sentinel) < 0) text else text.replace(sentinel.toString(), "")
            if (cleaned.isEmpty()) return
            val start = characters.length
            characters.append(cleaned)
            val end = characters.length
            val previous = runs.lastOrNull()
            if (previous != null && previous.end == start && previous.inlinePresentationIntent == intents && previous.link == link && previous.foregroundColor == null) {
                runs[runs.lastIndex] = previous.copy(end = end)
                return
            }
            runs += AttributedString.Run(start, end, link = link, inlinePresentationIntent = intents)
        }

        fun build() = AttributedString(characters.toString(), runs.toList())
    }
}
