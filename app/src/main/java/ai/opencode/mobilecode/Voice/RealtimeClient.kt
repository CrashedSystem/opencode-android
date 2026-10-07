@file:PortedFrom("OpenCode/Voice/RealtimeClient.swift", "4084466", partial = "RealtimePreflightClient and RealtimePreflightError, which Settings → Advanced verifies keys with; the lab's stand-in preflight and the Realtime connection wait for voice calls")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class RealtimePreflightClient(
    var verify: suspend (apiKey: String, model: String) -> Unit,
) {
    companion object : DependencyKey<RealtimePreflightClient> {
        override val liveValue: RealtimePreflightClient get() = network

        override val testValue: RealtimePreflightClient get() = RealtimePreflightClient(verify = { _, _ -> })

        private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

        private val network = RealtimePreflightClient(
            verify = { apiKey, model ->
                val encodedModel = URLEncoder.encode(model, "UTF-8").replace("+", "%20")
                val request = runCatching {
                    Request.Builder()
                        .url("https://api.openai.com/v1/models/$encodedModel")
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                }.getOrElse { throw RealtimePreflightError.service("The voice model address is invalid.") }
                val (statusCode, data) = try {
                    withContext(Dispatchers.IO) {
                        client.newCall(request).execute().use { it.code to it.body.bytes() }
                    }
                } catch (error: IOException) {
                    throw RealtimePreflightError.transport(error)
                }
                if (statusCode !in 200 until 300) throw RealtimePreflightError.response(statusCode = statusCode, data = data)
            },
        )
    }
}

sealed class RealtimePreflightError(override val message: String) : Exception(message) {
    data object rejectedAPIKey : RealtimePreflightError("OpenAI rejected the API key.")

    data object noConnection : RealtimePreflightError("No connection.")

    data class service(val text: String) : RealtimePreflightError(text)

    companion object {
        fun response(statusCode: Int, data: ByteArray): RealtimePreflightError {
            if (statusCode == 401) return rejectedAPIKey
            val payload = runCatching { envelopeJson.decodeFromString(OpenAIErrorEnvelope.serializer(), data.decodeToString()) }.getOrNull()
            if (payload != null && payload.error.message.isNotBlank()) return service(payload.error.message)
            return service("OpenAI couldn’t verify this key (HTTP $statusCode).")
        }

        /// An I/O failure is iOS's `URLError`: no connection.
        fun transport(error: Throwable): RealtimePreflightError =
            if (error is IOException || error is URLError) noConnection else service(error.message ?: "OpenAI couldn’t verify this key.")

        private val envelopeJson = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class OpenAIErrorEnvelope(val error: Payload) {
        @Serializable
        data class Payload(val message: String)
    }
}

var DependencyValues.realtimePreflight: RealtimePreflightClient
    get() = this[RealtimePreflightClient]
    set(value) {
        this[RealtimePreflightClient] = value
    }
