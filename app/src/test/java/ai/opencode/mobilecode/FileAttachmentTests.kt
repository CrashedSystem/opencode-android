@file:PortedFrom("OpenCodeTests/FileAttachmentTests.swift", "553a407", partial = "typing, presentation, and upload wire; SessionFeature and ShareHandoffStore are outside this slice")

package ai.opencode.mobilecode

import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentFileTypeTests {
    @Test
    fun mimeMirrorsDesktopNormalization() {
        assertEquals("application/pdf", AttachmentFileType.mime("a.pdf", "application/pdf", byteArrayOf(0x25, 0x50)))
        assertEquals("application/pdf", AttachmentFileType.mime("a.pdf", null, byteArrayOf()))
        assertEquals("image/png", AttachmentFileType.mime("a.PNG", "application/octet-stream", byteArrayOf()))
        assertEquals("text/plain", AttachmentFileType.mime("a.json", "application/json", byteArrayOf()))
        assertEquals("text/plain", AttachmentFileType.mime("a.svg", "image/svg+xml", "<svg/>".toByteArray()))
        assertEquals("video/quicktime", AttachmentFileType.mime("a.mov", "video/quicktime", byteArrayOf(0, 0, 0, 0x14)))
        assertEquals("text/plain", AttachmentFileType.mime("Makefile", null, "all:\n\techo".toByteArray()))
        assertEquals("application/octet-stream", AttachmentFileType.mime("blob", null, byteArrayOf(1, 2, 3, 0)))
    }

    @Test
    fun labelsMatchDesktopCards() {
        assertEquals("PDF", AttachmentFileType.label("Report.pdf", "application/pdf"))
        assertEquals("Swift", AttachmentFileType.label("/tmp/x/App.swift", "text/plain"))
        assertEquals("MOV", AttachmentFileType.label("clip.mov", "video/quicktime"))
        assertEquals("File", AttachmentFileType.label(".gitignore", "text/plain"))
        assertEquals("File", AttachmentFileType.label("README", "text/plain"))
    }

    @Test
    fun namesStayASinglePathComponent() {
        assertEquals("-..-etc-passwd", AttachmentFileType.sanitizedName("../../etc/passwd"))
        assertEquals("hidden", AttachmentFileType.sanitizedName("  .hidden "))
        assertEquals("file", AttachmentFileType.sanitizedName(""))
    }
}

class PromptPresentationTests {
    private val reference = OpenCodeFileReference("Report.pdf", "application/pdf", "/tmp/opencode/uploads/u1/Report.pdf")

    @Test
    fun modelTextAppendsDesktopReferenceLines() {
        assertEquals(
            "Summarize\nAttached file: `/tmp/opencode/uploads/u1/Report.pdf`",
            OpenCodePromptPresentation.modelText("Summarize", listOf(reference)),
        )
        assertEquals(
            "Attached file: `/tmp/opencode/uploads/u1/Report.pdf`",
            OpenCodePromptPresentation.modelText("  ", listOf(reference)),
        )
    }

    @Test
    fun displayTextDropsOnlyReferenceLines() {
        val text = "Summarize\nAttached file: `/tmp/opencode/uploads/u1/Report.pdf`\nThe user made the following comment regarding this file of a.ts: hi"
        assertEquals(
            "Summarize\nThe user made the following comment regarding this file of a.ts: hi",
            OpenCodePromptPresentation.displayText(text, listOf(reference)),
        )
    }

    @Test
    fun userMessagesRenderChipsInsteadOfReferenceLines() {
        val items = OpenCodeClient.decodeTranscript(
            """
            {"data":[{"id":"msg_1","type":"user","text":"Summarize\nAttached file: `/tmp/opencode/uploads/u1/Report.pdf`",
              "metadata":{"displayText":"Summarize","comments":[],"attachments":[{"name":"Report.pdf","mime":"application/pdf","path":"/tmp/opencode/uploads/u1/Report.pdf"},{"name":"broken"}]},
              "files":[
                {"data":"JVBERg==","mime":"application/pdf","source":{"type":"inline"},"name":"Spec.pdf"},
                {"data":"","mime":"text/plain","source":{"type":"uri","uri":"file:///p/a.ts"},"name":"a.ts"},
                {"data":"aGk=","mime":"text/plain","source":{"type":"inline"},"name":"b.ts","mention":{"start":0,"end":5,"text":"@b.ts"}}
              ]}]}
            """.trimIndent().toByteArray(),
        )
        assertEquals(
            listOf(
                OpenCodeTranscriptItem.userFiles(
                    "msg_1-files",
                    listOf(
                        OpenCodeTranscriptFile("msg_1-file-0", "Report.pdf", "application/pdf", "/tmp/opencode/uploads/u1/Report.pdf"),
                        OpenCodeTranscriptFile("msg_1-file-inline-0", "Spec.pdf", "application/pdf"),
                    ),
                ),
                OpenCodeTranscriptItem.userText("msg_1", "Summarize"),
            ),
            items,
        )
    }

