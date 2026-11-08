# Aria durable foundation API

App-owned package `dev.pampa.pampai.core.assistant.durable`. Root explicitly transferred the sole compiler runner on 2026-11-08. Local build/test results are recorded separately in aria-durable-foundation-report.md. Existing runtime NV gates stay open. Schema 6 -> 7 is additive Room AutoMigration with compiler-generated exported 7.json. No provider/device/runtime validation is implied.

`DurableFoundation(db, proofs = SourceProofValidator.Deny)` is the transactional research/profile service. `BudgetLedger(db, tariffs = TariffValidator.Deny, proofs = SourceProofValidator.Deny)` is transport-independent before-wire admission/settlement. No service schedules or calls providers. Background configuration defaults disabled/setup incomplete. Construct with the shared PampaiDatabase; DI supplies deny-by-default foundation and ledger.

Source APIs: `putGrant`, `revoke`, `createJob`, `claim`, `commit`, `checkpoint(lease, now)`, `validate`, `cancel`, `complete`, `retrieve`, `correct`, `exclude`, `forget`, `reenable`, `configure`. Grant scope includes package, authority, exact account/student/area/year. Every grant update increments its local version, never claims external authentication validity. Jobs capture ModelPolicy and normalized scope vector. `SourceProofValidator.valid(grant, now)` must validate source-owned generation proof from a locally available verified adapter receipt; it must be nonblocking, must reject unknown capability, and is called inside commit/retrieval transactions. Default denies all source data. An opaque string alone proves nothing.

Claims return `JobLease(job, owner, fence)`; commit requires matching fence/cancel epoch, unexpired owner lease, current learning epoch and source vector. Steps retain cursor, fragment offset/text, content hash and progress; evidence is record-key deduplicated, full-match aggregates use a stable aggregate record key. Read failures persist UNKNOWN and cannot delete prior records. Temporary or missing conversation IDs fail at create, claim and commit. Foreground jobs require no background setup.

Profile and evidence values declare TEXT, NUMBER or BOOLEAN; numbers remain canonical decimal strings with explicit units in evidence. Profile retrieval requires explicit field keys and optional source/evidence filters, a bounded limit and text character allowance; the caller must still enforce its actual model token budget. Statements dominate automated values. Corrections/exclusions advance a persistent global learning fence and cancel unfinished jobs. Canonical field/record tombstones persist until explicit re-enable. An unmapped exclusion blocks all automated acquisition; no semantic substring matching is claimed. Dependent inferences are invalidated by evidence/field dependency. No source app is mutated. Legacy memories remain untouched; runtime unification is a later integration.

Budget API: `reserve(AttemptReservation, now)` returns Admission; admission is the only before-wire authority. Supply a unique actual attempt ID, exact execution/job/area/provider/model/purpose, provider/account quota key and conservative upper bounds. Configure persisted global and background `BudgetCap` windows plus per-area background caps. No implicit window reset: callers explicitly configure new windows from verified policy. `setForeground`, `cooldown`, `settle` persist priority, eligibility and use. Reservation immediately assumes MAY_HAVE_DISPATCHED: process death between callback and socket cannot free it. Only explicit NOT_DISPATCHED settlement releases it. Observed settlement is idempotent; conflicting duplicate settlement is rejected. Observed overruns remain charged and explicit. Money uses integer micro-units and checked arithmetic; active money caps require trusted, current, feature-bounded matching tariff proof. Historical CostTable is never read.

Integration: map engine ProviderAttempt to conservative app bounds (payload byte count alone is insufficient), call reserve before transport and translate denial to ProviderAdmissionDenied. Map ProviderSettlement preserving unknown usage/certainty and verified retry eligibility. Source adapters must provide verified proof receipts before unattended source work can pass. Worker/UI/runtime integration is outside this assignment.

## Concrete call sequence

```kotlin
// Source adapters must replace Deny only with a verified source-generation receipt validator.
val foundation = DurableFoundation(db, sourceProofValidator)
val ledger = BudgetLedger(db, trustedTariffValidator, sourceProofValidator)
foundation.putGrant(SourceGrant(
  id = "music/account/student/year", app = "source.package", authority = "source.authority",
  account = "exact-account", student = null, area = "music", year = null,
  enabled = true, generationProof = sourceOwnedOpaqueProof, proofExpiresAt = verifiedExpiry,
))
val job = foundation.createJob(
  id = jobId, conversationId = durableConversationId, area = "music", intent = "RESEARCH",
  execution = "FOREGROUND", objective = objective, policy = capturedModelPolicy,
  grantIds = listOf("music/account/student/year"),
  plan = listOf(ResearchStep(jobId, 0, "music/account/student/year", normalizedBoundedQuery)),
  maxRecords = 100, now = now,
)
val lease = foundation.claim(job.id, ownerId, now, leaseMillis = 30_000) ?: return
// Revalidate immediately before a source read; adapter verifies generation again after the read.
if (!foundation.validate(lease, now)) return
// commit returns false on ownership/auth/forget/temporary fences. It never treats failures as empty.
val accepted = foundation.commit(lease, checkpoint, syntheticEvidence, profileWrites, now)
// Commit yields READY; a fresh claim is required for the next tranche or complete().
```

`ModelPolicy` is captured as exact provider/model, effort and optional helper provider/model columns. Background configuration stores an explicit area model allowlist, cadence, request/token/optional monetary cap and exact cap window. Settings/jobs/management-profile are Room Flows. An absent area row is disabled/setup incomplete. Network/power preferences are data for the future worker; this service neither schedules nor checks device constraints.

