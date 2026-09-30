package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.orchestrator.ProviderSwitch
import dev.antigravity.fluidengine.ai.orchestrator.SwitchReason
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.Run

/**
 * Cosa si sa di una risposta finita male, per il foglio "Dettagli": chi, con quale modello, perche',
 * con che codice e che cosa ha detto il servizio, e per quali servizi e' passata prima.
 *
 * "Il servizio ha risposto con un errore" da solo non dice a nessuno cosa fare; il codice e il
 * messaggio del servizio si' — a chi li va a cercare, e a chi li deve incollare in una segnalazione.
 * Viene dalla riga del run su disco ([Run.failure]) o, nel mezzo secondo prima che arrivi, dallo
 * stato `Failed` del runtime.
 */
@Immutable
internal data class FailureReport(
  val kind: FailureKind?,
  val provider: ProviderId?,
  val model: String?,
  val reason: SwitchReason?,
  val httpCode: Int?,
  val providerMessage: String?,
  val switches: List<ProviderSwitch>,
  val technical: String?,
) {
  /** La frase intera, la stessa che la risposta mostra. */
  val summary: String get() = kind?.let { AssistantTexts.failure(it, provider = provider) } ?: "Qualcosa e' andato storto."

  /** "Groq era al limite", se il servizio ha detto perche'. */
  val reasonLine: String? get() = reason?.let { r -> "${provider?.label ?: "Il servizio"} ${AssistantTexts.reasonText(r)}" }

  /** "Groq → Gemini → OpenRouter": i servizi per cui la domanda e' passata, se piu' d'uno. */
  val chain: String? get() {
    if (switches.isEmpty()) return null
    val hops = (listOf(switches.first().from) + switches.map { it.to }).fold(mutableListOf<ProviderId>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
    return hops.joinToString(" → ") { it.label }
  }

  /** Il perche' di ogni passaggio di mano, in una riga. */
  val chainReasons: String? get() = switches.takeIf { it.isNotEmpty() }?.distinctBy { it.from }?.joinToString(" · ") { "${it.from.label} ${AssistantTexts.reasonText(it.reason)}" }

  /** Il testo che "Copia" mette negli appunti: tutto, in righe semplici, pronto da incollare. */
  fun asText(): String = buildList {
    add("Aria · dettagli dell'errore")
    add(summary)
    provider?.let { add("Servizio: ${it.label}${model?.let { m -> " ($m)" } ?: ""}") }
    kind?.let { add("Motivo: ${AssistantTexts.failureTitle(it)}${reasonLine?.let { r -> " — $r" } ?: ""}") }
    httpCode?.let { add("Codice HTTP: $it") }
    providerMessage?.takeIf { it.isNotBlank() }?.let { add("Risposta del servizio: $it") }
    chain?.let { add("Servizi provati: $it${chainReasons?.let { r -> " ($r)" } ?: ""}") }
    technical?.takeIf { it.isNotBlank() && it != providerMessage }?.let { add("Dettaglio tecnico: $it") }
  }.joinToString("\n")

  companion object {
    /** Il resoconto di [message], dalla riga del run se c'e' e dallo stato vivo per il resto. */
    fun of(message: Message, run: Run?, live: AssistantState?): FailureReport {
      val failed = live as? AssistantState.Failed
      val detail = run?.failure
      val provider = detail?.provider ?: failed?.provider ?: run?.models?.lastOrNull()?.provider ?: run?.provider
      return FailureReport(
        kind = message.failureKind ?: failed?.kind,
        provider = provider,
        model = run?.models?.lastOrNull { it.provider == provider }?.model ?: run?.chatModel,
        reason = detail?.reason ?: failed?.reason,
        httpCode = detail?.httpCode ?: failed?.error?.httpCode,
        providerMessage = detail?.message ?: failed?.error?.providerMessage,
        switches = run?.switches.orEmpty(),
        technical = run?.error,
      )
    }
  }
}

/**
 * Il foglio "Dettagli" di una risposta fallita: vetro, dal padrone di casa dei modali alla radice
 * (lo stesso del selettore del modello), con in fondo "Copia". Sta nella pagina della chat e non
 * nell'item della lista: un portal dentro un item che esce di vista si chiuderebbe da solo.
 */
@Composable
internal fun FailureDetailsSheet(report: FailureReport?, onDismiss: () -> Unit) {
  val context = LocalContext.current
  FluidGlassModalPortal(
    item = report,
    onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.Sheet,
    paneTitle = "Dettagli dell'errore",
    footer = { current ->
      FluidButton(
        text = "Copia",
        onClick = { copy(context, current.asText()) },
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Medium,
        fillWidth = true,
        leading = { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
      )
    },
  ) { current -> FailureDetailsContent(current) }
}

/** Il contenuto del foglio, a se' per gli screenshot. */
@Composable
internal fun FailureDetailsContent(report: FailureReport) {
  val rows = buildList {
    // Senza servizio (nessuna chiave, un errore prima di chiamarne uno) la riga non c'e'.
    report.provider?.let { add(DetailRow("Servizio", it.label, report.model.orEmpty())) }
    report.kind?.let { add(DetailRow("Motivo", AssistantTexts.failureTitle(it), report.reasonLine.orEmpty())) }
    if (report.httpCode != null || !report.providerMessage.isNullOrBlank()) {
      add(DetailRow("Risposta del servizio", report.httpCode?.let { "Codice $it" } ?: "Senza codice", report.providerMessage.orEmpty()))
    }
    report.chain?.let { add(DetailRow("Servizi provati", it, report.chainReasons.orEmpty())) }
  }
  Column(Modifier.fillMaxWidth()) {
    Text("Cosa e' successo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    Text(report.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    if (rows.isNotEmpty()) {
      Spacer(Modifier.height(12.dp))
      FluidListGroup {
        rows.forEachIndexed { index, row ->
          if (index > 0) FluidListDivider()
          FluidListRow(title = row.value, subtitle = row.detail, eyebrow = row.label)
        }
      }
    }
    Spacer(Modifier.height(12.dp))
  }
}

private data class DetailRow(val label: String, val value: String, val detail: String)
