# Generic provider attempt lifecycle

API (implementation contract, 2026-11-08): package `dev.antigravity.fluidengine.ai.provider`.

```kotlin
interface ProviderAttemptLifecycle {
  suspend fun beforeAttempt(attempt: ProviderAttempt) {}
  suspend fun afterAttempt(settlement: ProviderSettlement) {}
}
class ProviderExecutionContext(
  val executionId: String,
  val purpose: String,
  val lifecycle: ProviderAttemptLifecycle,
  val beforeDispatch: suspend (ProviderDispatch) -> Unit = {},
) : CoroutineContext.Element
// Additive ChatRequest parameter:
val lifecycle: ProviderAttemptLifecycle = ProviderAttemptLifecycle.None
class ProviderAdmissionDenied(val reason: String) : CancellationException
class ProviderObserverFailure(val attemptId: String, cause: Throwable) : CancellationException
```

`ProviderAttempt` contains UUID attemptId, optional executionId/purpose, ProviderDispatch (actual model and encoded reasoning), streaming flag, serialized payload byte count, encoded maxOutputTokens (nullable means unbounded/unknown), tool count, binary input bytes, webSearch/webSearchMaxResults, documents and structuredOutput. Payload bytes are NOT a token estimate or token upper bound. Binary/media, web, document parsing and provider implicit reasoning may require separate tariff bounds; admission must reject unknown bounds when policy requires them. Engine supplies no tariff authority.

`ProviderSettlement` contains attempt, outcome (SUCCESS/FAILED/CANCELLED/NOT_DISPATCHED), certainty (NOT_DISPATCHED/MAY_HAVE_DISPATCHED/RESPONSE_RECEIVED), nullable usage, rateLimit, nullable HTTP status/retryAfterSec, error type, and partialResponse flag. Unknown usage remains null, including rejection and cancellation; never release a monetary reservation merely because usage is absent.

Wrap an entire operation in `withContext(ProviderExecutionContext(executionId, purpose, lifecycle))` to cover router, rounds, web and helper chat requests, including later app title/extractor calls inside that scope. A request-specific lifecycle composes with the context lifecycle (context first; same instance only once). Both hooks are suspend functions. beforeAttempt must atomically reserve before returning; admission implementations must roll back their own incomplete transaction if they throw. Existing beforeDispatch identity/effort audit still runs on every attempt before admission. Audit exceptions are converted to a non-retryable observer control signal.

Each admitted observer receives one settlement invocation in NonCancellable context. A later admission failure settles earlier admitted observers as NOT_DISPATCHED. Settlement observer failures are collected, all observers still run, then ProviderObserverFailure propagates without retry/replay. These control signals subclass CancellationException so existing engine router/orchestrator cancellation barriers propagate them unchanged; apps must handle them explicitly at their operation boundary. A denied observer receives no afterAttempt because it acquired no reservation. Retries/fallbacks create fresh UUIDs. SUCCESS means provider decoding completed; cancellation during delivery can instead yield CANCELLED with observed usage retained.

Scope: chat/generate requests (including web-enabled and helper ChatRequest paths). Catalog GET and legacy transcribe API are not ChatRequest and are not budgeted by this API; callers must not use them for autonomous budgeted generation. No app persistence, prices, source policy or scheduling is implemented here.

## Integration example (no app wiring included)

```kotlin
val adapter = object : ProviderAttemptLifecycle {
  override suspend fun beforeAttempt(attempt: ProviderAttempt) {
    // Validate model, fresh tariff and every chargeable feature; atomically reserve by attemptId.
    // Throw ProviderAdmissionDenied("reason") before returning when admission is refused.
  }
  override suspend fun afterAttempt(settlement: ProviderSettlement) {
    // Atomically settle by settlement.attempt.attemptId. Null usage remains uncertain.
    // Make persistence idempotent for app recovery; never redispatch to repair a failed settlement.
  }
}
withContext(ProviderExecutionContext(
  executionId = persistedExecutionId,
  purpose = "research",
  lifecycle = adapter,
  beforeDispatch = { dispatch -> existingDispatchAudit(dispatch) },
)) {
  // ask(), router calls and app helper/title/extractor ChatRequests invoked here inherit both hooks.
}
```

