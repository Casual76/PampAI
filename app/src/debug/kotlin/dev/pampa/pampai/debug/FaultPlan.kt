package dev.pampa.pampai.debug

import dev.antigravity.fluidengine.ai.provider.ChatRequest
import dev.antigravity.fluidengine.ai.provider.ProviderId

/** I guasti che si possono chiedere, col nome che si scrive nel comando adb. */
enum class FaultKind(val wire: String) {
  /** Il modello ha scritto male una tool call: Groq `tool_use_failed` (su Gemini, `MALFORMED_FUNCTION_CALL`). */
  TOOL_USE_FAILED("tool_use_failed"),

  /** Il modello non c'e' piu': ritirato, rinominato, senza endpoint. */
  MODEL_GONE("model_gone"),

  /** La richiesta non ci sta: 413 di Groq, contesto pieno su Gemini e OpenRouter. */
  CONTEXT("context"),

  /** Un 429 con la sua attesa. */
  RATE_LIMIT("rate_limit"),

  /** Un 503. */
  SERVER("server"),

  /** Il servizio tace: [FaultWire.STALL_MILLIS] di silenzio, o il timeout di lettura se arriva prima. */
  STALL("stall"),

  /** Una risposta senza una parola che finisce per `LENGTH`: il thinking si e' mangiato i token. */
  EMPTY("empty");

  companion object {
    fun of(wire: String?): FaultKind? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
  }
}

/**
 * Quali chiamate colpire. Di default la chat: il router (lo stadio 1, l'unica chiamata con uno
 * schema JSON) un 400 se lo mangia in silenzio col suo ripiego, e un guasto consumato li' non si
 * vedrebbe mai sullo schermo.
 */
enum class CallTarget(val wire: String) {
  CHAT("chat"),
  ROUTER("router"),
  ANY("any");

  fun matches(request: ChatRequest): Boolean = when (this) {
    CHAT -> request.jsonSchema == null
    ROUTER -> request.jsonSchema != null
    ANY -> true
  }

  companion object {
    fun of(wire: String?): CallTarget? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
  }
}

/** Un comando arrivato da adb, gia' letto. */
sealed interface FaultCommand {
  data class Arm(val providers: List<ProviderId>, val kind: FaultKind, val count: Int, val target: CallTarget) : FaultCommand
  data object Off : FaultCommand
  data class Invalid(val reason: String) : FaultCommand

  companion object {
    const val OFF = "off"
    const val ALL = "all"

    /**
     * Dagli extra del broadcast a un comando. [count] null = uno; `provider all` = tutti e tre,
     * per vedere cosa succede quando non c'e' nessuno a cui passare.
     */
    fun parse(kind: String?, provider: String?, count: Int?, call: String?): FaultCommand {
      val kindText = kind?.trim()?.lowercase()
      if (kindText == OFF) return Off
      val parsedKind = FaultKind.of(kindText)
        ?: return Invalid("kind '${kind.orEmpty()}' sconosciuto; validi: ${FaultKind.entries.joinToString { it.wire }}, $OFF")
      val providerText = provider?.trim()?.lowercase()
      val providers = when {
        providerText.isNullOrEmpty() -> return Invalid("manca --es provider <${ProviderId.entries.joinToString("|") { it.id }}|$ALL>")
        providerText == ALL -> ProviderId.entries.toList()
        else -> listOf(ProviderId.fromId(providerText) ?: return Invalid("provider '$provider' sconosciuto"))
      }
      val times = count ?: 1
      if (times < 1) return Invalid("count deve essere almeno 1")
      val target = if (call.isNullOrBlank()) CallTarget.CHAT else CallTarget.of(call) ?: return Invalid("call '$call' sconosciuto; validi: ${CallTarget.entries.joinToString { it.wire }}")
      return Arm(providers, parsedKind, times, target)
    }
  }
}

/**
 * Cosa resta da iniettare, per provider: un guasto alla volta, un comando nuovo sullo stesso
 * provider sostituisce il vecchio. In memoria e basta: un'app uccisa riparte pulita.
 */
class FaultPlan {

  data class Armed(val kind: FaultKind, val remaining: Int, val target: CallTarget)

  /** Un guasto appena consumato: quale, e quanti ne restano su quel provider. */
  data class Shot(val kind: FaultKind, val left: Int)

  private val armed = mutableMapOf<ProviderId, Armed>()

  @Synchronized
  fun arm(command: FaultCommand.Arm) {
    command.providers.forEach { armed[it] = Armed(command.kind, command.count, command.target) }
  }

  @Synchronized
  fun clear() = armed.clear()

  @Synchronized
  fun isArmed(provider: ProviderId): Boolean = provider in armed

  /** Il guasto per questa chiamata, se ce n'e' uno che la riguarda: lo consuma. */
  @Synchronized
  fun take(provider: ProviderId, request: ChatRequest): Shot? {
    val current = armed[provider] ?: return null
    if (!current.target.matches(request)) return null
    val left = current.remaining - 1
    if (left <= 0) armed.remove(provider) else armed[provider] = current.copy(remaining = left)
    return Shot(current.kind, left)
  }

  @Synchronized
  fun snapshot(): Map<ProviderId, Armed> = armed.toMap()
}
