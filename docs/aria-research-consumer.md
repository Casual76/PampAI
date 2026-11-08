# Connected research consumer: implemented API and handoff

## Live implementation handoff to UI author (root transfer received)

Final implementation and core validation are complete; core sources/tests and the serial compiler runner are released to ROOT. See `aria-research-consumer-report.md` and `validation-research-consumer/results.json`: 210 core tests (25 new consumer cases), 264 engine tests freshly executed earlier in this topic, core lint 0 errors/22 warnings, AAR pass. UI/app/worker integration and prior runtime NV remain separately root-owned.

Core source/test and sole core/engine compiler ownership transferred explicitly after the initial design. The following **actual Kotlin APIs supersede sketches below**; inspect `core-assistant/.../research/ResearchContracts.kt`, `ResearchSources.kt`, `ResearchSetup.kt`, `ResearchProfile.kt` for constructor details. Public package is `dev.pampa.pampai.core.assistant.research` (flat package to simplify consumption).

* `ResearchRead(selection: SourceSelection, tool: String, arguments: JsonObject, allowTechnicalRefresh: Boolean=false)` derives stable `grantId`. Selection fields: packageName, authority, domain, account, student?, year?, area. `ResearchSources.discover(): List<SourceCandidate>`, `connect(read,reconnect=false): SourceGrant`, `renew(reads)`, `revoke(grantId)`. Failures throw `ResearchUnavailable(code)`; caller displays typed code. Explicit reconnect uses same ID. No connection enables background.
* `ResearchSetup.areas: Flow<List<AreaConfiguration>>`, `plans: Flow<List<AreaReadPlan>>`, `plan(area)`, `savePlan(plan)`, `configure(configuration,models)`, `configureCap(cap)`, `configuration(area)`, `models(area)`. `AreaReadPlan(area,revision=UUID,reads,provider,model,effort?,helperProvider?,helperModel?,profilingConsent=false)`; save replaces revision and fences active work. Exact read scopes persist in a dedicated DataStore. AreaConfiguration carries frequency/window/caps/Wi-Fi/battery policy; enabled requires separately saved explicit plan and profilingConsent. No inferred consent from interactive consultation.
* `ResearchProfile.management: Flow<List<ProfileItem>>`, `settings`, suspend `detail(id): RetrievedProfile?` (management provenance/dependencies/grants), `exclusions: Flow<List<ProfileExclusion>>`, `retrieve(ProfileQuery,allowedReads: List<ResearchRead>?=null): List<RetrievedProfile>`, `correct(id,field,value,conversationId?)`, `forget(field,grantId?,recordKey?)`, `exclude(ProfileExclusion)`, `reenable(key)`. Management detail is not prompt authorization; retrieve performs source renewal and foundation fences.
* Runner contracts in ResearchContracts: `ForegroundResearch(conversationId,objective,intent,policy,reads,maxRecords=100)`; TrancheResult Progress/Completed/Suspended. Runner implementation and final worker admission signatures follow in this same file before release. No feature/app builds are authorized to this author while UI works.

UI API reconciliation: `ResearchProfile.grants` and `exclusions` are now Room-backed Flows. `AreaResearchPlan(configuration: AreaConfiguration, policy: ModelPolicy, reads: List<ResearchRead>, maxRecords: Int=100)` is exposed through `ResearchProfile.plan(area)`, `savePlan(plan)`, `disable(area)` and `configureGlobal(cap)`, matching aria-profile-ui.md. `setupComplete` in explicitly saved setup is the explicit profiling consent; enabled is checked separately. The lower-level ResearchSetup API remains available. Source discovery and connect/reconnect signatures are unchanged.

Reopened setup review: `ResearchSetup.caps: Flow<List<BudgetCap>>` is implemented from persisted Room caps; no feature DAO access. CV canonical sections are grades/agenda/lessons/communications/absences/notes/materials/documents and are checked before dispatch.

Worker entry signatures now implemented: `ResearchRunner.admitBackground(area,objective,maxRecords=100): String`, `eligibleBackgroundJobs(now,after: JobCursor?=null,limit=20): JobBatch`, `runBackgroundTranche(jobId): TrancheResult`, `cancel(jobId)`. General `runTranche` is shared implementation; worker must use the background-specific entry. Foreground: `admitForeground(input): String`, `resumeForeground(jobId,conversationId)`, `consultTemporary(objective,intent,policy,reads)`. AssistantRequest has optional `research: ResearchTask(intent,reads,resumeJobId?)` and `profileQuery: ProfileQuery`; AssistantEngine invokes the actual runner and guarded targeted retrieval. Source/runner/profile packages have no AssistantRuntime dependency.

