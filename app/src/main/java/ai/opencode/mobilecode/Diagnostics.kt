@file:PortedFrom("OpenCode/Diagnostics.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.isTesting
import android.content.SharedPreferences
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

const val NSPOSIXErrorDomain = "NSPOSIXErrorDomain"
const val NSURLErrorDomain = "NSURLErrorDomain"
const val NSCocoaErrorDomain = "NSCocoaErrorDomain"

const val ENOTCONN = 57
const val ECONNRESET = 54
const val ECONNABORTED = 53
const val EPIPE = 32
const val ENETDOWN = 50
const val ENETUNREACH = 51
const val EHOSTDOWN = 64
const val EHOSTUNREACH = 65
const val ETIMEDOUT = 60
const val EACCES = 13

class URLError(val code: Code) : Exception("URLError.$code") {
    enum class Code(val rawValue: Int) {
        unknown(-1),
        cancelled(-999),
        timedOut(-1001),
        notConnectedToInternet(-1009),
        networkConnectionLost(-1005),
        internationalRoamingOff(-1018),
        dataNotAllowed(-1020),
    }

    companion object {
        val unknown: Code get() = Code.unknown
    }
}

class NSError(
    val domain: String,
    val code: Int,
    val userInfo: Map<String, Any?> = emptyMap(),
) : Exception("$domain $code")

interface UserDefaults {
    fun `object`(forKey: String): Any?
    fun set(value: Boolean, forKey: String)

    companion object {
        var standard: UserDefaults = MemoryUserDefaults()
    }
}

class MemoryUserDefaults : UserDefaults {
    private val values = mutableMapOf<String, Boolean>()

    override fun `object`(forKey: String): Any? = values[forKey]

    override fun set(value: Boolean, forKey: String) {
        values[forKey] = value
    }
}

class PreferencesUserDefaults(private val preferences: SharedPreferences) : UserDefaults {
    override fun `object`(forKey: String): Any? =
        if (preferences.contains(forKey)) preferences.getBoolean(forKey, false) else null

    override fun set(value: Boolean, forKey: String) {
        preferences.edit().putBoolean(forKey, value).apply()
    }
}

sealed interface DiagnosticsErrorCategory {
    data object unauthorized : DiagnosticsErrorCategory
    data object notFound : DiagnosticsErrorCategory
    data object timeout : DiagnosticsErrorCategory
    data object offline : DiagnosticsErrorCategory
    data object cancelled : DiagnosticsErrorCategory
    data class server(val status: Int) : DiagnosticsErrorCategory {
        override fun toString() = "server($status)"
    }
    data object transport : DiagnosticsErrorCategory

    companion object {
        operator fun invoke(error: Throwable): DiagnosticsErrorCategory = classify(error)

        operator fun invoke(status: Int): DiagnosticsErrorCategory = when (status) {
            401 -> unauthorized
            404 -> notFound
            else -> server(status)
        }

        private fun classify(error: Throwable): DiagnosticsErrorCategory {
            if (error is java.util.concurrent.CancellationException || error is kotlinx.coroutines.CancellationException) return cancelled
            if (error is URLError) {
                return when (error.code) {
                    URLError.Code.timedOut -> timeout
                    URLError.Code.notConnectedToInternet,
                    URLError.Code.networkConnectionLost,
                    URLError.Code.internationalRoamingOff,
                    URLError.Code.dataNotAllowed,
                    -> offline
                    URLError.Code.cancelled -> cancelled
                    else -> transport
                }
            }
            if (error is SocketTimeoutException) return timeout
            if (error is UnknownHostException || error is ConnectException || error is NoRouteToHostException) return offline
            // A WebSocket that loses its network fails with the socket's errno.
            val posix = error as? NSError ?: return transport
            if (posix.domain != NSPOSIXErrorDomain) return transport
            return when (posix.code) {
                ETIMEDOUT -> timeout
                ENOTCONN, ECONNRESET, ECONNABORTED, EPIPE, ENETDOWN, ENETUNREACH, EHOSTDOWN, EHOSTUNREACH -> offline
                else -> transport
            }
        }
    }
}

@Serializable
data class DiagnosticsEvent(
    @Serializable(with = UUIDAsStringSerializer::class) var id: UUID,
    var kind: Kind,
    var monotonicSeconds: Double,
    @Serializable(with = InstantIsoSerializer::class) var wallDate: Instant,
    var name: String,
    var sessionID: String? = null,
    var navigationEpoch: Int? = null,
    var requestID: String? = null,
    var method: String? = null,
    var path: String? = null,
    var reason: String? = null,
    var statusCode: Int? = null,
    @Serializable(with = ErrorCategorySerializer::class) var errorCategory: DiagnosticsErrorCategory? = null,
    var durationMilliseconds: Double? = null,
    var wireBytes: Long? = null,
    var decodedBytes: Long? = null,
    var contentEncoding: String? = null,
    var protocolName: String? = null,
    var connectionReused: Boolean? = null,
    var dnsMilliseconds: Double? = null,
    var connectMilliseconds: Double? = null,
    var tlsMilliseconds: Double? = null,
    var firstByteMilliseconds: Double? = null,
    var downloadMilliseconds: Double? = null,
    var transactionCount: Int? = null,
    /// A short excerpt of what was said, kept only while Include Transcripts is on.
    var excerpt: String? = null,
) {
    @Serializable
    enum class Kind { lifecycle, navigation, mark, requestStart, requestEnd, networkMetrics, stream, voice }
}

/**
 * A bounded recorder suitable for shipping builds: opt-in on the App Store,
 * on by default in debug and TestFlight builds, where it also persists to a
 * small rolling journal so a report can include the run before a crash.
 * Events contain timing and routing metadata only; headers and bodies have no
 * place in this model. The one exception is a voice turn's excerpt, kept only
 * while Include Transcripts is on.
 */
class DiagnosticsRecorder(
    enabled: Boolean? = null,
    private val maximumEvents: Int = 2_000,
    private val retentionSeconds: Double = 600.0,
    private val defaults: UserDefaults? = UserDefaults.standard,
    private val defaultEnabled: Boolean = false,
    private val journal: DiagnosticsJournal? = null,
    private val monotonicNow: () -> Double = { systemUptimeSeconds() },
    private val wallNow: () -> Instant = { Instant.now() },
) {
    private class State(
        var enabled: Boolean,
        var includesTranscripts: Boolean,
        /// A change notification is already scheduled.
        var notifyPending: Boolean = false,
        val events: MutableList<DiagnosticsEvent> = mutableListOf(),
        var navigationEpoch: Int = 0,
        var currentNavigationEpoch: Int? = null,
        var currentSessionID: String? = null,
        val requestStarts: MutableMap<String, Double> = mutableMapOf(),
    )

    private val lock = Any()
    private val state: State
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "opencode.diagnostics.notify").apply { isDaemon = true }
    }

    /// What the journal held at launch: the tail of the previous run.
    val previousRun: DiagnosticsJournal.PreviousRun?

    val didChange: SharedFlow<Unit> = changes.asSharedFlow()

    init {
        val environmentEnabled = System.getenv("OPENCODE_DIAGNOSTICS_ENABLED") == "1" ||
            (System.getProperty("opencode.arguments") ?: "").split(" ").contains("-diagnostics-enabled")
        val stored = defaults?.`object`(defaultsKey) as? Boolean
        val initial = enabled ?: (environmentEnabled || (stored ?: defaultEnabled))
        val transcripts = (defaults?.`object`(transcriptsDefaultsKey) as? Boolean) ?: defaultEnabled
        state = State(enabled = initial, includesTranscripts = transcripts)
        previousRun = journal?.open(recording = initial)
    }

    val isEnabled: Boolean get() = synchronized(lock) { state.enabled }

    /// Voice turns keep a short excerpt of what was said. On by default
    /// wherever recording is.
    val includesTranscripts: Boolean get() = synchronized(lock) { state.includesTranscripts }

    /// Turning transcripts off also forgets every excerpt already kept, in
    /// memory and in the journal.
    fun setIncludesTranscripts(value: Boolean) {
        defaults?.set(value, transcriptsDefaultsKey)
        synchronized(lock) {
            state.includesTranscripts = value
            if (!value) state.events.replaceWith(withoutExcerpts(state.events))
        }
        if (!value) journal?.removeExcerpts()
        notifyChange()
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) { state.enabled = enabled }
        defaults?.set(enabled, defaultsKey)
        // Turning recording off also forgets what this phone kept on disk.
        if (!enabled) journal?.clear()
        notifyChange()
    }

    fun clear() {
        synchronized(lock) {
            state.events.clear()
            state.requestStarts.clear()
            state.currentNavigationEpoch = null
            state.currentSessionID = null
        }
        journal?.clear()
        notifyChange()
    }

    fun snapshot(): List<DiagnosticsEvent> = synchronized(lock) {
        if (!state.enabled) return emptyList()
        prune(state, monotonicNow())
        if (state.includesTranscripts) state.events.toList() else withoutExcerpts(state.events)
    }

    fun recordMark(name: String, sessionID: String?) {
        record { current, now, wall ->
            if (name == "session_tap") {
                current.navigationEpoch += 1
                current.currentNavigationEpoch = current.navigationEpoch
                current.currentSessionID = sessionID
            } else if (name == "session_navigation_started" &&
                (current.currentNavigationEpoch == null || current.currentSessionID != sessionID)
            ) {
                current.navigationEpoch += 1
                current.currentNavigationEpoch = current.navigationEpoch
                current.currentSessionID = sessionID
            }
            val kind = when {
                name == "session_tap" || name == "session_navigation_started" -> DiagnosticsEvent.Kind.navigation
                name.startsWith("app_") -> DiagnosticsEvent.Kind.lifecycle
                name.startsWith("sse_") || name.startsWith("stream_") || name.startsWith("voice_") -> DiagnosticsEvent.Kind.stream
                else -> DiagnosticsEvent.Kind.mark
            }
            DiagnosticsEvent(
                id = UUID.randomUUID(),
                kind = kind,
                monotonicSeconds = now,
                wallDate = wall,
                name = name,
                sessionID = sessionID,
                navigationEpoch = current.currentNavigationEpoch,
            )
        }
    }

    /// One mark on a voice call's turn timeline. `excerpt` is dropped unless
    /// Include Transcripts is on, and is cut to `excerptLimit` characters.
    fun recordVoice(name: String, sessionID: String?, detail: String? = null, excerpt: String? = null) {
        val kept = excerpt?.let { excerpt(it) }
        record { current, now, wall ->
            DiagnosticsEvent(
                id = UUID.randomUUID(),
                kind = DiagnosticsEvent.Kind.voice,
                monotonicSeconds = now,
                wallDate = wall,
                name = name,
                sessionID = sessionID ?: current.currentSessionID,
                navigationEpoch = current.currentNavigationEpoch,
                reason = detail,
                excerpt = if (current.includesTranscripts) kept else null,
            )
        }
    }

    /// A voice call's Realtime socket failed, closed, or expired. Only the
    /// error's domain and codes and the service's close reason are kept.
    fun recordVoiceSocketEvent(
        name: String,
        domain: String,
        code: Int,
        closeCode: Int,
        underlyingDomain: String? = null,
        underlyingCode: Int? = null,
        closeReason: String? = null,
        errorCategory: DiagnosticsErrorCategory? = DiagnosticsErrorCategory.transport,
    ) {
        val parts = mutableListOf("domain=$domain", "code=$code", "closeCode=$closeCode")
        if (underlyingDomain != null && underlyingCode != null) {
            parts += "underlying=$underlyingDomain:$underlyingCode"
        }
        if (!closeReason.isNullOrEmpty()) parts += "closeReason=$closeReason"
        val summary = parts.joinToString(" ")
        record { current, now, wall ->
            DiagnosticsEvent(
                id = UUID.randomUUID(),
                kind = DiagnosticsEvent.Kind.stream,
                monotonicSeconds = now,
                wallDate = wall,
                name = name,
                sessionID = current.currentSessionID,
                navigationEpoch = current.currentNavigationEpoch,
                reason = summary,
                statusCode = code,
                errorCategory = errorCategory,
            )
        }
    }

    fun requestStarted(id: String, method: String, url: URI, reason: String?) {
        record { current, now, wall ->
            current.requestStarts[id] = now
            DiagnosticsEvent(
                id = UUID.randomUUID(),
                kind = DiagnosticsEvent.Kind.requestStart,
                monotonicSeconds = now,
                wallDate = wall,
                name = "request_start",
                sessionID = sessionID(url.path),
                navigationEpoch = current.currentNavigationEpoch,
                requestID = id,
                method = method,
                path = redactedPath(url),
                reason = reason?.let { redactedPath(it) },
            )
        }
    }

    fun requestEnded(
        id: String,
        method: String,
        url: URI,
        statusCode: Int?,
        errorCategory: DiagnosticsErrorCategory?,
        decodedBytes: Long,
        contentEncoding: String?,
    ) {
        record { current, now, wall ->
            val start = current.requestStarts.remove(id)
            DiagnosticsEvent(
                id = UUID.randomUUID(),
                kind = DiagnosticsEvent.Kind.requestEnd,
                monotonicSeconds = now,
                wallDate = wall,
                name = "request_end",
                sessionID = sessionID(url.path),
                navigationEpoch = current.currentNavigationEpoch,
                requestID = id,
                method = method,
                path = redactedPath(url),
                statusCode = statusCode,
                errorCategory = errorCategory,
                durationMilliseconds = start?.let { max(0.0, now - it) * 1_000 },
                decodedBytes = decodedBytes,
                contentEncoding = if (contentEncoding.isNullOrEmpty()) "identity" else contentEncoding,
            )
        }
    }

    fun networkMetrics(requestID: String, path: String, metrics: URLSessionTaskMetrics) {
        val transactions = metrics.transactionMetrics
        if (transactions.isEmpty()) return
        record { current, now, wall ->
            DiagnosticsEvent(
                id = UUID.randomUUID(),
                kind = DiagnosticsEvent.Kind.networkMetrics,
                monotonicSeconds = now,
                wallDate = wall,
                name = "network_metrics",
                navigationEpoch = current.currentNavigationEpoch,
                requestID = requestID,
                path = path,
                wireBytes = transactions.fold(0L) { sum, transaction -> sum + transaction.countOfResponseBodyBytesReceived },
                protocolName = transactions.mapNotNull { it.networkProtocolName }.lastOrNull(),
                connectionReused = transactions.all { it.isReusedConnection },
                dnsMilliseconds = totalDuration(transactions, { it.domainLookupStartDate }, { it.domainLookupEndDate }),
                connectMilliseconds = totalDuration(transactions, { it.connectStartDate }, { it.connectEndDate }),
                tlsMilliseconds = totalDuration(transactions, { it.secureConnectionStartDate }, { it.secureConnectionEndDate }),
                firstByteMilliseconds = totalDuration(transactions, { it.requestEndDate }, { it.responseStartDate }),
                downloadMilliseconds = totalDuration(transactions, { it.responseStartDate }, { it.responseEndDate }),
                transactionCount = transactions.size,
            )
        }
    }

    fun exportJSON(events: List<DiagnosticsEvent>? = null): ByteArray = Companion.exportJSON(events ?: snapshot())

    /// `wallClock` adds each event's UTC time, for lining a report up with server logs.
    fun exportText(events: List<DiagnosticsEvent>? = null, wallClock: Boolean = false): String =
        Companion.exportText(events ?: snapshot(), wallClock)

    /// The previous run's journaled events, oldest first; empty without a journal.
    fun previousRunEvents(): List<DiagnosticsEvent> {
        val events = journal?.previousRunEvents() ?: emptyList()
        return if (includesTranscripts) events else withoutExcerpts(events)
    }

    /// How long the app has been in the foreground without interruption.
    val foregroundSeconds: Double? get() = journal?.foregroundSeconds(monotonicNow())

    private fun record(make: (State, Double, Instant) -> DiagnosticsEvent) {
        if (!isEnabled) return
        val now = monotonicNow()
        val wall = wallNow()
        val inserted = synchronized(lock) {
            if (!state.enabled) return@synchronized null
            val value = make(state, now, wall)
            append(state, value, now)
            value
        } ?: return
        journal?.append(inserted)
        notifyChange()
    }

    private fun append(current: State, event: DiagnosticsEvent, now: Double) {
        current.events += event
        prune(current, now)
        if (current.events.size > maximumEvents) {
            current.events.subList(0, current.events.size - maximumEvents).clear()
        }
    }

    private fun prune(current: State, now: Double) {
        val oldest = now - retentionSeconds
        val firstKept = current.events.indexOfFirst { it.monotonicSeconds >= oldest }
        if (firstKept > 0) {
            current.events.subList(0, firstKept).clear()
        } else if (firstKept < 0 && current.events.isNotEmpty()) {
            current.events.clear()
        }
    }

    private fun notifyChange() {
        val schedule = synchronized(lock) {
            if (state.notifyPending) {
                false
            } else {
                state.notifyPending = true
                true
            }
        }
        if (!schedule) return
        scheduler.schedule({
            synchronized(lock) { state.notifyPending = false }
            changes.tryEmit(Unit)
        }, (notifyInterval * 1_000).toLong(), TimeUnit.MILLISECONDS)
    }

    companion object {
        val shared: DiagnosticsRecorder by lazy {
            // The unit-test host keeps the opt-in default and writes no journal.
            if (!InternalBuild.isEnabled || isTesting) {
                DiagnosticsRecorder()
            } else {
                DiagnosticsRecorder(
                    maximumEvents = 5_000,
                    retentionSeconds = 900.0,
                    defaultEnabled = true,
                    journal = runCatching { DiagnosticsJournal.live() }.getOrNull(),
                )
            }
        }

        const val didChangeName = "OpenCodeDiagnosticsDidChange"
        const val defaultsKey = "diagnostics.enabled"
        const val transcriptsDefaultsKey = "diagnostics.includeTranscripts"
        const val excerptLimit = 120

        /// Change notifications are coalesced to at most this often: with
        /// recording on by default, every request records three events.
        const val notifyInterval = 0.25

        fun withoutExcerpts(events: List<DiagnosticsEvent>): List<DiagnosticsEvent> {
            if (events.none { it.excerpt != null }) return events
            return events.map { event -> event.copy(excerpt = null) }
        }

        fun excerpt(text: String): String? {
            val flattened = text.split(Regex("\\R")).filter { it.isNotEmpty() }.joinToString(" ").trim()
            if (flattened.isEmpty()) return null
            if (flattened.length <= excerptLimit) return flattened
            return flattened.take(excerptLimit - 1) + "…"
        }

        fun exportJSON(events: List<DiagnosticsEvent>): ByteArray =
            (runCatching { diagnosticsExportJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(DiagnosticsEvent.serializer()), events) }
                .getOrDefault("[]")).toByteArray()

        fun exportText(values: List<DiagnosticsEvent>, wallClock: Boolean = false): String {
            val origin = values.firstOrNull()?.monotonicSeconds ?: return "OpenCode diagnostics\n(no events)\n"
            val lines = mutableListOf("OpenCode diagnostics", "Events: ${values.size}")
            if (wallClock) {
                val first = values.first()
                val last = values.last()
                lines += "From ${DateTimeFormatter.ISO_INSTANT.format(first.wallDate)} to ${DateTimeFormatter.ISO_INSTANT.format(last.wallDate)}"
            }
            lines += ""
            for (event in values) {
                val relative = String.format(Locale.US, "+%.3fs", event.monotonicSeconds - origin)
                val pieces = mutableListOf<String>()
                pieces += relative
                if (wallClock) pieces += event.wallDate.atOffset(ZoneOffset.UTC).format(wallClockFormat)
                pieces += "[${event.kind.name}]"
                pieces += event.name
                event.navigationEpoch?.let { pieces += "epoch=$it" }
                event.method?.let { pieces += it }
                event.path?.let { pieces += it }
                event.durationMilliseconds?.let { pieces += String.format(Locale.US, "%.0fms", it) }
                event.statusCode?.let { pieces += "status=$it" }
                event.errorCategory?.let { pieces += "error=$it" }
                if ((event.kind == DiagnosticsEvent.Kind.stream || event.kind == DiagnosticsEvent.Kind.voice) && event.reason != null) {
                    pieces += event.reason!!
                }
                event.excerpt?.let { pieces += "“$it”" }
                event.wireBytes?.let { pieces += "wire=$it" }
                event.decodedBytes?.let { pieces += "decoded=$it" }
                event.contentEncoding?.let { pieces += "encoding=$it" }
                event.protocolName?.let { pieces += "protocol=$it" }
                event.connectionReused?.let { pieces += "reused=$it" }
                lines += pieces.joinToString(" ")
            }
            return lines.joinToString("\n") + "\n"
        }

        fun redactedPath(url: URI): String {
            val path = redactedPath(url.rawPath ?: url.path)
            val names = url.rawQuery?.split("&")?.map { it.substringBefore("=") }?.filter { it.isNotEmpty() } ?: emptyList()
            return path + if (names.isEmpty()) "" else "?" + names.joinToString("&") { "$it=<redacted>" }
        }

        /// Path segments that carry credentials or file names: `/auth/<route>/…`
        /// (a pairing code is the credential) and `/api/fs/read/<file>`. A query,
        /// if any, is dropped.
        fun redactedPath(path: String): String {
            val bare = path.split("?", limit = 2).first()
            for (prefix in listOf("/auth/", "/api/fs/read/")) {
                if (!bare.startsWith(prefix)) continue
                val rest = bare.removePrefix(prefix)
                val kept = if (prefix == "/auth/") {
                    rest.split("/", limit = 2).firstOrNull()?.takeIf { it.isNotEmpty() }?.let { "$it/" } ?: ""
                } else {
                    ""
                }
                return prefix + kept + "<redacted>"
            }
            return bare
        }

        private fun sessionID(path: String): String? {
            val parts = path.split("/").filter { it.isNotEmpty() }
            val index = parts.indexOf("session")
            if (index < 0 || index + 1 >= parts.size) return null
            return parts[index + 1]
        }

        private fun totalDuration(
            transactions: List<URLSessionTaskTransactionMetrics>,
            start: (URLSessionTaskTransactionMetrics) -> Instant?,
            end: (URLSessionTaskTransactionMetrics) -> Instant?,
        ): Double? {
            val values = transactions.mapNotNull { transaction ->
                val started = start(transaction) ?: return@mapNotNull null
                val ended = end(transaction) ?: return@mapNotNull null
                max(0.0, (ended.toEpochMilli() - started.toEpochMilli()).toDouble())
            }
            return if (values.isEmpty()) null else values.sum()
        }

        private val wallClockFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}

