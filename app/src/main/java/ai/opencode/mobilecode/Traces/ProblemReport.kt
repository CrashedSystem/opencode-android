@file:PortedFrom("OpenCode/Traces/ProblemReport.swift", "494e433")

package ai.opencode.mobilecode

import android.os.Build
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.Serializable

/// What made the report: the person (shake, Settings) or the app noticing trouble.
@Serializable
enum class ProblemReportTrigger {
    shake, settings, callDropped, crash, streamStalled,

    /// Leaving the app with Developer Mode on: the session's flow so far.
    snapshot,
    ;

    val isAutomatic: Boolean
        get() = when (this) {
            callDropped, crash, streamStalled, snapshot -> true
            shake, settings -> false
        }

    /// What happened, as the sheet and the report's title read it.
    val title: String
        get() = when (this) {
            shake -> "Reported with a shake"
            settings -> "Reported from Settings"
            callDropped -> "Call dropped"
            crash -> "App closed unexpectedly"
            streamStalled -> "Connection stalled"
            snapshot -> "Left the app"
        }
}

/// Where the app was. No credentials: the server is its origin only.
@Serializable
data class ProblemReportContext(
    var server: String = "",
    var screen: String = "",
    var sessionID: String? = null,
    var sessionTitle: String? = null,
    var project: String? = null,
    var connection: String = "",
    var call: String? = null,
    /// What Phone Reports runs on: the server's default may be the very model
    /// that's failing.
    var model: OpenCodeModelRef? = null,
)

@Serializable
data class ProblemReportDevice(
    var appVersion: String,
    var build: String,
    var distribution: String,
    var system: String,
    var model: String,
    var liveActivities: String? = null,
) {
    companion object {
        val current: ProblemReportDevice
            get() {
                val release = runCatching { Build.VERSION.RELEASE }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "?"
                val machine = runCatching { Build.MODEL }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "?"
                val fingerprint = runCatching { Build.FINGERPRINT }.getOrNull().orEmpty()
                val hardware = runCatching { Build.HARDWARE }.getOrNull().orEmpty()
                val simulated = fingerprint.startsWith("generic") || hardware == "ranchu" || hardware == "goldfish"
                val distribution = if (InternalBuild.isDebug) "Debug" else (if (InternalBuild.isTestFlight) "TestFlight" else "Play")
                return ProblemReportDevice(
                    appVersion = BuildConfig.VERSION_NAME,
                    build = BuildConfig.VERSION_CODE.toString(),
                    distribution = distribution,
                    system = "Android $release",
                    model = if (simulated) "$machine (Emulator)" else machine,
                )
            }
    }
}

@Serializable
data class ProblemReport(
    @Serializable(with = UUIDAsStringSerializer::class) var id: UUID,
    @Serializable(with = InstantIsoSerializer::class) var createdAt: Instant,
    var trigger: ProblemReportTrigger,
    var status: Status,
    var note: String = "",
    var includesScreenshot: Boolean = true,
    var device: ProblemReportDevice,
    var context: ProblemReportContext,
    /// The last few telling timeline lines, for the readable summary.
    var highlights: List<String> = emptyList(),
    var attempts: Int = 0,
) {
    @Serializable
    enum class Status {
        /// Saved by the app when it noticed trouble; waits for Send Report?.
        offered,
        /// Open in the sheet.
        composing,
        /// Sent by the person; waits for delivery.
        pending,
    }
}

/// A report and its files, each already within its share of the size budget.
data class ProblemReportBundle(
    var report: ProblemReport,
    var files: Map<String, ByteArray>,
) {
    object File {
        const val timelineText = "timeline.txt"
        const val timelineJSON = "timeline.json"
        const val previousRun = "previous-run.txt"
        const val screenshot = "screenshot.jpg"
        const val summary = "summary.md"
    }
}

object ProblemReportBuilder {
    /// The whole bundle stays under 2 MB.
    const val screenshotLimit = 600_000
    const val timelineJSONLimit = 700_000
    const val timelineTextLimit = 300_000
    const val previousRunLimit = 150_000
    /// Inline when the server can't take uploads.
    const val inlineTimelineLimit = 24_000
    /// The previous run is attached when it ended this recently, or crashed.
    const val previousRunWindow = 900.0

    fun make(
        id: UUID,
        trigger: ProblemReportTrigger,
        status: ProblemReport.Status,
        note: String = "",
        context: ProblemReportContext,
        device: ProblemReportDevice,
        now: Instant,
        events: List<DiagnosticsEvent>,
        previousRun: List<DiagnosticsEvent> = emptyList(),
        screenshot: ByteArray?,
    ): ProblemReportBundle {
        var report = ProblemReport(
            id = id,
            createdAt = now,
            trigger = trigger,
            status = status,
            note = note,
            device = device,
            context = context,
        )
        val timeline = if (trigger == ProblemReportTrigger.crash) previousRun else events
        report = report.copy(highlights = highlights(timeline))
        val files = mutableMapOf<String, ByteArray>()
        // After a crash, this launch has barely begun: the timeline leads with the run that crashed.
        val recorded = if (trigger == ProblemReportTrigger.crash) previousRun + events else events
        files[ProblemReportBundle.File.timelineText] =
            tail(DiagnosticsRecorder.exportText(recorded, wallClock = true), timelineTextLimit).toByteArray()
        files[ProblemReportBundle.File.timelineJSON] = boundedJSON(recorded, timelineJSONLimit)
        val last = previousRun.lastOrNull()
        if (last != null && (trigger == ProblemReportTrigger.crash || now.epochSecond - last.wallDate.epochSecond <= previousRunWindow)) {
            files[ProblemReportBundle.File.previousRun] =
                tail(DiagnosticsRecorder.exportText(previousRun, wallClock = true), previousRunLimit).toByteArray()
        }
        if (screenshot != null && screenshot.size <= screenshotLimit) {
            files[ProblemReportBundle.File.screenshot] = screenshot
        }
        return ProblemReportBundle(report = report, files = files)
    }

