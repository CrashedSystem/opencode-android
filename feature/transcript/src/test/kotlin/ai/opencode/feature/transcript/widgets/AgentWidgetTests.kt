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
import ai.opencode.server.model.OpenCodeWidgetProgram
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import ai.opencode.core.testing.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A game awaiting `widget.frame()` when its Done handler submits ends in a deadlock exit right after the submit. */
    @Test
    fun liveSubmitEndsTheRunWithoutReportingItsExit() = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val log = CopyOnWriteArrayList<String>()
        val stopped = AtomicBoolean(false)
        val program = object : AgentWidgetLiveProgram {
            override fun start(inputJson: String, listener: AgentWidgetLiveListener, httpsHosts: List<String>?): AgentWidgetLiveRun {
                val run = object : AgentWidgetLiveRun {
                    override fun deliver(messageJson: String) = Unit
                    override fun pause() = Unit
                    override fun resume() = Unit
                    override fun snapshot() = true
                    override fun stop() = stopped.set(true)
                }
                thread {
                    listener.posted(listOf("""{"type":"submit","value":{"score":40}}"""))
                    listener.exited(AgentWidgetEngineException("main: Error: The program is waiting on a promise that can never settle: nothing else is running"))
                }
                return run
            }
        }
        val events = object : AgentWidgetLiveDriver.Events {
            override fun rendered(view: AgentWidgetNode, fullscreen: Boolean?) = Unit
            override fun drew(id: String, commandsJson: String) = Unit
            override fun wantsFrame() = Unit
            override fun answered(response: OpenCodeWidgetResponse) { log += "answered ${(response as OpenCodeWidgetResponse.Submit).json}" }
            override fun sent(message: OpenCodeWidgetMessageValue) = Unit
            override fun failed(error: AgentWidgetEngineException) { log += "failed" }
            override fun reported(phase: String, message: String) = Unit
            override fun ended() { log += "ended" }
        }
        val driver = AgentWidgetLiveDriver(program, "{}", null, scope, events)
        withContext(main) { driver.setVisible(true) }
        assertTrue(eventually(wallClock = true) { log.isNotEmpty() })
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        assertEquals(listOf("""answered {"score":40}"""), log.toList())
        assertTrue(stopped.get())
        assertTrue(!withContext(main) { driver.isRunning })
        scope.cancel()
        main.close()
    }

    /** A restarted draw effect leaves its cancelled decode running; the next one must not share its list. */
    @Test
    fun canvasDecodeCancelledMidWayNeverWritesTheShownList() = runBlocking {
        val buffers = CanvasDrawBuffers()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = launch(Dispatchers.Default) {
            buffers.update<Boolean>({ list ->
                withContext(Dispatchers.IO) {
                    list.clear()
                    list.int(1)
                    started.countDown()
                    release.await()
                    list.int(2)
                    list.int(3)
                    true
                }
            }) { it }
        }
        withContext(Dispatchers.IO) { started.await() }
        slow.cancel()
        val fast = launch(Dispatchers.Default) {
            buffers.update<Boolean>({ list ->
                withContext(Dispatchers.IO) {
                    list.clear()
                    list.int(9)
                    true
                }
            }) { it }
        }
        delay(150)
        release.countDown()
        slow.join()
        fast.join()
        assertEquals(1, buffers.shown.intCount)
        assertEquals(9, buffers.shown.ints[0])
    }

    /** specs/v2/widgets.md, Failures: only the widget template's own errors are reported; the client's are logged. */
    @Test
    fun onlyProgramErrorsAreReported() {
        assertTrue(AgentWidgetEngineException("x is not defined", line = 3, column = 7).isProgramError())
        assertTrue(AgentWidgetEngineException("ran over 50 ms without waiting").isProgramError())
        assertTrue(AgentWidgetNode.InvalidView("a step must return a view").isProgramError())
        val info = OpenCodeWidgetProgram("main()")
        assertTrue(AgentWidgetPrograms.CompileFailed(AgentWidgetEngineException("Unexpected token"), info).isProgramError())
        val unavailable = runCatching { AgentWidgetEngine.Unavailable.compile("main()") }.exceptionOrNull()!!
        assertFalse(unavailable.isProgramError())
        assertFalse(AgentWidgetPrograms.CompileFailed(unavailable as AgentWidgetEngineException, info).isProgramError())
        assertFalse(AgentWidgetEngineException("snapshot failed", isProgramError = false).isProgramError())
        assertFalse(IllegalStateException("The widget's program isn't loaded.").isProgramError())
    }

    /** The position the runtime appends counts the prelude; the report carries the widget template's own line and column. */
    @Test
    fun aReportedMessageDropsTheRuntimesPosition() {
        assertEquals("ReferenceError: Unknown identifier 'x'.", programMessage("ReferenceError: Unknown identifier 'x'. (line 464, col 66)"))
        assertEquals("Unexpected token", programMessage("Unexpected token"))
    }

    @Test
    fun alphaSetsOpacityAndRestoreBringsItBack() {
        val opacity = CanvasOpacity()
        opacity.set(0.5f)
        opacity.save()
        opacity.set(0.8f)
        assertEquals(0.8f, opacity.value)
        assertTrue(opacity.restore())
        assertEquals(0.5f, opacity.value)
        opacity.set(2f)
        assertEquals(1f, opacity.value)
        opacity.set(-1f)
        assertEquals(0f, opacity.value)
        assertTrue(!opacity.restore())
        assertEquals(0f, opacity.value)
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
        // Android: `data` lives in the program's run session (AgentWidgetStep.shared), not in each step's input.
        assertEquals(listOf(Effect.Run("""{"phase":"init"}""")), effects)
        machine.handle(Event.Ran(Result.success(output("""{"n":1}""")))).let { (m, e) -> machine = m; effects = e }
        assertEquals(emptyList<Effect>(), effects)
        assertTrue(machine.phase is Phase.Ready)
        machine.handle(Event.Input(AgentWidgetEvent.press("go"))).let { (m, e) -> machine = m; effects = e }
        assertEquals(listOf(Effect.Run("""{"phase":"event","state":{"n":1},"event":{"id":"go","type":"press"}}""")), effects)
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
                """{"widgetTemplate":"confirm","title":"Deploy?","data":{"title":"Deploy?"}}""",
                metadata = mapOf(
                    "widgetTemplate" to json("""{"name":"confirm","version":"0123456789abcdef"}"""),
                    "widget" to json("""{"status":"submitted","response":{"confirmed":true}}"""),
                ),
            ),
        )!!
        assertEquals("call_1", widget.callID)
        assertEquals(OpenCodeWidgetRef("confirm", "0123456789abcdef"), widget.widgetTemplate)
        assertEquals(AgentWidgetState.Status.Submitted, widget.status)
        assertEquals("""{"confirmed":true}""", widget.response)
        assertEquals("Deploy?", widget.label)
    }

    /** The call names its widget template in the input and, once posted, the pinned version in `metadata.widgetTemplate`. */
    @Test
    fun readsAWidgetCallNamingItsWidgetTemplate() {
        val widget = AgentWidgetAdapter.widget(
            call(
                """{"widgetTemplate":"confirm","data":{"title":"Deploy?"}}""",
                metadata = mapOf("widgetTemplate" to json("""{"name":"confirm","version":"0123456789abcdef"}""")),
            ),
        )!!
        assertEquals(OpenCodeWidgetRef("confirm", "0123456789abcdef"), widget.widgetTemplate)
        assertEquals("confirm", AgentWidgetAdapter.title(call("""{"widgetTemplate":"confirm"}""")))
    }

    @Test
    fun anUnpinnedToolCallAsksForTheLatestWidgetTemplate() {
        val widget = AgentWidgetAdapter.widget(call("""{"widgetTemplate":"form","data":{}}"""))!!
        assertEquals("", widget.widgetTemplate.version)
        assertTrue(widget.widgetTemplate.isValid)
        assertEquals(AgentWidgetState.Status.Open, widget.status)
    }

    @Test
    fun otherCallsAndStreamingInputAreNotWidgets() {
        assertNull(AgentWidgetAdapter.widget(call("""{"widgetTemplate":"form"}""", status = OpenCodeToolCall.Status.Streaming)))
        assertNull(AgentWidgetAdapter.widget(call("""{"widgetTemplate":"../etc"}""")))
        assertNull(AgentWidgetAdapter.widget(call("""{"widgetTemplate":"form"}""", name = "read")))
    }

    @Test
    fun aWidgetCallIsItsOwnRow() {
        val items = listOf(
            OpenCodeTranscriptItem.Tool("t1", call("null", name = "read", callID = "c0")),
            OpenCodeTranscriptItem.Tool("t2", call("""{"widgetTemplate":"form"}""")),
        )
        val entries = TranscriptEntry.build(items)
        assertTrue(entries.any { ((it as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.Tool)?.call?.name == "widget" })
    }

    /** Android: the plugin's state pins the version and says how the widget ended. */
    @Test
    fun aToolCallTakesItsStateFromThePlugin() {
        val tool = OpenCodeTranscriptItem.Tool("msg_a-tool-0", call("""{"widgetTemplate":"confirm","data":{"a":1}}""").copy(messageID = "msg_a"))
        val record = ai.opencode.server.model.OpenCodeWidgetState(
            sessionID = "ses", messageID = "msg_a", callID = "call_1", widgetTemplate = OpenCodeWidgetRef("confirm", "0123456789abcdef"),
            title = "Deploy?", status = ai.opencode.server.model.OpenCodeWidgetStatus.Submitted, response = "true",
        )
        val pinned = AgentWidgetAdapter.states(listOf(tool), mapOf(record.key to record))["msg_a/call_1"]!!
        assertEquals("0123456789abcdef", pinned.widgetTemplate.version)
        assertEquals(AgentWidgetState.Status.Submitted, pinned.status)
        assertEquals("true", pinned.response)
        assertEquals("Deploy?", pinned.label)
        assertEquals("""{"a":1}""", pinned.data)
        assertEquals("msg_a/call_1", AgentWidgetAdapter.key(tool))

        val unpinned = AgentWidgetAdapter.states(listOf(tool))["msg_a/call_1"]!!
        assertEquals("", unpinned.widgetTemplate.version)
        assertEquals(AgentWidgetState.Status.Open, unpinned.status)
    }

    /** Android: specs/v2/widgets-fullscreen.md: the root's `fullscreen`, `fill` canvases, and the event a widget template hears. */
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
        // widgets-fullscreen.md, Telling the program: the close button's area in root coordinates, null on leaving.
        assertEquals(
            """{"id":"","type":"fullscreen","value":true,"close":{"x":339,"y":8,"width":44,"height":44}}""",
            AgentWidgetEvent.fullscreen(true, AgentWidgetRect(339f, 8f, 44f, 44f)).json,
        )
        assertEquals("""{"id":"","type":"fullscreen","value":false,"close":null}""", AgentWidgetEvent.fullscreen(false, null).json)
        // An absolute pointer value carries the area over its node, and leaves it out when there is none.
        assertEquals(
            """{"id":"0/:board","type":"pointer","value":{"kind":"down","x":1,"y":2,"width":391,"height":700,"close":{"x":339,"y":-4.5,"width":44,"height":44}}}""",
            AgentWidgetEvent.pointer("0/:board", "down", 1f, 2f, 391f, 700f, AgentWidgetRect(339f, -4.5f, 44f, 44f)).json,
        )
        assertEquals(
            """{"id":"0/:board","type":"pointer","value":{"kind":"up","x":1,"y":2,"width":10,"height":10}}""",
            AgentWidgetEvent.pointer("0/:board", "up", 1f, 2f, 10f, 10f).json,
        )
        assertEquals("""{"x":338.29,"y":8,"width":44,"height":44}""", AgentWidgetRect(338.2857f, 8f, 44f, 44f).json)
    }

    @Test
    fun keyboardEventsAndQueuedReplacement() {
        // widgets-input.md: keys and focus belong to no node.
        assertEquals("""{"id":"","type":"focus","value":false}""", AgentWidgetEvent.focus(false).json)
        assertEquals(
            """{"id":"","type":"key","value":{"kind":"down","key":"a","code":"KeyA","repeat":false,"shift":false,"alt":false,"ctrl":false,"meta":false}}""",
            AgentWidgetEvent.key("down", "a", "KeyA", repeat = false, shift = false, alt = false, ctrl = false, meta = false).json,
        )
        assertEquals("KeyA", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_A))
        assertEquals("Digit7", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_7))
        assertEquals("ArrowLeft", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Space", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_SPACE))
        assertEquals("ShiftLeft", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_SHIFT_LEFT))
        // Back is never a widget key: it always closes full screen.
        assertNull(AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_BACK))
        assertNull(AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_VOLUME_UP))

        // A queued event is replaced only by a newer value of the same control or a newer move of the same press.
        val down = AgentWidgetEvent.pointer("0/:board", "down", 1f, 2f, 10f, 10f)
        val move = AgentWidgetEvent.pointer("0/:board", "move", 3f, 4f, 10f, 10f)
        assertTrue(!down.replaceable(move))
        assertTrue(move.replaceable(AgentWidgetEvent.pointer("0/:board", "move", 5f, 6f, 10f, 10f)))
        assertTrue(!move.replaceable(AgentWidgetEvent.pointer("0/:board", "up", 5f, 6f, 10f, 10f)))
        assertTrue(AgentWidgetEvent.change("0/1", "a").replaceable(AgentWidgetEvent.change("0/1", "ab")))
        val keyDown = AgentWidgetEvent.key("down", "a", "KeyA", repeat = false, shift = false, alt = false, ctrl = false, meta = false)
        assertTrue(!keyDown.replaceable(AgentWidgetEvent.key("up", "a", "KeyA", repeat = false, shift = false, alt = false, ctrl = false, meta = false)))
        assertTrue(!AgentWidgetEvent.press("0/2").replaceable(AgentWidgetEvent.press("0/2")))
    }
}
