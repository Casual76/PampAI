package dev.pampa.pampai.feature.assistant.assist

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/** Cosa il sistema dice del ruolo di assistente: tenuto da PampAI, da un'altra app, o da nessuno. */
object AssistantRole {
  fun isHeld(context: Context): Boolean {
    val roles = context.getSystemService(RoleManager::class.java) ?: return false
    return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
  }

  /**
   * Il ruolo di assistente non si chiede con `RoleManager.createRequestRoleIntent` (non e'
   * richiedibile): l'unica strada e' la pagina delle impostazioni del sistema, con il ripiego
   * sulla pagina delle app predefinite dove quella non esiste.
   */
  fun openSettings(context: Context) {
    val intents = listOf(
      Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
      Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
      Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
      if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
    }
  }
}

/** Rilegge il ruolo ogni volta che la schermata torna davanti: si cambia nelle impostazioni di sistema. */
@Composable
fun rememberAssistantRoleHeld(): Boolean {
  val context = LocalContext.current
  var epoch by remember { mutableIntStateOf(0) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) epoch++ }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  return remember(epoch) { AssistantRole.isHeld(context) }
}

/**
 * La card "assistente di sistema": lo stato del ruolo e i due passi (impostare PampAI, attivare
 * testo e screenshot dallo schermo). Vive nell'onboarding e nelle impostazioni.
 */
@Composable
fun AssistantRoleCard() {
  val context = LocalContext.current
  val held = rememberAssistantRoleHeld()
  FluidListGroup(glass = true) {
    FluidListRow(
      title = "Assistente predefinito",
      subtitle = if (held) {
        "PampAI risponde al tasto di accensione: tienilo premuto e parla."
      } else {
        "Imposta PampAI come app assistente digitale: la trovi in Impostazioni > App > App predefinite > Assistente digitale."
      },
      badge = if (held) {
        null
      } else {
        {
          FluidButton(
            text = "Imposta",
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Small,
            onClick = { AssistantRole.openSettings(context) },
          )
        }
      },
    )
    FluidListDivider()
    FluidListRow(
      title = "Testo e screenshot dallo schermo",
      subtitle = "Nella stessa pagina attiva \"Usa testo dallo schermo\" e \"Usa screenshot\": cosi' Aria puo' leggere cosa c'e' sotto quando glielo chiedi.",
      onClick = { AssistantRole.openSettings(context) },
    )
  }
}
