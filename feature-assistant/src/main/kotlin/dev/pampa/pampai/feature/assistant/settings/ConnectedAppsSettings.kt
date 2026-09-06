package dev.pampa.pampai.feature.assistant.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ai.bridge.RemoteAvailability
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow

/**
 * Le app Pampa collegate attraverso il bridge: quali rispondono, quanti strumenti portano, e
 * perche' una non risponde (firma diversa, da aggiornare, non pronta). "Aggiorna" rifa' la
 * ricerca; succede da solo anche quando un'app si installa o si aggiorna.
 */
@Composable
fun ConnectedAppsSection(viewModel: AssistantSettingsViewModel) {
  val apps by viewModel.connected.collectAsStateWithLifecycle()
  val refreshing by viewModel.connectedRefreshing.collectAsStateWithLifecycle()
  if (apps.isEmpty()) {
    FluidCard {
      Text(
        "Nessuna app collegata trovata. Le app Pampa espongono i loro strumenti ad Aria dalle versioni con il bridge: aggiornale dal Pampa Store, poi tocca Aggiorna.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FluidButton(text = if (refreshing) "Cerco…" else "Aggiorna", onClick = viewModel::refreshConnected, style = FluidButtonStyle.Plain, size = FluidButtonSize.Small, enabled = !refreshing)
    }
    return
  }
  FluidListGroup(glass = true) {
    apps.forEachIndexed { index, app ->
      FluidListRow(
        title = app.label,
        subtitle = when (app.availability) {
          RemoteAvailability.INSTALLED_OK -> "${app.toolCount} strumenti · v${app.version}" + (app.hint?.let { " · $it" } ?: "")
          RemoteAvailability.NOT_READY -> "Installata ma non pronta: aprila una volta (accesso, dati) e riprova."
          RemoteAvailability.NEEDS_UPDATE -> "Installata senza il bridge: aggiornala dal Pampa Store."
          RemoteAvailability.NO_PERMISSION -> "Firma diversa: e' una build di sviluppo? Gli strumenti passano solo fra app firmate con la stessa chiave."
          RemoteAvailability.NOT_INSTALLED -> "Non installata."
        },
        meta = when (app.availability) {
          RemoteAvailability.INSTALLED_OK -> "collegata"
          RemoteAvailability.NOT_READY -> "non pronta"
          else -> "no"
        },
      )
      if (index < apps.lastIndex) FluidListDivider()
    }
    FluidListDivider()
    FluidListRow(
      title = if (refreshing) "Cerco…" else "Aggiorna",
      subtitle = "Ricerca di nuovo le app che espongono strumenti.",
      onClick = { if (!refreshing) viewModel.refreshConnected() },
    )
  }
}
