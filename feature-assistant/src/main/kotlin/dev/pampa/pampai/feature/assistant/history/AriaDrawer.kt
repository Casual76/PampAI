package dev.pampa.pampai.feature.assistant.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.fluid.fluidContextMenuAnchor
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.rememberFluidContextMenu
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Il cassetto delle conversazioni: quello che in una chat sta dietro l'hamburger.
 *
 * Prende il posto delle tre schede in fondo. Una barra di navigazione va bene per un'app fatta di
 * pagine; una chat ha una pagina sola, e tutto il resto — le conversazioni di prima, le
 * impostazioni — e' roba che si va a prendere e da cui si torna. Le righe sono nude, senza gruppi
 * ne' piastrelle: in un cassetto di titoli il vetro e le cornici sono rumore.
 */
@Composable
fun AriaDrawer(
  onOpenConversation: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenUsage: () -> Unit,
  viewModel: HistoryViewModel = hiltViewModel(),
) {
  val items by viewModel.items.collectAsStateWithLifecycle()
  val active by viewModel.activeConversationId.collectAsStateWithLifecycle()
  val query by viewModel.query.collectAsStateWithLifecycle()
  val hits by viewModel.hits.collectAsStateWithLifecycle()
  val zone = ZoneId.systemDefault()
  val today = LocalDate.now(zone)
  val pinned = items.filter { it.pinned }
  val grouped = items.filter { !it.pinned }.groupBy { Instant.ofEpochMilli(it.updatedAtMillis).atZone(zone).toLocalDate() }

  Column(
    Modifier
      .fillMaxHeight()
      .background(MaterialTheme.colorScheme.surface)
      .statusBarsPadding()
      .padding(horizontal = 12.dp),
  ) {
    Text(
      text = "Aria",
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
    )
    FluidTextField(value = query, onValueChange = viewModel::setQuery, placeholder = "Cerca nelle conversazioni…")
    Spacer(Modifier.height(8.dp))

    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
      if (query.length >= 3) {
        item { SectionLabel(if (hits.isEmpty()) "Niente per \"$query\"" else "${hits.size} passaggi") }
        items@ for (hit in hits) {
          item(key = "hit-${hit.conversationId}-${hit.atMillis}") {
            DrawerRow(
              title = hit.conversationTitle,
              detail = "…${hit.snippet}…",
              selected = false,
              onClick = { viewModel.open(hit.conversationId); onOpenConversation() },
            )
          }
        }
        return@LazyColumn
      }
      item {
        NavRow(Icons.Rounded.Settings, "Impostazioni", onOpenSettings)
        NavRow(Icons.Rounded.Insights, "Consumi", onOpenUsage)
        Spacer(Modifier.height(8.dp))
      }
      if (pinned.isNotEmpty()) {
        item(key = "pin-h") { SectionLabel("Fissate") }
        pinned.forEach { conversation ->
          item(key = "pin-${conversation.id}") { ConversationRow(conversation, active, viewModel, onOpenConversation) }
        }
      }
      grouped.forEach { (day, conversations) ->
        item(key = "h-$day") {
          SectionLabel(
            when (day) {
              today -> "Oggi"
              today.minusDays(1) -> "Ieri"
              else -> DateFormat.getDateInstance(DateFormat.LONG).format(Date(conversations.first().updatedAtMillis))
            },
          )
        }
        conversations.forEach { conversation ->
          item(key = "c-${conversation.id}") { ConversationRow(conversation, active, viewModel, onOpenConversation) }
        }
      }
      if (items.isEmpty()) {
        item { Text("Le conversazioni che farai restano qui.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier
          .background(MaterialTheme.colorScheme.primary, ContinuousCornerShape(FluidRadius.Control))
          .fluidPressable(onClick = { viewModel.newConversation(); onOpenConversation() }, role = Role.Button)
          .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Nuova chat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
  )
}

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .fluidPressable(onClick = onClick, role = Role.Button, pressedScale = 1f)
      .padding(horizontal = 12.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(14.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun ConversationRow(
  conversation: dev.pampa.pampai.core.assistant.db.Conversation,
  active: Long?,
  viewModel: HistoryViewModel,
  onOpen: () -> Unit,
) {
  val menu = rememberFluidContextMenu(actions = {
    listOf(
      FluidContextAction(if (conversation.pinned) "Togli dalle fissate" else "Fissa in alto", Icons.Rounded.PushPin) { viewModel.pin(conversation.id, !conversation.pinned) },
      FluidContextAction("Elimina", Icons.Rounded.Delete, destructive = true) { viewModel.delete(conversation.id) },
    )
  })
  DrawerRow(
    title = conversation.title,
    detail = null,
    selected = conversation.id == active,
    onClick = { viewModel.open(conversation.id); onOpen() },
    modifier = Modifier.fluidContextMenuAnchor(menu),
    onLongClick = { menu.open() },
  )
}

@Composable
private fun DrawerRow(
  title: String,
  detail: String?,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
) {
  Box(
    modifier
      .fillMaxWidth()
      .padding(vertical = 1.dp)
      .background(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = ContinuousCornerShape(FluidRadius.Control),
      )
      .fluidPressable(onClick = onClick, onLongClick = onLongClick, role = Role.Button, pressedScale = 1f)
      .padding(horizontal = 12.dp, vertical = 11.dp),
  ) {
    Column {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      detail?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}