`ProviderExecutionContext.beforeDispatch` is an additive optional suspend audit callback, default `{}`. It runs before the existing request-specific `ChatRequest.beforeDispatch`; neither is replaced. Keep callbacks idempotent if deliberately attaching the same audit at both levels. The observer interface uses a singleton `ProviderAttemptLifecycle.None` by default.

The context follows structured coroutine inheritance. Work launched into a detached app scope must explicitly carry the context. A more specific purpose can use another context with the same adapter and execution id. Custom `ChatProvider` implementations must implement the lifecycle contract themselves; the built-in adapters enforce it. An app wrapper that catches `CancellationException` must rethrow these typed signals or handle them as terminal admission/settlement outcomes, never as a reason for retry.

`RESPONSE_RECEIVED` means HTTP status/headers were observed; it does not certify billing or provider obedience. `MAY_HAVE_DISPATCHED` conservatively covers transport entry without a response, including DNS/connect failures whose no-send status is not proven. `partialResponse` means at least one SSE payload was seen; it is not a token count. A completed response without usage remains `SUCCESS` with `usage = null`.

The new optional `AiHttp.postJson(..., onResponse: (Int, Map<String, String>) -> Unit = { _, _ -> })` and corresponding `postJsonStream` callback capture status/rate headers before JSON decoding or non-2xx error mapping. Existing streaming `onHeaders` retains its 2xx-only behavior. These transport observations never call app observers from the HTTP reader thread; lifecycle settlement occurs in the caller coroutine.

Admission callbacks run sequentially, context before request. The same lifecycle instance at both levels is called once. Cancellation after successful admission but before transport settles as `NOT_DISPATCHED`. Process death can prevent callback execution; durable app recovery must retain an unsettled reservation rather than infer zero usage. `NonCancellable` protects coroutine cancellation cleanup, not process death, storage failure or an observer that never returns.


Final bounds clarification: `ProviderAttempt.modelCandidates: List<String>` includes all model ids encoded in server-side fallback routing; the app can deny any unauthorized candidate. `dispatch.model` identifies the encoded primary model, not a verified remote billing identity. `inputTokenUpperBound: Int?` is deliberately null; the app must obtain a trustworthy bound from its authorized model/context policy, or deny when one is required. `webSearchMaxResults: Int?` reports only a positive `max_results` actually encoded in the web plugin; Gemini and Groq have no encoded result cap and report null. Even an encoded result cap is not a bound on the number of paid search operations. `structuredOutput` reflects the encoded payload after fallback rather than the original request intent. `maxOutputTokens` is null when omitted, dropped, or invalid/nonpositive.


## Final data signatures

```kotlin
data class ProviderAttempt(
  val attemptId: String,
  val executionId: String?,
  val purpose: String?,
  val dispatch: ProviderDispatch,
  val streaming: Boolean,
  val payloadBytes: Int,
  val maxOutputTokens: Int?,
  val toolCount: Int,
  val binaryInputBytes: Long,
  val webSearch: Boolean,
  val webSearchMaxResults: Int?,
  val documents: Boolean,
  val structuredOutput: Boolean,
  /** Encoded candidates, including server-side model fallbacks; no claim of remote model confirmation. */
  val modelCandidates: List<String> = listOf(dispatch.model),
  /** Unknown without a trustworthy model tokenizer/media bound. Payload bytes are not a token bound. */
  val inputTokenUpperBound: Int? = null,
)
enum class ProviderAttemptOutcome { SUCCESS, FAILED, CANCELLED, NOT_DISPATCHED }
enum class ProviderDispatchCertainty { NOT_DISPATCHED, MAY_HAVE_DISPATCHED, RESPONSE_RECEIVED }
data class ProviderSettlement(
  val attempt: ProviderAttempt,
  val outcome: ProviderAttemptOutcome,
  val certainty: ProviderDispatchCertainty,
  val usage: Usage?,
  val rateLimit: RateLimitInfo,
  val httpStatus: Int?,
  val retryAfterSec: Double?,
  val errorType: String?,
  val partialResponse: Boolean,
)
```
