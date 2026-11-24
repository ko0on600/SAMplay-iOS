package com.nuvio.app.features.downloads

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SAMplay 2.0.4: «Download file» on an HLS link (FaselHD's proxy) saves the pieces as one file. */
class AndroidHlsTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun piece(index: Int): ByteArray = ByteArray(188 * 3) { ((index * 31 + it) % 251).toByte() }

    /** A proxy like FaselHD's: `/stream?url=…` answers the playlist or a piece, slower for early pieces. */
    private inner class ProxyServer(private val pieces: Int, private val failOnce: Set<Int> = emptySet()) : Dispatcher() {
        val inFlight = AtomicInteger(0)
        var mostAtOnce = 0
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        lateinit var base: String

        fun playlist(): String = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:1\n")
            for (i in 0 until pieces) append("#EXTINF:2.0,\n$base/stream?url=hls%2Fsegment_$i.ts&ref=x\n")
            append("#EXT-X-ENDLIST\n")
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!.queryParameter("url").orEmpty()
            requests.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            if (url.endsWith("playlist.m3u8")) {
                return MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setChunkedBody(playlist(), 4096)
            }
            val index = url.substringAfter("segment_").substringBefore(".ts").toInt()
            val now = inFlight.incrementAndGet()
            synchronized(this) { mostAtOnce = maxOf(mostAtOnce, now) }
            try {
                Thread.sleep(if (index % 3 == 0) 120L else 20L)
                if (index in failOnce && requests[url]!!.get() == 1) return MockResponse().setResponseCode(503)
                return MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(piece(index)))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun proxyLink(server: MockWebServer): String =
        server.url("/stream").toString() + "?url=https%3A%2F%2Fcdn%2Fhls%2F160_hd1080b_playlist.m3u8&ref=x"

    @Test
    fun proxyPlaylistIsSavedAsOneFileInOrderWithPiecesInParallel(): Unit = runBlocking {
        MockWebServer().use { server ->
            val proxy = ProxyServer(pieces = 25, failOnce = setOf(7))
            server.dispatcher = proxy
            server.start()
            proxy.base = server.url("/").toString().trimEnd('/')
            val directory = temporary.newFolder()
            var lastProgress = 0L to (null as Long?)

            val output = transferAndroidDownload(hlsItem(proxyLink(server)), directory, null,
                onHeaders = { _, _ -> }, onProgress = { done, total -> lastProgress = done to total })

            val expected = (0 until 25).fold(ByteArray(0)) { all, i -> all + piece(i) }
            assertContentEquals(expected, output.readBytes())
            assertTrue(proxy.mostAtOnce in 3..HLS_PARALLEL_PIECES, "pieces at once: ${proxy.mostAtOnce}")
            assertEquals(2, proxy.requests["hls/segment_7.ts"]!!.get(), "a 503 piece is asked again")
            assertEquals(expected.size.toLong() to expected.size.toLong(), lastProgress)
            assertFalse(File(output.path + ".hls").exists())
        }
    }

    @Test
    fun aStoppedDownloadContinuesFromItsCheckpoint(): Unit = runBlocking {
        MockWebServer().use { server ->
            val proxy = ProxyServer(pieces = 12)
            server.dispatcher = proxy
            server.start()
            proxy.base = server.url("/").toString().trimEnd('/')
            val directory = temporary.newFolder()
            val item = hlsItem(proxyLink(server))
            val partial = File(directory, "${item.fileName}.part")
            // 10 pieces were written and checkpointed; the 11th was half written when it stopped.
            partial.writeBytes((0 until 10).fold(ByteArray(0)) { all, i -> all + piece(i) } + piece(10).copyOf(100))
            File(partial.path + ".hls").writeText("v1 12 10 ${piece(0).size * 10}")

            val output = transferAndroidDownload(item, directory, null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertContentEquals((0 until 12).fold(ByteArray(0)) { all, i -> all + piece(i) }, output.readBytes())
            assertEquals(null, proxy.requests["hls/segment_3.ts"], "pieces already saved are not fetched again")
            assertEquals(1, proxy.requests["hls/segment_10.ts"]!!.get())
        }
    }

    @Test
    fun aes128PiecesAreDecrypted(): Unit = runBlocking {
        val key = ByteArray(16) { (it + 1).toByte() }
        fun encrypt(data: ByteArray, sequence: Long): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(hlsIv(null, sequence)))
            doFinal(data)
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/v/index.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n" +
                            "#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST\n",
                    )
                    "/v/k.bin" -> MockResponse().setBody(Buffer().write(key))
                    "/v/a.ts" -> MockResponse().setBody(Buffer().write(encrypt(piece(0), 5)))
                    "/v/b.ts" -> MockResponse().setBody(Buffer().write(encrypt(piece(1), 6)))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val output = transferAndroidDownload(hlsItem(server.url("/v/index.m3u8").toString()),
                temporary.newFolder(), null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            assertContentEquals(piece(0) + piece(1), output.readBytes())
        }
    }

    @Test
    fun liveOrSeparateSoundStreamsFailWithAReason(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:6,\na.ts\n"))
            val error = assertFailsWith<UnsupportedDownloadException> {
                transferAndroidDownload(hlsItem(server.url("/live.m3u8").toString()), temporary.newFolder(), null,
                    onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            assertEquals("This is a live stream; it has no end to download", error.message)
            assertFalse(shouldRetryAndroidDownload(error, 0))
        }
    }

    @Test
    fun onlyAnotherNetworkRestartsTheTransfer() {
        assertFalse(downloadNetworkChanged("wifi-1", "wifi-1"))
        assertFalse(downloadNetworkChanged("wifi-1", null))
        assertTrue(downloadNetworkChanged("wifi-1", "mobile-2"))
        assertTrue(downloadNetworkChanged(null, "wifi-1"))
    }

    private fun hlsItem(url: String) = DownloadItem(
        id = "hls-1",
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:13:7",
        title = "Impractical Jokers",
        streamTitle = "FaselHD",
        providerName = "FaselHD",
        sourceUrl = url,
        fileName = "Impractical Jokers S13E07_hls-1.ts",
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
