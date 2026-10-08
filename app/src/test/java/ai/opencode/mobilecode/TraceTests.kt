@file:PortedFrom("OpenCodeTests/TraceTests.swift", "42c4225")

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            // The delete is queued. Flush so the next open sees it.
            second.flush()
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
        } finally {
            directory.deleteRecursively()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraceUploadsTests {
    @get:Rule
    val dependencies = TestDependencies()

    private fun store(
        enabled: Boolean,
        uploads: MutableList<ProblemReportTrigger>,
        crashed: Boolean = false,
        foreground: Double = 600.0,
    ): TestStore<TraceUploadsFeature.State, TraceUploadsFeature.Action> =
        TestStore(
            initialState = { TraceUploadsFeature.State() },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.date = DateGenerator.constant(Instant.ofEpochSecond(1_000))
                it.traceUploader = TraceUploader(
                    isEnabled = { enabled },
                    upload = { bundle ->
                        synchronized(uploads) { uploads += bundle.report.trigger }
                        true
                    },
                )
                it.diagnostics.takeUnexpectedEnd = { crashed }
                it.diagnostics.foregroundSeconds = { foreground }
            },
        ) { TraceUploadsFeature() }

    @Test
    fun nothingUploadsWithDeveloperModeOff() {
        val uploads = mutableListOf<ProblemReportTrigger>()
        val store = store(enabled = false, uploads = uploads, crashed = true)
        store.send(TraceUploadsFeature.Action.task)
        store.send(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.shake))
        store.send(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.callDropped))
        expect(uploads.isEmpty())
    }

    @Test
    fun aShakeUploadsEveryTimeButAutomaticTracesAreThrottled() {
        val uploads = mutableListOf<ProblemReportTrigger>()
        val store = store(enabled = true, uploads = uploads)
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.shake))
        store.send(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.shake))
        store.send(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.callDropped))
        store.send(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.streamStalled))
        store.send(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.snapshot))
        store.finish()
        store.skipReceivedActions()
        // The uploads run concurrently, so they can land in any order.
        expect(synchronized(uploads) { uploads.sortedBy { it.ordinal } } == listOf(ProblemReportTrigger.shake, ProblemReportTrigger.shake, ProblemReportTrigger.callDropped))
        expect(store.state.lastResult?.uploaded == true)
    }

    @Test
    fun aCrashLastRunUploadsOnLaunch() {
        val uploads = mutableListOf<ProblemReportTrigger>()
        val store = store(enabled = true, uploads = uploads, crashed = true)
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(TraceUploadsFeature.Action.task)
        store.finish()
        store.skipReceivedActions()
        // Debug builds treat a crash as one only when a lab asks.
        expect(synchronized(uploads) { uploads.toList() } == if (TraceUploadsFeature.reportsCrashes) listOf(ProblemReportTrigger.crash) else emptyList())
    }

    @Test
    fun aStallRightAfterWakingIsNotATrace() {
        val uploads = mutableListOf<ProblemReportTrigger>()
        val store = store(enabled = true, uploads = uploads, foreground = 10.0)
        store.send(TraceUploadsFeature.Action.traceRequested(ProblemReportTrigger.streamStalled))
        expect(uploads.isEmpty())
    }

    @Test
    fun theTraceBodyMatchesTheWorker() {
        val device = ProblemReportDevice(appVersion = "0.1.0", build = "37", distribution = "TestFlight", system = "iOS 26.0", model = "iPhone18,2")
        val bundle = ProblemReportBuilder.make(
            id = UUID.fromString("4DB11E09-4794-431A-897C-F9571032F194"),
            trigger = ProblemReportTrigger.shake,
            status = ProblemReport.Status.pending,
            context = ProblemReportContext(server = "http://127.0.0.1:4096", screen = "Session"),
            device = device,
            now = Instant.ofEpochSecond(1_000),
            events = listOf(event("app_launch", kind = DiagnosticsEvent.Kind.lifecycle)),
            screenshot = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        )
        val body = DeveloperMode.traceBody(bundle)
        expect(body["id"]?.jsonPrimitive?.content == "4DB11E09-4794-431A-897C-F9571032F194")
        expect(body["trigger"]?.jsonPrimitive?.content == "shake")
        expect(body["device"]?.jsonObject?.get("model")?.jsonPrimitive?.content == "iPhone18,2")
        val files = require(body["files"]?.jsonObject)
        expect(files["screenshot.jpg"]?.jsonPrimitive?.content == java.util.Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        expect(files["timeline.json"]?.jsonPrimitive?.content?.contains("app_launch") == true)
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
