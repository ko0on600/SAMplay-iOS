package com.nuvio.app.features.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import co.touchlab.kermit.Logger
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.network.NetworkErrorInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    internal const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val WARM_CONNECTIONS = 2
    private const val WARM_TIMEOUT_S = 6L
    private const val WARM_BYTES = 128L * 1024L
    private const val WARM_BYTES_TIMEOUT_S = 12L
    private const val WARM_BYTES_RETRY_MS = 2_000L
    private const val WARM_BYTES_BUDGET_MS = 60_000L
    private val warmLog = Logger.withTag("ServerPlayback")

    private val DEFAULT_STREAM_HEADERS = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
    )

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            // Broken system HTTP proxies rewrite CDN requests → HTTP 403 / connection failures.
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(NetworkErrorInterceptor())
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            .addInterceptor(PlaybackRedirects())
            .build()
    }

    private val loopbackPlaybackHttpClient: OkHttpClient by lazy {
        playbackHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestChain = if (isLoopbackHost(request.url.host)) {
                    chain.withReadTimeout(65, TimeUnit.SECONDS)
                } else {
                    chain
                }
                requestChain.proceed(request)
            }
            .build()
    }

    /**
     * Leaves open connections to where [url] leads in the pool the player draws from. Starting an
     * MP4 or MKV drops its first connection to read the index at the file's end, then again to come
     * back, and each of those would otherwise pay for a new connection.
     */
    suspend fun warmConnections(url: String, headers: Map<String, String>) = coroutineScope {
        val client = playbackHttpClient.newBuilder().callTimeout(WARM_TIMEOUT_S, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).head().apply {
            withDefaultUserAgent(headers).forEach { (key, value) -> header(key, value) }
        }.build()
        repeat(WARM_CONNECTIONS) {
            launch(Dispatchers.IO) { runCatching { client.newCall(request).execute().close() } }
        }
    }

    /**
     * SAMplay 2.0.3: asks for the first bytes of [url] until the server sends them, for at most
     * [WARM_BYTES_BUDGET_MS]. PikPak's WebDAV fetches a file it has not served lately only once
     * its bytes are asked for (a HEAD does not start it), and until then every read ends early:
     * the player failed twice and opened such a file 20 to 45 s after the tap (on the phone, the
     * same file opened again started in 6 s). Asked while the user looks at the links, that wait
     * runs before the tap.
     */
    suspend fun warmFirstBytes(url: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val client = playbackHttpClient.newBuilder().callTimeout(WARM_BYTES_TIMEOUT_S, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).header("Range", "bytes=0-${WARM_BYTES - 1}").apply {
            withDefaultUserAgent(headers).forEach { (key, value) -> header(key, value) }
        }.build()
        val host = runCatching { URL(url).host }.getOrDefault("?")
        val startedAt = SystemClock.elapsedRealtime()
        var tries = 0
        while (SystemClock.elapsedRealtime() - startedAt < WARM_BYTES_BUDGET_MS) {
            ensureActive()
            tries++
            val received = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    // A server that ignores the range sends the whole file: only its start is read.
                    val wanted = body.contentLength().let { if (it in 1 until WARM_BYTES) it else WARM_BYTES }
                    body.source().require(wanted)
                    true
                }
            }.getOrDefault(false)
            if (received) {
                warmLog.i { "warm: $host sent the first bytes after $tries tries in ${SystemClock.elapsedRealtime() - startedAt} ms" }
                return@withContext
            }
            delay(WARM_BYTES_RETRY_MS)
        }
        warmLog.i { "warm: $host did not send the first bytes in $tries tries" }
    }

    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        playUrl: String? = null,
    ): DataSource.Factory {
        val requestHeaders = sanitizeHeaders(defaultHeaders)
        val baseClient = if (useLongReadTimeout) loopbackPlaybackHttpClient else playbackHttpClient
        val authorization = requestHeaders.headerValue("Authorization")
        val playHost = playUrl?.let { runCatching { URL(it).host }.getOrNull() }?.takeIf { it.isNotBlank() }
        // Non-Auth headers (Referer / Cookie / Origin / UA) must be re-pinned on every network
        // hop — HLS segments and CDN redirects hotlink-check each request.
        // Authorization must NEVER sit in setDefaultRequestProperties: Media3 re-applies those
        // on each open(), and a PikPak CDN hop that still sees Basic auth resets the socket
        // → ERROR_CODE_IO_NETWORK_CONNECTION_FAILED. Auth is injected only for the play URL host.
        val nonAuthHeaders = requestHeaders.filterKeys { !it.equals("Authorization", ignoreCase = true) }
        val client = if (nonAuthHeaders.isNotEmpty() || authorization != null) {
            baseClient.newBuilder()
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val builder = request.newBuilder()
                    for ((key, value) in nonAuthHeaders) {
                        if (!key.equals("User-Agent", ignoreCase = true)) {
                            builder.header(key, value)
                        }
                    }
                    if (authorization != null && playHost != null &&
                        request.url.host.equals(playHost, ignoreCase = true)
                    ) {
                        builder.header("Authorization", authorization)
                    } else if (authorization != null && playHost != null &&
                        !request.url.host.equals(playHost, ignoreCase = true)
                    ) {
                        builder.removeHeader("Authorization")
                    }
                    chain.proceed(builder.build())
                }
                .build()
        } else {
            baseClient
        }

        val requestProps = nonAuthHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(requestProps)
            setUserAgent(requestHeaders.headerValue("User-Agent") ?: DEFAULT_USER_AGENT)
        }
    }

    fun createDataSourceFactory(
        context: Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        playUrl: String? = null,
    ): DataSource.Factory {
        return DefaultDataSource.Factory(
            context,
            createHttpDataSourceFactory(defaultHeaders, useLongReadTimeout, playUrl),
        )
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null,
    ): HttpURLConnection {
        val mergedHeaders = withDefaultUserAgent(headers)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = playbackHostnameVerifier
            }
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", mergedHeaders.headerValue("User-Agent") ?: DEFAULT_USER_AGENT)
            mergedHeaders.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()

    private fun isLoopbackHost(host: String): Boolean = when (host.lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }

    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> {
        val sanitized = sanitizeHeaders(headers)
        if (sanitized.headerValue("User-Agent") != null) return sanitized
        return DEFAULT_STREAM_HEADERS + sanitized
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}
