package dev.pampa.pampai.feature.assistant.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.*
import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.research.AreaScheduleStatus
import dev.pampa.pampai.core.assistant.runtime.*
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ui.page.FluidPageHost
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*

@Composable
internal fun UpdatesRoute(onBack: () -> Unit, viewModel: ProfileViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val catalog by viewModel.models.state.collectAsStateWithLifecycle()
  var caps by remember { mutableStateOf<Boolean?>(null) }
  var picker by remember { mutableStateOf(false) }
  var helper by remember { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  val draft = state.areaDraft
  // Conserva il contenuto uscente per la molla, senza un BackHandler che rubi il gesto predittivo.
  var leavingDraft by remember { mutableStateOf(draft) }
  SideEffect { if (draft != null) leavingDraft = draft }
  FluidPageHost(stack = if (draft == null) listOf("areas") else listOf("areas", "edit-${draft.area}"),
    onBack = { viewModel.changeArea(null) }, modifier = Modifier.fillMaxSize()) { page ->
    if (page == "areas") {
      ProfileScaffold("Aggiornamenti del profilo", "Decidi cosa può imparare Aria. Ogni area ha scelte e limiti propri.", onBack) {
        state.message?.let { item { Notice(it) } }
        item { FluidListGroup(glass = true) {
          FluidListRow("Limiti globali", "Se configurati valgono anche per le richieste in chat", onClick = { caps = false })
          FluidListDivider()
          FluidListRow("Limiti degli aggiornamenti", "Un limite aggiuntivo per tutto il lavoro automatico", onClick = { caps = true })
        } }
        state.caps.forEach { cap -> item(key = "cap-${cap.id}") { FluidListGroup {
          FluidListRow(if (cap.backgroundOnly) "Limite salvato per aggiornamenti" else "Limite globale salvato",
            "${cap.requests} richieste · ${cap.tokens} token\nDal ${dateLabel(cap.start)} al ${dateLabel(cap.end)}\n" +
              (cap.money?.let { "Tetto di spesa: ${java.math.BigDecimal.valueOf(it, 6).toPlainString()} · valuta da verificare" } ?: "Tetto di spesa disattivato esplicitamente"))
        } } }
        item { Notice("Prima di configurare limiti globali, le richieste esplicite in chat non hanno un massimale monetario garantito. I limiti per area si aggiungono a quelli globali e degli aggiornamenti.") }
        val areas = (state.grants.map { it.area } + state.areas.map { it.area }).distinct()
        if (areas.isEmpty()) item { Notice("Collega prima una sezione in Fonti collegate. Nessun aggiornamento è attivo.") }
        areas.forEach { area -> item(key = area) {
          val config = state.areas.firstOrNull { it.area == area }
          val status = state.schedules.firstOrNull { it.area == area }
          AreaUpdatesRow(area, config, status, { viewModel.editArea(area) }, { viewModel.disable(area) })
        } }
        item { Notice("Tariffe e valuta non sono verificate: con un tetto di spesa il lavoro automatico resta sospeso. Anche senza tetto di spesa servono limiti del modello verificati. Nessun prezzo o quota gratuita viene presunto.") }
        item { Notice("Android esegue gli aggiornamenti quando possibile, rispettando rete e batteria: l’orario non è garantito. Nessuna notifica o messaggio spontaneo.") }
      }
    } else {
      val editing = draft ?: leavingDraft
      if (editing != null) AreaSetupScreen(editing, state, onBack = { viewModel.changeArea(null) }, onChange = viewModel::changeArea,
        onModel = { helper = false; picker = true }, onHelper = { helper = editing.policy != null; picker = true }, onSave = { viewModel.saveArea(editing) })
    }
  }
  if (picker && draft != null) FluidGlassModalPortal(visible = true, onDismissRequest = { picker = false }, presentation = FluidGlassModalPresentation.Sheet, paneTitle = "Modello per quest’area") {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      item { Text(if (helper) "Supporto autorizzato" else "Modello principale dell’area", style = MaterialTheme.typography.titleLarge) }
      item { Notice("Questa scelta riguarda solo il profilo. Il modello della conversazione non cambia. Compatibilità con i limiti da verificare prima di ogni esecuzione.") }
      item { FluidTextField(query, { query = it }, label = "Cerca in tutti i modelli configurati", minHeight = 48.dp) }
      if (catalog.options.isEmpty()) item { Notice("Configura e verifica un provider nelle impostazioni. Nessun modello viene scelto automaticamente.") }
      catalog.options.filter { (it.label + it.identity.key).contains(query, true) }.forEach { option -> item(key = option.identity.key) {
        FluidListGroup { FluidListRow(option.label, "${option.identity.provider.id} / ${option.identity.model}", onClick = {
          val policy = if (helper) draft.policy?.copy(helper = option.identity) else ModelPolicy(option.identity, draft.policy?.effort, draft.policy?.helper)
          viewModel.changeArea(draft.copy(policy = policy)); picker = false
        }) }
      } }
      item { ProfileButton("Chiudi", { picker = false }, style = FluidButtonStyle.Plain) }
    }
  }
  caps?.let { background -> CapSheet(background, state.caps.filter { it.backgroundOnly == background }.maxByOrNull { it.start }, state.busy, state.message, { caps = null }) { viewModel.saveCap(it) } }
}

