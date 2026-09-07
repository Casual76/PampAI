package dev.pampa.pampai.feature.assistant.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownTable
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import kotlinx.coroutines.delay

/**
 * Un blocco di codice come lo si vuole in una chat: lingua in testa, tasto copia, e il testo che
 * **scorre** invece di essere tagliato.
 *
 * Il blocco della libreria manda a capo le righe lunghe o le taglia al bordo, e una riga di codice
 * tagliata a meta' non e' codice: e' un'immagine di codice. Qui la riga resta intera
 * (`softWrap = false`) dentro uno scorrimento orizzontale suo, che e' come si legge il codice su
 * uno schermo stretto ovunque.
 */
@Composable
fun AriaCodeBlock(model: MarkdownComponentModel) {
  val raw = remember(model) {
    runCatching { model.content.substring(model.node.startOffset, model.node.endOffset) }.getOrDefault("")
  }
  val (language, body) = remember(raw) { splitFence(raw) }
  if (body.isBlank()) return

  val context = LocalContext.current
  var copied by remember(body) { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1_600)
      copied = false
    }
  }

  Column(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), ContinuousCornerShape(FluidRadius.Control)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 14.dp, end = 6.dp, top = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = language ?: "codice",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      Row(
        modifier = Modifier
          .fluidPressable(
            onClick = {
              context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("codice", body))
              copied = true
            },
            role = Role.Button,
            haptic = null,
          )
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
          contentDescription = "Copia il codice",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(14.dp),
        )
        Text(
          text = if (copied) "copiato" else "copia",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Text(
      text = body,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
      color = MaterialTheme.colorScheme.onSurface,
      softWrap = false,
      modifier = Modifier
        .horizontalScroll(rememberScrollState())
        .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
    )
  }
}

/**
 * Separa la lingua dal corpo.
 *
 * Il nodo arriva col testo grezzo come l'ha scritto il modello: i tre apici, l'eventuale nome del
 * linguaggio, e per i blocchi indentati nessun apice ma quattro spazi davanti a ogni riga.
 */
private fun splitFence(raw: String): Pair<String?, String> {
  val lines = raw.trimEnd().lines()
  if (lines.isEmpty()) return null to ""
  val first = lines.first().trimStart()
  if (!first.startsWith("```") && !first.startsWith("~~~")) {
    // Blocco indentato: via i quattro spazi (o il tab) che lo rendono tale.
    val body = lines.joinToString("\n") { it.removePrefix("    ").removePrefix("\t") }
    return null to body.trim('\n')
  }
  val fence = first.take(3)
  val language = first.removePrefix(fence).trim().substringBefore(' ').takeIf { it.isNotBlank() }
  val body = lines.drop(1).dropLastWhile { it.trimStart().startsWith(fence) || it.isBlank() }
  return language to body.joinToString("\n")
}

/**
 * Una tabella che scorre invece di essere tagliata.
 *
 * Le colonne della libreria si dividono la larghezza dello schermo: con quattro o cinque colonne
 * ognuna resta larga un pollice e il contenuto sparisce nei puntini. Qui la tabella prende la
 * larghezza che le serve — 128 dp a colonna, mai meno dello schermo — e lo schermo ci scorre sopra.
 */
@Composable
fun AriaTable(model: MarkdownComponentModel) {
  val raw = remember(model) {
    runCatching { model.content.substring(model.node.startOffset, model.node.endOffset) }.getOrDefault("")
  }
  val columns = remember(raw) {
    val header = raw.lineSequence().firstOrNull { it.contains('|') } ?: return@remember 1
    header.trim().trim('|').split('|').size.coerceAtLeast(1)
  }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val wanted = maxOf(maxWidth, (columns * 128).dp)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
      Box(Modifier.width(wanted)) {
        MarkdownTable(content = model.content, node = model.node, style = model.typography.text)
      }
    }
  }
}
