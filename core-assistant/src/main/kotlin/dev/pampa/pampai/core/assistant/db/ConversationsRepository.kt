package dev.pampa.pampai.core.assistant.db

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.orchestrator.AiRequestLog
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.Exchange
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.provider.ContentPart
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.orchestrator.ProviderSwitch
import dev.antigravity.fluidengine.ai.orchestrator.SwitchReason
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.tools.PampaiToolTrace
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class MessageRole { USER, ASSISTANT }

enum class MessageStatus { PENDING, STREAMING, DONE, FAILED, CANCELLED }

enum class AttachmentKind { IMAGE, DOCUMENT }

data class Conversation(
  val id: Long,
  val title: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val lastProvider: ProviderId?,
  val source: String,
  val pinned: Boolean,
  val loadedGroups: List<String>,
  val autoTitled: Boolean,
  /** L'id della categoria scelta come plugin, o null. */
  val plugin: String? = null,
  /** La chat temporanea: fuori dalla cronologia, senza titolo automatico, cancellata quando la si lascia. */
  val temporary: Boolean = false,
)

data class Attachment(
  val id: Long,
  val messageId: Long,
  val kind: AttachmentKind,
  val mime: String,
  val name: String,
  val path: String,
  val bytes: Long,
) {
  /** La parte per il modello, letta dal file: null se il file non c'e' piu'. */
  fun toPart(): ContentPart? {
    val file = File(path)
    if (!file.exists()) return null
    val data = runCatching { file.readBytes() }.getOrNull() ?: return null
    return when (kind) {
      AttachmentKind.IMAGE -> ContentPart.Image(data, mime)
      AttachmentKind.DOCUMENT -> ContentPart.Document(data, mime, name)
    }
  }
}

data class Message(
  val id: Long,
  val conversationId: Long,
  val role: MessageRole,
  val text: String,
  val chips: List<AnswerChip>,
  val status: MessageStatus,
  val failureKind: FailureKind?,
  val createdAtMillis: Long,
  val mode: AskMode,
  val attachments: List<Attachment> = emptyList(),
  /** Il messaggio a cui risponde (o che continua); null = una prima domanda. */
  val parentId: Long? = null,
  /** Quale versione e' fra le sue sorelle ("‹ 2/3 ›"); null quando ce n'e' una sola. */
  val version: Version? = null,
)

/**
 * Da dove parte una domanda nell'albero delle versioni. Nessuna cancella niente: "rigenera" e
 * "modifica e rinvia" aggiungono un fratello, e le frecce delle versioni riportano al ramo di prima.
 */
sealed interface Anchor {
  /** Una domanda nuova in fondo al ramo che si sta guardando. */
  data object Continue : Anchor

  /** "Modifica e rinvia": una domanda sorella di [replacing] (un messaggio dell'utente), con un ramo tutto suo. */
  data class Edit(val replacing: Long) : Anchor

  /** "Rigenera": una risposta sorella di [replacing] (una risposta), alla stessa domanda. */
  data class Regenerate(val replacing: Long) : Anchor
}

/**
 * Cio' che [ConversationsRepository.startTurn] ha scritto: la domanda nuova ([userId], null per
 * "rigenera"), la risposta in attesa, la domanda a cui risponde, la foglia che si guardava prima
 * (per tornarci se la versione nuova si butta), e la foglia su cui finisce la storia che il
 * modello deve conoscere (il padre della domanda).
 */
data class Turn(
  val userId: Long?,
  val assistantId: Long,
  val questionId: Long,
  val previousLeafId: Long?,
  val conversationId: Long,
  val historyLeafId: Long?,
)

