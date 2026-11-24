package com.nuvio.app.features.player

import android.os.SystemClock
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Nuvio RS: remembers where a stream's link led. Starting a video opens several connections and
 * every seek one more, and each would otherwise ask the link's server again, which for an addon's
 * resolve link can take a second. A target that stops answering is forgotten and the link is asked again.
 */
internal class PlaybackRedirects(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Interceptor {
    private class Target(val url: HttpUrl, val rememberedAt: Long)

    private val targets = object : LinkedHashMap<String, Target>(MAX_LINKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Target>) = size > MAX_LINKS
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val link = request.url.toString()

        if (request.method == "GET") remembered(link)?.let { target ->
            val response = try {
                chain.proceed(request.towards(target))
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                null
            }
            if (response?.isSuccessful == true) return response
            response?.close()
            forget(link)
        }

        val response = chain.proceed(request)
        // A warm-up's HEAD saves the GET that follows it the same trip.
        val readsTheFile = request.method == "GET" || request.method == "HEAD"
        if (readsTheFile && response.isSuccessful && response.priorResponse != null) remember(link, response.request.url)
        return response
    }

    /** Authorization stays with the link's own host, as it does when OkHttp follows the redirect itself. */
    private fun Request.towards(target: HttpUrl): Request {
        val sameOrigin = target.host == url.host && target.port == url.port && target.scheme == url.scheme
        return newBuilder().url(target).apply { if (!sameOrigin) removeHeader("Authorization") }.build()
    }

    @Synchronized
    private fun remembered(link: String): HttpUrl? {
        val target = targets[link] ?: return null
        if (now() - target.rememberedAt <= TTL_MS) return target.url
        targets.remove(link)
        return null
    }

    @Synchronized
    private fun remember(link: String, target: HttpUrl) {
        targets[link] = Target(target, now())
    }

    @Synchronized
    private fun forget(link: String) {
        targets.remove(link)
    }

    private companion object {
        const val TTL_MS = 10 * 60_000L
        const val MAX_LINKS = 16
    }
}
