@file:PortedFrom("OpenCode/AgentFiles/AgentFileLoader.swift", "553a407")

package ai.opencode.mobilecode

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

fun OpenCodeServer.fileReadRequest(target: AgentFileReference.Target): URLRequest? =
    fileReadRequest(location = target.location, relativePath = target.relativePath)

sealed class AgentFileError {
    data object unreadable : AgentFileError()
    data object notFound : AgentFileError()
    data object unauthorized : AgentFileError()
    data class server(val status: Int) : AgentFileError()
    data class network(val message: String) : AgentFileError()

    val title: String
        get() = when (this) {
            notFound -> "File Not Found"
            else -> "Couldn’t Open File"
        }

    val detail: String
        get() = when (this) {
            unreadable -> "This path can’t be read from the server."
            notFound -> "It may have been moved or deleted."
            unauthorized -> "The server didn’t accept this device’s password."
            is server -> "The server couldn’t read it (HTTP $status). It may be a folder."
            is network -> message
        }

    companion object {
        fun from(status: Int): AgentFileError? = when (status) {
            in 200..299, 304 -> null
            404 -> notFound
            401, 403 -> unauthorized
            else -> server(status)
        }
    }
}

class HTTPURLResponse(
    val statusCode: Int,
    val headerFields: Map<String, String> = emptyMap(),
    val mimeType: String? = headerFields.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.substringBefore(";")?.trim(),
    val expectedContentLength: Long = headerFields.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toLongOrNull() ?: -1,
) {
    fun value(forHTTPHeaderField: String): String? =
        headerFields.entries.firstOrNull { it.key.equals(forHTTPHeaderField, true) }?.value
}

data class AgentFileProbe(
    var size: Long?,
    var mime: String?,
    var supportsRanges: Boolean,
    var entityTag: String? = null,
    var lastModified: String? = null,
) {
    constructor(response: HTTPURLResponse) : this(
        size = size(response),
        mime = response.mimeType,
        supportsRanges = response.statusCode == 206,
        entityTag = response.value("ETag"),
        lastModified = response.value("Last-Modified"),
    )

    companion object {
        fun totalLength(contentRange: String): Long? {
            val slash = contentRange.lastIndexOf('/')
            if (slash < 0) return null
            return contentRange.substring(slash + 1).trim().toLongOrNull()
        }

        private fun size(response: HTTPURLResponse): Long? {
            val contentRange = response.value("Content-Range")
            if (response.statusCode == 206) return contentRange?.let { totalLength(it) }
            if (response.statusCode == 416) return contentRange?.let { totalLength(it) } ?: 0
            return response.expectedContentLength.takeIf { it >= 0 }
        }
    }
}

object AgentFileLoader {
    const val confirmationBytes: Long = 100L * 1024 * 1024
    const val maxHighlightedBytes = 2 * 1024 * 1024

    var cacheDirectory: File = File(System.getProperty("java.io.tmpdir"), "AgentFiles")
    var client: OkHttpClient = OkHttpClient()

    suspend fun probe(request: URLRequest): AgentFileProbe = withContext(Dispatchers.IO) {
        try {
            val call = client.newCall(okHttp(request, mapOf("Range" to "bytes=0-1", "Cache-Control" to "no-cache")))
            call.execute().use { response ->
                call.cancel()
                if (response.code != 416) {
                    AgentFileError.from(response.code)?.let { throw AgentFileException(it) }
                }
                AgentFileProbe(httpResponse(response))
            }
        } catch (error: AgentFileException) {
            throw error
        } catch (error: Exception) {
            throw AgentFileException(AgentFileError.network(error.message ?: "The server sent an unexpected response."))
        }
    }

    @Serializable
    data class Validators(
        var entityTag: String? = null,
        var lastModified: String? = null,
    )

    fun cacheURL(server: OpenCodeServer, absolutePath: String, name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("${server.persistenceID}|$absolutePath".toByteArray())
        val key = digest.take(12).joinToString("") { "%02x".format(it) }
        return File(File(cacheDirectory, key), AttachmentFileType.sanitizedName(name))
    }

