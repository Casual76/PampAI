package dev.pampa.pampai.core.assistant.usage

import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.orchestrator.AiUsageEvent
import dev.antigravity.fluidengine.ai.orchestrator.AiUsageSink
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.db.UsageEventDao
import dev.pampa.pampai.core.assistant.db.UsageEventEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Cosa ha consumato una chiamata: un evento per riga, dal piu' recente. */
data class UsageEvent(
  val atMillis: Long,
  val provider: ProviderId,
  val model: String,
  val kind: String,
  val promptTokens: Int?,
  val completionTokens: Int?,
  val costUsd: Double?,
  val audioSeconds: Double?,
  val durationMillis: Long,
  val error: String?,
  val rateLimited: Boolean,
  val remainingRequests: Int?,
  val remainingTokens: Int?,
) {
  val tokens: Int get() = (promptTokens ?: 0) + (completionTokens ?: 0)
}

/**
 * Il tracker dei consumi: ogni chiamata a un provider (router, chat, profondo, trascrizione,
 * voce) finisce qui, su disco, e da qui escono i totali per giorno, settimana e mese. E' il
 * `AiUsageSink` dell'orchestratore: viene chiamato sul thread della domanda, quindi scrive altrove.
 */
@Singleton
class UsageRepository @Inject constructor(private val dao: UsageEventDao) : AiUsageSink {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onTurn(event: AiUsageEvent) {
    scope.launch {
      dao.insert(
        UsageEventEntity(
          atMillis = event.atMillis,
          provider = event.provider.id,
          model = event.model,
          kind = event.tier.name,
          promptTokens = event.usage?.promptTokens,
          completionTokens = event.usage?.completionTokens,
          costUsd = event.usage?.costUsd ?: CostTable.estimate(event.provider, event.model, event.usage),
          durationMillis = event.durationMillis,
          conversationId = event.conversationId,
          error = event.error?.let { describe(it) },
          rateLimited = event.rateLimited,
          remainingRequests = event.rateLimit.remainingRequests,
          remainingTokens = event.rateLimit.remainingTokens,
        ),
      )
    }
  }

  /** Una trascrizione: i secondi di audio, nessun token. */
  fun recordSpeech(provider: ProviderId, model: String, audioSeconds: Double, durationMillis: Long, error: String? = null) {
    scope.launch {
      dao.insert(
        UsageEventEntity(
          atMillis = System.currentTimeMillis(), provider = provider.id, model = model, kind = "STT",
          promptTokens = null, completionTokens = null, costUsd = CostTable.estimateAudio(provider, model, audioSeconds),
          audioSeconds = audioSeconds, durationMillis = durationMillis, conversationId = null, error = error,
          rateLimited = false, remainingRequests = null, remainingTokens = null,
        ),
      )
    }
  }

  fun observeSince(fromMillis: Long): Flow<List<UsageEvent>> = dao.observeSince(fromMillis).map { list -> list.map { it.toModel() } }

  suspend fun countToday(provider: ProviderId, dayStartMillis: Long): Int = dao.countSince(dayStartMillis, provider.id)

  suspend fun clear() = dao.deleteAll()

  private fun describe(error: AiError): String = when (error) {
    is AiError.RateLimited -> if (error.freeModelCap) "tetto giornaliero gratuito" else "limite di richieste"
    is AiError.Unauthorized -> "chiave rifiutata"
    is AiError.Server -> "errore del servizio (${error.code})"
    is AiError.BadRequest -> "richiesta rifiutata (${error.code})"
    is AiError.Network -> "rete"
    is AiError.Timeout -> "tempo scaduto"
    is AiError.Parse -> "risposta illeggibile"
  }

  private fun UsageEventEntity.toModel() = UsageEvent(
    atMillis, ProviderId.fromId(provider) ?: ProviderId.GROQ, model, kind, promptTokens, completionTokens, costUsd, audioSeconds, durationMillis, error, rateLimited, remainingRequests, remainingTokens,
  )
}

/**
 * I prezzi pubblici di Groq e Gemini, in dollari per milione di token, per stimare una spesa che
 * il provider non dichiara (OpenRouter la dichiara e vince). Un listino locale con la sua data:
 * quando un prezzo cambia, cambia qui.
 */
object CostTable {
  const val UPDATED = "2026-09"

  private data class Price(val prompt: Double, val completion: Double)

  private val prices: Map<String, Price> = mapOf(
    // Groq (i modelli gratuiti del free tier costano zero finche' si resta nel tier).
    "groq/qwen/qwen3.8-27b" to Price(0.29, 0.59),
    "groq/llama-3.1-8b-instant" to Price(0.05, 0.08),
    "groq/llama-3.3-70b-versatile" to Price(0.59, 0.79),
    "groq/openai/gpt-oss-20b" to Price(0.10, 0.50),
    "groq/openai/gpt-oss-120b" to Price(0.15, 0.75),
    "groq/groq/compound-mini" to Price(0.15, 0.75),
    "groq/meta-llama/llama-4-scout-17b-16e-instruct" to Price(0.11, 0.34),
    // Gemini (listino a pagamento; il free tier costa zero).
    "gemini/gemini-3.6-flash" to Price(0.30, 2.50),
    "gemini/gemini-3.5-flash-lite" to Price(0.10, 0.40),
    "gemini/gemini-3.6-pro" to Price(2.00, 12.00),
    "gemini/gemini-3.5-transcribe" to Price(0.30, 2.50),
  )

  fun estimate(provider: ProviderId, model: String, usage: dev.antigravity.fluidengine.ai.provider.Usage?): Double? {
    val u = usage ?: return null
    val price = prices["${provider.id}/${model.removePrefix("models/")}"] ?: return null
    return (u.promptTokens * price.prompt + u.completionTokens * price.completion) / 1_000_000.0
  }

  /** Whisper su Groq: 0,04 $ per ora di audio (large-v3), 0,111 $ (large-v3-turbo). */
  fun estimateAudio(provider: ProviderId, model: String, audioSeconds: Double): Double? {
    if (provider != ProviderId.GROQ) return null
    val perHour = if (model.contains("turbo")) 0.04 else 0.111
    return audioSeconds / 3600.0 * perHour
  }
}
