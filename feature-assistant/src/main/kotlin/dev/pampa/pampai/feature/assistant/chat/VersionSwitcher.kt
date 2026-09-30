package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.Version

/**
 * Le versioni di un messaggio, "‹ 2/3 ›": due frecce e il numero, come nelle chat che si usano.
 *
 * Una versione nasce da "rigenera" (una risposta sorella) o da "modifica e rinvia" (una domanda
 * sorella, con il suo ramo): nessuna delle due cancella niente, e le frecce riportano al ramo di
 * prima. Le frecce sono le azioni piccole della chat ([SmallAction]), a quarantotto dp di
 * bersaglio; il numero ha le cifre tabulari, cosi' passando da 1 a 2 non balla.
 *
 * @param enabled falso mentre Aria lavora su questa conversazione: la domanda in corso si attacca
 *   al ramo di adesso, e cambiarlo sotto i suoi piedi la farebbe finire altrove.
 */
@Composable
internal fun VersionSwitcher(version: Version, enabled: Boolean, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    SmallAction(
      icon = Icons.Rounded.ChevronLeft,
      description = "Versione precedente",
      onClick = { version.prevId?.let(onSelect) },
      enabled = enabled && version.prevId != null,
    )
    Text(
      text = "${version.index}/${version.count}",
      style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      // Un annuncio educato: chi usa TalkBack sente "Versione 2 di 3" quando la versione cambia.
      modifier = Modifier.semantics {
        contentDescription = "Versione ${version.index} di ${version.count}"
        liveRegion = LiveRegionMode.Polite
      },
    )
    SmallAction(
      icon = Icons.Rounded.ChevronRight,
      description = "Versione successiva",
      onClick = { version.nextId?.let(onSelect) },
      enabled = enabled && version.nextId != null,
    )
  }
}

/**
 * Le frecce anche fra le azioni di TalkBack del messaggio: dalla bolla o dalla risposta si cambia
 * versione senza andare a cercare i tasti piccoli.
 */
internal fun versionAccessibilityActions(version: Version?, enabled: Boolean, onSelect: (Long) -> Unit): List<CustomAccessibilityAction> {
  if (version == null || !enabled) return emptyList()
  return listOfNotNull(
    version.prevId?.let { id -> CustomAccessibilityAction("Versione precedente") { onSelect(id); true } },
    version.nextId?.let { id -> CustomAccessibilityAction("Versione successiva") { onSelect(id); true } },
  )
}

/**
 * Il posto di un messaggio nella conversazione, che resta lo stesso quando se ne sceglie un'altra
 * versione: un crossfade dalla versione di prima a quella nuova, con la misura che scivola.
 *
 * E' la chiave dell'item a tenere ferma la lista (vedi [slotKey]); qui si decide solo *come* cambia
 * il contenuto, e cambia quando cambia l'id del messaggio. Il bersaglio della transizione e' l'id,
 * non il messaggio: Room riemette il messaggio a ogni salvataggio del parziale, e con il messaggio
 * come bersaglio ogni salvataggio sarebbe stato una transizione, con la misura della risposta viva
 * trascinata da una molla (e ritagliata) mentre il testo cresce. L'ultimo messaggio visto per ogni
 * id resta qui, perche' la versione che esce sfumi con il suo testo. Con il moto ridotto il cambio
 * e' immediato.
 */
@Composable
internal fun MessageSlot(message: Message, content: @Composable (Message) -> Unit) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val seen = remember { HashMap<Long, Message>() }
  seen[message.id] = message
  AnimatedContent(
    targetState = message.id,
    transitionSpec = { slotTransition(reducedMotion) },
    label = "messageSlot",
  ) { id -> content(if (id == message.id) message else seen[id] ?: message) }
}

private fun AnimatedContentTransitionScope<Long>.slotTransition(reducedMotion: Boolean): ContentTransform =
  if (reducedMotion) {
    EnterTransition.None togetherWith ExitTransition.None
  } else {
    fadeIn(FluidMotion.crossFade()) togetherWith fadeOut(FluidMotion.crossFade()) using
      SizeTransform(clip = true) { _, _ -> FluidMotion.intSize(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy) }
  }

/**
 * La chiave del posto di un messaggio nella lista: il padre e il ruolo, non l'id. Le versioni di
 * un messaggio sono sorelle (stesso padre), quindi passare dall'una all'altra lascia la chiave
 * com'e' e la lista non salta: ritrova l'item e cambia solo cio' che c'e' dentro.
 */
internal fun slotKey(message: Message): String = "slot-${message.parentId ?: 0L}-${message.role.name}"
