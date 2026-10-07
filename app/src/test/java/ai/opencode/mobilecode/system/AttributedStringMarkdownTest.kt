package ai.opencode.mobilecode.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributedStringMarkdownTest {
    private val options = AttributedString.MarkdownParsingOptions(
        interpretedSyntax = AttributedString.MarkdownParsingOptions.InterpretedSyntax.inlineOnlyPreservingWhitespace,
    )

    @Test
    fun stripsStrongEmphasisAndKeepsTheRun() {
        val parsed = parse("a **bold** b")
        assertEquals("a bold b", parsed.characters)
        val run = parsed.runs.single { AttributedString.Intent.stronglyEmphasized in it.inlinePresentationIntent }
        assertEquals(2, run.start)
        assertEquals(6, run.end)
        assertEquals("bold", parsed.characters.substring(run.start, run.end))
    }

    @Test
    fun stripsEmphasisAndKeepsTheRun() {
        val parsed = parse("a *em* b")
        assertEquals("a em b", parsed.characters)
        val run = parsed.runs.single { AttributedString.Intent.emphasized in it.inlinePresentationIntent }
        assertEquals(2, run.start)
        assertEquals(4, run.end)
    }

    @Test
    fun stripsStrikethroughAndKeepsTheRun() {
        val parsed = parse("a ~~gone~~ b")
        assertEquals("a gone b", parsed.characters)
        val run = parsed.runs.single { AttributedString.Intent.strikethrough in it.inlinePresentationIntent }
        assertEquals(2, run.start)
        assertEquals(6, run.end)
    }

    @Test
    fun codeSpanKeepsAsterisks() {
        val parsed = parse("a `*bold*` b")
        assertEquals("a *bold* b", parsed.characters)
        val run = parsed.runs.single { AttributedString.Intent.code in it.inlinePresentationIntent }
        assertEquals(2, run.start)
        assertEquals(8, run.end)
        assertEquals("*bold*", parsed.characters.substring(run.start, run.end))
        assertTrue(AttributedString.Intent.stronglyEmphasized !in run.inlinePresentationIntent)
    }

    @Test
    fun nestedEmphasisInsideALinkIsOneLinkRun() {
        val parsed = parse("[*em* and **strong**](https://example.com/a)")
        assertEquals("em and strong", parsed.characters)
        assertEquals(1, parsed.runs.size)
        val run = parsed.runs.single()
        assertEquals(0, run.start)
        assertEquals(13, run.end)
        assertEquals("https://example.com/a", run.link?.absoluteString)
        assertTrue(run.inlinePresentationIntent.isEmpty())
    }

    @Test
    fun emphasisWrappingALinkStaysOnTheLink() {
        val parsed = parse("**[docs](https://example.com)**")
        assertEquals("docs", parsed.characters)
        val run = parsed.runs.single()
        assertEquals(0, run.start)
        assertEquals(4, run.end)
        assertEquals("https://example.com", run.link?.absoluteString)
        assertTrue(AttributedString.Intent.stronglyEmphasized in run.inlinePresentationIntent)
    }

    @Test
    fun autolinksAngleBracketsAndBareUrls() {
        val angled = parse("go <https://example.com/a>")
        assertEquals("go https://example.com/a", angled.characters)
        assertEquals("https://example.com/a", angled.runs.single { it.link != null }.link?.absoluteString)
        val bare = parse("go https://example.com/a now")
        assertEquals("go https://example.com/a now", bare.characters)
        val link = bare.runs.single { it.link != null }
        assertEquals(3, link.start)
        assertEquals(24, link.end)
        assertEquals("https://example.com/a", link.link?.absoluteString)
    }

    @Test
    fun decodesEscapesAndEntities() {
        assertEquals("Escaped *text*", parse("Escaped \\*text\\*").characters)
        assertEquals("A & B <tag>", parse("A &amp; B &lt;tag&gt;").characters)
        assertTrue(parse("Escaped \\*text\\*").runs.none { it.inlinePresentationIntent.isNotEmpty() })
    }

    @Test
    fun keepsWhitespaceAndDoesNotInterpretBlocks() {
        assertEquals("a\n\n\nb", parse("a\n\n\nb").characters)
        assertEquals("  hello", parse("  hello").characters)
        assertEquals("hello\n", parse("hello\n").characters)
        assertEquals("a  \nb", parse("a  \nb").characters)
        assertEquals("# Heading", parse("# Heading").characters)
        assertEquals("- item", parse("- item").characters)
        assertEquals("> quote", parse("> quote").characters)
        assertEquals("---", parse("---").characters)
        assertNull(parse("# Heading").runs.singleOrNull { it.inlinePresentationIntent.isNotEmpty() })
    }

    @Test
    fun unfinishedMarkersAndInWordUnderscoresStayLiteral() {
        assertEquals("- _Unfinished", parse("- _Unfinished").characters)
        assertEquals("Some **bo", parse("Some **bo").characters)
        assertEquals("foo_bar_baz", parse("foo_bar_baz").characters)
        val stars = parse("2*3*4")
        assertEquals("234", stars.characters)
        val emphasized = stars.runs.single { AttributedString.Intent.emphasized in it.inlinePresentationIntent }
        assertEquals(1, emphasized.start)
        assertEquals(2, emphasized.end)
    }

    private fun parse(markdown: String) = AttributedString(markdown = markdown, options = options)
}