data class URLSessionTaskTransactionMetrics(
    var countOfResponseBodyBytesReceived: Long = 0,
    var networkProtocolName: String? = null,
    var isReusedConnection: Boolean = false,
    var domainLookupStartDate: Instant? = null,
    var domainLookupEndDate: Instant? = null,
    var connectStartDate: Instant? = null,
    var connectEndDate: Instant? = null,
    var secureConnectionStartDate: Instant? = null,
    var secureConnectionEndDate: Instant? = null,
    var requestEndDate: Instant? = null,
    var responseStartDate: Instant? = null,
    var responseEndDate: Instant? = null,
)

data class URLSessionTaskMetrics(
    var transactionMetrics: List<URLSessionTaskTransactionMetrics> = emptyList(),
)

internal object UUIDAsStringSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

internal object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value))
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

internal object ErrorCategorySerializer : KSerializer<DiagnosticsErrorCategory> {
    override val descriptor = PrimitiveSerialDescriptor("DiagnosticsErrorCategory", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: DiagnosticsErrorCategory) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): DiagnosticsErrorCategory {
        val raw = decoder.decodeString()
        return when {
            raw == "unauthorized" -> DiagnosticsErrorCategory.unauthorized
            raw == "notFound" -> DiagnosticsErrorCategory.notFound
            raw == "timeout" -> DiagnosticsErrorCategory.timeout
            raw == "offline" -> DiagnosticsErrorCategory.offline
            raw == "cancelled" -> DiagnosticsErrorCategory.cancelled
            raw == "transport" -> DiagnosticsErrorCategory.transport
            raw.startsWith("server(") && raw.endsWith(")") ->
                DiagnosticsErrorCategory.server(raw.removePrefix("server(").removeSuffix(")").toInt())
            else -> DiagnosticsErrorCategory.transport
        }
    }
}

internal val diagnosticsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

internal val diagnosticsExportJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    prettyPrint = true
}

internal fun systemUptimeSeconds(): Double = try {
    android.os.SystemClock.elapsedRealtime() / 1000.0
} catch (_: RuntimeException) {
    System.nanoTime() / 1_000_000_000.0
}

private fun <T> MutableList<T>.replaceWith(values: List<T>) {
    clear()
    addAll(values)
}
