package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.pampai.feature.assistant.settings.label

/**
 * Quanti caratteri stanno nel chip del modello prima che l'ellissi mangi il livello di impegno.
 *
 * Il chip ([ModelPill] in `ChatComposer.kt`) non ha una larghezza massima: sta in una riga con il
 * "+" a sinistra e il tasto d'invio a destra, e con `labelMedium` sui ~411 dp del telefono gli
 * restano circa 220 dp di testo, cioe' una trentina di caratteri. Oltre, la parte tagliata sarebbe
 * proprio "\u00b7 Auto", che e' l'unica informazione che il menu non ripete in grande.
 */
private const val CHIP_MAX_CHARS = 30

/** Il servizio che risponde adesso: il primo dell'ordine con una chiave verificata. */
private fun activeProvider(state: ChatUiState): ProviderId? =
  state.settings.chatOrder.firstOrNull { state.keys[it]?.verified == true } ?: state.settings.chatOrder.firstOrNull()

/**
 * L'etichetta del chip: chi risponde adesso e quanto ci pensa.
 *
 * Quando il profondo e' un modello diverso dalla chat li mostra entrambi ("Flash \u00b7 Pro \u00b7 Auto"):
 * e' il profondo a rispondere alle domande difficili, e finche' il chip diceva solo la chat
 * "la selezione del modello non aveva effetto" \u2014 si vedeva un modello e ne rispondeva un altro.
 * Se i due nomi non stanno nel chip resta la sola chat, e il profondo si legge nel menu.
 */
internal fun modelLabel(state: ChatUiState, thinkingAuto: Boolean): String {
  val provider = activeProvider(state) ?: return "Nessun modello"
  val chatId = state.settings.chatModel(provider)
  val deepId = state.settings.deepModel(provider)?.takeIf { it != chatId }
  val chat = chatId?.let { shortModelName(state, provider, it) } ?: provider.label
  val effort = if (thinkingAuto) "Auto" else state.settings.thinking.label()
  if (deepId != null) {
    val (chatShort, deepShort) = compactPair(chat, shortModelName(state, provider, deepId))
    val both = "$chatShort \u00b7 $deepShort \u00b7 $effort"
    if (both.length <= CHIP_MAX_CHARS) return both
  }
  // Resta la sola chat, e se non ci sta nemmeno lei si taglia con l'ellissi: un taglio netto
  // ("gemini-2.5-flash-pre") si legge come il nome vero di un modello, e non come un nome accorciato.
  val room = CHIP_MAX_CHARS - effort.length - 3
  val short = if (chat.length <= room) chat else chat.take(room - 1).trimEnd() + "\u2026"
  return "$short \u00b7 $effort"
}

/**
 * Il nome corto di un modello per il chip: quello del catalogo senza il fornitore davanti
 * ("Google: Gemini 2.5 Flash" e' come lo chiama OpenRouter) e senza il "(free)" in coda; senza
 * catalogo, l'ultimo pezzo dell'id ("thinkingmachines/inkling:free" diventa "inkling").
 */
private fun shortModelName(state: ChatUiState, provider: ProviderId, id: String): String {
  val display = state.catalogues[provider]?.chat(id)?.displayName
  val name = display?.substringAfter(": ") ?: id.substringAfterLast('/').removeSuffix(":free")
  return name.replace(Regex("\\s*\\((free|gratis)\\)\\s*$", RegexOption.IGNORE_CASE), "").trim()
}

/**
 * Due nomi di modello senza le parole iniziali che hanno in comune: "Gemini 2.5 Flash" e
 * "Gemini 2.5 Pro" diventano "Flash" e "Pro". Si toglie solo se a entrambi resta qualcosa.
 */
private fun compactPair(chat: String, deep: String): Pair<String, String> {
  val a = chat.split(' ')
  val b = deep.split(' ')
  val common = a.zip(b).takeWhile { (x, y) -> x.equals(y, ignoreCase = true) }.size
  if (common == 0 || common >= a.size || common >= b.size) return chat to deep
  return a.drop(common).joinToString(" ") to b.drop(common).joinToString(" ")
}

/**
 * Come si chiama un modello nel menu: il nome del catalogo, o l'id se il catalogo non c'e'.
 *
 * Senza il fornitore davanti ("Thinking Machines: Inkling (free)" su OpenRouter): con "profondo: "
 * a sinistra non ci stava nella riga, e il pezzo tagliato era il nome. Il "(free)" resta, perche'
 * su OpenRouter vuol dire tetto giornaliero, e conta.
 */
