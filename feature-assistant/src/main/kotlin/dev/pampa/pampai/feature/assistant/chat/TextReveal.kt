package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy

/** Quanto ci mette una parola nuova a sfumare dentro. */
const val RevealMillis = 180

/**
 * Quanto ci mette una parola riscritta: il recognizer che cambia idea su cio' che ha gia' detto,
 * o il modello che chiude un `**`. Piu' corta, perche' al posto della parola vecchia ce n'e' gia'
 * una nuova da leggere.
 */
const val RewriteMillis = 120

/**
 * Sotto questa maschera non si disegna: l'ultimo fotogramma della sfumatura vale zero e non
 * merita un `drawPath`, e un valore mai esattamente zero non deve tenere in vita il layer.
 */
private const val MaskFloor = 0.004f

private const val NanosPerMilli = 1_000_000L

/** Vicina ai default di `markdownPadding()`: a fine risposta il passaggio al Markdown salta poco. */
private val BlockSpacing = 8.dp

/**
 * Testo che *arriva*: le parole nuove sfumano dentro in [revealMillis], quelle gia' viste restano
 * ferme. Un unico `Text`, un solo layout; la sfumatura e' una maschera `DstOut` sui glifi delle
 * parole nuove, in un layer fuori schermo acceso solo mentre c'e' qualcosa da rivelare.
 *
 * Solo alfa, niente salita di qualche dp: un `Text` gia' impaginato non puo' spostare una parola,
 * ed e' comunque l'effetto di ChatGPT. Il primo testo mai visto non si anima (la riapertura di
 * una chat, il cambio di chiave dell'item alla prima risposta), a meno che [animateFirst] non
 * dica che il testo sta proprio nascendo. Con il movimento ridotto dal sistema le durate sono
 * zero e tutto compare com'e'.
 *
 * L'orologio e' un `LaunchedEffect` che gira solo finche' c'e' qualcosa da rivelare e scrive
 * `now` in uno stato letto **solo nel disegno**: avanzare l'orologio invalida il disegno di questo
 * `Text`, non ricompone niente.
 */
@Composable
fun RevealingText(
  text: AnnotatedString,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
  onTextLayout: ((TextLayoutResult) -> Unit)? = null,
  revealMillis: Int = RevealMillis,
  rewriteMillis: Int = RewriteMillis,
  softWrap: Boolean = true,
  animateFirst: Boolean = false,
) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val tracker = remember { RevealTracker() }
  val clock = remember { RevealClock() }
  val layout = remember { LayoutHolder() }
  // In composizione, prima del layout e del disegno del fotogramma: quando il testo nuovo viene
  // disegnato la prima volta il tracker sa gia' quali parole nascondere. In un effetto sarebbe un
  // fotogramma tardi, e quel fotogramma mostrerebbe le parole nuove intere prima di sfumarle.
  remember(text, reducedMotion) {
    tracker.update(
      newText = text.text,
      revealNanos = if (reducedMotion) 0L else revealMillis * NanosPerMilli,
      rewriteNanos = if (reducedMotion) 0L else rewriteMillis * NanosPerMilli,
      animateFirst = animateFirst,
    )
    clock.active.value = tracker.hasWork
  }
  LaunchedEffect(text, reducedMotion) {
    if (!tracker.hasWork) return@LaunchedEffect
    while (true) {
      val now = withFrameNanos { it }
      tracker.stamp(now)
      clock.now.longValue = now
      if (tracker.settle(now)) {
        clock.active.value = false
        break
      }
    }
  }
  Text(
    text = text,
    style = style,
    color = color,
    maxLines = maxLines,
    overflow = overflow,
    softWrap = softWrap,
    onTextLayout = { result ->
      layout.result = result
      onTextLayout?.invoke(result)
    },
    modifier = modifier
      .graphicsLayer {
        // Il layer fuori schermo serve alla maschera: senza, il `DstOut` buca il fondale invece
        // del testo. Acceso solo mentre c'e' qualcosa da rivelare: un layer alto quanto una
        // risposta lunga e' una texture intera, e da fermo non serve a niente.
        compositingStrategy = if (clock.active.value) CompositingStrategy.Offscreen else CompositingStrategy.Auto
      }
      .drawWithContent {
        drawContent()
        if (!clock.active.value) return@drawWithContent
        val result = layout.result ?: return@drawWithContent
        val now = clock.now.longValue
        for (token in tracker.revealing) {
          val mask = token.mask(now)
          if (mask > MaskFloor) drawPath(token.path(result), Color.Black, alpha = mask, blendMode = BlendMode.DstOut)
        }
      },
  )
}