    /// `scheme://host:port`: no user, password, path, or query.
    fun redactedServer(address: String): String {
        val components = runCatching { URI(address.trim()) }.getOrNull() ?: return "unknown"
        val scheme = components.scheme ?: return "unknown"
        val host = components.host ?: return "unknown"
        if (host.isEmpty()) return "unknown"
        val port = if (components.port == -1) "" else ":${components.port}"
        return "$scheme://$host$port"
    }

    /// Voice turns, streams, lifecycle, navigation, and failed requests: what
    /// a reader scans first. Newest last.
    fun highlights(events: List<DiagnosticsEvent>, count: Int = 15): List<String> {
        val telling = events.filter { event ->
            when (event.kind) {
                DiagnosticsEvent.Kind.voice, DiagnosticsEvent.Kind.stream, DiagnosticsEvent.Kind.lifecycle, DiagnosticsEvent.Kind.navigation -> true
                DiagnosticsEvent.Kind.requestEnd ->
                    (event.errorCategory != null && event.errorCategory != DiagnosticsErrorCategory.cancelled) ||
                        (event.statusCode ?: 200) >= 400
                DiagnosticsEvent.Kind.mark, DiagnosticsEvent.Kind.requestStart, DiagnosticsEvent.Kind.networkMetrics -> false
            }
        }
        val text = DiagnosticsRecorder.exportText(telling.takeLast(count), wallClock = true)
        return text.split('\n').drop(4).filter { it.isNotEmpty() }
    }

    /// The prompt Phone Reports receives. `inlineTimeline` stands in for the
    /// attached files on a server without uploads.
    fun summary(report: ProblemReport, hasScreenshot: Boolean, inlineTimeline: String? = null): String {
        val device = report.device
        val context = report.context
        val lines = mutableListOf("## OpenCode Android problem report: ${report.trigger.title}", "")
        val note = report.note.trim()
        if (note.isNotEmpty()) {
            lines += note.split('\n').map { "> $it" }
            lines += ""
        }
        var screen = context.screen
        context.sessionID?.let { screen += " `$it`" }
        val title = context.sessionTitle
        if (!title.isNullOrEmpty()) screen += " “$title”"
        context.project?.let { screen += " in $it" }
        lines += listOf(
            "- Reported: ${DateTimeFormatter.ISO_INSTANT.format(report.createdAt)}",
            "- App: ${device.appVersion} (${device.build}), ${device.distribution}",
            "- Device: ${device.model}, ${device.system}",
            "- Server: ${context.server}",
            "- Screen: $screen",
            "- Connection: ${context.connection}",
        )
        context.call?.let { lines += "- Call: $it" }
        device.liveActivities?.let { lines += "- Live Activities: $it" }
        if (report.attempts > 0) lines += "- Delivery attempts before this one: ${report.attempts}"
        if (!hasScreenshot) lines += "- Screenshot: none"
        if (report.highlights.isNotEmpty()) {
            lines += listOf("", if (report.trigger == ProblemReportTrigger.crash) "End of the previous run:" else "Recent timeline:", "```text")
            lines += report.highlights
            lines += "```"
        }
        if (inlineTimeline != null) {
            lines += listOf(
                "",
                "Timeline (this server can’t take uploads, so it’s inline and may be cut):",
                "```text",
                inlineTimeline.trim('\n'),
                "```",
            )
        }
        return lines.joinToString("\n")
    }

    /// The end of `text` within `limit` UTF-8 bytes, cut at a line.
    fun tail(text: String, limit: Int): String {
        val encoded = text.toByteArray(Charsets.UTF_8)
        if (encoded.size <= limit) return text
        val bytes = encoded.copyOfRange(encoded.size - limit, encoded.size)
        val newline = bytes.indexOf('\n'.code.toByte())
        val start = if (newline >= 0) newline + 1 else 0
        return "… (earlier lines omitted)\n" + String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /// The newest events whose JSON fits `limit`.
    fun boundedJSON(events: List<DiagnosticsEvent>, limit: Int): ByteArray {
        var kept = events
        var data = DiagnosticsRecorder.exportJSON(kept)
        while (data.size > limit && kept.isNotEmpty()) {
            kept = kept.drop(maxOf(1, kept.size / 4))
            data = DiagnosticsRecorder.exportJSON(kept)
        }
        return data
    }
}
