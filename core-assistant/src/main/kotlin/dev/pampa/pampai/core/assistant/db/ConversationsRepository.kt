package dev.pampa.pampai.core.assistant.db

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.orchestrator.AiRequestLog
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.Exchange
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.provider.ContentPart
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.tools.PampaiToolTrace
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
) {
  val totalTokens: Int? get() = if (promptTokens == null && completionTokens == null) null else (promptTokens ?: 0) + (completionTokens ?: 0)
  val durationMillis: Long? get() = finishedAtMillis?.let { it - startedAtMillis }
}

data class Totals(val conversations: Int, val runs: Int, val tokens: Long, val costUsd: Double)

/** Un passaggio trovato cercando nella cronologia. */
data class SearchHit(val conversationId: Long, val conversationTitle: String, val messageId: Long, val role: MessageRole, val snippet: String, val atMillis: Long)

@Serializable
private data class ChipJson(val id: String, val value: String? = null)

@Serializable
private data class TraceJson(val name: String, val millis: Long, val ok: Boolean, val chars: Int, val args: String = "", val preview: String = "", val app: String? = null)

/**
 * Le conversazioni su disco: una riga per conversazione, una per messaggio, una per allegato,
 * una per scambio con la sua telemetria. Il traffico dei tool non si salva (si ricostruisce solo
 * la coppia domanda/risposta, con gli allegati dell'ultima); i gruppi aperti si', perche' con il
 * catalogo gerarchico valgono per tutta la conversazione.
 */
