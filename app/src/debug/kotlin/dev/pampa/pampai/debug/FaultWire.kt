package dev.pampa.pampai.debug

import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.net.AiErrorMapper
import dev.antigravity.fluidengine.ai.provider.ChatRequest
import dev.antigravity.fluidengine.ai.provider.ProviderId
import java.net.SocketTimeoutException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Cosa "risponde il server" per ogni guasto: un codice HTTP con header e corpo come li manderebbe
 * il provider vero, o una risposta 200 i cui pezzi passano dal codec vero. Niente [AiError]
 * costruiti a mano: l'errore esce da [AiErrorMapper] (o dal codec) come uscirebbe da `AiHttp`, cosi'
 * si prova anche la classificazione, non solo il failover.
 *
 * Puro: si prova sulla JVM.
 */
object FaultWire {

  /** Il silenzio di `stall`: un po' piu' del timeout di uno stream che non pensa (45 s). */
  const val STALL_MILLIS = 50_000L

  /** Le pazienze di default di `AiHttp` come le configura `AssistantModule`, per le richieste che non ne portano una. */
  const val DEFAULT_CHUNK_TIMEOUT_MILLIS = 45_000L
  const val DEFAULT_READ_TIMEOUT_MILLIS = 120_000L

  /** Quello che il server fa al posto di rispondere. */
  sealed interface Scenario {
    /** Un rifiuto prima di qualunque byte: quello che `AiHttp` passa ad [AiErrorMapper.map]. */
    data class Http(val code: Int, val headers: Map<String?, List<String>>, val body: String) : Scenario {
      fun toError(): AiError = AiErrorMapper.map(code, headers, body)
    }

    /**
     * Una risposta 200: in stream i payload `data:` dell'SSE, uno per pezzo; senza stream un solo
     * corpo JSON. Li legge il codec del provider, lo stesso della risposta vera.
     */
    data class Wire(val payloads: List<String>) : Scenario

    /** Silenzio: vedi [silence]. */
    data object Stall : Scenario
  }

  /**
   * Quanto tace un `stall` e come finisce. Se la pazienza della richiesta (la stessa che `AiHttp`
   * darebbe alla socket) arriva prima di [STALL_MILLIS], si tace fino a li' e poi e' un timeout,
   * come farebbe la socket; altrimenti si tace [STALL_MILLIS] e poi il servizio vero risponde.
   */
  data class Silence(val millis: Long, val timesOut: Boolean)

  fun silence(request: ChatRequest, streaming: Boolean): Silence {
    val patience = request.readTimeoutMillis?.takeIf { it > 0 }?.toLong()
      ?: if (streaming) DEFAULT_CHUNK_TIMEOUT_MILLIS else DEFAULT_READ_TIMEOUT_MILLIS
    return if (patience <= STALL_MILLIS) Silence(patience, timesOut = true) else Silence(STALL_MILLIS, timesOut = false)
  }

  /** Il timeout come esce da `AiHttp` quando la socket tace: la stessa eccezione, lo stesso involucro. */
  fun timeout(): Throwable = AiErrorMapper.wrap(SocketTimeoutException("Read timed out"))

  fun scenario(kind: FaultKind, provider: ProviderId, model: String, streaming: Boolean): Scenario = when (kind) {
    FaultKind.TOOL_USE_FAILED -> toolUseFailed(provider, streaming)
    FaultKind.MODEL_GONE -> modelGone(provider, model)
    FaultKind.CONTEXT -> context(provider, model)
    FaultKind.RATE_LIMIT -> rateLimit(provider, model)
    FaultKind.SERVER -> server(provider)
    FaultKind.STALL -> Scenario.Stall
    FaultKind.EMPTY -> empty(provider, model, streaming)
  }

  // --- tool_use_failed ---------------------------------------------------------------------------

  private const val GROQ_TOOL_MESSAGE = "Failed to call a function. Please adjust your prompt. See 'failed_generation' for more details."

  private fun groqToolError(): JsonObject = buildJsonObject {
    put("message", GROQ_TOOL_MESSAGE)
    put("type", "invalid_request_error")
    put("code", "tool_use_failed")
    put("failed_generation", "<function=apri_app{\"nome\": \"Spotify\"}</function>")
  }