@Composable
internal fun AreaSetupScreen(draft: AreaDraft, state: ProfileState, onBack: () -> Unit,
  onChange: (AreaDraft) -> Unit, onModel: () -> Unit, onHelper: () -> Unit, onSave: () -> Unit) {
  ProfileScaffold("Configura ${schoolSections[draft.area] ?: musicSections[draft.area] ?: "area"}", "Le scelte valgono solo dopo il salvataggio esplicito.", onBack) {
    state.message?.let { item { Notice(it) } }
    item { FluidSectionHeader("Cosa può leggere e imparare", detail = "Seleziona i collegamenti esatti. Le chat temporanee sono sempre escluse.") }
    val available = (state.plans.flatMap { it.reads } + state.connectedReads + draft.reads).distinct()
      .filter { it.selection.area == draft.area && state.grants.any { grant -> grant.id == it.grantId && grant.enabled } }
    if (available.isEmpty()) item { Notice("Apri Fonti collegate e conferma questa sezione e il periodo di lettura. Non viene dedotto un ambito dall’account.") }
    available.forEach { read -> item(key = read.grantId + read.arguments.toString()) { FluidListGroup {
      FluidToggleRow(listOfNotNull(read.selection.account, read.selection.student, read.selection.year).joinToString(" · "), read in draft.reads,
        { chosen -> onChange(draft.copy(reads = if (chosen) (draft.reads + read).distinct() else draft.reads - read)) },
        subtitle = scopeLabel(read))
    } } }
    item { FluidTextField(draft.maxRecords, { onChange(draft.copy(maxRecords = it)) }, label = "Elementi massimi da esplorare per aggiornamento", minHeight = 48.dp) }
    item { FluidSectionHeader("Modelli autorizzati") }
    item { FluidListGroup(glass = true) {
      FluidListRow("Principale", draft.policy?.identity?.let { "${it.provider.id} / ${it.model}" } ?: "Nessuno · scelta obbligatoria", onClick = onModel)
      FluidListDivider()
      FluidListRow("Supporto facoltativo", draft.policy?.helper?.let { "${it.provider.id} / ${it.model}" } ?: "Nessuno autorizzato", onClick = onHelper)
      if (draft.policy?.helper != null) { FluidListDivider(); FluidListRow("Rimuovi supporto", "Non autorizzare chiamate al modello di supporto", onClick = { onChange(draft.copy(policy = draft.policy.copy(helper = null))) }) }
    } }
    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      (listOf<ThinkingLevel?>(null) + ThinkingLevel.entries).forEach { effort ->
        FluidChip(selected = draft.policy?.effort == effort, onClick = { draft.policy?.let { onChange(draft.copy(policy = it.copy(effort = effort))) } },
          label = when (effort) { null -> "Predefinito"; ThinkingLevel.LOW -> "Basso"; ThinkingLevel.MEDIUM -> "Medio"; ThinkingLevel.HIGH -> "Alto" }, modifier = Modifier.heightIn(min = 48.dp))
      }
    } }
    item { Notice("Limiti del modello non ancora verificati per questa configurazione. Salvare un’autorizzazione non conferma che il lavoro possa essere eseguito.") }
    item { FluidSectionHeader("Frequenza e condizioni") }
    item { FluidTextField(draft.cadenceHours, { onChange(draft.copy(cadenceHours = it)) }, label = "Intervallo in ore", minHeight = 48.dp) }
    item { FluidListGroup {
      FluidToggleRow("Solo Wi-Fi", draft.wifiOnly, { onChange(draft.copy(wifiOnly = it)) })
      FluidListDivider()
      FluidToggleRow("Batteria non scarica", draft.batteryNotLow, { onChange(draft.copy(batteryNotLow = it)) })
    } }
    item { FluidSectionHeader("Limiti per quest’area", detail = "Finestra esplicita. La frequenza non azzera i consumi e non rappresenta la quota del provider.") }
    item { BudgetFields(draft.requests, draft.tokens, draft.money, draft.moneyDisabled, draft.startDate, draft.endDate,
      { onChange(draft.copy(requests = it)) }, { onChange(draft.copy(tokens = it)) }, { onChange(draft.copy(money = it)) },
      { onChange(draft.copy(moneyDisabled = it)) }, { onChange(draft.copy(startDate = it)) }, { onChange(draft.copy(endDate = it)) }) }
    item { FluidListGroup { FluidToggleRow("Autorizzo l’aggiornamento di quest’area", draft.enabled, { onChange(draft.copy(enabled = it)) }, subtitle = "Consenti di ricavare fatti e deduzioni dalle sole letture selezionate. Esecuzione subordinata a budget, limiti verificati e programmazione.") } }
    item { ProfileButton("Salva le mie scelte", onSave, enabled = !state.busy, loading = state.busy) }
  }
}

