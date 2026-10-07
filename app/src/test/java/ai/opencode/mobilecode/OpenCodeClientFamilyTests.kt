@file:PortedFrom("OpenCodeTests/OpenCodeClientFamilyTests.swift", "553a407")

package ai.opencode.mobilecode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

class OpenCodeClientFamilyTests {
    @Test
    fun overlapsActiveStatusAndBoundsThirtyChildRequests() = runBlocking {
        val children = (1..30).map { "child-$it" }
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to children),
            active = listOf("child-2", "unrelated"),
            held = setOf("root", "active") + children,
        )
        val scope = this
        fixture.use {
            val load = scope.async(Dispatchers.IO) { OpenCodeClient.liveValue.sessionFamily(server, root) }
            try {
                waitForStarts(listOf("root", "active"))
                release(listOf("root"))
                for (start in 0 until 30 step 6) {
                    val batch = children.subList(start, start + 6)
                    waitForStarts(batch)
                    release(batch)
                }
                release(listOf("active"))
                val family = load.await()
                assertEquals(listOf("root") + children, family.sessions.map { it.id })
                assertEquals(setOf("child-2", "unrelated"), family.activeSessionIDs)
                assertEquals(32, requests.size)
                assertEquals(6, peakChildRequests)
                assertEquals(7, peakRequests)
                assertEquals(0, outstandingRequests)
            } finally {
                load.cancel()
            }
        }
    }

    @Test
    fun discoversAllDepthsInStableBreadthFirstOrderDespiteCyclesAndDuplicateIDs() = runBlocking {
        val fixture = FamilyHTTPFixture(
            children = mapOf(
                "root" to listOf("a", "b", "a"),
                "a" to listOf("root", "shared", "a-leaf"),
                "b" to listOf("shared", "b-leaf"),
                "shared" to listOf("a"),
                "b-leaf" to listOf("deep"),
            ),
            held = setOf("a", "b"),
        )
        val scope = this
        fixture.use {
            val load = scope.async(Dispatchers.IO) { OpenCodeClient.liveValue.sessionFamily(server, root) }
            try {
                waitForStarts(listOf("a", "b"))
                release(listOf("b"))
                release(listOf("a"))
                val family = load.await()
                assertEquals(listOf("root", "a", "b", "shared", "a-leaf", "b-leaf", "deep"), family.sessions.map { it.id })
                assertEquals(root, family.sessions.first())
                val shared = family.sessions.first { it.id == "shared" }
                assertEquals("a/shared", shared.title)
                assertEquals("a", shared.parentID)
                assertEquals(OpenCodeModelRef("model", "provider"), shared.model)
                assertEquals("explore", shared.agent)
                assertEquals(1000L, shared.updatedAt)
                assertEquals(8, requests.size)
                assertEquals(setOf("root", "a", "b", "shared", "a-leaf", "b-leaf", "deep", "active"), requests.map { key(it) }.toSet())
                for (request in requests.filter { key(it) != "active" }) {
                    assertEquals("GET", request.method)
                    assertTrue(request.query.contains(URLQueryItem("order", "desc")))
                    assertTrue(request.query.contains(URLQueryItem("limit", "100")))
                }
            } finally {
                load.cancel()
            }
        }
    }

    @Test
    fun emptyFamilyRetainsRoot() = runBlocking {
        val fixture = FamilyHTTPFixture(children = emptyMap())
        fixture.use {
            val family = OpenCodeClient.liveValue.sessionFamily(server, root)
            assertEquals(listOf(root), family.sessions)
            assertTrue(family.activeSessionIDs.isEmpty())
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun retainsWholeResponseAtTraversalCapWithoutRequestingMoreChildren() = runBlocking {
        val children = (1..100).map { "child-$it" }
        val fixture = FamilyHTTPFixture(children = mapOf("root" to children))
        fixture.use {
            val family = OpenCodeClient.liveValue.sessionFamily(server, root)
            assertEquals(listOf("root") + children, family.sessions.map { it.id })
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun capStopsAtTheSameParentAndCancelsSpeculativeRequests() = runBlocking {
        val children = (1..98).map { "child-$it" }
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to children, "child-1" to listOf("grandchild-1", "grandchild-2")),
            held = children.take(6).toSet(),
            overrides = mapOf("child-2" to FamilyHTTPFixture.Response(503, ByteArray(0))),
        )
        val scope = this
        fixture.use {
            val load = scope.async(Dispatchers.IO) { OpenCodeClient.liveValue.sessionFamily(server, root) }
            try {
                waitForStarts(children.take(6))
                release(listOf("child-2"))
                release(listOf("child-1"))
                val family = load.await()
                assertEquals(listOf("root") + children + listOf("grandchild-1", "grandchild-2"), family.sessions.map { it.id })
                assertEquals(8, requests.size)
                waitForIdle()
                assertEquals(0, outstandingRequests)
            } finally {
                load.cancel()
            }
        }
    }

    @Test
    fun propagatesHTTPFailuresFromEveryRequiredEndpoint() = runBlocking {
        for (endpoint in listOf("root", "a", "active")) {
            val fixture = FamilyHTTPFixture(
                children = mapOf("root" to listOf("a")),
                overrides = mapOf(endpoint to FamilyHTTPFixture.Response(503, ByteArray(0))),
            )
            fixture.use {
                val error = runCatching { OpenCodeClient.liveValue.sessionFamily(server, root) }.exceptionOrNull() as OpenCodeClientError
                assertEquals("The server returned HTTP 503.", error.message)
            }
        }
    }

    @Test
    fun malformedChildResponseFailsInsteadOfReturningAPartialFamily() = runBlocking {
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to listOf("a")),
            overrides = mapOf("a" to FamilyHTTPFixture.Response(body = """{"data":[{"id":"broken"}]}""".toByteArray())),
        )
        fixture.use {
            val error = runCatching { OpenCodeClient.liveValue.sessionFamily(server, root) }.exceptionOrNull() as OpenCodeClientError
            assertEquals("The server response does not match the current OpenCode V2 contract.", error.message)
        }
    }

    @Test
    fun requiredFailureCancelsUnfinishedSiblingAndActiveRequests() = runBlocking {
        val fixture = FamilyHTTPFixture(
            children = mapOf("root" to listOf("a", "b")),
            held = setOf("a", "b", "active"),
            overrides = mapOf("a" to FamilyHTTPFixture.Response(503, ByteArray(0))),
        )
        fixture.use {
            supervisorScope {
                val load = async(Dispatchers.IO) { OpenCodeClient.liveValue.sessionFamily(server, root) }
                try {
                    waitForStarts(listOf("a", "b", "active"))
                    release(listOf("a"))
                    val error = runCatching { load.await() }.exceptionOrNull() as OpenCodeClientError
                    assertEquals("The server returned HTTP 503.", error.message)
                    waitForIdle()
                    assertEquals(0, outstandingRequests)
                } finally {
                    load.cancel()
                }
            }
        }
    }

    @Test
    fun callerCancellationCancelsHeldHTTPRequests() = runBlocking {
        val fixture = FamilyHTTPFixture(children = emptyMap(), held = setOf("root", "active"))
        fixture.use {
            supervisorScope {
                val load = async(Dispatchers.IO) { OpenCodeClient.liveValue.sessionFamily(server, root) }
                waitForStarts(listOf("root", "active"))
                load.cancel()
                val error = runCatching { load.await() }.exceptionOrNull()
                assertTrue(error is OpenCodeClientError || error is CancellationException)
                waitForIdle()
                assertEquals(0, outstandingRequests)
            }
        }
    }
}

