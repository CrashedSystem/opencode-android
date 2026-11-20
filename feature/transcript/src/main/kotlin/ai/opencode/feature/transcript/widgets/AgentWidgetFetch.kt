package ai.opencode.feature.transcript.widgets

import com.anomalyco.psychoscript.FetchCompletion
import com.anomalyco.psychoscript.FetchRequest
import com.anomalyco.psychoscript.FetchResponse
import com.anomalyco.psychoscript.FetchTransport
import com.anomalyco.psychoscript.HttpHeader
import com.anomalyco.psychoscript.isPublicAddress
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.http.HttpMethod
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Which hosts a live widget may fetch from: the one place a grant is decided. Every declared
 * capability is granted for now (specs/v2/widgets-live.md); a permission prompt goes here.
 */
object AgentWidgetCapabilities {
    fun grantedHosts(declared: List<String>?): List<String>? = declared?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
}

/**
 * A live widget's `fetch` transfer through OkHttp, one per run. The runner checks the URL, hosts,
 * methods, headers, sizes, rates and redirects; this carries one exchange: no redirects, no
 * cookies, and only public addresses from DNS, so a name can't rebind to the phone's network.
 * [failed] hears of each transfer that failed (for the widget's error report).
 */
internal class AgentWidgetFetchTransport(private val failed: (String) -> Unit) : FetchTransport {
    override fun start(request: FetchRequest, completion: FetchCompletion) {
        val call = try {
            val type = request.headers.firstOrNull { it.name.equals("content-type", ignoreCase = true) }?.value?.toMediaTypeOrNull()
            val body = request.body?.toRequestBody(type)
                ?: if (HttpMethod.requiresRequestBody(request.method)) ByteArray(0).toRequestBody(type) else null
            val builder = Request.Builder().url(request.url).method(request.method, body)
            request.headers.forEach { builder.addHeader(it.name, it.value) }
            client.newBuilder().callTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS).build().newCall(builder.build())
        } catch (error: IllegalArgumentException) {
            completion.fail(error.message ?: "invalid request")
            return
        }
        calls[request.id] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(request.id)
                val message = e.message ?: "network error"
                if (!call.isCanceled()) failed("${request.method} ${request.url.substringBefore('?')}: $message")
                completion.fail(message)
            }

            override fun onResponse(call: Call, response: Response) {
                calls.remove(request.id)
                try {
                    response.use {
                        // One byte over the cap, so the runner reports TooLarge.
                        val max = request.maxResponseBytes.toLong() + 1
                        val source = it.body.source().apply { request(max) }
                        val bytes = source.buffer.readByteArray(minOf(source.buffer.size, max))
                        completion.succeed(FetchResponse(it.code.toUShort(), it.headers.map { (n, v) -> HttpHeader(n, v) }, bytes, null))
                    }
                } catch (e: IOException) {
                    completion.fail(e.message ?: "network error")
                }
            }
        })
    }

    override fun cancel(id: ULong) {
        calls.remove(id)?.cancel()
    }

    private companion object {
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .dns { host ->
                    Dns.SYSTEM.lookup(host).filter { isPublicAddress(it.hostAddress ?: "") }
                        .ifEmpty { throw UnknownHostException("$host has no public address") }
                }
                .build()
        }
        val calls = ConcurrentHashMap<ULong, Call>()
    }
}
