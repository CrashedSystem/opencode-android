@file:PortedFrom("OpenCode/LabMarks.swift", "553a407")

package ai.opencode.mobilecode

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/// Shared-clock timing marks for the responsiveness gauntlet and the opt-in
/// on-device diagnostics timeline. Every mark is an
/// OSLog line in `opencode.perf`; when a DEBUG build is launched with
/// `OPENCODE_LAB_MARKS_URL`, the same mark is also posted to the fixture so
/// its choreography timeline and the app's state changes can be paired.
/// Wall clock, not monotonic: the fixture runs on the same machine as the
/// simulator and stamps its own actions with `Date()` too.
object LabMarks {
    private val onceKeys = ConcurrentHashMap.newKeySet<String>()
    val endpoint: String? = if (BuildConfig.DEBUG) System.getenv("OPENCODE_LAB_MARKS_URL") else null

    val isEnabled: Boolean get() = endpoint != null
    val needsNetworkMetrics: Boolean
        get() = endpoint != null || DiagnosticsRecorder.shared.isEnabled

    fun mark(
        name: String,
        sessionID: String? = null,
        values: Map<String, Int> = emptyMap(),
    ) {
        val detail = values.keys.sorted().joinToString(" ") { "$it=${values[it] ?: 0}" }
        Log.i("opencode.perf", "$name session=${sessionID ?: "-"} $detail")
        DiagnosticsRecorder.shared.recordMark(name, sessionID)
        if (endpoint == null) return
        val body = linkedMapOf<String, Any>("name" to name, "t" to System.currentTimeMillis())
        if (sessionID != null) body["sessionID"] = sessionID
        for ((key, value) in values) body[key] = value
        post(body)
    }

    fun markOnce(name: String, sessionID: String? = null) {
        val key = "$name:${sessionID ?: "-"}"
        if (onceKeys.add(key)) mark(name, sessionID)
    }

    fun networkTask(requestID: String, path: String, metrics: URLSessionTaskMetrics) {
        DiagnosticsRecorder.shared.networkMetrics(requestID, path, metrics)
        if (!BuildConfig.DEBUG || endpoint == null) return
        val transaction = metrics.transactionMetrics.lastOrNull() ?: return
        val body = linkedMapOf<String, Any>(
            "name" to "network_task",
            "t" to System.currentTimeMillis(),
            "path" to path,
            "reused" to transaction.isReusedConnection,
        )
        transaction.networkProtocolName?.let { body["protocol"] = it }
        millis(transaction.connectStartDate, transaction.connectEndDate)?.let { body["connectMs"] = it }
        millis(transaction.secureConnectionStartDate, transaction.secureConnectionEndDate)?.let { body["tlsMs"] = it }
        post(body)
    }

    private fun post(body: Map<String, Any>) {
        val endpoint = endpoint ?: return
        val data = runCatching { JSONObject(body).toString().toByteArray(Charsets.UTF_8) }.getOrNull() ?: return
        Thread {
            runCatching {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(data) }
                connection.responseCode
                connection.disconnect()
            }
        }.apply { isDaemon = true }.start()
    }

    fun transcriptSnapshot(
        sessionID: String?,
        ids: List<String>,
        vanished: List<String>,
        reappeared: List<String>,
        rows: List<Map<String, String>> = emptyList(),
        inserted: List<String> = emptyList(),
        reconfigured: List<String> = emptyList(),
        reloaded: List<String> = emptyList(),
    ) {
        if (!BuildConfig.DEBUG || endpoint == null) return
        post(
            mapOf(
                "name" to "transcript_snapshot",
                "t" to System.currentTimeMillis(),
                "sessionID" to (sessionID ?: ""),
                "ids" to ids,
                "vanished" to vanished,
                "reappeared" to reappeared,
                "rows" to rows,
                "insertedIDs" to inserted,
                "reconfigured" to reconfigured,
                "reloaded" to reloaded,
            ),
        )
    }

    private fun millis(start: Long?, end: Long?): Int? {
        if (start == null || end == null) return null
        return (end - start).toInt()
    }
}
