@file:PortedFrom("OpenCode/ProblemReports/ProblemReportStore.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json

/// Reports on disk until Phone Reports has them: one folder per report with
/// `report.json` and its files. Survives the app closing and an unreachable
/// server; delivery deletes the folder.
class ProblemReportStore(
    val root: File,
) {
    fun save(bundle: ProblemReportBundle) {
        val folder = folder(bundle.report.id)
        folder.mkdirs()
        for ((name, data) in bundle.files) {
            writeAtomic(File(folder, name), data)
        }
        update(bundle.report)
        trim()
    }

    fun update(report: ProblemReport) {
        val folder = folder(report.id)
        folder.mkdirs()
        writeAtomic(File(folder, "report.json"), encoder.encodeToString(ProblemReport.serializer(), report).toByteArray())
        // The readable summary travels with the files a share sheet offers.
        val summary = ProblemReportBuilder.summary(report, hasScreenshot = url(report.id, ProblemReportBundle.File.screenshot) != null)
        runCatching { writeAtomic(File(folder, ProblemReportBundle.File.summary), summary.toByteArray()) }
    }

    fun load(id: UUID): ProblemReport? {
        val file = File(folder(id), "report.json")
        if (!file.isFile) return null
        return runCatching { decoder.decodeFromString(ProblemReport.serializer(), file.readText()) }.getOrNull()
    }

    /// Oldest first.
    fun all(): List<ProblemReport> {
        val folders = root.listFiles()?.toList() ?: return emptyList()
        return folders.mapNotNull { folder ->
            runCatching { UUID.fromString(folder.name) }.getOrNull()?.let { load(it) }
        }.sortedBy { it.createdAt }
    }

    fun data(id: UUID, name: String): ByteArray? {
        val file = File(folder(id), name)
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun url(id: UUID, name: String): File? {
        val file = File(folder(id), name)
        return if (file.isFile) file else null
    }

    /// The summary first, then what's attached: for the share sheet.
    fun shareableFiles(id: UUID): List<File> {
        val names = listOf(
            ProblemReportBundle.File.summary,
            ProblemReportBundle.File.screenshot,
            ProblemReportBundle.File.timelineText,
            ProblemReportBundle.File.timelineJSON,
            ProblemReportBundle.File.previousRun,
        )
        return names.mapNotNull { url(id, it) }
    }

    fun delete(id: UUID) {
        folder(id).deleteRecursively()
    }

    fun removeScreenshot(id: UUID) {
        File(folder(id), ProblemReportBundle.File.screenshot).delete()
    }

    private fun folder(id: UUID): File = File(root, id.toString())

    private fun trim() {
        val reports = all()
        for (report in reports.dropLast(maximumReports)) delete(report.id)
    }

    companion object : DependencyKey<ProblemReportStore> {
        /// Older reports beyond this are dropped so the folder stays small.
        const val maximumReports = 12

        private val encoder = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }
        private val decoder = Json { ignoreUnknownKeys = true }

        override val liveValue
            get() = ProblemReportStore(
                File(AppGroup.containerURL ?: File(System.getProperty("java.io.tmpdir"), "OpenCode"), "OpenCode/ProblemReports"),
            )

        override val testValue
            get() = ProblemReportStore(File(System.getProperty("java.io.tmpdir"), "problem-reports-${UUID.randomUUID()}"))
    }
}

var DependencyValues.problemReportStore: ProblemReportStore
    get() = this[ProblemReportStore]
    set(value) {
        this[ProblemReportStore] = value
    }

private fun writeAtomic(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeBytes(bytes)
    if (!temporary.renameTo(file)) {
        file.writeBytes(bytes)
        temporary.delete()
    }
}
