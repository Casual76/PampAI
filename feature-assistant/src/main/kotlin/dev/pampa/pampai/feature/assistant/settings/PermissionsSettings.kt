package dev.pampa.pampai.feature.assistant.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampai.core.assistant.permissions.SpecialAccess

/** Un permesso a runtime con le parole per spiegarlo. */
private data class RuntimePermission(val title: String, val why: String, val permissions: Array<String>)

private val RuntimePermissions = listOf(
  RuntimePermission("Calendario", "Per leggere, creare e spostare gli eventi.", arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
  RuntimePermission("Contatti", "Per trovare numeri ed email quando chiedi di chiamare o scrivere.", arrayOf(Manifest.permission.READ_CONTACTS)),
  RuntimePermission("Telefono", "Per far partire una chiamata (sempre con conferma).", arrayOf(Manifest.permission.CALL_PHONE)),
  RuntimePermission("Posizione", "Per \"dove sono?\" e per le app che vogliono il posto.", arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)),
)

/** Rilegge lo stato dei permessi ogni volta che la schermata torna davanti (si cambiano nelle impostazioni di sistema). */
@Composable
private fun rememberResumeEpoch(): Int {
  var epoch by remember { mutableIntStateOf(0) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) epoch++ }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  return epoch
}

/**
 * I permessi che i tool del telefono usano: quelli a runtime (un dialogo) e gli accessi speciali
 * (una pagina delle impostazioni). Ogni riga dice a cosa serve; niente si chiede prima del bisogno,
 * ma da qui si concede tutto in una volta.
 */
@Composable
fun PermissionsSection() {
  val context = LocalContext.current
  val resumeEpoch = rememberResumeEpoch()
  var epoch by remember { mutableIntStateOf(0) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { epoch++ }
  FluidListGroup(glass = true) {
    RuntimePermissions.forEachIndexed { index, permission ->
      val granted = remember(epoch, resumeEpoch) { permission.permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
      FluidListRow(
        title = permission.title,
        subtitle = if (granted) "Concesso. ${permission.why}" else permission.why,
        badge = if (granted) null else {
          {
            FluidButton(
              text = "Consenti",
              style = FluidButtonStyle.Tinted,
              size = FluidButtonSize.Small,
              onClick = {
                launcher.launch(permission.permissions)
                if (epoch > 0) runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
              },
            )
          }
        },
      )
      if (index < RuntimePermissions.lastIndex) FluidListDivider()
    }
    FluidListDivider()
    SpecialAccess.entries.forEachIndexed { index, access ->
      val granted = remember(resumeEpoch, epoch) { access.granted(context) }
      FluidListRow(
        title = access.label.replaceFirstChar { it.uppercase() },
        subtitle = when (access) {
          SpecialAccess.NOTIFICATIONS -> if (granted) "Attivo: Aria legge e chiude le notifiche, e vede la musica di ogni app." else "Per leggere le notifiche nella tendina e controllare la musica di qualsiasi app."
          SpecialAccess.WRITE_SETTINGS -> if (granted) "Attivo: luminosita' e rotazione." else "Per cambiare luminosita' e rotazione dello schermo."
          SpecialAccess.DND -> if (granted) "Attivo: Non disturbare e suoneria silenziosa." else "Per attivare Non disturbare e mettere il telefono in silenzioso."
          SpecialAccess.EXACT_ALARM -> if (granted) "Attivo: i promemoria suonano al minuto." else "Senza, i promemoria possono arrivare con qualche minuto di ritardo."
        },
        badge = if (granted) null else {
          {
            FluidButton(text = "Apri", style = FluidButtonStyle.Tinted, size = FluidButtonSize.Small, onClick = { runCatching { context.startActivity(access.intent(context)) } })
          }
        },
      )
      if (index < SpecialAccess.entries.lastIndex) FluidListDivider()
    }
  }
}

/**
 * Le azioni fidate: i tool con conferma che l'utente decide di lasciar fare senza chiedere (un
 * timer, la torcia). Chiamate, installazioni e cancellazioni non compaiono: si confermano sempre.
 */
@Composable
fun TrustedActionsSection(viewModel: AssistantSettingsViewModel, state: AssistantSettingsUiState) {
  val trusted = state.pampai.trustedActions
  FluidCard {
    if (viewModel.trustable.isEmpty()) {
      Text("Nessuna azione da fidare per ora.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      return@FluidCard
    }
    Column {
      Text(
        "Tocca un'azione per non farle piu' chiedere conferma. Chiamate, installazioni e cancellazioni la chiedono sempre.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        viewModel.trustable.forEach { (name, _) ->
          FluidChip(label = name.replace('_', ' '), selected = name in trusted, onClick = { viewModel.setTrusted(name, name !in trusted) })
        }
      }
    }
  }
}
