package dev.pampa.pampai.feature.assistant.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.*
import dev.pampa.pampai.core.assistant.durable.*

internal enum class ProfilePage { HUB, SOURCES, UPDATES, EXCLUSIONS }

@Composable
internal fun ProfileRoute(page: ProfilePage, onBack: () -> Unit, onOpen: (ProfilePage) -> Unit,
  onMemory: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  when (page) {
    ProfilePage.HUB -> ProfileScreen(state, onBack, onOpen, onMemory, viewModel::detail, viewModel::reloadProfile)
    ProfilePage.SOURCES -> SourcesScreen(state, onBack, viewModel)
    ProfilePage.UPDATES -> UpdatesRoute(onBack, viewModel)
    ProfilePage.EXCLUSIONS -> ExclusionsScreen(state, onBack, viewModel::reenable, viewModel::refreshExclusions)
  }
  state.detail?.let { detail -> ProfileDetail(detail, state.busy,
    onDismiss = { viewModel.dismissDetail() }, onCorrect = { viewModel.correct(detail.item, it) },
    onForget = { viewModel.forget(detail.item) }, onExclude = { viewModel.exclude(it) }) }
}

@Composable
internal fun ProfileScreen(state: ProfileState, onBack: () -> Unit, onOpen: (ProfilePage) -> Unit,
  onMemory: () -> Unit, onDetail: (String) -> Unit, onRetry: () -> Unit = {}) {
  ProfileScaffold("Il tuo profilo", "Quello che hai detto, i fatti delle app e le deduzioni di Aria.", onBack) {
    item { FluidListGroup(glass = true) {
      FluidListRow("Fonti collegate", "Account e sezioni che autorizzi a leggere", onClick = { onOpen(ProfilePage.SOURCES) })
      FluidListDivider()
      FluidListRow("Aggiornamenti del profilo", "Modelli, frequenza e limiti per ogni area · spenti finché non configurati", onClick = { onOpen(ProfilePage.UPDATES) })
      FluidListDivider()
      FluidListRow("Informazioni escluse", "${state.exclusions.size} esclusioni · controlla cosa non verrà appreso", onClick = { onOpen(ProfilePage.EXCLUSIONS) })
      FluidListDivider()
      FluidListRow("Memorie e promemoria", "I ricordi aggiunti da te e i promemoria esistenti", onClick = onMemory)
    } }
    state.message?.let { item { Notice(it) } }
    if (state.busy) item { Notice("Operazione in corso…") }
    when {
      state.loading -> item { Notice("Caricamento del profilo…") }
      state.loadFailed -> item { Column { Notice("Profilo non disponibile. Non è possibile confermare se sia vuoto o aggiornato."); ProfileButton("Riprova", onRetry) } }
      state.items.none { it.status != "FORGOTTEN" } -> item { Notice("Il profilo è ancora vuoto. Collega una fonte e scegli esplicitamente come aggiornare il profilo. Le chat temporanee restano escluse.") }
      else -> listOf("USER_STATEMENT", "OBSERVATION", "INFERENCE").forEach { kind ->
        val rows = state.items.filter { (if (kind == "INFERENCE") it.kind !in setOf("USER_STATEMENT", "OBSERVATION") else it.kind == kind) && it.status != "FORGOTTEN" }
        if (rows.isNotEmpty()) {
          item { FluidSectionHeader(kindLabel(kind), detail = if (kind == "INFERENCE") "Interpretazioni da verificare. Le tue correzioni hanno precedenza." else null) }
          rows.forEach { item(key = it.id) { FluidListGroup(glass = true) {
            FluidListRow(fieldLabel(it.field), readableValue(it.value), meta = statusLabel(it.status), onClick = { onDetail(it.id) })
          } } }
        }
      }
    }
    item { Notice("Aria usa queste informazioni quando glielo chiedi. Nessun messaggio o notifica spontanea. Copertura e aggiornamento possono essere incompleti.") }
  }
}

@Composable
internal fun ProfileScaffold(title: String, subtitle: String, onBack: () -> Unit,
  content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
  FluidScreen(title = title, subtitle = subtitle, onBack = onBack, contentMaxWidth = 760.dp,
    modifier = Modifier.imePadding(), itemSpacing = 12.dp,
    ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Glow) },
    content = content)
}

@Composable internal fun Notice(text: String) {
  FluidInlineMessage(title = "Da sapere", message = text, tone = FluidTone.Info)
}

@Composable
internal fun ProfileButton(text: String, onClick: () -> Unit, style: FluidButtonStyle = FluidButtonStyle.Filled,
  enabled: Boolean = true, loading: Boolean = false) {
  FluidButton(text, onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    size = FluidButtonSize.Large, style = style, enabled = enabled, loading = loading,
    fillWidth = true, grip = FluidPressGrip.YieldsToScroll)
}