/** La traccia di uno scambio: quanto e' costato, con cosa, in quanti passi. */
data class Run(
  val id: Long,
  val conversationId: Long,
  val messageId: Long,
  val startedAtMillis: Long,
  val finishedAtMillis: Long?,
  val steps: Int,
  val provider: ProviderId?,
  val routerModel: String?,
  val chatModel: String?,
  val deepModel: String?,
  val tierReached: ModelTier?,
  val groups: List<String>,
  val promptTokens: Int?,
  val completionTokens: Int?,
  val costUsd: Double?,
  val waitedSeconds: Int,
  val tools: List<PampaiToolTrace>,
  val outcome: String,
  val error: String?,
  val contextTokens: Int?,
  val contextWindow: Int?,
  /**
   * Tutti i modelli che hanno risposto, nell'ordine in cui l'hanno fatto (engine 1.29.0): con un
   * cambio di servizio [routerModel]/[chatModel]/[deepModel] tengono solo l'ultimo per livello,
   * qui si legge "Gemini pro, poi la chat di OpenRouter". Vuoto per le righe salvate prima.
   */
  val models: List<RunModel> = emptyList(),
  /**
   * I cambi di servizio della domanda (engine 2.8.0), per la riga "Ha risposto Gemini: Groq era al
   * limite". Vuoto quando ha risposto il primo, e per le righe salvate prima.
   */
  val switches: List<ProviderSwitch> = emptyList(),
  /** Il dettaglio di un fallimento, per il foglio "Dettagli": chi, con che codice, cosa ha detto. */
  val failure: FailureDetail? = null,
) {
  val totalTokens: Int? get() = if (promptTokens == null && completionTokens == null) null else (promptTokens ?: 0) + (completionTokens ?: 0)
  val durationMillis: Long? get() = finishedAtMillis?.let { it - startedAtMillis }
}

/** Un modello che ha risposto in un passaggio: quale servizio, a quale livello, quale modello. */
data class RunModel(val provider: ProviderId, val tier: ModelTier, val model: String)

/**
 * Perche' una domanda e' fallita, detto dal servizio: il codice HTTP e il suo messaggio (corto, gia'
 * ripulito dalle chiavi dall'engine). "Il servizio ha risposto con un errore" da solo non dice a
 * nessuno cosa fare; questo si', a chi lo va a cercare.
 */
data class FailureDetail(val provider: ProviderId?, val reason: SwitchReason?, val httpCode: Int?, val message: String?)

data class Totals(val conversations: Int, val runs: Int, val tokens: Long, val costUsd: Double)

/** Un passaggio trovato cercando nella cronologia. */
data class SearchHit(val conversationId: Long, val conversationTitle: String, val messageId: Long, val role: MessageRole, val snippet: String, val atMillis: Long)

@Serializable
private data class ChipJson(val id: String, val value: String? = null)

@Serializable
private data class TraceJson(val name: String, val millis: Long, val ok: Boolean, val chars: Int, val args: String = "", val preview: String = "", val app: String? = null)

@Serializable
private data class RunModelJson(val provider: String, val tier: String, val model: String)

/**
 * Cosa sta nella colonna `groupsJson` di un passaggio: i gruppi, e dall'engine 1.29.0 anche i
 * modelli usati in ordine. Un oggetto in quella colonna, e non una colonna nuova, perche' non vale
 * una migrazione di Room: le righe vecchie (una lista di stringhe) si leggono come prima.
 */
@Serializable
private data class GroupsJson(
  val groups: List<String> = emptyList(),
  val models: List<RunModelJson> = emptyList(),
  val switches: List<SwitchJson> = emptyList(),
  val failure: FailureJson? = null,
)

@Serializable
private data class SwitchJson(val from: String, val to: String, val reason: String)

@Serializable
private data class FailureJson(val provider: String? = null, val reason: String? = null, val httpCode: Int? = null, val message: String? = null)

/** Un allegato gia' scritto su disco, in attesa della sua riga: i file si scrivono fuori dalla transazione. */
private class StagedFile(val kind: AttachmentKind, val mime: String, val name: String, val path: String, val bytes: Long)

/**
 * Le conversazioni su disco: una riga per conversazione, una per messaggio, una per allegato,
 * una per scambio con la sua telemetria. Il traffico dei tool non si salva (si ricostruisce solo
 * la coppia domanda/risposta, con gli allegati dell'ultima); i gruppi aperti si', perche' con il
 * catalogo gerarchico valgono per tutta la conversazione.
 *
 * I messaggi sono un albero ([MessageTree]): chi osserva una conversazione vede il cammino attivo,
 * con le versioni di ogni messaggio; le operazioni che lo cambiano stanno ciascuna in una
 * transazione, cosi' un processo ucciso a meta' non lascia rami senza radice.
 */