Normal questions now use production `ResearchQuestionPlanner.profileQuery(question)` automatically when AssistantRequest.profileQuery is null. It maps bounded school/music questions to canonical fields, with no model/account/source scope inference. `ResearchForeground.select` matches those fields against current explicitly saved area read plans or explicitly connected interactive read scopes. `ResearchConnections` persists the exact connect probe independently of autonomous setup; a configured area's plan overrides its older connection scope. Disabled/revoked grants are not silently reenabled. Natural grades/preferences questions need no literal app brand. Ordinary open/play/write commands remain on the existing action path. Unrelated questions select no profile fields and cause no source reads. Temporary and locked conversations do not receive durable profile context.

Research synthesis includes only pertinent stored profile knowledge and its validated dependency evidence from captured source grants; it rechecks both profile block and exact source proofs before each actual wire attempt and again before committing. Existing ordinary orchestration receives the same bounded targeted block through a provider beforeDispatch guard. There is no whole-profile injection. `ResearchForeground.answer` uses actual admit/resume/tranche APIs, not a second unbudgeted synthesis. Explicit user resume can call `retryForeground(jobId,conversationId)`; unknown eligibility is suspended indefinitely until explicit retry/policy action, rather than a guessed timer.

Implementation files under `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research`: ResearchContracts, ResearchTransport (signed production Binder + hardcoded package/authority/domain/tool/argument policy), ResearchSources (proof acquisition/renewal/revoke), ResearchConnections (interactive scope persistence), SourcePages (Fluidify/CV continuation), ResearchSetup (area plan persistence), ResearchProfile (management/fenced retrieval), ResearchModel (actual provider and bounds installation), ResearchRunner (durable execution), ResearchQuestionPlanner, ResearchForeground (production routing/prompt guards), ResearchBindings (Hilt). Existing integration changes are AssistantEngine, AssistantRuntime request fields, DatabaseModule, DurableFoundation and DAO read queries only; no schema/entity fields changed. Existing budget coordinator/ledger/model-selection implementation is preserved.

Following worker author: select only BACKGROUND IDs, call runBackgroundTranche, honor Suspended.eligibleAt when present, and never infer a reset when null; unknown bounds/tariffs/source state require explicit reconciliation. No startup worker, recurring work, notifications or permission prompts were introduced. Network/battery/cadence and exact windows are persisted configuration for the following worker; this runner does not claim Android scheduling enforcement. Worker must apply those constraints and recheck settings. No foreground service/runtime dependency exists in runner DI.

Bounds currently accept only exact text-only Groq Llama catalogue entries with positive explicit context/output limits and no reasoning flag. The entire catalogue context ceiling is reserved as input, rather than estimating tokens from bytes; actual encoded output cap is checked for every attempt. Unknown models, reasoning/media/document/search/tool charges stay unsupported for bounded calls. This is a conservative local catalogue policy, not verified upstream billing or provider compliance. No fresh catalogue/provider calls were made during this task. All monetary caps remain denied without a trusted tariff, and existing explicit pre-setup uncapped foreground accounting remains labelled no-guarantee. Source TTL cache uses local monotonic deadlines; point-in-time source proofs are not a distributed lock or upstream login certainty.

## Archived initial design snapshot (superseded by implementation above)

The remainder records the initial read-only design and its then-current source observations. Its proposed signatures, ownership status and unexecuted-check statements describe that earlier stage only. Final executed validation and release are recorded in aria-research-consumer-report.md.

Prepared 2026-11-08 from the assignment, full next-brief and current source. **Design only; implementation and compiler ownership have not transferred.** This document is the only file created by this task. Root must explicitly release core sources/tests and the serial compiler before implementation. No polling or elapsed-time release. The eventual delivery must contain the connected production path and meaningful executed fixtures; these declarations are proposed APIs, not implemented services or acceptance evidence.

User-authorized advancement remains valid; earlier runtime/device/source gates remain NV. Background defaults disabled/setup incomplete. No engine/schema/source-repository changes, credentials/config-store access, device/live API calls, commits or publication. Setup/profile UI, WorkManager and startup wiring belong to the following topic.

## Verified current surfaces

Paths below use `C = core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant`, `E = engine/engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai`, and `B = engine/engine-ai-bridge/src/main/kotlin/dev/antigravity/fluidengine/ai/bridge`.