private fun menuModelName(state: ChatUiState, provider: ProviderId, id: String): String =
  state.catalogues[provider]?.chat(id)?.displayName?.substringAfter(": ") ?: id

/** Le quattro posizioni del ragionamento nel pop-up: Auto piu' i tre livelli dell'engine. */
internal enum class Effort(val label: String) { AUTO("Auto"), LOW("Basso"), MEDIUM("Medio"), HIGH("Alto") }

/**
 * Il contenuto del pop-up del modello: una riga per servizio (chi risponde adesso ha la spunta,
 * chi non ha la chiave e' spento) e il controllo dell'impegno.
 *
 * Ogni servizio mostra **due** modelli, la chat e il profondo: le domande difficili le prende il
 * profondo, e un menu che nominasse solo la chat farebbe credere che risponda sempre lei. In
 * fondo una nota dice dove si cambiano e, se la riserva automatica e' accesa, che un servizio al
 * limite passa la mano al successivo: e' l'altro motivo per cui "risponde un modello diverso".
 */
@Composable
internal fun ModelMenu(
  state: ChatUiState,
  thinkingAuto: Boolean,
  onProvider: (ProviderId) -> Unit,
  onEffort: (Effort) -> Unit,
) {
  val first = state.settings.chatOrder.firstOrNull { state.keys[it]?.verified == true }
  Column(Modifier.width(300.dp).padding(vertical = 4.dp)) {
    Text("Chi risponde", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    state.settings.chatOrder.forEach { provider ->
      val verified = state.keys[provider]?.verified == true
      val chatId = state.settings.chatModel(provider)
      val chat = chatId?.let { menuModelName(state, provider, it) } ?: "modello predefinito"
      // Profondo nullo, o uguale alla chat: e' la chat a fare anche quello, e va detto cosi'.
      val deep = state.settings.deepModel(provider)?.takeIf { it != chatId }?.let { menuModelName(state, provider, it) } ?: "come la chat"
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .then(if (verified) Modifier.fluidPressable(onClick = { onProvider(provider) }, role = Role.Button, pressedScale = 1f, haptic = null) else Modifier)
          .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(10.dp).background(providerColour(provider), FluidCapsuleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(provider.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (verified) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
          if (verified) {
            Text("chat: $chat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("profondo: $deep", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
          } else {
            Text("Serve una chiave", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        if (provider == first) Icon(Icons.Rounded.Check, contentDescription = "In uso", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
      }
    }
    MenuDivider()
    Text("Impegno", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    val selected = if (thinkingAuto) Effort.AUTO else when (state.settings.thinking) {
      ThinkingLevel.LOW -> Effort.LOW
      ThinkingLevel.MEDIUM -> Effort.MEDIUM
      ThinkingLevel.HIGH -> Effort.HIGH
    }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
      FluidSegmentedControl(options = Effort.entries, selected = selected, onSelect = onEffort, label = { it.label })
    }
    Text(
      if (thinkingAuto) "Lo decide Aria: alto sulle domande profonde, basso su quelle secche." else "Fisso: vale per tutte le domande finche' non lo cambi.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    MenuDivider()
    Text(
      buildString {
        append("Le domande difficili vanno al profondo. Cambi i modelli in Impostazioni › Modelli.")
        if (state.failoverEnabled) append("\nRiserva automatica accesa: se un servizio e' al limite, Aria passa al successivo.")
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
  }
}

/** Un colore per servizio, per riconoscerli a colpo d'occhio nel pop-up. */
private fun providerColour(provider: ProviderId): Color = when (provider) {
  ProviderId.GROQ -> Color(0xFFF55036)
  ProviderId.GEMINI -> Color(0xFF4285F4)
  ProviderId.OPENROUTER -> Color(0xFF6E56CF)
}

/** Una riga di menu: icona, testo, dettaglio, e a destra una spunta o una freccia. */
@Composable
internal fun MenuRow(
  icon: ImageVector,
  label: String,
  detail: String? = null,
  trailing: ImageVector? = null,
  checked: Boolean? = null,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .fluidPressable(onClick = onClick, role = Role.Button, pressedScale = 1f, haptic = null)
      .padding(horizontal = 16.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    when {
      checked == true -> Icon(Icons.Rounded.Check, contentDescription = "Attivo", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
      trailing != null -> Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
internal fun MenuDivider() {
  Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
}