@Singleton
class ConversationsRepository @Inject constructor(
  @ApplicationContext private val context: Context,
  private val conversationDao: ConversationDao,
  private val messageDao: MessageDao,
  private val attachmentDao: AttachmentDao,
  private val runDao: RunDao,
  private val json: Json,
  private val db: PampaiDatabase,
) {

  fun observeConversations(): Flow<List<Conversation>> = conversationDao.observeAll().map { list -> list.map { it.toModel() } }

  fun observeConversation(id: Long): Flow<Conversation?> = conversationDao.observe(id).map { it?.toModel() }

  /**
   * Il cammino attivo della conversazione, dalla prima domanda alla foglia scelta, con la versione
   * di ogni messaggio. E' cio' che si mostra: gli altri rami esistono, e si raggiungono con le
   * frecce ([selectVersion]).
   */
  fun observeMessages(conversationId: Long): Flow<List<Message>> =
    combine(
      messageDao.observeByConversation(conversationId),
      attachmentDao.observeByConversation(conversationId),
      // Solo la foglia: la riga della conversazione cambia a ogni passaggio (updatedAtMillis), il
      // cammino no.
      conversationDao.observe(conversationId).map { it?.activeLeafId }.distinctUntilChanged(),
    ) { messages, attachments, activeLeafId ->
      val byMessage = attachments.groupBy { it.messageId }
      MessageTree.path(messages, activeLeafId).map { node ->
        node.message.toModel(byMessage[node.message.id].orEmpty().map { it.toModel() }, node.version.takeIf { it.count > 1 })
      }
    }

  fun observeRuns(conversationId: Long): Flow<List<Run>> = runDao.observeByConversation(conversationId).map { list -> list.map { it.toModel() } }

  fun observeTotals(): Flow<Totals> = combine(conversationDao.observeCount(), runDao.observeCount(), runDao.observeTotalTokens(), runDao.observeTotalCost()) { c, r, t, cost ->
    Totals(c, r, t, cost)
  }

  suspend fun conversation(id: Long): Conversation? = conversationDao.get(id)?.toModel()

  suspend fun latest(): Conversation? = conversationDao.latest()?.toModel()

  suspend fun createConversation(title: String, nowMillis: Long, source: String = "app", temporary: Boolean = false): Long =
    conversationDao.insert(ConversationEntity(title = title.take(80), createdAtMillis = nowMillis, updatedAtMillis = nowMillis, source = source, temporary = temporary))

  /** Un messaggio solo, con i suoi allegati: la domanda di una risposta da rigenerare, per esempio. */
  suspend fun message(id: Long): Message? {
    val entity = messageDao.get(id) ?: return null
    return entity.toModel(attachmentDao.listByMessage(id).map { it.toModel() }, null)
  }

  /** La foglia su cui finisce il cammino attivo (quella a cui si attacchera' la prossima domanda). */
  suspend fun activeLeaf(conversationId: Long): Long? {
    val conversation = conversationDao.get(conversationId) ?: return null
    return MessageTree.leafOf(messageDao.listByConversation(conversationId), conversation.activeLeafId)
  }

  /** Gli allegati di un messaggio riletti dal disco, pronti per il modello; quelli spariti si saltano. */
  suspend fun pendingAttachmentsOf(messageId: Long): List<PendingAttachment> {
    val rows = attachmentDao.listByMessage(messageId)
    return withContext(Dispatchers.IO) {
      rows.mapNotNull { row ->
        val kind = AttachmentKind.entries.firstOrNull { it.name == row.kind } ?: AttachmentKind.DOCUMENT
        runCatching { PendingAttachment(kind, row.mime, row.name, File(row.path).readBytes()) }.getOrNull()
      }
    }
  }

  /**
   * Una domanda che parte: scrive in una transazione sola la domanda (se c'e'), i suoi allegati, la
   * risposta in attesa e la foglia attiva, che diventa la risposta nuova.
   *
   * - [Anchor.Continue]: la domanda si attacca alla foglia del cammino che si sta guardando.
   * - [Anchor.Edit]: la domanda nuova e' sorella di quella sostituita (stesso padre). Senza
   *   [attachments] nuovi si ricopiano quelli della domanda di prima, file compresi: una riga, un
   *   file, e cancellare un ramo non tocca mai i file di un altro.
   * - [Anchor.Regenerate]: nessuna domanda nuova; la risposta e' sorella di quella sostituita. Un
   *   id di una domanda (una domanda rimasta senza risposta) vale come "rispondi di nuovo a questa".
   *
   * @throws IllegalArgumentException se la conversazione o il messaggio sostituito non ci sono.
   */
  suspend fun startTurn(conversationId: Long, anchor: Anchor, question: String, mode: AskMode, attachments: List<PendingAttachment>, now: Long): Turn {
    // I file prima della transazione: scrivere su disco la dentro terrebbe fermo il database.
    val staged = when (anchor) {
      Anchor.Continue -> stage(attachments, now)
      is Anchor.Edit -> if (attachments.isNotEmpty()) stage(attachments, now) else copyAttachments(anchor.replacing, now)
      is Anchor.Regenerate -> emptyList()
    }
    return try {
      db.withTransaction {
        val conversation = conversationDao.get(conversationId) ?: throw IllegalArgumentException("conversazione $conversationId inesistente")
        val all = messageDao.listByConversation(conversationId)
        val shownLeaf = MessageTree.leafOf(all, conversation.activeLeafId)
        val known = all.associateBy { it.id }
        val userId: Long?
        val questionId: Long
        val historyLeafId: Long?
        when (anchor) {
          Anchor.Continue -> {
            historyLeafId = shownLeaf
            userId = messageDao.insert(userMessage(conversationId, question, mode, now, parentId = shownLeaf))
            questionId = userId
          }
          is Anchor.Edit -> {
            val replaced = known[anchor.replacing]?.takeIf { it.role == MessageRole.USER.name }
              ?: throw IllegalArgumentException("domanda ${anchor.replacing} inesistente")
            // Un padre sparito vale come radice, come per MessageTree.
            historyLeafId = replaced.parentId?.takeIf { it in known }
            userId = messageDao.insert(userMessage(conversationId, question, mode, now, parentId = replaced.parentId))
            questionId = userId
          }
          is Anchor.Regenerate -> {
            val replaced = known[anchor.replacing] ?: throw IllegalArgumentException("messaggio ${anchor.replacing} inesistente")
            val asked = if (replaced.role == MessageRole.USER.name) replaced else replaced.parentId?.let { known[it] }
            if (asked == null || asked.role != MessageRole.USER.name) throw IllegalArgumentException("risposta ${anchor.replacing} senza domanda")
            historyLeafId = asked.parentId?.takeIf { it in known }
            userId = null
            questionId = asked.id
          }
        }
        if (userId != null) {
          staged.forEach { attachmentDao.insert(AttachmentEntity(messageId = userId, kind = it.kind.name, mime = it.mime, name = it.name, path = it.path, bytes = it.bytes)) }
        }
        val assistantId = messageDao.insert(
          MessageEntity(
            conversationId = conversationId, role = MessageRole.ASSISTANT.name, text = "", status = MessageStatus.PENDING.name,
            createdAtMillis = now + 1, parentId = questionId, selectedAtMillis = now,
          ),
        )
        conversationDao.setActiveLeaf(conversationId, assistantId)
        Turn(userId, assistantId, questionId, shownLeaf, conversationId, historyLeafId)
      }
    } catch (e: Throwable) {
      // Le righe non ci sono (la transazione e' tornata indietro): nemmeno i file devono restare.
      withContext(NonCancellable + Dispatchers.IO) { staged.forEach { runCatching { File(it.path).delete() } } }
      throw e
    }
  }

  /**
   * Le frecce delle versioni: si passa al ramo di [siblingId], aprendo la sua foglia scelta per
   * ultima (il punto in cui lo si era lasciato). Quella foglia diventa la piu' fresca.
   */
  suspend fun selectVersion(conversationId: Long, siblingId: Long, now: Long = System.currentTimeMillis()) {
    db.withTransaction {
      val all = messageDao.listByConversation(conversationId)
      if (all.none { it.id == siblingId }) return@withTransaction
      val leaf = MessageTree.bestLeaf(all, siblingId) ?: return@withTransaction
      conversationDao.setActiveLeaf(conversationId, leaf)
      messageDao.setSelectedAt(leaf, now)
    }
  }

  /**
   * Un risultato della ricerca nel cassetto: il ramo che contiene [messageId] diventa quello
   * mostrato, cosi' aprendo la conversazione il passaggio trovato c'e'.
   *
   * @return la conversazione del messaggio, o null se il messaggio non c'e' piu'.
   */
  suspend fun activateMessage(messageId: Long, now: Long = System.currentTimeMillis()): Long? = db.withTransaction {
    val message = messageDao.get(messageId) ?: return@withTransaction null
    val all = messageDao.listByConversation(message.conversationId)
    val leaf = MessageTree.bestLeaf(all, messageId) ?: return@withTransaction message.conversationId
    conversationDao.setActiveLeaf(message.conversationId, leaf)
    messageDao.setSelectedAt(leaf, now)
    message.conversationId
  }

  /**
   * Una versione nuova ("rigenera", "modifica e rinvia") fermata prima di dire qualcosa: se ne va
   * (messaggi, passaggi, allegati e file) e si torna al ramo che si guardava. Una risposta che ha
   * gia' del testo resta: e' una versione vera, anche se a meta'.
   */
  suspend fun discardVersion(turn: Turn) {
    val files = db.withTransaction {
      val all = messageDao.listByConversation(turn.conversationId)
      val answer = all.firstOrNull { it.id == turn.assistantId } ?: return@withTransaction emptyList()
      if (answer.text.isNotBlank() || answer.status == MessageStatus.DONE.name) return@withTransaction emptyList()
      val ids = MessageTree.subtree(all, turn.userId ?: turn.assistantId).toList()
      val paths = attachmentDao.listByMessages(ids).map { it.path }
      attachmentDao.deleteByMessages(ids)
      runDao.deleteByMessages(ids)
      messageDao.deleteByIds(ids)
      val back = turn.previousLeafId?.takeIf { previous -> previous !in ids && all.any { it.id == previous } }
      conversationDao.setActiveLeaf(turn.conversationId, back)
      paths
    }
    deleteFiles(files)
  }

  suspend fun updatePartial(messageId: Long, text: String) =
    messageDao.updatePartial(messageId, text, MessageStatus.STREAMING.name, listOf(MessageStatus.PENDING.name, MessageStatus.STREAMING.name))

  suspend fun complete(messageId: Long, text: String, chips: List<AnswerChip>) =
    messageDao.complete(messageId, text, json.encodeToString(chips.map { ChipJson(it.id, it.value) }), MessageStatus.DONE.name)

  suspend fun fail(messageId: Long, kind: FailureKind, partial: String?) =
    messageDao.fail(messageId, partial, MessageStatus.FAILED.name, kind.name)

  suspend fun cancel(messageId: Long, partial: String?) =
    messageDao.cancel(messageId, partial, MessageStatus.CANCELLED.name)

  /** Al riavvio: una risposta rimasta aperta non e' piu' "in corso". */
  suspend fun failStale() = messageDao.failStale(MessageStatus.FAILED.name, FailureKind.UNKNOWN.name, listOf(MessageStatus.PENDING.name, MessageStatus.STREAMING.name))

  suspend fun touch(conversationId: Long, nowMillis: Long, provider: ProviderId?) = conversationDao.touch(conversationId, nowMillis, provider?.id)

  /**
   * Il titolo. Quello del modello arriva solo se non ce n'e' gia' uno (e mai a una temporanea: le
   * resta la domanda troncata). Quello scritto a mano vince sempre, anche su un titolo automatico
   * che arriva dopo: prima una rinomina fatta in fretta veniva sovrascritta dal modello.
   */
  suspend fun rename(conversationId: Long, title: String, auto: Boolean) {
    val clean = title.trim().take(80).ifEmpty { return }
    if (auto) conversationDao.renameAuto(conversationId, clean) else conversationDao.renameManual(conversationId, clean)
  }

  suspend fun setPinned(conversationId: Long, pinned: Boolean) = conversationDao.setPinned(conversationId, pinned)

  suspend fun setPlugin(conversationId: Long, plugin: String?) = conversationDao.setPlugin(conversationId, plugin)

  /** Manutenzione all'avvio: le tracce dei passaggi piu' vecchi di un mese si alleggeriscono. */
  suspend fun compact(nowMillis: Long) = runDao.dropOldTraces(nowMillis - TRACE_RETENTION_MILLIS)

  /** Una colonna sola: riscrivere la riga intera riportava indietro la foglia scelta nel frattempo. */
  suspend fun setLoadedGroups(conversationId: Long, groupIds: List<String>) =
    conversationDao.setLoadedGroups(conversationId, json.encodeToString(groupIds))

  suspend fun addRun(conversationId: Long, messageId: Long, log: AiRequestLog, finishedAtMillis: Long, outcome: String, error: String?, traces: List<PampaiToolTrace>, contextTokens: Int?, contextWindow: Int?): Long =
    runDao.insert(
      RunEntity(
        conversationId = conversationId,
        messageId = messageId,
        startedAtMillis = log.startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        steps = log.steps,
        provider = (log.switchedTo.lastOrNull() ?: log.provider).id,
        routerModel = log.models[ModelTier.ROUTER],
        chatModel = log.models[ModelTier.CHAT] ?: log.model,
        deepModel = log.models[ModelTier.DEEP],
        tierReached = log.tierReached.name,
        groupsJson = json.encodeToString(GroupsJson(log.groups, log.modelsUsed.map { RunModelJson(it.provider.id, it.tier.name, it.model) }, log.switches.map { it.toJson() })),
        promptTokens = log.usage?.promptTokens,
        completionTokens = log.usage?.completionTokens,
        costUsd = log.usage?.costUsd,
        waitedSeconds = log.waitedSeconds,
        toolTracesJson = json.encodeToString(
          if (traces.isNotEmpty()) traces.map { TraceJson(it.name, it.millis, it.ok, it.chars, it.args, it.preview, it.app) }
          else log.tools.map { TraceJson(it.name, it.millis, it.ok, it.chars) },
        ),
        outcome = outcome,
        error = error,
        contextTokens = contextTokens,
        contextWindow = contextWindow,
      ),
    )

  suspend fun addFailedRun(
    conversationId: Long,
    messageId: Long,
    startedAtMillis: Long,
    finishedAtMillis: Long,
    outcome: String,
    error: String?,
    traces: List<PampaiToolTrace> = emptyList(),
    failure: FailureDetail? = null,
    switches: List<ProviderSwitch> = emptyList(),
  ): Long =
    runDao.insert(
      RunEntity(
        conversationId = conversationId, messageId = messageId, startedAtMillis = startedAtMillis, finishedAtMillis = finishedAtMillis,
        steps = 0, provider = failure?.provider?.id, routerModel = null, chatModel = null, deepModel = null, tierReached = null,
        groupsJson = if (failure == null && switches.isEmpty()) null else json.encodeToString(
          GroupsJson(
            switches = switches.map { it.toJson() },
            failure = failure?.let { FailureJson(it.provider?.id, it.reason?.name, it.httpCode, it.message) },
          ),
        ),
        promptTokens = null, completionTokens = null, costUsd = null, waitedSeconds = 0,
        toolTracesJson = traces.takeIf { it.isNotEmpty() }?.let { list -> json.encodeToString(list.map { TraceJson(it.name, it.millis, it.ok, it.chars, it.args, it.preview, it.app) }) },
        outcome = outcome, error = error,
      ),
    )

  /**
   * Le coppie domanda/risposta concluse, per ricostruire la conversazione in memoria del modello.
   * Solo il cammino sopra [questionId] (la domanda esclusa): le altre versioni e la risposta che si
   * sta rigenerando non esistono, per il modello. Con [questionId] nullo, tutto il cammino attivo.
   * Gli allegati si rileggono dal disco solo per l'ultima coppia: e' l'unica che il compattatore
   * ripropone al modello.
   */
  suspend fun exchanges(conversationId: Long, questionId: Long?, limit: Int): List<Exchange> {
    val all = messageDao.listByConversation(conversationId)
    val stored = conversationDao.get(conversationId)
    val messages = if (questionId != null) MessageTree.ancestors(all, questionId).dropLast(1) else MessageTree.path(all, stored?.activeLeafId).map { it.message }
    // Chi ha risposto davvero a ciascuna domanda, dai passaggi salvati: prima era "Groq" per tutte,
    // e l'engine lo usa per decidere come riproporre la storia al modello.
    val fallbackProvider = stored?.lastProvider?.let { ProviderId.fromId(it) } ?: ProviderId.defaultOrder.first()
    val providerByMessage = runDao.listByConversation(conversationId).mapNotNull { run -> ProviderId.fromId(run.provider)?.let { run.messageId to it } }.toMap()
    val exchanges = mutableListOf<Pair<Exchange, Long>>()
    var pendingQuestion: MessageEntity? = null
    messages.forEach { message ->
      when (message.role) {
        MessageRole.USER.name -> pendingQuestion = message
        MessageRole.ASSISTANT.name -> {
          val question = pendingQuestion
          if (question != null && message.status == MessageStatus.DONE.name && message.text.isNotBlank()) {
            exchanges += Exchange(question.text, message.text, decodeChips(message.chipsJson), providerByMessage[message.id] ?: fallbackProvider, message.createdAtMillis) to question.id
          }
          pendingQuestion = null
        }
      }
    }
    val kept = exchanges.takeLast(limit)
    if (kept.isEmpty()) return emptyList()
    val (last, lastQuestionId) = kept.last()
    val attachments = withContext(Dispatchers.IO) { attachmentDao.listByMessage(lastQuestionId).mapNotNull { it.toModel().toPart() } }
    return kept.dropLast(1).map { it.first } + last.copy(attachments = attachments)
  }

  /** Cerca nelle conversazioni: le parole della domanda in qualsiasi messaggio, dal piu' recente. */
  suspend fun search(query: String, limit: Int = 20): List<SearchHit> {
    val words = query.trim().split(Regex("\\s+")).filter { it.length > 2 }
    if (words.isEmpty()) return emptyList()
    val found = messageDao.search(words.first(), limit * 4)
      .filter { message -> words.all { w -> message.text.contains(w, ignoreCase = true) } }
      .take(limit)
    return found.mapNotNull { message ->
      val conversation = conversationDao.get(message.conversationId) ?: return@mapNotNull null
      // La chat temporanea non si cerca: non esiste per il cassetto e non deve esistere nemmeno
      // per il tool `conversazioni_cerca`, che altrimenti la ripescherebbe finche' e' aperta.
      if (conversation.temporary) return@mapNotNull null
      val index = message.text.indexOf(words.first(), ignoreCase = true).coerceAtLeast(0)
      val start = (index - 80).coerceAtLeast(0)
      val end = (index + 120).coerceAtMost(message.text.length)
      SearchHit(conversation.id, conversation.title, message.id, MessageRole.valueOf(message.role), message.text.substring(start, end).replace('\n', ' '), message.createdAtMillis)
    }
  }

  /** Una conversazione intera, con tutti i suoi rami: messaggi, passaggi, allegati e file. */
  suspend fun delete(conversationId: Long) {
    val files = attachmentDao.listByConversation(conversationId).map { it.path }
    db.withTransaction {
      attachmentDao.deleteByConversation(conversationId)
      runDao.deleteByConversation(conversationId)
      messageDao.deleteByConversation(conversationId)
      conversationDao.delete(conversationId)
    }
    withContext(Dispatchers.IO) { files.forEach { runCatching { File(it).delete() } } }
  }

  /**
   * Butta le chat temporanee: la conversazione, i suoi messaggi, gli allegati (anche i file) e i
   * passaggi. Non ci sono chiavi esterne in questo database, quindi niente cascata: si passa da
   * [delete], che l'ordine giusto ce l'ha gia'.
   *
   * @return gli id cancellati, cosi' chi chiama puo' dimenticarli anche dalla memoria del processo
   *   (SQLite riusa il ROWID piu' alto dopo una cancellazione: una conversazione in memoria rimasta
   *   su quell'id finirebbe dentro la prossima chat).
   */
  suspend fun deleteTemporary(): List<Long> {
    val ids = conversationDao.temporaryIds()
    ids.forEach { delete(it) }
    return ids
  }

  suspend fun deleteAll() {
    db.withTransaction {
      attachmentDao.deleteAll()
      runDao.deleteAll()
      messageDao.deleteAll()
      conversationDao.deleteAll()
    }
    withContext(Dispatchers.IO) { File(context.filesDir, "attachments").deleteRecursively() }
  }

  private fun userMessage(conversationId: Long, text: String, mode: AskMode, now: Long, parentId: Long?) = MessageEntity(
    conversationId = conversationId, role = MessageRole.USER.name, text = text, status = MessageStatus.DONE.name,
    createdAtMillis = now, mode = mode.name, parentId = parentId, selectedAtMillis = now,
  )

  private fun attachmentsDir(): File = File(context.filesDir, "attachments").apply { mkdirs() }

  private fun fileFor(name: String, now: Long): File =
    File(attachmentsDir(), "$now-${UUID.randomUUID().toString().take(8)}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}")

  /** Gli allegati nuovi, scritti nella cartella dell'app prima che esista la riga del messaggio. */
  private suspend fun stage(attachments: List<PendingAttachment>, now: Long): List<StagedFile> = withContext(Dispatchers.IO) {
    val written = mutableListOf<StagedFile>()
    try {
      attachments.forEach { a ->
        val file = fileFor(a.name, now)
        file.writeBytes(a.bytes)
        written += StagedFile(a.kind, a.mime, a.name, file.absolutePath, a.bytes.size.toLong())
      }
    } catch (e: Throwable) {
      written.forEach { runCatching { File(it.path).delete() } }
      throw e
    }
    written
  }

  /** Le copie dei file di un messaggio, per una sua versione: quelli che non si leggono piu' si saltano. */
  private suspend fun copyAttachments(messageId: Long, now: Long): List<StagedFile> {
    val rows = attachmentDao.listByMessage(messageId)
    return withContext(Dispatchers.IO) {
      rows.mapNotNull { row ->
        runCatching {
          val target = fileFor(row.name, now)
          File(row.path).copyTo(target, overwrite = true)
          StagedFile(AttachmentKind.entries.firstOrNull { it.name == row.kind } ?: AttachmentKind.DOCUMENT, row.mime, row.name, target.absolutePath, target.length())
        }.getOrNull()
      }
    }
  }

  private suspend fun deleteFiles(paths: List<String>) {
    if (paths.isEmpty()) return
    withContext(Dispatchers.IO) { paths.forEach { runCatching { File(it).delete() } } }
  }

  private fun decodeChips(raw: String?): List<AnswerChip> =
    raw?.let { runCatching { json.decodeFromString<List<ChipJson>>(it) }.getOrNull() }?.map { AnswerChip(it.id, it.value) } ?: emptyList()

  private fun ConversationEntity.toModel() = Conversation(
    id = id, title = title, createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
    lastProvider = ProviderId.fromId(lastProvider), source = source, pinned = pinned,
    loadedGroups = loadedGroupsJson?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList(),
    autoTitled = autoTitled,
    plugin = plugin,
    temporary = temporary,
  )

  private fun AttachmentEntity.toModel() = Attachment(id, messageId, AttachmentKind.entries.firstOrNull { it.name == kind } ?: AttachmentKind.DOCUMENT, mime, name, path, bytes)

  private fun MessageEntity.toModel(attachments: List<Attachment>, version: Version?) = Message(
    id = id,
    conversationId = conversationId,
    role = MessageRole.entries.firstOrNull { it.name == role } ?: MessageRole.ASSISTANT,
    text = text,
    chips = decodeChips(chipsJson),
    status = MessageStatus.entries.firstOrNull { it.name == status } ?: MessageStatus.DONE,
    failureKind = failureKind?.let { name -> FailureKind.entries.firstOrNull { it.name == name } },
    createdAtMillis = createdAtMillis,
    mode = AskMode.entries.firstOrNull { it.name == mode } ?: AskMode.TEXT,
    attachments = attachments,
    parentId = parentId,
    version = version,
  )

  private fun RunEntity.toModel(): Run {
    val stored = groupsJson?.let { decodeGroups(it) }
    return Run(
      id = id, conversationId = conversationId, messageId = messageId, startedAtMillis = startedAtMillis, finishedAtMillis = finishedAtMillis,
      steps = steps, provider = ProviderId.fromId(provider), routerModel = routerModel, chatModel = chatModel, deepModel = deepModel,
      tierReached = tierReached?.let { name -> ModelTier.entries.firstOrNull { it.name == name } },
      groups = stored?.groups ?: emptyList(),
      models = stored?.models?.mapNotNull { m ->
        val provider = ProviderId.fromId(m.provider) ?: return@mapNotNull null
        val tier = ModelTier.entries.firstOrNull { it.name == m.tier } ?: return@mapNotNull null
        RunModel(provider, tier, m.model)
      } ?: emptyList(),
      switches = stored?.switches?.mapNotNull { sw ->
        val from = ProviderId.fromId(sw.from) ?: return@mapNotNull null
        val to = ProviderId.fromId(sw.to) ?: return@mapNotNull null
        ProviderSwitch(from, to, SwitchReason.entries.firstOrNull { it.name == sw.reason } ?: SwitchReason.BAD_REQUEST)
      } ?: emptyList(),
      failure = stored?.failure?.let { f ->
        FailureDetail(ProviderId.fromId(f.provider), f.reason?.let { name -> SwitchReason.entries.firstOrNull { it.name == name } }, f.httpCode, f.message)
      },
      promptTokens = promptTokens, completionTokens = completionTokens, costUsd = costUsd, waitedSeconds = waitedSeconds,
      tools = toolTracesJson?.let { runCatching { json.decodeFromString<List<TraceJson>>(it) }.getOrNull() }?.map { PampaiToolTrace(it.name, it.args, it.millis, it.ok, it.chars, it.preview, it.app) } ?: emptyList(),
      outcome = outcome, error = error, contextTokens = contextTokens, contextWindow = contextWindow,
    )
  }

  private fun ProviderSwitch.toJson() = SwitchJson(from.id, to.id, reason.name)

  /** Le due forme di `groupsJson`: l'oggetto di adesso, o la lista di stringhe di prima. */
  private fun decodeGroups(raw: String): GroupsJson? =
    runCatching { json.decodeFromString<GroupsJson>(raw) }.getOrNull()
      ?: runCatching { GroupsJson(groups = json.decodeFromString<List<String>>(raw)) }.getOrNull()
}

/** Quanto restano le tracce complete degli strumenti di un passaggio. */
private const val TRACE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