| Actual source | Current API / consequence |
|---|---|
| `B/AiToolClient.kt` | `discover()`, `availability(host)`, suspend `catalog(host)`, `negotiation(authority)`, `run(authority,name,args,confirmed,requestId,language)`. Catalog acquisition negotiates typed v2; `run` attaches the negotiated agreement. `RemoteHost(packageName,authority,label)` is discovery, not a receipt. Host checks caller signature; consumer must also verify discovered package/authority identity before trusting responses. |
| `C/bridge/RemoteCatalogs.kt` | Private client, ordinary dynamic registry and `state: StateFlow<List<ConnectedApp>>`. Research needs its own constrained facade; ordinary tools/actions remain available through their existing path. |
| `C/bridge/SourceBridgePolicy.kt` | Typed capability/catalog and Fluidify account guard; no source-proof verifier or research dispatch allowlist. A tool's action metadata is insufficient. |
| `C/durable/DurableFoundation.kt` | `SourceProofValidator.valid(SourceGrant,now): Boolean` is synchronous/nonblocking. `putGrant` increments version and invalidates dependent state; `refreshProof(id,expectedVersion,generationProof,proposedExpiresAt,now)` renews freshness only. `createJob` currently requires all grants' areas equal job area. `claim/validate/checkpoint/commit/complete/cancel`, `retrieve/correct/exclude/forget/reenable`, and management Flows already exist. |
| `C/durable/DurableEntities.kt` | Schema7 stores grant identity/version, captured job vector/model, bounded step query/cursor/fragment/hash/progress/outcome, evidence, dependencies and exclusions. `jobs()` Flow has LIMIT 200; it is a management feed, insufficient as a complete eligibility scheduler. No durable runner or fenced job-evidence read service exists. |
| `C/di/DatabaseModule.kt` | Singleton foundation and ledger currently use default `SourceProofValidator.Deny`; both must receive the same verified cache. Ledger tariff default must remain Deny. |
| `C/runtime/RuntimeBudgetCoordinator.kt` | `job(JobLease,purpose,block): BudgetExecutionResult<T>`; Completed/Yielded(reason,eligibleAt)/StorageFailure(attemptId). Captured lease checks apply before uncapped foreground admission too. `configureBounds(RuntimeAttemptPolicy)` currently defaults Deny. `foreground(policy,purpose)` and `helper(identity)` already cover ordinary paths. |
| `C/durable/BudgetLedger.kt` | All configured global caps apply to foreground; BACKGROUND additionally needs global/background/area windows and setup/model authorization. Only explicit foreground with no global configuration is uncapped. Unknown usage stays conservative. No trusted monetary tariff is installed. |
| `C/runtime/AssistantEngine.kt` | `execute` enters budget foreground context; `answer` runs preflight and local quick commands, rebuilds conversation, constructs `PromptBuilder`/`AskInput`, invokes `orchestrator.ask`, and persists result/run. Research insertion must retain those admission/cancel/partial/operation-ledger semantics. Legacy `memory.promptBlock()` currently remains independent. |
| `C/runtime/ModelSelection.kt`, `E/provider/Model.kt` | `ModelPolicy(identity: ExactModel,effort: ThinkingLevel?,helper: ExactModel?)`. `ModelInfo` has nullable `contextWindow`, `maxOutputTokens`, feature flags and nonauthoritative prices; no metadata provenance/expiry or complete reasoning charge guarantee. Capture policy once, never look up last selection on resume. |

Prerequisite contracts read: `aria-durable-foundation.md`, `aria-provider-lifecycle.md`, `aria-runtime-budget.md`, `aria-source-proof-contract.md`, `aria-proof-renewal-integration.md`, `aria-next-phase-contracts.md`, assignment and full consumer next-brief. The source-proof document reports 55 assigned tests; engine264 is prerequisite evidence supplied by root. Neither count is a consumer test run. No compiler/test/lint/build ran here.

Downstream production contracts inspected read-only as documentation: Fluidify `app/src/main/java/dev/lelonio/square/bridge/SourceProofRegistry.kt` and `ResultFragments.kt`; CV `android/core/core-assistant/src/main/kotlin/dev/antigravity/classevivaexpressive/core/assistant/tools/SchoolDataTools.kt`. Source repositories remain unchanged. CV's repository AGENTS.md was read; this task's explicit no-subagent instruction takes precedence over its general team guidance.

## Proposed concrete consumer API

New app-owned packages: `C/research/source`, `C/research`, `C/profile`; names below are the intended callable contract for the next UI/worker author. Kotlin sketches omit imports. All returned failures use typed codes and sanitized source metadata, never raw source content in diagnostic state.

