package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/*
 * La lista della chat e il fondo: dove si apre una conversazione, quando la si segue mentre la
 * risposta cresce, e quando la si lascia stare. Tre regole, quelle delle chat che si usano:
 *
 * 1. Una conversazione si apre gia' in fondo, senza scorrere davanti a tutti i messaggi. Tornando
 *    dalle impostazioni si ritrova il punto in cui la si era lasciata.
 * 2. Mentre la risposta si forma la lista la segue solo se si e' in fondo (a meno di [FollowSlack]).
 *    Chi e' risalito a rileggere non viene riportato giu': si riaggancia quando torna lui in fondo.
 * 3. Una domanda appena mandata porta in fondo, con l'animazione, anche da risaliti.
 *
 * Il fondo e' misurato contro l'area sopra il composer: il padding in fondo alla lista e' l'altezza
 * vera del composer piu' tastiera e barra di navigazione (vedi [ChatRoute]).
 */

/** Quanto sopra il fondo conta ancora come "in fondo": un dito che sfiora la lista non stacca il seguito. */
private val FollowSlack = 48.dp

/** Il tetto dell'attesa della prima misura, all'apertura: poi la lista si mostra comunque. */
private const val FirstLayoutTimeoutMillis = 600L

/**
 * La lista di una conversazione e cio' che sa del fondo.
 *
 * [positioned] e' falso finche' la lista appena aperta non e' stata portata in fondo: fino ad
 * allora non si disegna (sono uno o due fotogrammi), cosi' non si vede la cima della conversazione
 * un attimo prima del salto. [follow] dice se la si sta seguendo; si salva con la posizione, perche'
 * tornando dalle impostazioni a meta' risposta il seguito deve riprendere da solo.
 */
@Stable
internal class ChatScroll(val list: LazyListState, positioned: Boolean, follow: Boolean) {
  var positioned by mutableStateOf(positioned)
  var follow: Boolean = follow

  /** Cresce a ogni domanda mandata: e' il segnale per [FollowBottom] di portare in fondo, animato. */
  internal var reveals by mutableIntStateOf(0)
    private set

  /** Una domanda appena mandata: in fondo, anche da risaliti, e da li' si segue. */
  fun revealLatest() {
    follow = true
    reveals++
  }

  companion object {
    val Saver: Saver<ChatScroll, Any> = listSaver<ChatScroll, Int>(
      save = { listOf(it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset, if (it.positioned) 1 else 0, if (it.follow) 1 else 0) },
      restore = { ChatScroll(LazyListState(it[0], it[1]), positioned = it[2] == 1, follow = it[3] == 1) },
    )
  }
}

/**
 * Una lista per conversazione. La chiave viene da [ChatScrollKeys]: cambia quando si apre un'altra
 * conversazione o una chat nuova, e allora la lista riparte e si porta in fondo; resta quando si
 * torna da un'altra pagina, e allora la posizione salvata torna com'era.
 */
@Composable
internal fun rememberChatScroll(key: String): ChatScroll =
  rememberSaveable(key, saver = ChatScroll.Saver) { ChatScroll(LazyListState(), positioned = false, follow = true) }

/**
 * Quale lista mostra la chat. Di solito una per conversazione; l'eccezione e' la chat nuova che
 * nasce su disco mentre Aria le risponde: e' la stessa pagina, e cambiarle lista vorrebbe dire
 * rifarla da capo (e nasconderla per un fotogramma) proprio mentre la prima risposta arriva.
 * Allora la conversazione appena nata eredita la chiave della chat nuova.
 *
 * Campi semplici e non stato: li scrive la composizione con gli stessi ingressi e lo stesso esito,
 * e si salvano con la pagina ([Saver]).
 */
internal class ChatScrollKeys(
  private var epoch: Int = 0,
  private var last: Long? = null,
  private var adopted: Long? = null,
  private var bornHere: Boolean = false,
) {
  fun keyFor(conversationId: Long?, liveShown: Boolean): String {
    if (conversationId == null) {
      if (last != null) {
        epoch++
        last = null
        adopted = null
      }
      // Una chat nuova con una domanda in corso: quando nascera' su disco sara' ancora lei.
      bornHere = liveShown
      return "nuova-$epoch"
    }
    if (conversationId != last) {
      adopted = if (last == null && bornHere) conversationId else null
      bornHere = false
      last = conversationId
    }
    return if (conversationId == adopted) "nuova-$epoch" else "c-$conversationId"
  }

  companion object {
    val Saver: Saver<ChatScrollKeys, Any> = listSaver<ChatScrollKeys, Long>(
      save = { listOf(it.epoch.toLong(), it.last ?: NoId, it.adopted ?: NoId, if (it.bornHere) 1L else 0L) },
      restore = { ChatScrollKeys(it[0].toInt(), it[1].takeIf { id -> id != NoId }, it[2].takeIf { id -> id != NoId }, it[3] == 1L) },
    )
    private const val NoId = Long.MIN_VALUE
  }
}

@Composable
internal fun rememberChatScrollKeys(): ChatScrollKeys = rememberSaveable(saver = ChatScrollKeys.Saver) { ChatScrollKeys() }

