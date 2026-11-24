package com.nuvio.app.samplay

import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KJsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * SAMplay iOS: just enough of Android's org.json, java.io and java.util.concurrent for the SAMplay
 * code that was written against them (AI translation, Pro AI, sync), so it runs unchanged on iOS.
 */
open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

class JSONException(message: String) : RuntimeException(message)

private val compatJson = Json { isLenient = true }

private fun wrap(e: JsonElement?): Any? = when (e) {
    null, JsonNull -> null
    is KJsonObject -> JSONObject(e)
    is KJsonArray -> JSONArray(e)
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.longOrNull != null -> e.longOrNull
        e.doubleOrNull != null -> e.doubleOrNull
        else -> e.content
    }
}

internal fun toElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is JSONObject -> v.toElement()
    is JSONArray -> v.toElement()
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> KJsonObject(v.entries.associate { it.key.toString() to toElement(it.value) })
    is Collection<*> -> KJsonArray(v.map { toElement(it) })
    else -> JsonPrimitive(v.toString())
}

class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(text: String) : this() {
        val parsed = try { compatJson.parseToJsonElement(text) } catch (e: Exception) { throw JSONException(e.message ?: "bad json") }
        val obj = parsed as? KJsonObject ?: throw JSONException("not a JSON object")
        obj.forEach { (k, v) -> map[k] = wrap(v) }
    }

    internal constructor(obj: KJsonObject) : this() { obj.forEach { (k, v) -> map[k] = wrap(v) } }

    fun put(key: String, value: Any?): JSONObject { if (value == null) map.remove(key) else map[key] = value; return this }
    fun has(key: String): Boolean = map.containsKey(key)
    fun isNull(key: String): Boolean = map[key] == null
    fun opt(key: String): Any? = map[key]
    fun get(key: String): Any = map[key] ?: throw JSONException("No value for $key")
    fun remove(key: String): Any? = map.remove(key)
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun length(): Int = map.size

    fun optString(key: String, fallback: String = ""): String = when (val v = map[key]) {
        null -> fallback
        is String -> v
        else -> v.toString()
    }
    fun getString(key: String): String = map[key]?.let { if (it is String) it else it.toString() } ?: throw JSONException("No value for $key")
    fun optLong(key: String, fallback: Long = 0L): Long = num(map[key])?.toLong() ?: fallback
    fun getLong(key: String): Long = num(map[key])?.toLong() ?: throw JSONException("No long $key")
    fun optInt(key: String, fallback: Int = 0): Int = num(map[key])?.toInt() ?: fallback
    fun getInt(key: String): Int = num(map[key])?.toInt() ?: throw JSONException("No int $key")
    fun optDouble(key: String, fallback: Double = Double.NaN): Double = num(map[key])?.toDouble() ?: fallback
    fun getDouble(key: String): Double = num(map[key])?.toDouble() ?: throw JSONException("No double $key")
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = when (val v = map[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: fallback
        else -> fallback
    }
    fun getBoolean(key: String): Boolean = map[key] as? Boolean ?: throw JSONException("No boolean $key")
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun getJSONObject(key: String): JSONObject = map[key] as? JSONObject ?: throw JSONException("No object $key")
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray
    fun getJSONArray(key: String): JSONArray = map[key] as? JSONArray ?: throw JSONException("No array $key")

    internal fun toElement(): KJsonObject = KJsonObject(map.mapValues { toElement(it.value) })
    override fun toString(): String = toElement().toString()
}

class JSONArray() {
    private val list = ArrayList<Any?>()

    constructor(text: String) : this() {
        val parsed = try { compatJson.parseToJsonElement(text) } catch (e: Exception) { throw JSONException(e.message ?: "bad json") }
        val arr = parsed as? KJsonArray ?: throw JSONException("not a JSON array")
        arr.forEach { list += wrap(it) }
    }
    constructor(items: Collection<*>) : this() { items.forEach { list += it } }
    internal constructor(arr: KJsonArray) : this() { arr.forEach { list += wrap(it) } }

    fun put(value: Any?): JSONArray { list += value; return this }
    fun length(): Int = list.size
    fun opt(i: Int): Any? = list.getOrNull(i)
    fun get(i: Int): Any = list.getOrNull(i) ?: throw JSONException("No value at $i")
    fun optString(i: Int, fallback: String = ""): String = list.getOrNull(i)?.let { if (it is String) it else it.toString() } ?: fallback
    fun getString(i: Int): String = list.getOrNull(i)?.let { if (it is String) it else it.toString() } ?: throw JSONException("No value at $i")
    fun optJSONObject(i: Int): JSONObject? = list.getOrNull(i) as? JSONObject
    fun getJSONObject(i: Int): JSONObject = list.getOrNull(i) as? JSONObject ?: throw JSONException("No object at $i")
    fun optJSONArray(i: Int): JSONArray? = list.getOrNull(i) as? JSONArray
    fun getJSONArray(i: Int): JSONArray = list.getOrNull(i) as? JSONArray ?: throw JSONException("No array at $i")
    fun optLong(i: Int, fallback: Long = 0L): Long = num(list.getOrNull(i))?.toLong() ?: fallback
    fun optInt(i: Int, fallback: Int = 0): Int = num(list.getOrNull(i))?.toInt() ?: fallback
    fun optDouble(i: Int, fallback: Double = Double.NaN): Double = num(list.getOrNull(i))?.toDouble() ?: fallback

    internal fun toElement(): KJsonArray = KJsonArray(list.map { toElement(it) })
    override fun toString(): String = toElement().toString()
}

private fun num(v: Any?): Number? = when (v) {
    is Number -> v
    is String -> v.toLongOrNull() ?: v.toDoubleOrNull()
    else -> null
}

/** java.util.concurrent.atomic stand-ins (callers only need get/set/increment). */
class AtomicInteger(initial: Int = 0) {
    private val ref = atomic(initial)
    fun get(): Int = ref.value
    fun set(v: Int) { ref.value = v }
    fun incrementAndGet(): Int = ref.incrementAndGet()
    fun getAndIncrement(): Int = ref.getAndIncrement()
    fun decrementAndGet(): Int = ref.decrementAndGet()
    fun addAndGet(d: Int): Int = ref.addAndGet(d)
    fun compareAndSet(expect: Int, update: Int): Boolean = ref.compareAndSet(expect, update)
}

class AtomicReference<T>(initial: T) {
    private val ref = atomic(initial)
    fun get(): T = ref.value
    fun set(v: T) { ref.value = v }
    fun getAndSet(v: T): T = ref.getAndSet(v)
    fun compareAndSet(expect: T, update: T): Boolean = ref.compareAndSet(expect, update)
}

class AtomicBoolean(initial: Boolean = false) {
    private val ref = atomic(initial)
    fun get(): Boolean = ref.value
    fun set(v: Boolean) { ref.value = v }
    fun getAndSet(v: Boolean): Boolean = ref.getAndSet(v)
    fun compareAndSet(expect: Boolean, update: Boolean): Boolean = ref.compareAndSet(expect, update)
}
