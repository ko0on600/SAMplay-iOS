package com.nuvio.app.samplay

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.Foundation.HTTPMethod
import platform.Foundation.HTTPBody
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/** The status and body of a finished request (status 0: no answer). */
data class HttpReply(val code: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val text: String get() = body.decodeToString()
    val isSuccessful: Boolean get() = code in 200..299
}

private val blockingSession: NSURLSession by lazy {
    val config = NSURLSessionConfiguration.defaultSessionConfiguration
    config.timeoutIntervalForRequest = 180.0
    config.timeoutIntervalForResource = 600.0
    NSURLSession.sessionWithConfiguration(config)
}

/**
 * A blocking HTTP call (for code written against HttpURLConnection/OkHttp's execute()). Call it off
 * the main thread (Dispatchers.IO). Network failures throw [IOException].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun blockingHttp(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    timeoutSeconds: Double = 180.0,
): HttpReply {
    val nsUrl = NSURL.URLWithString(url) ?: throw IOException("Bad URL")
    val request = NSMutableURLRequest.requestWithURL(nsUrl)
    request.HTTPMethod = method
    request.setTimeoutInterval(timeoutSeconds)
    headers.forEach { (k, v) -> request.setValue(v, forHTTPHeaderField = k) }
    if (body != null) request.HTTPBody = body.toNSData()
    val semaphore = dispatch_semaphore_create(0)
    var reply: HttpReply? = null
    var failure: String? = null
    val task = blockingSession.dataTaskWithRequest(request) { data, response, error ->
        if (error != null) {
            failure = error.localizedDescription
        } else {
            val http = response as? NSHTTPURLResponse
            val hdrs = http?.allHeaderFields?.entries?.associate { (k, v) -> k.toString().lowercase() to v.toString() }.orEmpty()
            reply = HttpReply(http?.statusCode?.toInt() ?: 0, data?.toByteArray() ?: ByteArray(0), hdrs)
        }
        dispatch_semaphore_signal(semaphore)
    }
    task.resume()
    dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
    return reply ?: throw IOException(failure ?: "network error")
}

/** application/x-www-form-urlencoded body. */
fun formBody(fields: List<Pair<String, String>>): ByteArray =
    fields.joinToString("&") { (k, v) -> urlEncode(k) + "=" + urlEncode(v) }.encodeToByteArray()

fun urlEncode(value: String): String {
    val out = StringBuilder()
    value.encodeToByteArray().forEach { b ->
        val c = (b.toInt() and 0xFF)
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch == '-' || ch == '_' || ch == '.' || ch == '~') out.append(ch)
        else out.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
    return out.toString()
}
