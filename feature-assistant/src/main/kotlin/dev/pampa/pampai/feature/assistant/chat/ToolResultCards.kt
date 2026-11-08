package dev.pampa.pampai.feature.assistant.chat

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ai.tools.ToolOutcome
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.pampa.pampai.core.assistant.receipts.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReceiptEntryPoint { fun receiptActions(): ReceiptActions }

internal fun receiptOutcomeLabel(outcome: ToolOutcome): String = when (outcome) {
  ToolOutcome.ACCEPTED -> "Richiesta inviata"
  ToolOutcome.COMPLETED -> "Verificato"
  ToolOutcome.PARTIAL -> "Verificato in parte"
  ToolOutcome.UNCERTAIN -> "Esito da verificare - nessuna ripetizione automatica"
  ToolOutcome.ERROR -> "Errore"
}


internal fun receiptTitle(receipt: ToolReceipt): String {
  val data = receipt.result.data
  val playlist = if (receipt.result.entities.any { it.source == "spotify" && it.type == "playlist" })
    (data?.get("playlist") as? JsonObject)?.get("name") as? JsonPrimitive else null
  return playlist?.contentOrNull?.takeIf { it.isNotBlank() }
    ?: (data?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    ?: when {
      receipt.toolName == "timer_crea" -> "Timer"
      receipt.toolName == "sveglia_crea" -> "Sveglia"
      receipt.result.entities.any { it.type == "playlist" } -> "Playlist"
      else -> "Risultato dell'operazione"
    }
}

/** Source readback entries only: no MediaBrowser resolution of Spotify IDs, no playback claim. */
internal fun receiptTrackLabels(receipt: ToolReceipt): List<String> {
  val spotify = receipt.result.entities.any { it.source == "spotify" && it.type == "playlist" }
  return (receipt.result.data?.get("items") as? JsonArray).orEmpty().take(5).mapNotNull { value ->
    val entry = value as? JsonObject
    if (spotify) {
      val track = ((entry?.get("item") as? JsonObject)?.get("track") as? JsonObject)
      val name = (track?.get("name") as? JsonPrimitive)?.contentOrNull
      when {
        name.isNullOrBlank() -> "Brano non disponibile"
        (track?.get("is_playable") as? JsonPrimitive)?.booleanOrNull == false -> "$name (non disponibile)"
        else -> name
      }
    } else (entry?.get("title") as? JsonPrimitive)?.contentOrNull
  }
}

internal fun receiptCanOpen(receipt: ToolReceipt): Boolean =
  receipt.result.sources.any { it.id in setOf("android.clock", "android.calendar", "fluidify") } ||
    receipt.result.entities.any { it.source in setOf("android.calendar", "fluidify") || (it.source == "spotify" && it.type == "playlist") }

/** Same renderer in chat and overlay. All fields come from typed source metadata. */
@Composable
internal fun ToolResultCards(receipts: List<ToolReceipt>) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  receipts.forEach { receipt ->
    key(receipt.operationId) {
      var opening by remember { mutableStateOf(false) }
      var notice by remember { mutableStateOf<String?>(null) }
      val data = receipt.result.data
      FluidCard(modifier = Modifier.padding(vertical = 6.dp), glass = true) {
        Text(receiptOutcomeLabel(receipt.result.outcome), style = MaterialTheme.typography.labelLarge)
        Text(receiptTitle(receipt), style = MaterialTheme.typography.titleMedium)
        (data?.get("requestedDurationSeconds") as? JsonPrimitive)?.intOrNull?.let {
          Text("Durata richiesta: $it secondi - stato in Orologio", style = MaterialTheme.typography.bodyMedium)
        }
        receiptTrackLabels(receipt).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        receipt.result.error?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
        receipt.result.freshness?.observedAtMillis?.let {
          val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
          Text("Verificato: ${date.format(java.util.Date(it))}", style = MaterialTheme.typography.labelSmall)
        }
        if (receiptCanOpen(receipt)) {
          FluidChip(label = if (opening) "Rilettura..." else "Apri e verifica", selected = false, onClick = {
            if (!opening) scope.launch {
              opening = true
              try {
                val actions = EntryPointAccessors.fromApplication(context.applicationContext, ReceiptEntryPoint::class.java).receiptActions()
                notice = actions.open(receipt)
              } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = "Fonte non disponibile" }
              finally { opening = false }
            }
          })
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      }
    }
  }
}