@Composable
internal fun ProfileDetail(detail: RetrievedProfile, busy: Boolean, onDismiss: () -> Unit,
  onCorrect: (String) -> Unit, onForget: () -> Unit, onExclude: (SourceGrant) -> Unit) {
  var correction by rememberSaveable(detail.item.id) { mutableStateOf(readableValue(detail.item.value)) }
  var confirmForget by remember { mutableStateOf(false) }
  FluidGlassModalPortal(visible = true, onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.Sheet, paneTitle = "Dettagli del profilo") {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp).imePadding(),
      contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      item { Text(fieldLabel(detail.item.field), style = MaterialTheme.typography.titleLarge) }
      item { Text("${kindLabel(detail.item.kind)} · ${statusLabel(detail.item.status)}", style = MaterialTheme.typography.bodyMedium) }
      item { Text(readableValue(detail.item.value), style = MaterialTheme.typography.bodyLarge) }
      detail.item.derivation?.let { item { Notice("Come è stata dedotta: $it") } }
      detail.item.limitations?.let { item { Notice("Limiti: $it") } }
      detail.item.generatedModel?.let { model -> item { Text("Modello: ${detail.item.generatedProvider.orEmpty()} / $model", style = MaterialTheme.typography.bodySmall) } }
      if (detail.evidence.isEmpty()) item { Notice(if (detail.item.kind == "USER_STATEMENT") "Valore dichiarato da te. Nessuna app sorgente." else "Provenienza o copertura non disponibile. Non considerare questa informazione verificata.") }
      detail.evidence.forEach { evidence -> item(key = evidence.id) {
        val grant = detail.grants.firstOrNull { it.id == evidence.grantId }
        val displayZone = profileTimeZone(evidence.timeZone)
        FluidListGroup {
          FluidListRow("Fonte e periodo", listOfNotNull(grant?.let { sourceLabel(it) }, "Dal ${dateLabel(evidence.periodStart, displayZone.zone)} al ${dateLabel(evidence.periodEnd, displayZone.zone)}", displayZone.label).joinToString("\n"))
          FluidListDivider()
          FluidListRow("Copertura e aggiornamento", "Osservato: ${dateLabel(evidence.observedAt, displayZone.zone)}\nSincronizzato: ${dateLabel(evidence.syncedAt, displayZone.zone)}\n${coverageLabel(evidence.coverage)} · ${freshnessLabel(evidence.freshness)}")
          FluidListDivider()
          FluidListRow("Fatto alla base", "${fieldLabel(evidence.field)}: ${readableValue(evidence.value, displayZone.zone)} ${evidence.unit.orEmpty()}")
        }
      } }
      item { FluidTextField(correction, { correction = it }, label = "Il valore corretto", singleLine = false, minHeight = 48.dp) }
      item { ProfileButton("Salva la mia correzione", { onCorrect(correction) }, enabled = !busy && correction.isNotBlank()) }
      item { ProfileButton("Dimentica questa informazione", { confirmForget = true }, style = FluidButtonStyle.Destructive, enabled = !busy) }
      detail.grants.forEach { grant -> item { ProfileButton("Escludi questa fonte dall’apprendimento", { onExclude(grant) }, style = FluidButtonStyle.Plain, enabled = !busy) } }
      item { ProfileButton("Chiudi", onDismiss, style = FluidButtonStyle.Plain) }
    }
  }
  if (confirmForget) FluidAlert(onDismissRequest = { confirmForget = false }, title = "Dimenticare e non riapprendere?",
    message = "Aria dimenticherà questa categoria e le deduzioni collegate. Non la apprenderà di nuovo finché non rimuovi l’esclusione. I dati nelle app sorgenti non cambiano.",
    actions = listOf(FluidAlertAction("Annulla", onClick = { confirmForget = false }),
      FluidAlertAction("Dimentica", onClick = { confirmForget = false; onForget() })))
}

internal fun sourceLabel(grant: SourceGrant): String = listOfNotNull(
  if (grant.app.contains("fluidify", true) || grant.app.contains("square", true)) "Fluidify" else "ClasseViva",
  grant.account, grant.student, grant.year, schoolSections[grant.area] ?: musicSections[grant.area]
).joinToString(" · ")
internal fun coverageLabel(value: String) = when (value.uppercase()) {
  "COMPLETE", "FULL" -> "Copertura dichiarata completa dalla fonte"
  "PARTIAL" -> "Copertura parziale"
  else -> "Copertura non verificata"
}
internal fun freshnessLabel(value: String) = when (value.uppercase()) {
  "FRESH" -> "Aggiornamento dichiarato dalla fonte"
  "STALE" -> "Dati da aggiornare"
  else -> "Freschezza non verificata"
}

@Composable
private fun ExclusionsScreen(state: ProfileState, onBack: () -> Unit, onReenable: (String) -> Unit, onRefresh: () -> Unit) {
  var pending by remember { mutableStateOf<ProfileExclusion?>(null) }
  ProfileScaffold("Informazioni escluse", "Queste informazioni non verranno apprese di nuovo.", onBack) {
    state.message?.let { item { Notice(it) } }
    item { ProfileButton("Aggiorna elenco", onRefresh, enabled = !state.busy, style = FluidButtonStyle.Plain) }
    if (state.exclusions.isEmpty()) item { Notice("Non ci sono esclusioni salvate.") }
    state.exclusions.forEach { row -> item(key = row.key) { FluidListGroup {
      FluidListRow(row.field?.let(::fieldLabel) ?: "Fonte esclusa", listOfNotNull(
        state.grants.firstOrNull { it.id == row.grantId }?.let(::sourceLabel),
        if (row.unmapped) "Apprendimento sospeso: esclusione da chiarire" else if (row.recordKey != null) "Singolo elemento escluso dai prossimi apprendimenti" else "Esclusa dai prossimi apprendimenti").joinToString("\n"), onClick = { pending = row })
    } } }
  }
  pending?.let { row -> FluidAlert(onDismissRequest = { pending = null }, title = "Consenti di apprendere di nuovo?",
    message = "Rimuove solo questa esclusione. Non ricollega fonti e non accende gli aggiornamenti automatici.",
    actions = listOf(FluidAlertAction("Annulla", onClick = { pending = null }), FluidAlertAction("Consenti", onClick = { pending = null; onReenable(row.key) }))) }
}
