# Aria app runtime budget API

App-owned implementation in `dev.pampa.pampai.core.assistant.runtime.RuntimeBudgetCoordinator`. Engine contracts remain unchanged. No worker/startup/setup/background enablement is installed. Validation provenance and source freeze are in `aria-runtime-budget-report.md`.

## Execution APIs

```kotlin
suspend fun <T> foreground(policy: ModelPolicy?, purpose: String = "chat", block: suspend () -> T): T
suspend fun <T> job(lease: JobLease, purpose: String, block: suspend () -> T): BudgetExecutionResult<T>
suspend fun <T> helper(identity: ExactModel, block: suspend () -> T): T
suspend fun configure(cap: BudgetCap)
fun configureBounds(policy: RuntimeAttemptPolicy)
suspend fun abandonedForeground(): List<String>
suspend fun recoverAbandonedForeground(ids: Set<String>)
val lastStop: StateFlow<RuntimeBudgetStop?>
```

`foreground` enters persistent priority before preparation, then installs the engine execution context. Nested app preflight inherits that context. Structured router/round/tool/web/retry requests inherit the same lifecycle. Every actual wire UUID is independently admitted and settled. A detached call must enter an explicit app execution scope; detached automatic titles do so with purpose `title`. ModelSelection enters `helper` only on a cache miss, with the explicit helper identity, same execution/lease and purpose `image-helper`. Cache hits do not reserve or call the provider. Encoded web requests are recorded as `web-helper`. Existing primary/helper effort audits and UsageRepository telemetry remain; helper telemetry is not duplicated by the coordinator.

`job` takes the captured durable lease. Every wire validates current job owner/fence/lease, area, captured primary/helper model policy, learning epoch, durable-conversation status and source authorization vector through BudgetLedger. Results are `Completed(value)`, `Yielded(reason, eligibleAt)` or `StorageFailure(attemptId)`. Cooldown yields the persisted eligibility timestamp; other unknown eligibility is null. Caller obtains checkpoints with foundation APIs and schedules a fresh eligible claim. Never loop on Yielded or fall back to ordinary foreground. Cancellation still propagates. Helpers in job scopes must match the captured helper identity. Job calls also persist per-wire exact identity/effort audits.

## Foreground and background policy

ALL global caps cover foreground and background. Background-only caps additionally cover background. Only absence of ANY configured global cap permits explicitly uncapped foreground accounting. Configured but missing/expired global windows deny foreground too: expiry never reopens uncapped mode. Setup UI must explain this global scope before saving caps.

This foreground rule applies equally to ordinary chat and explicitly user-requested `job.execution == FOREGROUND` durable work. A foreground job retains its job ID and every owner/source/model fence even when uncapped; validation runs before the uncapped admission branch. It does not require autonomous area/global setup merely to synthesize a user-requested result. `job.execution == BACKGROUND` ALWAYS requires mandatory global/background/area caps, enabled area setup and allowed models, regardless of purpose. It never enters uncapped mode or falls back after denial.

UNCAPPED_UNKNOWN rows use zero only as an internal schema-compatible placeholder, never an observed token count or trustworthy bound. That state explicitly marks unknown accounting. An overlapping configured cap denies UNKNOWN_PRIOR_TOKENS while those tokens remain unknown; use a deliberate new non-overlapping policy window. Observed uncapped usage is labelled UNCAPPED_OBSERVED. Money remains null, not zero. These calls carry no token/money ceiling guarantee. No profile/evidence job is created for ordinary or temporary chat.

## Bounds, money, settlement and quotas

`RuntimeAttemptPolicy.bounds(attempt): RuntimeAttemptBounds?` must be supplied only from validated local model metadata/policy, covering the complete input including media and relevant reasoning/output charging. Default returns null and denies bounded execution. The actual encoded output limit must be present on every bounded attempt; dropped limits are reevaluated before another wire call. Payload bytes are not tokens. Candidate models outside the selected exact identity are denied, including server-side model fallback routing. Automatic foreground without an exact policy admits the wire primary only, never additional server fallback candidates.

No trusted tariff registry exists in this adapter: every active money cap denies by default. It never uses CostTable, free-tier thresholds or invented search/media bounds. A future explicitly trusted tariff extension must persist provenance/currency/version/expiry and validate every actual encoded feature before money-bounded dispatch. The current adapter does not claim a monetary guarantee or convert provider dollars to an unconfigured currency.

Missing observed usage retains bounded reservations. NOT_DISPATCHED alone releases. Identical settlement replay is idempotent; conflicting replay fails. Observer storage failures are terminal and never replay a model call. Completed responses without usage and cancelled/disconnected transports remain uncertain across database reopen. Explicit observed token usage is retained even when above a reservation.

Quota uses one conservative provider/configured-account bucket shared across contexts/key rotations; no credentials or credential-derived IDs are persisted. Observed Retry-After or exhausted reset headers persist eligibility. A 429 with unknown reset persists LOCAL_BACKOFF_UNKNOWN_RESET with bounded 30-60 second jitter, never a verified provider reset. Cooldown and foreground priority also apply before uncapped dispatch.

## Recovery and app boundaries

`abandonedForeground` lists older-process markers, excluding this process's active IDs. After explicit owner reconciliation on restart, `recoverAbandonedForeground(ids)` removes only those exact abandoned markers. Do not clear markers merely because a call was cancelled; do not use this API without reconciling the abandoned owner. Unsettled attempts remain conservatively charged. No automatic startup recovery is installed here.

`lastStop` exposes execution ID, structured refusal reason and storage-failure flag without raw content. It is diagnostic last-event state: consumers must match execution IDs rather than attribute it to every conversation. At the execution boundary terminal control signals produce Failed rather than user-Cancelled state while preserving partials/receipts. Preflight translates terminal signals into non-cancellation `RuntimeBudgetRefusal`, exposing a draft-preserved message through existing composer exception handling before draft consumption. Inside provider orchestration the original typed cancellation controls remain terminal.

Helper audits before message creation use messageId=-1, and conversationId=-1 when absent; no image/prompt content is stored. Future reader/research/worker/UI integrations are ROOT-owned. Legacy audio transcription/catalog GET are outside the ChatRequest lifecycle and cannot be used as autonomous budget-covered generation. Custom providers must implement the engine lifecycle contract.

## Owned files and verification boundary

New coordinator; existing runtime AssistantEngine/AssistantRuntime/ModelSelection; narrow BudgetLedger policy/recovery APIs and DurableEntities DAO query only (no entity/schema fields); new RuntimeBudgetIntegrationTest and test-local TinyHttpServer; existing QuickCancellationIntegrationTest constructor. Feature SendAdmissionTest/UiCorrectionsTest constructor fixtures were adapted in the source phase but are intentionally excluded from this engine/core-only compiler transfer. Existing Hilt injection uses existing ledger/database bindings.

The helper/cache/router/web/title test uses actual provider lifecycle and app coordinator with synthetic requests, not full production UI/ModelSelection/orchestrator end-to-end acceptance. Existing core image/cancellation tests are part of the core suite; feature UI flows remain for ROOT's later integrated validation. No device/live API/credentials, downstream compile, commit, push or runtime/background approval is part of this validation.
