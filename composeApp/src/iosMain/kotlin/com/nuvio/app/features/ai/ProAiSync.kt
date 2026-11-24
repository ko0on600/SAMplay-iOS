package com.nuvio.app.features.ai

import com.nuvio.app.samplay.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * SAMplay: the Pro AI service, keys and models travel between the phone and the TV with Google
 * sync, in the shared settings section (the phone app uses the same names). Only what was set is
 * sent, so a device without a key never replaces one another device has; a key removed on one
 * device is removed on the others.
 */
object ProAiSync {
    internal const val PROVIDER = "ai_pro_provider"
    internal fun keyName(provider: ProProvider) = "ai_pro_key_${provider.id}"
    internal fun modelName(provider: ProProvider) = "ai_pro_model_${provider.id}"

    /** One device's Pro AI settings as stored (blank: not set). */
    internal data class Stored(
        val keys: Map<ProProvider, String>,
        val models: Map<ProProvider, String>,
        val provider: ProProvider,
    )

    internal sealed interface Change {
        data class Key(val provider: ProProvider, val value: String) : Change
        data class Model(val provider: ProProvider, val value: String) : Change
        data class Select(val provider: ProProvider) : Change
    }

    fun current(): Map<String, JsonElement> = current(com.nuvio.app.samplay.IosContext)

    fun apply(current: Map<String, JsonElement>, merged: Map<String, JsonElement?>) =
        apply(com.nuvio.app.samplay.IosContext, current, merged)

    /** This device's keys, chosen models and service (set values only). */
    fun current(context: Context): Map<String, JsonElement> {
        AiSettings.initialize(context)
        return values(
            Stored(
                keys = ProProvider.entries.associateWith { AiSettings.proKey(it) },
                models = ProProvider.entries.associateWith { AiSettings.storedProModel(it) },
                provider = AiSettings.proProvider.value,
            ),
        )
    }

    /**
     * Sets what [merged] (the shared section; a null value is a deletion) has that differs from
     * [current]; anything else in the section is left alone.
     */
    fun apply(context: Context, current: Map<String, JsonElement>, merged: Map<String, JsonElement?>) {
        AiSettings.initialize(context)
        changes(current, merged, AiSettings.proProvider.value).forEach { change ->
            when (change) {
                is Change.Key -> AiSettings.setProKey(change.provider, change.value)
                is Change.Model -> AiSettings.setProModel(change.provider, change.value)
                is Change.Select -> AiSettings.selectProProvider(change.provider)
            }
        }
    }

    internal fun values(stored: Stored): Map<String, JsonElement> {
        val values = LinkedHashMap<String, JsonElement>()
        ProProvider.entries.forEach { provider ->
            stored.keys[provider]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { values[keyName(provider)] = JsonPrimitive(it) }
            stored.models[provider]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { values[modelName(provider)] = JsonPrimitive(it) }
        }
        // The chosen service goes with the keys: a device with none takes the other's choice.
        if (values.isNotEmpty()) values[PROVIDER] = JsonPrimitive(stored.provider.id)
        return values
    }

    internal fun changes(
        current: Map<String, JsonElement>,
        merged: Map<String, JsonElement?>,
        selected: ProProvider,
    ): List<Change> {
        val out = ArrayList<Change>()
        ProProvider.entries.forEach { provider ->
            differs(current, merged, keyName(provider))?.let { out += Change.Key(provider, it) }
            differs(current, merged, modelName(provider))?.let { out += Change.Model(provider, it) }
        }
        (merged[PROVIDER] as? JsonPrimitive)?.contentOrNull
            ?.let { id -> ProProvider.entries.firstOrNull { it.id == id } }
            ?.takeIf { it != selected }
            ?.let { out += Change.Select(it) }
        return out
    }

    /** The value [merged] has for [name] when it differs from this device's ("" for a deletion). */
    private fun differs(current: Map<String, JsonElement>, merged: Map<String, JsonElement?>, name: String): String? {
        if (!merged.containsKey(name)) return null
        val value = (merged[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val here = (current[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return value.takeIf { it != here }
    }
}