/**
 * Piu' paragrafi (blocchi) come [RevealingText] indipendenti: in streaming cambia solo l'ultimo,
 * quindi solo lui paga il layer fuori schermo. Un layer alto quanto una risposta lunga sfonda il
 * tetto delle texture.
 *
 * I blocchi che c'erano al primo passaggio sono gia' letti; quelli che si aprono dopo, mentre il
 * testo arriva, si rivelano parola per parola. [animateFirst] dice che anche i primi stanno
 * nascendo adesso (la risposta e' appena partita): allora si rivelano tutti.
 */
@Composable
internal fun RevealingParagraphs(blocks: List<StreamBlock>, modifier: Modifier = Modifier, animateFirst: Boolean = false) {
  val scheme = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val firstCount = remember { blocks.size }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BlockSpacing)) {
    blocks.forEachIndexed { index, block ->
      key(index) {
        val fresh = animateFirst || index >= firstCount
        when (block.kind) {
          StreamBlockKind.Body -> RevealingText(block.text, typography.bodyLarge, scheme.onSurface, animateFirst = fresh)
          StreamBlockKind.Heading1 -> RevealingText(block.text, chatHeading(1, typography), scheme.onSurface, animateFirst = fresh)
          StreamBlockKind.Heading2 -> RevealingText(block.text, chatHeading(3, typography), scheme.onSurface, animateFirst = fresh)
          StreamBlockKind.Quote -> {
            val bar = scheme.outlineVariant
            RevealingText(
              text = block.text,
              style = typography.bodyLarge,
              color = scheme.onSurfaceVariant,
              animateFirst = fresh,
              modifier = Modifier
                .padding(start = 12.dp)
                .drawBehind {
                  drawRoundRect(bar, Offset(-12.dp.toPx(), 0f), Size(3.dp.toPx(), size.height), CornerRadius(1.5.dp.toPx()))
                },
            )
          }
          StreamBlockKind.Code -> StreamingCodeBlock(block, fresh)
        }
      }
    }
  }
}

/**
 * La stessa scatola di [AriaCodeBlock], intestazione compresa (senza il tasto copia, che arriva a
 * fine risposta col Markdown): cosi' il passaggio non sposta niente.
 */
@Composable
private fun StreamingCodeBlock(block: StreamBlock, fresh: Boolean) {
  val scheme = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  Column(
    Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .background(scheme.surfaceVariant.copy(alpha = 0.6f), ContinuousCornerShape(FluidRadius.Control)),
  ) {
    Box(Modifier.padding(start = 14.dp, top = 6.dp).height(26.dp), contentAlignment = Alignment.CenterStart) {
      Text(block.language ?: "codice", style = typography.labelSmall, color = scheme.onSurfaceVariant)
    }
    RevealingText(
      text = block.text,
      style = typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      color = scheme.onSurface,
      softWrap = false,
      animateFirst = fresh,
      modifier = Modifier
        .horizontalScroll(rememberScrollState())
        .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
    )
  }
}

/** Nato, ma non ancora timbrato dal primo fotogramma. */
private const val Pending = Long.MAX_VALUE

/** Gia' dentro: non si disegna e non si anima. */
private const val Committed = Long.MIN_VALUE

/**
 * Un pezzo di testo che si rivela da solo: `[start, end)` sul testo del layout (che coincide con
 * `AnnotatedString.text`), quando e' nato, quanto ci mette.
 */
internal class RevealToken(val start: Int, val end: Int, var born: Long, val durationNanos: Long) {
  private var pathFor: TextLayoutResult? = null
  private var cached: Path? = null

