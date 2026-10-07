@file:PortedFrom("OpenCodeTests/OpenCodeClientDecodingTests.swift", "8fd69ec")

package ai.opencode.mobilecode

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

class OpenCodeClientDecodingTests {
    @Test
    fun liveServerReadContract() = runBlocking {
        assumeTrue(System.getenv("OPENCODE_LIVE_SERVER") != null)
        val url = System.getenv("OPENCODE_LIVE_SERVER")!!.let { if (it.startsWith("http")) it else "http://127.0.0.1:49374" }
        val password = ProcessBuilder("opencode", "service", "get", "password")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()
        val server = OpenCodeServer(url = url, username = "opencode", password = password)
        val client = OpenCodeClient.liveValue
        client.health(server)
        val snapshot = client.homeSnapshot(server)
        assertTrue(snapshot.sessions.all { it.parentID == null })
        val session = snapshot.sessions.firstOrNull() ?: error("live server has no session to read")
        val loaded = client.getSession(server, session.id)
        assertEquals(session.directory, loaded.directory)
        client.familyPendingRequests(server, listOf(loaded.id), loaded.directory)
        client.pendingInputs(server, loaded.id)
        client.transcript(server, loaded.id, 12)
        client.historyPage(server, loaded.id, null)
        client.runningShells(server, loaded.directory)
        client.contextUsage(server, loaded.id)
        val events = mutableListOf<OpenCodeServerEvent>()
        val job = this.launch {
            try {
                client.events(server).collect { events += it }
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        kotlinx.coroutines.delay(3_000)
        job.cancel()
        job.join()
        assertTrue(events.isNotEmpty())
        val spec = okhttp3.OkHttpClient().newCall(
            okhttp3.Request.Builder()
                .url("$url/openapi.json")
                .header("Authorization", server.basicAuthorization!!)
                .build(),
        ).execute().body.string()
        val paths = (protocolJson.parseToJsonElement(spec) as kotlinx.serialization.json.JsonObject).getValue("paths") as kotlinx.serialization.json.JsonObject
        val missing = clientRoutes().filter { (method, path) ->
            val item = paths[path] ?: return@filter true
            !(item as kotlinx.serialization.json.JsonObject).containsKey(method.lowercase())
        }
        assertEquals(emptyList<Pair<String, String>>(), missing)
    }

    @Test
    fun liveServerTransferDiagnostics() {
        assumeTrue(System.getenv("OPENCODE_LIVE_TRANSFER_TEST") == "1")
        error("DiagnosticsRecorder is outside this protocol slice")
    }

    @Test
    fun viewedProtocolUsesExactIdleWatermark() = runBlocking {
        val fixture = WireFixture { request ->
            if (request.method == "POST") WireFixture.Reply.status(204)
            else WireFixture.Reply.json(200, """{"data":{"id":"ses_view","projectID":"p","title":"Viewed","location":{"directory":"/project"},"time":{"updated":1789440123999,"idle":1789440123456,"viewed":1789440000000}}}""")
        }
        fixture.use {
            val client = OpenCodeClient.liveValue
            val session = client.getSession(fixture.server, "ses_view")
            assertEquals(1789440123456L, session.idleMilliseconds)
            assertEquals(1789440000000L, session.viewedMilliseconds)
            client.viewSession(fixture.server, session.id, session.idleMilliseconds!!)
            assertEquals(listOf("GET /api/session/ses_view", "POST /api/session/ses_view/view"), fixture.requests.map { it.route })
            assertEquals(buildJsonObject { put("idle", 1789440123456) }, fixture.requests[1].json())
        }
    }

    @Test
    fun homeActivityResolvesOnlyActiveAncestorsIncludingNestedChildren() = runBlocking {
        fun session(id: String, parent: String?) =
            """{"id":"$id","projectID":"p","title":"Test session","location":{"directory":"/project"},"time":{"updated":1000}""" +
                (parent?.let { ""","parentID":"$it"""" } ?: "") + "}"
        val responses = mapOf(
            "GET /api/project" to "[]",
            "GET /api/session" to """{"data":[${session("ses_root", null)}]}""",
            "GET /api/session/active" to """{"data":{"ses_deep":{"type":"busy"},"ses_sibling":{"type":"busy"}}}""",
            "GET /api/session/ses_deep" to """{"data":${session("ses_deep", "ses_child")}}""",
            "GET /api/session/ses_child" to """{"data":${session("ses_child", "ses_root")}}""",
            "GET /api/session/ses_sibling" to """{"data":${session("ses_sibling", "ses_root")}}""",
        )
        val fixture = WireFixture { request ->
            responses[request.route]?.let { WireFixture.Reply.json(200, it) } ?: WireFixture.Reply.status(404)
        }
        fixture.use {
            val snapshot = OpenCodeClient.liveValue.homeSnapshot(fixture.server)
            assertEquals(listOf("ses_root"), snapshot.sessions.map { it.id })
            assertEquals(setOf("ses_deep", "ses_sibling"), snapshot.activeSessionIDs)
            assertEquals(mapOf("ses_deep" to "ses_child", "ses_child" to "ses_root", "ses_sibling" to "ses_root"), snapshot.activeSessionParents)
            assertEquals(6, fixture.requests.size)
            assertEquals(responses.keys, fixture.requests.map { it.route }.toSet())
        }
    }

    @Test
    fun activeAncestryOnlyIgnoresDeletedSessions() = runBlocking {
        for (status in listOf(404, 503)) {
            val fixture = WireFixture { request ->
                when (request.route) {
                    "GET /api/project" -> WireFixture.Reply.json(200, "[]")
                    "GET /api/session" -> WireFixture.Reply.json(200, """{"data":[]}""")
                    "GET /api/session/active" -> WireFixture.Reply.json(200, """{"data":{"ses_child":{"type":"busy"}}}""")
                    else -> WireFixture.Reply.status(status)
                }
            }
            fixture.use {
                val error = runCatching { OpenCodeClient.liveValue.homeSnapshot(fixture.server) }
                if (status == 404) {
                    assertEquals(setOf("ses_child"), error.getOrThrow().activeSessionIDs)
                } else {
                    assertEquals(503, (error.exceptionOrNull() as OpenCodeClientError).statusCode)
                }
            }
        }
    }

    @Test
    fun syntheticInboxNeverRendersModelFacingShellPayloadAsUserText() = runBlocking {
        val fixture = pendingRequestsFixture(mapOf(
            "GET /api/session/ses_child/inbox" to WireFixture.Reply.json(200, """{"data":[{"id":"msg_user","type":"user","payload":{"text":"Queued user prompt"}},{"id":"msg_shell","type":"synthetic","payload":{"text":"<shell>model-facing result</shell>","description":"xcodebuild build-for-testing","metadata":{"source":"shell","state":"completed"}}},{"id":"msg_invisible","type":"synthetic","payload":{"text":"hidden instruction"}}]}"""),
        ))
        fixture.use {
            val inputs = OpenCodeClient.liveValue.pendingInputs(fixture.server, "ses_child")
            assertEquals(listOf("msg_user", "msg_shell"), inputs.map { it.id })
            assertEquals("Queued user prompt", inputs[0].text)
            assertEquals("", inputs[1].text)
            assertEquals("Shell finished", inputs[1].notice)
        }
        val transcript = OpenCodeClient.decodeTranscript("""{"data":[{"id":"msg_shell","type":"synthetic","text":"<shell state=\"completed\">raw tool output</shell>","description":"xcodebuild build-for-testing","metadata":{"source":"shell","state":"completed"}}]}""".toByteArray())
        assertEquals(listOf(OpenCodeTranscriptItem.notice("msg_shell", "Shell finished · xcodebuild build-for-testing")), transcript)
    }

    @Test
    fun inboxRetainsDeliveryModeForPresentation() = runBlocking {
        val fixture = pendingRequestsFixture(mapOf(
            "GET /api/session/ses_child/inbox" to WireFixture.Reply.json(200, """{"data":[{"id":"msg_steer","type":"user","delivery":"steer","payload":{"text":"Use this approach"}},{"id":"msg_queue","type":"user","delivery":"queue","payload":{"text":"Do this afterwards"}}]}"""),
        ))
        fixture.use {
            val inputs = OpenCodeClient.liveValue.pendingInputs(fixture.server, "ses_child")
            assertEquals(listOf(OpenCodeDelivery.steer, OpenCodeDelivery.queue), inputs.map { it.delivery })
        }
    }

    @Test
    fun currentServerStatusAndRenamedRequests() = runBlocking {
        val fixture = pendingRequestsFixture(mapOf(
            "GET /api/info" to WireFixture.Reply.json(200, """{"version":"test-current","pid":123,"urls":[],"paths":{"tmp":"/tmp"}}"""),
            "GET /api/form" to WireFixture.Reply.json(200, forms),
            "GET /api/session/ses_child/inbox" to WireFixture.Reply.json(200, """{"data":[{"id":"msg_queued","type":"user","payload":{"text":"Queued message"}}]}"""),
            "POST /api/experimental/session/ses_child/wait" to WireFixture.Reply.status(204),
            "DELETE /api/session/ses_child/form/frm_child" to WireFixture.Reply.status(204),
            "DELETE /api/session/ses_child" to WireFixture.Reply.status(204),
        ))
        fixture.use {
            val client = OpenCodeClient.liveValue
            client.health(fixture.server)
            val pending = client.familyPendingRequests(fixture.server, listOf("ses_root", "ses_child"), "/project")
            assertEquals(listOf("frm_root", "frm_child", "frm_global"), pending.forms.map { it.id })
            assertEquals(listOf("per_child"), pending.permissions.map { it.id })
            assertEquals("Queued message", client.pendingInputs(fixture.server, "ses_child").first().text)
            client.waitForIdle(fixture.server, "ses_child")
            client.cancelForm(fixture.server, "ses_child", "frm_child")
            client.deleteSession(fixture.server, "ses_child")
            assertEquals(
                listOf(
                    "DELETE /api/session/ses_child",
                    "DELETE /api/session/ses_child/form/frm_child",
                    "GET /api/form",
                    "GET /api/info",
                    "GET /api/permission/request",
                    "GET /api/session/ses_child/inbox",
                    "POST /api/experimental/session/ses_child/wait",
                ),
                fixture.requests.map { it.route }.sorted(),
            )
        }
    }

    @Test
    fun pendingSnapshotFiltersFormsAndPermissionsToTheFamily() = runBlocking {
        val fixture = pendingRequestsFixture()
        fixture.use {
            val pending = OpenCodeClient.liveValue.familyPendingRequests(fixture.server, listOf("ses_root", "ses_child"), "/project")
            assertEquals(listOf("per_child"), pending.permissions.map { it.id })
            assertEquals(listOf("frm_root", "frm_child", "frm_global"), pending.forms.map { it.id })
            assertEquals("ses_child", pending.forms.first { it.id == "frm_child" }.sessionID)
            assertNull(pending.forms.first().fields.first().options?.first()?.description)
        }
    }

    @Test
    fun pendingSnapshotDoesNotTreatRealFailuresAsEmpty() = runBlocking {
        val cases = listOf(
            Triple("GET /api/form", WireFixture.Reply.status(401), "The server rejected these credentials."),
            Triple("GET /api/form", WireFixture.Reply.status(500), "The server returned HTTP 500."),
            Triple("GET /api/form", WireFixture.Reply.json(200, """{"data":[{"id":"frm_bad"}]}"""), "The server response does not match the current OpenCode V2 contract."),
            Triple("GET /api/permission/request", WireFixture.Reply.status(404), "The server returned HTTP 404."),
        )
        for ((route, reply, message) in cases) {
            val fixture = pendingRequestsFixture(mapOf(route to reply))
            fixture.use {
                val error = runCatching {
                    OpenCodeClient.liveValue.familyPendingRequests(fixture.server, listOf("ses_root", "ses_child"), "/project")
                }.exceptionOrNull() as OpenCodeClientError
                assertEquals(message, error.message)
            }
        }
    }

    @Test
    fun formAndPermissionRepliesUseTheirOwningChildSession() = runBlocking {
        val fixture = WireFixture { WireFixture.Reply.status(204) }
        fixture.use {
            val client = OpenCodeClient.liveValue
            client.replyPermission(fixture.server, "ses_child", "per_child", OpenCodePermissionReply.once)
            client.replyForm(fixture.server, "ses_child", "frm_child", mapOf("q0" to OpenCodeFormValue.string("Ship")))
            client.cancelForm(fixture.server, "ses_child", "frm_child")
            assertEquals(
                listOf(
                    "POST /api/session/ses_child/permission/per_child/reply",
                    "POST /api/session/ses_child/form/frm_child/reply",
                    "DELETE /api/session/ses_child/form/frm_child",
                ),
                fixture.requests.map { it.route },
            )
            assertEquals(buildJsonObject { put("decision", "once") }, fixture.requests[0].json())
            assertEquals(buildJsonObject { put("answer", buildJsonObject { put("q0", "Ship") }) }, fixture.requests[1].json())
        }
    }

    @Test
    fun decodesDetailedToolStatesAndShellOutput() {
        val items = OpenCodeClient.decodeTranscript(
            """
            {"data":[
              {"id":"msg_shell","type":"shell","shellID":"sh_1","command":"git status","status":"exited","exit":0,"output":{"output":"clean"}},
              {"id":"msg_failed_shell","type":"shell","shellID":"sh_2","command":"false","status":"exited","exit":1,"output":{"output":""}},
              {"id":"msg_assistant","type":"assistant","content":[
                {"type":"reasoning","text":"Thinking","state":{"itemId":"reasoning_1","reasoningEncryptedContent":"opaque"}},
                {"type":"tool","id":"call_1","name":"edit","state":{"status":"completed","input":{"path":"App.swift"},"content":[{"type":"text","text":"Edited App.swift"}],"metadata":{"files":[{"file":"App.swift","patch":"@@ -1 +1 @@"}]}}},
                {"type":"tool","id":"call_2","name":"shell","state":{"status":"error","input":{"command":"false"},"error":{"type":"tool.execution","message":"Exited 1"},"content":[{"type":"text","text":"partial"}]}}
              ]}
            ]}
            """.trimIndent().toByteArray(),
        )
        assertEquals(4, items.size)
        val calls = items.mapNotNull { (it as? OpenCodeTranscriptItem.tool)?.call }
        val shell = calls.first { it.callID == "sh_1" }
        val failedShell = calls.first { it.callID == "sh_2" }
        val edit = calls.first { it.callID == "call_1" }
        val failed = calls.first { it.callID == "call_2" }
        assertEquals("git status", shell.subtitle)
        assertEquals(listOf("clean"), shell.output)
        assertEquals(OpenCodeToolCall.Status.error, failedShell.status)
        assertEquals("Command exited with code 1", failedShell.error)
        assertEquals("call_1", edit.callID)
        assertEquals("msg_assistant", edit.messageID)
        assertEquals("App.swift", edit.subtitle)
        assertEquals(listOf("Edited App.swift"), edit.output)
        assertTrue(edit.metadata.containsKey("files"))
        assertEquals(OpenCodeToolCall.Status.error, failed.status)
        assertEquals("Exited 1", failed.error)
        assertEquals(listOf("partial"), failed.output)
    }

    @Test
    fun toolRowsUseDesktopNamesDetailsAndReadableArguments() {
        fun call(name: String, input: Map<String, OpenCodeJSONValue>) = OpenCodeToolCall(
            callID = "c", name = name, status = OpenCodeToolCall.Status.completed,
            input = OpenCodeJSONValue.`object`(input), output = emptyList(), metadata = emptyMap(),
        )
        val shell = call("shell", mapOf(
            "command" to OpenCodeJSONValue.string("swift test --filter Layout\necho done"),
            "description" to OpenCodeJSONValue.string("Check session layout"),
            "timeout" to OpenCodeJSONValue.number(120_000.0),
        ))
        assertEquals("Shell", shell.displayName)
        assertEquals("Shell", call("bash", emptyMap()).displayName)
        assertEquals("swift test --filter Layout", shell.subtitle)
        assertEquals(listOf("timeout"), shell.arguments.map { it.first })
        assertEquals("120000", shell.arguments.first().second)
        val read = call("read", mapOf("path" to OpenCodeJSONValue.string("/repo/OpenCode/SessionView.swift"), "offset" to OpenCodeJSONValue.number(40.0)))
        assertEquals("SessionView.swift", read.subtitle)
        assertEquals(listOf("offset", "path"), read.arguments.map { it.first })
        assertEquals("Todo write", call("todo_write", emptyMap()).displayName)
        val include = call("grep", mapOf("pattern" to OpenCodeJSONValue.string("TODO"), "include" to OpenCodeJSONValue.array(listOf(OpenCodeJSONValue.string("*.swift")))))
        assertEquals("""["*.swift"]""", include.arguments.first { it.first == "include" }.second)
        val streaming = call("edit", emptyMap()).copy(input = OpenCodeJSONValue.string("""{"path": "a.sw"""))
        assertEquals(listOf("input"), streaming.arguments.map { it.first })
    }

    @Test
    fun permissionSheetTitlesAskWhatTheAgentWantsToDo() {
        fun request(action: String, resources: List<String>) = OpenCodePermissionRequest("p", "s", action, resources)
        assertEquals("Run Command?", request("shell", listOf("swift test")).title)
        assertEquals("Edit File?", request("edit", listOf("a.swift")).title)
        assertEquals("Edit 2 Files?", request("edit", listOf("a.swift", "b.swift")).title)
        assertEquals("Access a Folder Outside the Project?", request("external_directory", listOf("/tmp")).title)
        assertEquals("Allow Opencode read mcp resource?", request("opencode_read_mcp_resource", emptyList()).title)
    }

    @Test
    fun toolResultFileBlocksProjectImageAttachments() {
        val png = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(png)
        val image = """{"type":"file","mime":"image/png","name":"preview.png","uri":"data:image/png;base64,$encoded"}"""
        val payload = """{"data":[{"id":"msg_reply","type":"assistant","content":[{"type":"tool","id":"call_read","name":"read","state":{"status":"completed","input":{"path":"preview.png"},"content":[{"type":"text","text":"Image read successfully"},$image,{"type":"file","mime":"application/pdf","uri":"data:application/pdf;base64,AA=="},$image,{"type":"file","mime":"image/png","uri":"https://example.test/remote.png"}]}}]}]}"""
        val call = (OpenCodeClient.decodeTranscript(payload.toByteArray()).first() as OpenCodeTranscriptItem.tool).call
        assertEquals(listOf("Image read successfully"), call.output)
        assertEquals(listOf("msg_reply-tool-call_read-image-1", "msg_reply-tool-call_read-image-3"), call.imageAttachments.map { it.id })
        assertTrue(call.imageAttachments.all { it.data.contentEquals(png) })
        val old = protocolJson.encodeToString(OpenCodeToolCall(
            callID = "call_old", name = "read", status = OpenCodeToolCall.Status.completed,
            input = OpenCodeJSONValue.`null`, output = listOf("Done"), metadata = emptyMap(),
        ))
        val legacy = protocolJson.decodeFromString<OpenCodeToolCall>(old)
        assertTrue(legacy.imageAttachments.isEmpty())
    }

    @Test
    fun transcriptPageKeepsOpaqueCursorAndChronologicalItems() {
        val page = OpenCodeClient.decodeTranscriptPage("""{"data":[{"id":"newer","type":"user","text":"Newer"},{"id":"older","type":"user","text":"Older"}],"cursor":{"previous":"newer-page","next":"opaque/older+page="}}""".toByteArray())
        assertEquals(listOf(OpenCodeTranscriptItem.userText("older", "Older"), OpenCodeTranscriptItem.userText("newer", "Newer")), page.items)
        assertEquals("opaque/older+page=", page.next)
        assertNull(OpenCodeClient.decodeTranscriptPage("""{"data":[],"cursor":{}}""".toByteArray()).next)
    }

    @Test
    fun boundedJSONKeepsSmallValuesAndTrimsLargeOnesDeterministically() {
        assertEquals(OpenCodeJSONValue.string("ééé"), OpenCodeJSONValue.string("ééé").bounded(stringLimit = 3))
        assertEquals(OpenCodeJSONValue.string("abc\n… output truncated"), OpenCodeJSONValue.string("abcd").bounded(stringLimit = 3))
        val small = OpenCodeJSONValue.`object`(mapOf(
            "b" to OpenCodeJSONValue.array(listOf(OpenCodeJSONValue.string("abcd"), OpenCodeJSONValue.`null`)),
            "a" to OpenCodeJSONValue.bool(true),
        ))
        assertEquals(
            OpenCodeJSONValue.`object`(mapOf(
                "b" to OpenCodeJSONValue.array(listOf(OpenCodeJSONValue.string("abc\n… output truncated"), OpenCodeJSONValue.`null`)),
                "a" to OpenCodeJSONValue.bool(true),
            )),
            small.bounded(stringLimit = 3, collectionLimit = 2),
        )
        val large = OpenCodeJSONValue.`object`((0..4).associate { "k$it" to OpenCodeJSONValue.number(it.toDouble()) })
        assertEquals(
            OpenCodeJSONValue.`object`(mapOf("k0" to OpenCodeJSONValue.number(0.0), "k1" to OpenCodeJSONValue.number(1.0), "k2" to OpenCodeJSONValue.number(2.0))),
            large.bounded(collectionLimit = 3),
        )
        assertEquals(
            OpenCodeJSONValue.array(listOf(OpenCodeJSONValue.number(0.0), OpenCodeJSONValue.number(1.0), OpenCodeJSONValue.number(2.0))),
            OpenCodeJSONValue.array((0..4).map { OpenCodeJSONValue.number(it.toDouble()) }).bounded(collectionLimit = 3),
        )
    }

    @Test
    fun decodesCompactionStatesLikeTheTUI() {
        val tokens = """"tokens":{"input":1000,"output":200,"reasoning":50,"cache":{"read":300,"write":0}}"""
        val json = """
            {"data":[
              {"id":"c_run","type":"compaction","status":"running","reason":"auto","summary":"","recent":""},
              {"id":"c_done","type":"compaction","status":"completed","reason":"manual","summary":"  ## Summary\nKept the plan.  ","recent":"",$tokens},
              {"id":"c_provider","type":"compaction","status":"completed","reason":"auto","summary":"","recent":"","providerContext":{"id":"ctx"}},
              {"id":"c_abort","type":"compaction","status":"failed","reason":"manual","error":{"type":"aborted","message":"Aborted"}},
              {"id":"c_fail","type":"compaction","status":"failed","reason":"auto","error":{"type":"unknown","message":"Summary hit the output limit"},$tokens}
            ]}
        """.trimIndent()
        val infos = OpenCodeClient.decodeTranscript(json.toByteArray()).map { (it as OpenCodeTranscriptItem.compaction).info }
        assertEquals(
            listOf(
                OpenCodeCompaction(status = OpenCodeCompaction.Status.running),
                OpenCodeCompaction(status = OpenCodeCompaction.Status.completed, summary = OpenCodeMarkdownBlock.blocks("## Summary\nKept the plan."), inputTokens = 1300, outputTokens = 250),
                OpenCodeCompaction(status = OpenCodeCompaction.Status.completed, isProvider = true),
                OpenCodeCompaction(status = OpenCodeCompaction.Status.cancelled),
                OpenCodeCompaction(status = OpenCodeCompaction.Status.failed, errorMessage = "Summary hit the output limit", inputTokens = 1300, outputTokens = 250),
            ),
            infos.reversed(),
        )
        assertEquals("## Summary\n\nKept the plan.", OpenCodeMarkdownBlock.markdown(infos.reversed()[1]!!.summary))
        val legacy = protocolJson.decodeFromString<OpenCodeTranscriptItem>("""{"compaction":{"id":"old"}}""")
        assertEquals(OpenCodeTranscriptItem.compaction("old"), legacy)
    }

    @Test
    fun questionToolInputSupportsChoiceMultipleAndFreeResponsePrompts() {
        val input = protocolJson.decodeFromString<OpenCodeJSONValue>("""
            {"questions":[
              {"header":"Scope","question":"What should ship?","multiple":false,"options":[
                {"label":"App only","description":"Smallest change"},
                {"label":"Everything","description":"Include related work"}
              ]},
              {"header":"Checks","question":"Which checks should run?","multiple":true,"custom":true,"options":[
                {"label":"Unit tests","description":"Fast"},
                {"label":"Simulator","description":"Full UI"}
              ]},
              {"question":"Anything else?","custom":true}
            ]}
        """.trimIndent())
        val questions = OpenCodeToolCall(
            callID = "call_question", name = "question", status = OpenCodeToolCall.Status.running,
            input = input, output = emptyList(), metadata = emptyMap(),
        ).questions!!
        assertEquals(
            listOf(
                OpenCodeQuestion(question = "What should ship?", header = "Scope"),
                OpenCodeQuestion(question = "Which checks should run?", header = "Checks"),
                OpenCodeQuestion(question = "Anything else?", header = ""),
            ),
            questions,
        )
    }

    @Test
    fun malformedQuestionToolInputFallsBackToGenericToolUI() {
        val input = protocolJson.decodeFromString<OpenCodeJSONValue>("""{"questions":[{"header":"Missing question"},{"question":"  "}]}""")
        val call = OpenCodeToolCall(
            callID = "call_question", name = "question", status = OpenCodeToolCall.Status.running,
            input = input, output = emptyList(), metadata = emptyMap(),
        )
        assertNull(call.questions)
    }

    @Test
    fun formConditionsAndAcknowledgementsFollowV2Semantics() {
        val fields = protocolJson.decodeFromString<List<OpenCodeFormField>>("""
            [
              {"key":"conditional","type":"string","when":[{"key":"choice","op":"neq","value":"skip"}]},
              {"key":"member","type":"string","when":[{"key":"choices","op":"eq","value":"one"}]},
              {"key":"external","type":"external","url":"https://example.com"},
              {"key":"boolean","type":"boolean","required":true}
            ]
        """.trimIndent())
        assertFalse(fields[0].isVisible(emptyMap()))
        assertTrue(fields[0].isVisible(mapOf("choice" to OpenCodeFormValue.string("other"))))
        assertTrue(fields[1].isVisible(mapOf("choices" to OpenCodeFormValue.strings(listOf("one", "two")))))
        assertEquals("Complete this step", fields[2].validationError(OpenCodeFormValue.boolean(false)))
        assertNull(fields[2].validationError(OpenCodeFormValue.boolean(true)))
        assertNull(fields[3].validationError(OpenCodeFormValue.boolean(false)))
    }

    @Test
    fun formVisibilityIsProgressiveAcrossHiddenFields() {
        val form = protocolJson.decodeFromString<OpenCodeFormRequest>("""
            {"id":"frm_1","sessionID":"ses_1","title":"Conditional","fields":[
              {"key":"a","type":"boolean"},
              {"key":"b","type":"string","when":[{"key":"a","op":"eq","value":true}]},
              {"key":"c","type":"string","when":[{"key":"b","op":"eq","value":"show"}]},
              {"key":"external","type":"external","url":"https://example.com"}
            ]}
        """.trimIndent())
        val answers = mapOf("a" to OpenCodeFormValue.boolean(false), "b" to OpenCodeFormValue.string("show"))
        assertEquals(listOf("a", "external"), form.activeFields(answers).map { it.key })
        assertEquals(OpenCodeFormValue.boolean(false), form.defaultAnswers["external"])
    }

    private fun pendingRequestsFixture(overrides: Map<String, WireFixture.Reply> = emptyMap()) = WireFixture { request ->
        if (request.path in listOf("/api/permission/request", "/api/form") &&
            (request.method != "GET" || request.queryValue("location[directory]") != "/project")
        ) {
            return@WireFixture WireFixture.Reply.status(400)
        }
        overrides[request.route]?.let { return@WireFixture it }
        when (request.route) {
            "GET /api/permission/request" -> WireFixture.Reply.json(200, """{"data":[{"id":"per_child","sessionID":"ses_child","action":"shell","resources":["git status"]},{"id":"per_other","sessionID":"ses_other","action":"shell","resources":["pwd"]}]}""")
            "GET /api/form" -> WireFixture.Reply.json(200, forms)
            else -> WireFixture.Reply.status(404)
        }
    }

    private fun <T> WireFixture.use(block: suspend WireFixture.() -> T): T = try {
        runBlocking { block() }
    } finally {
        close()
    }
}

private val forms = """{"data":[{"id":"frm_root","sessionID":"ses_root","title":"Questions","metadata":{"kind":"question"},"fields":[{"key":"q0","type":"string","options":[{"value":"Ship","label":"Ship"}],"custom":true}]},{"id":"frm_child","sessionID":"ses_child","title":"Child questions","fields":[{"key":"q0","type":"string"}]},{"id":"frm_global","sessionID":"global","title":"Sign in","fields":[{"key":"token","type":"string"}]},{"id":"frm_other","sessionID":"ses_other","title":"Other session","fields":[{"key":"q0","type":"string"}]}]}"""

private fun clientRoutes() = listOf(
    "GET" to "/api/info",
    "GET" to "/auth/connect/{code}",
    "GET" to "/api/project",
    "GET" to "/api/session",
    "GET" to "/api/session/active",
    "GET" to "/api/session/{sessionID}",
    "POST" to "/api/session",
    "PATCH" to "/api/session/{sessionID}",
    "DELETE" to "/api/session/{sessionID}",
    "POST" to "/api/session/{sessionID}/view",
    "POST" to "/api/session/{sessionID}/prompt",
    "GET" to "/api/session/{sessionID}/message",
    "GET" to "/api/session/{sessionID}/message/{messageID}",
    "POST" to "/api/experimental/session/{sessionID}/wait",
    "POST" to "/api/session/{sessionID}/interrupt",
    "POST" to "/api/session/{sessionID}/compact",
    "POST" to "/api/session/{sessionID}/fork",
    "GET" to "/api/experimental/session/{sessionID}/export",
    "POST" to "/api/session/{sessionID}/model",
    "GET" to "/api/model",
    "GET" to "/api/model/default",
    "GET" to "/api/permission/request",
    "GET" to "/api/form",
    "POST" to "/api/session/{sessionID}/permission/{requestID}/reply",
    "POST" to "/api/session/{sessionID}/form/{formID}/reply",
    "DELETE" to "/api/session/{sessionID}/form/{formID}",
    "GET" to "/api/session/{sessionID}/inbox",
    "PATCH" to "/api/session/{sessionID}/inbox/{inboxID}",
    "DELETE" to "/api/session/{sessionID}/inbox/{inboxID}",
    "GET" to "/api/shell",
    "GET" to "/api/shell/{id}",
    "GET" to "/api/shell/{id}/output",
    "DELETE" to "/api/shell/{id}",
    "POST" to "/api/session/{sessionID}/background",
    "POST" to "/api/experimental/fs/write",
    "GET" to "/api/fs/read/*",
    "GET" to "/api/event",
)
