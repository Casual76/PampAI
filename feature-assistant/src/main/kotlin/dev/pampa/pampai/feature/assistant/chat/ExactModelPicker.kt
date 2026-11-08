package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.pampa.pampai.core.assistant.runtime.*
import kotlinx.coroutines.launch

/** The same catalog and explicit vision consent on both app and overlay. */
@Composable
internal fun ExactModelControl(selection: ModelSelection, conversationId: Long?) {
  val state by selection.state.collectAsState()
  val needsImages by selection.imageChoiceNeeded.collectAsState()
  var open by remember { mutableStateOf(false) }
  var helper by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  LaunchedEffect(needsImages) {
    if (needsImages) {
      query = ""
      helper = false
    }
  }
  val active by selection.activeAttempt.collectAsState()
  val policy = state.selected(conversationId)
  val scope = rememberCoroutineScope()
  AssistantModelControl(policy?.identity?.let { "${it.provider.id} / ${it.model}" } ?: "Configura un modello", { helper = false; query = ""; open = true })
  FluidGlassModalPortal(visible = open || needsImages, onDismissRequest = { open = false; selection.imageChoiceNeeded.value = false }, presentation = FluidGlassModalPresentation.Sheet, paneTitle = "Scegli modello") {
    LazyColumn(Modifier.testTag("picker-sheet").fillMaxWidth().heightIn(max = 560.dp).imePadding(), contentPadding = PaddingValues(16.dp)) {
      item { Column {
      Text(if (needsImages) "Queste immagini richiedono una scelta" else "Modello della conversazione", style = MaterialTheme.typography.titleMedium)
      active?.let { Text("Tentativo attivo: ${it.identity.provider.id} / ${it.identity.model}", style = MaterialTheme.typography.bodySmall) }
      policy?.helper?.let { Text("Supporto visivo scelto: ${it.provider.id} / ${it.model}", style = MaterialTheme.typography.bodySmall) }
      policy?.takeIf { it.helper != null }?.let { selected ->
        TextButton(onClick = { scope.launch { selection.save(conversationId, selected.copy(helper = null)) } }) {
          Text("Disattiva supporto visivo")
        }
      }
      if (needsImages) Text("Solo modelli con supporto immagini verificato. La bozza resta disponibile.", style = MaterialTheme.typography.bodySmall)
      policy?.let { Text("Selezionato: ${it.identity.provider.id} / ${it.identity.model}", style = MaterialTheme.typography.bodySmall) }
      Text("Impegno per i prossimi invii", style = MaterialTheme.typography.labelMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (listOf<ThinkingLevel?>(null) + ThinkingLevel.entries).forEach { effort ->
          FluidChip(selected = policy?.effort == effort, onClick = { selection.effort(conversationId, effort) },
            modifier = Modifier.heightIn(min = 48.dp), label = when (effort) {
              null -> "Auto"; ThinkingLevel.LOW -> "Basso"; ThinkingLevel.MEDIUM -> "Medio"; ThinkingLevel.HIGH -> "Alto"
            })
        }
      }
      TextButton(onClick = { helper = !helper }) { Text(if (helper) "Modello principale" else "Supporto visivo") }
      if (helper) Text("Invia le immagini al supporto scelto. Il principale riceve una descrizione dichiarata, senza pixel.", style = MaterialTheme.typography.bodySmall)
      FluidTextField(value = query, onValueChange = { query = it }, placeholder = "Cerca modello o provider")
      if (state.options.isEmpty()) Text("Configura e verifica un provider nelle impostazioni.")
      } }
      item {
        LazyColumn(Modifier.testTag("picker-catalog").fillMaxWidth().heightIn(max = 280.dp)) {
          val options = state.options.filter {
            (!needsImages && !helper || it.images == ImageCapability.YES) &&
              (it.label + it.identity.key).contains(query, true)
          }
          val groups = listOf(
            "Preferiti" to options.filter { it.identity.key in state.favorites },
            "Recenti" to options.filter { it.identity.key !in state.favorites && it.identity.key in state.recent }
              .sortedBy { state.recent.indexOf(it.identity.key) },
            "Tutti i modelli" to options.filter { it.identity.key !in state.favorites && it.identity.key !in state.recent },
          )
          if (options.isEmpty() && state.options.isNotEmpty()) item { Text("Nessun modello trovato") }
          groups.forEach { (title, models) ->
            if (models.isNotEmpty()) item(key = "group-$title") {
              Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { heading() }.padding(vertical = 8.dp))
            }
            items(models, key = { it.identity.key }) { option ->
          Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = option.identity == (if (helper) policy?.helper else policy?.identity) }.fluidPressable(onClick = { selection.choose(conversationId, option.identity, helper); open = false }).padding(vertical = 12.dp)) {
              Text(option.label, style = MaterialTheme.typography.bodyMedium)
              val imageLabel = when (option.images) {
                ImageCapability.YES -> "immagini supportate"
                ImageCapability.NO -> "senza immagini"
                ImageCapability.UNKNOWN -> "supporto immagini non verificato"
              }
              Text("${option.identity.provider.id} / ${option.identity.model} · $imageLabel", style = MaterialTheme.typography.labelSmall)
            }
            val favorite = option.identity.key in state.favorites
            TextButton(
              onClick = { selection.favorite(option.identity) },
              modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                contentDescription = if (favorite) "Rimuovi ${option.label} dai preferiti" else "Aggiungi ${option.label} ai preferiti"
                role = Role.Checkbox
                toggleableState = if (favorite) ToggleableState.On else ToggleableState.Off
              },
            ) { Text(if (favorite) "\u2605" else "\u2606") }
          }
            }
          }
        }
      }
      item { Column {
      TextButton(onClick = { open = false; selection.imageChoiceNeeded.value = false }) { Text("Chiudi") }
      } }
    }
  }
}
