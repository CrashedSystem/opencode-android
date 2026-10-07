@file:PortedFrom("OpenCode/Traces/DeveloperMode.swift", "494e433", partial = "all but upload(VoiceCallLog), which arrives with the voice call log")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import java.security.SecureRandom
import java.util.Base64
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/// Developer Mode: everything worth debugging goes to the log server
/// (`packages/voice-logs`) under this phone's random owner key: each
/// finished voice call, and traces (see `TraceUploadsFeature`). Anyone with
/// `webURL` can view them; they expire after 30 days. Off by default; while
/// it's on, diagnostics recording stays on too, so traces have a timeline.
object DeveloperMode {
    const val base = "https://opencode-voice-logs.anomalyco.workers.dev"

    val isEnabled: Boolean
        get() {
            val enabled by Shared(SharedKey.developerMode)
            return enabled
        }

    /// This phone's owner key, made once.
    fun owner(): String {
        var owner by Shared(SharedKey.developerModeOwner)
        owner?.let { if (it.length == 64) return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val made = bytes.joinToString("") { "%02x".format(it) }
        owner = made
        return made
    }

    val webURL: String get() = "$base/c/${owner()}"

    suspend fun upload(bundle: ProblemReportBundle): Boolean =
        put("v1/traces", id = bundle.report.id, body = traceBody(bundle).toString())

    /// The worker's trace shape: the report, a readable summary, and its files
    /// (text as text, the screenshot as base64).
    fun traceBody(bundle: ProblemReportBundle): JsonObject {
        val report = bundle.report
        return buildJsonObject {
            put("id", report.id.toString().uppercase())
            put("createdAt", DateTimeFormatter.ISO_INSTANT.format(report.createdAt.truncatedTo(ChronoUnit.SECONDS)))
            put("trigger", report.trigger.name)
            put("title", report.trigger.title)
            put("summary", ProblemReportBuilder.summary(report, hasScreenshot = bundle.files[ProblemReportBundle.File.screenshot] != null))
            put("device", jsonObject(ProblemReportDevice.serializer(), report.device))
            put("context", jsonObject(ProblemReportContext.serializer(), report.context))
            put("highlights", buildJsonArray { report.highlights.forEach { add(JsonPrimitive(it)) } })
            put(
                "files",
                buildJsonObject {
                    for ((name, data) in bundle.files) {
                        put(name, if (name.endsWith(".jpg")) Base64.getEncoder().encodeToString(data) else String(data, Charsets.UTF_8))
                    }
                },
            )
        }
    }

    private val json = Json { explicitNulls = false }

    private fun <Value> jsonObject(serializer: KSerializer<Value>, value: Value): JsonElement =
        runCatching { json.encodeToJsonElement(serializer, value) }.getOrElse { JsonObject(emptyMap()) }

    private suspend fun put(path: String, id: UUID, body: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/$path/${owner()}/${id.toString().uppercase()}")
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        runCatching { client.newCall(request).execute().use { it.code == 201 } }.getOrDefault(false)
    }
}
