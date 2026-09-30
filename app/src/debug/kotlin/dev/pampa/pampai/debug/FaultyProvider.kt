package dev.pampa.pampai.debug

import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.provider.ChatDelta
import dev.antigravity.fluidengine.ai.provider.ChatProvider
import dev.antigravity.fluidengine.ai.provider.ChatRequest
import dev.antigravity.fluidengine.ai.provider.ChatTurn
import dev.antigravity.fluidengine.ai.provider.FinishReason
import dev.antigravity.fluidengine.ai.provider.GeminiCodec
import dev.antigravity.fluidengine.ai.provider.OpenAiCompatCodec
import dev.antigravity.fluidengine.ai.provider.ProviderId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Un provider vero con davanti il [FaultPlan]: a ogni chiamata di chat (`complete` o `stream`) chiede
 * al piano se tocca a un guasto; se no passa la mano al provider vero, identica. `listModels` e
 * `transcribe` non si toccano mai: la verifica della chiave e la voce restano quelle vere.
 *
 * Una risposta 200 finta si legge col codec del provider (Groq e OpenRouter il dialetto OpenAI,
 * Gemini il suo), nello stesso ordine in cui lo fanno `OpenAiCompatProvider` e `GeminiProvider`.
 */
class FaultyProvider(
  private val real: ChatProvider,
  private val plan: FaultPlan,
  /** Una riga per ogni guasto iniettato; nell'app va nel logcat col tag `FaultInjector`. */
  private val report: (String) -> Unit,
  /** L'attesa di `stall`; nei test una finta che non aspetta. */
  private val sleep: suspend (Long) -> Unit = { delay(it) },
) : ChatProvider by real {

  override suspend fun complete(request: ChatRequest): ChatTurn {
    val shot = plan.take(id, request) ?: return real.complete(request)
    return when (val scenario = FaultWire.scenario(shot.kind, id, request.model, streaming = false)) {
      is FaultWire.Scenario.Http -> throw scenario.toError().also { report(line(shot, request, "complete", describe(it))) }
      is FaultWire.Scenario.Wire -> decodeTurn(scenario.payloads.single()).also {
        report(line(shot, request, "complete", "200 finish=${it.finishReason} testo=${it.message.text?.length ?: 0}"))
      }
      FaultWire.Scenario.Stall -> {
        stall(shot, request, streaming = false)
        real.complete(request)
      }
    }
  }

  override fun stream(request: ChatRequest): Flow<ChatDelta> = flow {
    // Dentro il flow e non fuori: il guasto si consuma quando lo stream parte davvero.
    val shot = plan.take(id, request)
    if (shot == null) {
      emitAll(real.stream(request))
      return@flow
    }
    when (val scenario = FaultWire.scenario(shot.kind, id, request.model, streaming = true)) {
      is FaultWire.Scenario.Http -> throw scenario.toError().also { report(line(shot, request, "stream", describe(it))) }
      is FaultWire.Scenario.Wire -> replay(shot, request, scenario.payloads)
      FaultWire.Scenario.Stall -> {
        stall(shot, request, streaming = true)
        emitAll(real.stream(request))
      }
    }
  }

  private suspend fun stall(shot: FaultPlan.Shot, request: ChatRequest, streaming: Boolean) {
    val silence = FaultWire.silence(request, streaming)
    val how = if (silence.timesOut) "poi timeout" else "poi risponde il servizio vero"
    report(line(shot, request, if (streaming) "stream" else "complete", "silenzio ${silence.millis / 1000} s, $how"))
    sleep(silence.millis)
    if (silence.timesOut) throw FaultWire.timeout()
  }

  /** I pezzi finti dentro il codec vero, poi la chiusura come la fa il provider vero. */
  private suspend fun FlowCollector<ChatDelta>.replay(shot: FaultPlan.Shot, request: ChatRequest, payloads: List<String>) {
    try {
      if (id == ProviderId.GEMINI) {
        val state = GeminiCodec.StreamState()
        payloads.forEach { payload -> GeminiCodec.parseStreamChunk(payload, state).forEach { emit(it) } }
        state.raw()?.let { emit(ChatDelta.Raw(it)) }
        val finish = state.finish ?: FinishReason.STOP
        report(line(shot, request, "stream", "200 finish=$finish"))
        emit(ChatDelta.Finish(finish, state.usage, RateLimitInfo.EMPTY, state.citations.values.toList()))
      } else {
        val state = OpenAiCompatCodec.StreamState()
        payloads.forEach { payload -> OpenAiCompatCodec.parseStreamChunk(payload, state).forEach { emit(it) } }
        OpenAiCompatCodec.rawFromStream(state)?.let { emit(ChatDelta.Raw(it)) }
        val finish = OpenAiCompatCodec.finishReason(state.finish, hasToolCalls = false)
        report(line(shot, request, "stream", "200 finish=$finish"))
        emit(ChatDelta.Finish(finish, state.usage, RateLimitInfo.EMPTY, state.citations.values.toList()))
      }
    } catch (e: AiError) {
      // Un errore dentro lo stream (il tool_use_failed di Groq): l'ha scritto il codec.
      report(line(shot, request, "stream", "200 poi ${describe(e)}"))
      throw e
    }
  }

  private fun decodeTurn(body: String): ChatTurn {
    val json = Json.parseToJsonElement(body)
    val provider = id
    return if (provider == ProviderId.GEMINI) {
      GeminiCodec.parseResponse(json, RateLimitInfo.EMPTY)
    } else {
      OpenAiCompatCodec.parseCompletion(json, RateLimitInfo.EMPTY, provider)
    }
  }

  private fun line(shot: FaultPlan.Shot, request: ChatRequest, call: String, outcome: String): String =
    "${id.id}: iniettato ${shot.kind.wire} ($call, modello ${request.model}) -> $outcome; ne restano ${shot.left}"

  companion object {
    /** L'errore come lo vede l'orchestratore: tipo, codice, perche', e la frase del provider. */
    fun describe(error: Throwable): String = when (error) {
      is AiError.BadRequest -> "BadRequest(${error.code}, ${error.reason})"
      is AiError.RateLimited -> "RateLimited(${error.httpCode}, retryAfter=${error.retryAfterSec}s, freeCap=${error.freeModelCap})"
      is AiError.Server -> "Server(${error.code})"
      is AiError -> error::class.simpleName.orEmpty()
      else -> error::class.java.simpleName
    } + ((error as? AiError)?.providerMessage?.let { " \"$it\"" } ?: "")
  }
}
