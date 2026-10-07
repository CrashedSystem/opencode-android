@file:PortedFrom("OpenCodeTests/TranscriptEntryTests.swift", "553a407", partial = "pure entry and identity tests; copied markdown round-trip waits on the block parser")

package ai.opencode.mobilecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEntryTests {
    @Test
    fun snapshotTraceDistinguishesFlickerFromNavigationAndOldHistory() {
        val trace = TranscriptSnapshotTrace()
        assertTrue(trace.record("s1", listOf("text", "tool"), 0.0).reappeared.isEmpty())
        assertEquals(listOf("tool"), trace.record("s1", listOf("text"), 1.0).vanished)
        assertEquals(listOf("tool"), trace.record("s1", listOf("text", "tool"), 2.0).reappeared)
        trace.record("s1", listOf("text"), 3.0)
        assertTrue(trace.record("s1", listOf("text", "tool"), 9.0).reappeared.isEmpty())
        assertTrue(trace.record("s2", listOf("other"), 10.0).vanished.isEmpty())
    }

    @Test
    fun completionNoticesCollapseWithToolsWithoutCrossingAnAnswer() {
        val notice = OpenCodeTranscriptItem.notice(id = "done", text = "Shell finished · Build app")
        val entries = TranscriptEntry.build(from = listOf(
            tool("build", "shell"), notice, tool("read"),
            OpenCodeTranscriptItem.assistantMarkdown(id = "answer", blocks = emptyList()),
            OpenCodeTranscriptItem.notice(id = "later", text = "Agent finished · Review"),
        ))
        assertEquals(listOf("group-build", "answer", "group-later"), entries.map { it.id })
        assertEquals(listOf("build", "done", "read"), entries[0].itemIDs)
    }

    @Test
    fun groupsConsecutiveUserAndToolItems() {
        val items = listOf(
            OpenCodeTranscriptItem.userImages(id = "text-images", images = emptyList()),
            OpenCodeTranscriptItem.userText(id = "text", text = "Hello"),
            tool("read", "read"),
            tool("shell", "shell"),
            OpenCodeTranscriptItem.assistantMarkdown(id = "answer", blocks = emptyList()),
        )
        val entries = TranscriptEntry.build(from = items)
        assertEquals(3, entries.size)
        assertEquals("user-text-images", entries[0].id)
        assertEquals(listOf("text-images", "text"), entries[0].itemIDs)
        assertEquals("group-read", entries[1].id)
        assertEquals(listOf("read", "shell"), entries[1].itemIDs)
        assertEquals("answer", entries[2].id)
    }

    @Test
    fun questionToolBreaksToolRuns() {
        val question = OpenCodeTranscriptItem.tool(
            id = "question",
            call = OpenCodeToolCall(
                callID = "call-question",
                name = "question",
                status = OpenCodeToolCall.Status.completed,
                input = OpenCodeJSONValue.`object`(mapOf(
                    "questions" to OpenCodeJSONValue.array(listOf(
                        OpenCodeJSONValue.`object`(mapOf("question" to OpenCodeJSONValue.string("Continue?"))),
                    )),
                )),
                output = emptyList(),
                metadata = emptyMap(),
            ),
        )
        val entries = TranscriptEntry.build(from = listOf(tool("one"), question, tool("two")))
        assertEquals(listOf("group-one", "question", "group-two"), entries.map { it.id })
    }

    @Test
    fun findsContainingEntry() {
        val entries = TranscriptEntry.build(from = listOf(
            OpenCodeTranscriptItem.userText(id = "first", text = "One"),
            OpenCodeTranscriptItem.userText(id = "second", text = "Two"),
        ))
        assertEquals("user-second", TranscriptEntry.entryID(containing = "second", entries = entries))
        assertNull(TranscriptEntry.entryID(containing = "missing", entries = entries))
    }

    @Test
    fun adjacentPromptsKeepSeparateUserGroups() {
        val entries = TranscriptEntry.build(from = listOf(
            OpenCodeTranscriptItem.userText(id = "msg_first", text = "First"),
            OpenCodeTranscriptItem.userText(id = "msg_second", text = "Second"),
            OpenCodeTranscriptItem.userImages(id = "msg_third-images", images = emptyList()),
            OpenCodeTranscriptItem.userText(id = "msg_third", text = "Third"),
        ))
        assertEquals(listOf("user-msg_first", "user-msg_second", "user-msg_third-images"), entries.map { it.id })
        assertEquals(listOf("msg_third-images", "msg_third"), entries.last().itemIDs)
    }

    @Test
    fun identityPlanRetainsToolRowWhenLeadingMemberMoves() {
        val previous = TranscriptEntry.toolGroup(id = "tool-b", tools = listOf(tool("tool-b"), tool("tool-c")))
        val grown = TranscriptEntry.toolGroup(id = "tool-a", tools = listOf(tool("tool-a"), tool("tool-b"), tool("tool-c")))
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(previous),
            previousQueued = emptyList(),
            previousEntryStableIDs = mapOf(previous.id to "stable-tools"),
            previousQueuedStableIDs = emptyMap(),
            entries = listOf(grown),
            queued = emptyList(),
        )
        assertEquals("stable-tools", plan.entryStableIDs[grown.id])
    }

    @Test
    fun identityPlanResolvesGroupSplitAndMergeDeterministically() {
        val a = tool("a")
        val b = tool("b")
        val c = tool("c")
        val d = tool("d")
        val combined = TranscriptEntry.toolGroup(id = "a", tools = listOf(a, b, c, d))
        val leading = TranscriptEntry.toolGroup(id = "a", tools = listOf(a, b))
        val trailing = TranscriptEntry.toolGroup(id = "c", tools = listOf(c, d))
        val split = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(combined),
            previousQueued = emptyList(),
            previousEntryStableIDs = mapOf(combined.id to "stable-combined"),
            previousQueuedStableIDs = emptyMap(),
            entries = listOf(leading, trailing),
            queued = emptyList(),
        )
        assertEquals("stable-combined", split.entryStableIDs[leading.id])
        assertEquals(trailing.id, split.entryStableIDs[trailing.id])

        val merged = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(leading, trailing),
            previousQueued = emptyList(),
            previousEntryStableIDs = mapOf(leading.id to "stable-leading", trailing.id to "stable-trailing"),
            previousQueuedStableIDs = emptyMap(),
            entries = listOf(combined),
            queued = emptyList(),
        )
        assertEquals("stable-leading", merged.entryStableIDs[combined.id])
    }

    @Test
    fun tailIncludesTheReplyBehindItsActions() {
        val user = TranscriptEntry.single(OpenCodeTranscriptItem.userText(id = "u", text = "Hi"))
        val reply = TranscriptEntry.single(OpenCodeTranscriptItem.assistantMarkdown(id = "r", blocks = emptyList()))
        val end = TranscriptEntry.replyEnd(id = "reply-end-r", blocks = emptyList())
        assertEquals(listOf(end.id, reply.id), TranscriptEntry.tail(of = listOf(user, reply, end)).map { it.id })
        assertEquals(listOf(reply.id), TranscriptEntry.tail(of = listOf(user, reply)).map { it.id })
        assertTrue(TranscriptEntry.tail(of = emptyList()).isEmpty())
    }

    @Test
    fun queuedInputKeepsItsIdentityWhenEchoedThenPromoted() {
        val local = OpenCodeQueuedInput(input = OpenCodePendingInput(id = "msg_queued", text = "Steer here"))
        val inbox = OpenCodeQueuedInput(input = OpenCodePendingInput(id = "msg_queued", text = "Steer here"))
        val echoed = TranscriptRowIdentityPlan.make(
            previousEntries = emptyList(),
            previousQueued = listOf(local),
            previousEntryStableIDs = emptyMap(),
            previousQueuedStableIDs = mapOf(local.id to local.id),
            entries = emptyList(),
            queued = listOf(inbox),
        )
        assertEquals(local.id, echoed.queuedStableIDs[inbox.id])
        assertTrue(echoed.promotedEntryIDs.isEmpty())
        assertTrue(echoed.supersededQueuedIDs.isEmpty())

        val entry = TranscriptEntry.build(from = listOf(
            OpenCodeTranscriptItem.userText(id = "msg_queued", text = "Steer here"),
        )).first()
        val promoted = TranscriptRowIdentityPlan.make(
            previousEntries = emptyList(),
            previousQueued = listOf(inbox),
            previousEntryStableIDs = emptyMap(),
            previousQueuedStableIDs = echoed.queuedStableIDs,
            entries = listOf(entry),
            queued = emptyList(),
        )
        assertEquals(local.id, promoted.entryStableIDs[entry.id])
        assertEquals(setOf(entry.id), promoted.promotedEntryIDs)
        assertTrue(promoted.supersededQueuedIDs.isEmpty())
    }

    @Test
    fun promotedEntrySupersedesAStaleInboxSnapshot() {
        val inbox = OpenCodeQueuedInput(input = OpenCodePendingInput(id = "msg_queued", text = "Steer here"))
        val entry = TranscriptEntry.build(from = listOf(
            OpenCodeTranscriptItem.userText(id = "msg_queued", text = "Steer here"),
        )).first()
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = emptyList(),
            previousQueued = listOf(inbox),
            previousEntryStableIDs = emptyMap(),
            previousQueuedStableIDs = mapOf(inbox.id to "local-queued"),
            entries = listOf(entry),
            queued = listOf(inbox),
        )
        assertEquals("local-queued", plan.entryStableIDs[entry.id])
        assertEquals("local-queued", plan.queuedStableIDs[inbox.id])
        assertEquals(setOf(entry.id), plan.promotedEntryIDs)
        assertEquals(setOf(inbox.id), plan.supersededQueuedIDs)

        val repeated = TranscriptRowIdentityPlan.make(
            previousEntries = listOf(entry),
            previousQueued = listOf(inbox),
            previousEntryStableIDs = plan.entryStableIDs,
            previousQueuedStableIDs = plan.queuedStableIDs,
            entries = listOf(entry),
            queued = listOf(inbox),
        )
        assertEquals(setOf(inbox.id), repeated.supersededQueuedIDs)
    }

    @Test
    fun identicalQueuedTextPromotesEachPromptExactlyOnce() {
        val first = OpenCodeQueuedInput(input = OpenCodePendingInput(id = "msg_first", text = "Again"))
        val second = OpenCodeQueuedInput(input = OpenCodePendingInput(id = "msg_second", text = "Again"))
        val entries = TranscriptEntry.build(from = listOf(
            OpenCodeTranscriptItem.userText(id = "msg_first", text = "Again"),
            OpenCodeTranscriptItem.assistantMarkdown(id = "reply", blocks = emptyList()),
            OpenCodeTranscriptItem.userText(id = "msg_second", text = "Again"),
        ))
        val plan = TranscriptRowIdentityPlan.make(
            previousEntries = emptyList(),
            previousQueued = listOf(first, second),
            previousEntryStableIDs = emptyMap(),
            previousQueuedStableIDs = mapOf(first.id to first.id, second.id to second.id),
            entries = entries,
            queued = emptyList(),
        )
        assertEquals(setOf("user-msg_first", "user-msg_second"), plan.promotedEntryIDs)
        assertEquals("msg_first", plan.entryStableIDs["user-msg_first"])
        assertEquals("msg_second", plan.entryStableIDs["user-msg_second"])
    }

    private fun tool(id: String, name: String = "read") = OpenCodeTranscriptItem.tool(
        id = id,
        call = OpenCodeToolCall(
            callID = "call-$id",
            name = name,
            status = OpenCodeToolCall.Status.completed,
            input = OpenCodeJSONValue.`null`,
            output = emptyList(),
            metadata = emptyMap(),
        ),
    )
}

