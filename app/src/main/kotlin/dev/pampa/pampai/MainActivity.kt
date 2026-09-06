package dev.pampa.pampai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dagger.hilt.android.AndroidEntryPoint
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampai.core.assistant.runtime.AssistantRuntime
import dev.pampa.pampai.core.assistant.service.AssistantNotifications
import dev.pampa.pampai.core.assistant.settings.PampaiSettings
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.feature.assistant.EntryRequest
import dev.pampa.pampai.feature.assistant.PampaiRoot
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  @Inject lateinit var engineSettings: EngineSettingsStore
  @Inject lateinit var pampaiSettings: PampaiSettingsStore
  @Inject lateinit var runtime: AssistantRuntime

  /** L'ultima richiesta arrivata da fuori (notifica, scorciatoia): la home la consuma. */
  private val entry = MutableStateFlow<EntryRequest?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    entry.value = entryOf(intent)
    setContent {
      val engine by engineSettings.settings.collectAsState(initial = EngineSettings())
      // La prima schermata dipende da una lettura su disco: meglio un fotogramma vuoto che
      // l'onboarding che lampeggia davanti a chi l'ha gia' fatto.
      val pampai by produceState<PampaiSettings?>(initialValue = null) { value = pampaiSettings.settings.first() }
      val request by entry.collectAsState()
      PampaiTheme(settings = engine) {
        pampai?.let { PampaiRoot(startAtOnboarding = !it.onboardingDone, entry = request) }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    entryOf(intent)?.let { entry.value = it }
  }

  override fun onStart() {
    super.onStart()
    runtime.appInForeground = true
  }

  override fun onStop() {
    runtime.appInForeground = false
    super.onStop()
  }

  private fun entryOf(intent: Intent?): EntryRequest? {
    intent ?: return null
    val conversation = intent.getLongExtra(AssistantNotifications.EXTRA_CONVERSATION, -1L).takeIf { it > 0 }
    val voice = intent.getBooleanExtra(EXTRA_VOICE, false)
    if (conversation == null && !voice) return null
    return EntryRequest(conversationId = conversation, voice = voice)
  }

  companion object {
    const val EXTRA_VOICE = "dev.pampa.pampai.extra.VOICE"
  }
}
