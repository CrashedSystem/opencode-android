@file:PortedFrom("OpenCodeTests/OpenCodeEventDecodingTests.swift", "553a407")

package ai.opencode.mobilecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeEventDecodingTests {
    @Test
    fun decodesSanitizedLiveSessionContract() {
        val lines = """
            {"type":"server.connected","data":{}}
            {"type":"session.step.started","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture"}}
            {"type":"session.text.started","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0}}
            {"type":"session.text.delta","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0,"delta":"Hel"}}
            {"type":"session.text.ended","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0,"text":"Hello"}}
            {"type":"session.reasoning.started","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0}}
            {"type":"session.reasoning.delta","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0,"delta":"Why"}}
            {"type":"session.reasoning.ended","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0,"text":"Why"}}
            {"type":"session.tool.input.started","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","name":"shell"}}
            {"type":"session.tool.input.delta","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","delta":"{}"}}
            {"type":"session.tool.input.ended","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","text":"{}"}}
            {"type":"session.tool.called","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","input":{},"executed":false}}
            {"type":"session.tool.progress","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","metadata":{"state":"running"}}}
            {"type":"session.tool.success","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","content":[{"type":"text","text":"done"}],"executed":false}}
            {"type":"session.tool.failed","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","error":{"message":"failed"},"executed":false}}
            {"type":"session.step.streamed","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture"}}
            {"type":"session.step.ended","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture"}}
            {"type":"session.inbox.enqueued","data":{"sessionID":"ses_fixture","inboxID":"msg_input","item":{"type":"user"}}}
            {"type":"session.inbox.delivered","data":{"sessionID":"ses_fixture","inboxID":"msg_input"}}
        """.trimIndent().lines()
        val events = lines.map { OpenCodeClient.decodeStreamFrame(it.toByteArray()) }
        assertEquals(lines.size, events.size)
        assertEquals(OpenCodeServerEvent.connected, events.first())
        assert(events.contains(OpenCodeServerEvent.inputEnqueued("ses_fixture")))
        val content = events.flatMap { event ->
            (event as? OpenCodeServerEvent.contentBatch)?.events ?: emptyList()
        }
        assertEquals(16, content.size)
        assert(content.contains(OpenCodeLiveContentEvent.textEnded("msg_fixture", 0, "Hello")))
        assert(content.contains(OpenCodeLiveContentEvent.toolSucceeded("msg_fixture", "tool_fixture", listOf("done"), emptyMap())))
    }

    @Test
    fun shellLifecycleFramesNameTheOwnerAndEnd() {
        val created = """{"type":"shell.created","location":{"directory":"/repo"},"data":{"info":{"id":"sh_1","status":"running","command":"bun dev\n--port 3000","cwd":"/repo","shell":"/bin/zsh","file":"/tmp/sh_1.out","pid":4,"metadata":{"sessionID":"ses_1"},"time":{"started":1}}}}"""
        val user = """{"type":"shell.created","data":{"info":{"id":"sh_2","status":"running","command":"ls","cwd":"/repo","shell":"/bin/zsh","file":"/tmp/sh_2.out","metadata":{"sessionID":"ses_1","background":true},"time":{"started":1}}}}"""
        val started = OpenCodeClient.decodeStreamFrame(created.toByteArray())
        assertEquals(
            OpenCodeServerEvent.shellStarted(OpenCodeShell(id = "sh_1", command = "bun dev\n--port 3000", sessionID = "ses_1", startedAt = 1)),
            started,
        )
        assertEquals("bun dev", (started as OpenCodeServerEvent.shellStarted).shell.title)
        assertEquals(
            OpenCodeServerEvent.shellStarted(OpenCodeShell(id = "sh_2", command = "ls", sessionID = "ses_1", isUserCommand = true, startedAt = 1)),
            OpenCodeClient.decodeStreamFrame(user.toByteArray()),
        )
        for (type in listOf("shell.exited", "shell.deleted")) {
            val frame = """{"type":"$type","location":{"directory":"/repo"},"data":{"id":"sh_1","exit":0,"status":"exited"}}"""
            assertEquals(OpenCodeServerEvent.shellEnded("sh_1"), OpenCodeClient.decodeStreamFrame(frame.toByteArray()))
        }
    }

    @Test
    fun streamFramesDecodeOnceAndThrowOnServerFailure() {
        val frame = """ {"type":"session.text.delta","data":{"sessionID":"s","assistantMessageID":"m","ordinal":0,"delta":"Hi"}}""".toByteArray()
        assertEquals(
            OpenCodeServerEvent.contentBatch("s", listOf(OpenCodeLiveContentEvent.textDelta("m", 0, "Hi"))),
            OpenCodeClient.decodeStreamFrame(frame),
        )
        assertNull(OpenCodeClient.decodeStreamFrame("""{"type":"unknown.event"}""".toByteArray()))
        assertNull(OpenCodeClient.decodeStreamFrame("not json".toByteArray()))
        val failure = OpenCodeClientError(message = "The event stream reported a server failure.")
        for (raw in listOf("""{"_tag":"Die","cause":{}}""", """{"type":"server.connected","_tag":"Fail"}""")) {
            val error = runCatching { OpenCodeClient.decodeStreamFrame(raw.toByteArray()) }.exceptionOrNull()
            assertEquals(failure, error)
        }
    }

    @Test
    fun adjacentDeltasForOnePartCoalesceWithoutReordering() {
        val pending = mutableListOf<OpenCodeLiveContentEvent>()
        pending.appendCoalescingDeltas(listOf(
            OpenCodeLiveContentEvent.textDelta("m", 0, "Hel"),
            OpenCodeLiveContentEvent.textDelta("m", 0, "lo"),
            OpenCodeLiveContentEvent.textDelta("m", 1, "Other part"),
            OpenCodeLiveContentEvent.reasoningDelta("m", 0, "Why"),
            OpenCodeLiveContentEvent.reasoningDelta("m", 0, " not"),
            OpenCodeLiveContentEvent.toolInputDelta("m", "t", "{\"a\""),
        ))
        pending.appendCoalescingDeltas(listOf(
            OpenCodeLiveContentEvent.toolInputDelta("m", "t", ":1}"),
            OpenCodeLiveContentEvent.toolInputDelta("m", "u", "{}"),
            OpenCodeLiveContentEvent.textDelta("m", 0, "!"),
            OpenCodeLiveContentEvent.textEnded("m", 0, "Hello!"),
            OpenCodeLiveContentEvent.textDelta("m", 0, "late"),
        ))
        assertEquals(
            listOf(
                OpenCodeLiveContentEvent.textDelta("m", 0, "Hello"),
                OpenCodeLiveContentEvent.textDelta("m", 1, "Other part"),
                OpenCodeLiveContentEvent.reasoningDelta("m", 0, "Why not"),
                OpenCodeLiveContentEvent.toolInputDelta("m", "t", "{\"a\":1}"),
                OpenCodeLiveContentEvent.toolInputDelta("m", "u", "{}"),
                OpenCodeLiveContentEvent.textDelta("m", 0, "!"),
                OpenCodeLiveContentEvent.textEnded("m", 0, "Hello!"),
                OpenCodeLiveContentEvent.textDelta("m", 0, "late"),
            ),
            pending,
        )
    }

    @Test
    fun decodesInboxEvents() {
        for (type in listOf("session.inbox.enqueued", "session.inbox.delivery.changed")) {
            val data = """{"type":"$type","data":{"sessionID":"ses_fixture"}}""".toByteArray()
            assertEquals(OpenCodeServerEvent.inputEnqueued("ses_fixture"), OpenCodeClient.decodeStreamFrame(data))
        }
        val cancelled = """{"type":"session.inbox.cancelled","data":{"sessionID":"ses_fixture","inboxID":"msg_cancel"}}""".toByteArray()
        assertEquals(OpenCodeServerEvent.inputCancelled("ses_fixture", "msg_cancel"), OpenCodeClient.decodeStreamFrame(cancelled))
        assertEquals(
            OpenCodeServerEvent.inputQueueChanged("ses_fixture"),
            OpenCodeClient.decodeStreamFrame("""{"type":"session.inbox.delivered","data":{"sessionID":"ses_fixture"}}""".toByteArray()),
        )
    }
}
