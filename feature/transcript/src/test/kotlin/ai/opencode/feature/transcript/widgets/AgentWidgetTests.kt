package ai.opencode.feature.transcript.widgets

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Closed
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Effect
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Event
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Phase
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.RespondFailure
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Status
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Agent widgets: the step output and draw command readers, the widget machine, and reading a widget from its tool call. */
class AgentWidgetTests {
    @get:Rule val isolation = TestIsolationRule()

    // Step output

    @Test
    fun readsAStepOutputAndDropsUnknownNodes() {
        val out = AgentWidgetStep.output(
            """
            {"state":{"picked":1},"view":{"type":"column","gap":2,"children":[
              {"type":"text","text":"Hi","style":"title","tone":"accent"},
              {"type":"sparkle"},
              [{"type":"divider"}, null, false],
              {"type":"slider","id":"s","value":3,"min":0,"max":10,"step":1},
              {"type":"select","id":"area","options":[{"value":"n","label":"North"}],"value":"n"}
            ]}}
            """.trimIndent(),
        )
        val column = out.view as AgentWidgetNode.Column
        assertEquals(2, column.gap)
        assertEquals(AgentWidgetNode.Align.Stretch, column.align)
        assertEquals(4, column.children.size)
        assertEquals(AgentWidgetNode.Text("Hi", AgentWidgetNode.TextStyle.Title, AgentWidgetNode.Tone.Accent), column.children[0])
        assertEquals(AgentWidgetNode.Divider, column.children[1])
        assertEquals("""{"picked":1}""", out.state)
        assertNull(out.response)
    }

    @Test
    fun readsSubmitDismissAndSend() {
        val view = """"view":{"type":"divider"}"""
        assertEquals(OpenCodeWidgetResponse.Submit("""{"ok":true}"""), AgentWidgetStep.output("""{"state":{},$view,"submit":{"ok":true}}""").response)
        assertEquals(OpenCodeWidgetResponse.Submit("null"), AgentWidgetStep.output("""{"state":{},$view,"submit":null}""").response)
        assertEquals(OpenCodeWidgetResponse.Dismiss, AgentWidgetStep.output("""{"state":{},$view,"dismiss":true}""").response)
        val sent = AgentWidgetStep.output("""{"state":{},$view,"send":{"value":2,"wake":false}}""")
        assertNull(sent.response)
        assertEquals(OpenCodeWidgetMessageValue("2", wake = false), sent.message)
    }

    @Test
    fun rejectsAnInvalidView() {
        for (text in listOf("""{"state":{},"view":{"type":"sparkle"}}""", """{"state":{},"view":{"type":"button","id":"x"}}""", """{"state":{}}""")) {
            val failure = runCatching { AgentWidgetStep.output(text) }.exceptionOrNull()
            assertTrue(text, failure is AgentWidgetNode.InvalidView)
        }
    }

    // Draw commands

    private val palette = AgentWidgetDrawList.Palette(IntArray(AgentWidgetDrawList.Token.entries.size) { 0x10 + it })

    @Test
    fun readsDrawCommands() {
        val list = AgentWidgetDrawList()
        AgentWidgetDrawList.decode(
            """
            {"commands":[["save"],["rect",0,0,10,20,"#f00"],["line",0,0,5,5,"muted"],
              ["text",1,2,"Mon",{"size":11,"align":"center","weight":600}],["image","a.png",0,0,4,4],["bogus"],["restore"]]}
            """.trimIndent(),
            palette,
            list,
        )
        assertEquals(6, list.commandCount)
        assertEquals(listOf("Mon", "a.png"), list.strings)
        assertEquals(AgentWidgetDrawList.SAVE, list.ints[0])
        assertEquals(AgentWidgetDrawList.RECT, list.ints[1])
        assertEquals(AgentWidgetDrawList.FILL_COLOR, list.ints[2])
        assertEquals(0xFFFF0000.toInt(), list.ints[3])
        assertEquals(listOf(0f, 0f, 10f, 20f), list.floats.take(4))
        assertEquals(AgentWidgetDrawList.LINE, list.ints[7])
        assertEquals(1, list.ints[10])
        assertEquals(palette.tokens[AgentWidgetDrawList.Token.Muted.ordinal], list.ints[11])
    }

    /** Android: the runner already rejects such a draw (`Budget`); the reader keeps the first 2,000 and marks it truncated, which counts as over budget. */
    @Test
    fun rejectsTooManyDrawCommands() {
        val many = (0..AgentWidgetDrawList.MAX_COMMANDS).joinToString(",", """{"commands":[""", "]}") { """["save"]""" }
        val list = AgentWidgetDrawList()
        AgentWidgetDrawList.decode(many, palette, list)
        assertTrue(list.truncated)
        assertEquals(AgentWidgetDrawList.MAX_COMMANDS, list.commandCount)
    }

