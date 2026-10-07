@file:PortedFrom("OpenCodeTests/SessionSheetTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.uuid
import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.TestClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.UUIDGenerator
import ai.opencode.mobilecode.tca.expect
import java.io.File
import kotlin.io.use
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SessionSheetTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")

    private fun session(id: String = "ses_sheet", title: String = "Sheet", parentID: String? = null, cost: Double? = null) = OpenCodeSession(
        id = id,
        title = title,
        projectID = "prj",
        directory = "/work/app",
        updatedAt = 100_000,
        parentID = parentID,
        cost = cost,
    )

    private fun store(
        renameSession: suspend (OpenCodeServer, String, String) -> Unit = OpenCodeClient.testValue.renameSession,
        getSession: suspend (OpenCodeServer, String) -> OpenCodeSession = OpenCodeClient.testValue.getSession,
        compactSession: suspend (OpenCodeServer, String) -> Unit = OpenCodeClient.testValue.compactSession,
        forkSession: suspend (OpenCodeServer, String) -> OpenCodeSession = OpenCodeClient.testValue.forkSession,
        exportSession: suspend (OpenCodeServer, String) -> ByteArray = OpenCodeClient.testValue.exportSession,
        clock: TestClock? = null,
        uuid: UUIDGenerator? = null,
    ) = TestStore(
        initialState = { SessionSheetFeature.State(server, "ses_sheet") },
        withDependencies = {
            it.openCodeClient = testOpenCodeClient(
                renameSession = renameSession,
                getSession = getSession,
                compactSession = compactSession,
                forkSession = forkSession,
                exportSession = exportSession,
            )
            if (clock != null) it.continuousClock = clock
            if (uuid != null) it.uuid = uuid
        },
    ) { SessionSheetFeature() }

    @Ignore("SessionFeature.kt")
    @Test
    fun contextFractionUsesTheReplyModelsWindow() {}

    @Ignore("SessionContextRing.tint reads theme colors in composition")
    @Test
    fun contextRingTintTracksTheThresholds() {}

    @Test
    fun contextDecodingMatchesTheTUI() {
        fun reading(messages: String, next: Boolean = false): OpenCodeContextReading {
            val cursor = if (next) ""","cursor":{"next":"c1"}""" else ""
            return OpenCodeClient.decodeContextUsage("""{"data":[$messages]$cursor}""".toByteArray())
        }
        val tokens = """"tokens":{"input":1000,"output":200,"reasoning":50,"cache":{"read":3000,"write":10}}"""
        val modelJSON = """"model":{"id":"fixture-model","providerID":"fixture"}"""
        // Newest first: a reply still streaming has no usage yet; the one before counts.
        expect(
            reading("""{"type":"assistant",$modelJSON},{"type":"user"},{"type":"assistant",$modelJSON,$tokens}""") ==
                OpenCodeContextReading.measured(OpenCodeContextUsage(4_260, model)),
        )
        // A completed compaction resets the window; a failed one does not.
        expect(reading("""{"type":"compaction","status":"completed"},{"type":"assistant",$modelJSON,$tokens}""") == OpenCodeContextReading.empty)
        expect(
            reading("""{"type":"compaction","status":"failed"},{"type":"assistant",$modelJSON,$tokens}""") ==
                OpenCodeContextReading.measured(OpenCodeContextUsage(4_260, model)),
        )
        // Zero usage reads as nothing, like the TUI's `tokens <= 0`.
        expect(
            reading("""{"type":"assistant",$modelJSON,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}}}""") ==
                OpenCodeContextReading.empty,
        )
        // A window of user messages says nothing unless it reaches the start.
        expect(reading("""{"type":"user"},{"type":"user"}""", next = true) == OpenCodeContextReading.inconclusive)
        expect(reading("""{"type":"user"}""") == OpenCodeContextReading.empty)
        expect(runCatching { OpenCodeClient.decodeContextUsage("[]".toByteArray()) }.exceptionOrNull() is OpenCodeClientError)
    }

    @Ignore("SessionFeature.kt")
    @Test
    fun pageRefreshReadsContextAndInconclusiveReadsKeepIt() {}

    @Ignore("SessionFeature.kt")
    @Test
    fun onlyAnEndedStepRereadsContextWithItsSettlement() {}

    @Ignore("SessionFeature.kt")
    @Test
    fun switchingFamilyMemberForgetsTheOtherSessionsContext() {}

    @Ignore("SessionFeature.kt")
    @Test
    fun rootCostIncludesSubagentsAndChildCostIsItsOwn() {}

    @Test
    fun renameTrimsPatchesAndUpdatesTheTitle() {
        val patched = mutableListOf<String>()
        val store = store(renameSession = { _, id, title ->
            expect(id == "ses_sheet")
            patched += title
        })
        store.send(SessionSheetFeature.Action.renameTapped("Sheet")) {
            it.renameDraft = "Sheet"
            it.isRenaming = true
        }
        store.send(SessionSheetFeature.Action.binding(BindingAction.set(SessionSheetFeature.State::renameDraft, "  Ship the sheet \n"))) {
            it.renameDraft = "  Ship the sheet \n"
        }
        store.send(SessionSheetFeature.Action.renameSubmitted) {
            it.isRenaming = false
            it.operation = SessionSheetFeature.Operation.renaming
        }
        store.receive<SessionSheetFeature.Action.renameFinished> { it.operation = null }
        store.receive(SessionSheetFeature.Action.delegate(SessionSheetFeature.Delegate.renamed("Ship the sheet")))
        expect(patched == listOf("Ship the sheet"))

        // A blank title is not sent.
        store.send(SessionSheetFeature.Action.renameTapped("Ship the sheet")) {
            it.renameDraft = "Ship the sheet"
            it.isRenaming = true
        }
        store.send(SessionSheetFeature.Action.binding(BindingAction.set(SessionSheetFeature.State::renameDraft, "   "))) {
            it.renameDraft = "   "
        }
        store.send(SessionSheetFeature.Action.renameSubmitted) { it.isRenaming = false }
        expect(patched.size == 1)
    }

    @Test
    fun autoRenameAsksTheServerForATitleAndShowsIt() {
        val patched = mutableListOf<String>()
        val generated = session(title = "Plan the boys’ dinner")
        val store = store(
            renameSession = { _, _, title -> patched += title },
            getSession = { _, _ -> generated },
        )
        store.send(SessionSheetFeature.Action.renameTapped("Sheet")) {
            it.renameDraft = "Sheet"
            it.isRenaming = true
        }
        store.send(SessionSheetFeature.Action.autoRenameTapped) {
            it.isRenaming = false
            it.operation = SessionSheetFeature.Operation.autoRenaming
        }
        store.receive<SessionSheetFeature.Action.renameFinished> { it.operation = null }
        store.receive(SessionSheetFeature.Action.delegate(SessionSheetFeature.Delegate.renamed("Plan the boys’ dinner")))
        // The empty title is the server's cue to generate one.
        expect(patched == listOf(""))
    }

    @Test
    fun failedRenameKeepsTheTitleAndExplains() {
        val store = store(renameSession = { _, _, _ -> throw OpenCodeClientError(message = "Offline") })
        store.send(SessionSheetFeature.Action.renameTapped("Sheet")) {
            it.renameDraft = "Sheet"
            it.isRenaming = true
        }
        store.send(SessionSheetFeature.Action.binding(BindingAction.set(SessionSheetFeature.State::renameDraft, "New"))) {
            it.renameDraft = "New"
        }
        store.send(SessionSheetFeature.Action.renameSubmitted) {
            it.isRenaming = false
            it.operation = SessionSheetFeature.Operation.renaming
        }
        store.receive<SessionSheetFeature.Action.renameFinished> {
            it.operation = null
            it.failure = SessionSheetFeature.Failure("Couldn’t Rename Session", "Offline")
        }
        store.send(SessionSheetFeature.Action.failureDismissed) { it.failure = null }
    }

    @Test
    fun failedAutoRenameKeepsTheTitleAndSaysSoBriefly() {
        val clock = TestClock()
        val store = store(
            renameSession = { _, _, _ -> throw OpenCodeClientError(message = "Offline") },
            clock = clock,
        )
        store.send(SessionSheetFeature.Action.renameTapped("Sheet")) {
            it.renameDraft = "Sheet"
            it.isRenaming = true
        }
        store.send(SessionSheetFeature.Action.autoRenameTapped) {
            it.isRenaming = false
            it.operation = SessionSheetFeature.Operation.autoRenaming
        }
        // No alert: the shimmer stops, the old title stays, and its line says so.
        store.receive<SessionSheetFeature.Action.renameFinished> {
            it.operation = null
            it.autoRenameFailed = true
        }
        expect(store.state.failure == null)
        clock.advance(by = 2.2.seconds)
        store.receive(SessionSheetFeature.Action.autoRenameFailureCleared) { it.autoRenameFailed = false }
    }

    @Test
    fun aRenameForAnotherSessionIsIgnored() {
        val store = store()
        // Finished after the sheet moved to another session: it must not rename this one.
        store.send(SessionSheetFeature.Action.renameFinished("Somebody else's title", null, "ses_other"))
        expect(store.state.sessionID == "ses_sheet")
        expect(store.state.operation == null)
        expect(store.state.failure == null)
    }

    @Ignore("SessionFeature.kt")
    @Test
    fun compactQueuesThenClosesTheSheetToShowTheCompactionRow() {}

    @Test
    fun compactClearsTheOperationAndDelegates() {
        val compacted = mutableListOf<String>()
        val store = store(compactSession = { _, id ->
            expect(id == "ses_sheet")
            compacted += id
        })
        store.send(SessionSheetFeature.Action.compactTapped) { it.operation = SessionSheetFeature.Operation.compacting }
        store.receive<SessionSheetFeature.Action.compactFinished> { it.operation = null }
        store.receive(SessionSheetFeature.Action.delegate(SessionSheetFeature.Delegate.compacted))
        expect(compacted == listOf("ses_sheet"))
    }

    @Test
    fun failedCompactionStaysOpenWithAnError() {
        val store = store(compactSession = { _, _ -> throw OpenCodeClientError(message = "Nothing to compact", statusCode = 400) })
        store.send(SessionSheetFeature.Action.compactTapped) { it.operation = SessionSheetFeature.Operation.compacting }
        store.receive<SessionSheetFeature.Action.compactFinished> {
            it.operation = null
            it.failure = SessionSheetFeature.Failure("Couldn’t Compact Session", "Nothing to compact")
        }
    }

    @Test
    fun forkOpensTheNewSession() {
        val forked = session("ses_fork", title = "Sheet (fork)")
        val store = store(forkSession = { _, id ->
            expect(id == "ses_sheet")
            forked
        })
        store.send(SessionSheetFeature.Action.forkTapped) { it.operation = SessionSheetFeature.Operation.forking }
        store.receive<SessionSheetFeature.Action.forkFinished> { it.operation = null }
        store.receive(SessionSheetFeature.Action.delegate(SessionSheetFeature.Delegate.forked(forked)))
    }

    @Test
    fun failedForkStaysPut() {
        val store = store(forkSession = { _, _ -> throw OpenCodeClientError(message = "Session not found", statusCode = 404) })
        store.send(SessionSheetFeature.Action.forkTapped) { it.operation = SessionSheetFeature.Operation.forking }
        store.receive<SessionSheetFeature.Action.forkFinished> {
            it.operation = null
            it.failure = SessionSheetFeature.Failure("Couldn’t Fork Session", "Session not found")
        }
    }

    @Test
    fun exportWritesAShareableFile() {
        for (format in OpenCodeExportFormat.entries) exportWrites(format)
    }

    private fun exportWrites(format: OpenCodeExportFormat) {
        val store = store(exportSession = { _, _ -> transfer.toByteArray() }, uuid = UUIDGenerator.incrementing())
        store.send(SessionSheetFeature.Action.exportTapped(format, "Fix: the / sheet")) {
            it.operation = SessionSheetFeature.Operation.exporting(format)
        }
        val url = File(sessionExportRoot(), "Exports/00000000-0000-0000-0000-000000000000/Fix the sheet.${format.fileExtension}")
        store.receive<SessionSheetFeature.Action.exportFinished> {
            it.operation = null
            it.exportedFile = SessionSheetFeature.ExportedFile(url)
        }
        val content = url.readText()
        when (format) {
            OpenCodeExportFormat.markdown -> {
                expect(content.startsWith("# Export me\n\n**Session ID:** ses_sheet"))
                expect(content.contains("## User\n\nPlease summarize"))
                expect(content.contains("## Assistant\n\nHere it is.\n\n**Tool: read**"))
                expect(!content.contains("private reasoning"))
            }
            OpenCodeExportFormat.json -> {
                val object_ = protocolJson.parseToJsonElement(content).jsonObject
                expect((object_["info"] as? kotlinx.serialization.json.JsonObject)?.get("id")?.toString()?.trim('"') == "ses_sheet")
                expect((object_["messages"] as? kotlinx.serialization.json.JsonArray)?.size == 2)
            }
        }
        store.send(SessionSheetFeature.Action.exportDismissed) { it.exportedFile = null }
        store.finish()
        expect(!url.exists())
    }

    @Test
    fun exportFailuresExplain() {
        val bodies = ArrayDeque(
            listOf(
                Result.failure(OpenCodeClientError(message = "Offline")),
                Result.success("{}".toByteArray()),
            ),
        )
        val store = store(
            exportSession = { _, _ -> bodies.removeFirst().getOrThrow() },
            uuid = UUIDGenerator.incrementing(),
        )
        for (message in listOf("Offline", "The export couldn’t be saved.")) {
            store.send(SessionSheetFeature.Action.exportTapped(OpenCodeExportFormat.markdown, "Sheet")) {
                it.operation = SessionSheetFeature.Operation.exporting(OpenCodeExportFormat.markdown)
            }
            store.receive<SessionSheetFeature.Action.exportFinished> {
                it.operation = null
                it.failure = SessionSheetFeature.Failure("Couldn’t Export Session", message)
            }
            store.send(SessionSheetFeature.Action.failureDismissed) { it.failure = null }
        }
    }

    @Test
    fun listActionsAndDeletionAreDelegatedToHome() {
        val store = store()
        store.send(SessionSheetFeature.Action.pinTapped)
        store.receive(SessionSheetFeature.Action.delegate(SessionSheetFeature.Delegate.togglePin))
        store.send(SessionSheetFeature.Action.readTapped)
        store.receive(SessionSheetFeature.Action.delegate(SessionSheetFeature.Delegate.toggleRead))
        store.send(SessionSheetFeature.Action.deleteTapped) { it.confirmsDeletion = true }
        store.send(SessionSheetFeature.Action.deleteConfirmed) { it.confirmsDeletion = false }
        store.receive(SessionSheetFeature.Action.delegate(SessionSheetFeature.Delegate.deleteSession))
    }

    @Ignore("SessionFeature.kt")
    @Test
    fun chooseModelSwapsTheSheetForThePicker() {}

    // homeOpensForksAndRoutesSheetActionsThroughTheDrawerPaths is in SessionSheetHomeTests below:
    // Home's reducer marks LabMarks, which needs Robolectric's android.util.Log.

    @Test
    fun headerProjectSkipsTheGlobalProject() {
        val projects = listOf(
            OpenCodeProject(id = "global", name = "Global", directory = "/"),
            OpenCodeProject(id = "prj", name = "opencode-ios", directory = "/work/app"),
        )
        expect(SessionListContext.project(session(), projects)?.name == "opencode-ios")
        val global = session().copy(projectID = "global")
        expect(SessionListContext.project(global, projects) == null)
        expect(SessionListContext.project(null, projects) == null)
    }

    @Test
    fun sheetRoutesMatchTheV2Contract() = runBlocking {
        val info = """{"id":"ses_fork","projectID":"prj","title":"Forked","cost":1.5,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/work/app"}}"""
        val fixture = WireFixture { request ->
            when (request.route) {
                "PATCH /api/session/ses_1" -> WireFixture.Reply.status(204)
                "POST /api/session/ses_1/compact" -> WireFixture.Reply.json(200, """{"data":{"id":"msg_c","sessionID":"ses_1","time":{"created":1},"type":"compaction","payload":{},"delivery":"steer"}}""")
                "POST /api/session/ses_1/fork" -> WireFixture.Reply.json(200, """{"data":$info}""")
                "GET /api/experimental/session/ses_1/export" -> WireFixture.Reply.json(200, transfer)
                "GET /api/session/ses_1/message" -> WireFixture.Reply.json(
                    200,
                    """{"data":[{"type":"assistant","model":{"id":"m","providerID":"p"},"tokens":{"input":5,"output":1,"reasoning":0,"cache":{"read":4,"write":0}}}]}""",
                )
                else -> WireFixture.Reply.status(404)
            }
        }
        fixture.use {
            val client = OpenCodeClient.liveValue
            client.renameSession(fixture.server, "ses_1", "New title")
            client.compactSession(fixture.server, "ses_1")
            val forked = client.forkSession(fixture.server, "ses_1")
            val exported = client.exportSession(fixture.server, "ses_1")
            val context = client.contextUsage(fixture.server, "ses_1")
            expect(forked.id == "ses_fork" && forked.cost == 1.5 && forked.directory == "/work/app")
            expect(exported.contentEquals(transfer.toByteArray()))
            expect(context == OpenCodeContextReading.measured(OpenCodeContextUsage(10, OpenCodeModelRef(id = "m", providerID = "p"))))
            val requests = fixture.requests
            expect(
                requests.map { it.route } == listOf(
                    "PATCH /api/session/ses_1",
                    "POST /api/session/ses_1/compact",
                    "POST /api/session/ses_1/fork",
                    "GET /api/experimental/session/ses_1/export",
                    "GET /api/session/ses_1/message",
                ),
            )
            expect(requests[0].json() == protocolJson.parseToJsonElement("""{"title":"New title"}""").jsonObject)
            expect(requests[1].json() == JsonObject(emptyMap()))
            expect(requests[2].json() == JsonObject(emptyMap()))
            expect(requests[4].queryValue("order") == "desc")
            expect(requests[4].queryValue("limit") == "8")
        }
    }

    @Test
    fun modelCatalogCarriesTheContextWindow() = runBlocking {
        val fixture = WireFixture { request ->
            when (request.path) {
                "/api/model" -> WireFixture.Reply.json(
                    200,
                    """{"data":[{"id":"alias","modelID":"api-model","providerID":"p","name":"Model","enabled":true,"status":"active","limit":{"context":400000,"output":32000}},{"id":"same","modelID":"same","providerID":"p","name":"Same","enabled":true,"status":"active","limit":{"context":0,"output":1}}]}""",
                )
                else -> WireFixture.Reply.json(200, """{"data":null}""")
            }
        }
        fixture.use {
            val catalog = OpenCodeClient.liveValue.modelCatalog(fixture.server, "/work/app")
            expect(catalog.models.map { it.contextLimit } == listOf(400_000, null))
            expect(catalog.models.map { it.catalogID } == listOf("alias", null))
            expect(catalog.models[0].matches(OpenCodeModelRef(id = "alias", providerID = "p")))
            expect(catalog.models[0].matches(OpenCodeModelRef(id = "api-model", providerID = "p")))
            expect(!catalog.models[0].matches(OpenCodeModelRef(id = "alias", providerID = "other")))
        }
    }

    private val transfer = """
        {"data":{"info":{"id":"ses_sheet","title":"Export me","time":{"created":1000,"updated":2000}},"messages":[
          {"id":"msg_1","type":"user","text":"Please summarize"},
          {"id":"msg_2","type":"assistant","content":[
            {"type":"reasoning","text":"private reasoning"},
            {"type":"text","text":"Here it is."},
            {"type":"tool","name":"read","state":{"status":"completed","input":{"path":"a.swift"},"content":[{"type":"text","text":"ok"}]}}
          ]}
        ]}}
    """.trimIndent()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionSheetHomeTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String = "ses_sheet", title: String = "Sheet") =
        OpenCodeSession(id = id, title = title, projectID = "prj", directory = "/work/app", updatedAt = 100_000)

    // MARK: Home

    @Test
    fun homeOpensForksAndRoutesSheetActionsThroughTheDrawerPaths() {
        val open = session()
        val forked = session("ses_fork", title = "Sheet (fork)")
        val state = HomeFeature.State(server = server, snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open), activeSessionIDs = emptySet()))
        state.sessionDetail = SessionFeature.State(server = server, session = open, cachedItems = emptyList())
        state.markReadLocally(open)
        state.transcriptCache += (forked.id to emptyList())
        val deleted = mutableListOf<String>()
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.continuousClock = ImmediateClock()
                it.openCodeClient.viewSession = { _, _, _ -> }
                it.openCodeClient.deleteSession = { _, id -> deleted += id }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(sessionAction(SessionFeature.Action.delegate(SessionFeature.Delegate.togglePin(open.id))))
        store.receive<HomeFeature.Action.togglePin>()
        expect(store.state.isPinned(open.id))
        store.send(sessionAction(SessionFeature.Action.delegate(SessionFeature.Delegate.toggleRead(open.id))))
        store.receive<HomeFeature.Action.markUnread>()
        expect(store.state.isUnread(open))

        store.send(sessionAction(SessionFeature.Action.delegate(SessionFeature.Delegate.openSession(forked))))
        store.receive<HomeFeature.Action.sessionTapped>()
        expect(store.state.sessions.map { it.id } == listOf(forked.id, open.id))
        expect(store.state.sessionDetail?.session?.id == forked.id)

        store.send(sessionAction(SessionFeature.Action.delegate(SessionFeature.Delegate.deleteSession(forked.id))))
        store.receive<HomeFeature.Action.deleteSessionConfirmed>()
        store.receive<HomeFeature.Action.deleteSessionResponse>()
        expect(deleted == listOf(forked.id))
        expect(store.state.sessions.map { it.id } == listOf(open.id))
        expect(store.state.sessionDetail?.isDraft == true)
    }
}
