@file:PortedFrom("OpenCodeTests/FileAttachmentTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.UUIDGenerator
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.uuid
import ai.opencode.mobilecode.tca.withLock
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileAttachmentFeatureTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_1", title = "Files", projectID = "p", directory = "/project", updatedAt = System.currentTimeMillis())

    private fun staged(name: String, mime: String = "application/pdf", size: Long = 100, id: Int = 1) = ComposerStagedFile(
        id = uuid(id),
        name = name,
        mime = mime,
        size = size,
        url = File("/nonexistent/ComposerFiles/${uuid(id)}/$name"),
    )

    private fun uploadPath(name: String) = "/tmp/opencode/uploads/u/$name"

    private fun file(files: List<ComposerFileAttachment>, index: Int, status: ComposerFileAttachment.Status) =
        files.toMutableList().also { it[index] = it[index].copy(status = status) }

    @Test
    fun uploadReportsProgressThenBecomesAPathReference() {
        val events = Channel<OpenCodeUploadEvent>(Channel.UNLIMITED)
        val requests = mutableListOf<String>()
        val file = staged("Report.pdf")
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.openCodeClient.uploadFile = { _, directory, url, name ->
                    requests += "${directory ?: "-"} ${url.name} $name"
                    events.consumeAsFlow()
                }
            },
        ) { SessionFeature() }

        store.send(SessionFeature.Action.filesStaged(listOf(file))) {
            it.pendingFiles = listOf(ComposerFileAttachment(file))
        }
        expect(store.state.hasPromptContent)
        events.trySend(OpenCodeUploadEvent.progress(40))
        store.receive(SessionFeature.Action.fileUploadProgress(id = file.id, sent = 40)) {
            it.pendingFiles = file(it.pendingFiles, 0, ComposerFileAttachment.Status.uploading(40))
        }
        expect(store.state.pendingFiles[0].progress == 0.4)
        events.trySend(OpenCodeUploadEvent.finished(uploadPath("Report.pdf")))
        store.receive(SessionFeature.Action.fileUploadFinished(id = file.id, result = Result.success(uploadPath("Report.pdf")))) {
            it.pendingFiles = file(it.pendingFiles, 0, ComposerFileAttachment.Status.uploaded(uploadPath("Report.pdf")))
        }
        events.close()
        expect(requests == listOf("/project Report.pdf Report.pdf"))
        expect(
            store.state.pendingFiles[0].reference == OpenCodeFileReference(
                name = "Report.pdf",
                mime = "application/pdf",
                path = uploadPath("Report.pdf"),
            ),
        )
        store.skipInFlightEffects()
    }

    @Test
    fun sendWaitsForUploadsAndCarriesTheirReferences() {
        val events = Channel<OpenCodeUploadEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<Pair<String, List<OpenCodeFileReference>>>()
        val file = staged("clip.mov", mime = "video/quicktime")
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.draft = "What happens here?"
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.uploadFile = { _, _, _, _ -> events.consumeAsFlow() }
                it.openCodeClient.sendPrompt = { _, _, text, _, files, _, _ -> sent += text to files }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(SessionFeature.Action.filesStaged(listOf(file)))
        store.send(SessionFeature.Action.sendTapped) { it.sendAfterUploads = OpenCodeDelivery.steer }
        expect(store.state.draft == "What happens here?", "The draft stays put until the message goes")
        expect(store.state.promptStates.isEmpty())
        expect(!store.state.canSendPrompt)

        events.trySend(OpenCodeUploadEvent.finished(uploadPath("clip.mov")))
        events.close()
        store.receive<SessionFeature.Action.fileUploadFinished>()
        store.receive<SessionFeature.Action.sendTapped> {
            it.sendAfterUploads = null
            it.pendingFiles = emptyList()
            it.draft = ""
        }
        val messageID = "msg_00000000000000000000000000000000"
        expect(
            store.state.items == listOf(
                OpenCodeTranscriptItem.userFiles(
                    id = "$messageID-files",
                    files = listOf(OpenCodeTranscriptFile(id = "$messageID-file-0", name = "clip.mov", mime = "video/quicktime", path = uploadPath("clip.mov"))),
                ),
                OpenCodeTranscriptItem.userText(id = messageID, text = "What happens here?"),
            ),
        )
        store.receive<SessionFeature.Action.promptAdmitted>()
        expect(sent.map { it.first } == listOf("What happens here?"))
        expect(sent.map { it.second } == listOf(listOf(OpenCodeFileReference(name = "clip.mov", mime = "video/quicktime", path = uploadPath("clip.mov")))))
        store.finish()
    }

    @Test
    fun failedUploadBlocksSendUntilRetried() {
        var attempts = 0
        val file = staged("notes.txt", mime = "text/plain")
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.openCodeClient.uploadFile = { _, _, _, _ ->
                    attempts += 1
                    val attempt = attempts
                    flow {
                        if (attempt == 1) {
                            throw OpenCodeClientError(message = "The server returned HTTP 500.", statusCode = 500)
                        }
                        emit(OpenCodeUploadEvent.finished("/tmp/opencode/uploads/u/notes.txt"))
                    }
                }
            },
        ) { SessionFeature() }

        store.send(SessionFeature.Action.filesStaged(listOf(file))) {
            it.pendingFiles = listOf(ComposerFileAttachment(file))
        }
        store.receive(
            SessionFeature.Action.fileUploadFinished(
                id = file.id,
                result = Result.failure(OpenCodeClientError(message = "The server returned HTTP 500.", statusCode = 500)),
            ),
        ) {
            it.pendingFiles = file(it.pendingFiles, 0, ComposerFileAttachment.Status.failed("Couldn’t upload"))
        }
        expect(!store.state.canSendPrompt)
        store.send(SessionFeature.Action.sendTapped)
        expect(store.state.promptStates.isEmpty())

        store.send(SessionFeature.Action.fileRetryTapped(file.id)) {
            it.pendingFiles = file(it.pendingFiles, 0, ComposerFileAttachment.Status.uploading(0))
        }
        store.receive(SessionFeature.Action.fileUploadFinished(id = file.id, result = Result.success("/tmp/opencode/uploads/u/notes.txt"))) {
            it.pendingFiles = file(it.pendingFiles, 0, ComposerFileAttachment.Status.uploaded("/tmp/opencode/uploads/u/notes.txt"))
        }
        expect(attempts == 2)
        expect(store.state.canSendPrompt)
    }

    @Test
    fun removingAnUploadingFileCancelsItAndReleasesAWaitingSend() = runBlocking {
        var terminated = false
        val file = staged("big.zip", mime = "application/zip")
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.draft = "Go"
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.openCodeClient.uploadFile = { _, _, _, _ ->
                    callbackFlow {
                        awaitClose { terminated = true }
                    }
                }
                it.openCodeClient.sendPrompt = { _, _, _, _, files, _, _ -> expect(files.isEmpty()) }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(SessionFeature.Action.filesStaged(listOf(file)))
        store.send(SessionFeature.Action.sendTapped) { it.sendAfterUploads = OpenCodeDelivery.steer }
        store.send(SessionFeature.Action.fileRemoved(file.id)) { it.pendingFiles = emptyList() }
        repeat(100) {
            if (terminated) return@repeat
            yield()
        }
        expect(terminated, "Removing the chip cancels its transfer")
        store.receive<SessionFeature.Action.sendTapped>()
        store.receive<SessionFeature.Action.promptAdmitted>()
        store.finish()
    }

    @Test
    fun missingRouteHidesFileOptionsForThatServer() {
        val file = staged("a.pdf")
        val serverID = server.persistenceID
        val store = TestStore(
            initialState = { SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.openCodeClient.uploadFile = { _, _, _, _ ->
                    flow { throw OpenCodeClientError.uploadsUnsupported }
                }
            },
        ) { SessionFeature() }
        expect(store.state.supportsFileUploads)

        store.send(SessionFeature.Action.filesStaged(listOf(file))) {
            it.pendingFiles = listOf(ComposerFileAttachment(file))
        }
        store.receive(SessionFeature.Action.fileUploadFinished(id = file.id, result = Result.failure(OpenCodeClientError.uploadsUnsupported))) {
            it::uploadUnsupportedServerIDs.withLock { value = setOf(serverID) }
            it.pendingFiles = file(it.pendingFiles, 0, ComposerFileAttachment.Status.failed("Server doesn’t support files"))
        }
        expect(!store.state.supportsFileUploads)
        // Retrying can't help until the server is updated.
        store.send(SessionFeature.Action.fileRetryTapped(file.id))
        // Another screen on the same server starts out without file options.
        expect(!SessionFeature.State(server = server, session = session).supportsFileUploads)
    }

    @Test
    fun pdfTravelsInlineWhenTheModelReadsPDFs() {
        val folder = File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString())
        folder.mkdirs()
        val url = File(folder, "Spec.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
        val file = ComposerStagedFile(id = uuid(7), name = "Spec.pdf", mime = "application/pdf", size = 8, url = url)
        val model = OpenCodeModel(modelID = "m", providerID = "p", name = "Reader", inputs = setOf("text", "image", "pdf"))
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.models = listOf(model)
        state.currentModel = model.ref
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.uploadFile = { _, _, _, _ ->
                    error("A PDF the model reads natively must not upload")
                }
            },
        ) { SessionFeature() }

        val attachment = OpenCodeOutgoingAttachment(id = uuid(7).toString(), data = "%PDF-1.7".toByteArray(), mime = "application/pdf", name = "Spec.pdf")
        store.send(SessionFeature.Action.filesStaged(listOf(file)))
        store.receive(SessionFeature.Action.attachmentAdded(attachment)) {
            it.pendingAttachments = listOf(attachment)
        }
        expect(store.state.pendingFiles.isEmpty())
    }

    @Test
    fun sharedFilesStartUploadingOnceAbsorbed() {
        var calls = 0
        val file = staged("memo.docx", mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
        state.absorb(SharedComposerDraft(text = "From Files", files = listOf(file)))
        expect(state.pendingFiles == listOf(ComposerFileAttachment(file)))
        expect(state.unstartedUploadIDs == listOf(file.id))
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.openCodeClient.uploadFile = { _, _, _, _ ->
                    calls += 1
                    flow { throw CancellationException() }
                }
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(SessionFeature.Action.queuedUploadsStarted) { it.unstartedUploadIDs = emptyList() }
        expect(calls == 1)
        store.finish()
    }

    private fun uuid(value: Int) = UUID(0, value.toLong())
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

class ShareHandoffFileTests {
    @Test
    fun sharedFilesMoveIntoTheAppStagingDirectory() {
        val root = File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString())
        val staging = File(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString())
        try {
            val source = File(root, "source.pdf")
            root.mkdirs()
            source.writeBytes("%PDF".toByteArray())
            val store = ShareHandoffStore(File(root, "Shares"))
            val id = UUID.randomUUID()
            val handoff = ShareHandoff(
                id = UUID.randomUUID(),
                createdAt = Instant.now(),
                serverID = "server",
                target = ShareHandoff.Target.newSession(projectID = null),
                text = "",
                files = listOf(ShareHandoff.File(id = id, fileName = "file-0", name = "Q3 Report.pdf", mime = "application/pdf")),
            )
            store.save(handoff, emptyList(), listOf(source))

            val shares = store.consumeAll(Instant.now(), staging)

            val file = shares.first().files.first()
            assertEquals("Q3 Report.pdf", file.name)
            assertEquals(4L, file.size)
            assertEquals(File(File(staging, id.toString()), "Q3 Report.pdf"), file.url)
            assertTrue("%PDF".toByteArray().contentEquals(file.url.readBytes()))
            val draft = SharedComposerDraft(shares.first(), projectID = null)
            assertEquals(listOf("Q3 Report.pdf"), draft.files.map { it.name })
        } finally {
            root.deleteRecursively()
            staging.deleteRecursively()
        }
    }
}