    @Test
    fun readsColours() {
        assertEquals(0xFFFFFFFF.toInt(), AgentWidgetDrawList.color("#fff", palette))
        assertEquals(0x80000000.toInt(), AgentWidgetDrawList.color("#00000080", palette))
        assertEquals(palette.tokens[AgentWidgetDrawList.Token.Accent.ordinal], AgentWidgetDrawList.color("accent", palette))
        assertNull(AgentWidgetDrawList.color("red", palette))
    }

    // Machine

    private fun output(state: String = "{}", response: OpenCodeWidgetResponse? = null) =
        AgentWidgetOutput(state, AgentWidgetNode.Divider, response)

    private fun AgentWidgetMachine.Machine.handle(event: Event): Pair<AgentWidgetMachine.Machine, List<Effect>> =
        AgentWidgetMachine.reduce(this, event)

    @Test
    fun runsInitThenStepsThenResponds() {
        var (machine, effects) = AgentWidgetMachine.open("""{"a":1}""", Status.Open)
        assertEquals(listOf(Effect.Fetch), effects)
        machine.handle(Event.Loaded).let { (m, e) -> machine = m; effects = e }
        assertEquals(listOf(Effect.Run("""{"phase":"init","data":{"a":1}}""")), effects)
        machine.handle(Event.Ran(Result.success(output("""{"n":1}""")))).let { (m, e) -> machine = m; effects = e }
        assertEquals(emptyList<Effect>(), effects)
        assertTrue(machine.phase is Phase.Ready)
        machine.handle(Event.Input(AgentWidgetEvent.press("go"))).let { (m, e) -> machine = m; effects = e }
        assertEquals(listOf(Effect.Run("""{"phase":"event","data":{"a":1},"state":{"n":1},"event":{"id":"go","type":"press"}}""")), effects)
        val submit = OpenCodeWidgetResponse.Submit("true")
        machine.handle(Event.Ran(Result.success(output(response = submit)))).let { (m, e) -> machine = m; effects = e }
        assertEquals(listOf(Effect.Respond(submit, attempt = 1)), effects)
        machine.handle(Event.Responded(1, null)).let { (m, e) -> machine = m; effects = e }
        assertEquals(emptyList<Effect>(), effects)
        assertEquals(Phase.Closed(AgentWidgetNode.Divider, Closed.Submitted("true"), "{}"), machine.phase)
    }

    @Test
    fun aFailedStepKeepsTheViewAndSaysWhy() {
        var machine = AgentWidgetMachine.open("null", Status.Open).first
        machine = machine.handle(Event.Loaded).first
        machine = machine.handle(Event.Ran(Result.success(output("""{"n":1}""")))).first
        machine = machine.handle(Event.Input(AgentWidgetEvent.press("go"))).first
        machine = machine.handle(Event.Ran(Result.failure(Exception("boom")))).first
        assertEquals(Phase.Ready("""{"n":1}""", AgentWidgetNode.Divider, "boom"), machine.phase)
    }

    @Test
    fun aConflictSaysItWasAlreadyAnswered() {
        var machine = AgentWidgetMachine.open("null", Status.Open).first
        machine = machine.handle(Event.Loaded).first
        machine = machine.handle(Event.Ran(Result.success(output()))).first
        machine = machine.handle(Event.Input(AgentWidgetEvent.press("go"))).first
        machine = machine.handle(Event.Ran(Result.success(output(response = OpenCodeWidgetResponse.Dismiss)))).first
        machine = machine.handle(Event.Responded(1, RespondFailure.Conflict)).first
        assertEquals(Phase.Ready("{}", AgentWidgetNode.Divider, "This widget was already answered."), machine.phase)
        machine = machine.handle(Event.StatusChanged(Status.Answered(Closed.Dismissed))).first
        assertEquals(Phase.Closed(AgentWidgetNode.Divider, Closed.Dismissed, "{}"), machine.phase)
    }

    @Test
    fun anAnsweredWidgetDrawsItsInitViewReadOnly() {
        var machine = AgentWidgetMachine.open("null", Status.Answered(Closed.Submitted("\"x\""))).first
        machine = machine.handle(Event.Loaded).first
        machine = machine.handle(Event.Ran(Result.success(output()))).first
        assertEquals(Phase.Closed(AgentWidgetNode.Divider, Closed.Submitted("\"x\""), "{}"), machine.phase)
    }

    @Test
    fun theFirstInitFailingFailsTheWidget() {
        var machine = AgentWidgetMachine.open("null", Status.Open).first
        machine = machine.handle(Event.Loaded).first
        machine = machine.handle(Event.Ran(Result.failure(Exception("no view")))).first
        assertEquals(Phase.Failed("no view", Status.Open), machine.phase)
        assertEquals(listOf(Effect.Fetch), machine.handle(Event.Retry).second)
    }

    // Tool call