```kotlin
data class SourceSelection(
  val packageName: String, val authority: String, val domain: String,
  val account: String, val student: String?, val year: String?, val area: String,
)
data class ResearchRead(
  val grantId: String, val tool: String, val arguments: JsonObject,
  val allowTechnicalRefresh: Boolean = false,
)
data class SourceCandidate(
  val packageName: String, val authority: String, val domain: String,
  val availability: RemoteAvailability, val proofV1: Boolean,
  val account: String?, val student: String?, val readTools: Set<String>,
)
sealed interface SourceAccess<out T> {
  data class Ready<T>(val value: T) : SourceAccess<T>
  data class Unavailable(val code: String) : SourceAccess<Nothing>
  data class AuthorizationChanged(val grantId: String) : SourceAccess<Nothing>
}

class ResearchSources {
  suspend fun discover(): List<SourceCandidate>
  suspend fun connect(selection: SourceSelection, probe: ResearchRead): SourceAccess<SourceGrant>
  suspend fun renew(grantId: String, reads: List<ResearchRead>): SourceAccess<SourceGrant>
  suspend fun revoke(grantId: String)
  // Adapter-private verified types: callers cannot assert trust with a JsonObject.
  internal suspend fun acquire(read: ResearchRead): SourceAccess<CapturedSourceRead>
  internal suspend fun readOne(captured: CapturedSourceRead, continuation: ReadContinuation?): SourceAccess<VerifiedReadPiece>
  internal suspend fun validate(captured: CapturedSourceRead): SourceAccess<VerifiedReceipt>
}
```

`probe.grantId` on first connect denotes the canonical local binding to create; connect verifies it against selection, never accepts an arbitrary substitute. Canonical binding uses length-delimited package/authority/domain/account/student/year/area components, not new UUIDs per connection. Hashing that non-secret canonical tuple is acceptable; credentials are never part of it. Existing disabled binding returns LOCAL_REVOKED; an explicit reconnect operation must be distinguished from ordinary connect/renew. UI reconnect reuses the original ID and preserves exclusions. Discover may show the account advertised by the source; connection still requires explicit matching selection. CV year/section come from user scope, never source UI defaults. Connecting grants read access only and does not configure or enable an autonomous area.

```kotlin
enum class ResearchIntent { DIRECT, LOOKUP, RESEARCH }
data class ForegroundResearch(
  val conversationId: Long, val objective: String, val intent: ResearchIntent,
  val policy: ModelPolicy, val reads: List<ResearchRead>, val maxRecords: Int,
)
sealed interface ResearchAdmission {
  data class Accepted(val jobId: String) : ResearchAdmission
  data class Refused(val code: String) : ResearchAdmission
}
sealed interface TrancheResult {
  data class Progress(val jobId: String, val nextEligibleAt: Long?) : TrancheResult
  data class Completed(val jobId: String, val answer: ResearchAnswer?) : TrancheResult
  data class Suspended(val jobId: String, val code: String, val eligibleAt: Long?) : TrancheResult
  data class Rejected(val jobId: String, val code: String) : TrancheResult
}
data class ResearchAnswer(
  val text: String, val evidenceIds: Set<String>, val limitations: List<String>,
  val policy: ModelPolicy,
)
class ResearchRunner {
  suspend fun admitForeground(input: ForegroundResearch): ResearchAdmission
  suspend fun resumeForeground(jobId: String, conversationId: Long): TrancheResult
  suspend fun runTranche(jobId: String): TrancheResult
  suspend fun eligibleBackgroundJobs(now: Long, after: JobCursor?, limit: Int): JobBatch
  suspend fun cancel(jobId: String)
  suspend fun consultTemporary(input: TemporaryResearch): SourceAccess<ResearchAnswer>
}
```

`TemporaryResearch` contains objective/intent/captured policy/reads/bounds, no durable job ID. `JobCursor` is `(eligibleAt,id)`; `JobBatch` contains bounded job IDs and next cursor. `ReadContinuation` is adapter-owned discriminated Fluidify fragment/page or CV page/detail state. `CapturedSourceRead`, `VerifiedReadPiece`, `VerifiedReceipt` have private/internal construction in signed transport/verification code; none is an externally injectable boolean trust marker.

`admitForeground` requires non-temporary stored conversation and an explicit non-null selected model. LOOKUP is a small bounded consultation; RESEARCH permits a bounded multi-source plan. DIRECT uses existing quick-command execution; runner never becomes a second mutation dispatcher. `resumeForeground` verifies conversation ownership and captured policy instead of replacing it with current selection. No automatic foreground resumption on startup. Follow-up intent can use fenced prior evidence plus a new bounded source query; it cannot assume previous coverage is complete/current.

Worker entry is only `eligibleBackgroundJobs` then `runTranche(jobId)`. Eligibility returns **only** BACKGROUND jobs and is advisory: tranche rechecks type, setup, owner, proofs, constraints supplied by worker and all caps. Background admission must use a separate internal creation method requiring complete enabled area config and exact source scopes; it cannot reuse `admitForeground`, temporary consultation or uncapped execution. Worker never calls `AssistantRuntime.submit`, `foreground`, UI, voice or notifications. A foreground job ID supplied to a background-specific caller is refused, rather than promoted or driven opportunistically.

