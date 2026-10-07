package ai.opencode.mobilecode.system

import ai.opencode.mobilecode.MarkdownStyle
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Justified text laid out by the device's own text stack. Android 15 and later draw letter
 * spacing differently at line edges, which Robolectric's SDK 33 cannot show.
 */
@RunWith(AndroidJUnit4::class)
class JustifiedTextOnDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val style = MarkdownStyle.bodyFont
    private val fonts = createFontFamilyResolver(InstrumentationRegistry.getInstrumentation().targetContext)
    private val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, background = Color.LightGray)
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)

    private val reply = buildAnnotatedString {
        append("I found the layout issue in ")
        withStyle(code) { append("SessionView.swift") }
        append(". The transcript and composer share the available height, so the keyboard is a useful stress test.")
    }

    private val paragraphs = listOf(
        reply,
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
    fun theTranscriptKeepsTheReportedReplyOnItsSolvedLines() {
        var laid: TextLayoutResult? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                JustifiedText(reply, style, Color.Black, Modifier.width(408.dp), onTextLayout = { laid = it })
            }
        }
        composeRule.waitForIdle()
        val layout = laid!!
        val lines = (0 until layout.lineCount).map { reply.text.substring(layout.getLineStart(it), layout.getLineEnd(it, visibleEnd = true)) }
        assertEquals(
            listOf(
                "I found the layout issue in SessionView.swift. The",
                "transcript and composer share the available height, so",
                "the keyboard is a useful stress test.",
            ),
            lines,
        )
    }

    @Test
    fun everySolvedLineLandsWhereTheEngineSetIt() {
        val failures = mutableListOf<String>()
        var worst = 0.0
        var checked = 0
        for (density in listOf(Density(1f), Density(2.75f), Density(3f), Density(3f, fontScale = 2f))) {
            val widths = TextWidths(Justification.style(style), density, fonts)
            for (dp in 180..420 step 6) {
                val width = (dp * density.density).toInt()
                for (text in paragraphs) {
                    val plan = Justification.plan(text, width, widths)!!
                    val laid = Paragraph(plan.text.text, Justification.style(style), Constraints(maxWidth = plan.layoutWidth), density, fonts, plan.text.spanStyles)
                    val laidLines = (0 until laid.lineCount).associateBy { laid.getLineStart(it) }
                    for (line in plan.lines) {
                        // A word wider than the column has no break to keep, so Android wraps it inside.
                        if (line.right > line.reach + 1) continue
                        val index = laidLines[line.start]
                        if (index == null || laid.getLineEnd(index, visibleEnd = true) != line.end) {
                            failures += "\"${text.text.substring(line.start, line.end)}\" at $width px, density ${density.density}, font scale ${density.fontScale}"
                            continue
                        }
                        worst = maxOf(worst, abs(laid.getLineRight(index) - line.right))
                        checked++
                    }
                }
            }
        }
        println("justified-landing-on-device lines=$checked rewrapped=${failures.size} worstRightEdgeErrorPx=$worst")
        assertTrue("Android re-wrapped ${failures.size} solved lines:\n" + failures.take(10).joinToString("\n"), failures.isEmpty())
        assertTrue("worst right edge error $worst px", worst < 0.01)
    }

    @Test
    fun streamedWordsLandWhereTheEngineSetThem() {
        val density = Density(3f)
        val widths = TextWidths(Justification.style(style), density, fonts)
        for (text in paragraphs) {
            val ends = text.text.indices.filter { text.text[it] == ' ' } + text.length
            var previous: Justification.Plan? = null
            for (end in ends) {
                val plan = Justification.plan(text.subSequence(0, end), 1224, widths, previous = previous)!!
                val laid = Paragraph(plan.text.text, Justification.style(style), Constraints(maxWidth = plan.layoutWidth), density, fonts, plan.text.spanStyles)
                assertEquals(
                    "\"${text.text.take(24)}\" through $end",
                    plan.lines.map { it.start to it.end },
                    (0 until laid.lineCount).map { laid.getLineStart(it) to laid.getLineEnd(it, visibleEnd = true) }.filter { it.first < it.second },
                )
                previous = plan
            }
        }
    }
}
