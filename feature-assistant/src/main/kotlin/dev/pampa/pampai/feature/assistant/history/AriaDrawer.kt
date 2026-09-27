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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidContextMenuController
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
  /**
   * Aprire e cominciare passano dalla chat, non da qui: e' la chat a tenere il plugin, "pensa piu'
   * a fondo" e la chat temporanea armata, e una conversazione aperta alle sue spalle se li portava
   * dietro (la chat nuova dal cassetto restava temporanea, il plugin di prima finiva su un'altra).
   */
  onOpen: (Long) -> Unit,
  onNewChat: () -> Unit,
  onOpenMemory: () -> Unit = {},
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
  // Un menu alla volta. L'host di radice mette lo scrim anche sopra il cassetto, quindi una
  // seconda pressione lunga non arriva a nessuna riga finche' il primo e' aperto; questo chiude
  // comunque quello aperto prima di alzarne un altro, per non dipendere da chi mangia i tocchi.
  val menuSlot = remember { MenuSlot() }
  var renaming by remember { mutableStateOf<dev.pampa.pampai.core.assistant.db.Conversation?>(null) }
  var deleting by remember { mutableStateOf<dev.pampa.pampai.core.assistant.db.Conversation?>(null) }
  var deletingAll by remember { mutableStateOf(false) }
  val rowActions = RowActions(
    open = { id -> onOpen(id); onOpenConversation() },
    rename = { renaming = it },
    delete = { deleting = it },
    pin = { viewModel.pin(it.id, !it.pinned) },
  )

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
              onClick = { onOpen(hit.conversationId); onOpenConversation() },
            )
          }
        }
        return@LazyColumn
      }
      item {
        NavRow(Icons.Rounded.Settings, "Impostazioni", onOpenSettings)
        NavRow(Icons.Rounded.Psychology, "Memoria e promemoria", onOpenMemory)
        NavRow(Icons.Rounded.Insights, "Consumi", onOpenUsage)
        Spacer(Modifier.height(8.dp))
      }
      if (pinned.isNotEmpty()) {
        item(key = "pin-h") { SectionLabel("Fissate") }
        pinned.forEach { conversation ->
          item(key = "pin-${conversation.id}") { ConversationRow(conversation, active, rowActions, menuSlot) }
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
          item(key = "c-${conversation.id}") { ConversationRow(conversation, active, rowActions, menuSlot) }
        }
      }
      if (items.isEmpty()) {
        item { Text("Le conversazioni che farai restano qui.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
      } else {
        item(key = "delete-all") {
          Text(
            "Elimina tutte le conversazioni",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
              .padding(top = 16.dp)
              .fillMaxWidth()
              .fluidPressable(onClick = { deletingAll = true }, role = Role.Button, pressedScale = 1f)
              .padding(horizontal = 12.dp, vertical = 12.dp),
          )
        }
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
          .fluidPressable(onClick = { onNewChat(); onOpenConversation() }, role = Role.Button)
          .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Nuova chat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
      }
    }
  }

  renaming?.let { conversation ->
    var title by remember(conversation.id) { mutableStateOf(conversation.title) }
    FluidAlert(
      onDismissRequest = { renaming = null },
      title = "Rinomina",
      actions = listOf(
        FluidAlertAction("Annulla", { renaming = null }),
        FluidAlertAction("Salva", {
          viewModel.rename(conversation.id, title.trim())
          renaming = null
        }, FluidAlertAction.Emphasis.Preferred, enabled = title.isNotBlank()),
      ),
    ) {
      FluidTextField(value = title, onValueChange = { title = it.take(80) }, placeholder = "Titolo della conversazione")
    }
  }
  deleting?.let { conversation ->
    FluidAlert(
      onDismissRequest = { deleting = null },
      title = "Eliminare la conversazione?",
      message = "\"${conversation.title}\" sparisce con tutti i suoi messaggi e allegati.",
      actions = listOf(
        FluidAlertAction("Annulla", { deleting = null }),
        FluidAlertAction("Elimina", {
          viewModel.delete(conversation.id)
          deleting = null
        }, FluidAlertAction.Emphasis.Destructive),
      ),
    )
  }
  if (deletingAll) {
    FluidAlert(
      onDismissRequest = { deletingAll = false },
      title = "Eliminare tutto?",
      message = "Tutte le ${items.size} conversazioni, con messaggi e allegati. Non si puo' annullare.",
      actions = listOf(
        FluidAlertAction("Annulla", { deletingAll = false }),
        FluidAlertAction("Elimina tutto", {
          viewModel.deleteAll()
          deletingAll = false
        }, FluidAlertAction.Emphasis.Destructive),
      ),
    )
  }
}