```kotlin
data class ProfileQuery(
  val fields: List<String>, val sourceGrant: String? = null,
  val evidenceIds: Set<String> = emptySet(), val limit: Int = 12,
  val maxCharacters: Int = 8000,
)
class ResearchProfile {
  val settings: Flow<List<AreaConfiguration>>
  val management: Flow<List<ProfileItem>>
  suspend fun retrieve(query: ProfileQuery): SourceAccess<List<RetrievedProfile>>
  suspend fun correct(id: String, field: String, value: String, conversationId: Long?)
  suspend fun forget(field: String, grantId: String? = null, recordKey: String? = null)
  suspend fun exclude(exclusion: ProfileExclusion)
  suspend fun reenable(exclusionKey: String)
}
```

Management Flow is for UI only; filtered retrieval is the only prompt input. Reenable removes a chosen local exclusion; it neither reconnects revoked sources nor enables scheduling. UI configuration uses existing foundation `configure(AreaConfiguration, List<AreaModel>)` and coordinator `configure(BudgetCap)`, with explicit scopes kept in the selected per-area research plan. No scope is inferred from an account merely being connected. Future UI must persist its explicit area-to-read plan before enabling execution; absent plan refuses background creation. The current schema's AreaConfiguration has no grant/section list, so UI must coordinate that plan representation with this runner rather than assume it is already stored in area settings.

## Verified receipt cache and authorization lifecycle

1. Discovery resolves actual provider package/authority, checks supported domain/package allowlist and signing identity, calls client catalog and requires protocol>=2 plus `typed_results`. Require source_capabilities contract `aria.source-proof`, version1 and supported readTools. Never use ordinary capabilities text, catalog metadata or a model-generated object as proof. Source proof tools are control-plane operations, not model-selectable actions.
2. Create an exact logical read from selected scope and an allowlisted tool/argument schema. Obtain source_capabilities then source_lease. Compare typed receipt contract/version/domain/authority/account/student/incarnation/authorization/capability/lease and exact captured scope to the requested binding and transport identity. CV requires account/studente/anno/sezione. Reject malformed/nonpositive TTL, missing fields, ERROR/PARTIAL control responses or unrecognized hosts; do not switch account.
3. Record local monotonic request start and verified response time. Bound validity by remaining source TTL conservatively, subtracting elapsed round trip and applying a stricter local age ceiling. Source wall timestamps are informational; derive local wall expiry from the conservative remaining duration. Cache validity must also check monotonic time/process identity, so wall-clock rollback cannot extend acceptance. Expired/reopened-process cache fails closed and requires new signed validation.
4. Only the verifier publishes immutable cache entries. Cache indexed by stable grant tuple + `(incarnation,authorization)` + exact allowed logical read; each entry retains capability/lease/original scope and conservative expiry. `generationProof` encodes the two opaque identity components unambiguously, never only account or capability/lease. Grants bind the explicit authorization area; query receipts separately authorize reads. Maintain the complete required read-set per active vector; checking any unrelated receipt for the same account is insufficient.
5. Shared cache implements `SourceProofValidator`: no Binder/network/DB waits under Room. Predicate checks exact grant identity, stable authorization, allowed scope coverage, expected expiry, local monotonic freshness and invalidation marker. Foundation/ledger both receive this same instance via DI. Actual read dispatch independently checks the exact per-query lease; grant freshness never authorizes an arbitrary query.
6. Every source_read validates its returned sourceProof; every completed page and subsequent evidence/profile/prompt consumption requires source_validate against the **original logical scope**. For expired handles acquire new capability+lease, compare stable authorization, validate, then `refreshProof` with expected local version. Refresh only when expiry increases; a current equivalent receipt can satisfy validation without calling refresh. Never `putGrant` per page/query. Renew all required scopes before transaction entry.
7. Observed incarnation/authorization/account/scope change clears cached receipts and uses existing stable-ID grant invalidation/revoke; no temporary fresh ID to avoid tombstones. Explicit reconnection/rebinding, if authorized, uses `putGrant` and preserves canonical IDs/exclusions. Offline/expired/unsupported is UNKNOWN, cannot erase knowledge or implicitly reenable; consumption suspends. Local revoke clears cache before calling transactional revoke and cancels dependent work. A concurrent publish checks an invalidation generation so late validation cannot repopulate trust after revoke.

The signature checked local Binder channel authenticates the source; receipts are not cryptographic self-verifying signatures. Fresh proof is point-in-time source authorization, not proof of upstream session/billing, remote revocation awareness or a distributed lock. Revalidate close to transaction/prompt dispatch; local atomic revoke/forget remains the DB guarantee.