  private fun toolUseFailed(provider: ProviderId, streaming: Boolean): Scenario = when (provider) {
    // Groq lo manda a stream gia' aperto, come pezzo `error`; senza stream e' un 400.
    ProviderId.GROQ ->
      if (streaming) {
        Scenario.Wire(listOf(buildJsonObject { put("error", groqToolError()) }.toString()))
      } else {
        http(400, body = buildJsonObject { put("error", groqToolError()) })
      }
    // OpenRouter passa l'errore del fornitore a valle (qui Groq) in `metadata.raw`.
    ProviderId.OPENROUTER -> http(
      400,
      body = buildJsonObject {
        putJsonObject("error") {
          put("message", "Provider returned error")
          put("code", 400)
          putJsonObject("metadata") {
            put("raw", buildJsonObject { put("error", groqToolError()) }.toString())
            put("provider_name", "Groq")
          }
        }
      },
    )
    // Gemini non ha un tool_use_failed: il suo equivalente e' il candidato MALFORMED_FUNCTION_CALL,
    // che il codec trasforma in una risposta vuota con la ragione OTHER.
    ProviderId.GEMINI -> Scenario.Wire(
      listOf(
        buildJsonObject {
          putJsonArray("candidates") {
            add(
              buildJsonObject {
                put("finishReason", "MALFORMED_FUNCTION_CALL")
                put("finishMessage", "Malformed function call: print(apri_app(nome='Spotify'")
                put("index", 0)
              },
            )
          }
        }.toString(),
      ),
    )
  }

  // --- model_gone ----------------------------------------------------------------------------------

  private fun modelGone(provider: ProviderId, model: String): Scenario = when (provider) {
    ProviderId.GROQ -> http(
      400,
      body = buildJsonObject {
        putJsonObject("error") {
          put(
            "message",
            "The model `$model` has been decommissioned and is no longer supported. Please refer to " +
              "https://console.groq.com/docs/deprecations for a recommendation on which model to use instead.",
          )
          put("type", "invalid_request_error")
          put("code", "model_decommissioned")
        }
      },
    )
    ProviderId.GEMINI -> http(
      404,
      body = buildJsonObject {
        putJsonObject("error") {
          put("code", 404)
          put(
            "message",
            "models/$model is not found for API version v1beta, or is not supported for generateContent. " +
              "Call ListModels to see the list of available models and their supported methods.",
          )
          put("status", "NOT_FOUND")
        }
      },
    )
    ProviderId.OPENROUTER -> http(
      404,
      body = buildJsonObject {
        putJsonObject("error") {
          put("message", "No endpoints found for $model.")
          put("code", 404)
        }
      },
    )
  }

  // --- context -------------------------------------------------------------------------------------

  private fun context(provider: ProviderId, model: String): Scenario = when (provider) {
    // Il tetto di token per richiesta di Groq gratuito: un 413 che si chiama rate_limit_exceeded.
    ProviderId.GROQ -> http(
      413,
      body = buildJsonObject {
        putJsonObject("error") {
          put(
            "message",
            "Request too large for model `$model` in organization `org_fault` service tier `on_demand` on tokens per minute (TPM): " +
              "Limit 6000, Requested 9133, please reduce your message size and try again. Need more tokens? Upgrade to Dev Tier today at " +
              "https://console.groq.com/settings/billing",
          )
          put("type", "tokens")
          put("code", "rate_limit_exceeded")
        }
      },
    )
    ProviderId.GEMINI -> http(
      400,
      body = buildJsonObject {
        putJsonObject("error") {
          put("code", 400)
          put("message", "The input token count (1187340) exceeds the maximum number of tokens allowed (1048576).")
          put("status", "INVALID_ARGUMENT")
        }
      },
    )
    ProviderId.OPENROUTER -> http(
      400,
      body = buildJsonObject {
        putJsonObject("error") {
          put(
            "message",
            "This endpoint's maximum context length is 131072 tokens. However, you requested about 139521 tokens " +
              "(138021 of text input, 1500 in the output). Please reduce the length of either one, or use the " +
              "\"middle-out\" transform to compress your prompt automatically.",
          )
          put("code", 400)
        }
      },
    )
  }

  // --- rate_limit ----------------------------------------------------------------------------------

  /** L'attesa che i 429 finti chiedono: abbastanza per vedere il conto alla rovescia, poca per aspettarla. */
  const val RETRY_AFTER_SEC = 8

