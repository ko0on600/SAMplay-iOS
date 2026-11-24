package com.nuvio.app.features.player

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class PlaybackRedirectsTest {
    private val linkServer = MockWebServer()
    private val fileServer = MockWebServer()
    private var clock = 0L
    private val client = OkHttpClient.Builder().addInterceptor(PlaybackRedirects(now = { clock })).build()

    @BeforeTest
    fun start() {
        linkServer.start()
        fileServer.start()
    }

    @AfterTest
    fun stop() {
        linkServer.shutdown()
        fileServer.shutdown()
    }

    private fun redirect() = MockResponse().setResponseCode(302).addHeader("Location", fileServer.url("/file").toString())

    private fun ok() = MockResponse().setResponseCode(206).setBody("x")

    private fun request(method: String = "GET", auth: Boolean = false) = Request.Builder()
        .url(linkServer.url("/link"))
        .apply { if (auth) header("Authorization", "secret") }
        .method(method, null)
        .build()

    private fun play(request: Request = request()) = client.newCall(request).execute().use { it.code }

    @Test
    fun `the link is asked once`() {
        linkServer.enqueue(redirect())
        repeat(3) { fileServer.enqueue(ok()) }
        repeat(3) { assertEquals(206, play()) }
        assertEquals(1, linkServer.requestCount)
        assertEquals(3, fileServer.requestCount)
    }

    @Test
    fun `a warm up saves the first read the trip`() {
        linkServer.enqueue(redirect())
        repeat(2) { fileServer.enqueue(MockResponse().setResponseCode(200)) }
        assertEquals(200, play(request("HEAD")))
        assertEquals(200, play())
        assertEquals(1, linkServer.requestCount)
    }

    @Test
    fun `a target that stops answering sends the player back to the link`() {
        linkServer.enqueue(redirect())
        fileServer.enqueue(ok())
        play()
        fileServer.enqueue(MockResponse().setResponseCode(403))
        linkServer.enqueue(ok())
        assertEquals(206, play())
        assertEquals(2, linkServer.requestCount)
    }

    @Test
    fun `an old target is asked again`() {
        linkServer.enqueue(redirect())
        fileServer.enqueue(ok())
        play()
        clock += 10 * 60_000L + 1
        linkServer.enqueue(redirect())
        fileServer.enqueue(ok())
        play()
        assertEquals(2, linkServer.requestCount)
    }

    @Test
    fun `authorization does not follow to another host`() {
        linkServer.enqueue(redirect())
        repeat(2) { fileServer.enqueue(ok()) }
        play(request(auth = true))
        play(request(auth = true))
        fileServer.takeRequest()
        assertNull(fileServer.takeRequest().headers["Authorization"])
    }
}
