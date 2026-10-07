@file:PortedFrom("OpenCodeTests/AgentFileTests.swift", "553a407", partial = "reference, markdown cards, probe, and cache; tool summary lives in TranscriptEntryView")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.URL

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileReferenceTests {
    @Test
    fun inlineCodePathsNeedARecognizableFileName() {
        assertEquals("OpenCode/SessionView.swift", AgentFileReference.fromInlineCode("OpenCode/SessionView.swift")?.path)
        assertEquals("SessionView.swift", AgentFileReference.fromInlineCode("SessionView.swift")?.path)
        assertEquals(AgentFileReference.Kind.video, AgentFileReference.fromInlineCode("/tmp/demo.mp4")?.kind)
        assertEquals(AgentFileReference.Kind.pdf, AgentFileReference.fromInlineCode("~/Downloads/spec.pdf")?.kind)
        assertEquals(AgentFileReference.Kind.text, AgentFileReference.fromInlineCode("Makefile")?.kind)
        assertTrue(AgentFileReference.fromInlineCode("types/index.d.ts") != null)
        for (text in listOf(
            "store.send", "/api/fs/read", "--port", "https://example.com/a.pdf", "and/or", "a b.swift",
            "foo()", "Config.lock", "v1.2", "e.g.", "docs/", "@scope/pkg", "\$HOME/x.txt", "~",
        )) {
            assertNull(text, AgentFileReference.fromInlineCode(text))
        }
    }

    @Test
    fun lineSuffixesAreSplitOff() {
        assertEquals(AgentFileReference("OpenCode/SessionView.swift", 42), AgentFileReference.fromInlineCode("OpenCode/SessionView.swift:42"))
        assertEquals(AgentFileReference("main.go", 10), AgentFileReference.fromInlineCode("main.go:10:4"))
        assertEquals(AgentFileReference("src/app.ts", 7), AgentFileReference.fromLink("src/app.ts#L7"))
        assertEquals(AgentFileReference("SessionView.swift", 42), AgentFileReference.fromLink("SessionView.swift:42"))
        assertEquals(AgentFileReference("README.md", 7), AgentFileReference.fromLink("README.md?plain=1#L7"))
    }

    @Test
    fun localLinksAreFilesAndWebLinksAreNot() {
        assertEquals("docs/spec.pdf", AgentFileReference.fromLink("docs/spec.pdf")?.path)
        assertEquals("/tmp/demo clip.mov", AgentFileReference.fromLink("/tmp/demo%20clip.mov")?.path)
        assertEquals("/Users/kit/a.mp4", AgentFileReference.fromLink("file:///Users/kit/a.mp4")?.path)
        assertNull(AgentFileReference.fromLink("file://other-host/a.mp4"))
        assertNull(AgentFileReference.fromLink("https://example.com/a.mp4"))
        assertNull(AgentFileReference.fromLink("mailto:kit@example.com"))
        assertNull(AgentFileReference.fromLink("#section"))
        assertEquals("README.md", AgentFileReference.fromLink("README.md?plain=1")?.path)
    }

    @Test
    fun kindsChooseTheViewer() {
        assertEquals(AgentFileReference.Kind.video, AgentFileReference("a.mov").kind)
        assertEquals(AgentFileReference.Kind.audio, AgentFileReference("a.m4a").kind)
        assertEquals(AgentFileReference.Kind.image, AgentFileReference("a.png").kind)
        assertEquals(AgentFileReference.Kind.pdf, AgentFileReference("a.pdf").kind)
        assertEquals(AgentFileReference.Kind.text, AgentFileReference("a.swift").kind)
        assertEquals(AgentFileReference.Kind.document, AgentFileReference("a.zip").kind)
        assertEquals("Video · MP4", AgentFileReference("a.mp4").typeLabel)
        assertFalse(AgentFileReference("a.swift").kind.showsCard)
        assertTrue(AgentFileReference("a.pdf").kind.showsCard)
    }

    @Test
    fun targetsReadInsideTheSessionOrFromTheFilesOwnFolder() {
        val directory = "/Users/kit/code/app"
        assertEquals(
            AgentFileReference.Target(directory, "OpenCode/A.swift", "/Users/kit/code/app/OpenCode/A.swift"),
            AgentFileReference("OpenCode/A.swift").target(`in` = directory),
        )
        assertEquals("docs/spec.pdf", AgentFileReference("/Users/kit/code/app/docs/./spec.pdf").target(`in` = directory)?.relativePath)
        assertEquals(
            AgentFileReference.Target("/tmp", "demo.mp4", "/tmp/demo.mp4"),
            AgentFileReference("/tmp/demo.mp4").target(`in` = directory),
        )
        assertEquals(
            AgentFileReference.Target("/Users/kit/code/other", "x.md", "/Users/kit/code/other/x.md"),
            AgentFileReference("../other/x.md").target(`in` = directory),
        )
        assertEquals("/Users/kit/Downloads/a.pdf", AgentFileReference("~/Downloads/a.pdf").target(`in` = directory)?.absolutePath)
        assertNull(AgentFileReference("~/a.pdf").target(`in` = "/srv/app"))
        assertNull(AgentFileReference("a.pdf").target(`in` = null))
        assertEquals("/Users/kit/code/app2", AgentFileReference("/Users/kit/code/app2/a.md").target(`in` = directory)?.location)
    }

    @Test
    fun linkURLsRoundTrip() {
        val file = AgentFileReference("/tmp/a clip?.mp4", 3)
        assertEquals(file, AgentFileReference(linkURL = file.linkURL))
        assertNull(AgentFileReference(linkURL = URL("https://example.com")))
    }

    @Test
    fun readRequestScopesToTheLocation() {
        val server = OpenCodeServer(url = "https://box.ts.net:4096", username = "opencode", password = "pw")
        val target = AgentFileReference.Target("/tmp/x y", "my clip.mp4", "/tmp/x y/my clip.mp4")
        val request = server.fileReadRequest(target)!!
        assertEquals("https://box.ts.net:4096/api/fs/read/my%20clip.mp4?location%5Bdirectory%5D=/tmp/x%20y", request.url.absoluteString)
        assertEquals(server.basicAuthorization, request.value("Authorization"))
    }
}