internal fun scopeLabel(read: dev.pampa.pampai.core.assistant.research.ResearchRead): String {
  val section = schoolSections[read.selection.area] ?: musicSections[read.tool] ?: "Sezione scelta"
  val from = (read.arguments["from"] as? JsonPrimitive)?.longOrNull
  val until = (read.arguments["until"] as? JsonPrimitive)?.longOrNull
  val zone = ZoneId.systemDefault()
  return section + if (from != null && until != null) " · ${dateLabel(from, zone)} — ${dateLabel(until, zone)} (${zone.id}, fine esclusa)" else " · solo l’ambito selezionato"
}

@Composable
private fun BudgetFields(requests: String, tokens: String, money: String, moneyDisabled: Boolean, start: String, end: String,
  onRequests: (String) -> Unit, onTokens: (String) -> Unit, onMoney: (String) -> Unit, onMoneyDisabled: (Boolean) -> Unit,
  onStart: (String) -> Unit, onEnd: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    FluidTextField(requests, onRequests, label = "Numero massimo di richieste", minHeight = 48.dp)
    FluidTextField(tokens, onTokens, label = "Token massimi totali", minHeight = 48.dp)
    FluidListGroup { FluidToggleRow("Uso solo limiti di richieste e token", moneyDisabled, onMoneyDisabled,
      subtitle = "Disattiva esplicitamente il tetto di spesa. Non garantisce un costo massimo.") }
    if (!moneyDisabled) {
      FluidTextField(money, onMoney, label = "Tetto di spesa · valuta da verificare", minHeight = 48.dp)
      Notice("Senza tariffa e valuta verificate, il tetto di spesa sospende il lavoro automatico.")
    }
    FluidTextField(start, onStart, label = "Inizio finestra (AAAA-MM-GG)", minHeight = 48.dp)
    FluidTextField(end, onEnd, label = "Fine esclusa (AAAA-MM-GG)", minHeight = 48.dp)
  }
}

@Composable
private fun CapSheet(background: Boolean, existing: BudgetCap?, busy: Boolean, message: String?, onDismiss: () -> Unit, onSave: (BudgetCap) -> Unit) {
  fun date(value: Long?) = value?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()
  var requests by rememberSaveable(background) { mutableStateOf(existing?.requests?.toString() ?: "0") }; var tokens by rememberSaveable(background) { mutableStateOf(existing?.tokens?.toString() ?: "0") }
  var money by rememberSaveable(background) { mutableStateOf(existing?.money?.let { java.math.BigDecimal.valueOf(it, 6).toPlainString() } ?: "0") }; var noMoney by rememberSaveable(background) { mutableStateOf(existing != null && existing.money == null) }
  var start by rememberSaveable(background) { mutableStateOf(date(existing?.start)) }; var end by rememberSaveable(background) { mutableStateOf(date(existing?.end)) }
  var error by remember { mutableStateOf<String?>(null) }
  FluidGlassModalPortal(visible = true, onDismissRequest = onDismiss, presentation = FluidGlassModalPresentation.Sheet, paneTitle = "Limiti di utilizzo") {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      item { Text(if (background) "Limiti degli aggiornamenti" else "Limiti globali, inclusa la chat", style = MaterialTheme.typography.titleLarge) }
      item { Notice("Scegli una finestra futura per non includere in silenzio traffico precedente dal costo sconosciuto. La fine della finestra sospende il lavoro; nessun rinnovo di budget è implicito.") }
      item { BudgetFields(requests, tokens, money, noMoney, start, end, { requests = it }, { tokens = it }, { money = it }, { noMoney = it }, { start = it }, { end = it }) }
      (error ?: message)?.let { item { Notice(it) } }
      item { ProfileButton("Salva questi limiti", {
        try {
          fun date(value: String) = LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
          val r = requireNotNull(requests.toLongOrNull()); val t = requireNotNull(tokens.toLongOrNull())
          val s = date(start); val e = date(end); require(r > 0 && t > 0 && e > s)
          onSave(BudgetCap(existing?.id ?: if (background) "profile-background" else "profile-global", background, s, e, r, t, if (noMoney) null else moneyUnits(money)))
          error = null
        } catch (_: Exception) { error = "Controlla numeri positivi, date e tetto di spesa." }
      }, enabled = !busy) }
      item { ProfileButton("Chiudi", onDismiss, style = FluidButtonStyle.Plain) }
    }
  }
}

@Composable
internal fun AreaUpdatesRow(area: String, config: AreaConfiguration?, status: AreaScheduleStatus?, onEdit: () -> Unit, onDisable: () -> Unit) {
  FluidListGroup(glass = true) {
    FluidListRow(schoolSections[area] ?: musicSections[area] ?: "Area del profilo", scheduleLabel(status, config),
      meta = "Primo momento utile: ${dateLabel(status?.nextEligibleAt)}\nUltimo tentativo: ${dateLabel(status?.lastAttemptAt)}\nUltimo aggiornamento completato: ${dateLabel(status?.lastSuccessAt)}", onClick = onEdit)
    if (config?.enabled == true) {
      FluidListDivider(); FluidListRow("Disattiva quest’area", "Non verrà aggiornata finché non la riattivi", onClick = onDisable)
    }
  }
}
