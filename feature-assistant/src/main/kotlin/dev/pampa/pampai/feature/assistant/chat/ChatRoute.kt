package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState

/** La chat con Aria. Per ora la pagina vuota: la conversazione arriva col passo A2. */
@Composable
fun ChatRoute(bottomInset: Dp) {
  FluidScreen(
    title = "Aria",
    subtitle = "Chiedimi qualcosa.",
    extraBottomPadding = bottomInset,
    ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Cards) },
  ) {
    item {
      FluidEmptyState(
        title = "La chat arriva nel prossimo passo",
        detail = "Intanto dalle impostazioni puoi verificare le chiavi, scegliere i modelli e impostare PampAI come assistente.",
      )
    }
  }
}
