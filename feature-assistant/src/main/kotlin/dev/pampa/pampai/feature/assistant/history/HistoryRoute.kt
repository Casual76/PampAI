package dev.pampa.pampai.feature.assistant.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Le conversazioni salvate, dalla piu' recente, raggruppate per giorno (le fissate in testa). Un
 * tocco riapre e continua nella scheda della chat; la pressione lunga fissa o elimina. In cima
 * la ricerca dentro tutte le conversazioni.
 */
@Composable
fun HistoryRoute(
  bottomInset: Dp,
  onOpenConversation: () -> Unit,
  viewModel: HistoryViewModel = hiltViewModel(),
) {
  val items by viewModel.items.collectAsStateWithLifecycle()
  val active by viewModel.activeConversationId.collectAsStateWithLifecycle()
  val busy by viewModel.busy.collectAsStateWithLifecycle()
  val query by viewModel.query.collectAsStateWithLifecycle()
  val hits by viewModel.hits.collectAsStateWithLifecycle()
  val zone = ZoneId.systemDefault()
  val today = LocalDate.now(zone)
  val pinned = items.filter { it.pinned }
  val grouped = items.filter { !it.pinned }.groupBy { Instant.ofEpochMilli(it.updatedAtMillis).atZone(zone).toLocalDate() }

  FluidScreen(
    title = "Cronologia",
    subtitle = if (items.isEmpty()) "Le conversazioni restano qui, sul telefono." else "${items.size} conversazioni, sul telefono.",
    extraBottomPadding = bottomInset,
    itemSpacing = 12.dp,
    actions = {
      if (items.isNotEmpty()) FluidBarAction(icon = Icons.Rounded.DeleteSweep, contentDescription = "Elimina tutte", onClick = viewModel::deleteAll)
    },
  ) {
    item {
      FluidTextField(value = query, onValueChange = viewModel::setQuery, placeholder = "Cerca nelle conversazioni…")
    }
    if (query.length >= 3) {
      item { FluidSectionHeader(title = "Risultati", detail = if (hits.isEmpty()) "Niente per \"$query\"." else "${hits.size} passaggi") }
      if (hits.isNotEmpty()) {
        item(key = "hits") {
          FluidListGroup(glass = true) {
            hits.forEachIndexed { index, hit ->
              if (index > 0) FluidListDivider()
              FluidListRow(
                title = hit.conversationTitle,
                subtitle = "…${hit.snippet}…",
                meta = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(hit.atMillis)),
                onClick = { viewModel.open(hit.conversationId); onOpenConversation() },
              )
            }
          }
        }
      }
      return@FluidScreen
    }
    item {
      FluidListGroup(glass = true) {
        FluidListRow(
          title = "Nuova conversazione",
          subtitle = "Una domanda che parte da zero.",
          tone = FluidTone.Primary,
          leading = { Icon(Icons.Rounded.Add, contentDescription = null) },
          onClick = { viewModel.newConversation(); onOpenConversation() },
        )
      }
    }
    if (items.isEmpty()) {
      item { FluidEmptyState(title = "Nessuna conversazione", detail = "Quelle che farai restano qui, e le puoi riprendere quando vuoi.") }
    }
    if (pinned.isNotEmpty()) {
      item(key = "pinned-h") { FluidSectionHeader(title = "Fissate") }
      item(key = "pinned") { ConversationGroup(pinned, active, busy, viewModel, onOpenConversation) }
    }
    grouped.forEach { (day, conversations) ->
      item(key = "day-$day") {
        FluidSectionHeader(
          title = when (day) {
            today -> "Oggi"
            today.minusDays(1) -> "Ieri"
            else -> DateFormat.getDateInstance(DateFormat.LONG).format(Date(conversations.first().updatedAtMillis))
          },
        )
      }
      item(key = "group-$day") { ConversationGroup(conversations, active, busy, viewModel, onOpenConversation) }
    }
  }
}

@Composable
private fun ConversationGroup(
  conversations: List<dev.pampa.pampai.core.assistant.db.Conversation>,
  active: Long?,
  busy: Boolean,
  viewModel: HistoryViewModel,
  onOpenConversation: () -> Unit,
) {
  FluidListGroup(glass = true) {
    conversations.forEachIndexed { index, conversation ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = conversation.title,
        subtitle = buildString {
          append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(conversation.updatedAtMillis)))
          conversation.lastProvider?.let { append(" · ").append(it.label) }
          if (conversation.source == "session") append(" · dal tasto")
          if (conversation.id == active && busy) append(" · in corso")
        },
        tone = if (conversation.id == active) FluidTone.Primary else FluidTone.Neutral,
        onClick = { viewModel.open(conversation.id); onOpenConversation() },
        contextActions = {
          listOf(
            FluidContextAction(if (conversation.pinned) "Togli dalle fissate" else "Fissa in alto", Icons.Rounded.PushPin) { viewModel.pin(conversation.id, !conversation.pinned) },
            FluidContextAction("Elimina", Icons.Rounded.Delete, destructive = true) { viewModel.delete(conversation.id) },
          )
        },
      )
    }
  }
}
