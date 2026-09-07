package dev.pampa.pampai.core.assistant.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.orchestrator.AiDiagnosticsLog
import dev.antigravity.fluidengine.ai.orchestrator.AiOrchestrator
import dev.antigravity.fluidengine.ai.orchestrator.AiOrchestratorConfig
import dev.antigravity.fluidengine.ai.orchestrator.AskInput
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantFailure
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.Conversation
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.provider.ChatRequest
import dev.antigravity.fluidengine.ai.provider.ContentPart
import dev.antigravity.fluidengine.ai.provider.Message
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderFactory
import dev.antigravity.fluidengine.ai.provider.ReadyProvider
import dev.antigravity.fluidengine.ai.provider.ReasoningLevel
import dev.antigravity.fluidengine.ai.provider.displayName
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.antigravity.fluidengine.ai.tools.resolvedCategory
import dev.pampa.pampai.core.assistant.attachments.AttachmentReader
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.db.MemoryRepository
import dev.pampa.pampai.core.assistant.prompt.AriaChips
import dev.pampa.pampai.core.assistant.prompt.PreRouter
import dev.pampa.pampai.core.assistant.prompt.PromptBuilder
import dev.pampa.pampai.core.assistant.prompt.PromptContext
import dev.pampa.pampai.core.assistant.screen.ScreenContextStore
import dev.pampa.pampai.core.assistant.music.FluidifyClient
import dev.pampa.pampai.core.assistant.permissions.PermissionGate
import dev.pampa.pampai.core.assistant.reminders.ReminderRepository
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.core.assistant.tools.Dates
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.RegistryHolder
import dev.pampa.pampai.core.assistant.tools.Surface
import dev.pampa.pampai.core.assistant.usage.UsageRepository
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Com'e' finita una domanda, per la notifica e per chi ha chiamato. */
data class ExecutionResult(val conversationId: Long, val question: String, val answer: String?, val failure: FailureKind?, val cancelled: Boolean)

/**
 * Una domanda dall'inizio alla fine: la conversazione su disco, gli allegati, il contesto dei tool,
 * il prompt, il pre-router, l'orchestratore dell'engine sul catalogo di adesso, e ogni passo
 * scritto in Room man mano — cosi' un processo che muore lascia una domanda "fallita" e non un
 * buco, e chi riapre l'app trova la risposta o il lavoro in corso.
 */
