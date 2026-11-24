package com.nuvio.app.features.reshaped

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.StoredLibraryPayload
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * SAMplay 2.0: reads what SAMplay v32 (the app 2.0 installs over) kept, in v32's own formats,
 * and merges it into 2.0's. Pure, so the rules are unit-tested; [SamplayV32Import] (Android)
 * does the reading and writing. Nothing the viewer already has in 2.0 is replaced.
 */
internal object SamplayV32Data {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** v32 "sam_addons" › "urls": a JSON array of manifest URLs, in the viewer's order. */
    fun parseAddonUrls(raw: String?): List<String> =
        parseArray(raw)
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()

    /** v32 "sam_addons" › "enabled": a JSON object, manifest URL → on/off. */
    fun parseAddonEnabled(raw: String?): Map<String, Boolean> {
        val obj = parseObject(raw) ?: return emptyMap()
        return obj.mapNotNull { (url, value) ->
            val enabled = (value as? JsonPrimitive)?.booleanOrNull ?: return@mapNotNull null
            url.trim().takeIf(String::isNotEmpty)?.let { it to enabled }
        }.toMap()
    }

    /**
     * 2.0's installed list (newline-separated) with v32's add-ons added after the ones already
     * there; null when there is nothing new.
     */
    fun mergeAddonUrls(current: String?, v32Urls: List<String>): String? {
        val existing = current.orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val added = v32Urls.filter { url -> existing.none { it.equals(url, ignoreCase = true) } }
        if (added.isEmpty()) return null
        return (existing + added).joinToString("\n")
    }

    /**
     * 2.0's on/off lines ("url<TAB>true|false") with a line for each v32 add-on that has none;
     * add-ons v32 never switched off stay on. Null when there is nothing new.
     */
    fun mergeAddonEnabled(current: String?, v32Urls: List<String>, v32Enabled: Map<String, Boolean>): String? {
        val lines = current.orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val known = lines.map { it.substringBefore('\t').trim() }
        val added = v32Urls
            .filter { url -> known.none { it.equals(url, ignoreCase = true) } }
            .map { url -> "$url\t${v32Enabled[url] ?: true}" }
        if (added.isEmpty()) return null
        return (lines + added).joinToString("\n")
    }

    /** A v32 WebDAV / PikPak drive (v32 "sam_webdav" › "accounts"). */
    data class Drive(val label: String, val baseUrl: String, val username: String, val password: String) {
        /** Stable across launches (the address and user, not the secret). */
        val key: String get() = "${baseUrl.trim().trimEnd('/').lowercase()}|${username.trim()}"

        override fun toString(): String = "Drive(label=$label, baseUrl=$baseUrl)"
    }

    fun parseDrives(raw: String?): List<Drive> =
        parseArray(raw).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val baseUrl = o.text("baseUrl")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            Drive(
                label = o.text("label").orEmpty(),
                baseUrl = baseUrl,
                username = o.text("username").orEmpty(),
                password = o.text("password").orEmpty(),
            )
        }.distinctBy(Drive::key)

    /** v32 "my_library" › "items": the titles kept from Discover, newest first. */
    fun parseLibrary(raw: String?, nowEpochMs: Long): List<LibraryItem> =
        parseArray(raw).mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.text("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val type = o.text("type")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val addedAt = (o["addedAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L } ?: nowEpochMs
            LibraryItem(
                id = id,
                type = type,
                name = o.text("name")?.takeIf(String::isNotBlank) ?: id,
                poster = o.text("poster")?.takeIf(String::isNotBlank),
                releaseInfo = o.text("releaseInfo")?.takeIf(String::isNotBlank),
                imdbId = id.takeIf { it.startsWith("tt") },
                savedAtEpochMs = addedAt,
            )
        }.distinctBy { it.type to it.id }

    /**
     * 2.0's stored library (its JSON payload) with v32's titles added; titles already saved keep
     * 2.0's copy. Null when there is nothing new.
     */
    fun mergeLibraryPayload(current: String?, v32Items: List<LibraryItem>): String? {
        if (v32Items.isEmpty()) return null
        val stored = current?.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString<StoredLibraryPayload>(it) }.getOrNull() ?: return null
        } ?: StoredLibraryPayload()
        val added = v32Items.filter { item -> stored.items.none { it.id == item.id && it.type == item.type } }
        if (added.isEmpty()) return null
        val items = (stored.items + added).sortedByDescending(LibraryItem::savedAtEpochMs)
        return json.encodeToString(stored.copy(items = items))
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun parseArray(raw: String?): List<kotlinx.serialization.json.JsonElement> =
        raw?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            .orEmpty()

    private fun parseObject(raw: String?): JsonObject? =
        raw?.takeIf(String::isNotBlank)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
}
