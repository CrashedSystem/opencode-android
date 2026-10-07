@file:PortedFrom("OpenCodeTests/WireFixture.swift", "553a407")

package ai.opencode.mobilecode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.Closeable

class WireFixture(private val reply: (Request) -> Reply) : Closeable {
    sealed class Reply {
        data class json(val status: Int, val body: String) : Reply()
        data object redirect : Reply()

        companion object {
            fun status(status: Int) = json(status, "")
        }
    }

    data class Request(
        val method: String?,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val route: String get() = "${method ?: "GET"} $path"

        fun queryValue(name: String): String? = queryItems().firstOrNull { it.name == name }?.value

        fun queryItems(): List<URLQueryItem> {
            val raw = query ?: return emptyList()
            return raw.split("&").filter { it.isNotEmpty() }.map { part ->
                val pieces = part.split("=", limit = 2)
                URLQueryItem(percentDecode(pieces[0]), pieces.getOrNull(1)?.let { percentDecode(it) })
            }
        }

        fun json(): JsonObject = protocolJson.parseToJsonElement(body.decodeToString()).jsonObject
    }

    private val web = MockWebServer()
    private val captured = mutableListOf<Request>()
    val server: OpenCodeServer

    init {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val recorded = Request(
                    method = request.method,
                    path = request.url.encodedPath,
                    query = request.url.encodedQuery,
                    headers = request.headers.toMap(),
                    body = request.body?.toByteArray() ?: ByteArray(0),
                )
                synchronized(captured) { captured += recorded }
                return when (val response = reply(recorded)) {
                    is Reply.json -> MockResponse.Builder()
                        .code(response.status)
                        .setHeader("Content-Type", "application/json")
                        .body(response.body)
                        .build()
                    Reply.redirect -> {
                        val target = request.url.newBuilder().removePathSegment(request.url.pathSize - 1).addPathSegment("redirected").build()
                        MockResponse.Builder().code(307).setHeader("Location", target.toString()).body("").build()
                    }
                }
            }
        }
        web.start()
        server = OpenCodeServer(url = web.url("/").toString().trimEnd('/'), username = "user", password = "secret")
    }

    val requests: List<Request> get() = synchronized(captured) { captured.toList() }

    override fun close() {
        web.close()
    }

}

private fun percentDecode(value: String): String = java.net.URLDecoder.decode(value, Charsets.UTF_8)