/**
 * Le tre regole del fondo, per [scroll]. Niente loop di fotogrammi: si ascoltano la misura della
 * lista e il dito (`snapshotFlow` su `layoutInfo` e `isScrollInProgress`), cosi' a riposo non si
 * chiede nessun fotogramma e la batteria non se ne accorge.
 *
 * - Il dito (o un lancio) decide il seguito: finito in fondo, si segue; risalito oltre lo scarto,
 *   no. La crescita del contenuto invece non stacca mai il seguito: e' la risposta che si allunga.
 * - Seguendo, una crescita di pochi pixel (la risposta viva, che cresce spalmata sui fotogrammi) si
 *   insegue senza animazione; un item nuovo (la domanda, la risposta che comincia) si raggiunge con
 *   la molla, perche' e' un salto e deve vedersi da dove arriva.
 */
@Composable
internal fun FollowBottom(scroll: ChatScroll) {
  val slack = with(LocalDensity.current) { FollowSlack.toPx() }
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  LaunchedEffect(scroll, reducedMotion) {
    val list = scroll.list
    // La prima misura: prima non c'e' niente da misurare, e il seguito si deciderebbe sul vuoto.
    withTimeoutOrNull(FirstLayoutTimeoutMillis) { snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 } }
    if (!scroll.positioned) {
      guarded { list.jumpToEnd() }
      scroll.follow = true
      scroll.positioned = true
    }
    var lastCount = list.layoutInfo.totalItemsCount
    var lastReveal = scroll.reveals
    var lastDistance = list.distanceToEnd()
    snapshotFlow { FollowSample(list.isScrollInProgress, list.distanceToEnd(), list.layoutInfo.totalItemsCount, scroll.reveals) }
      .collect { sample ->
        val newItems = sample.count > lastCount
        lastCount = sample.count
        val distance = sample.distance
        // Il fondo si insegue solo quando si allontana da solo (il contenuto cresce, il composer o
        // la tastiera si alzano): un dito lasciato a venti pixel dal fondo resta dov'e'.
        val grew = distance != null && distance > (lastDistance ?: 0)
        lastDistance = distance
        when {
          sample.reveals != lastReveal -> {
            lastReveal = sample.reveals
            scroll.follow = true
            guarded { list.toEnd(animated = !reducedMotion) }
            lastDistance = list.distanceToEnd()
          }
          sample.scrolling -> scroll.follow = distance != null && distance <= slack
          !scroll.follow -> if (distance != null && distance <= slack) scroll.follow = true
          distance == null || (newItems && distance > slack) -> {
            guarded { list.toEnd(animated = !reducedMotion) }
            lastDistance = list.distanceToEnd()
          }
          grew -> {
            guarded { list.scrollBy(distance.toFloat()) }
            lastDistance = list.distanceToEnd()
          }
        }
      }
  }
}

private data class FollowSample(val scrolling: Boolean, val distance: Int?, val count: Int, val reveals: Int)

/**
 * Quanto manca al fondo, in pixel: dal bordo basso dell'ultimo item (piu' il padding in fondo, cioe'
 * il composer) al bordo basso della lista. Null se l'ultimo item non e' nemmeno in vista.
 */
internal fun LazyListState.distanceToEnd(): Int? {
  val info = layoutInfo
  val last = info.visibleItemsInfo.lastOrNull() ?: return null
  if (last.index != info.totalItemsCount - 1) return null
  return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0)
}

/**
 * In fondo in un colpo: all'ultimo item e poi di quanto sporge. Dopo `scrollToItem` la misura e'
 * gia' rifatta (la lista si rimisura subito), quindi i due passi finiscono nello stesso fotogramma.
 */
internal suspend fun LazyListState.jumpToEnd() {
  val total = layoutInfo.totalItemsCount
  if (total == 0) return
  if (distanceToEnd() == null) scrollToItem(total - 1)
  distanceToEnd()?.takeIf { it > 0 }?.let { scrollBy(it.toFloat()) }
}

/** In fondo, con la molla della chrome se [animated]. Un item lontano si raggiunge prima con il salto della lista. */
private suspend fun LazyListState.toEnd(animated: Boolean) {
  if (!animated) {
    jumpToEnd()
    return
  }
  val total = layoutInfo.totalItemsCount
  if (total == 0) return
  if (distanceToEnd() == null) animateScrollToItem(total - 1)
  distanceToEnd()?.takeIf { it > 0 }?.let { animateScrollBy(it.toFloat(), FollowSpec) }
}

private val FollowSpec: AnimationSpec<Float> = FluidMotion.snappy()

/**
 * Uno scorrimento nostro che il dito puo' interrompere: la lista lo cancella quando l'utente la
 * tocca (il dito ha la precedenza), e quella cancellazione non deve chiudere l'ascolto del fondo.
 * Se invece a chiudersi e' l'effetto stesso, la cancellazione passa.
 */
private suspend inline fun guarded(block: () -> Unit) {
  try {
    block()
  } catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
  }
}
