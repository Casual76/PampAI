package dev.pampa.pampai.feature.assistant.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.research.*
import dev.pampa.pampai.core.assistant.runtime.ModelSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

internal data class ProfileState(
  val loading: Boolean = true, val items: List<ProfileItem> = emptyList(),
  val areas: List<AreaConfiguration> = emptyList(), val exclusions: List<ProfileExclusion> = emptyList(),
  val candidates: List<SourceCandidate> = emptyList(), val detail: RetrievedProfile? = null,
  val grants: List<SourceGrant> = emptyList(), val plans: List<AreaReadPlan> = emptyList(),
  val connectedReads: List<ResearchRead> = emptyList(), val areaDraft: AreaDraft? = null,
  val busy: Boolean = false, val message: String? = null,
  val loadFailed: Boolean = false,
  val caps: List<BudgetCap> = emptyList(),
  val schedules: List<AreaScheduleStatus> = emptyList(),
)

internal fun profileFailure(error: Throwable): String = when ((error as? ResearchUnavailable)?.code) {
  "ACCOUNT_MISMATCH", "AUTHORIZATION_CHANGED", "SCHOOL_SCOPE_MISMATCH" -> "L’account o l’autorizzazione è cambiata. Scegli di nuovo la fonte; nessun account è stato sostituito."
  "LOCAL_REVOKED", "SOURCE_REVOKED" -> "Fonte scollegata. Per usarla serve un nuovo collegamento esplicito."
  "BOUNDS_UNSUPPORTED" -> "Aggiornamenti sospesi: i limiti del modello non sono verificati. Scegli un modello supportato."
  else -> when (error) {
    is java.time.format.DateTimeParseException -> "Controlla le date: usa il formato AAAA-MM-GG."
    is NumberFormatException, is ArithmeticException -> "Controlla i numeri e il tetto di spesa: il valore non è valido."
    is IllegalArgumentException -> error.message ?: "Controlla i campi richiesti."
    else -> "Operazione non completata. La fonte può essere offline o richiedere l’accesso. Riprova senza cambiare account."
  }
}