  private fun rateLimit(provider: ProviderId, model: String): Scenario = when (provider) {
    ProviderId.GROQ -> http(
      429,
      headers = mapOf(
        "retry-after" to "$RETRY_AFTER_SEC",
        "x-ratelimit-limit-requests" to "1000",
        "x-ratelimit-remaining-requests" to "996",
        "x-ratelimit-limit-tokens" to "6000",
        "x-ratelimit-remaining-tokens" to "0",
        "x-ratelimit-reset-requests" to "5m45.6s",
        "x-ratelimit-reset-tokens" to "7.66s",
      ),
      body = buildJsonObject {
        putJsonObject("error") {
          put(
            "message",
            "Rate limit reached for model `$model` in organization `org_fault` service tier `on_demand` on tokens per minute (TPM): " +
              "Limit 6000, Used 5642, Requested 1873. Please try again in 7.66s. Need more tokens? Upgrade to Dev Tier today at " +
              "https://console.groq.com/settings/billing",
          )
          put("type", "tokens")
          put("code", "rate_limit_exceeded")
        }
      },
    )
    // Gemini non manda header: l'attesa sta nel RetryInfo dei dettagli.
    ProviderId.GEMINI -> http(
      429,
      body = buildJsonObject {
        putJsonObject("error") {
          put("code", 429)
          put(
            "message",
            "You exceeded your current quota, please check your plan and billing details. For more information on this error, " +
              "head to: https://ai.google.dev/gemini-api/docs/rate-limits.",
          )
          put("status", "RESOURCE_EXHAUSTED")
          put(
            "details",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("@type", "type.googleapis.com/google.rpc.RetryInfo")
                  put("retryDelay", "${RETRY_AFTER_SEC}s")
                },
              )
            },
          )
        }
      },
    )
    // Il limite al minuto dei gratuiti, non il tetto giornaliero (che ha una frase sua nella UI).
    ProviderId.OPENROUTER -> http(
      429,
      headers = mapOf("retry-after" to "$RETRY_AFTER_SEC"),
      body = buildJsonObject {
        putJsonObject("error") {
          put("message", "Rate limit exceeded: free-models-per-min. ")
          put("code", 429)
        }
      },
    )
  }

  // --- server --------------------------------------------------------------------------------------

  private fun server(provider: ProviderId): Scenario = when (provider) {
    ProviderId.GROQ -> http(
      503,
      body = buildJsonObject {
        putJsonObject("error") {
          put(
            "message",
            "Groq is currently over capacity. Please try again and back off exponentially. Visit https://groqstatus.com to see if there is an ongoing incident.",
          )
          put("type", "internal_server_error")
          put("code", "service_unavailable")
        }
      },
    )
    ProviderId.GEMINI -> http(
      503,
      body = buildJsonObject {
        putJsonObject("error") {
          put("code", 503)
          put("message", "The model is overloaded. Please try again later.")
          put("status", "UNAVAILABLE")
        }
      },
    )
    ProviderId.OPENROUTER -> http(
      503,
      body = buildJsonObject {
        putJsonObject("error") {
          put("message", "Service temporarily unavailable")
          put("code", 503)
        }
      },
    )
  }

  // --- empty ---------------------------------------------------------------------------------------

  /**
   * Nessun uso nei payload, di proposito: la risposta finta non deve finire nei consumi veri
   * dell'utente come token spesi.
   */
  private fun empty(provider: ProviderId, model: String, streaming: Boolean): Scenario = when (provider) {
    // Il caso classico: il thinking si mangia tutto il tetto, il candidato arriva senza parti.
    ProviderId.GEMINI -> Scenario.Wire(
      listOf(
        buildJsonObject {
          putJsonArray("candidates") {
            add(
              buildJsonObject {
                putJsonObject("content") { put("role", "model") }
                put("finishReason", "MAX_TOKENS")
                put("index", 0)
              },
            )
          }
          put("modelVersion", model)
        }.toString(),
      ),
    )
    ProviderId.GROQ, ProviderId.OPENROUTER -> Scenario.Wire(
      listOf(
        if (streaming) {
          buildJsonObject {
            put("id", "chatcmpl-fault")
            put("object", "chat.completion.chunk")
            put("model", model)
            putJsonArray("choices") {
              add(
                buildJsonObject {
                  put("index", 0)
                  putJsonObject("delta") { put("content", "") }
                  put("finish_reason", "length")
                },
              )
            }
          }.toString()
        } else {
          buildJsonObject {
            put("id", "chatcmpl-fault")
            put("object", "chat.completion")
            put("model", model)
            putJsonArray("choices") {
              add(
                buildJsonObject {
                  put("index", 0)
                  putJsonObject("message") {
                    put("role", "assistant")
                    put("content", "")
                  }
                  put("finish_reason", "length")
                },
              )
            }
          }.toString()
        },
      ),
    )
  }

  private fun http(code: Int, headers: Map<String, String> = emptyMap(), body: JsonObject): Scenario.Http =
    // Come `HttpURLConnection.headerFields`: chiave nulla per la riga di stato, valori in lista.
    Scenario.Http(code, mapOf<String?, List<String>>(null to listOf("HTTP/1.1 $code")) + headers.mapValues { listOf(it.value) }, body.toString())
}
