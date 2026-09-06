package dev.pampa.pampai.feature.assistant.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme

/**
 * Il colore di PampAI: ametista che vira al magenta. Distinto da tutte le app Pampa (blu Fluid,
 * verde Glass, arancio, viola nebula dello Store) e vicino all'alone multicolore dell'assistente.
 * I due esadecimali sono da ritoccare a occhio sul telefono.
 */
val PampaiBrand: AccentPreset = AccentPreset(
  name = "pampai",
  label = "PampAI",
  light = Color(0xFF9D2BD6),
  dark = Color(0xFFE879F9),
)

/** Il tema dell'app: il design system dell'engine con il brand di PampAI. */
@Composable
fun PampaiTheme(settings: EngineSettings, content: @Composable () -> Unit) {
  FluidTheme(settings = settings, brand = PampaiBrand, content = content)
}