@Singleton
class AssistantEngine @Inject constructor(
  @ApplicationContext private val context: Context,
  private val runtime: AssistantRuntime,
  private val registryHolder: RegistryHolder,
  private val providers: ProviderFactory,
  private val settingsStore: AiSettingsStore,
  private val pampaiSettings: PampaiSettingsStore,
  private val conversations: ConversationsRepository,
  private val memory: MemoryRepository,
  private val attachments: AttachmentReader,
  private val gate: PampaiConfirmationGate,
  private val diagnostics: AiDiagnosticsLog,
  private val usage: UsageRepository,
  private val http: AiHttp,
  private val screen: ScreenContextStore,
  private val permissions: PermissionGate,
  private val reminders: ReminderRepository,
  private val fluidify: FluidifyClient,
) {

  internal fun screenNote(request: AssistantRequest): String? = screenNoteOf(screen.current, request) { pkg ->
    pkg?.let { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }
  }

  /** Le conversazioni in memoria per processo: il traffico tool dell'ultima domanda vive qui, non su disco. */
  private val memoryConversations = ConcurrentHashMap<Long, Conversation>()

  /**
   * Il lavoro gira sotto un [Job] figlio: e' quello che [AssistantRuntime.cancel] ferma. Fermarlo non
   * cancella chi ha chiamato — il service deve ancora chiudere la notifica e fermarsi.
   */
  suspend fun execute(request: AssistantRequest): ExecutionResult {
    val job = Job(currentCoroutineContext()[Job])
    runtime.currentJob = job
    return try {
      withContext(job) { run(request) }
    } catch (e: CancellationException) {
      currentCoroutineContext().ensureActive()
      ExecutionResult(request.conversationId ?: -1L, request.question, null, null, cancelled = true)
    } finally {
      job.complete()
      if (runtime.currentJob === job) runtime.currentJob = null
    }
  }

  @OptIn(FlowPreview::class)
  private suspend fun run(request: AssistantRequest): ExecutionResult = coroutineScope {
    val now = System.currentTimeMillis()
    val question = request.question
    val catalog = registryHolder.catalog.value
    val registry = catalog.registry
    val conversationId = request.conversationId?.takeIf { conversations.conversation(it) != null }
      ?: conversations.createConversation(question, now, source = if (request.surface == Surface.SESSION) "session" else "app")
    runtime.setActiveConversation(conversationId)
    // Rigenera: la domanda dell'utente c'e' gia' su disco; con un id vero si riusa il messaggio
    // dell'assistente, con l'id sentinella (modifica e rinvia) se ne crea uno nuovo.
    val skipUser = request.regenerateMessageId != null
    val reuseMessage = request.regenerateMessageId?.takeIf { it > 0 }
    val userMessageId = if (skipUser) null else conversations.addUserMessage(conversationId, question, now, request.mode)
    if (userMessageId != null) {
      request.attachments.forEach { conversations.addAttachment(userMessageId, it.kind, it.mime, it.name, it.bytes) }
    }
    val messageId = reuseMessage ?: conversations.addPendingAssistantMessage(conversationId, now + 1)
    if (reuseMessage != null) conversations.updatePartial(messageId, "")

    // Il testo parziale finisce su disco ogni 300 ms: chi riapre l'app a meta' lo trova.
    val persister = launch(Dispatchers.IO) {
      runtime.state.filterIsInstance<AssistantState.Answering>().sample(300).collect { conversations.updatePartial(messageId, it.partial) }
    }
    var traced: PampaiToolContext? = null
    var estimate: ContextEstimate? = null
    try {
      val settings = settingsStore.current()
      val ordered = orderedProviders(request, settings)
      if (ordered.isEmpty()) throw AssistantFailure(FailureKind.NO_KEYS, null)
      val first = ordered.first()
      runtime.setState(AssistantState.Classifying(question, first.provider.id))

      val conversation = memoryConversations.getOrPut(conversationId) { rebuild(conversationId, now, registry) }
      conversation.lastActivityMillis = now
      val zone = ZoneId.systemDefault()
      val toolContext = PampaiToolContext(
        app = context, zone = zone, locale = Locale.getDefault(), now = System::currentTimeMillis,
        surface = request.surface, mode = request.mode, actionsEnabled = settings.actionsEnabled,
        gate = gate, memory = memory, conversations = conversations, settings = pampaiSettings, http = http,
        provider = first, deepCapabilities = first.capabilities(first.model(ModelTier.DEEP)),
        conversationId = conversationId, capabilitiesSummary = { catalog.summary },
        screen = screen.takeIf { request.surface == Surface.SESSION },
        permissions = permissions, reminders = reminders, fluidify = fluidify, usage = usage, aiSettings = settingsStore,
        connectedPackages = { catalog.connectedPackages },
      )
      traced = toolContext
      val parts = request.attachments.map { it.toPart() }
      val stored = conversations.conversation(conversationId)
      val prompt = PromptBuilder.build(
        PromptContext(
          nowLabel = nowLabel(zone),
          language = "it",
          memoryBlock = memory.promptBlock(),
          connectedApps = catalog.summary,
          surface = request.surface,
          mode = request.mode,
          actionsEnabled = settings.actionsEnabled,
          loadedCategories = conversation.loadedCategories.map { it.id },
          maxSteps = config(request.surface).maxRounds,
          screenNote = screenNote(request),
          attachmentsNote = parts.takeIf { it.isNotEmpty() }?.let { list -> "l'utente ha allegato " + list.joinToString(", ") { it.displayName ?: "testo" } },
          conversationTitle = stored?.title?.takeIf { stored.autoTitled },
        ),
      )
      val pre = PreRouter(catalog.preRules).decide(question, settings.actionsEnabled, parts.isNotEmpty())
      val orchestrator = AiOrchestrator(registry, catalog.router, diagnostics, config = config(request.surface), usageSink = usage)
      val input = AskInput(
        question = question,
        mode = request.mode,
        language = "it",
        settings = settings,
        providers = ordered,
        toolContext = toolContext,
        systemPrompt = prompt,
        conversation = conversation,
        actionsEnabled = settings.actionsEnabled,
        preselectedGroups = if (pre.confident) pre.groups else null,
        routerHint = if (pre.confident) emptySet() else pre.groups,
        deepRequested = pre.deep,
        chipFilter = { chip -> AriaChips.accepts(chip) },
        attachmentFallback = { part -> attachments.fallbackText(part) },
        attachments = parts,
      )
      estimate = ContextMeter.estimate(prompt, conversation, if (first.provider.id == dev.antigravity.fluidengine.ai.provider.ProviderId.GROQ) 5_000 else 60_000, registry.specsFor(conversation.loadedGroups), parts, first)
      val result = orchestrator.ask(input, runtime.mutableState())
      // Aspettarlo, non solo fermarlo: `cancel()` torna prima che la sua ultima scrittura sia finita.
      persister.cancelAndJoin()
      val finished = System.currentTimeMillis()
      conversations.complete(messageId, result.answer, result.chips)
      conversations.addRun(conversationId, messageId, result.log, finished, "ok", null, toolContext.traces, estimate?.tokens, estimate?.window)
      conversations.touch(conversationId, finished, result.provider)
      conversations.setLoadedGroups(conversationId, conversation.loadedGroups.map { it.id })
      runtime.setState(
        AssistantState.Done(
          question = question, answer = result.answer, chips = result.chips, provider = result.provider, mode = request.mode,
          usage = result.usage, toolsUsed = result.toolsUsed, durationMillis = result.log.durationMillis, tierReached = result.tierReached,
        ),
      )
      if (stored != null && !stored.autoTitled) {
        // Prima chi ha appena risposto: se il primo della lista era al limite per la domanda,
        // lo e' anche per il titolo, e chiederglielo lo stesso vuol dire non avere il titolo.
        val forTitle = ordered.sortedBy { it.provider.id != result.provider }
        runtime.scope.launch { autoTitle(conversationId, question, result.answer, forTitle) }
      }
      ExecutionResult(conversationId, question, result.answer, null, cancelled = false)
    } catch (e: CancellationException) {
      persister.cancel()
      val partial = (runtime.state.value as? AssistantState.Answering)?.partial
      withContext(NonCancellable) {
        conversations.cancel(messageId, partial)
        conversations.addFailedRun(conversationId, messageId, now, System.currentTimeMillis(), "cancelled", null, traced?.traces.orEmpty())
        conversations.touch(conversationId, System.currentTimeMillis(), null)
      }
      runtime.setState(AssistantState.Cancelled(question, partial))
      ExecutionResult(conversationId, question, null, null, cancelled = true)
    } catch (e: AssistantFailure) {
      persister.cancel()
      val partial = (runtime.state.value as? AssistantState.Answering)?.partial
      conversations.fail(messageId, e.kind, partial)
      conversations.addFailedRun(conversationId, messageId, now, System.currentTimeMillis(), "failed", e.error?.message ?: e.kind.name, traced?.traces.orEmpty())
      conversations.touch(conversationId, System.currentTimeMillis(), null)
      runtime.setState(AssistantState.Failed(question, e.kind, e.error, e.retryAfterSec, partial))
      ExecutionResult(conversationId, question, null, e.kind, cancelled = false)
    } catch (e: Throwable) {
      persister.cancel()
      conversations.fail(messageId, FailureKind.UNKNOWN, null)
      conversations.addFailedRun(conversationId, messageId, now, System.currentTimeMillis(), "failed", e.message ?: e::class.simpleName, traced?.traces.orEmpty())
      runtime.setState(AssistantState.Failed(question, FailureKind.UNKNOWN, e as? AiError, null, null))
      ExecutionResult(conversationId, question, null, FailureKind.UNKNOWN, cancelled = false)
    }
  }

  /** I provider nell'ordine dell'utente, o quello scelto per questa domanda (rigenera con...). */
  private suspend fun orderedProviders(request: AssistantRequest, settings: dev.antigravity.fluidengine.ai.keys.AiSettings): List<ReadyProvider> {
    val override = request.override ?: return providers.ordered(ProviderFactory.Kind.CHAT)
    val chosen = providers.build(override.provider, settings) ?: return providers.ordered(ProviderFactory.Kind.CHAT)
    val ready = if (override.chatModel != null) chosen.copy(chatModel = override.chatModel) else chosen
    return listOf(ready) + providers.ordered(ProviderFactory.Kind.CHAT).filter { it.provider.id != override.provider }
  }

  private fun config(surface: Surface): AiOrchestratorConfig = when (surface) {
    // Una card sopra un'altra app vive un minuto e mezzo; la chat, con il service, quattro.
    Surface.SESSION -> AiOrchestratorConfig(maxRounds = 8, maxOpens = 4, toolTimeoutMillis = 90_000L, totalBudgetMillis = 90_000L, finalReserveMillis = 15_000L, toolTextChars = 4_000, maxOutputTokens = 1_200)
    Surface.APP -> AiOrchestratorConfig(maxRounds = 12, maxMoreTools = 4, maxOpens = 6, toolTimeoutMillis = 90_000L, totalBudgetMillis = 240_000L, finalReserveMillis = 25_000L, toolTextChars = 4_000, maxOutputTokens = 2_000)
  }

  private suspend fun rebuild(conversationId: Long, now: Long, registry: ToolRegistry<PampaiToolContext>): Conversation {
    val conversation = Conversation(conversationId, now)
    conversation.exchanges += conversations.exchanges(conversationId, limit = 8)
    val stored = conversations.conversation(conversationId)
    stored?.loadedGroups?.mapNotNull { registry.group(it) }?.let { groups ->
      conversation.touch(groups)
      groups.forEach { group -> group.resolvedCategory?.let { conversation.loadedCategories += it } }
    }
    return conversation
  }

  /**
   * Un titolo di poche parole dal modello del router: costa nulla ed e' il pattern delle chat vere.
   *
   * Prova i servizi in ordine invece di fermarsi al primo. Su un piano gratuito il primo e' spesso
   * proprio quello che ha appena esaurito i token del minuto, e un titolo mancato lascia in
   * cronologia la domanda troncata a meta' parola.
   */
  private suspend fun autoTitle(conversationId: Long, question: String, answer: String, candidates: List<ReadyProvider>) {
    for (ready in candidates) {
      val title = runCatching {
        ready.provider.complete(
          ChatRequest(
            model = ready.model(ModelTier.ROUTER),
            messages = listOf(
              Message.System("Scrivi un titolo di 3-6 parole, in italiano, senza punteggiatura finale e senza virgolette, per questa conversazione. Rispondi solo con il titolo."),
              Message.User("Domanda: ${question.take(400)}\nRisposta: ${answer.take(600)}"),
            ),
            reasoning = ReasoningLevel.NONE,
            maxOutputTokens = 24,
            temperature = 0.2,
          ),
        ).message.text?.trim()?.trim('"', '«', '»', '.')?.takeIf { it.isNotBlank() && it.length <= 80 }
      }.getOrNull()
      if (title != null) {
        conversations.rename(conversationId, title, auto = true)
        return
      }
    }
  }

  private fun nowLabel(zone: ZoneId): String {
    val now = ZonedDateTime.now(zone)
    return "${Dates.label(now.toLocalDate())}, ${Dates.longDay(now.dayOfWeek)} ${now.dayOfMonth} ${now.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALIAN)} ${now.year}, ore ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}, fuso ${zone.id}"
  }

  /** Il "cosa c'e' nel prompt" per l'anello del contesto, senza far partire niente. */
  suspend fun estimateContext(conversationId: Long?): ContextEstimate? {
    val ready = providers.ordered(ProviderFactory.Kind.CHAT).firstOrNull() ?: return null
    val catalog = registryHolder.catalog.value
    val conversation = conversationId?.let { memoryConversations[it] } ?: Conversation(-1L, System.currentTimeMillis())
    val prompt = PromptBuilder.build(
      PromptContext(nowLabel(ZoneId.systemDefault()), "it", memory.promptBlock(), catalog.summary, Surface.APP, AskMode.TEXT, true, conversation.loadedCategories.map { it.id }, 12, null, null, null),
    )
    return ContextMeter.estimate(prompt, conversation, if (ready.provider.id == dev.antigravity.fluidengine.ai.provider.ProviderId.GROQ) 5_000 else 60_000, catalog.registry.specsFor(conversation.loadedGroups), emptyList(), ready)
  }

  /** Dimentica la conversazione in memoria: dopo una cancellazione, o una modifica che ne cambia la storia. */
  fun forget(conversationId: Long) {
    memoryConversations.remove(conversationId)
  }
}