    suspend fun download(
        request: URLRequest,
        to: File,
        progress: (Long, Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val sidecar = File(to.path + ".validators")
        val headers = mutableMapOf<String, String>("Cache-Control" to "no-cache")
        if (to.exists()) {
            val validators = runCatching { json.decodeFromString(Validators.serializer(), sidecar.readText()) }.getOrNull()
            validators?.entityTag?.let { headers["If-None-Match"] = it }
            validators?.lastModified?.let { headers["If-Modified-Since"] = it }
        }
        val response = try {
            client.newCall(okHttp(request, headers)).execute()
        } catch (error: Exception) {
            throw AgentFileException(AgentFileError.network(error.message ?: "The server sent an unexpected response."))
        }
        response.use { http ->
            if (http.code == 304 && to.exists()) return@withContext to
            AgentFileError.from(http.code)?.let { throw AgentFileException(it) }
            val body = http.body
            val expected = http.header("Content-Length")?.toLongOrNull()
            val temporary = File.createTempFile("agent-file-", null)
            val reporter = DownloadProgressDelegate(progress)
            try {
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            reporter.wrote(received, expected ?: -1)
                        }
                    }
                }
            } catch (error: Exception) {
                temporary.delete()
                throw AgentFileException(AgentFileError.network(error.message ?: "The server sent an unexpected response."))
            }
            to.parentFile?.mkdirs()
            if (to.exists()) to.delete()
            if (!temporary.renameTo(to)) {
                temporary.copyTo(to, overwrite = true)
                temporary.delete()
            }
            val probe = AgentFileProbe(httpResponse(http))
            val validators = Validators(probe.entityTag, probe.lastModified)
            if (validators.entityTag != null || validators.lastModified != null) {
                runCatching { sidecar.writeText(json.encodeToString(Validators.serializer(), validators)) }
            } else {
                sidecar.delete()
            }
            to
        }
    }

    fun readText(at: File): String? {
        if (!at.exists() || at.length() > maxHighlightedBytes) return null
        val data = runCatching { at.readBytes() }.getOrNull() ?: return null
        if (data.take(8192).any { it == 0.toByte() }) return null
        return runCatching { data.toString(Charsets.UTF_8) }.getOrElse { data.toString(Charsets.ISO_8859_1) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun okHttp(request: URLRequest, extra: Map<String, String>): Request {
        val builder = Request.Builder().url(request.url.absoluteString)
        request.value("Authorization")?.let { builder.header("Authorization", it) }
        extra.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun httpResponse(response: okhttp3.Response): HTTPURLResponse {
        val headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") }
        return HTTPURLResponse(statusCode = response.code, headerFields = headers)
    }
}

class AgentFileException(val error: AgentFileError) : Exception(error.detail)

class DownloadProgressDelegate(private val report: (Long, Long?) -> Unit) {
    private var lastReported = -1L

    fun wrote(totalBytesWritten: Long, totalBytesExpectedToWrite: Long) {
        val expected = totalBytesExpectedToWrite.takeIf { it > 0 }
        val step = maxOf((expected ?: 0) / 200, 128L * 1024)
        if (totalBytesWritten == expected || totalBytesWritten - lastReported >= step) {
            lastReported = totalBytesWritten
            report(totalBytesWritten, expected)
        }
    }

    companion object {
        suspend fun download(request: URLRequest, progress: (Long, Long?) -> Unit): File =
            AgentFileLoader.download(request, File.createTempFile("agent-download-", null), progress)
    }
}

class StreamingFileLoader(
    val request: URLRequest,
    val mime: String?,
    val fileName: String,
) {
    fun invalidate() = Unit

    fun headers(): Map<String, String> {
        val authorization = request.value("Authorization") ?: return emptyMap()
        return mapOf("Authorization" to authorization)
    }

    companion object {
        const val scheme = "opencode-stream"

        fun rangeHeader(offset: Long, length: Int, toEnd: Boolean): String {
            if (toEnd || length <= 0) return "bytes=$offset-"
            return "bytes=$offset-${offset + length - 1}"
        }
    }
}
