@file:PortedFrom("OpenCodeTests/DiagnosticsRecorderTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.expect
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.junit.Ignore
import org.junit.Test

class DiagnosticsRecorderTests {
    class Clock {
        var value = 100.0
        var calls = 0

        fun now(): Double {
            calls += 1
            return value
        }
    }

    @Test
    fun boundsByCountAndAgeAndUsesMonotonicDurations() {
        val clock = Clock()
        val recorder = DiagnosticsRecorder(
            enabled = true,
            maximumEvents = 3,
            retentionSeconds = 10.0,
            defaults = null,
            monotonicNow = { clock.now() },
            wallNow = { Instant.ofEpochSecond(1_000) },
        )
        val url = URI("https://example.test/api/session/ses_safe/message?password=hunter2")
        recorder.requestStarted(id = "one", method = "GET", url = url, reason = "refresh")
        clock.value = 101.25
        recorder.requestEnded(
            id = "one",
            method = "GET",
            url = url,
            statusCode = 200,
            errorCategory = null,
            decodedBytes = 1_024,
            contentEncoding = "gzip",
        )
        recorder.recordMark("snapshot_apply", sessionID = "ses_safe")
        recorder.recordMark("fresh_content_laid_out", sessionID = "ses_safe")

        val bounded = recorder.snapshot()
        expect(bounded.size == 3)
        expect(bounded.first().durationMilliseconds == 1_250.0)
        expect(bounded.first().path == "/api/session/ses_safe/message?password=<redacted>")

        clock.value = 112.0
        recorder.recordMark("after_retention", sessionID = null)
        expect(recorder.snapshot().map { it.name } == listOf("after_retention"))
    }

    @Test
    fun socketErrnosClassifyAsOfflineOrTimeout() {
        val posix = { code: Int -> DiagnosticsErrorCategory(NSError(domain = NSPOSIXErrorDomain, code = code)) }
        for (code in listOf(ENOTCONN, ECONNRESET, ECONNABORTED, EPIPE, ENETDOWN, ENETUNREACH, EHOSTDOWN, EHOSTUNREACH)) {
            expect(posix(code) == DiagnosticsErrorCategory.offline)
        }
        expect(posix(ETIMEDOUT) == DiagnosticsErrorCategory.timeout)
        expect(posix(EACCES) == DiagnosticsErrorCategory.transport)
        expect(DiagnosticsErrorCategory(URLError(URLError.Code.networkConnectionLost)) == DiagnosticsErrorCategory.offline)
        expect(DiagnosticsErrorCategory(NSError(domain = NSCocoaErrorDomain, code = ENOTCONN)) == DiagnosticsErrorCategory.transport)
    }

    @Test
    fun voiceSocketEventsAreStreamEventsWithTheirCodes() {
        val recorder = DiagnosticsRecorder(enabled = true, defaults = null)
        recorder.recordVoiceSocketEvent(
            "voice_socket_dropped",
            domain = NSURLErrorDomain,
            code = URLError.unknown.rawValue,
            closeCode = 1006,
            underlyingDomain = NSPOSIXErrorDomain,
            underlyingCode = ENOTCONN,
            closeReason = "going away",
            errorCategory = DiagnosticsErrorCategory.offline,
        )
        recorder.recordMark("voice_socket_reconnected", sessionID = null)

        val events = recorder.snapshot()
        expect(events.map { it.kind } == listOf(DiagnosticsEvent.Kind.stream, DiagnosticsEvent.Kind.stream))
        val dropped = events.first()
        expect(dropped.reason == "domain=NSURLErrorDomain code=-1 closeCode=1006 underlying=NSPOSIXErrorDomain:57 closeReason=going away")
        expect(dropped.statusCode == -1)
        expect(recorder.exportText().contains("voice_socket_dropped status=-1 error=offline domain=NSURLErrorDomain"))
    }

    @Test
    @Ignore("OpenCode/Voice/RealtimeClient.kt will port RealtimeTransportDiagnostic equality")
    fun realtimeTransportDiagnosticUnwraps() {}

    @Test
    fun disabledPathRecordsNothing() {
        val clock = Clock()
        val recorder = DiagnosticsRecorder(enabled = false, defaults = null, monotonicNow = { clock.now() })
        recorder.recordMark("secret prompt body", sessionID = "ses_private")
        expect(recorder.snapshot().isEmpty())
        expect(clock.calls == 0)
    }

    @Test
    fun exportsNeverContainQueryValuesOrAuthorizationHeaders() {
        val recorder = DiagnosticsRecorder(enabled = true, defaults = null)
        val url = URI("https://user:password@example.test/api/session/ses_123?password=super-secret&token=private")
        recorder.requestStarted(id = "request", method = "GET", url = url, reason = "test")
        recorder.requestEnded(
            id = "request",
            method = "GET",
            url = url,
            statusCode = 200,
            errorCategory = null,
            decodedBytes = 20,
            contentEncoding = "gzip",
        )
        val export = recorder.exportJSON().toString(Charsets.UTF_8)
        expect(export.contains("ses_123"))
        expect(export.contains("password=<redacted>"))
        expect(!export.contains("super-secret"))
        expect(!export.contains("private"))
        expect(!export.contains("Authorization", ignoreCase = true))
        expect(!export.contains("user:password"))
    }

    @Test
    fun textExportSnapshot() {
        val event = DiagnosticsEvent(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            kind = DiagnosticsEvent.Kind.requestEnd,
            monotonicSeconds = 42.0,
            wallDate = Instant.EPOCH,
            name = "request_end",
            navigationEpoch = 2,
            method = "GET",
            path = "/api/session/ses_example/message?limit=<redacted>",
            statusCode = 200,
            durationMilliseconds = 125.0,
            decodedBytes = 326_000,
            contentEncoding = "gzip",
        )
        val recorder = DiagnosticsRecorder(enabled = true, defaults = null)
        expect(
            recorder.exportText(events = listOf(event)) == """
            OpenCode diagnostics
            Events: 1

            +0.000s [requestEnd] request_end epoch=2 GET /api/session/ses_example/message?limit=<redacted> 125ms status=200 decoded=326000 encoding=gzip
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun classifiesTransportAndHTTPFailures() {
        expect(DiagnosticsErrorCategory(URLError(URLError.Code.timedOut)) == DiagnosticsErrorCategory.timeout)
        expect(DiagnosticsErrorCategory(URLError(URLError.Code.notConnectedToInternet)) == DiagnosticsErrorCategory.offline)
        expect(DiagnosticsErrorCategory(URLError(URLError.Code.cancelled)) == DiagnosticsErrorCategory.cancelled)
        expect(DiagnosticsErrorCategory(status = 401) == DiagnosticsErrorCategory.unauthorized)
        expect(DiagnosticsErrorCategory(status = 404) == DiagnosticsErrorCategory.notFound)
        expect(DiagnosticsErrorCategory(status = 503) == DiagnosticsErrorCategory.server(503))
    }

    @Test
    fun journalKeepsTheTailAndForgetsExcerpts() {
        val directory = java.io.File(System.getProperty("java.io.tmpdir"), "diagnostics-journal-${UUID.randomUUID()}")
        val journal = DiagnosticsJournal(directory, build = "0.1.0 (1)")
        val opened = journal.open(recording = true)
        expect(!opened.endedUnexpectedly)
        val event = DiagnosticsEvent(
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            kind = DiagnosticsEvent.Kind.voice,
            monotonicSeconds = 1.0,
            wallDate = Instant.EPOCH,
            name = "voice_turn",
            excerpt = "hello",
        )
        journal.append(event)
        journal.flush()
        expect(journal.currentRunEvents().map { it.excerpt } == listOf("hello"))
        journal.removeExcerpts()
        journal.flush()
        expect(journal.currentRunEvents().map { it.excerpt } == listOf(null))
        directory.deleteRecursively()
    }
}
