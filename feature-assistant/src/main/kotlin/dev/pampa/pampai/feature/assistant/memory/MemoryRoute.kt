package dev.pampa.pampai.feature.assistant.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampai.core.assistant.db.Memory
import dev.pampa.pampai.core.assistant.reminders.ReminderRepeat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Cosa si sta modificando nel dialogo: un ricordo nuovo (id null) o uno esistente. */
private data class Editing(val id: Long?, val text: String)

/**
 * La memoria di Aria e i promemoria, in una pagina: prima si potevano solo chiedere in chat, e
 * una cosa che un assistente ricorda di te deve potersi vedere e cancellare senza doverglielo
 * chiedere.
 */
@Composable
fun MemoryRoute(onBack: () -> Unit, viewModel: MemoryViewModel = hiltViewModel()) {
  val memories by viewModel.memories.collectAsStateWithLifecycle()
  val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
  var editing by remember { mutableStateOf<Editing?>(null) }
  var confirmClear by remember { mutableStateOf(false) }
  var deletingReminder by remember { mutableStateOf<Long?>(null) }

  FluidScreen(
    title = "Memoria",
    subtitle = "Cosa Aria sa di te e cosa deve ricordarti.",
    onBack = onBack,
    itemSpacing = 12.dp,
    ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Glow) },
  ) {
    item {
      FluidSectionHeader(
        title = "Cosa sa di te",
        detail = "Entra in ogni domanda. Si aggiunge dicendo \"ricordati che…\", o da qui.",
      )
    }
    val list = memories
    when {
      list == null -> Unit
      list.isEmpty() -> item {
        FluidInlineMessage(
          title = "Niente per ora",
          message = "Prova a dire ad Aria \"ricordati che sono vegetariano\" o \"la mia fermata e' Dalmazia\".",
          tone = FluidTone.Info,
        )
      }
      else -> item {
        FluidListGroup(glass = true) {
          list.forEachIndexed { index, memory ->
            if (index > 0) FluidListDivider()
            MemoryRow(
              memory = memory,
              onEdit = { editing = Editing(memory.id, memory.text) },
              onPin = { viewModel.setPinned(memory.id, !memory.pinned) },
              onDelete = { viewModel.remove(memory.id) },
            )
          }
        }
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidButton(text = "Aggiungi", onClick = { editing = Editing(null, "") }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Small)
        if (!list.isNullOrEmpty()) {
          FluidButton(text = "Dimentica tutto", onClick = { confirmClear = true }, style = FluidButtonStyle.Plain, size = FluidButtonSize.Small)
        }
      }
    }

    item { FluidSectionHeader(title = "Promemoria", detail = "Quelli ancora da suonare. \"Ricordami alle 18 di…\" per crearne uno.") }
    val reminders = upcoming
    when {
      reminders == null -> Unit
      reminders.isEmpty() -> item {
        FluidInlineMessage(title = "Nessun promemoria", message = "Quando ne chiedi uno ad Aria compare qui.", tone = FluidTone.Info)
      }
      else -> item {
        FluidListGroup(glass = true) {
          reminders.forEachIndexed { index, reminder ->
            if (index > 0) FluidListDivider()
            val repeat = runCatching { ReminderRepeat.valueOf(reminder.repeat) }.getOrDefault(ReminderRepeat.NONE)
            FluidListRow(
              title = reminder.text,
              subtitle = whenLabel(reminder.atMillis) + if (repeat != ReminderRepeat.NONE) " · ${repeat.label}" else "",
              contextActions = {
                listOf(FluidContextAction("Elimina", Icons.Rounded.Delete, destructive = true) { deletingReminder = reminder.id })
              },
              onClick = { deletingReminder = reminder.id },
            )
          }
        }
      }
    }
  }

  editing?.let { current ->
    var text by remember(current) { mutableStateOf(current.text) }
    FluidAlert(
      onDismissRequest = { editing = null },
      title = if (current.id == null) "Nuovo ricordo" else "Modifica ricordo",
      message = "Una frase breve, come la diresti a un amico.",
      actions = listOf(
        FluidAlertAction("Annulla", { editing = null }),
        FluidAlertAction("Salva", {
          if (current.id == null) viewModel.add(text) else viewModel.update(current.id, text)
          editing = null
        }, FluidAlertAction.Emphasis.Preferred, enabled = text.isNotBlank()),
      ),
    ) {
      FluidTextField(value = text, onValueChange = { text = it.take(300) }, placeholder = "Es. sono allergico alle noci", singleLine = false, maxLines = 4)
    }
  }
  if (confirmClear) {
    FluidAlert(
      onDismissRequest = { confirmClear = false },
      title = "Dimenticare tutto?",
      message = "Aria non sapra' piu' niente di quello che le hai chiesto di ricordare.",
      actions = listOf(
        FluidAlertAction("Annulla", { confirmClear = false }),
        FluidAlertAction("Dimentica", { viewModel.clearMemory(); confirmClear = false }, FluidAlertAction.Emphasis.Destructive),
      ),
    )
  }
  deletingReminder?.let { id ->
    FluidAlert(
      onDismissRequest = { deletingReminder = null },
      title = "Eliminare il promemoria?",
      message = upcoming?.firstOrNull { it.id == id }?.text,
      actions = listOf(
        FluidAlertAction("Annulla", { deletingReminder = null }),
        FluidAlertAction("Elimina", { viewModel.removeReminder(id); deletingReminder = null }, FluidAlertAction.Emphasis.Destructive),
      ),
    )
  }
}

@Composable
private fun MemoryRow(memory: Memory, onEdit: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
  FluidListRow(
    title = memory.text,
    subtitle = (if (memory.pinned) "Fissato · " else "") + "dal " + dayLabel(memory.createdAtMillis),
    onClick = onEdit,
    contextActions = {
      listOf(
        FluidContextAction("Modifica", Icons.Rounded.Edit, onClick = onEdit),
        FluidContextAction(if (memory.pinned) "Non fissare" else "Fissa: sempre nel prompt", Icons.Rounded.PushPin, onClick = onPin),
        FluidContextAction("Dimentica", Icons.Rounded.Delete, destructive = true, onClick = onDelete),
      )
    },
  )
}

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)

private fun dayLabel(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dayFormat)

/** "Oggi alle 18:00", "domani alle 8:00", "3 ottobre alle 9:30". */
private fun whenLabel(millis: Long): String {
  val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
  val today = LocalDate.now(at.zone)
  val day = when (at.toLocalDate()) {
    today -> "Oggi"
    today.plusDays(1) -> "Domani"
    else -> at.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }
  }
  return "$day alle ${at.format(clockFormat)}"
}
