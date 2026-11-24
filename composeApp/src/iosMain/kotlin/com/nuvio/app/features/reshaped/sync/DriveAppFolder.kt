package com.nuvio.app.features.reshaped.sync

import com.nuvio.app.samplay.samplayHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class DriveSyncFile(val id: String?, val text: String?, val others: List<Pair<String, String>> = emptyList())

/** SAMplay iOS port: Reshaped's one file in the viewer's Google Drive (drive.file scope). */
internal object DriveAppFolder {
    const val FILE_NAME = "Nuvio Reshaped sync.json"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    private val json = Json { ignoreUnknownKeys = true }

    class SignedOutException : Exception("Not signed in to Google")
    private class NotFoundException : Exception("Not found")

    suspend fun read(): DriveSyncFile {
        val listed = call {
            method = HttpMethod.Get
            url(FILES_URL)
            parameter("spaces", "drive")
            parameter("q", "name = '$FILE_NAME' and trashed = false")
            parameter("fields", "files(id,modifiedTime)")
            parameter("orderBy", "modifiedTime desc")
            parameter("pageSize", "10")
        }
        val files = runCatching { json.parseToJsonElement(listed).jsonObject["files"] as? JsonArray }.getOrNull() ?: JsonArray(emptyList())
        val ids = files.mapNotNull { (it as? JsonObject)?.str("id")?.takeIf(String::isNotBlank) }
        val id = ids.firstOrNull() ?: return DriveSyncFile(null, null)
        val others = ids.drop(1).mapNotNull { other -> runCatching { other to download(other) }.getOrNull() }
        return DriveSyncFile(id, download(id), others)
    }

    private suspend fun download(id: String): String = call {
        method = HttpMethod.Get
        url("$FILES_URL/$id?alt=media")
    }

    suspend fun delete(id: String) {
        try {
            call { method = HttpMethod.Delete; url("$FILES_URL/$id") }
        } catch (gone: NotFoundException) {
        }
    }

    suspend fun write(id: String?, text: String): String {
        if (id != null) {
            val updated = runCatching {
                call {
                    method = HttpMethod.Patch
                    url("$UPLOAD_URL/$id?uploadType=media&fields=id")
                    contentType(ContentType.Application.Json)
                    setBody(text)
                }
            }
            updated.exceptionOrNull()?.let { error -> if (error !is NotFoundException) throw error }
            if (updated.isSuccess) return id
        }
        val metadata = buildJsonObject {
            put("name", FILE_NAME)
            put("mimeType", "application/json")
        }.toString()
        val boundary = "samplay-sync-${kotlin.random.Random.nextLong().toULong()}"
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(text).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }
        val created = call {
            method = HttpMethod.Post
            url("$UPLOAD_URL?uploadType=multipart&fields=id")
            contentType(ContentType.parse("multipart/related; boundary=$boundary"))
            setBody(body)
        }
        return json.parseToJsonElement(created).jsonObject.str("id")
    }

    /** Runs a request with a fresh token, once more with a refreshed one on HTTP 401. */
    private suspend fun call(build: HttpRequestBuilder.() -> Unit): String {
        repeat(2) { attempt ->
            val token = GoogleAccount.accessToken(forceRefresh = attempt > 0) ?: throw SignedOutException()
            val response = samplayHttp.request {
                build()
                header("Authorization", "Bearer $token")
            }
            val result = response.textOrError()
            if (result != null) return result
        }
        throw SignedOutException()
    }

    private suspend fun HttpResponse.textOrError(): String? = when {
        status.isSuccess() -> bodyAsText()
        status.value == 401 -> null
        status.value == 404 -> throw NotFoundException()
        else -> {
            val reason = runCatching {
                (json.parseToJsonElement(bodyAsText()).jsonObject["error"] as? JsonObject)?.str("message")
            }.getOrNull()
            throw SyncIOException(if (reason.isNullOrBlank()) "Drive HTTP ${status.value}" else "Drive HTTP ${status.value}: $reason")
        }
    }
}