/** Cosa si puo' fare con una riga: aprirla, e le voci del suo menu. */
private class RowActions(
  val open: (Long) -> Unit,
  val rename: (dev.pampa.pampai.core.assistant.db.Conversation) -> Unit,
  val delete: (dev.pampa.pampai.core.assistant.db.Conversation) -> Unit,
  val pin: (dev.pampa.pampai.core.assistant.db.Conversation) -> Unit,
)

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

/**
 * Il menu aperto in questo momento, se c'e'. Ogni riga tiene il proprio controller (e' cosi' che
 * l'engine registra l'ancora e la ripresa della riga), e il cassetto tiene solo il riferimento a
 * quello alzato per ultimo: e' quello da chiudere prima di alzarne un altro.
 */
private class MenuSlot {
  var open: FluidContextMenuController? = null
}

@Composable
private fun ConversationRow(
  conversation: dev.pampa.pampai.core.assistant.db.Conversation,
  active: Long?,
  actions: RowActions,
  menuSlot: MenuSlot,
) {
  val menu = rememberFluidContextMenu(actions = {
    listOf(
      FluidContextAction(if (conversation.pinned) "Togli dalle fissate" else "Fissa in alto", Icons.Rounded.PushPin) { actions.pin(conversation) },
      FluidContextAction("Rinomina", Icons.Rounded.Edit) { actions.rename(conversation) },
      FluidContextAction("Elimina", Icons.Rounded.Delete, destructive = true) { actions.delete(conversation) },
    )
  })
  val openMenu = {
    menuSlot.open?.takeIf { it !== menu }?.dismiss()
    if (menu.open()) menuSlot.open = menu
  }
  DrawerRow(
    title = conversation.title,
    detail = null,
    selected = conversation.id == active,
    onClick = { actions.open(conversation.id) },
    modifier = Modifier
      .fluidContextMenuAnchor(menu)
      // Il menu si apre anche senza pressione lunga: TalkBack lo elenca fra le azioni della riga.
      .semantics {
        customActions = listOf(
          CustomAccessibilityAction(if (conversation.pinned) "Togli dalle fissate" else "Fissa in alto") { actions.pin(conversation); true },
          CustomAccessibilityAction("Rinomina") { actions.rename(conversation); true },
          CustomAccessibilityAction("Elimina") { actions.delete(conversation); true },
        )
      },
    onLongClick = openMenu,
    trailing = {
      Box(
        Modifier
          .size(40.dp)
          .fluidPressable(onClick = openMenu, role = Role.Button, haptic = null),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Rounded.MoreHoriz, contentDescription = "Altre azioni", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
      }
    },
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
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier
      .fillMaxWidth()
      .padding(vertical = 1.dp)
      .background(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = ContinuousCornerShape(FluidRadius.Control),
      )
      .fluidPressable(onClick = onClick, onLongClick = onLongClick, role = Role.Button, pressedScale = 1f)
      .padding(start = 12.dp, end = if (trailing != null) 2.dp else 12.dp, top = if (trailing != null) 2.dp else 11.dp, bottom = if (trailing != null) 2.dp else 11.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f).padding(vertical = if (trailing != null) 9.dp else 0.dp)) {
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
    trailing?.invoke()
  }
}
