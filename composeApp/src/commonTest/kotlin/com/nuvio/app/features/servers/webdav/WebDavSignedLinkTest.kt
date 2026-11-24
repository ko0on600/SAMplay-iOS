package com.nuvio.app.features.servers.webdav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SAMplay 2.0.3: the link "Copy stream link" gives for a PikPak file (the user's phone, 19 Nov 2026). */
class WebDavSignedLinkTest {
    private val drive = "https://dav.example.com/sam/english/Cougar%20Town%20-%20S01E01.mp4"
    private val cdn = "https://cdn.example.net/file?sig=s"
    private val login = "Basic dGVzdDp0ZXN0"
    private val requests = mutableListOf<HttpRequestData>()

    private fun driveAnswering(answer: (HttpRequestData) -> Pair<HttpStatusCode, String?>): WebDavClient {
        val http = HttpClient(
            MockEngine { request ->
                requests += request
                val (status, location) = answer(request)
                respond("", status, if (location != null) headersOf(HttpHeaders.Location, location) else headersOf())
            },
        ) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return WebDavClient(http)
    }

    @Test
    fun theRedirectToTheSignedLinkIsFollowedWithRangeGetsOnly() = runTest {
        val client = driveAnswering { request ->
            if (request.url.host == "dav.example.com") HttpStatusCode.Found to cdn else HttpStatusCode.PartialContent to null
        }
        val statuses = mutableListOf<Int>()
        val link = client.signedLink(drive, login) { statuses += it }
        assertEquals(cdn, link.url)
        assertFalse(link.needsAuthorization)
        assertEquals(listOf(302, 206), statuses)
        assertTrue(requests.all { it.method == HttpMethod.Get && it.headers[HttpHeaders.Range] == "bytes=0-0" })
        assertEquals(login, requests[0].headers[HttpHeaders.Authorization])
        assertNull(requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun aFileTheDriveCannotFindIsMarkedMissing() = runTest {
        val link = driveAnswering { HttpStatusCode.NotFound to null }.signedLink(drive, login)
        assertTrue(link.missing)
        assertTrue(link.needsAuthorization)
        assertEquals(drive, link.url)
    }

    @Test
    fun theCopiedLinkCarriesTheFileNameAfterTheHash() {
        val link = "https://dl.example.net/download/?fid=1&sign=s"
        assertEquals(
            "$link#My%20Name%20Is%20Earl%20-%20S01E01.mp4",
            WebDavClient.withFileName(link, "/sam/english/\u0645\u0633\u0644\u0633\u0644\u0627\u062a/My Name Is Earl/S01/My Name Is Earl - S01E01.mp4"),
        )
        assertEquals("$link#%D9%84%D9%87%D9%81%D8%A9.mp4", WebDavClient.withFileName(link, "/sam/\u0644\u0647\u0641\u0629.mp4"))
        assertEquals("$link#Show.DD%2B5.1.mkv", WebDavClient.withFileName(link, "/tv/Show.DD+5.1.mkv"))
        assertEquals("$link#a%20b.mp4", WebDavClient.withFileName(link, "/tv/a%20b.mp4"))
        assertEquals("$link#x", WebDavClient.withFileName("$link#x", "/tv/a.mp4"))
        assertEquals(link, WebDavClient.withFileName(link, "/"))
    }

    @Test
    fun aDriveThatServesTheFileItselfGivesNoLinkWithoutTheLogin() = runTest {
        val link = driveAnswering { HttpStatusCode.PartialContent to null }.signedLink(drive, login)
        assertTrue(link.needsAuthorization)
        assertFalse(link.missing)
        assertEquals(drive, link.url)
        assertEquals(1, requests.size)
    }
}
