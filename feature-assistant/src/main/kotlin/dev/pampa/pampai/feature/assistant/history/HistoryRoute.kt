package dev.pampa.pampai.feature.assistant.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState

/** Le conversazioni passate. Per ora la pagina vuota: la cronologia arriva col passo A2. */
@Composable
fun HistoryRoute(bottomInset: Dp) {
  FluidScreen(title = "Cronologia", subtitle = "Le conversazioni con Aria.", extraBottomPadding = bottomInset) {
    item { FluidEmptyState(title = "Nessuna conversazione", detail = "Quelle che farai restano qui, sul telefono.") }
  }
}