class AgentFileMarkdownTests {
    @Test
    fun pathsInCodeAndLocalLinksBecomeFileLinks() {
        val blocks = OpenCodeMarkdownBlock.blocks("See `OpenCode/SessionView.swift:12`, [the spec](docs/spec.pdf), and [the site](https://opencode.ai).")
        val paragraph = blocks.first()?.kind as? OpenCodeMarkdownBlock.Kind.paragraph
        val urls = paragraph?.text?.runs?.mapNotNull { it.link }.orEmpty()
        assertEquals(3, urls.size)
        assertEquals(AgentFileReference("OpenCode/SessionView.swift", 12), AgentFileReference(linkURL = urls[0]))
        assertEquals("docs/spec.pdf", AgentFileReference(linkURL = urls[1])?.path)
        assertEquals("https://opencode.ai", urls[2].absoluteString)
    }

    @Test
    fun mediaAndDocumentsGetOneCardRowAfterTheirFirstMention() {
        val blocks = OpenCodeMarkdownBlock.blocks(
            """
            The recording is `out/demo.mp4` and the code is `src/app.ts`.

            - Spec: [spec](docs/spec.pdf)
            - Again: `out/demo.mp4`

            ```sh
            ls out/demo.mp4
            ```
            """.trimIndent(),
        )
        val cards = blocks.mapNotNull { block ->
            (block.kind as? OpenCodeMarkdownBlock.Kind.files)?.files?.map { it.path }
        }
        assertEquals(listOf(listOf("out/demo.mp4"), listOf("docs/spec.pdf")), cards)
        assertEquals(blocks.indices.toList(), blocks.map { it.id })
    }

    @Test
    fun markdownImagesOfMediaBecomeCards() {
        val blocks = OpenCodeMarkdownBlock.blocks("![demo](recordings/demo.mov)\n\n![shot](shot.png)")
        val files = blocks.first()?.kind as? OpenCodeMarkdownBlock.Kind.files
        assertEquals(listOf("recordings/demo.mov"), files?.files?.map { it.path })
        assertTrue(blocks.last()?.kind is OpenCodeMarkdownBlock.Kind.image)
    }

    @Test
    fun aFileMentionedTwiceGetsOneCard() {
        val blocks = OpenCodeMarkdownBlock.blocks("Rendered `out/demo.mp4`.\n\n![demo](out/demo.mp4)\n\n![code](src/app.ts)")
        val cards = blocks.mapNotNull { block ->
            (block.kind as? OpenCodeMarkdownBlock.Kind.files)?.files?.map { it.path }
        }
        assertEquals(listOf(listOf("out/demo.mp4")), cards)
    }