@Singleton
class ConversationsRepository @Inject constructor(
  @ApplicationContext private val context: Context,
  private val conversationDao: ConversationDao,
  private val messageDao: MessageDao,
  private val attachmentDao: AttachmentDao,
  private val runDao: RunDao,
  private val json: Json,
) {

  fun observeConversations(): Flow<List<Conversation>> = conversationDao.observeAll().map { list -> list.map { it.toModel() } }

  fun observeConversation(id: Long): Flow<Conversation?> = conversationDao.observe(id).map { it?.toModel() }

  fun observeMessages(conversationId: Long): Flow<List<Message>> =
    combine(messageDao.observeByConversation(conversationId), attachmentDao.observeByConversation(conversationId)) { messages, attachments ->
      val byMessage = attachments.groupBy { it.messageId }
      messages.map { entity -> entity.toModel(byMessage[entity.id].orEmpty().map { it.toModel() }) }
    }

  fun observeRuns(conversationId: Long): Flow<List<Run>> = runDao.observeByConversation(conversationId).map { list -> list.map { it.toModel() } }

  fun observeTotals(): Flow<Totals> = combine(conversationDao.observeCount(), runDao.observeCount(), runDao.observeTotalTokens(), runDao.observeTotalCost()) { c, r, t, cost ->
    Totals(c, r, t, cost)
  }

  suspend fun conversation(id: Long): Conversation? = conversationDao.get(id)?.toModel()

  suspend fun latest(): Conversation? = conversationDao.latest()?.toModel()

  suspend fun createConversation(title: String, nowMillis: Long, source: String = "app"): Long =
    conversationDao.insert(ConversationEntity(title = title.take(80), createdAtMillis = nowMillis, updatedAtMillis = nowMillis, source = source))

  suspend fun addUserMessage(conversationId: Long, text: String, nowMillis: Long, mode: AskMode): Long =
    messageDao.insert(MessageEntity(conversationId = conversationId, role = MessageRole.USER.name, text = text, status = MessageStatus.DONE.name, createdAtMillis = nowMillis, mode = mode.name))

  suspend fun addPendingAssistantMessage(conversationId: Long, nowMillis: Long): Long =
    messageDao.insert(MessageEntity(conversationId = conversationId, role = MessageRole.ASSISTANT.name, text = "", status = MessageStatus.PENDING.name, createdAtMillis = nowMillis))

  /** Copia il file dell'allegato nella cartella dell'app e lo lega al messaggio. */
  suspend fun addAttachment(messageId: Long, kind: AttachmentKind, mime: String, name: String, bytes: ByteArray): Long = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, "attachments").apply { mkdirs() }
    val file = File(dir, "$messageId-${System.currentTimeMillis()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}")
    file.writeBytes(bytes)
    attachmentDao.insert(AttachmentEntity(messageId = messageId, kind = kind.name, mime = mime, name = name, path = file.absolutePath, bytes = bytes.size.toLong()))
  }

  suspend fun updatePartial(messageId: Long, text: String) =
    messageDao.updatePartial(messageId, text, MessageStatus.STREAMING.name, listOf(MessageStatus.PENDING.name, MessageStatus.STREAMING.name))

  suspend fun complete(messageId: Long, text: String, chips: List<AnswerChip>) {
    val message = messageDao.get(messageId) ?: return
    messageDao.update(message.copy(text = text, chipsJson = json.encodeToString(chips.map { ChipJson(it.id, it.value) }), status = MessageStatus.DONE.name, failureKind = null))
  }

  suspend fun fail(messageId: Long, kind: FailureKind, partial: String?) {
    val message = messageDao.get(messageId) ?: return
    messageDao.update(message.copy(text = partial ?: message.text, status = MessageStatus.FAILED.name, failureKind = kind.name))
  }

  suspend fun cancel(messageId: Long, partial: String?) {
    val message = messageDao.get(messageId) ?: return
    messageDao.update(message.copy(text = partial ?: message.text, status = MessageStatus.CANCELLED.name))
  }

  /** Al riavvio: una risposta rimasta aperta non e' piu' "in corso". */
  suspend fun failStale() = messageDao.failStale(MessageStatus.FAILED.name, FailureKind.UNKNOWN.name, listOf(MessageStatus.PENDING.name, MessageStatus.STREAMING.name))

  suspend fun touch(conversationId: Long, nowMillis: Long, provider: ProviderId?) {
    val conversation = conversationDao.get(conversationId) ?: return
    conversationDao.update(conversation.copy(updatedAtMillis = nowMillis, lastProvider = provider?.id ?: conversation.lastProvider))
  }

  suspend fun rename(conversationId: Long, title: String, auto: Boolean) {
    val conversation = conversationDao.get(conversationId) ?: return
    if (auto && conversation.autoTitled) return
    conversationDao.update(conversation.copy(title = title.take(80), autoTitled = auto || conversation.autoTitled))
  }

  suspend fun setPinned(conversationId: Long, pinned: Boolean) {
    val conversation = conversationDao.get(conversationId) ?: return
    conversationDao.update(conversation.copy(pinned = pinned))
  }

  suspend fun setPlugin(conversationId: Long, plugin: String?) {
    val conversation = conversationDao.get(conversationId) ?: return
    if (conversation.plugin != plugin) conversationDao.update(conversation.copy(plugin = plugin))
  }

  suspend fun setLoadedGroups(conversationId: Long, groupIds: List<String>) {
    val conversation = conversationDao.get(conversationId) ?: return
    val encoded = json.encodeToString(groupIds)
    if (encoded != conversation.loadedGroupsJson) conversationDao.update(conversation.copy(loadedGroupsJson = encoded))
  }

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
        groupsJson = json.encodeToString(log.groups),
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

  suspend fun addFailedRun(conversationId: Long, messageId: Long, startedAtMillis: Long, finishedAtMillis: Long, outcome: String, error: String?, traces: List<PampaiToolTrace> = emptyList()): Long =
    runDao.insert(
      RunEntity(
        conversationId = conversationId, messageId = messageId, startedAtMillis = startedAtMillis, finishedAtMillis = finishedAtMillis,
        steps = 0, provider = null, routerModel = null, chatModel = null, deepModel = null, tierReached = null, groupsJson = null,
        promptTokens = null, completionTokens = null, costUsd = null, waitedSeconds = 0,
        toolTracesJson = traces.takeIf { it.isNotEmpty() }?.let { list -> json.encodeToString(list.map { TraceJson(it.name, it.millis, it.ok, it.chars, it.args, it.preview, it.app) }) },
        outcome = outcome, error = error,
      ),
    )

  /**
   * Le coppie domanda/risposta concluse, per ricostruire la conversazione in memoria del modello.
   * Gli allegati si rileggono dal disco solo per l'ultima coppia: e' l'unica che il compattatore
   * ripropone al modello.
   */
  suspend fun exchanges(conversationId: Long, limit: Int): List<Exchange> {
    val messages = messageDao.listByConversation(conversationId)
    val exchanges = mutableListOf<Pair<Exchange, Long>>()
    var pendingQuestion: MessageEntity? = null
    messages.forEach { message ->
      when (message.role) {
        MessageRole.USER.name -> pendingQuestion = message
        MessageRole.ASSISTANT.name -> {
          val question = pendingQuestion
          if (question != null && message.status == MessageStatus.DONE.name && message.text.isNotBlank()) {
            exchanges += Exchange(question.text, message.text, decodeChips(message.chipsJson), ProviderId.GROQ, message.createdAtMillis) to question.id
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
      val index = message.text.indexOf(words.first(), ignoreCase = true).coerceAtLeast(0)
      val start = (index - 80).coerceAtLeast(0)
      val end = (index + 120).coerceAtMost(message.text.length)
      SearchHit(conversation.id, conversation.title, message.id, MessageRole.valueOf(message.role), message.text.substring(start, end).replace('\n', ' '), message.createdAtMillis)
    }
  }

  /** Modifica e rinvia: cade tutto cio' che segue il messaggio (la risposta e gli scambi dopo). */
  suspend fun truncateAfter(conversationId: Long, messageId: Long) {
    val message = messageDao.get(messageId) ?: return
    val after = messageDao.listByConversation(conversationId).filter { it.createdAtMillis > message.createdAtMillis || (it.createdAtMillis == message.createdAtMillis && it.id > message.id) }
    val ids = after.map { it.id }
    if (ids.isNotEmpty()) {
      attachmentDao.listByConversation(conversationId).filter { it.messageId in ids }.forEach { runCatching { File(it.path).delete() } }
      attachmentDao.deleteByMessages(ids)
      runDao.deleteByMessages(ids)
      messageDao.deleteAfter(conversationId, message.createdAtMillis, message.id)
    }
  }

  suspend fun updateUserText(messageId: Long, text: String) {
    val message = messageDao.get(messageId) ?: return
    messageDao.update(message.copy(text = text))
  }

  suspend fun delete(conversationId: Long) {
    withContext(Dispatchers.IO) { attachmentDao.listByConversation(conversationId).forEach { runCatching { File(it.path).delete() } } }
    attachmentDao.deleteByConversation(conversationId)
    runDao.deleteByConversation(conversationId)
    messageDao.deleteByConversation(conversationId)
    conversationDao.delete(conversationId)
  }

  suspend fun deleteAll() {
    withContext(Dispatchers.IO) { File(context.filesDir, "attachments").deleteRecursively() }
    attachmentDao.deleteAll()
    runDao.deleteAll()
    messageDao.deleteAll()
    conversationDao.deleteAll()
  }

  private fun decodeChips(raw: String?): List<AnswerChip> =
    raw?.let { runCatching { json.decodeFromString<List<ChipJson>>(it) }.getOrNull() }?.map { AnswerChip(it.id, it.value) } ?: emptyList()

  private fun ConversationEntity.toModel() = Conversation(
    id = id, title = title, createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
    lastProvider = ProviderId.fromId(lastProvider), source = source, pinned = pinned,
    loadedGroups = loadedGroupsJson?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList(),
    autoTitled = autoTitled,
    plugin = plugin,
  )

  private fun AttachmentEntity.toModel() = Attachment(id, messageId, AttachmentKind.entries.firstOrNull { it.name == kind } ?: AttachmentKind.DOCUMENT, mime, name, path, bytes)

  private fun MessageEntity.toModel(attachments: List<Attachment>) = Message(
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
  )

  private fun RunEntity.toModel() = Run(
    id = id, conversationId = conversationId, messageId = messageId, startedAtMillis = startedAtMillis, finishedAtMillis = finishedAtMillis,
    steps = steps, provider = ProviderId.fromId(provider), routerModel = routerModel, chatModel = chatModel, deepModel = deepModel,
    tierReached = tierReached?.let { name -> ModelTier.entries.firstOrNull { it.name == name } },
    groups = groupsJson?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList(),
    promptTokens = promptTokens, completionTokens = completionTokens, costUsd = costUsd, waitedSeconds = waitedSeconds,
    tools = toolTracesJson?.let { runCatching { json.decodeFromString<List<TraceJson>>(it) }.getOrNull() }?.map { PampaiToolTrace(it.name, it.args, it.millis, it.ok, it.chars, it.preview, it.app) } ?: emptyList(),
    outcome = outcome, error = error, contextTokens = contextTokens, contextWindow = contextWindow,
  )
}
