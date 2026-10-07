@file:PortedFrom("OpenCodeTests/WireRequestTests.swift", "553a407")

package ai.opencode.mobilecode

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireRequestTests {
    @Test
    fun composerPromptPostsFilesAndExplicitDelivery() = runBlocking {
        for (delivery in OpenCodeDelivery.entries) {
            val fixture = WireFixture { WireFixture.Reply.json(200, "{}") }
            try {
                val image = OpenCodeOutgoingAttachment(data = byteArrayOf(1, 2), mime = "image/png")
                OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "hello", listOf(image), emptyList(), "msg_1", delivery)
                val request = fixture.requests.first()
                assertEquals(1, fixture.requests.size)
                assertEquals("POST", request.method)
                assertEquals("/api/session/ses_1/prompt", request.path)
                assertEquals(
                    buildJsonObject {
                        put("id", "msg_1")
                        put("text", "hello")
                        putJsonArray("files") { add(buildJsonObject { put("uri", image.dataURL) }) }
                        put("delivery", delivery.name)
                    },
                    request.json(),
                )
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun voicePromptPostsDeliveryWithGeneratedID() = runBlocking {
        val fixture = WireFixture { WireFixture.Reply.json(200, "{}") }
        try {
            val messageID = OpenCodeClient.liveValue.sendDeliveredPrompt(fixture.server, "ses_1", "steer", OpenCodeDelivery.steer)
            val request = fixture.requests.first()
            assertTrue(messageID.startsWith("msg_"))
            assertEquals("/api/session/ses_1/prompt", request.path)
            assertEquals(
                buildJsonObject {
                    put("id", messageID)
                    put("text", "steer")
                    put("delivery", "steer")
                },
                request.json(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun runningShellsReadOnlyTheSessionLocation() = runBlocking {
        val fixture = WireFixture {
            WireFixture.Reply.json(200, """{"location":{"directory":"/a b"},"data":[{"id":"sh_1","status":"running","command":"bun dev","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/o","metadata":{"sessionID":"ses_1"},"time":{"started":1}},{"id":"sh_2","status":"exited","command":"ls","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/p","metadata":{},"time":{"started":1}}]}""")
        }
        try {
            val shells = OpenCodeClient.liveValue.runningShells(fixture.server, "/a b")
            assertEquals(listOf(OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = "ses_1", isListed = true, startedAt = 1)), shells)
            val request = fixture.requests.first()
            assertEquals("GET", request.method)
            assertEquals("/api/shell", request.path)
            assertEquals(listOf(URLQueryItem("location[directory]", "/a b")), request.queryItems())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun shellDetailOutputAndStopUseTheShellRoutes() = runBlocking {
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "GET" to "/api/shell/sh_1" -> WireFixture.Reply.json(200, """{"location":{"directory":"/a b"},"data":{"id":"sh_1","status":"exited","command":"bun test","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/o","exit":1,"metadata":{"sessionID":"ses_1"},"time":{"started":1000,"completed":4000}}}""")
                "GET" to "/api/shell/sh_1/output" -> WireFixture.Reply.json(200, """{"location":{"directory":"/a b"},"data":{"output":"FAIL\n","cursor":105,"size":105,"truncated":false}}""")
                else -> WireFixture.Reply.json(204, "")
            }
        }
        try {
            val shell = OpenCodeClient.liveValue.shell(fixture.server, "/a b", "sh_1")
            assertEquals(OpenCodeShell.Status.exited, shell.status)
            assertEquals(1, shell.exitCode)
            assertEquals(1000L, shell.startedAt)
            assertEquals(4000L, shell.completedAt)
            val page = OpenCodeClient.liveValue.shellOutput(fixture.server, "/a b", "sh_1", 100, 65_536)
            assertEquals(OpenCodeShellOutputPage("FAIL\n", 105, 105), page)
            OpenCodeClient.liveValue.stopShell(fixture.server, "/a b", "sh_1")
            OpenCodeClient.liveValue.moveToBackground(fixture.server, "ses_1")
            assertEquals(
                listOf("GET /api/shell/sh_1", "GET /api/shell/sh_1/output", "DELETE /api/shell/sh_1", "POST /api/session/ses_1/background"),
                fixture.requests.map { "${it.method} ${it.path}" },
            )
            assertEquals(
                listOf(
                    URLQueryItem("location[directory]", "/a b"),
                    URLQueryItem("cursor", "100"),
                    URLQueryItem("limit", "65536"),
                ),
                fixture.requests[1].queryItems(),
            )
            assertEquals(listOf(URLQueryItem("location[directory]", "/a b")), fixture.requests[2].queryItems())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverEndpointReplacesPathQueryAndFragment() {
        val server = OpenCodeServer(url = "  https://host.example:4096/base?token=x#part \n", username = "u", password = "p")
        assertEquals("https://host.example:4096/api/event", server.endpoint("/api/event")?.absoluteString)
        assertEquals(
            "https://host.example:4096/api/model?directory=/a%20b",
            server.endpoint("/api/model", listOf(URLQueryItem("directory", "/a b")))?.absoluteString,
        )
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("u:p".toByteArray()), server.basicAuthorization)
        assertNull(OpenCodeServer(url = "https://host.example", username = "u", password = "").basicAuthorization)
        for (url in listOf("ftp://example.invalid", "https://", "not a url")) {
            assertNull(OpenCodeServer(url = url, username = "", password = "").endpoint("/api/event"))
        }
    }

    @Test
    fun imageRequestReadsProjectFilesThroughTheServer() {
        val server = OpenCodeServer(url = "https://host.example:4096", username = "u", password = "p")
        val file = server.imageRequest("/work/proj/shots/a b/ü 100%.png", "/work/proj")
        assertEquals(
            "https://host.example:4096/api/fs/read/shots/a%20b/%C3%BC%20100%25.png?location%5Bdirectory%5D=/work/proj",
            file?.url?.absoluteString,
        )
        assertEquals(server.basicAuthorization, file?.value("Authorization"))
        assertEquals("/api/fs/read/a b.png", server.imageRequest("file:///work/proj/a%20b.png", "/work/proj")?.url?.path)
        assertEquals("/api/fs/read/docs/x.png", server.imageRequest("sandbox:docs/x.png", "/work/proj")?.url?.path)
        assertNull(server.imageRequest("/elsewhere/x.png", "/work/proj"))
        assertNull(server.imageRequest("x.png", null))
        val remote = server.imageRequest("https://cdn.example/x.png", null)
        assertEquals("https://cdn.example/x.png", remote?.url?.absoluteString)
        assertNull(remote?.value("Authorization"))
    }

    @Test
    fun assistantReplyStopsAtNextUserMessageSoSteersDoNotDuplicateFinalAnswer() = runBlocking {
        val voice1 = VoiceHandoff.format("First voice request", emptyList())
        val voice2 = VoiceHandoff.format("Second voice request", emptyList())
        val voice3 = VoiceHandoff.format("Third voice request", emptyList())
        fun message(id: String, type: String, text: String?) = buildJsonObject {
            put("id", id)
            put("type", type)
            putJsonObject("time") { put("created", 1) }
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    if (text != null) put("text", text)
                })
            }
        }
        val payload = buildJsonObject {
            putJsonArray("data") {
                add(message("msg_a4", "assistant", "Final answer after typed steer."))
                add(message("msg_typed", "user", "Typed composer steer mid-run"))
                add(message("msg_a3", "assistant", null))
                add(message("msg_3", "user", voice3))
                add(message("msg_a2", "assistant", "Interim answer for second request."))
                add(message("msg_2", "user", voice2))
                add(message("msg_a1", "assistant", null))
                add(message("msg_1", "user", voice1))
            }
        }
        val fixture = WireFixture { WireFixture.Reply.json(200, payload.toString()) }
        try {
            val client = OpenCodeClient.liveValue
            assertNull(client.assistantReply(fixture.server, "ses_1", "msg_1"))
            assertEquals("Interim answer for second request.", client.assistantReply(fixture.server, "ses_1", "msg_2"))
            assertEquals("Final answer after typed steer.", client.assistantReply(fixture.server, "ses_1", "msg_3"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun waitForIdleRewaitsWhenWaitTimesOutWhileSessionRemainsActive() = runBlocking {
        OpenCodeClient.waitClock = { }
        try {
            var attempts = 0
            val fixture = WireFixture { request ->
                when (request.method to request.path) {
                    "POST" to "/api/experimental/session/ses_1/wait" -> {
                        attempts += 1
                        if (attempts < 3) WireFixture.Reply.json(500, "{}") else WireFixture.Reply.json(204, "")
                    }
                    "GET" to "/api/session/active" -> WireFixture.Reply.json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                    else -> WireFixture.Reply.json(404, "{}")
                }
            }
            try {
                OpenCodeClient.liveValue.waitForIdle(fixture.server, "ses_1")
                assertEquals(3, attempts)
            } finally {
                fixture.close()
            }
        } finally {
            OpenCodeClient.waitClock = { seconds -> kotlinx.coroutines.delay(seconds * 1000L) }
        }
    }

    @Test
    fun waitForIdleDoesNotRetryClientErrorsAndBoundsFastActiveRetries() = runBlocking {
        var fourXX = 0
        var activeChecks = 0
        val nonRetryable = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" -> {
                    fourXX += 1
                    WireFixture.Reply.json(404, "{}")
                }
                "GET" to "/api/session/active" -> {
                    activeChecks += 1
                    WireFixture.Reply.json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                }
                else -> WireFixture.Reply.json(404, "{}")
            }
        }
        try {
            val error = runCatching { OpenCodeClient.liveValue.waitForIdle(nonRetryable.server, "ses_1") }.exceptionOrNull()
            assertTrue(error is OpenCodeClientError)
            assertEquals(1, fourXX)
            assertEquals(0, activeChecks)
        } finally {
            nonRetryable.close()
        }

        OpenCodeClient.waitClock = { }
        var fastFail = 0
        var wedgedActive = 0
        val wedged = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" -> {
                    fastFail += 1
                    WireFixture.Reply.json(500, "{}")
                }
                "GET" to "/api/session/active" -> {
                    wedgedActive += 1
                    WireFixture.Reply.json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                }
                else -> WireFixture.Reply.json(404, "{}")
            }
        }
        try {
            val error = runCatching { OpenCodeClient.liveValue.waitForIdle(wedged.server, "ses_1") }.exceptionOrNull()
            assertTrue(error is OpenCodeClientError)
            assertEquals(12, fastFail)
            assertEquals(11, wedgedActive)
        } finally {
            wedged.close()
            OpenCodeClient.waitClock = { seconds -> kotlinx.coroutines.delay(seconds * 1000L) }
        }
    }
}


