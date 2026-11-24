package com.nuvio.app.features.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings of the AI subtitle tools (from SAMplay): the whisper model, the languages last used,
 * and "Pro AI" translation: the service (Claude, ChatGPT, Gemini or Grok) and, for each, the user's
 * own API key and model. Nothing here is built in: without a key Pro AI is simply not offered.
 */
object AiSettings {
    private const val PREFS = "nuvio_ai_subtitles"
    /** Claude's key and model keep SAMplay's names; the other services add their id. */
    private const val KEY_PRO_KEY = "pro_ai_key"
    private const val KEY_PRO_MODEL = "pro_ai_model"
    private const val KEY_PRO_PROVIDER = "pro_ai_provider"
    private const val KEY_WHISPER_MODEL = "whisper_model"
    private const val KEY_TARGET = "target_language"
    private const val KEY_SPOKEN = "spoken_language"

    @Volatile private var prefs: SharedPreferences? = null

    private val _proAiKeySet = MutableStateFlow(false)
    /** Whether the chosen service has a key saved (the key itself is never shown again). */
    val proAiKeySet: StateFlow<Boolean> = _proAiKeySet.asStateFlow()

    private val _proProvider = MutableStateFlow(ProProvider.CLAUDE)
    /** The service Pro AI translates with. */
    val proProvider: StateFlow<ProProvider> = _proProvider.asStateFlow()

    private val _whisperModelId = MutableStateFlow("base")
    val whisperModelId: StateFlow<String> = _whisperModelId.asStateFlow()

    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            _proProvider.value = ProProvider.byId(p.getString(KEY_PRO_PROVIDER, null))
            _proAiKeySet.value = p.getString(keyName(_proProvider.value), "").orEmpty().isNotBlank()
            _whisperModelId.value = p.getString(KEY_WHISPER_MODEL, "base") ?: "base"
        }
    }

    private fun p(): SharedPreferences? = prefs

    private fun keyName(provider: ProProvider) =
        if (provider == ProProvider.CLAUDE) KEY_PRO_KEY else "${KEY_PRO_KEY}_${provider.id}"

    private fun modelName(provider: ProProvider) =
        if (provider == ProProvider.CLAUDE) KEY_PRO_MODEL else "${KEY_PRO_MODEL}_${provider.id}"

    fun selectProProvider(provider: ProProvider) {
        p()?.edit()?.putString(KEY_PRO_PROVIDER, provider.id)?.apply()
        _proProvider.value = provider
        _proAiKeySet.value = proKey(provider).isNotBlank()
    }

    fun proKey(provider: ProProvider): String = p()?.getString(keyName(provider), "").orEmpty()

    fun setProKey(provider: ProProvider, value: String) {
        p()?.edit()?.putString(keyName(provider), value.trim())?.apply()
        if (provider == _proProvider.value) _proAiKeySet.value = value.isNotBlank()
    }

    fun proModel(provider: ProProvider): String =
        p()?.getString(modelName(provider), null)?.takeIf { it.isNotBlank() } ?: provider.defaultModel

    /** SAMplay sync: the model the viewer chose for [provider], as stored (blank for the default). */
    fun storedProModel(provider: ProProvider): String = p()?.getString(modelName(provider), null).orEmpty().trim()

    /** A blank [value] goes back to the service's default model. */
    fun setProModel(provider: ProProvider, value: String) {
        p()?.edit()?.putString(modelName(provider), value.trim())?.apply()
    }

    /** The chosen service's key. */
    var proAiKey: String
        get() = proKey(_proProvider.value)
        set(value) = setProKey(_proProvider.value, value)

    /** The chosen service's model. */
    var proAiModel: String
        get() = proModel(_proProvider.value)
        set(value) = setProModel(_proProvider.value, value)

    var whisperModel: String
        get() = _whisperModelId.value
        set(value) {
            val id = if (value == "tiny") "tiny" else "base"
            p()?.edit()?.putString(KEY_WHISPER_MODEL, id)?.apply()
            _whisperModelId.value = id
        }

    /** The language translations go into; Arabic until another is chosen. */
    var targetLanguage: String
        get() = p()?.getString(KEY_TARGET, null)?.takeIf { it.isNotBlank() } ?: "ar"
        set(value) { p()?.edit()?.putString(KEY_TARGET, value)?.apply() }

    /** SAMplay: a translation shows the translated line only (default) or the original above it. */
    var translateShowBoth: Boolean
        get() = p()?.getBoolean("translate_show_both", false) ?: false
        set(value) { p()?.edit()?.putBoolean("translate_show_both", value)?.apply() }

    /** SAMplay: every finished translation is also saved to Download/SAMplay/Subtitles. */
    var saveTranslations: Boolean
        get() = p()?.getBoolean("save_translations", false) ?: false
        set(value) { p()?.edit()?.putBoolean("save_translations", value)?.apply() }

    /** The language spoken in the video for whisper; "auto" detects it. */
    var spokenLanguage: String
        get() = p()?.getString(KEY_SPOKEN, null)?.takeIf { it.isNotBlank() } ?: "auto"
        set(value) { p()?.edit()?.putString(KEY_SPOKEN, value)?.apply() }
}
