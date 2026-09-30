package dev.pampa.pampai.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors

/**
 * Il telecomando dell'iniettore di guasti ([FaultInjector]), solo nella build di debug. Serve a
 * vedere sul telefono vero il failover e il foglio "Dettagli" senza aspettare che un provider
 * sbagli davvero. Ogni guasto passa dal mapper e dai codec veri dell'engine: si prova anche la
 * classificazione dell'errore, non solo cosa se ne fa.
 *
 * Uso (l'app va aperta almeno una volta dopo l'installazione o un force-stop: Android non consegna
 * broadcast a un pacchetto "fermo"):
 *
 * ```
 * adb shell am broadcast -a dev.pampa.pampai.debug.FAULT -p dev.pampa.pampai.debug --es kind <kind> --es provider <provider> [--ei count N] [--es call chat|router|any]
 * adb shell am broadcast -a dev.pampa.pampai.debug.FAULT -p dev.pampa.pampai.debug --es kind off
 * adb logcat -s FaultInjector
 * ```
 *
 * - `provider`: `groq`, `gemini`, `openrouter`, o `all` (tutti e tre: nessuno a cui passare).
 * - `count`: quante chiamate di chat di fila falliscono su quel provider (default 1). Un comando
 *   nuovo sullo stesso provider sostituisce il vecchio; `off` spegne tutto.
 * - `call`: `chat` (default) colpisce ogni chiamata senza schema JSON (i giri della risposta, la
 *   riprova senza stream, il titolo, la ricerca web); `router` solo lo stadio 1; `any` tutte.
 *   Il router un 400 se lo mangia in silenzio col suo ripiego: per questo il default lo salta.
 * - `kind`:
 *   - `tool_use_failed`: Groq 400 `tool_use_failed` (in stream: il pezzo `error` a stream aperto);
 *     OpenRouter 400 con l'errore di Groq in `metadata.raw`; Gemini il suo equivalente, il
 *     candidato `MALFORMED_FUNCTION_CALL` (risposta vuota, fine OTHER).
 *   - `model_gone`: Groq 400 `model_decommissioned`, Gemini 404 "is not found for API version
 *     v1beta", OpenRouter 404 "No endpoints found". Attenzione: e' un guasto con conseguenze vere,
 *     l'app riallinea subito il modello scelto (`AiKeyVerifier.markUnavailable`) e il sostituto
 *     finisce nelle impostazioni; per tornare indietro si riavvia l'app e si sceglie di nuovo il modello.
 *   - `context`: Groq 413 "Request too large", Gemini 400 "input token count", OpenRouter 400
 *     "maximum context length".
 *   - `rate_limit`: 429 con 8 secondi di attesa (Groq e OpenRouter nell'header `retry-after`,
 *     Gemini nel `RetryInfo`).
 *   - `server`: 503.
 *   - `stall`: il servizio tace 50 s; se la pazienza della richiesta finisce prima (45 s per uno
 *     stream che non pensa) e' un timeout come quello della socket, altrimenti poi risponde il
 *     servizio vero. Lo Stop deve interromperlo.
 *   - `empty`: una risposta 200 senza una parola che finisce per `LENGTH`.
 *   - `off`: spegne tutto.
 *
 * Il broadcast torna ad adb una riga con cosa e' armato (`Broadcast completed: result=-1, data=...`);
 * ogni guasto iniettato scrive una riga nel logcat col tag `FaultInjector`, con l'errore come lo
 * ha classificato il mapper. Lo stato vive in memoria: un'app uccisa riparte pulita.
 */
class FaultReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val injector = EntryPointAccessors.fromApplication(context.applicationContext, FaultEntryPoint::class.java).faultInjector()
    val command = FaultCommand.parse(
      kind = intent.getStringExtra(EXTRA_KIND),
      provider = intent.getStringExtra(EXTRA_PROVIDER),
      count = count(intent),
      call = intent.getStringExtra(EXTRA_CALL),
    )
    val summary = injector.apply(command)
    if (isOrderedBroadcast) {
      resultCode = if (command is FaultCommand.Invalid) Activity.RESULT_CANCELED else Activity.RESULT_OK
      resultData = summary
    }
  }

  /** `--ei count 3` o, per chi sbaglia, `--es count 3`; assente = null (cioe' uno). */
  private fun count(intent: Intent): Int? {
    if (!intent.hasExtra(EXTRA_COUNT)) return null
    val asInt = intent.getIntExtra(EXTRA_COUNT, Int.MIN_VALUE)
    if (asInt != Int.MIN_VALUE) return asInt
    return intent.getStringExtra(EXTRA_COUNT)?.trim()?.toIntOrNull() ?: 0
  }

  companion object {
    const val ACTION = "dev.pampa.pampai.debug.FAULT"
    const val EXTRA_KIND = "kind"
    const val EXTRA_PROVIDER = "provider"
    const val EXTRA_COUNT = "count"
    const val EXTRA_CALL = "call"
  }
}
