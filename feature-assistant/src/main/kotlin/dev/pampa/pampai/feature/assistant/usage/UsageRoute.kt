package dev.pampa.pampai.feature.assistant.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidMiniChart
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampai.core.assistant.usage.CostTable
import java.util.Locale

private fun tokens(n: Int): String = if (n >= 1_000_000) String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0) else if (n >= 1000) String.format(Locale.getDefault(), "%.1fk", n / 1000.0) else n.toString()
private fun cost(usd: Double): String = if (usd <= 0.0) "0 $" else String.format(Locale.getDefault(), "%.4f $", usd)

/** Il free tier di Gemini: circa mille richieste al giorno sui modelli flash, senza header di limite. La soglia dell'avviso. */
private const val GEMINI_FREE_DAILY = 1_000

/**
 * Il tracker dei consumi: periodo, totali con grafico, servizi e modelli, avvisi sui limiti,
 * azzeramento. I costi sono stime dal listino locale ([CostTable]); i piani gratuiti valgono zero.
 */
@Composable
fun UsageRoute(onBack: () -> Unit, viewModel: UsageViewModel = hiltViewModel()) {
  val summary by viewModel.summary.collectAsStateWithLifecycle()
  val period by viewModel.period.collectAsStateWithLifecycle()
  var confirmClear by remember { mutableStateOf(false) }

  FluidScreen(
    title = "Consumi",
    subtitle = "Richieste, token e costo stimato per servizio.",
    onBack = onBack,
    itemSpacing = 12.dp,
    ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Cards) },
  ) {
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsagePeriod.entries.forEach { p -> FluidChip(label = p.label, selected = period == p, onClick = { viewModel.setPeriod(p) }) }
      }
    }
    item {
      FluidCard(glass = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
          Stat("Richieste", summary.requests.toString(), Modifier.weight(1f))
          Stat("Token", tokens(summary.tokens), Modifier.weight(1f))
          Stat("Costo stimato", cost(summary.costUsd), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        FluidMiniChart(points = summary.series.ifEmpty { listOf(0f, 0f) }, color = MaterialTheme.colorScheme.primary, modifier = Modifier.height(72.dp))
        Spacer(Modifier.height(4.dp))
        Text(summary.seriesLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    if (summary.rateLimited > 0) {
      item { FluidInlineMessage(title = "Limiti raggiunti", message = "${summary.rateLimited} richieste rifiutate per limite (429) nel periodo: Aria passa al servizio dopo, ma conviene distanziare le domande o aggiungere una chiave.", tone = FluidTone.Warning) }
    }
    if (summary.geminiToday >= GEMINI_FREE_DAILY * 8 / 10) {
      item { FluidInlineMessage(title = "Gemini vicino al tetto giornaliero", message = "${summary.geminiToday} richieste oggi: il piano gratuito ne concede circa $GEMINI_FREE_DAILY al giorno.", tone = FluidTone.Warning) }
    }
    if (summary.providers.isEmpty()) {
      item { FluidInlineMessage(title = "Niente nel periodo", message = "Le richieste compaiono qui man mano che le fai.", tone = FluidTone.Info) }
    }
    summary.providers.forEach { provider ->
      item {
        FluidSectionHeader(
          title = provider.provider.label,
          detail = listOfNotNull(
            "${provider.requests} richieste",
            "${tokens(provider.tokens)} token",
            cost(provider.costUsd),
            provider.rateLimited.takeIf { it > 0 }?.let { "$it × 429" },
            provider.remainingRequests?.let { "restano $it richieste" + (provider.remainingTokens?.let { t -> ", ${tokens(t)} token" } ?: "") },
          ).joinToString(" · "),
        )
      }
      item {
        FluidListGroup(glass = true) {
          provider.models.forEachIndexed { index, model ->
            FluidListRow(
              title = model.model,
              subtitle = listOfNotNull(
                model.kind.lowercase(),
                "${model.requests} richieste",
                "${tokens(model.tokens)} token",
                model.audioSeconds.takeIf { it > 0 }?.let { "${it.toInt()} s audio" },
                model.errors.takeIf { it > 0 }?.let { "$it errori" },
              ).joinToString(" · "),
              meta = cost(model.costUsd),
            )
            if (index < provider.models.lastIndex) FluidListDivider()
          }
        }
      }
    }
    item {
      FluidCard {
        Text("I costi sono stime dal listino del ${CostTable.UPDATED}; i piani gratuiti valgono zero. OpenRouter riporta il costo vero quando lo manda.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        FluidButton(text = "Azzera il tracker", onClick = { confirmClear = true }, style = FluidButtonStyle.Plain, size = FluidButtonSize.Small)
      }
    }
  }
  if (confirmClear) {
    FluidAlert(
      onDismissRequest = { confirmClear = false },
      title = "Azzerare i consumi?",
      message = "Cancella lo storico delle richieste registrate da PampAI. Non tocca niente sui servizi.",
      actions = listOf(
        FluidAlertAction("Annulla", { confirmClear = false }),
        FluidAlertAction("Azzera", { viewModel.clear(); confirmClear = false }, FluidAlertAction.Emphasis.Destructive),
      ),
    )
  }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
  androidx.compose.foundation.layout.Column(modifier) {
    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
