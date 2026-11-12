package com.anpaiss.ridestats

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpResult(val code: Int, val bytes: ByteArray) {
    val body: String get() = bytes.decodeToString()
}

/**
 * Blocking HTTP with the two paths the Karoo has, as in PoiAhead: its own WiFi
 * first, then the phone's link over BT through the Karoo's HTTP channel. A
 * response with any status comes back to the caller; only a failure to reach
 * the server is an IOException.
 */
class Net(private val karoo: KarooSystemService?) {

    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray? = null): HttpResult =
        try {
            direct(method, url, headers, body)
        } catch (e: IOException) {
            val k = karoo?.takeIf { it.connected } ?: throw e
            sdk(k, method, url, headers, body)
        }

    private fun direct(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) doOutput = true
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return HttpResult(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    private fun sdk(k: KarooSystemService, method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
        val done = CountDownLatch(1)
        var complete: HttpResponseState.Complete? = null
        var failure: String? = null
        val id = k.addConsumer(
            OnHttpResponse.MakeHttpRequest(method, url, headers + ("User-Agent" to USER_AGENT), body, waitForConnection = false),
            { msg -> failure = msg; done.countDown() },
            { done.countDown() },
        ) { event: OnHttpResponse ->
            (event.state as? HttpResponseState.Complete)?.let { complete = it; done.countDown() }
        }
        try {
            if (!done.await(TIMEOUT_MS.toLong() + 15_000, TimeUnit.MILLISECONDS)) throw IOException("sdk: timeout")
        } finally {
            k.removeConsumer(id)
        }
        val r = complete ?: throw IOException("sdk: ${failure ?: "no response"}")
        r.error?.let { throw IOException("sdk: $it") }
        return HttpResult(r.statusCode, r.body ?: ByteArray(0))
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
        val USER_AGENT = "RideStats/${BuildConfig.VERSION_NAME} (Hammerhead Karoo extension)"
    }
}