  /**
   * 1 = nascosto, 0 = dentro. In attesa del primo fotogramma e' nascosto: il testo nuovo viene
   * disegnato una volta *prima* che l'orologio lo timbri, e quel fotogramma non deve mostrarlo.
   */
  fun mask(now: Long): Float = when {
    born == Committed -> 0f
    born == Pending -> 1f
    durationNanos <= 0L -> 0f
    else -> 1f - FluidMotion.EaseOut.transform(((now - born).toFloat() / durationNanos).coerceIn(0f, 1f))
  }

  fun finished(now: Long): Boolean = born != Pending && (durationNanos <= 0L || now - born >= durationNanos)

  /** Il contorno dei glifi: una volta per layout (che cambia col testo), non a ogni fotogramma. */
  fun path(layout: TextLayoutResult): Path {
    if (pathFor !== layout) {
      pathFor = layout
      cached = layout.getPathForRange(start, end)
    }
    return cached!!
  }
}

/**
 * Sa quali parole sono nuove. I token sono i run massimali di non-spazio; sul testo nuovo si tiene
 * il prefisso comune col precedente, e quel che viene dopo nasce in attesa. Se il nuovo testo
 * diverge *prima* della fine del vecchio e' una riscrittura: la coda dalla divergenza rinasce, col
 * tempo della riscrittura. Una parola tagliata dal prefisso (il modello che manda "Prima" e poi
 * "vera") tiene la testa com'era e fa nascere solo la coda: cosi' i pezzi di parola non
 * lampeggiano a ogni chunk.
 */
internal class RevealTracker {
  private var text = ""
  private var seen = false
  private var tokens: List<RevealToken> = emptyList()

  /** I token non ancora dentro: in attesa del fotogramma o a meta' sfumatura. */
  var revealing: List<RevealToken> = emptyList()
    private set

  val hasWork: Boolean get() = revealing.isNotEmpty()

  fun update(newText: String, revealNanos: Long, rewriteNanos: Long, animateFirst: Boolean) {
    if (seen && newText == text) return
    val old = text
    val oldTokens = tokens
    text = newText
    val fresh = ArrayList<RevealToken>(oldTokens.size + 8)
    if (!seen) {
      seen = true
      val born = if (animateFirst && revealNanos > 0L) Pending else Committed
      eachRun(newText, 0) { start, end -> fresh += RevealToken(start, end, born, revealNanos) }
    } else {
      val common = commonPrefix(old, newText)
      val rewrite = common < old.length
      val duration = if (rewrite) rewriteNanos else revealNanos
      val born = if (duration > 0L) Pending else Committed
      for (token in oldTokens) {
        if (token.end <= common) {
          fresh += token
          continue
        }
        if (token.start < common) fresh += RevealToken(token.start, common, token.born, token.durationNanos)
        break
      }
      eachRun(newText, common) { start, end -> fresh += RevealToken(start, end, born, duration) }
    }
    tokens = fresh
    revealing = fresh.filter { it.born != Committed }
  }

  /** Il primo fotogramma dopo la nascita: da qui si conta. */
  fun stamp(now: Long) {
    for (token in revealing) if (token.born == Pending) token.born = now
  }

  /** Mette a posto i token finiti; vero quando non c'e' piu' niente da rivelare. */
  fun settle(now: Long): Boolean {
    if (revealing.any { it.finished(now) }) {
      revealing = revealing.filter { token ->
        val done = token.finished(now)
        if (done) token.born = Committed
        !done
      }
    }
    return revealing.isEmpty()
  }
}

private inline fun eachRun(text: String, from: Int, block: (Int, Int) -> Unit) {
  var i = from
  val n = text.length
  while (i < n) {
    while (i < n && text[i].isWhitespace()) i++
    if (i >= n) break
    val start = i
    while (i < n && !text[i].isWhitespace()) i++
    block(start, i)
  }
}

private fun commonPrefix(a: String, b: String): Int {
  val n = minOf(a.length, b.length)
  var i = 0
  while (i < n && a[i] == b[i]) i++
  return i
}

/** `now` e' letto solo nel disegno, `active` dal layer e dal disegno: nessuno dei due ricompone. */
private class RevealClock {
  val now = mutableLongStateOf(0L)
  val active = mutableStateOf(false)
}

/** Il layout arriva in fase di layout, il disegno lo legge dopo: un contenitore, non uno stato. */
private class LayoutHolder {
  var result: TextLayoutResult? = null
}