class ReplyEndTests {
    @Test
    fun eachFinishedReplyEndsWithItsWholeText() {
        val items = listOf(
            OpenCodeTranscriptItem.userText(id = "u1", text = "Why?"),
            reply("a1", "First part."),
            OpenCodeTranscriptItem.tool(
                id = "t1",
                call = OpenCodeToolCall(
                    callID = "c1",
                    name = "bash",
                    status = OpenCodeToolCall.Status.completed,
                    input = OpenCodeJSONValue.`null`,
                    output = emptyList(),
                    metadata = emptyMap(),
                ),
            ),
            reply("a2", "Second part."),
            OpenCodeTranscriptItem.userText(id = "u2", text = "And now?"),
            reply("a3", "Done."),
        )
        val entries = TranscriptEntry.build(from = items)
        assertEquals(listOf("First part.\n\nSecond part.", "Done."), replyEnds(entries))
        val index = entries.indexOfFirst { it.id == "reply-end-a2" }
        assertEquals("u2", entries[index + 1].userMessageID)
    }

    @Test
    fun aReplyStillRunningHasNoActionsYet() {
        val items = listOf(
            OpenCodeTranscriptItem.userText(id = "u1", text = "Why?"),
            reply("a1", "Working on it"),
        )
        assertTrue(replyEnds(TranscriptEntry.build(from = items, isRunning = true)).isEmpty())
        assertEquals(listOf("Working on it"), replyEnds(TranscriptEntry.build(from = items, isRunning = false)))
    }

    private fun reply(id: String, text: String) =
        OpenCodeTranscriptItem.assistantMarkdown(id = id, blocks = OpenCodeMarkdownBlock.blocks(from = text))

    private fun replyEnds(entries: List<TranscriptEntry>) = entries.mapNotNull { entry ->
        if (entry is TranscriptEntry.replyEnd) OpenCodeMarkdownBlock.markdown(entry.blocks) else null
    }
}
