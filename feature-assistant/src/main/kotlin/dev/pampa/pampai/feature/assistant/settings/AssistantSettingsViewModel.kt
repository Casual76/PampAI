package dev.pampa.pampai.feature.assistant.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiKeyVerifier
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.KeyState
import dev.antigravity.fluidengine.ai.keys.ModelCatalogStore
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.keys.VerifyResult
import dev.antigravity.fluidengine.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidengine.ai.orchestrator.AiRequestLog
import dev.antigravity.fluidengine.ai.provider.ModelCatalogue
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.OpenRouterKeyInfo
import dev.antigravity.fluidengine.ai.provider.OpenRouterDataPolicy
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampai.core.assistant.settings.PampaiSettings
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.core.assistant.bridge.RemoteCatalogs
import dev.pampa.pampai.core.assistant.runtime.PampaiConfirmationGate
import dev.pampa.pampai.core.assistant.tools.RegistryHolder
import dev.pampa.pampai.core.assistant.settings.SttMode
import dev.pampa.pampai.core.assistant.settings.TtsEngine
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AssistantSettingsUiState(
  val settings: AiSettings = AiSettings(),
  val keys: Map<ProviderId, KeyState> = emptyMap(),
  val catalogues: Map<ProviderId, ModelCatalogue> = emptyMap(),
  val keyInfo: Map<ProviderId, OpenRouterKeyInfo> = emptyMap(),
  val recent: List<AiRequestLog> = emptyList(),
  val pampai: PampaiSettings = PampaiSettings(),
  val engine: EngineSettings = EngineSettings(),
) {
  val verified: Set<ProviderId> get() = keys.filterValues { it.verified }.keys
  val enabled: Boolean get() = settings.enabled && verified.isNotEmpty()
}

/**
 * Le impostazioni di Aria: le chiavi, l'ordine dei provider, i modelli per livello, le
 * preferenze, la voce, l'aspetto. Tutto passa dagli store (dell'engine e di PampAI); il ViewModel
 * li unisce in uno stato solo e traduce i tocchi in scritture.
 */
@HiltViewModel
class AssistantSettingsViewModel @Inject constructor(
  private val settingsStore: AiSettingsStore,
  private val keyStore: AiKeyStore,
  private val verifier: AiKeyVerifier,
  private val catalogs: ModelCatalogStore,
  private val diagnostics: AiDiagnosticsLog,
  private val pampaiStore: PampaiSettingsStore,
  registryHolder: RegistryHolder,
  private val remoteCatalogs: RemoteCatalogs,
  private val engineStore: EngineSettingsStore,
) : ViewModel() {

  private val core = combine(settingsStore.settings, keyStore.states, catalogs.catalogues, verifier.keyInfo) { settings, keys, catalogues, info ->
    AssistantSettingsUiState(settings = settings, keys = keys, catalogues = catalogues, keyInfo = info)
  }

  val state: StateFlow<AssistantSettingsUiState> = combine(core, diagnostics.entries, pampaiStore.settings, engineStore.settings) { base, recent, pampai, engine ->
    base.copy(recent = recent, pampai = pampai, engine = engine)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantSettingsUiState())

  init {
    // I cataloghi si leggono dal disco all'apertura e si rinfrescano se vecchi di un giorno.
    viewModelScope.launch {
      keyStore.currentStates().filterValues { it.verified }.keys.forEach { provider ->
        launch { runCatching { verifier.refreshIfStale(provider) } }
      }
    }
  }

  fun setEnabled(enabled: Boolean) = viewModelScope.launch { settingsStore.setEnabled(enabled) }

  /** Il consenso e l'accensione insieme: e' la pagina di consenso a chiamarlo. */
  fun acceptConsentAndEnable() = viewModelScope.launch {
    settingsStore.setConsentAccepted(System.currentTimeMillis())
    settingsStore.setEnabled(true)
  }

  suspend fun saveAndVerify(provider: ProviderId, key: String?): VerifyResult {
    if (!key.isNullOrBlank()) keyStore.set(provider, key)
    return verifier.verify(provider)
  }

  fun removeKey(provider: ProviderId) = viewModelScope.launch { keyStore.set(provider, null) }

  fun setChatOrder(order: List<ProviderId>) = viewModelScope.launch { settingsStore.setChatOrder(order) }
  fun setSttOrder(order: List<ProviderId>) = viewModelScope.launch { settingsStore.setSttOrder(order) }

  fun setModel(provider: ProviderId, tier: ModelTier, model: String?) = viewModelScope.launch { settingsStore.setModel(provider, tier, model) }
  fun setSttModel(provider: ProviderId, model: String?) = viewModelScope.launch { settingsStore.setSttModel(provider, model) }
  fun setOpenRouterFallbacks(models: List<String>) = viewModelScope.launch { settingsStore.setOpenRouterFallbacks(models) }
  fun setOpenRouterDataPolicy(policy: OpenRouterDataPolicy) = viewModelScope.launch { settingsStore.setOpenRouterDataPolicy(policy) }

  fun setThinking(level: ThinkingLevel) = viewModelScope.launch { settingsStore.setThinking(level) }
  fun setSpeakReplies(speak: Boolean) = viewModelScope.launch { settingsStore.setSpeakReplies(speak) }
  fun setActionsEnabled(enabled: Boolean) = viewModelScope.launch { settingsStore.setActionsEnabled(enabled) }

  fun refreshCatalogue(provider: ProviderId) = viewModelScope.launch { runCatching { verifier.refreshIfStale(provider, force = true) } }

  // Voce.
  /** I tool con conferma che si possono fidare: nome e descrizione, dal catalogo di adesso. */
  val trustable: List<Pair<String, String>> = registryHolder.catalog.value.registry.tools
    .filter { it.needsConfirmation && PampaiConfirmationGate.canTrust(it.name) }
    .map { it.name to it.description }
    .sortedBy { it.first }

  val connected = remoteCatalogs.state
  val connectedRefreshing = remoteCatalogs.isRefreshing
  fun refreshConnected() = remoteCatalogs.refreshAsync()

  fun setTrusted(tool: String, trusted: Boolean) = viewModelScope.launch { pampaiStore.setTrusted(tool, trusted) }

  fun setSttMode(mode: SttMode) = viewModelScope.launch { pampaiStore.setSttMode(mode) }
  fun setStartInText(enabled: Boolean) = viewModelScope.launch { pampaiStore.setStartInText(enabled) }
  fun setThinkingAuto(auto: Boolean) = viewModelScope.launch { pampaiStore.setThinkingAuto(auto) }
  fun setFailoverEnabled(enabled: Boolean) = viewModelScope.launch { pampaiStore.setFailoverEnabled(enabled) }
  fun setTtsEngine(engine: TtsEngine) = viewModelScope.launch { pampaiStore.setTtsEngine(engine) }
  fun setOnboardingDone() = viewModelScope.launch { pampaiStore.setOnboardingDone(true) }

  // Aspetto: le impostazioni dell'engine.
  fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { engineStore.setThemeMode(mode) }
  fun setAmoled(enabled: Boolean) = viewModelScope.launch { engineStore.setAmoledEnabled(enabled) }
  fun setAccentMode(mode: AccentMode) = viewModelScope.launch { engineStore.setAccentMode(mode) }
  fun setCustomAccent(name: String) = viewModelScope.launch { engineStore.setCustomAccent(name) }
  fun setHaptics(enabled: Boolean) = viewModelScope.launch { engineStore.setHapticsEnabled(enabled) }
}
