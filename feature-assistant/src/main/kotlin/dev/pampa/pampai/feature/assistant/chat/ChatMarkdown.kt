package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Il Markdown completo delle risposte, con i colori e la tipografia del tema.
 *
 * Chiama l'overload *core* della libreria, non quello del modulo m3. Quello crea lo stato con
 * `retainState = false`: a ogni testo nuovo torna `Loading` finche' l'analisi (che e' sospesa)
 * non ha finito, e per quel fotogramma lo slot e' vuoto. In streaming il testo cambia quindici
 * volte al secondo, ed era il lampeggio che si vedeva mentre Aria risponde. Con `retainState` il
 * `Success` precedente resta a schermo finche' non c'e' quello nuovo; con `immediate` la prima
 * analisi e' sincrona, cosi' nemmeno all'apertura di una chat c'e' un fotogramma vuoto.
 *
 * I titoli sono quelli di [chatHeading], gli stessi del testo in streaming: a fine risposta il
 * passaggio da [RevealingParagraphs] a questo non cambia le misure. I default del modulo m3
 * (`displayLarge` per `#`, `displayMedium` per `##`) sono titoli da pagina, non da conversazione:
 * un "## Estate" a 45 sp in mezzo a una risposta e' un cartello. Il codice, in linea e a blocco,
 * usa la stessa misura di [AriaCodeBlock].
 */
@Composable
fun MarkdownBody(markdown: String) {
  val components = remember {
    markdownComponents(
      codeFence = { AriaCodeBlock(it) },
      codeBlock = { AriaCodeBlock(it) },
      table = { AriaTable(it) },
    )
  }
  val typography = MaterialTheme.typography
  val code = typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
  Markdown(
    content = markdown,
    colors = markdownColor(),
    typography = markdownTypography(
      h1 = chatHeading(1, typography),
      h2 = chatHeading(2, typography),
      h3 = chatHeading(3, typography),
      h4 = chatHeading(4, typography),
      h5 = chatHeading(5, typography),
      h6 = chatHeading(6, typography),
      code = code,
      inlineCode = code,
    ),
    components = components,
    retainState = true,
    immediate = true,
  )
}

/**
 * Lo stile di un titolo `#…` in chat: `#` e `##` in `titleMedium`, dal `###` in giu' in
 * `titleSmall`, tutti SemiBold. Due misure sole: in una risposta un titolo separa i paragrafi,
 * non costruisce una gerarchia a sei livelli.
 */
internal fun chatHeading(level: Int, typography: Typography): TextStyle =
  (if (level <= 2) typography.titleMedium else typography.titleSmall).copy(fontWeight = FontWeight.SemiBold)
