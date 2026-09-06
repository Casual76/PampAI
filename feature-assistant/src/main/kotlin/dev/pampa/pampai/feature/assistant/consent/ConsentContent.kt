package dev.pampa.pampai.feature.assistant.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidHeroCard

/**
 * Le voci del consenso, alla prima accensione: cosa parte dal telefono, verso chi, cosa resta
 * qui, cosa Aria non fa mai da sola. Sono voci di una lista, cosi' vivono sia nell'onboarding
 * sia in una pagina a se'. Il tasto che accende e' l'unico modo di dire si'.
 */
fun LazyListScope.consentItems(
  canAccept: Boolean,
  onAccept: () -> Unit,
  onLater: (() -> Unit)?,
) {
  item {
    FluidHeroCard(
      title = "Prima di accendere Aria",
      subtitle = "Due parole su cosa fa dei tuoi dati, e cosa non fa mai da sola.",
    )
  }
  item { FluidSectionHeader(title = "Cosa parte dal telefono") }
  item {
    ConsentCard(
      "Le tue domande (scritte, o trascritte dalla voce), gli allegati che metti in chat, e i dati che gli strumenti leggono per rispondere: " +
        "dalle app Pampa collegate (registro, meteo, autobus, musica, store), dal telefono (calendario, contatti, notifiche, posizione) e dal web, solo quando la domanda li richiede. " +
        "Quando la richiami dal tasto di accensione, lo schermo entra solo se glielo chiedi o se Aria decide che le serve.",
    )
  }
  item { FluidSectionHeader(title = "Verso chi") }
  item {
    ConsentCard(
      "Verso il servizio che hai scelto e verificato con la tua chiave: Groq, Google (Gemini) o OpenRouter. " +
        "Non c'e' nessun server di PampAI in mezzo: i dati vanno dal telefono al servizio e basta, e valgono le regole di quel servizio sulla tua chiave.",
    )
  }
  item { FluidSectionHeader(title = "Cosa resta qui") }
  item {
    ConsentCard(
      "Le conversazioni e la memoria di Aria, salvate su questo telefono: le puoi rileggere, continuare o cancellare quando vuoi. " +
        "Le chiavi, cifrate nel Keystore del dispositivo. I consumi per servizio, contati qui per te.",
    )
  }
  item { FluidSectionHeader(title = "Cosa non fa") }
  item {
    ConsentCard(
      "Non chiama, non installa, non cancella e non prenota niente senza che tu confermi con un tasto o a voce. " +
        "Le azioni che decidi di fidarti (un timer, la torcia) le fa senza chiedere, e sei tu a scegliere quali. E' spenta finche' non la accendi qui.",
    )
  }
  item {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      FluidButton(
        text = if (canAccept) "Ho capito, accendi Aria" else "Serve prima una chiave verificata",
        enabled = canAccept,
        fillWidth = true,
        onClick = onAccept,
      )
      if (onLater != null) FluidButton(text = "Non ora", style = FluidButtonStyle.Plain, fillWidth = true, onClick = onLater)
    }
  }
}

@Composable
private fun ConsentCard(text: String) {
  FluidCard(glass = true) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
  }
}