A lease must never be reused after a successful commit. Record IDs and period keys must be stable across pagination/restarts; full-match aggregates need one canonical aggregate record key, never a fresh per-page ID. Evidence metadata includes units, exact period/timezone, unknown-capable observed/sync timestamps, coverage/freshness, provenance and hash. Adapter fragment assembly/hash verification is a separate follow-on contract. This store does not infer real source freshness from a successful local transaction.

The ledger returns structured denial reasons. Duplicate attempt IDs always deny another wire attempt, even if the original reservation was released; use a new actual-attempt UUID for a real retry. Background reservations require a currently running job and matching owner/fence, current source proofs and captured primary/helper identity, explicit global/background windows, enabled area setup and allowed model. Reservations remain conservative across reopen. Foreground activity rows intentionally survive restart; operation recovery must explicitly end abandoned activity, preserving priority until reconciliation.

`ObservedSettlement` accepts certainty `NOT_DISPATCHED`, `MAY_HAVE_DISPATCHED`, or `RESPONSE_RECEIVED`, nullable observed tokens/money and an error label. Missing observations retain their corresponding reserved bound. NOT_DISPATCHED with nonzero observations is rejected. The callback key plus complete payload is fingerprinted; identical replay returns the existing row and conflicting replay fails. The current service does not automatically refine a terminal unknown settlement later; reconciliation must preserve conservative charges. Persist verified Retry-After/reset via `cooldown`; shorter later values cannot shorten an existing cooldown.

Global epoch invalidation is intentionally conservative: correction/exclusion/grant changes cancel all unfinished jobs, while affected evidence/profile is explicitly invalidated or forgotten. Canonical exclusions can suspend ambiguous broader acquisition/retrieval until explicit re-enable. Tombstones contain keys only. No substring-based semantic erasure or general semantic inference matcher is claimed.


## Before-wire adapter inputs

```kotlin
val admission = ledger.reserve(AttemptReservation(
  attemptId = actualAttemptUuid, executionId = executionId, jobId = lease.job.id,
  area = lease.job.area, provider = actualProviderName, model = actualModelId,
  purpose = actualPurpose, quotaKey = providerAndExactQuotaAccount,
  background = lease.job.execution == "BACKGROUND",
  inputTokenBound = conservativeVerifiedInputBound, outputTokenBound = encodedBound,
  tariff = trustedCompleteFeatureTariff, jobFence = lease.fence, jobOwner = lease.owner,
), now)
if (!admission.admitted) throw ProviderAdmissionDenied(admission.reason)
```

The runtime adapter must obtain conservative input bounds separately: the generic engine deliberately supplies null rather than inventing a payload-bytes/token conversion. The trusted tariff validator must enforce one common configured currency/micro-unit across the ledger; no historical free quota or implicit currency conversion is performed. Background job identity is compulsory; foreground calls without a durable job may reserve against global caps (ephemeral runtime integration must avoid attaching temporary conversation content to durable research/profile rows).

Room has 15 new indexed/normalized tables: aria_grants, aria_state, aria_areas, aria_area_models, aria_jobs, aria_job_grants, aria_steps, aria_evidence, aria_profile, aria_dependencies, aria_exclusions, aria_budget_caps, aria_attempts, aria_cooldowns, aria_foreground. No large JSON snapshots replace these relationships. The low-level DAO is a persistence implementation surface; all runtime mutations must use the services.

Mutable source records must use an immutable source revision/snapshot component in `periodKey`, while keeping `recordKey` canonical across revisions for exclusion matching. Reusing the identical grant/record/period tuple deduplicates to the stored evidence and retains its original timestamps/coverage; it does not silently overwrite evidence already supporting an inference. Source adapters own that canonical revision mapping and fragment/hash verification. Unknown imported durations must remain nullable/unknown in the adapter's typed value and provenance; this foundation never converts them to zero or merges aggregate totals.

## Proof freshness renewal (root integration extension)

```kotlin
suspend fun refreshProof(
  id: String,
  expectedVersion: Long,
  generationProof: String,
  proposedExpiresAt: Long,
  now: Long,
): Boolean
```

`SourceGrant.generationProof` and the captured JobGrant value identify the source's stable opaque **authorization** identity, including process incarnation; they are not the short-lived per-query capability/lease ID. Source adapters keep receipt/lease/query details in their verified cache. `SourceProofValidator` must verify the proposed receipt-backed grant against that cache, including exact caller/scope, source incarnation+authorization, monotonic receipt age and proposed expiry. A mere same-account string or same opaque identity without a verified fresh receipt is insufficient.

Call `refreshProof` when a new verified short-lived receipt extends freshness for the same enabled source authorization. It atomically checks exact local version and authorization identity, requires a strictly increasing expiry later than `now`, validates the proposed grant with SourceProofValidator, then updates only `proofExpiresAt`. It may renew an expired grant when a fresh receipt verifies; it never uses the expired proof as verification. No local authorization version, learning epoch, job/vector/checkpoint/evidence/profile data or revision changes. Default Deny rejects renewal. Missing/disabled grants, blank/different identity, version mismatch, stale/non-increasing expiry and unverified receipt return false without mutations. Validator exceptions propagate and roll back.

Any change of account/student/area/year/authority/package, actual source authorization state or process incarnation still uses `putGrant`/`revoke`, preserving invalidation. That distinction prevents ordinary 60-second capability renewal from destroying resumability. Fresh source reads remain independently tied to their exact query lease; refreshing this grant cannot authorize another query or make old source data fresh. This API changes no entities/schema and requires no migration.

```kotlin
val refreshed = foundation.refreshProof(
  id = grant.id, expectedVersion = grant.version,
  generationProof = verifiedReceipt.authorization,
  proposedExpiresAt = verifiedReceipt.expiresAt, now = now,
)
// A false result must suspend/reconcile. Never fall back to accepting an expired grant.
```
