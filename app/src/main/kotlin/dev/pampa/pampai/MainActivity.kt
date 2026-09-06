package dev.pampa.pampai

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
import dev.pampa.pampai.core.assistant.settings.PampaiSettings
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.feature.assistant.PampaiRoot
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  @Inject lateinit var engineSettings: EngineSettingsStore
  @Inject lateinit var pampaiSettings: PampaiSettingsStore

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val engine by engineSettings.settings.collectAsState(initial = EngineSettings())
      // La prima schermata dipende da una lettura su disco: meglio un fotogramma vuoto che
      // l'onboarding che lampeggia davanti a chi l'ha gia' fatto.
      val pampai by produceState<PampaiSettings?>(initialValue = null) { value = pampaiSettings.settings.first() }
      PampaiTheme(settings = engine) {
        pampai?.let { PampaiRoot(startAtOnboarding = !it.onboardingDone) }
      }
    }
  }
}
