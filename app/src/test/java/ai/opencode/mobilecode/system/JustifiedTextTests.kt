package ai.opencode.mobilecode.system

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class JustifiedTextTests {
    @get:Rule
    val composeRule = createComposeRule()

    private val style = TextStyles.body.copy(lineHeight = 26.sp)
    private val fonts = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, background = Color.LightGray)
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)

    private val paragraphs = listOf(
        AnnotatedString("Each item below is one of the problems the move from rc.112 to rc.118 created, and how it was solved, starting with the ones users could notice."),
        buildAnnotatedString {
            withStyle(bold) { append("Why:") }
            append(" V1 permission rules are applied last-match-wins, so the order you write them in is what makes them work. Effect doesn't guarantee that it keeps object key order when decoding, so relying on it was a hidden bug waiting for an upgrade.")
        },
        buildAnnotatedString {
            withStyle(bold) { append("Why:") }
            append(" rc.118 builds schemas differently. Core and ")
            withStyle(code) { append("ai") }
            append(" each had their own \"does this tool take input?\" check, and one of them went wrong for transformed schemas. Providers would have been sent ")
            withStyle(code) { append("{anyOf: [object, array]}") }
            append(" instead of an empty object.")
        },
        AnnotatedString("“Punctuation is small,” the typographer said, “but it is not invisible.” Periods, commas, colons, quotes: each carries almost no ink, yet a browser sets every one of them flush, so the margin appears to dent inward wherever a line happens to end in one."),
        AnnotatedString("The first line of a verse that keeps on going for a while longer than the column\n  then an indented second line that continues with more words, to wrap at least once more."),
        AnnotatedString("A well-known state-of-the-art build tool, run on a long-running server, keeps a write-ahead log and a copy-on-write tree."),
    )

    @Test
    fun everyLineLandsWhereTheEngineSetIt() {
        var worst = 0.0
        var checked = 0
        val failures = mutableListOf<String>()
        for (density in listOf(1f, 2.75f)) {
            val widths = TextWidths(Justification.style(style), Density(density), fonts)
            for (dp in listOf(408, 343, 280, 220)) {
                val width = (dp * density).toInt()
                for (text in paragraphs) {
                    val plan = Justification.plan(text, width, widths)!!
                    assertEquals("the characters are unchanged", text.text, plan.text.text)
                    val laid = Paragraph(plan.text.text, Justification.style(style), Constraints(maxWidth = plan.layoutWidth), Density(density), fonts, plan.text.spanStyles)
                    val where = "\"${text.text.take(24)}\" at $width px (density $density)"
                    if (plan.lines.size != laid.lineCount) {
                        failures += "$where: ${laid.lineCount} lines, planned ${plan.lines.size}"
                        continue
                    }
                    plan.lines.forEachIndexed { index, line ->
                        if (line.start != laid.getLineStart(index) || line.end != laid.getLineEnd(index, visibleEnd = true)) {
                            failures += "$where line $index: ${laid.getLineStart(index)}-${laid.getLineEnd(index, true)}, planned ${line.start}-${line.end}"
                        }
                        val right = laid.getLineRight(index)
                        worst = maxOf(worst, abs(right - line.right))
                        checked++
                        val endsSegment = text.text.substring(line.end).substringBefore('\n').isBlank()
                        if (!endsSegment && !line.loose && right < width - 0.01) failures += "$where line $index ends at $right, short of the margin"
                        if (!endsSegment && plan.text.spanStyles.any { it.item.background.isSpecified && line.end in it.start until it.end }) {
                            failures += "$where line $index: the break space keeps a background"
                        }
                    }
                }
            }
        }
        println("justified-landing lines=$checked worstRightEdgeErrorPx=$worst")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("worst right edge error $worst px", worst < 0.01)
        assertEquals(265, checked)
    }

    @Test
    fun streamedWordsMoveOnlyTheLastTwoLinesAndStillLand() {
        val text = paragraphs[1]
        val widths = TextWidths(Justification.style(style), Density(2.75f), fonts)
        val ends = text.text.indices.filter { text.text[it] == ' ' } + text.length
        var previous: Justification.Plan? = null
        for (end in ends) {
            val plan = Justification.plan(text.subSequence(0, end), 770, widths, previous = previous)!!
            previous?.lines?.dropLast(2)?.forEachIndexed { index, line -> assertEquals("line $index at $end", line, plan.lines[index]) }
            val laid = Paragraph(plan.text.text, Justification.style(style), Constraints(maxWidth = plan.layoutWidth), Density(2.75f), fonts, plan.text.spanStyles)
            assertEquals(plan.lines.map { it.start to it.end }, (0 until laid.lineCount).map { laid.getLineStart(it) to laid.getLineEnd(it, visibleEnd = true) })
            previous = plan
        }
        assertEquals(text.length, previous!!.lines.last().end)
    }

    @Test
    fun readsAsItsSourceTextAndReportsItsOwnWidth() {
        val text = paragraphs[2]
        composeRule.setContent {
            SelectionContainer {
                JustifiedText(text, style, Color.Black, Modifier.width(280.dp).testTag("paragraph"))
            }
        }
        composeRule.onNodeWithTag("paragraph").assertWidthIsEqualTo(280.dp)
        composeRule.onNodeWithText(text.text).assertExists()
    }

    private val Color.isSpecified get() = this != Color.Unspecified
}
