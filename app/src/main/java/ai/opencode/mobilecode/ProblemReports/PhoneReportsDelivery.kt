@file:PortedFrom("OpenCode/ProblemReports/PhoneReportsDelivery.swift", "dd71df9")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StorageDirectory
import ai.opencode.mobilecode.tca.fileStorage
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable

/// Where one server's reports go: a project picked once, and the Phone
/// Reports session the app made there.
@Serializable
data class PhoneReportsDestination(
    var projectID: String,
    var projectName: String,
    var directory: String,
    var sessionID: String? = null,
)

/// Keyed by server persistence ID.
val SharedKey.Companion.phoneReportsDestinations: SharedKey<Map<String, PhoneReportsDestination>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "phone-reports.json"), default = emptyMap())

/// Delivers one report as a queued prompt in Phone Reports, creating the
/// session the first time and again if it was deleted.
object PhoneReportsDelivery {
    const val sessionTitle = "Phone Reports"

    data class Receipt(
        var sessionID: String,
        var uploadsUnsupported: Boolean = false,
    )

    /// A delivery that failed, with the session it found or made first, so a
    /// retry reuses it instead of making another.
    class Failure(
        val sessionID: String? = null,
    ) : Exception() {
        override fun equals(other: Any?): Boolean {
            val failure = other as? Failure ?: return false
            return sessionID == failure.sessionID
        }

        override fun hashCode(): Int = sessionID?.hashCode() ?: 0
    }

    suspend fun deliver(
        report: ProblemReport,
        store: ProblemReportStore,
        server: OpenCodeServer,
        destination: PhoneReportsDestination,
        supportsUploads: Boolean,
        messageID: String,
        client: OpenCodeClient,
    ): Receipt {
        val sessionID = try {
            session(destination, server, client)
        } catch (error: OpenCodeClientError) {
            throw Failure()
        }
        // Every report, not just the first: the server's default may be the
        // model that's failing. A failed switch still sends.
        val model = report.context.model
        if (model != null) {
            runCatching { client.switchModel(server, sessionID, model) }
        }
        try {
            return send(report, sessionID, store, server, destination.directory, supportsUploads, messageID, client)
        } catch (error: OpenCodeClientError) {
            throw Failure(sessionID = sessionID)
        }
    }

    private suspend fun send(
        report: ProblemReport,
        sessionID: String,
        store: ProblemReportStore,
        server: OpenCodeServer,
        directory: String,
        supportsUploads: Boolean,
        messageID: String,
        client: OpenCodeClient,
    ): Receipt {
        var files = emptyList<OpenCodeFileReference>()
        var uploadsUnsupported = false
        if (supportsUploads) {
            try {
                files = upload(report, store, server, directory, client)
            } catch (error: OpenCodeClientError) {
                if (!error.isUnsupported) throw error
                uploadsUnsupported = true
            }
        }
        val screenshot = if (report.includesScreenshot) store.data(report.id, ProblemReportBundle.File.screenshot) else null
        // A crash's story is the run that crashed, not this launch.
        val inlined = if (report.trigger == ProblemReportTrigger.crash && store.url(report.id, ProblemReportBundle.File.previousRun) != null) {
            ProblemReportBundle.File.previousRun
        } else {
            ProblemReportBundle.File.timelineText
        }
        val inline = if (files.isEmpty()) {
            store.data(report.id, inlined)?.let {
                ProblemReportBuilder.tail(String(it, Charsets.UTF_8), ProblemReportBuilder.inlineTimelineLimit)
            }
        } else {
            null
        }
        val text = ProblemReportBuilder.summary(report, hasScreenshot = screenshot != null, inlineTimeline = inline)
        val attachments = if (screenshot == null) {
            emptyList()
        } else {
            listOf(OpenCodeOutgoingAttachment(data = screenshot, mime = "image/jpeg", name = "screenshot.jpg"))
        }
        // Queued: a report never steers the agent mid-run; they pile up instead.
        client.sendPrompt(server, sessionID, text, attachments, files, messageID, OpenCodeDelivery.queue)
        return Receipt(sessionID = sessionID, uploadsUnsupported = uploadsUnsupported)
    }

    /// The remembered session while it exists; otherwise a new one.
    suspend fun session(
        destination: PhoneReportsDestination,
        server: OpenCodeServer,
        client: OpenCodeClient,
    ): String {
        val remembered = destination.sessionID
        if (remembered != null) {
            try {
                client.getSession(server, remembered)
                return remembered
            } catch (error: OpenCodeClientError) {
                if (error.statusCode != 404) throw error
            }
        }
        val created = client.createSession(server, destination.directory)
        // A failed rename leaves a working session with the server's title.
        runCatching { client.renameSession(server, created.id, sessionTitle) }
        return created.id
    }

    private suspend fun upload(
        report: ProblemReport,
        store: ProblemReportStore,
        server: OpenCodeServer,
        directory: String,
        client: OpenCodeClient,
    ): List<OpenCodeFileReference> {
        val stamp = stampFormat.format(report.createdAt)
        val files = listOf(
            ProblemReportBundle.File.timelineText to "text/plain",
            ProblemReportBundle.File.timelineJSON to "application/json",
            ProblemReportBundle.File.previousRun to "text/plain",
        )
        val references = mutableListOf<OpenCodeFileReference>()
        for ((name, mime) in files) {
            val url = store.url(report.id, name) ?: continue
            val uploadedName = "phone-report-$stamp-$name"
            var path: String? = null
            try {
                client.uploadFile(server, directory, url, uploadedName).collect { event ->
                    if (event is OpenCodeUploadEvent.finished) path = event.path
                }
            } catch (error: OpenCodeClientError) {
                throw error
            } catch (_: Throwable) {
                throw OpenCodeClientError(message = "Couldn’t upload the report")
            }
            val finished = path ?: throw OpenCodeClientError(message = "Couldn’t upload the report")
            references += OpenCodeFileReference(name = uploadedName, mime = mime, path = finished)
        }
        return references
    }

    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXX").withZone(ZoneOffset.UTC)
}
