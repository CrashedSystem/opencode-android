package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.AttributedString

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFixtureTests {
    @Test
    fun referenceStatesIncludeTheRenderedElements() {
        val blocks = TranscriptFixture.withIds(TranscriptFixture.referenceBlocks())
        assertTrue(blocks.any { it.kind is OpenCodeMarkdownBlock.Kind.heading })
        assertTrue(blocks.any { it.kind is OpenCodeMarkdownBlock.Kind.code })
        assertTrue(blocks.any { it.kind is OpenCodeMarkdownBlock.Kind.table })
        assertTrue(blocks.any { it.kind is OpenCodeMarkdownBlock.Kind.divider })
        assertTrue(blocks.any { it.kind is OpenCodeMarkdownBlock.Kind.files })
        assertTrue(blocks.any { (it.kind as? OpenCodeMarkdownBlock.Kind.listItem)?.marker == OpenCodeMarkdownBlock.doneTask })
        assertTrue(blocks.any { (it.kind as? OpenCodeMarkdownBlock.Kind.listItem)?.marker == OpenCodeMarkdownBlock.openTask })
        assertTrue(blocks.any { it.kind is OpenCodeMarkdownBlock.Kind.numberedItem })
        val nested = blocks.filterIsInstance<OpenCodeMarkdownBlock>().map { it.kind }.filterIsInstance<OpenCodeMarkdownBlock.Kind.listItem>()
        assertTrue(nested.any { it.level > 0 })
        val prose = blocks.first { it.kind is OpenCodeMarkdownBlock.Kind.paragraph }.kind as OpenCodeMarkdownBlock.Kind.paragraph
        assertTrue(prose.text.runs.any { AttributedString.Intent.stronglyEmphasized in it.inlinePresentationIntent } || blocks.any { block ->
            val text = when (val kind = block.kind) {
                is OpenCodeMarkdownBlock.Kind.paragraph -> kind.text
                is OpenCodeMarkdownBlock.Kind.heading -> kind.text
                else -> null
            }
            text?.runs?.any { AttributedString.Intent.stronglyEmphasized in it.inlinePresentationIntent } == true
        })
        val linked = blocks.map { it.kind }.filterIsInstance<OpenCodeMarkdownBlock.Kind.paragraph>().any { paragraph ->
            paragraph.text.runs.any { it.link != null }
        } || blocks.map { it.kind }.filterIsInstance<OpenCodeMarkdownBlock.Kind.listItem>().any { item ->
            item.text.runs.any { it.link != null }
        }
        assertTrue(linked)
        assertEquals(blocks.map { it.id }, blocks.indices.toList())
    }
}