/** L'orchestratore scrive lo stato direttamente: il runtime espone il flusso mutabile solo a chi esegue. */
internal fun AssistantRuntime.mutableState(): kotlinx.coroutines.flow.MutableStateFlow<AssistantState> = state as kotlinx.coroutines.flow.MutableStateFlow<AssistantState>

/** Cosa dire al modello dello schermo sotto la sessione: quale app, cosa c'e' a disposizione, se l'utente l'ha allegato. */
private fun AssistantEngine.screenNoteOf(snapshot: ScreenContextStore.Snapshot?, request: AssistantRequest, appLabel: (String?) -> String?): String? {
  if (request.surface != Surface.SESSION || snapshot == null || !snapshot.available) return null
  val app = appLabel(snapshot.foregroundPackage) ?: snapshot.foregroundPackage
  val attached = request.attachments.any { it.name.startsWith("schermo") }
  return buildString {
    append(if (app != null) "l'utente stava usando $app" else "l'app sotto non e' nota")
    append("; ")
    append(
      when {
        snapshot.lockscreen -> "il telefono e' bloccato: niente lettura dello schermo"
        attached -> "l'utente ha allegato lo schermo (o una porzione) a questo messaggio: guardalo, non serve schermo_guarda"
        snapshot.assistExpected && snapshot.screenshotExpected -> "il testo e lo screenshot dello schermo sono disponibili con i tool schermo_leggi / schermo_guarda (categoria schermo), solo se la domanda riguarda lo schermo"
        snapshot.assistExpected -> "il testo dello schermo e' disponibile con schermo_leggi (categoria schermo), solo se la domanda riguarda lo schermo"
        else -> "lo schermo non e' leggibile (impostazioni dell'assistente)"
      },
    )
  }
}
