package dev.pampa.pampai.feature.assistant.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.antigravity.fluidengine.ui.theme.FluidTheme

/**
 * Il colore di PampAI: ametista che vira al magenta. Distinto da tutte le app Pampa (blu Fluid,
 * verde Glass, arancio, viola nebula dello Store) e vicino all'alone multicolore dell'assistente.
 * Contrasto verificato (WCAG): 5,6:1 sul bianco nel tema chiaro, 7,6:1 sul fondo scuro nel
 * tema scuro, entrambi sopra la soglia AA per il testo normale.
 */
val PampaiBrand: AccentPreset = AccentPreset(
  name = "pampai",
  label = "PampAI",
  light = Color(0xFF9D2BD6),
  dark = Color(0xFFE879F9),
)

/**
 * Il tema dell'app: il design system dell'engine con il brand di PampAI.
 *
 * Il colore del testo di base e' messo qui, una volta: senza una `Surface` sopra, ogni `Text`
 * senza colore esplicito prendeva il nero di default di Compose, e in tema scuro il titolo della
 * chat e l'intestazione del cassetto erano nero su nero.
 */
@Composable
fun PampaiTheme(settings: EngineSettings, content: @Composable () -> Unit) {
  FluidTheme(settings = settings, brand = PampaiBrand) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
  }
}