## Read allowlist and continuation

Apply one hardcoded policy at research catalog exposure, planning, lease acquisition and immediately before **each** source_read/continuation/validate dispatch. Validate package+authority+domain, inner tool and exact arguments; unknown fields fail closed. Ordinary registry remains separate. No model-selected arbitrary BridgeCalls, parts URI reads, playback, playlist write/radio, UI/year selection, permissions, reminders, notifications, local memory writes, web or Notes/Codex/Mail/YouTube acquisition.

Fluidify permits history_records/aggregate/track_counts/artist_counts/coverage, explicitly scoped playlists/playlist_details/playlist_tracks and lease-bound result_fragment only. History account/from/until/filters/limit are captured logical scope. Default batching remains50. Fragments require previously returned snapshot under that lease. Cursor/fragment controls cannot alter account/filter/year/scope.

CV permits only registro_dati with explicit account/studente/anno/sezione and allowlisted bounded date/filter/limite fields; default20. `aggiorna=true` requires explicit configured technical refresh for that section and first page only, and never changes global selected year. Continuation cannot reenable refresh. Unsupported historical refresh suspends/reports limitation.

Fluidify checkpoint holds encoding version, original query identity, snapshot, queryHash, content hash, total expected UTF16 length, next offset and accumulated text. Append only contiguous returned UTF16 offsets; hash final assembled string as UTF8 SHA256 before JSON decode. Unicode/surrogate tests must use source-shaped escaped JSON and non-BMP characters. Withhold nextPageCursor until the final fragment is verified; partial bytes never become evidence. Prefer complete structured typed payload when available. Reject conflicting snapshot/query/hash/encoding/offset/size; bound assembly to foundation262144 chars, cursor8192 and query8192. Exceeding bounds yields an explicit partial limitation, never silently skips records.

Actual Fluidify envelope keys are `snapshot`, `hash`, `queryHash`, `offset`, `end`, `totalChars`, `encoding="concatenate_json_text_utf16"`, `truncated`, `fragment`, and (only before completion) `nextTool="result_fragment"`/`nextOffset`. Final envelope may carry `nextPageCursor`; partial result pagination deliberately withholds it. Source snapshot ceiling16M chars is larger than local checkpoint allowance, so the consumer must reject or narrow oversized queries explicitly. Source normally cuts fragments below1000 UTF16 code units and avoids splitting a surrogate pair; consumer still validates those invariants.

CV consumes full typed records first; text-detail truncation is not record truncation. If detail continuation is actually needed, persist same page cursor+detail offset/hash and require stable page hash. `hasMore` with no page cursor is not exhaustion if detail remains. Full-match aggregates have one stable key per logical query/period/revision; do not add identical aggregate totals from successive pages. Preserve observed/synced timestamps, exact time zone/period, coverage, source/account/year and unknown durations. `historyArchiveComplete=false` remains false; unknown imported duration stays null, never0.

Actual CV typed data includes `records`, `aggregate`, `account`, `student`, `year`, `section`, `retrieval`, `returned`, `textSummarized` and optional `authValid`. The current tool withholds nextCursor while text detail remains even though typed records are complete. Its bounded continuation directive supplies `dettaglio_offset` and `dettaglio_hash` in text; parse only that strict control grammar to recover the cursor when needed, never treat text as evidence/proof or execute arbitrary next-tool instructions. Complete source_validate still uses original captured scope. Detail return must deduplicate the already-consumed typed records; `textSummarized=true` alone cannot terminate pagination.

Expired immutable snapshot/handle restarts the current bounded read after proof renewal, retains committed page evidence and canonical stable-key deduplication. Deduplicate by canonical record identity and immutable revision-aware periodKey; exclusions use canonical recordKey across revisions. Never replay mutations. Restart limits are finite and exhaustion yields PARTIAL/UNKNOWN. No claimed lossless resume from an expired cursor.

## Durable tranche and required narrow foundation changes after transfer

Use existing tables; no schema change proposed. Mutations remain in foundation services, not consumer DAO writes. The following gaps require explicit narrow foundation API extension when root transfers ownership:

