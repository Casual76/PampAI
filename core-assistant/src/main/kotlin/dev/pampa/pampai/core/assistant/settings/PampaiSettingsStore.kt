package dev.pampa.pampai.core.assistant.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Come si ascolta: parziali di sistema + Whisper alla fine, solo Whisper, solo sistema. */
enum class SttMode { DUAL, WHISPER, SYSTEM }

/** Chi legge le risposte ad alta voce. */
enum class TtsEngine { SYSTEM, GEMINI, GROQ_EN }

/**
 * Le impostazioni di PampAI che non stanno nell'engine (quelle dell'assistente — chiavi, ordine
 * dei provider, modelli — sono di `AiSettingsStore`, e restano li').
 */
data class PampaiSettings(
  val onboardingDone: Boolean = false,
  val sttMode: SttMode = SttMode.DUAL,
  /** All'invocazione la barra parte come campo di testo invece che come visualizzatore. */
  val startInText: Boolean = false,
  val ttsEngine: TtsEngine = TtsEngine.SYSTEM,
  val ttsVoice: String? = null,
  /** I tool con conferma che l'utente ha deciso di fidarsi: non chiedono piu'. */
  val trustedActions: Set<String> = emptySet(),
  /**
   * Gli esempi della prima chat sono gia' stati visti.
   *
   * Servono una volta, per far capire cosa si puo' chiedere; dalla seconda in poi sono un muro di
   * testo davanti a una pagina vuota, e al loro posto resta il saluto.
   */
  val suggestionsSeen: Boolean = false,
)

private val Context.pampaiStore: DataStore<Preferences> by preferencesDataStore(name = "pampai")

class PampaiSettingsStore(private val context: Context) {

  val settings: Flow<PampaiSettings> = context.pampaiStore.data.map { it.toSettings() }

  suspend fun current(): PampaiSettings = settings.first()

  suspend fun setOnboardingDone(done: Boolean) = edit { it[Keys.OnboardingDone] = done }
  suspend fun setSttMode(mode: SttMode) = edit { it[Keys.SttMode] = mode.name }
  suspend fun setStartInText(enabled: Boolean) = edit { it[Keys.StartInText] = enabled }
  suspend fun setTtsEngine(engine: TtsEngine) = edit { it[Keys.TtsEngine] = engine.name }
  suspend fun setTtsVoice(voice: String?) = edit { if (voice == null) it.remove(Keys.TtsVoice) else it[Keys.TtsVoice] = voice }
  suspend fun setSuggestionsSeen() = edit { it[Keys.SuggestionsSeen] = true }
  suspend fun setTrusted(tool: String, trusted: Boolean) = edit {
    val now = it[Keys.TrustedActions].orEmpty()
    it[Keys.TrustedActions] = if (trusted) now + tool else now - tool
  }

  private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
    context.pampaiStore.edit(block)
  }

  private fun Preferences.toSettings(): PampaiSettings {
    val defaults = PampaiSettings()
    return PampaiSettings(
      onboardingDone = this[Keys.OnboardingDone] ?: defaults.onboardingDone,
      sttMode = this[Keys.SttMode]?.let { name -> runCatching { SttMode.valueOf(name) }.getOrNull() } ?: defaults.sttMode,
      startInText = this[Keys.StartInText] ?: defaults.startInText,
      ttsEngine = this[Keys.TtsEngine]?.let { name -> runCatching { TtsEngine.valueOf(name) }.getOrNull() } ?: defaults.ttsEngine,
      ttsVoice = this[Keys.TtsVoice],
      trustedActions = this[Keys.TrustedActions] ?: defaults.trustedActions,
      suggestionsSeen = this[Keys.SuggestionsSeen] ?: defaults.suggestionsSeen,
    )
  }

  private object Keys {
    val OnboardingDone = booleanPreferencesKey("onboarding_done")
    val SttMode = stringPreferencesKey("stt_mode")
    val StartInText = booleanPreferencesKey("start_in_text")
    val TtsEngine = stringPreferencesKey("tts_engine")
    val TtsVoice = stringPreferencesKey("tts_voice")
    val TrustedActions = stringSetPreferencesKey("trusted_actions")
    val SuggestionsSeen = booleanPreferencesKey("suggestions_seen")
  }
}
