package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos

/**
 * La lista che segue la conversazione: un messaggio nuovo la porta in fondo con l'animazione, e
 * mentre la risposta si forma la tiene in fondo senza. Sono i due effetti che stavano in
 * [ChatRoute], con le sole tre cose che guardano: il guscio resta la struttura della pagina.
 */
@Composable
internal fun FollowStreaming(listState: LazyListState, itemCount: Int, answering: Boolean) {
  // Un messaggio nuovo porta la lista in fondo, con l'animazione.
  LaunchedEffect(itemCount) {
    if (itemCount > 0) runCatching { listState.animateScrollToItem(itemCount + 1) }
  }
  // Mentre la risposta si forma, la lista la segue un fotogramma alla volta, scorrendo **solo di
  // quanto l'ultimo item sporge** dal fondo. La risposta viva cresce con `animateContentSize`,
  // quindi sporge di pochi pixel per fotogramma e il seguito e' continuo; il vecchio giro a
  // 120 ms con `scrollToItem` saltava di un blocco alla volta. Solo se l'utente e' ancora in
  // fondo: chi e' risalito a rileggere non va riportato giu' a forza, e chi sta trascinando o ha
  // lanciato la lista non va contrastato.
  LaunchedEffect(answering) {
    if (!answering) return@LaunchedEffect
    while (true) {
      withFrameNanos { }
      if (listState.isScrollInProgress) continue
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull() ?: continue
      if (last.index != info.totalItemsCount - 1) continue
      val overflow = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
      if (overflow > 0) runCatching { listState.scrollBy(overflow.toFloat()) }
    }
  }
}