    private fun json(text: String) = OpenCodeJSONValue.of(Json.parseToJsonElement(text))

    private fun call(
        input: String,
        status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed,
        metadata: Map<String, OpenCodeJSONValue> = emptyMap(),
        name: String = "widget",
        callID: String = "call_1",
    ) = OpenCodeToolCall(callID = callID, name = name, status = status, input = json(input), output = emptyList(), metadata = metadata)

    @Test
    fun readsAWidgetFromItsToolCall() {
        val widget = AgentWidgetAdapter.widget(
            call(
                """{"template":"confirm","title":"Deploy?","data":{"title":"Deploy?"}}""",
                metadata = mapOf("widget" to json("""{"template":{"version":"0123456789abcdef"},"status":"submitted","response":{"confirmed":true}}""")),
            ),
        )!!
        assertEquals("call_1", widget.callID)
        assertEquals(OpenCodeWidgetRef("confirm", "0123456789abcdef"), widget.template)
        assertEquals(AgentWidgetState.Status.Submitted, widget.status)
        assertEquals("""{"confirmed":true}""", widget.response)
        assertEquals("Deploy?", widget.label)
    }

    @Test
    fun anUnpinnedToolCallAsksForTheLatestTemplate() {
        val widget = AgentWidgetAdapter.widget(call("""{"template":"form","data":{}}"""))!!
        assertEquals("", widget.template.version)
        assertTrue(widget.template.isValid)
        assertEquals(AgentWidgetState.Status.Open, widget.status)
    }

    @Test
    fun otherCallsAndStreamingInputAreNotWidgets() {
        assertNull(AgentWidgetAdapter.widget(call("""{"template":"form"}""", status = OpenCodeToolCall.Status.Streaming)))
        assertNull(AgentWidgetAdapter.widget(call("""{"template":"../etc"}""")))
        assertNull(AgentWidgetAdapter.widget(call("""{"template":"form"}""", name = "read")))
    }

    @Test
    fun aWidgetCallIsItsOwnRow() {
        val items = listOf(
            OpenCodeTranscriptItem.Tool("t1", call("null", name = "read", callID = "c0")),
            OpenCodeTranscriptItem.Tool("t2", call("""{"template":"form"}""")),
        )
        val entries = TranscriptEntry.build(items)
        assertTrue(entries.any { ((it as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.Tool)?.call?.name == "widget" })
    }

    /** Android: the plugin's state pins the version and says how the widget ended. */
    @Test
    fun aToolCallTakesItsStateFromThePlugin() {
        val tool = OpenCodeTranscriptItem.Tool("msg_a-tool-0", call("""{"template":"confirm","data":{"a":1}}""").copy(messageID = "msg_a"))
        val record = ai.opencode.server.model.OpenCodeWidgetState(
            sessionID = "ses", messageID = "msg_a", callID = "call_1", template = OpenCodeWidgetRef("confirm", "0123456789abcdef"),
            title = "Deploy?", status = ai.opencode.server.model.OpenCodeWidgetStatus.Submitted, response = "true",
        )
        val pinned = AgentWidgetAdapter.states(listOf(tool), mapOf(record.key to record))["msg_a/call_1"]!!
        assertEquals("0123456789abcdef", pinned.template.version)
        assertEquals(AgentWidgetState.Status.Submitted, pinned.status)
        assertEquals("true", pinned.response)
        assertEquals("Deploy?", pinned.label)
        assertEquals("""{"a":1}""", pinned.data)
        assertEquals("msg_a/call_1", AgentWidgetAdapter.key(tool))

        val unpinned = AgentWidgetAdapter.states(listOf(tool))["msg_a/call_1"]!!
        assertEquals("", unpinned.template.version)
        assertEquals(AgentWidgetState.Status.Open, unpinned.status)
    }

    /** Android: specs/v2/widgets-fullscreen.md: the root's `fullscreen`, `fill` canvases, and the event a template hears. */
    @Test
    fun readsFullScreenOffersFillAndTheEvent() {
        val asked = AgentWidgetStep.output("""{"state":{},"view":{"type":"column","fullscreen":true,"children":[{"type":"canvas","height":120,"fill":true}]}}""")
        assertEquals(true, asked.fullscreen)
        assertTrue(AgentWidgetNode.fills(asked.view))
        assertTrue(AgentWidgetNode.drawsPixels(asked.view))
        val plain = AgentWidgetStep.output("""{"state":{},"view":{"type":"column","children":[{"type":"text","text":"Hi"}]}}""")
        assertNull(plain.fullscreen)
        assertTrue(!AgentWidgetNode.drawsPixels(plain.view))
        assertEquals(false, AgentWidgetStep.output("""{"state":{},"view":{"type":"divider","fullscreen":false}}""").fullscreen)
        assertEquals("""{"id":"","type":"fullscreen","value":true}""", AgentWidgetEvent.fullscreen(true).json)
    }
}
