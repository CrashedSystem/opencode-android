@file:PortedFrom("OpenCode/DiagnosticsJournal.swift", "553a407")

package ai.opencode.mobilecode

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference


/// Debug and TestFlight builds: diagnostics record by default and problem
/// reports are available. App Store builds are unchanged.
object InternalBuild {
    /// Synchronous, unlike StoreKit's `AppTransaction`, so launch can decide.
    val receiptURL: File? = null

    val isEnabled: Boolean = detect(isDebug = BuildConfig.DEBUG, receiptURL = receiptURL)

    val isTestFlight: Boolean get() = detect(isDebug = false, receiptURL = receiptURL)

    /// TestFlight installs carry a sandbox receipt; the App Store's is `receipt`.
    fun detect(isDebug: Boolean, receiptURL: File?): Boolean =
        isDebug || receiptURL?.name == "sandboxReceipt"
}

/// The diagnostics timeline on disk: an append-only JSON-lines file per run
/// that rotates into one older segment, so a run keeps at most two segments.
/// Each write reaches the file before the next event, so the tail survives a
/// crash or a kill. At launch the previous run's segments are set aside, and
/// a marker written on activation and cleared on backgrounding tells whether
/// that run ended while it was in the foreground.
class DiagnosticsJournal(
    val directory: File,
    val segmentBytes: Int = 768 * 1_024,
    /// "version (build)", written into the foreground marker.
    val build: String = currentBuild,
) {
    class PreviousRun(
        /// The run ended in the foreground without backgrounding first: a crash,
        /// a watchdog kill, or a debugger stop.
        var endedUnexpectedly: Boolean,
        var lastEventAt: Instant?,
        /// The build that was in the foreground; an update replaces the process
        /// without backgrounding it, which is not this build's crash.
        var build: String?,
    ) {
        override fun equals(other: Any?): Boolean {
            val run = other as? PreviousRun ?: return false
            return endedUnexpectedly == run.endedUnexpectedly && lastEventAt == run.lastEventAt && build == run.build
        }

        override fun hashCode(): Int = endedUnexpectedly.hashCode()
    }

    private val queue = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode.diagnostics.journal").apply { isDaemon = true }
    }

    /// Confined to `queue`.
    private var handle: FileOutputStream? = null
    private var bytes = 0
    private var isRecording = false

    /// System uptime at the last activation; nil while inactive. A lock, not
    /// the queue, so the main thread never waits behind a file write.
    private val activeSince = AtomicReference<Double?>(null)

    private val current: File get() = File(directory, "current.jsonl")
    private val older: File get() = File(directory, "current.1.jsonl")
    private val previous: List<File>
        get() = listOf(File(directory, "previous.1.jsonl"), File(directory, "previous.jsonl"))
    private val marker: File get() = File(directory, "foreground")

    /// Application Support/OpenCode/Diagnostics, following the app's
    /// foreground state for the unclean-exit marker.
    fun open(recording: Boolean): PreviousRun = sync {
        directory.mkdirs()
        val markedBuild = marker.takeIf { it.isFile }?.readBytes()?.toString(Charsets.UTF_8)
        marker.delete()
        val segments = listOf(older, current)
        val lastEventAt = segments.mapNotNull { file ->
            file.takeIf { it.isFile }?.let { Instant.ofEpochMilli(it.lastModified()) }
        }.maxOrNull()
        if (segments.any { it.exists() }) {
            for ((source, destination) in segments.zip(previous)) {
                destination.delete()
                if (source.exists()) source.renameTo(destination)
            }
        }
        isRecording = recording
        PreviousRun(
            endedUnexpectedly = markedBuild == build && lastEventAt != null,
            lastEventAt = lastEventAt,
            build = markedBuild,
        )
    }

    fun append(event: DiagnosticsEvent) {
        queue.execute {
            isRecording = true
            val line = runCatching { diagnosticsJson.encodeToString(DiagnosticsEvent.serializer(), event) }.getOrNull() ?: return@execute
            val bytesLine = (line + "\n").toByteArray()
            if (bytes + bytesLine.size > segmentBytes && handle != null) rotate()
            val opened = handle ?: openCurrent() ?: return@execute
            try {
                opened.write(bytesLine)
                opened.flush()
                bytes += bytesLine.size
            } catch (_: Exception) {
                runCatching { opened.close() }
                handle = null
            }
        }
    }

    /// Forgets this run and the previous one.
    fun clear() {
        queue.execute {
            runCatching { handle?.close() }
            handle = null
            bytes = 0
            isRecording = false
            for (url in listOf(current, older) + previous) url.delete()
        }
    }

    /// Rewrites every segment without voice excerpts: Include Transcripts was turned off.
    fun removeExcerpts() {
        queue.execute {
            runCatching { handle?.close() }
            handle = null
            for (url in listOf(older, current) + previous) {
                val events = events(url)
                if (events.none { it.excerpt != null }) continue
                val rewritten = events.map { it.copy(excerpt = null) }
                val data = rewritten.joinToString("\n") { diagnosticsJson.encodeToString(DiagnosticsEvent.serializer(), it) }
                atomicWrite(url, if (data.isEmpty()) ByteArray(0) else (data + "\n").toByteArray())
            }
            bytes = 0
        }
    }

    /// The previous run's events, oldest first. Reads up to two segments, so
    /// call it when building a report, not at launch.
    fun previousRunEvents(): List<DiagnosticsEvent> = sync { previous.flatMap { events(it) } }

    /// This run's events as written, oldest first.
    fun currentRunEvents(): List<DiagnosticsEvent> = sync {
        runCatching { handle?.fd?.sync() }
        listOf(older, current).flatMap { events(it) }
    }

    /// Waits for queued writes; tests and teardown use it.
    fun flush() {
        sync { }
    }

    fun foregroundSeconds(now: Double): Double? = activeSince.get()?.let { now - it }

    fun markForeground(at: Double = systemUptimeSeconds()) {
        activeSince.set(at)
        queue.execute {
            if (!isRecording) return@execute
            directory.mkdirs()
            marker.writeBytes(build.toByteArray())
        }
    }

    fun markInactive() {
        activeSince.set(null)
    }

    /// `waits` only at termination, where an async removal might never run.
    fun markBackground(waits: Boolean = false) {
        activeSince.set(null)
        val remove = { marker.delete() }
        if (waits) sync { remove() } else queue.execute { remove() }
    }

    private fun followApplicationState() {
        val attach = {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> markForeground()
                        Lifecycle.Event.ON_PAUSE -> markInactive()
                        Lifecycle.Event.ON_STOP -> markBackground()
                        else -> Unit
                    }
                },
            )
        }
        val looper = Looper.getMainLooper()
        if (looper != null && Looper.myLooper() == looper) attach() else if (looper != null) Handler(looper).post { attach() }
    }

    private fun rotate() {
        runCatching { handle?.close() }
        handle = null
        bytes = 0
        older.delete()
        if (current.exists()) current.renameTo(older)
    }

    private fun openCurrent(): FileOutputStream? {
        directory.mkdirs()
        if (!current.exists()) current.createNewFile()
        val opened = runCatching { FileOutputStream(current, true) }.getOrNull() ?: return null
        bytes = current.length().toInt()
        handle = opened
        return opened
    }

    private fun <T> sync(block: () -> T): T = queue.submit(block).get()

    companion object {
        val currentBuild: String
            get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        fun live(): DiagnosticsJournal {
            val root = AppGroup.containerURL ?: File(System.getProperty("java.io.tmpdir"), "OpenCode")
            val journal = DiagnosticsJournal(File(root, "OpenCode/Diagnostics"))
            journal.followApplicationState()
            return journal
        }

        private fun events(url: File): List<DiagnosticsEvent> {
            if (!url.isFile) return emptyList()
            val decoder = diagnosticsJson
            // A torn last line from a crash is skipped, not fatal.
            return url.readText().split("\n").mapNotNull { line ->
                if (line.isEmpty()) null else runCatching { decoder.decodeFromString(DiagnosticsEvent.serializer(), line) }.getOrNull()
            }
        }

        private fun atomicWrite(url: File, data: ByteArray) {
            val temporary = File(url.parentFile, url.name + ".tmp")
            temporary.writeBytes(data)
            if (!temporary.renameTo(url)) {
                url.writeBytes(data)
                temporary.delete()
            }
        }
    }
}

internal fun installDiagnosticsStorage(context: android.content.Context) {
    UserDefaults.standard = PreferencesUserDefaults(
        context.getSharedPreferences("ai.opencode.mobilecode.diagnostics", android.content.Context.MODE_PRIVATE),
    )
    AppGroup.containerURL = context.filesDir
}

