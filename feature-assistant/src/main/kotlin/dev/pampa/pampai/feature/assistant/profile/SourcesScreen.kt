package dev.pampa.pampai.feature.assistant.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.*
import dev.pampa.pampai.core.assistant.durable.SourceGrant
import dev.pampa.pampai.core.assistant.research.SourceCandidate

@Composable
internal fun SourcesScreen(state: ProfileState, onBack: () -> Unit, viewModel: ProfileViewModel) {
  var draft by remember { mutableStateOf<SourceDraft?>(null) }
  var reconnect by remember { mutableStateOf(false) }
  var revoke by remember { mutableStateOf<SourceGrant?>(null) }
  var unavailable by remember { mutableStateOf<SourceCandidate?>(null) }
  var launchError by remember { mutableStateOf(false) }
  val context = LocalContext.current
  ProfileScaffold("Fonti collegate", "Scegli account e sezioni. Collegare una fonte non accende gli aggiornamenti.", onBack) {
    state.message?.let { item { Notice(it) } }
    item { ProfileButton("Cerca app installate", { viewModel.discover() }, enabled = !state.busy, loading = state.busy) }
    item { Notice("Le app installate compaiono anche quando richiedono un aggiornamento. Per leggere dati privati serve una versione compatibile, l’accesso all’account e il collegamento esplicito. Le azioni già supportate conservano i controlli previsti.") }
    state.candidates.forEach { candidate -> item { FluidListGroup(glass = true) {
      FluidListRow(if (candidate.domain == "cv") "ClasseViva" else "Fluidify",
        sourceCandidateStatus(candidate),
        onClick = {
          if (candidate.supported) { draft = SourceDraft(candidate); reconnect = false }
          else { launchError = false; unavailable = candidate }
        })
    } } }
    item { FluidSectionHeader("Collegamenti salvati", detail = "Ogni collegamento riguarda solo l’account e la sezione scelti.") }
    if (state.grants.isEmpty()) item { Notice("Nessun collegamento salvato.") }
    state.grants.forEach { grant -> item(key = grant.id) { FluidListGroup {
      FluidListRow(sourceLabel(grant), if (grant.enabled) "Collegato · autorizzazione da ricontrollare prima dell’uso" else "Scollegato")
      FluidListDivider()
      FluidListRow(if (grant.enabled) "Ricontrolla autorizzazione" else "Ricollega esplicitamente", "Conferma di nuovo account e sezione", onClick = {
        val reads = (state.plans.flatMap { it.reads } + state.connectedReads).filter { it.grantId == grant.id }.distinct()
        val candidate = state.candidates.firstOrNull { it.supported && it.packageName == grant.app && it.authority == grant.authority && it.account == grant.account && it.student == grant.student }
        if (grant.enabled && reads.isNotEmpty()) viewModel.renew(reads)
        else if (candidate != null) { draft = SourceDraft(candidate, grant.account, grant.student.orEmpty(), grant.year.orEmpty(), grant.area); reconnect = !grant.enabled }
        else viewModel.discover()
      })
      if (grant.enabled) { FluidListDivider(); FluidListRow("Scollega", "Revoca il collegamento locale e ferma il lavoro che ne dipende", onClick = { revoke = grant }) }
    } } }
    item { Notice("Cronologia musicale: gli import possono avere intervalli mancanti e durate sconosciute. Non viene promessa una cronologia remota completa. L’anno scolastico scelto qui non cambia l’anno nell’app ClasseViva.") }
  }
  unavailable?.let { candidate ->
    FluidAlert(onDismissRequest = { unavailable = null }, title = sourceCandidateStatus(candidate),
      message = sourceCandidateHelp(candidate) + if (launchError) "\nNon è stato possibile aprire l’app." else "",
      actions = listOf(FluidAlertAction("Chiudi", { unavailable = null }), FluidAlertAction("Apri app", {
        val intent = context.packageManager.getLaunchIntentForPackage(candidate.packageName)
        launchError = intent == null || runCatching { context.startActivity(intent) }.isFailure
        if (!launchError) unavailable = null
      })))
  }
  draft?.let { value ->
    FluidGlassModalPortal(visible = true, onDismissRequest = { draft = null }, presentation = FluidGlassModalPresentation.Sheet, paneTitle = "Collega una sezione") {
      androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Conferma l’identità della fonte", style = MaterialTheme.typography.titleLarge) }
        item { Notice("Account disponibile: ${value.candidate.account ?: "non disponibile"}" + (value.candidate.student?.let { " · Studente: $it" } ?: "")) }
        item { FluidTextField(value.account, { draft = value.copy(account = it) }, label = "Account esatto", minHeight = 48.dp) }
        if (value.candidate.domain == "cv") {
          item { FluidTextField(value.student, { draft = value.copy(student = it) }, label = "Studente esatto", minHeight = 48.dp) }
          item { FluidTextField(value.year, { draft = value.copy(year = it) }, label = "Anno scolastico, es. 2026-2027", minHeight = 48.dp) }
        }
        item { FluidListGroup { (if (value.candidate.domain == "cv") schoolSections else musicSections).forEach { (key, label) ->
          FluidListRow(label, if (value.section == key) "Selezionata" else "Non selezionata", selected = value.section == key, onClick = { draft = value.copy(section = key) })
        } } }
        if (value.candidate.domain != "cv" && value.section.startsWith("history_") && value.section != "history_coverage") {
          item { FluidTextField(value.from, { draft = value.copy(from = it) }, label = "Dal giorno (AAAA-MM-GG)", minHeight = 48.dp) }
          item { FluidTextField(value.until, { draft = value.copy(until = it) }, label = "Fino al giorno escluso (AAAA-MM-GG)", minHeight = 48.dp) }
        }
        if (value.section.startsWith("playlist_")) item { FluidTextField(value.playlistId, { draft = value.copy(playlistId = it) }, label = "Identificativo della playlist scelta", minHeight = 48.dp) }
        if (value.candidate.domain == "cv") item { FluidListGroup {
          FluidToggleRow("Consenti aggiornamento tecnico", value.refresh, { draft = value.copy(refresh = it) }, subtitle = "Solo questa sezione, se supportato dalla fonte")
        } }
        state.message?.let { item { Notice(it) } }
        item { ProfileButton(if (reconnect) "Ricollega questa sezione" else "Collega questa sezione", {
          viewModel.connect(value, reconnect) { draft = null }
        }, enabled = !state.busy) }
        item { ProfileButton("Annulla", { draft = null }, style = FluidButtonStyle.Plain) }
      }
    }
  }
  revoke?.let { grant -> FluidAlert(onDismissRequest = { revoke = null }, title = "Scollegare questa fonte?", message = "Aria non potrà più usare questo collegamento. I dati nell’app sorgente restano invariati.", actions = listOf(
    FluidAlertAction("Annulla", { revoke = null }), FluidAlertAction("Scollega", { revoke = null; viewModel.revoke(grant.id) }, FluidAlertAction.Emphasis.Destructive))) }
}