* Multi-source foreground: permit `createJob(execution="FOREGROUND",area="interactive",...)` to capture grants from different areas, each independently proven. BACKGROUND keeps `grant.area == job.area`. Do not relabel all source grants as interactive or weaken source vectors. Existing ledger area remains job.area; per-source policy is checked independently. Add regressions for cross-area foreground success and background rejection.
* Fenced reads: service `snapshot(lease,now)` returns steps, captured grants and **validated, nonexcluded evidence**, plus canonical deduplicated IDs, only while owner/fence/epoch/vector are valid. Follow-up evidence retrieval additionally requires same authorized conversation/scope and revalidation; use a service API rather than unfenced DAO reads. This enables synthesis and profile retrieval proof planning without exposing whole profile content.
* Eligible background IDs: bounded keyset query ordered `(eligibleAt,id)` filtering execution BACKGROUND, claimable READY/expired RUNNING, never the truncated management jobs Flow. Read-only service validates configuration; claim remains the authority.
* Long calls: claim duration up to existing300000ms with a shorter operation timeout and time left for validation/commit. No implicit owner extension; expired owner discards late result. If renewal is needed, add an explicit fenced API only with root coordination.
* Profile writes: foundation commit must independently gate nonempty automatic items on enabled/setup-complete profiling authorization for the exact source areas represented by their dependencies, including foreground jobs. Interactive evidence can commit with `items=emptyList()` before setup. Existing commit checks types, references, statement precedence and vectors but does not enforce profiling consent. Narrow non-schema enforcement is necessary; consumer-only assertions are insufficient for late config changes.

Plan uses immutable versioned query envelopes inside ResearchStep.query to distinguish acquisition, synthesis and optional extraction. All steps carry a captured grant anchor because current createJob/commit require it; synthesis can reference multiple proven grants through the job vector and fenced evidence snapshot. Its anchor is bookkeeping, not a reduction of scope. Empty-evidence synthesis step writes bounded structured result into step.fragment; it never pretends generated text is source evidence. Source steps retain actual query/continuation separately in their versioned checkpoint envelope. All query fields remain immutable across commit.

One `runTranche` renews required proofs **before claim**, claims with fresh owner UUID, validates, reads checkpoint and performs one bounded read/fragment/model operation. Revalidate source responses and complete vector, then atomic commit yields READY. Resume reads stored step progress and never restarts completed pages absent explicit snapshot expiry. A final fresh claim calls complete only after all steps COMPLETE; stored validated synthesis is returned without another model call. The foreground controller may request a finite number of tranches within its existing turn deadline; worker invokes one tranche and returns. Background has no in-process retry loop.

Yielded(reason,eligibleAt) becomes one checkpointed suspended result with stored eligibility; never invoke foreground/uncapped fallback. Unknown reset stays null externally and requires explicit retry/policy change, not immediate rescheduling loops. Preserve stored partial read state and UNKNOWN on read failure, without deleting prior records. Cancel calls foundation.cancel, and coroutine cancellation releases local work; late commits fail owner/cancel/epoch fences. StorageFailure terminates without replay. Synthesis commit after a long call requires renewed same-authorization proof and active owner; generation change discards content while ledger retains actual incurred usage.

Extraction response schema admits only canonical registered field, kind OBSERVATION/INFERENCE, typed value TEXT/NUMBER/BOOLEAN, period, limitations and a nonempty bounded evidence-ID set from the fenced snapshot. Model identity is supplied by captured policy, not trusted from output. Observations must map to typed source records; model-derived claims remain inference with derivation and uncertainty. Reject unknown evidence IDs, field/type/period mismatches, invented source references and model identity changes. Automatic writes require separate profiling authorization above. Corrections, tombstones and source vector/learning epoch win at commit/retrieval. No model tools can persist profile values.

## Foreground invocation and prompt boundary

After root release, inject ResearchRunner and ResearchProfile into AssistantEngine. Preserve preflight, quick commands, remote kill switch, exact-selection audit, turn cancellation and operation ledger. The app planner selects DIRECT/LOOKUP/RESEARCH from explicit user source requests and bounded consultation rules; `deepRequested` alone never grants permission or changes model/effort. Two selected sources can appear in the initial plan. A request without explicit usable policy/scope returns a clear refusal for research, while ordinary general chat remains on the existing orchestrator path.

For LOOKUP/RESEARCH the actual `answer` path calls admitForeground (or in-memory consultTemporary), then bounded tranches and persists the returned validated ResearchAnswer through existing conversation completion/run/state handling. It must not generate a second unbudgeted orchestrator answer after runner synthesis. Source reads expose no actions; synthesis ChatRequest has tools empty, fixed captured provider/model/effort, explicit maxOutputTokens and server fallback list empty. Existing primary audit composes with coordinator job lifecycle; every extractor/helper/retry model call is inside `budgets.job(capturedLease,purpose)` and the exact helper seam if used.

Targeted profile retrieval takes explicit canonical fields selected for the user's current question, bounded source/evidence filters and character limits. It first collects dependency read scopes, performs signed validation/renewal, then calls foundation.retrieve. Recheck retrieval/local fences and source receipts in the beforeDispatch guard for each actual prompt wire, rather than inject a stale early list. Source/profile text is untrusted quoted evidence, never instructions/policy. No source content is injected on lockscreen; temporary consultations use only session-memory source evidence, no durable profile learning or creation of research rows. Accounting/audit may persist content-free attempts, as ordinary temporary chat already does.

