@file:PortedFrom("OpenCodeTests/FamilyDiscoveryBenchmarkTests.swift", "553a407")

package ai.opencode.mobilecode

import java.net.URI
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/// Opt-in, real HTTP measurement. No URLProtocol, client replacement, or
/// injected reducer state. Supply environment through the xctestrun file.
class FamilyDiscoveryBenchmarkTests {
    @Test(timeout = 120_000)
    fun testFamilyDiscoveryOverHTTP() {
        val address = System.getenv("FAMILY_BENCHMARK_URL") ?: ""
        assumeTrue("Set FAMILY_BENCHMARK_URL to the loopback family fixture", address.isNotEmpty())
        val components = URI(address)
        // The fixture is synthetic and unauthenticated, never a production API.
        assertEquals("http", components.scheme)
        assertEquals("127.0.0.1", components.host)
        if (components.scheme != "http" || components.host != "127.0.0.1") return
        val expectedChildren = (System.getenv("FAMILY_BENCHMARK_CHILDREN") ?: "30").toInt()
        assertTrue("Use 0...98 children to avoid the existing traversal cap", expectedChildren in 0..98)
        if (expectedChildren !in 0..98) return
        val server = OpenCodeServer(url = address, username = "", password = "")

        val rootData = http(address, "api/session/root")
        val root = json.decodeFromString<RootResponse>(rootData.decodeToString()).data
        val rootSession = OpenCodeSession(
            id = root.id,
            title = root.title,
            projectID = root.projectID,
            directory = root.location.directory,
            updatedAt = root.time.updated.toLong(),
        )
        val expectedIDs = listOf("root") + (0 until expectedChildren).map { "child-%02d".format(it + 1) }
        val durations = mutableListOf<Double>()
        for (trial in 1..5) {
            val reset = json.decodeFromString<FixtureMetrics>(http(address, "fixture/reset", "POST").decodeToString())
            assertEquals(expectedChildren, reset.config.children)
            val start = TimeSource.Monotonic.markNow()
            val family = kotlinx.coroutines.runBlocking { OpenCodeClient.liveValue.sessionFamily(server, rootSession) }
            val seconds = start.elapsedNow().inWholeNanoseconds / 1e9
            durations += seconds
            assertEquals(expectedIDs, family.sessions.map { it.id })
            assertTrue(family.activeSessionIDs.isEmpty())
            val metrics = json.decodeFromString<FixtureMetrics>(http(address, "fixture/metrics").decodeToString())
            assertEquals(expectedChildren + 1, metrics.requestedParentIDs.size)
            assertEquals(expectedIDs.toSet(), metrics.requestedParentIDs.toSet())
            assertEquals(1, metrics.activeCalls)
            assertEquals(expectedChildren + 2, metrics.requestCount)
            emit(
                mapOf(
                    "kind" to "trial",
                    "trial" to trial,
                    "seconds" to seconds,
                    "members" to family.sessions.size,
                    "children" to expectedChildren,
                    "injectedDelayMs" to metrics.config.delayMs,
                    "requestCount" to metrics.requestCount,
                    "activeCalls" to metrics.activeCalls,
                    "requestedParentIDs" to metrics.requestedParentIDs,
                    "maxConcurrency" to metrics.maxConcurrency,
                    "maxChildConcurrency" to metrics.maxChildConcurrency,
                ),
            )
        }
        emit(
            mapOf(
                "kind" to "summary",
                "trials" to durations.size,
                "children" to expectedChildren,
                "medianSeconds" to durations.sorted()[durations.size / 2],
                "trialSeconds" to durations,
            ),
        )
    }

    private fun http(baseURL: String, path: String, method: String = "GET"): ByteArray {
        val request = Request.Builder()
            .url(baseURL.trimEnd('/') + "/" + path)
            .method(method, if (method == "POST") ByteArray(0).toRequestBody() else null)
            .build()
        client.newCall(request).execute().use { response ->
            assertEquals("Fixture endpoint failed: $method $path", 200, response.code)
            return response.body.bytes()
        }
    }

    private fun emit(value: Map<String, Any?>) {
        println("FAMILY_BENCHMARK ${sortedJson(value)}")
    }

    @Serializable
    private data class RootResponse(val data: Root) {
        @Serializable
        data class Root(
            val id: String,
            val title: String,
            val projectID: String,
            val location: Location,
            val time: Time,
        ) {
            @Serializable
            data class Location(val directory: String)

            @Serializable
            data class Time(val updated: Double)
        }
    }

    @Serializable
    private data class FixtureMetrics(
        val config: Config,
        val requestedParentIDs: List<String> = emptyList(),
        val activeCalls: Int = 0,
        val requestCount: Int = 0,
        val maxConcurrency: Int = 0,
        val maxChildConcurrency: Int = 0,
    ) {
        @Serializable
        data class Config(val children: Int, val delayMs: Double = 0.0)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val client: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        fun sortedJson(value: Any?): String = when (value) {
            null -> "null"
            is String -> JSONObject.quote(value)
            is Boolean, is Int, is Long -> value.toString()
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { sortedJson(it) }
            is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                "${JSONObject.quote(key.toString())}:${sortedJson(item)}"
            }
            else -> JSONObject.quote(value.toString())
        }
    }
}