    @Test
    fun fileOnlyMessagesHaveNoBubble() {
        val items = OpenCodeClient.decodeTranscript(
            """
            {"data":[{"id":"msg_2","type":"user","text":"Attached file: `/tmp/a/clip.mov`",
              "metadata":{"displayText":"","comments":[],"attachments":[{"name":"clip.mov","mime":"video/quicktime","path":"/tmp/a/clip.mov"}]}}]}
            """.trimIndent().toByteArray(),
        )
        assertEquals(
            listOf(
                OpenCodeTranscriptItem.userFiles(
                    "msg_2-files",
                    listOf(OpenCodeTranscriptFile("msg_2-file-0", "clip.mov", "video/quicktime", "/tmp/a/clip.mov")),
                ),
            ),
            items,
        )
    }
}

class FileAttachmentWireTests {
    private fun temporaryFile(name: String, contents: String): File {
        val folder = File(System.getProperty("java.io.tmpdir"), java.util.UUID.randomUUID().toString())
        folder.mkdirs()
        return File(folder, name).apply { writeText(contents) }
    }

    @Test
    fun promptWithFilesMatchesDesktopWireFormat() = runBlocking {
        val fixture = WireFixture { WireFixture.Reply.json(200, "{}") }
        try {
            val pdf = OpenCodeOutgoingAttachment(data = "%PDF".toByteArray(), mime = "application/pdf", name = "Spec.pdf")
            val file = OpenCodeFileReference("clip.mov", "video/quicktime", "/tmp/opencode/uploads/u1/clip.mov")
            OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "Compare these", listOf(pdf), listOf(file), "msg_1", OpenCodeDelivery.steer)
            val request = fixture.requests.first()
            assertEquals(
                buildJsonObject {
                    put("id", "msg_1")
                    put("text", "Compare these\nAttached file: `/tmp/opencode/uploads/u1/clip.mov`")
                    putJsonArray("files") { add(buildJsonObject { put("uri", pdf.dataURL); put("name", "Spec.pdf") }) }
                    put("delivery", "steer")
                    putJsonObject("metadata") {
                        put("displayText", "Compare these")
                        putJsonArray("comments") {}
                        putJsonArray("attachments") {
                            add(buildJsonObject {
                                put("name", "clip.mov")
                                put("mime", "video/quicktime")
                                put("path", "/tmp/opencode/uploads/u1/clip.mov")
                            })
                        }
                    }
                },
                request.json(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun uploadStreamsTheFileIntoTheServerTemporaryDirectory() = runBlocking {
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/info" -> WireFixture.Reply.json(200, """{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/srv/tmp"}}""")
                "POST /api/experimental/fs/write" -> WireFixture.Reply.json(200, """{"location":{"directory":"/a b"},"data":{"path":"/srv/tmp/uploads/u/notes.txt"}}""")
                else -> WireFixture.Reply.status(404)
            }
        }
        try {
            val url = temporaryFile("notes.txt", "hello world")
            val events = OpenCodeClient.liveValue.uploadFile(fixture.server, "/a b", url, "notes.txt").toList()
            assertEquals(OpenCodeUploadEvent.finished("/srv/tmp/uploads/u/notes.txt"), events.last())
            val write = fixture.requests.first { it.route == "POST /api/experimental/fs/write" }
            assertEquals("/a b", write.queryValue("location[directory]"))
            val path = write.queryValue("path")!!
            assertTrue(path.matches(Regex("/srv/tmp/uploads/[0-9a-f-]{36}/notes\\.txt")))
            assertEquals("application/octet-stream", write.headers["Content-Type"])
            assertEquals(fixture.server.basicAuthorization, write.headers["Authorization"])
            if (write.body.isNotEmpty()) assertTrue("hello world".toByteArray().contentEquals(write.body))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun uploadFailuresDistinguishAMissingRoute() = runBlocking {
        val cases = listOf(
            Triple("""{"version":"1","pid":1,"urls":[]}""", 200, "") to true,
            Triple("""{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/t"}}""", 404, "") to true,
            Triple("""{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/t"}}""", 404, """{"_tag":"LocationNotFoundError"}""") to false,
            Triple("""{"version":"1","pid":1,"urls":[],"paths":{"tmp":"/t"}}""", 500, "") to false,
        )
        for ((input, unsupported) in cases) {
            val (info, writeStatus, writeBody) = input
            val fixture = WireFixture { request ->
                if (request.route == "GET /api/info") WireFixture.Reply.json(200, info) else WireFixture.Reply.json(writeStatus, writeBody)
            }
            try {
                val url = temporaryFile("a.bin", "x")
                val error = runCatching { OpenCodeClient.liveValue.uploadFile(fixture.server, null, url, "a.bin").toList() }.exceptionOrNull()
                assertEquals(unsupported, (error as? OpenCodeClientError)?.isUnsupported == true)
            } finally {
                fixture.close()
            }
        }
    }
}