class FamilyHTTPFixture(
    children: Map<String, List<String>>,
    active: List<String> = emptyList(),
    held: Set<String> = emptySet(),
    overrides: Map<String, Response> = emptyMap(),
) : Closeable {
    data class Response(val status: Int = 200, val body: ByteArray)
    data class Captured(val method: String, val target: String, val query: List<URLQueryItem>)

    private val listener = ServerSocket(0)
    val server = OpenCodeServer(url = "http://127.0.0.1:${listener.localPort}", username = "", password = "")
    val root = OpenCodeSession("root", "Root", "project", "/fixture", 0)
    private val responses: Map<String, Response>
    private val heldKeys = held.toMutableSet()
    private val started = mutableListOf<Captured>()
    private val running = mutableSetOf<Socket>()
    private val sockets = mutableMapOf<Socket, String>()
    private var peakChildren = 0
    private var peakAll = 0
    private val lock = Object()

    init {
        val bodies = mutableMapOf<String, Response>()
        val ids = setOf("root") + children.keys + children.values.flatten()
        for (id in ids) {
            val sessions = (children[id] ?: emptyList()).joinToString(",") { child ->
                """{"id":"$child","title":"$id/$child","projectID":"project","parentID":"$id","location":{"directory":"/fixture"},"time":{"updated":1000},"model":{"id":"model","providerID":"provider"},"agent":"explore"}"""
            }
            bodies[id] = Response(body = """{"data":[$sessions]}""".toByteArray())
        }
        val activeBody = active.joinToString(",") { """"$it":{"type":"running"}""" }
        bodies["active"] = Response(body = """{"data":{$activeBody}}""".toByteArray())
        bodies.putAll(overrides)
        responses = bodies
        Thread({ acceptLoop() }, "family-http").apply { isDaemon = true }.start()
    }

    val requests: List<Captured> get() = synchronized(lock) { started.toList() }
    val peakChildRequests: Int get() = synchronized(lock) { peakChildren }
    val peakRequests: Int get() = synchronized(lock) { peakAll }
    val outstandingRequests: Int get() = synchronized(lock) { running.size }

    fun key(request: Captured): String {
        val path = request.target.substringBefore("?")
        if (path == "/api/session/active") return "active"
        if (path != "/api/session") return path
        return request.query.firstOrNull { it.name == "parentID" }?.value ?: "missing-parent"
    }

    fun release(keys: List<String>) {
        synchronized(lock) {
            heldKeys.removeAll(keys.toSet())
            lock.notifyAll()
        }
    }

    suspend fun waitForStarts(keys: List<String>) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            if (keys.all { key -> synchronized(lock) { started.any { key(it) == key } } }) return
            delay(10)
        }
        error("timed out waiting for $keys, started=${synchronized(lock) { started.map { key(it) } }}")
    }

    suspend fun waitForIdle() {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            if (outstandingRequests == 0) return
            delay(10)
        }
        error("timed out waiting for idle, outstanding=$outstandingRequests")
    }

    override fun close() {
        listener.close()
        synchronized(lock) { running.toList() }.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop() {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: Exception) { return }
            Thread({ handle(socket) }, "family-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            val header = readHeaders(socket)
            val requestLine = header.lineSequence().first()
            val parts = requestLine.split(" ")
            val target = parts.getOrElse(1) { "/" }
            val query = target.substringAfter("?", "").split("&").filter { it.isNotEmpty() }.map { part ->
                val pieces = part.split("=", limit = 2)
                URLQueryItem(java.net.URLDecoder.decode(pieces[0], Charsets.UTF_8), pieces.getOrNull(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) })
            }
            val captured = Captured(parts[0], target, query)
            val requestKey = key(captured)
            synchronized(lock) {
                started += captured
                running += socket
                sockets[socket] = requestKey
                peakAll = maxOf(peakAll, running.size)
                peakChildren = maxOf(peakChildren, sockets.values.count { it != "active" })
            }
            while (synchronized(lock) { requestKey in heldKeys }) {
                socket.soTimeout = 50
                try {
                    if (socket.getInputStream().read() < 0) return
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    return
                }
            }
            val response = responses[requestKey] ?: Response(404, ByteArray(0))
            val body = response.body
            val statusText = if (response.status == 200) "OK" else "Error"
            val bytes = "HTTP/1.1 ${response.status} $statusText\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII) + body
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        } catch (_: Exception) {
        } finally {
            synchronized(lock) {
                running.remove(socket)
                sockets.remove(socket)
            }
            runCatching { socket.close() }
        }
    }

    private fun readHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) break
            bytes += value.toByte()
            val size = bytes.size
            if (size >= 4 && bytes[size - 4] == '\r'.code.toByte() && bytes[size - 3] == '\n'.code.toByte() && bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()) break
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }
}

private suspend fun <T> FamilyHTTPFixture.use(block: suspend FamilyHTTPFixture.() -> T): T = try {
    block()
} finally {
    close()
}