    @Test
    fun cardsSurviveTheTranscriptSnapshot() {
        val blocks = OpenCodeMarkdownBlock.blocks("Rendered `out/demo.mp4`.")
        val decoded = protocolJson.decodeFromString<List<OpenCodeMarkdownBlock>>(protocolJson.encodeToString(blocks))
        assertEquals(blocks.map { it.kind }, decoded.map { it.kind })
    }

    @Test
    fun toolRowsOfferViewableFiles() {
        fun call(name: String, input: Map<String, OpenCodeJSONValue>) = OpenCodeToolCall(
            callID = "c",
            name = name,
            status = OpenCodeToolCall.Status.completed,
            input = OpenCodeJSONValue.`object`(input),
            output = emptyList(),
            metadata = emptyMap(),
        )
        assertEquals("/repo/a.swift", call("write", mapOf("path" to OpenCodeJSONValue.string("/repo/a.swift"))).fileReference?.path)
        assertEquals(AgentFileReference.Kind.pdf, call("read", mapOf("filePath" to OpenCodeJSONValue.string("docs/spec.pdf"))).fileReference?.kind)
        assertNull(call("list", mapOf("path" to OpenCodeJSONValue.string("/repo/src"))).fileReference)
        assertNull(call("glob", mapOf("path" to OpenCodeJSONValue.string("src/*.ts"))).fileReference)
        assertNull(call("shell", mapOf("path" to OpenCodeJSONValue.string("a.swift"), "command" to OpenCodeJSONValue.string("ls"))).fileReference)
    }
}

class AgentFileLoadingTests {
    private fun response(status: Int, headers: Map<String, String>) = HTTPURLResponse(status, headers)

    @Test
    fun probeReadsRangeSupportAndSize() {
        val ranged = AgentFileProbe(response(206, mapOf("Content-Range" to "bytes 0-1/48213", "Content-Type" to "video/mp4", "ETag" to "W/\"1\"")))
        assertEquals(AgentFileProbe(48213, "video/mp4", true, "W/\"1\""), ranged)
        val whole = AgentFileProbe(response(200, mapOf("Content-Length" to "900", "Content-Type" to "application/pdf")))
        assertEquals(900L, whole.size)
        assertFalse(whole.supportsRanges)
        assertEquals(0L, AgentFileProbe(response(416, mapOf("Content-Range" to "bytes */0"))).size)
        assertNull(AgentFileProbe.totalLength("bytes 0-1/*"))
    }

    @Test
    fun streamingAsksForExactlyTheBytesAVFoundationWants() {
        assertEquals("bytes=0-1", StreamingFileLoader.rangeHeader(0, 2, false))
        assertEquals("bytes=1000-1499", StreamingFileLoader.rangeHeader(1000, 500, false))
        assertEquals("bytes=1000-", StreamingFileLoader.rangeHeader(1000, 500, true))
    }

    @Test
    fun errorsReadAsUserFacingCopy() {
        assertEquals(AgentFileError.notFound, AgentFileError.from(404))
        assertNull(AgentFileError.from(206))
        assertEquals("File Not Found", AgentFileError.notFound.title)
        assertEquals("Couldn’t Open File", AgentFileError.server(500).title)
    }

    @Test
    fun textDocumentsSplitLinesAndClampTheFocus() {
        val document = AgentTextDocument("let a = 1\nlet b = 2\n", "a.swift", 2)
        assertEquals(listOf("let a = 1", "let b = 2"), document.lines)
        assertEquals(2, document.focusLine)
        assertNull(AgentTextDocument("x", "a.txt", 9).focusLine)
    }

    @Test
    fun cacheKeysSeparateServersAndPaths() {
        val a = OpenCodeServer(url = "https://a:4096", username = "opencode", password = "")
        val b = OpenCodeServer(url = "https://b:4096", username = "opencode", password = "")
        val first = AgentFileLoader.cacheURL(a, "/tmp/x.mp4", "x.mp4")
        assertEquals("x.mp4", first.name)
        assertNotEquals(first, AgentFileLoader.cacheURL(b, "/tmp/x.mp4", "x.mp4"))
        assertNotEquals(first, AgentFileLoader.cacheURL(a, "/var/x.mp4", "x.mp4"))
    }
}