Keep legacy MemoryRepository behavior/data unchanged in this topic. New profile is never supplied wholesale to `memoryBlock`; add a bounded targeted block only when asked. Later legacy-memory UI/tool unification is root's following assignment. Correct/exclude/forget/reenable wrappers call actual foundation methods, not parallel in-memory repositories.

## Bounds installation and DI

New `ResearchBindings` (or narrow existing DatabaseModule edits) provides shared VerifiedSourceReceiptCache -> SourceProofValidator, foundation(db,cache), ledger(db,TariffValidator.Deny,cache), signed transport using AiToolClient, facade, adapters, profile and runner. Cache/transport depend only on application context, clocks and immutable policy; they must not depend on AssistantRuntime/Runner/Foundation. Facade uses foundation; runner uses facade/foundation/coordinator/provider factory; Engine uses runner. This ordering avoids a Runtime -> Engine -> Runner -> Runtime Hilt cycle.

Install a concrete ResearchAttemptBounds resolver before runner's first call via coordinator.configureBounds, not an unused interface. It uses validated exact model context/output policy for the captured ready model, with source/version provenance for accepted metadata. A verified entire context ceiling is a conservative input upper bound; reserve that ceiling rather than deriving token counts from bytes/characters. Output requires actual encoded positive limit within validated total output ceiling. Deny unknown reasoning-inclusive output behavior, binary/media/documents/search/additional charges or unverified context ceilings. ModelInfo optional fields alone do not prove all of these facts; unresolved models explicitly return BOUNDS_UNSUPPORTED before bounded wire. Do not invent static model limits or prices in this initial design.

Resolver must cover each actual encoded retry/candidate, reject a dropped output cap, and compose with the app's shared resolver rather than overwrite it with a job-specific mutable global closure. Keep validated policies keyed by exact identity and features; installation does not enable background. Existing uncapped foreground remains coordinator-owned and labelled no-guarantee; never request it to escape a bounded denial. Money caps continue to fail closed without trusted tariffs. During implementation, verify available local metadata and adapter wire semantics; document supported identities and concrete provenance or the exact unsupported reason. No online catalogue/account calls are authorized here.

## Integration fixtures after explicit compiler transfer

| Connected fixture | Required observable result |
|---|---|
| Signed fake transport -> facade -> real Room -> runner -> foreground branch; two sources and follow-up | Real calls/checkpoints/references; fixed primary/helper/effort, no isolated mock-only API; default50/20 batching preserved. |
| Old/missing typed/proof capability, spoofed host, wrong account/student/year, unknown/misclassified write | Refusal before read transport; no legacy-text proof fallback or account switch. |
| Short TTL renewal; changed authorization/incarnation; disabled binding; forged/stale receipt | Same-state preserves ID/version/epoch/evidence/job progress; real change invalidates; denied receipt cannot populate validator. |
| Unicode fragments, wrong offsets/query/hash, cursor withholding, expiry/restart, detail-only CV, repeated aggregates | No premature evidence/cursor; bounded restart/deduplication; full aggregate counted once; unknown duration/coverage retained. |
| Kill after page commit, competing claims, cancellation/forget/revoke barriers at source and synthesis return | Durable checkpoint reclaim; no duplicate committed records; late content inaccessible; usage retained. |
| Pre-setup foreground multisource and background with same grants | Foreground works with explicit model/proofs; no profile writes without profiling authorization; BACKGROUND refuses absent setup/caps and cross-area grants. |
| Actual bounds installation, configured cap, dropped cap retry, unknown media/reasoning,429, uncertain cancelled usage/reopen | Admission before every wire; Yielded persisted once; no fallback bypass, fake tariff or token-bytes estimate. |
| Temporary lookup/research; profile retrieval/correction/exclusion after restart | No durable research/evidence/profile content; targeted fields only; source revalidation and statements/tombstones prevail. |
| Extraction with invalid evidence/kind/canonical value/period; consent disabled during call | Invalid output rejected; late automatic commit denied; structured valid output has proven dependencies. |

No tests/builds are prepared or executed by this initial document delivery. After root transfer use serial JBR/external build root/maxworkers1/no-daemon/inprocess/nonincremental as assigned; preserve the three root lint fixes and all concurrent changes. Report actual executed new cases separately from retained/UP-TO-DATE/historical results and failures. Final implementation must list wired entry points/files, API deviations, meaningful checks and remaining live/runtime NV, then release all owned compiler processes/source ownership to root explicitly. This document does not release the current author or claim phase completion.