/** No raw DAO, conversation draft or active-chat model writes are permitted here. */
@HiltViewModel
class ProfileViewModel @Inject constructor(
  private val profile: ResearchProfile,
  private val sources: ResearchSources,
  val models: ModelSelection,
  private val setup: ResearchSetup,
  private val foundation: DurableFoundation,
  private val scheduler: ResearchScheduler,
  private val connections: ResearchConnections,
) : ViewModel() {
  private val mutable = MutableStateFlow(ProfileState())
  private val actions = Mutex()
  internal val state: StateFlow<ProfileState> = mutable.asStateFlow()
  private var observer: kotlinx.coroutines.Job? = null
  init {
    viewModelScope.launch {
      try { connections.reads.collect { rows -> mutable.update { it.copy(connectedReads = rows) } } }
      catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { mutable.update { it.copy(message = profileFailure(error)) } }
    }
    viewModelScope.launch {
      try { scheduler.statuses.collect { rows -> mutable.update { it.copy(schedules = rows) } } }
      catch (cancel: CancellationException) { throw cancel }
      catch (_: Exception) { mutable.update { it.copy(message = "Stato degli aggiornamenti non disponibile. Nessuna esecuzione confermata.") } }
    }
    reloadProfile()
    action { refreshExclusionsNow(); val rows = foundation.grants(); mutable.update { it.copy(grants = rows) } }
    viewModelScope.launch {
      try { combine(setup.plans, setup.caps) { rows, caps -> rows to caps }.collect { (rows, caps) -> mutable.update { it.copy(plans = rows, caps = caps) } } }
      catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { mutable.update { it.copy(message = profileFailure(error)) } }
    }
  }
  fun reloadProfile() {
    observer?.cancel()
    mutable.update { it.copy(loading = true, loadFailed = false) }
    observer = viewModelScope.launch {
      try {
        combine(profile.management, profile.settings, profile.grants, profile.exclusions) { items, settings, grants, exclusions ->
          ProfileState(loading = false, items = items, areas = settings, grants = grants, exclusions = exclusions)
        }.collect { fresh ->
          mutable.update { it.copy(loading = false, loadFailed = false, items = fresh.items, areas = fresh.areas, grants = fresh.grants, exclusions = fresh.exclusions) }
        }
      } catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { mutable.update { it.copy(loading = false, loadFailed = true, message = profileFailure(error)) } }
    }
  }
  private fun action(block: suspend () -> Unit) = viewModelScope.launch {
    actions.withLock {
      mutable.update { it.copy(busy = true, message = null) }
      try { block() }
      catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { mutable.update { it.copy(message = profileFailure(error)) } }
      finally { mutable.update { it.copy(busy = false) } }
    }
  }
  fun discover() = action { val found = sources.discover(); mutable.update { it.copy(candidates = found) } }
  internal fun detail(id: String) = action { mutable.update { it.copy(detail = null) }; val result = profile.detail(id); mutable.update { it.copy(detail = result, message = if (result == null) "Questa informazione non è più disponibile." else null) } }
  fun dismissDetail() { mutable.update { it.copy(detail = null) } }
  internal fun correct(item: ProfileItem, value: String) = action {
    require(value.isNotBlank()) { "Scrivi il valore corretto." }
    profile.correct("correction:${java.util.UUID.randomUUID()}", item.field, value)
    mutable.update { it.copy(detail = null, message = "Correzione salvata. La tua dichiarazione ha precedenza sulle deduzioni.") }
  }
  internal fun forget(item: ProfileItem) = action {
    profile.forget(item.field); refreshExclusionsNow()
    mutable.update { it.copy(detail = null, message = "Informazione dimenticata ed esclusa dai prossimi apprendimenti. Le app sorgenti non cambiano.") }
  }
  internal fun exclude(grant: SourceGrant) = action {
    profile.exclude(ProfileExclusion("source:${grant.id}", null, grant.id, null, false)); refreshExclusionsNow()
    mutable.update { it.copy(message = "Fonte esclusa dall’apprendimento. I dati dell’app sorgente restano invariati.") }
  }
  internal fun reenable(key: String) = action {
    profile.reenable(key); refreshExclusionsNow()
    mutable.update { it.copy(message = "Esclusione rimossa. Fonti e aggiornamenti restano da autorizzare separatamente.") }
  }
  private suspend fun refreshExclusionsNow() { val rows = profile.exclusions(); mutable.update { it.copy(exclusions = rows) } }
  fun refreshExclusions() = action { refreshExclusionsNow() }
  internal fun connect(draft: SourceDraft, reconnect: Boolean, onConnected: (ResearchRead) -> Unit) = action {
    val read = draft.read(); sources.connect(read, reconnect); onConnected(read)
    val grants = foundation.grants()
    mutable.update { it.copy(grants = grants, connectedReads = (it.connectedReads + read).distinct(), message = "Fonte collegata per la sola sezione scelta. Gli aggiornamenti automatici sono ancora spenti.") }
  }
  internal fun renew(reads: List<ResearchRead>) = action {
    sources.renew(reads); mutable.update { it.copy(message = "Autorizzazione ricontrollata. Account e sezioni restano quelli scelti.") }
  }
  internal fun revoke(grantId: String) = action {
    sources.revoke(grantId); val grants = foundation.grants(); mutable.update { it.copy(grants = grants, message = "Fonte scollegata. Gli aggiornamenti che ne dipendono non possono proseguire.") }
  }
  fun refreshGrants() = action { val rows = foundation.grants(); mutable.update { it.copy(grants = rows) } }
  internal fun editArea(area: String) = action {
    val plan = setup.plan(area); val configuration = setup.configuration(area)
    val zone = java.time.ZoneId.systemDefault()
    fun date(value: Long) = java.time.Instant.ofEpochMilli(value).atZone(zone).toLocalDate().toString()
    mutable.update { it.copy(areaDraft = AreaDraft(area, plan?.policy(), plan?.reads.orEmpty(),
      configuration?.requestCap?.toString() ?: "0", configuration?.tokenCap?.toString() ?: "0",
      configuration?.moneyCap?.let { java.math.BigDecimal.valueOf(it, 6).toPlainString() } ?: "0", configuration != null && configuration.moneyCap == null,
      configuration?.cadenceMillis?.div(3_600_000)?.toString() ?: "24", configuration?.wifiOnly ?: true,
      configuration?.batteryNotLow ?: true, configuration?.enabled ?: false,
      configuration?.windowStart?.let(::date) ?: "", configuration?.windowEnd?.let(::date) ?: "", plan?.maxRecords?.toString() ?: "100")) }
  }
  internal fun changeArea(draft: AreaDraft?) { mutable.update { it.copy(areaDraft = draft) } }
  internal fun saveArea(draft: AreaDraft) = action {
    val configuration = draft.configuration(); val policy = requireNotNull(draft.policy)
    // A partial multi-store save stays off; invalidation precedes changing scopes/models.
    setup.configure(configuration.copy(enabled = false), emptyList())
    setup.savePlan(AreaReadPlan(draft.area, reads = draft.reads, provider = policy.identity.provider.name,
      model = policy.identity.model, effort = policy.effort?.name, helperProvider = policy.helper?.provider?.name,
      helperModel = policy.helper?.model, profilingConsent = true, maxRecords = draft.maxRecords.toInt()))
    setup.configure(configuration, listOfNotNull(AreaModel(draft.area, policy.identity.provider.name, policy.identity.model),
      policy.helper?.let { AreaModel(draft.area, it.provider.name, it.model) }))
    scheduler.reconcile()
    mutable.update { it.copy(areaDraft = null, message = "Scelte salvate. Controlla lo stato della programmazione: autorizzare non significa aver completato un aggiornamento.") }
  }
  internal fun disable(area: String) = action {
    val configuration = setup.configuration(area) ?: AreaConfiguration(area)
    setup.configure(configuration.copy(enabled = false), setup.models(area))
    scheduler.reconcile()
    mutable.update { it.copy(message = "Aggiornamenti di quest’area disattivati.") }
  }
  internal fun saveCap(cap: BudgetCap) = action {
    require(cap.start > System.currentTimeMillis()) { "Scegli una finestra futura: il traffico precedente può avere un costo sconosciuto." }
    setup.configureCap(cap)
    scheduler.reconcile()
    mutable.update { it.copy(message = "Limiti salvati per la finestra scelta. Il tetto globale vale anche per le richieste in chat. Non è una quota del provider.") }
  }
}
