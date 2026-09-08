package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.pampai.core.assistant.db.Run
import java.util.Locale

/**
 * Cosa ha fatto Aria prima di rispondere, in una riga che si apre.
 *
 * Sta **sopra** la risposta, non sotto: e' il lavoro che l'ha prodotta, e leggerlo dopo vuol dire
 * leggerlo quando non serve piu'. Chiusa dice una cosa sola ("Ho chiesto a Convert to it!") e chi
 * ha risposto per ultimo; aperta mostra modelli, gruppi e ogni chiamata con i suoi argomenti.
 *
 * C'e' anche senza strumenti: una risposta "da sola" a una domanda difficile viene dal profondo,
 * e se la riga non c'era quel modello non si vedeva da nessuna parte — di qui "la selezione del
 * modello non ha effetto". Sparisce solo per le righe salvate prima della 1.29.0 senza modelli.
 */
@Composable
fun RunSteps(run: Run) {
  val hasDetails = run.tools.isNotEmpty() || run.error != null || modelsLine(run) != null
  if (!hasDetails) return
  var details by rememberSaveable(run.id) { mutableStateOf(false) }
  Column(Modifier.padding(bottom = 8.dp)) {
    Row(
      modifier = Modifier
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), ContinuousCornerShape(FluidRadius.Control))
        .fluidPressable(onClick = { details = !details }, pressedScale = 1f, role = Role.Button, haptic = null)
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        text = toolSummary(run),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Spacer(Modifier.width(6.dp))
      Icon(
        imageVector = if (details) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
        contentDescription = if (details) "Chiudi i passi" else "Mostra i passi",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
      )
    }
    if (details) {
      Spacer(Modifier.height(8.dp))
      run.error?.let { Text("errore: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
      modelsLine(run)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      providerChain(run)?.let { Text("servizi: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      if (run.groups.isNotEmpty()) Text("gruppi: ${run.groups.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      run.tools.forEach { trace ->
        Spacer(Modifier.height(4.dp))
        Text(
          text = "${trace.app?.let { "$it · " } ?: ""}${trace.name} ${trace.args} · ${trace.millis} ms · ${if (trace.ok) "ok" else "errore"}",
          style = MaterialTheme.typography.labelSmall,
          color = if (trace.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        if (trace.preview.isNotBlank()) Text(trace.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

/**
 * La riga chiusa, in italiano corrente.
 *
 * Quando il lavoro e' andato tutto a una sola app lo dice per nome — "Ho chiesto a Convert to it!"
 * e' informazione, "2 strumenti" e' contabilita'. In coda, chi ha risposto per ultimo
 * ("· profondo gemini-2.5-pro"): e' la cosa che si vuole sapere senza aprire la riga.
 */
private fun toolSummary(run: Run): String {
  val work = when {
    run.tools.isEmpty() -> run.error?.let { "Non ci sono riuscita" } ?: "Ho risposto da sola"
    else -> {
      val apps = run.tools.mapNotNull { it.app }.distinct()
      when {
        apps.size == 1 && apps.first().isNotBlank() -> "Ho chiesto a ${apps.first()}"
        run.tools.size == 1 -> "Ho usato ${run.tools.first().name.replace('_', ' ')}"
        else -> "Ho usato ${run.tools.size} strumenti"
      }
    }
  }
  val answerer = if (run.error == null) answeredBy(run) else null
  return if (answerer != null) "$work · $answerer" else work
}

/** L'id di un modello senza il fornitore davanti e senza il ":free" in coda: "minimax/minimax-m2.5" diventa "minimax-m2.5". */
private fun shortModel(id: String): String = id.substringAfterLast('/').removeSuffix(":free")

/** I livelli come li chiama la riga: minuscoli, perche' stanno in mezzo a una frase. */
private fun tierWord(tier: ModelTier): String = when (tier) {
  ModelTier.ROUTER -> "router"
  ModelTier.CHAT -> "chat"
  ModelTier.DEEP -> "profondo"
}

/**
 * "router m · chat m · profondo m", nell'ordine in cui hanno risposto.
 *
 * Da [Run.models] quando c'e' (1.29.0): se ci ha messo mano piu' di un servizio ogni voce porta
 * anche il suo ("chat Gemini gemini-2.5-flash · chat OpenRouter minimax-m2.5"), perche' "chat"
 * da solo non direbbe di chi. Per le righe vecchie, i tre campi per livello. Null se non c'e' nulla.
 */
private fun modelsLine(run: Run): String? {
  if (run.models.isNotEmpty()) {
    val several = run.models.map { it.provider }.distinct().size > 1
    return run.models.joinToString(" · ") { use ->
      listOfNotNull(tierWord(use.tier), use.provider.label.takeIf { several }, shortModel(use.model)).joinToString(" ")
    }
  }
  val parts = listOfNotNull(
    run.routerModel?.let { "router ${shortModel(it)}" },
    run.chatModel?.let { "chat ${shortModel(it)}" },
    run.deepModel?.takeIf { it != run.chatModel }?.let { "profondo ${shortModel(it)}" },
  )
  return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** "Gemini → OpenRouter" quando piu' di un servizio ha risposto in questo scambio; null altrimenti. */
private fun providerChain(run: Run): String? {
  val chain = run.models.fold(mutableListOf<ProviderId>()) { acc, use -> if (acc.lastOrNull() != use.provider) acc += use.provider; acc }
  return chain.takeIf { it.size > 1 }?.joinToString(" → ") { it.label }
}

/**
 * Chi ha dato la risposta finale, "profondo gemini-2.5-pro": l'ultimo di [Run.models], o per le
 * righe vecchie il modello del livello piu' alto raggiunto. Null se non si sa.
 */
private fun answeredBy(run: Run): String? {
  run.models.lastOrNull()?.let { return "${tierWord(it.tier)} ${shortModel(it.model)}" }
  val tier = run.tierReached ?: return null
  val model = when (tier) {
    ModelTier.DEEP -> run.deepModel ?: run.chatModel
    ModelTier.CHAT -> run.chatModel
    ModelTier.ROUTER -> run.routerModel
  } ?: return null
  return "${tierWord(tier)} ${shortModel(model)}"
}

/**
 * Il servizio nella telemetria: "Gemini" quando e' lo stesso dall'inizio alla fine, "risposto da
 * OpenRouter" quando la riserva automatica ha cambiato strada, col motivo se lo sappiamo. Un
 * cambio per limite senza attesa non lascia traccia nella riga (la riserva passa subito), quindi
 * "al limite" compare solo quando si e' davvero aspettato un `retry-after`.
 */
private fun providerLine(run: Run): String? {
  val final = run.provider ?: return null
  val first = run.models.firstOrNull()?.provider
  if (first == null || first == final) return final.label
  val reason = if (run.waitedSeconds > 0) " (${first.label} al limite)" else ""
  return "risposto da ${final.label}$reason"
}

@Composable
fun ConfirmationRow(pending: PendingConfirmation, onResolve: (Long, Boolean) -> Unit) {
  Spacer(Modifier.height(8.dp))
  Text(pending.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
  pending.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  Spacer(Modifier.height(6.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FluidButton(text = "Conferma", onClick = { onResolve(pending.id, true) }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Small)
    FluidButton(text = "Annulla", onClick = { onResolve(pending.id, false) }, style = FluidButtonStyle.Plain, size = FluidButtonSize.Small)
  }
}

/** La telemetria di uno scambio in una riga: strumenti, servizio (e se e' cambiato, da chi a chi), tempo, token, costo. */
fun telemetry(run: Run): String = buildList {
  add(if (run.tools.isEmpty()) "nessuno strumento" else "${run.tools.size} ${if (run.tools.size == 1) "strumento" else "strumenti"}")
  providerLine(run)?.let { add(it) }
  run.durationMillis?.let { add("${it / 1000} s") }
  run.totalTokens?.let { add(if (it >= 1000) String.format(Locale.getDefault(), "%.1fk token", it / 1000.0) else "$it token") }
  run.costUsd?.takeIf { it > 0.0 }?.let { add(String.format(Locale.getDefault(), "%.4f $", it)) }
  if (run.outcome != "ok") add(run.outcome)
}.joinToString(" · ")
