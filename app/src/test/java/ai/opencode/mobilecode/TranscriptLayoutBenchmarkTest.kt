package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.AttributedString
import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h800dp-mdpi")
class TranscriptLayoutBenchmarkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appendingOneTokenToTheLastParagraph() {
        val document = reply(400)
        val parsed = OpenCodeMarkdownBlock.blocks(from = document)
        assertTrue(parsed.size > 1)
        assertTrue(document.lines().size >= 400)
        val structured = TranscriptFixture.withIds(structuredReply())
        assertTrue(structured.any { it.kind is OpenCodeMarkdownBlock.Kind.table })
        assertTrue(structured.any { it.kind is OpenCodeMarkdownBlock.Kind.code })
        val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "pw")
        var blocks by mutableStateOf(structured)
        composeRule.setContent {
            ForcedAppearance(false) {
                SystemTheme {
                    Column(
                        Modifier
                            .width(440.dp)
                            .background(SystemColors.systemBackground)
                            .padding(horizontal = 16.dp),
                    ) {
                        MarkdownBlocksView(blocks, server, "/tmp/opencode-lab")
                    }
                }
            }
        }
        composeRule.waitForIdle()
        repeat(2) {
            blocks = grown(structured, " warm")
            composeRule.waitForIdle()
            blocks = structured
            composeRule.waitForIdle()
        }
        val parseSamples = ArrayList<Long>(8)
        val layoutSamples = ArrayList<Long>(8)
        repeat(8) {
            val parseStarted = System.nanoTime()
            val parsedFrame = OpenCodeMarkdownBlock.blocks(from = document + " x")
            val parsed = System.nanoTime() - parseStarted
            check(parsedFrame.last().kind is OpenCodeMarkdownBlock.Kind.paragraph)
            val layoutStarted = System.nanoTime()
            blocks = grown(structured, " x")
            composeRule.waitForIdle()
            val laidOut = System.nanoTime() - layoutStarted
            parseSamples += parsed
            layoutSamples += laidOut
            blocks = structured
            composeRule.waitForIdle()
        }
        fun median(values: List<Long>) = values.sorted().let { it[it.size / 2] }
        val parse = median(parseSamples)
        val layout = median(layoutSamples)
        println(
            "token-append-frame ns median=${parse + layout} parse=$parse layout=$layout " +
                "parseRange=${parseSamples.min()}-${parseSamples.max()} layoutRange=${layoutSamples.min()}-${layoutSamples.max()} n=8",
        )
        assertTrue(parse > 0L)
        assertTrue(layout > 0L)
    }

    private fun grown(blocks: List<OpenCodeMarkdownBlock>, token: String): List<OpenCodeMarkdownBlock> {
        val index = blocks.indexOfLast { it.kind is OpenCodeMarkdownBlock.Kind.paragraph }
        return blocks.mapIndexed { position, block ->
            if (position != index) return@mapIndexed block
            val paragraph = block.kind as OpenCodeMarkdownBlock.Kind.paragraph
            OpenCodeMarkdownBlock(block.id, OpenCodeMarkdownBlock.Kind.paragraph(AttributedString(paragraph.text.characters + token, paragraph.text.runs)))
        }
    }

    private fun reply(lines: Int): String {
        val body = StringBuilder()
        repeat(lines) { line ->
            when {
                line == 20 -> body.append("```swift\nlet value = line\nreturn value\n```\n")
                line == 80 -> body.append("| Name | Note |\n| --- | --- |\n| one | a longer cell that wraps |\n")
                line % 17 == 0 -> body.append("\n")
                else -> body.append("Line $line keeps the last paragraph growing with ordinary prose.\n")
            }
        }
        return body.toString()
    }

    private fun structuredReply(): List<OpenCodeMarkdownBlock> {
        val prose = (1..40).joinToString(" ") { "word$it" }
        return TranscriptFixture.blocks(
            """
            ## Reply

            $prose

            ```swift
            ${List(30) { "let value$it = $it" }.joinToString("\n")}
            ```

            | Change | Users saw | Fix | Proof |
            | --- | --- | --- | --- |
            | Permission order | Rules applied in the wrong order after an upgrade | Decode permissions to an ordered list | Canary test |
            | Tool schemas | Providers rejected tools that take no input | One shared schema | Byte-identical |
            | Empty chunks | Replies stopped mid-sentence | Filter keep-alive chunks | 400 sessions |

            $prose and the last paragraph is where the next token lands.
            """.trimIndent(),
        )
    }
}
