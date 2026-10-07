@file:PortedFrom("OpenCodeTests/ProblemReportTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DateGenerator
import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.TestClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.UUIDGenerator
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.uuid
import ai.opencode.mobilecode.tca.withLock
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun temporaryDirectory(): File {
    val directory = File(System.getProperty("java.io.tmpdir"), "reports-test-${UUID.randomUUID()}")
    directory.mkdirs()
    return directory
}

private fun event(
    name: String,
    at: Double = 0.0,
    wall: Instant = Instant.ofEpochSecond(1_000),
    kind: DiagnosticsEvent.Kind = DiagnosticsEvent.Kind.mark,
) = DiagnosticsEvent(id = UUID.randomUUID(), kind = kind, monotonicSeconds = at, wallDate = wall, name = name)

private fun <T> require(value: T?, comment: String? = null): T {
    if (value == null) throw AssertionError(comment ?: "Required value was null")
    return value
}

class DiagnosticsJournalTests {
    @Test
    fun rotatesWithinTwoSegmentsAndHandsTheTailToTheNextRun() {
        val directory = temporaryDirectory()
        try {
            val journal = DiagnosticsJournal(directory = directory, segmentBytes = 4_000)
            expect(journal.open(recording = true).endedUnexpectedly == false)
            for (index in 0 until 400) journal.append(event("event_$index", at = index.toDouble()))
            journal.flush()

            val files = directory.listFiles()?.toList() ?: emptyList()
            val total = files.sumOf { it.length() }
            expect(total <= 2 * 4_000)
            val written = journal.currentRunEvents()
            expect(written.lastOrNull()?.name == "event_399")
            expect(written.size < 400)

            val next = DiagnosticsJournal(directory = directory, segmentBytes = 4_000)
            next.open(recording = true)
            expect(next.previousRunEvents().map { it.name } == written.map { it.name })
            expect(next.currentRunEvents().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aRunThatEndsInTheForegroundIsUnexpected() {
        val directory = temporaryDirectory()
        try {
            val first = DiagnosticsJournal(directory = directory)
            first.open(recording = true)
            first.append(event("launched"))
            first.markForeground()
            first.flush()
            expect(DiagnosticsJournal(directory = directory).open(recording = true).endedUnexpectedly)

            val second = DiagnosticsJournal(directory = directory)
            second.open(recording = true)
            second.append(event("launched"))
            second.markForeground()
            second.markBackground()
            expect(DiagnosticsJournal(directory = directory).open(recording = true).endedUnexpectedly == false)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recorderPersistsEventsAndForgetsThemWhenTurnedOff() {
        val directory = temporaryDirectory()
        try {
            val journal = DiagnosticsJournal(directory = directory)
            val recorder = DiagnosticsRecorder(defaults = null, defaultEnabled = true, journal = journal)
            expect(recorder.isEnabled)
            recorder.recordMark("app_launch", sessionID = null)
            journal.flush()
            expect(journal.currentRunEvents().map { it.name } == listOf("app_launch"))

            recorder.setEnabled(false)
            journal.flush()
            expect(journal.currentRunEvents().isEmpty())
            expect(journal.previousRunEvents().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}

class InternalBuildTests {
    @Test
    fun debugAndTestFlightRecordButTheAppStoreDoesNot() {
        expect(InternalBuild.detect(isDebug = true, receiptURL = null))
        expect(InternalBuild.detect(isDebug = false, receiptURL = File("/app/StoreKit/sandboxReceipt")))
        expect(!InternalBuild.detect(isDebug = false, receiptURL = File("/app/StoreKit/receipt")))
        expect(!InternalBuild.detect(isDebug = false, receiptURL = null))
    }

    @Test
    fun recordingDefaultsOnOnlyWhereAsked() {
        expect(DiagnosticsRecorder(defaults = null, defaultEnabled = true).isEnabled)
        expect(!DiagnosticsRecorder(defaults = null).isEnabled)
        val defaults = MemoryUserDefaults()
        defaults.set(false, DiagnosticsRecorder.defaultsKey)
        expect(!DiagnosticsRecorder(defaults = defaults, defaultEnabled = true).isEnabled)
    }

    @Test
    fun voiceExcerptsFollowIncludeTranscripts() {
        val defaults = MemoryUserDefaults()
        val recorder = DiagnosticsRecorder(enabled = true, defaults = defaults, defaultEnabled = true)
        recorder.recordVoice("voice_user_transcript", sessionID = "ses_1", detail = "item=a", excerpt = "word ".repeat(60))
        val first = require(recorder.snapshot().firstOrNull())
        expect(first.kind == DiagnosticsEvent.Kind.voice)
        expect(first.excerpt?.length == DiagnosticsRecorder.excerptLimit)
        expect(first.excerpt?.endsWith("…") == true)
        expect(recorder.exportText().contains("voice_user_transcript item=a “word word"))
        recorder.setIncludesTranscripts(false)
        recorder.recordVoice("voice_user_transcript", sessionID = "ses_1", detail = "item=b", excerpt = "secret")
        val events = recorder.snapshot()
        expect(events.map { it.kind } == listOf(DiagnosticsEvent.Kind.voice, DiagnosticsEvent.Kind.voice))
        expect(events.all { it.excerpt == null })
        expect(!recorder.exportText().contains("secret"))
    }
}

class ProblemReportBundleTests {
    private val device = ProblemReportDevice(
        appVersion = "0.1.0",
        build = "37",
        distribution = "TestFlight",
        system = "iOS 26.0",
        model = "iPhone18,2",
    )

    @Test
    fun bundleCarriesContextTimelineAndScreenshotWithoutSecrets() {
        val context = ProblemReportContext(
            server = ProblemReportBuilder.redactedServer("https://kit:hunter2@box.tailnet.ts.net:4096/api?token=private"),
            screen = "Session",
            sessionID = "ses_abc",
            sessionTitle = "Fix uploads",
            project = "opencode-ios",
            connection = "Connected",
            call = "Reconnecting… · 1 request working",
        )
        expect(context.server == "https://box.tailnet.ts.net:4096")
        val events = listOf(
            event("request_end", at = 1.0, kind = DiagnosticsEvent.Kind.requestEnd),
            event("voice_connected", at = 2.0, kind = DiagnosticsEvent.Kind.voice),
            event("voice_dropped", at = 3.0, kind = DiagnosticsEvent.Kind.voice),
        )
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.callDropped,
            status = ProblemReport.Status.offered,
            note = "It cut out mid-sentence",
            context = context,
            device = device,
            now = Instant.ofEpochSecond(2_000),
            events = events,
            screenshot = ByteArray(1_000) { 1 },
        )
        expect(bundle.files.keys == setOf("timeline.txt", "timeline.json", "screenshot.jpg"))
        expect(bundle.report.highlights.size == 2, "Successful requests aren't highlights")
        val summary = ProblemReportBuilder.summary(bundle.report, hasScreenshot = true)
        for (expected in listOf(
            "Call dropped",
            "> It cut out mid-sentence",
            "0.1.0 (37), TestFlight",
            "iPhone18,2, iOS 26.0",
            "`ses_abc` “Fix uploads” in opencode-ios",
            "Reconnecting… · 1 request working",
            "voice_dropped",
        )) {
            expect(summary.contains(expected), expected)
        }
        val everything = summary + bundle.files.values.joinToString("") { String(it, Charsets.UTF_8) }
        expect(!everything.contains("hunter2"))
        expect(!everything.contains("private"))
        expect(!summary.contains("Live Activities"))
    }

    @Test
    fun summaryNamesTheLiveActivityState() {
        val withActivities = device.copy(liveActivities = "Off in Settings · Couldn’t start: ActivityAuthorizationError.denied")
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.shake,
            status = ProblemReport.Status.offered,
            context = ProblemReportContext(),
            device = withActivities,
            now = Instant.ofEpochSecond(2_000),
            events = emptyList(),
            screenshot = null,
        )
        val summary = ProblemReportBuilder.summary(bundle.report, hasScreenshot = false)
        expect(summary.contains("- Live Activities: Off in Settings · Couldn’t start: ActivityAuthorizationError.denied"))
    }

    @Test
    fun staysWithinTheSizeBudget() {
        val reason = "x".repeat(400)
        val events = List(8_000) { index ->
            event("voice_assistant_done", at = index.toDouble(), kind = DiagnosticsEvent.Kind.voice).copy(reason = reason)
        }
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.crash,
            status = ProblemReport.Status.offered,
            context = ProblemReportContext(),
            device = device,
            now = Instant.ofEpochSecond(2_000),
            events = events,
            previousRun = events,
            screenshot = ByteArray(ProblemReportBuilder.screenshotLimit + 1),
        )
        val total = bundle.files.values.sumOf { it.size }
        expect(total < 2_000_000)
        expect(bundle.files["screenshot.jpg"] == null, "An oversized screenshot is left out")
        expect(bundle.files["previous-run.txt"] != null)
        val json = String(require(bundle.files["timeline.json"]), Charsets.UTF_8)
        expect(json.contains("7999"), "The newest events survive the cut")
    }

    @Test
    fun previousRunIsAttachedOnlyAfterACrashOrWhenRecent() {
        val old = listOf(event("old", wall = Instant.EPOCH))
        val now = Instant.ofEpochSecond(10_000)
        val shake = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.shake,
            status = ProblemReport.Status.composing,
            context = ProblemReportContext(),
            device = device,
            now = now,
            events = emptyList(),
            previousRun = old,
            screenshot = null,
        )
        expect(shake.files["previous-run.txt"] == null)
        val crash = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.crash,
            status = ProblemReport.Status.offered,
            context = ProblemReportContext(),
            device = device,
            now = now,
            events = emptyList(),
            previousRun = old,
            screenshot = null,
        )
        expect(crash.files["previous-run.txt"] != null)
        expect(crash.report.highlights.isEmpty(), "Plain marks aren't highlights")
    }
}

private class FakeServer {
    data class Prompt(
        val sessionID: String,
        val text: String,
        val attachments: List<OpenCodeOutgoingAttachment>,
        val files: List<OpenCodeFileReference>,
        val delivery: OpenCodeDelivery,
        val messageID: String,
        val model: OpenCodeModelRef?,
    )

    val sessions = mutableSetOf<String>()
    val created = mutableListOf<String>()
    val renamed = mutableMapOf<String, String>()
    val models = mutableMapOf<String, OpenCodeModelRef>()
    val uploads = mutableListOf<String>()
    val prompts = mutableListOf<Prompt>()
    val attemptedMessageIDs = mutableListOf<String>()
    var reachable = true
    var promptsFail = false
    var uploadsSupported = true

    fun <T> with(body: (FakeServer) -> T): T = synchronized(this) { body(this) }

    val client: OpenCodeClient
        get() {
            val base = OpenCodeClient.testValue
            val fake = this
            return OpenCodeClient(
                health = base.health,
                redeemPairing = base.redeemPairing,
                homeSnapshot = base.homeSnapshot,
                homeAttention = base.homeAttention,
                transcript = base.transcript,
                message = base.message,
                sendPrompt = { _, sessionID, text, attachments, files, messageID, delivery ->
                    val (reachable, fails, model) = fake.with { server ->
                        server.attemptedMessageIDs += messageID
                        Triple(server.reachable, server.promptsFail, server.models[sessionID])
                    }
                    if (!reachable || fails) throw OpenCodeClientError(message = "Couldn’t reach the OpenCode server")
                    fake.with {
                        it.prompts += Prompt(sessionID, text, attachments, files, delivery, messageID, model)
                    }
                },
                sendDeliveredPrompt = base.sendDeliveredPrompt,
                assistantReply = base.assistantReply,
                uploadFile = { _, _, _, name ->
                    flow {
                        val supported = fake.with { it.uploadsSupported }
                        if (!supported) throw OpenCodeClientError.uploadsUnsupported
                        fake.with { it.uploads += name }
                        emit(OpenCodeUploadEvent.finished("/tmp/uploads/$name"))
                    }
                },
                waitForIdle = base.waitForIdle,
                isSessionActive = base.isSessionActive,
                interrupt = base.interrupt,
                createSession = { _, directory ->
                    val id = fake.with { server ->
                        if (!server.reachable) return@with null
                        val next = "ses_report_${server.created.size + 1}"
                        server.created += next
                        server.sessions += next
                        next
                    } ?: throw OpenCodeClientError(message = "Couldn’t reach the OpenCode server")
                    OpenCodeSession(id = id, title = "New session", projectID = "p", directory = directory, updatedAt = 0)
                },
                deleteSession = base.deleteSession,
                sessionFamily = base.sessionFamily,
                modelCatalog = base.modelCatalog,
                switchModel = { _, id, model -> fake.with { it.models[id] = model } },
                familyPendingRequests = base.familyPendingRequests,
                pendingInputs = base.pendingInputs,
                setPendingInputDelivery = base.setPendingInputDelivery,
                cancelPendingInput = base.cancelPendingInput,
                replyPermission = base.replyPermission,
                replyForm = base.replyForm,
                cancelForm = base.cancelForm,
                getSession = { _, id ->
                    val (reachable, exists) = fake.with { it.reachable to it.sessions.contains(id) }
                    if (!reachable) throw OpenCodeClientError(message = "Couldn’t reach the OpenCode server")
                    if (!exists) throw OpenCodeClientError(message = "Not found", statusCode = 404)
                    OpenCodeSession(id = id, title = "Phone Reports", projectID = "p", directory = "/reports", updatedAt = 0)
                },
                runningShells = base.runningShells,
                shell = base.shell,
                shellOutput = base.shellOutput,
                stopShell = base.stopShell,
                moveToBackground = base.moveToBackground,
                viewSession = base.viewSession,
                contextUsage = base.contextUsage,
                renameSession = { _, id, title -> fake.with { it.renamed[id] = title } },
                compactSession = base.compactSession,
                forkSession = base.forkSession,
                exportSession = base.exportSession,
                historyPage = base.historyPage,
                events = base.events,
            )
        }
}

class PhoneReportsDeliveryTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
    private val device = ProblemReportDevice(
        appVersion = "0.1.0",
        build = "37",
        distribution = "Debug",
        system = "iOS 26.0",
        model = "Simulator",
    )

    private fun savedReport(store: ProblemReportStore, screenshot: Boolean = true): ProblemReport {
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.shake,
            status = ProblemReport.Status.pending,
            note = "Composer jumped",
            context = ProblemReportContext(server = "http://127.0.0.1:4096", screen = "Session", connection = "Connected"),
            device = device,
            now = Instant.ofEpochSecond(2_000),
            events = listOf(event("voice_dropped", kind = DiagnosticsEvent.Kind.voice)),
            screenshot = if (screenshot) byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) else null,
        )
        val pending = bundle.copy(report = bundle.report.copy(status = ProblemReport.Status.pending))
        store.save(pending)
        return pending.report
    }

    @Test
    fun createsPhoneReportsOnceThenReusesItWithQueuedPrompts() = runBlocking {
        val fake = FakeServer()
        val store = ProblemReportStore(temporaryDirectory())
        try {
            val report = savedReport(store)
            var destination = PhoneReportsDestination(projectID = "p", projectName = "reports", directory = "/reports")
            val first = PhoneReportsDelivery.deliver(report, store, server, destination, true, "msg_1", fake.client)
            expect(first.sessionID == "ses_report_1")
            expect(fake.renamed == mapOf("ses_report_1" to "Phone Reports"))
            destination = destination.copy(sessionID = first.sessionID)
            val second = PhoneReportsDelivery.deliver(report, store, server, destination, true, "msg_2", fake.client)
            expect(second.sessionID == "ses_report_1")
            expect(fake.created == listOf("ses_report_1"))
            val prompt = require(fake.prompts.firstOrNull())
            expect(prompt.delivery == OpenCodeDelivery.queue)
            expect(prompt.text.contains("Composer jumped"))
            expect(prompt.attachments.map { it.mime } == listOf("image/jpeg"))
            expect(prompt.files.map { it.mime } == listOf("text/plain", "application/json"))
            expect(prompt.files.all { it.path.startsWith("/tmp/uploads/phone-report-") })
        } finally {
            store.root.deleteRecursively()
        }
    }

    @Test
    fun reportsRunOnTheModelOfTheSessionTheyAreAbout() = runBlocking {
        val fake = FakeServer()
        val store = ProblemReportStore(temporaryDirectory())
        try {
            var report = savedReport(store)
            val picked = OpenCodeModelRef(id = "model-a", providerID = "provider-a")
            report = report.copy(context = report.context.copy(model = picked))
            var destination = PhoneReportsDestination(projectID = "p", projectName = "reports", directory = "/reports")
            val first = PhoneReportsDelivery.deliver(report, store, server, destination, true, "msg_1", fake.client)
            expect(fake.prompts.lastOrNull()?.model == picked, "A new Phone Reports gets the model before the report")
            fake.with { it.models[first.sessionID] = OpenCodeModelRef(id = "model-default", providerID = "provider-c") }
            destination = destination.copy(sessionID = first.sessionID)
            PhoneReportsDelivery.deliver(report, store, server, destination, true, "msg_2", fake.client)
            expect(fake.prompts.lastOrNull()?.model == picked)
            report = report.copy(context = report.context.copy(model = null))
            PhoneReportsDelivery.deliver(report, store, server, destination, true, "msg_3", fake.client)
            expect(fake.prompts.lastOrNull()?.model == picked)
            expect(fake.created == listOf(first.sessionID))
        } finally {
            store.root.deleteRecursively()
        }
    }

    @Test
    fun recreatesADeletedSession() = runBlocking {
        val fake = FakeServer()
        val store = ProblemReportStore(temporaryDirectory())
        try {
            val report = savedReport(store)
            val destination = PhoneReportsDestination(projectID = "p", projectName = "reports", directory = "/reports", sessionID = "ses_deleted")
            val receipt = PhoneReportsDelivery.deliver(report, store, server, destination, true, "msg_1", fake.client)
            expect(receipt.sessionID == "ses_report_1")
            expect(fake.prompts.map { it.sessionID } == listOf("ses_report_1"))
        } finally {
            store.root.deleteRecursively()
        }
    }

    @Test
    fun inlinesTheTimelineWhenTheServerTakesNoUploads() = runBlocking {
        val fake = FakeServer()
        fake.uploadsSupported = false
        val store = ProblemReportStore(temporaryDirectory())
        try {
            val report = savedReport(store, screenshot = false)
            val destination = PhoneReportsDestination(projectID = "p", projectName = "reports", directory = "/reports")
            val receipt = PhoneReportsDelivery.deliver(report, store, server, destination, true, "msg_1", fake.client)
            expect(receipt.uploadsUnsupported)
            val prompt = require(fake.prompts.firstOrNull())
            expect(prompt.files.isEmpty())
            expect(prompt.attachments.isEmpty())
            expect(prompt.text.contains("```text\nOpenCode diagnostics"))
            expect(prompt.text.contains("- Screenshot: none"))
        } finally {
            store.root.deleteRecursively()
        }
    }
}

class ProblemReportsFeatureTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
    private val project = OpenCodeProject(id = "p", name = "reports", directory = "/reports")
    private val now = Instant.ofEpochSecond(2_000)

    private fun makeStore(fake: FakeServer, reports: ProblemReportStore): TestStore<ProblemReportsFeature.State, ProblemReportsFeature.Action> {
        val store = TestStore(
            initialState = {
                ProblemReportsFeature.State().apply {
                    server = this@ProblemReportsFeatureTests.server
                    projects = listOf(project)
                }
            },
            withDependencies = {
                it.problemReportStore = reports
                it.openCodeClient = fake.client
                it.uuid = UUIDGenerator.incrementing()
                it.continuousClock = TestClock()
                it.date = DateGenerator.constant(now)
                it.diagnostics.timeline = { listOf(event("voice_dropped", kind = DiagnosticsEvent.Kind.voice)) }
            },
        ) { ProblemReportsFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        return store
    }

    @Test
    fun firstReportAsksForAProjectThenSendsAndClosesTheSheet() {
        val fake = FakeServer()
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val store = makeStore(fake, reports)
            store.state::destinations.withLock { value = emptyMap() }
            store.send(ProblemReportsFeature.Action.reportRequested(ProblemReportTrigger.shake, byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
            store.receive<ProblemReportsFeature.Action.composerPrepared>()
            expect(store.state.composer?.isReady == true)
            expect(reports.all().map { it.status } == listOf(ProblemReport.Status.composing))
            store.send(ProblemReportsFeature.Action.noteChanged("Send stuck"))
            store.send(ProblemReportsFeature.Action.sendTapped)
            expect(store.state.composer?.isPickingProject == true)
            store.send(ProblemReportsFeature.Action.projectPicked(project))
            store.receive(ProblemReportsFeature.Action.sendTapped)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            expect(store.state.composer == null)
            expect(store.state.notice?.kind == ProblemReportsFeature.State.Notice.Kind.sent("ses_report_1"))
            expect(reports.all().isEmpty(), "Delivered reports leave the phone")
            expect(store.state.destination?.sessionID == "ses_report_1")
            expect(fake.prompts.firstOrNull()?.text?.contains("> Send stuck") == true)
            expect(fake.prompts.firstOrNull()?.delivery == OpenCodeDelivery.queue)
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun unreachableServerKeepsTheReportAndRetriesOnReconnect() {
        val fake = FakeServer()
        fake.reachable = false
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val store = makeStore(fake, reports)
            val key = server.persistenceID
            store.state::destinations.withLock {
                value = mapOf(key to PhoneReportsDestination(projectID = "p", projectName = "reports", directory = "/reports"))
            }
            store.send(ProblemReportsFeature.Action.reportRequested(ProblemReportTrigger.settings, null))
            store.receive<ProblemReportsFeature.Action.composerPrepared>()
            store.send(ProblemReportsFeature.Action.sendTapped)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            expect(store.state.composer == null)
            expect(store.state.notice?.kind == ProblemReportsFeature.State.Notice.Kind.waiting)
            val saved = require(reports.all().firstOrNull())
            expect(saved.status == ProblemReport.Status.pending)
            expect(saved.attempts == 1)
            fake.with { it.reachable = true }
            store.send(ProblemReportsFeature.Action.serverReachable)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            expect(reports.all().isEmpty())
            expect(fake.prompts.size == 1)
            expect(fake.prompts.firstOrNull()?.text?.contains("Delivery attempts before this one: 1") == true)
            expect(store.state.notice?.kind == ProblemReportsFeature.State.Notice.Kind.delivered("ses_report_1"))
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun troubleOffersAReportOnceAndDismissingDeletesIt() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val store = makeStore(FakeServer(), reports)
            store.send(ProblemReportsFeature.Action.troubleDetected(ProblemReportTrigger.callDropped))
            store.receive<ProblemReportsFeature.Action.offerPrepared>()
            val offer = require(store.state.offer)
            expect(offer.trigger == ProblemReportTrigger.callDropped)
            expect(reports.all().map { it.status } == listOf(ProblemReport.Status.offered))
            store.send(ProblemReportsFeature.Action.offerDismissed)
            store.send(ProblemReportsFeature.Action.troubleDetected(ProblemReportTrigger.callDropped))
            expect(store.state.offer == null)
            expect(reports.all().isEmpty())
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun anOfferOvertakenWhileSavingLeavesNothingBehind() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val store = makeStore(FakeServer(), reports)
            fun saved(): ProblemReport {
                val bundle = ProblemReportBuilder.make(
                    id = UUID.randomUUID(),
                    trigger = ProblemReportTrigger.callDropped,
                    status = ProblemReport.Status.offered,
                    context = ProblemReportContext(),
                    device = ProblemReportDevice.current,
                    now = now,
                    events = emptyList(),
                    screenshot = null,
                )
                reports.save(bundle)
                return bundle.report
            }
            store.send(ProblemReportsFeature.Action.reportRequested(ProblemReportTrigger.shake, null))
            store.receive<ProblemReportsFeature.Action.composerPrepared>()
            val overtaken = saved()
            store.send(ProblemReportsFeature.Action.offerPrepared(overtaken))
            expect(store.state.offer == null)
            expect(reports.load(overtaken.id) == null)
            store.send(ProblemReportsFeature.Action.cancelTapped)
            val first = saved()
            store.send(ProblemReportsFeature.Action.offerPrepared(first))
            val second = saved()
            store.send(ProblemReportsFeature.Action.offerPrepared(second))
            expect(store.state.offer?.reportID == first.id)
            expect(reports.all().map { it.id } == listOf(first.id))
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun aStallCountsOnlyAfterTimeInTheForeground() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val diagnostics = DiagnosticsClient(
                voice = { _, _, _, _ -> },
                timeline = { emptyList() },
                takeUnexpectedEnd = { false },
                previousRunEvents = { emptyList() },
                foregroundSeconds = { 30.0 },
                liveActivities = { null },
            )
            val store = TestStore(
                initialState = {
                    ProblemReportsFeature.State().apply { server = this@ProblemReportsFeatureTests.server }
                },
                withDependencies = {
                    it.problemReportStore = reports
                    it.openCodeClient = FakeServer().client
                    it.uuid = UUIDGenerator.incrementing()
                    it.continuousClock = TestClock()
                    it.date = DateGenerator.constant(now)
                    it.diagnostics = diagnostics
                },
            ) { ProblemReportsFeature() }
            store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            store.send(ProblemReportsFeature.Action.troubleDetected(ProblemReportTrigger.streamStalled))
            expect(store.state.lastOfferAt == null)
            diagnostics.foregroundSeconds = { 600.0 }
            store.send(ProblemReportsFeature.Action.troubleDetected(ProblemReportTrigger.streamStalled))
            store.receive<ProblemReportsFeature.Action.offerPrepared>()
            expect(store.state.offer?.trigger == ProblemReportTrigger.streamStalled)
        } finally {
            reports.root.deleteRecursively()
        }
    }
}

class DiagnosticsPrivacyTests {
    @Test
    fun turningTranscriptsOffForgetsEveryExcerpt() {
        val directory = temporaryDirectory()
        try {
            val earlier = DiagnosticsJournal(directory = directory, build = "1 (1)")
            earlier.open(recording = true)
            earlier.append(event("voice_user_transcript", kind = DiagnosticsEvent.Kind.voice).copy(excerpt = "my bank password is hunter2"))
            earlier.flush()
            val journal = DiagnosticsJournal(directory = directory, build = "1 (1)")
            val defaults = MemoryUserDefaults()
            val recorder = DiagnosticsRecorder(defaults = defaults, defaultEnabled = true, journal = journal)
            recorder.recordVoice("voice_user_transcript", sessionID = null, excerpt = "call my mother")
            journal.flush()
            expect(recorder.snapshot().firstOrNull()?.excerpt == "call my mother")
            expect(recorder.previousRunEvents().firstOrNull()?.excerpt == "my bank password is hunter2")
            recorder.setIncludesTranscripts(false)
            journal.flush()
            expect(recorder.snapshot().all { it.excerpt == null })
            expect(recorder.previousRunEvents().all { it.excerpt == null })
            expect(journal.currentRunEvents().all { it.excerpt == null })
            expect(journal.previousRunEvents().all { it.excerpt == null })
            val onDisk = directory.listFiles().orEmpty().joinToString("") { it.readText() }
            expect(!onDisk.contains("hunter2"))
            expect(!onDisk.contains("mother"))
            expect(onDisk.contains("voice_user_transcript"), "The marks stay; only what was said goes")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pairingCodesAndFilePathsNeverReachTheTimeline() {
        expect(DiagnosticsRecorder.redactedPath("/auth/connect/abc123-secret") == "/auth/connect/<redacted>")
        expect(DiagnosticsRecorder.redactedPath("/api/fs/read/Users/kit/notes.md") == "/api/fs/read/<redacted>")
        expect(DiagnosticsRecorder.redactedPath("/api/session/ses_1/message") == "/api/session/ses_1/message")
        val recorder = DiagnosticsRecorder(enabled = true, defaults = null)
        val url = URI("https://box.example/auth/connect/abc123-secret")
        recorder.requestStarted(id = "pair", method = "GET", url = url, reason = "/auth/connect/abc123-secret")
        recorder.requestEnded(
            id = "pair",
            method = "GET",
            url = url,
            statusCode = 401,
            errorCategory = DiagnosticsErrorCategory.unauthorized,
            decodedBytes = 0,
            contentEncoding = null,
        )
        val export = String(recorder.exportJSON(), Charsets.UTF_8) + recorder.exportText()
        expect(!export.contains("abc123-secret"))
        expect(export.contains("/auth/connect/<redacted>"))
    }

    @Test
    fun screenshotsOnlyInTheForegroundAndAwayFromCredentials() {
        expect(WindowSnapshot.isAllowed(WindowSnapshot.ApplicationState.active, 0, false))
        expect(!WindowSnapshot.isAllowed(WindowSnapshot.ApplicationState.background, 0, false))
        expect(!WindowSnapshot.isAllowed(WindowSnapshot.ApplicationState.inactive, 0, false))
        expect(!WindowSnapshot.isAllowed(WindowSnapshot.ApplicationState.active, 1, false))
        expect(!WindowSnapshot.isAllowed(WindowSnapshot.ApplicationState.active, 0, true))
        expect(WindowSnapshot.isAllowed(WindowSnapshot.ApplicationState.active, 0, false))
    }

    @Test
    fun aShakeWithTypingToUndoIsTheSystems() {
        expect(ProblemReportRequest.shakeReports(canUndo = false))
        expect(!ProblemReportRequest.shakeReports(canUndo = true))
    }

    @Test
    fun diagnosticsIndexesMetricsByRequest() {
        val metrics = event("network_metrics", kind = DiagnosticsEvent.Kind.networkMetrics).copy(requestID = "r1")
        val end = event("request_end", kind = DiagnosticsEvent.Kind.requestEnd).copy(requestID = "r1")
        expect(metricsByRequest(listOf(end, metrics))["r1"]?.name == "network_metrics")
    }
}

class CrashReportTests {
    private val device = ProblemReportDevice(
        appVersion = "0.1.0",
        build = "37",
        distribution = "TestFlight",
        system = "iOS 26.0",
        model = "iPhone18,2",
    )

    @Test
    fun anUpdateIsNotACrash() {
        val directory = temporaryDirectory()
        try {
            val old = DiagnosticsJournal(directory = directory, build = "0.1.0 (36)")
            old.open(recording = true)
            old.append(event("launched"))
            old.markForeground()
            old.flush()
            val updated = DiagnosticsJournal(directory = directory, build = "0.1.0 (37)").open(recording = true)
            expect(updated.endedUnexpectedly == false)
            expect(updated.build == "0.1.0 (36)")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aCrashReportLeadsWithTheRunThatCrashed() = runBlocking {
        val crashed = listOf(event("voice_dropped", wall = Instant.ofEpochSecond(1_000), kind = DiagnosticsEvent.Kind.voice))
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.crash,
            status = ProblemReport.Status.pending,
            context = ProblemReportContext(),
            device = device,
            now = Instant.ofEpochSecond(2_000),
            events = listOf(event("app_launch", kind = DiagnosticsEvent.Kind.lifecycle)),
            previousRun = crashed,
            screenshot = null,
        )
        val json = String(require(bundle.files["timeline.json"]), Charsets.UTF_8)
        expect(json.contains("voice_dropped") && json.contains("app_launch"))
        val fake = FakeServer()
        fake.uploadsSupported = false
        val store = ProblemReportStore(temporaryDirectory())
        try {
            store.save(bundle)
            val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
            val destination = PhoneReportsDestination(projectID = "p", projectName = "reports", directory = "/reports")
            PhoneReportsDelivery.deliver(bundle.report, store, server, destination, true, "m", fake.client)
            val inline = require(fake.prompts.firstOrNull()?.text?.substringAfter("inline and may be cut):"))
            expect(inline.contains("voice_dropped"))
            expect(!inline.contains("app_launch"))
        } finally {
            store.root.deleteRecursively()
        }
    }
}

class PhoneReportsRetryTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
    private val device = ProblemReportDevice(
        appVersion = "0.1.0",
        build = "37",
        distribution = "Debug",
        system = "iOS 26.0",
        model = "Simulator",
    )
    private val now = Instant.ofEpochSecond(2_000)

    private fun pending(reports: ProblemReportStore): ProblemReport {
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.settings,
            status = ProblemReport.Status.pending,
            context = ProblemReportContext(),
            device = device,
            now = now,
            events = emptyList(),
            screenshot = null,
        )
        reports.save(bundle)
        return bundle.report
    }

    private fun makeStore(
        fake: FakeServer,
        reports: ProblemReportStore,
        delivering: ProblemReportsFeature.State.Delivering? = null,
        clock: TestClock = TestClock(),
    ): TestStore<ProblemReportsFeature.State, ProblemReportsFeature.Action> {
        val store = TestStore(
            initialState = {
                ProblemReportsFeature.State().apply {
                    server = this@PhoneReportsRetryTests.server
                    this.delivering = delivering
                }
            },
            withDependencies = {
                it.problemReportStore = reports
                it.openCodeClient = fake.client
                it.uuid = UUIDGenerator.incrementing()
                it.continuousClock = clock
                it.date = DateGenerator.constant(now)
            },
        ) { ProblemReportsFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        val key = server.persistenceID
        store.state::destinations.withLock {
            value = mapOf(key to PhoneReportsDestination(projectID = "p", projectName = "reports", directory = "/reports"))
        }
        return store
    }

    @Test
    fun aSessionMadeBeforeAFailureIsReused() {
        val fake = FakeServer()
        fake.promptsFail = true
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            pending(reports)
            val store = makeStore(fake, reports)
            store.send(ProblemReportsFeature.Action.serverReachable)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            expect(store.state.destination?.sessionID == "ses_report_1")
            fake.with { it.promptsFail = false }
            store.send(ProblemReportsFeature.Action.serverReachable)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            expect(fake.created == listOf("ses_report_1"), "The retry reused the session")
            expect(reports.all().isEmpty())
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun reconnectingDuringADeliveryRetriesAfterItFails() {
        val fake = FakeServer()
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val report = pending(reports)
            val store = makeStore(
                fake,
                reports,
                delivering = ProblemReportsFeature.State.Delivering(report.id, server.persistenceID, "p"),
            )
            store.send(ProblemReportsFeature.Action.serverReachable)
            expect(store.state.retryAfterCurrent)
            val failure = PhoneReportsDelivery.Failure(OpenCodeClientError(message = "Couldn’t reach the OpenCode server"))
            store.send(ProblemReportsFeature.Action.deliveryFinished(report.id, Result.failure(failure)))
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            expect(reports.all().isEmpty(), "Not parked until the next reconnect")
            expect(fake.prompts.size == 1)
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun aResultLandsWhereItsDeliveryStarted() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val report = pending(reports)
            val store = makeStore(
                FakeServer(),
                reports,
                delivering = ProblemReportsFeature.State.Delivering(report.id, server.persistenceID, "p"),
            )
            val key = server.persistenceID
            store.state::destinations.withLock {
                value += (key to PhoneReportsDestination(projectID = "q", projectName = "other", directory = "/other"))
            }
            store.send(
                ProblemReportsFeature.Action.deliveryFinished(
                    report.id,
                    Result.success(PhoneReportsDelivery.Receipt(sessionID = "ses_in_p")),
                ),
            )
            expect(store.state.destination?.sessionID == null)
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun automaticRetriesStopAfterFiveFailures() {
        val fake = FakeServer()
        fake.reachable = false
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            pending(reports)
            val store = makeStore(fake, reports)
            repeat(ProblemReportsFeature.maximumAutomaticAttempts) {
                store.send(ProblemReportsFeature.Action.serverReachable)
                store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            }
            store.send(ProblemReportsFeature.Action.serverReachable)
            expect(store.state.delivering == null)
            expect(reports.all().firstOrNull()?.attempts == ProblemReportsFeature.maximumAutomaticAttempts)
            expect(reports.all().firstOrNull()?.status == ProblemReport.Status.pending, "Kept for a later launch")
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun aServerThatKeepsFailingIsRetriedOnABoundedBackoff() {
        val fake = FakeServer()
        fake.promptsFail = true
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val report = pending(reports)
            val clock = TestClock()
            val store = makeStore(fake, reports, clock = clock)
            store.send(ProblemReportsFeature.Action.serverReachable)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            for ((attempt, wait) in listOf(2 to 5, 3 to 15, 4 to 60, 5 to 300)) {
                clock.advance(by = (wait - 1).seconds)
                expect(reports.all().firstOrNull()?.attempts == attempt - 1, "Not before $wait s")
                clock.advance(by = 1.seconds)
                store.receive(ProblemReportsFeature.Action.retryDue(report.id))
                store.receive<ProblemReportsFeature.Action.deliveryFinished>()
                expect(reports.all().firstOrNull()?.attempts == attempt)
            }
            expect(store.state.notice?.kind == ProblemReportsFeature.State.Notice.Kind.failed(report.id))
            clock.advance(by = 3_600.seconds)
            expect(reports.all().firstOrNull()?.attempts == ProblemReportsFeature.maximumAutomaticAttempts, "The attempt limit ends it")
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun everyAttemptSendsTheSameMessage() {
        val fake = FakeServer()
        fake.promptsFail = true
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val report = pending(reports)
            val clock = TestClock()
            val store = makeStore(fake, reports, clock = clock)
            store.send(ProblemReportsFeature.Action.serverReachable)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            clock.advance(by = ProblemReportsFeature.retryDelay(1))
            store.receive(ProblemReportsFeature.Action.retryDue(report.id))
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            fake.with { it.promptsFail = false }
            store.send(ProblemReportsFeature.Action.serverReachable)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            val expected = ProblemReportsFeature.messageID(report.id)
            expect(fake.attemptedMessageIDs == listOf(expected, expected, expected), "The server can tell a retry from a second report")
            expect(fake.prompts.map { it.messageID } == listOf(expected))
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun deliveryCancelsTheBackoff() {
        val fake = FakeServer()
        fake.promptsFail = true
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            pending(reports)
            val clock = TestClock()
            val store = makeStore(fake, reports, clock = clock)
            store.send(ProblemReportsFeature.Action.serverReachable)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            fake.with { it.promptsFail = false }
            store.send(ProblemReportsFeature.Action.serverReachable)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            expect(reports.all().isEmpty())
            clock.advance(by = 600.seconds)
            expect(fake.prompts.size == 1)
            expect(store.state.delivering == null)
        } finally {
            reports.root.deleteRecursively()
        }
    }
}

class ProblemReportOfferExpiryTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
    private val device = ProblemReportDevice(
        appVersion = "0.1.0",
        build = "37",
        distribution = "Debug",
        system = "iOS 26.0",
        model = "Simulator",
    )
    private val now = Instant.ofEpochSecond(2_000)

    private fun makeStore(fake: FakeServer, reports: ProblemReportStore, clock: TestClock) =
        TestStore(
            initialState = { ProblemReportsFeature.State().apply { server = this@ProblemReportOfferExpiryTests.server } },
            withDependencies = {
                it.problemReportStore = reports
                it.openCodeClient = fake.client
                it.uuid = UUIDGenerator.incrementing()
                it.continuousClock = clock
                it.date = DateGenerator.constant(now)
                it.diagnostics.timeline = { emptyList() }
            },
        ) { ProblemReportsFeature() }.also { it.exhaustivity = Exhaustivity.off(showSkippedAssertions = false) }

    @Test
    fun anOfferExpiresAndTakesItsBundle() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val clock = TestClock()
            val store = makeStore(FakeServer(), reports, clock)
            store.send(ProblemReportsFeature.Action.troubleDetected(ProblemReportTrigger.callDropped))
            store.receive<ProblemReportsFeature.Action.offerPrepared>()
            val offer = require(store.state.offer)
            clock.advance(by = ProblemReportsFeature.offerLifetime - 1.seconds)
            expect(store.state.offer == offer)
            clock.advance(by = 1.seconds)
            store.receive(ProblemReportsFeature.Action.offerExpired(offer.reportID))
            expect(store.state.offer == null)
            expect(reports.all().isEmpty())
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun aTappedOfferNeverExpires() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val clock = TestClock()
            val store = makeStore(FakeServer(), reports, clock)
            store.send(ProblemReportsFeature.Action.troubleDetected(ProblemReportTrigger.callDropped))
            store.receive<ProblemReportsFeature.Action.offerPrepared>()
            store.send(ProblemReportsFeature.Action.offerTapped)
            clock.advance(by = ProblemReportsFeature.offerLifetime)
            expect(store.state.composer != null)
            expect(reports.all().map { it.status } == listOf(ProblemReport.Status.composing))
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun anOfferFromLastLaunchKeepsWhatTimeItHadLeft() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val fresh = ProblemReportBuilder.make(
                id = UUID.randomUUID(),
                trigger = ProblemReportTrigger.callDropped,
                status = ProblemReport.Status.offered,
                context = ProblemReportContext(),
                device = device,
                now = now.minusSeconds(240),
                events = emptyList(),
                screenshot = null,
            )
            reports.save(fresh)
            val clock = TestClock()
            val store = makeStore(FakeServer(), reports, clock)
            store.send(ProblemReportsFeature.Action.task)
            expect(store.state.offer?.reportID == fresh.report.id)
            clock.advance(by = 60.seconds)
            store.receive(ProblemReportsFeature.Action.offerExpired(fresh.report.id))
            expect(reports.all().isEmpty())
            val stale = ProblemReportBuilder.make(
                id = UUID.randomUUID(),
                trigger = ProblemReportTrigger.callDropped,
                status = ProblemReport.Status.offered,
                context = ProblemReportContext(),
                device = device,
                now = now.minusSeconds(301),
                events = emptyList(),
                screenshot = null,
            )
            reports.save(stale)
            store.send(ProblemReportsFeature.Action.task)
            expect(store.state.offer == null)
            expect(reports.all().isEmpty())
        } finally {
            reports.root.deleteRecursively()
        }
    }
}

class ProblemReportNoticeTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
    private val device = ProblemReportDevice(
        appVersion = "0.1.0",
        build = "37",
        distribution = "Debug",
        system = "iOS 26.0",
        model = "Simulator",
    )
    private val now = Instant.ofEpochSecond(2_000)

    private fun makeStore(fake: FakeServer, reports: ProblemReportStore, clock: TestClock, haptics: MutableList<String>) =
        TestStore(
            initialState = { ProblemReportsFeature.State().apply { server = this@ProblemReportNoticeTests.server } },
            withDependencies = {
                it.problemReportStore = reports
                it.openCodeClient = fake.client
                it.uuid = UUIDGenerator.incrementing()
                it.continuousClock = clock
                it.date = DateGenerator.constant(now)
                it.diagnostics.timeline = { emptyList() }
                it.haptics.success = { haptics += "success" }
                it.haptics.warning = { haptics += "warning" }
                it.haptics.error = { haptics += "error" }
            },
        ) { ProblemReportsFeature() }.also {
            it.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
            val key = server.persistenceID
            it.state::destinations.withLock {
                value = mapOf(key to PhoneReportsDestination(projectID = "p", projectName = "reports", directory = "/reports"))
            }
        }

    private fun send(store: TestStore<ProblemReportsFeature.State, ProblemReportsFeature.Action>) {
        store.send(ProblemReportsFeature.Action.reportRequested(ProblemReportTrigger.shake, null))
        store.receive<ProblemReportsFeature.Action.composerPrepared>()
        store.send(ProblemReportsFeature.Action.sendTapped)
        store.receive<ProblemReportsFeature.Action.deliveryFinished>()
    }

    private fun pending(reports: ProblemReportStore): ProblemReport {
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            trigger = ProblemReportTrigger.settings,
            status = ProblemReport.Status.pending,
            context = ProblemReportContext(),
            device = device,
            now = now,
            events = emptyList(),
            screenshot = null,
        )
        reports.save(bundle)
        return bundle.report
    }

    @Test
    fun aDeliveredReportSaysSentToPhoneReportsAndOpensIt() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val clock = TestClock()
            val haptics = mutableListOf<String>()
            val store = makeStore(FakeServer(), reports, clock, haptics)
            send(store)
            expect(store.state.composer == null, "The sheet closes on delivery")
            val notice = require(store.state.notice)
            expect(notice.kind == ProblemReportsFeature.State.Notice.Kind.sent("ses_report_1"))
            expect(haptics.isEmpty(), "Nothing until it shows")
            store.send(ProblemReportsFeature.Action.noticeAppeared(notice.id))
            expect(haptics == listOf("success"))
            store.send(ProblemReportsFeature.Action.noticeAppeared(notice.id))
            expect(haptics == listOf("success"))
            store.send(ProblemReportsFeature.Action.noticeTapped)
            store.receive(ProblemReportsFeature.Action.delegate(ProblemReportsFeature.Delegate.openSession("ses_report_1")))
            expect(store.state.notice == null)
            clock.advance(by = 10.seconds)
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun aDeliveredNoticeLeavesAfterItHasShown() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val clock = TestClock()
            val store = makeStore(FakeServer(), reports, clock, mutableListOf())
            send(store)
            val notice = require(store.state.notice)
            clock.advance(by = 600.seconds)
            expect(store.state.notice == notice)
            store.send(ProblemReportsFeature.Action.noticeAppeared(notice.id))
            clock.advance(by = ProblemReportsFeature.noticeDuration - 1.seconds)
            expect(store.state.notice != null)
            clock.advance(by = 1.seconds)
            store.receive(ProblemReportsFeature.Action.noticeExpired(notice.id))
            expect(store.state.notice == null)
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun aQueuedReportSaysSoThenReportSentWhenItGoes() {
        val fake = FakeServer()
        fake.reachable = false
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val clock = TestClock()
            val haptics = mutableListOf<String>()
            val store = makeStore(fake, reports, clock, haptics)
            send(store)
            expect(store.state.composer == null)
            val waiting = require(store.state.notice)
            expect(waiting.kind == ProblemReportsFeature.State.Notice.Kind.waiting)
            store.send(ProblemReportsFeature.Action.noticeAppeared(waiting.id))
            expect(haptics == listOf("warning"))
            fake.with { it.reachable = true }
            clock.advance(by = ProblemReportsFeature.retryDelay(1))
            store.receive<ProblemReportsFeature.Action.retryDue>()
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            val delivered = require(store.state.notice)
            expect(delivered.kind == ProblemReportsFeature.State.Notice.Kind.delivered("ses_report_1"))
            store.send(ProblemReportsFeature.Action.noticeAppeared(delivered.id))
            expect(haptics == listOf("warning", "success"))
            store.send(ProblemReportsFeature.Action.noticeTapped)
            store.receive(ProblemReportsFeature.Action.delegate(ProblemReportsFeature.Delegate.openSession("ses_report_1")))
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun exhaustedRetriesSayCouldntSendAndRetryWorks() {
        val fake = FakeServer()
        fake.reachable = false
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val report = pending(reports)
            val clock = TestClock()
            val haptics = mutableListOf<String>()
            val store = makeStore(fake, reports, clock, haptics)
            for (attempt in 1..ProblemReportsFeature.maximumAutomaticAttempts) {
                store.send(ProblemReportsFeature.Action.serverReachable)
                store.receive<ProblemReportsFeature.Action.deliveryFinished>()
                if (attempt < ProblemReportsFeature.maximumAutomaticAttempts) {
                    expect(store.state.notice == null, "Background retries stay quiet")
                }
            }
            val failed = require(store.state.notice)
            expect(failed.kind == ProblemReportsFeature.State.Notice.Kind.failed(report.id))
            store.send(ProblemReportsFeature.Action.noticeAppeared(failed.id))
            expect(haptics == listOf("error"))
            clock.advance(by = 60.seconds)
            expect(store.state.notice?.id == failed.id, "A failure stays until it's dealt with")
            expect(reports.all().firstOrNull()?.status == ProblemReport.Status.pending, "Still there to share or retry")
            fake.with { it.reachable = true }
            store.send(ProblemReportsFeature.Action.retryTapped)
            store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            expect(store.state.notice?.kind == ProblemReportsFeature.State.Notice.Kind.sent("ses_report_1"))
            expect(reports.all().isEmpty())
            expect(fake.prompts.map { it.messageID } == listOf(ProblemReportsFeature.messageID(report.id)), "Retry sends the same message")
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun dismissingAFailureKeepsTheReportForLater() {
        val fake = FakeServer()
        fake.reachable = false
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            pending(reports)
            val store = makeStore(fake, reports, TestClock(), mutableListOf())
            repeat(ProblemReportsFeature.maximumAutomaticAttempts) {
                store.send(ProblemReportsFeature.Action.serverReachable)
                store.receive<ProblemReportsFeature.Action.deliveryFinished>()
            }
            store.send(ProblemReportsFeature.Action.noticeDismissed)
            expect(store.state.notice == null)
            expect(reports.all().firstOrNull()?.status == ProblemReport.Status.pending)
        } finally {
            reports.root.deleteRecursively()
        }
    }

    @Test
    fun settingsOpensPhoneReportsOnceItExists() {
        val reports = ProblemReportStore(temporaryDirectory())
        try {
            val store = makeStore(FakeServer(), reports, TestClock(), mutableListOf())
            store.send(ProblemReportsFeature.Action.openPhoneReportsTapped)
            val key = server.persistenceID
            store.state::destinations.withLock {
                val current = value[key] ?: return@withLock
                value += (key to current.copy(sessionID = "ses_report_1"))
            }
            store.send(ProblemReportsFeature.Action.openPhoneReportsTapped)
            store.receive(ProblemReportsFeature.Action.delegate(ProblemReportsFeature.Delegate.openSession("ses_report_1")))
        } finally {
            reports.root.deleteRecursively()
        }
    }
}

// Robolectric gives the reducers' LabMarks a working android.util.Log.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhoneReportsNavigationTests {
    @get:Rule
    val dependencies = TestDependencies()

    val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
    val listed = OpenCodeSession(id = "ses_listed", title = "Phone Reports", projectID = "p", directory = "/reports", updatedAt = distantPast)

    @Test
    fun opensPhoneReportsFromTheDrawerListOrTheServer() {
        val fetched = OpenCodeSession(id = "ses_old", title = "Phone Reports", projectID = "p", directory = "/reports", updatedAt = distantPast)
        val store = TestStore(
            initialState = { HomeFeature.State(server = server, snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(listed), activeSessionIDs = emptySet())) },
            withDependencies = {
                it.problemReportsEnabled = true
                it.continuousClock = ImmediateClock()
                it.openCodeClient.getSession = { _, id ->
                    expect(id == "ses_old")
                    fetched
                }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
            },
        ) {
            HomeFeature()
        }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(HomeFeature.Action.problemReports(ProblemReportsFeature.Action.delegate(ProblemReportsFeature.Delegate.openSession("ses_listed"))))
        store.receive(HomeFeature.Action.sessionTapped("ses_listed"))

        // Older than the drawer's recent sessions.
        store.send(HomeFeature.Action.problemReports(ProblemReportsFeature.Action.delegate(ProblemReportsFeature.Delegate.openSession("ses_old"))))
        store.receive(HomeFeature.Action.problemReports(ProblemReportsFeature.Action.delegate(ProblemReportsFeature.Delegate.openFetchedSession(fetched))))
        store.receive(HomeFeature.Action.sessionTapped("ses_old"))
        expect(store.state.sessions.firstOrNull()?.id == "ses_old")
    }
}

class ProblemReportModelTests {
    @get:Rule
    val dependencies = TestDependencies()

    val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
    val project = OpenCodeProject(id = "p", name = "app", directory = "/app")
    val modelA = OpenCodeModelRef(id = "model-a", providerID = "provider-a")
    val modelB = OpenCodeModelRef(id = "model-b", providerID = "provider-b")
    val failingDefault = OpenCodeModelRef(id = "model-default", providerID = "provider-c")

    private fun session(id: String, model: OpenCodeModelRef?, updated: Long): OpenCodeSession =
        OpenCodeSession(id = id, title = id, projectID = "p", directory = "/app", updatedAt = updated * 1_000, model = model)

    @Test
    fun aReportTakesTheModelOfItsSessionThenTheLastOneUsed() {
        val state = HomeFeature.State(
            server = server,
            snapshot = OpenCodeHomeSnapshot(
                projects = listOf(project),
                sessions = listOf(
                    session("ses_reports", model = failingDefault, updated = 300),
                    session("ses_untouched", model = null, updated = 200),
                    session("ses_recent", model = modelB, updated = 100),
                    session("ses_about", model = modelA, updated = 50),
                ),
                activeSessionIDs = emptySet(),
            ),
        )
        state.problemReports.server = server
        state.problemReports::destinations.withLock {
            value += (server.persistenceID to PhoneReportsDestination(projectID = "p", projectName = "app", directory = "/app", sessionID = "ses_reports"))
        }

        // A new session's draft takes the newest session's model, never
        // Phone Reports' (here the server's failing default), and so does a report.
        state.sessionDetail = state.makeDraft()
        expect(state.sessionDetail?.currentModel == modelB)
        expect(state.problemReportContext.model == modelB)
        state.sessionDetail = null
        expect(state.problemReportContext.model == modelB)

        // The session the report is about, as its composer has it.
        state.sessionDetail = state.makeDetail(state.sessions[3])
        expect(state.problemReportContext.model == modelA)
        state.sessionDetail?.currentModel = modelB
        expect(state.problemReportContext.model == modelB, "Picked since the list was read")

        // A draft: whatever its composer is set to.
        state.sessionDetail = state.makeDraft()
        state.sessionDetail?.currentModel = modelA
        expect(state.problemReportContext.model == modelA)

        // No model anywhere: left unset, for the server to choose.
        val bare = HomeFeature.State(
            server = server,
            snapshot = OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(session("ses_a", model = null, updated = 1)), activeSessionIDs = emptySet()),
        )
        bare.sessionDetail = bare.makeDetail(bare.sessions[0])
        expect(bare.problemReportContext.model == null)
    }
}

class VoiceTimelineTests {
    @get:Rule
    val dependencies = TestDependencies()

    @Ignore("Voice/VoiceFeature.kt")
    @Test
    fun marksEachTurnOfACall() = Unit
}

class ProblemReportShakeTests {
    @Test
    fun aSustainedAccelerationIsAShakeAndABumpIsNot() {
        val shake = ProblemReportShake()
        val start = 1_000_000_000L
        var heard = false
        for (index in 0 until 20) {
            val time = start + index * 20_000_000L
            heard = shake.add(time, 20f, 0f, 0f) || heard
        }
        expect(heard, "A quarter-second of acceleration above 13 m/s² is a shake")
        val bump = ProblemReportShake()
        expect(!bump.add(start, 20f, 0f, 0f))
        expect(!bump.add(start + 20_000_000L, 0f, 0f, 9.8f))
    }
}
