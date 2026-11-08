# Aria finite silent work — connected implementation and design archive

Current status: ROOT explicitly transferred app/core/feature writes and the sole runner after the UI release. Ordinary WorkManager scheduling, startup factory wiring, DataStore status, guarded BACKGROUND execution and minimal observed UI status are implemented and validated. Final evidence, actual API behavior, conservative limits and explicit source/runner release are recorded in `aria-workmanager-report.md`. No live provider/source/device acceptance or self-approval is claimed.

The remainder of this document preserves the pre-transfer design and its original ownership record.

Archived status: design only, 2026-11-08. No production, dependency, test or build writes and no compiler runs are authorized until ROOT explicitly transfers app/core/feature and the runner. ROOT has confirmed the core reader's release and has transferred compiler/feature ownership to the UI author for feature tests, screenshots, app Hilt assembly and lint. Worker ownership remains READ + this new document ONLY until ROOT transfers it after the explicit UI release. This document does not release that owner or claim implementation acceptance. Implementation and verification remain required after transfer.

Reader final re-read: `aria-research-consumer-report.md`, frozen ResearchRunner, ResearchProfile, ResearchConnections and ResearchQuestionPlanner. The reader reports 210 core and 264 engine tests passed and core lint 0 errors; these are prerequisite evidence, not tests executed by this worker topic. Runner now injects ResearchProfile as its seventh collaborator. Its synthesis uses targeted persisted profile evidence restricted to the captured reads, with profile/source guards before prompt dispatch. Preserve that path when adding BACKGROUND priority checks. ResearchConnections stores explicit interactive scopes independently of autonomous setup; the worker must not treat those scopes as an enabled AreaReadPlan. Public background admission/eligibility/tranche/cancel signatures remain as listed below. No transfer is inferred from this re-read.

## Actual contracts inspected

- `research/ResearchRunner.kt`: `admitBackground(area, objective, maxRecords)`, `eligibleBackgroundJobs(now, after, limit)`, `runBackgroundTranche(jobId)`, `cancel(jobId)`. Admission captures the explicit plan revision and exact primary/helper policy. One tranche performs one source piece or synthesis, with Room checkpoints and lease fences; completion requires a subsequent claim. Background validates configuration and active global/background caps. Temporary/foreground APIs are separate and must never be called by a worker.
- `research/ResearchSetup.kt`: DataStore `aria_research_plans`, `plans`, `areas`, `caps`, `plan`, `configuration`, `models`, `savePlan`, `configure`, `configureCap`. Plan saves and configuration changes run through `foundation.changePolicy`, invalidating active jobs. There is no scheduling metadata store or reconciliation service yet.
- `research/ResearchProfile.kt`: settings/grants/exclusions flows and public mutation services. Profile management is metadata, not prompt evidence. Disable uses setup; forget/exclude/revoke fence durable work.
- `durable/DurableEntities.kt`: `AreaConfiguration` already persists cadence (default 24h), Wi-Fi/unmetered preference (true), battery-not-low (true), enabled/setup flags (false), explicit area budget window and limits. Room schema stays 7. Jobs already persist state, eligible time, lease owner/deadline, fence and cancel version; steps persist cursor/fragment/progress. DAO eligibility is BACKGROUND-only and excludes unexpired claims.
- `durable/DurableFoundation.kt`: job/steps reads, bounded keyset eligibility, claim/commit/complete/cancel. Any policy change currently invalidates all unfinished jobs, not merely one area's jobs. Scheduler reconciliation must respect that behavior.
- `runtime/RuntimeBudgetCoordinator.kt`: exact dispatch identity, configured bounds default deny, budgeted job lifecycle, conservative unsettled reservations. `abandonedForeground()` and `recoverAbandonedForeground(ids)` exclude current-process IDs. Ledger checks foreground priority before background model reservation. These APIs do not presently guard source reads against foreground priority.
- `app/PampaiApplication.kt`: Hilt application with a process `SupervisorJob` scope; existing remote config, source registry and model-maintenance startup remain separate. No WorkManager configuration or scheduler entry point exists. Existing discovery never constitutes profile authorization.
- `feature/.../profile/ProfileViewModel.kt`, `ProfilePresentation.kt`, `UpdatesRoute.kt`: explicit source/model/area setup, cadence and constraints controls already exist. Updates screen currently always displays unavailable last/next timestamps and says updates are unavailable. Area save temporarily disables before changing the plan, then writes explicit configuration/models. The scheduler will consume these contracts without changing chat selection.
- `core-assistant/build.gradle`, `app/build.gradle`: Groovy, compileSdk 36/minSdk 29, Hilt/KSP, Room/DataStore and coroutines. Core already has Robolectric/Room testing. No WorkManager dependency exists.

## Proposed production boundaries after transfer

New core package `research.scheduling` owns `ResearchScheduler`, `ResearchScheduleStore`, `ResearchScheduleStatus`, a finite tranche executor and `ResearchWorker`/`ResearchWorkerFactory`. Feature consumes pure Kotlin status types and `Flow<List<AreaScheduleStatus>>`; it never receives WorkInfo or a raw DAO. Injectable clock and a small work enqueue/cancel boundary allow deterministic tests; the actual Android adapter uses WorkManager.

Scheduler metadata uses a separate Preferences DataStore (`aria_research_schedule`), not Room entities/schema. One serialized row per area stores policy fingerprint, cycle identity, active BACKGROUND job ID, requested work ID, attempt/success timestamps, next eligible time, human reason enum, state and monotonic metadata revision. Store no prompts, source payloads, proof handles, credentials or model output. Fingerprints hash canonical local policy metadata; never hash credentials.

Room remains authoritative for job completion, checkpoints, fences and accounting. Metadata is a recoverable scheduler index, not evidence of success. A persisted queued record is upgraded to ENQUEUED only after enqueue succeeds/WorkInfo confirms it. A stored running record on reopen is reconciled against Room and WorkManager before displaying current execution. Null time means unknown, never midnight or an invented provider reset.

Public service shape:

```kotlin
interface ResearchScheduling {
  val statuses: Flow<List<AreaScheduleStatus>>
  suspend fun reconcile() // local policy/status only; no source/model calls
}
// Proposed status, not yet implemented:
data class AreaScheduleStatus(
  val area: String,
  val state: ScheduleState,
  val reason: ScheduleReason?,
  val lastAttemptAt: Long?,
  val lastSuccessAt: Long?,
  val nextEligibleAt: Long?,
)
```

Do not expose work/job/fingerprint/claim IDs as user text. States distinguish OFF, SETUP_REQUIRED, SUSPENDED, ENQUEUED, RUNNING, WAITING and COMPLETED; success timestamps describe completed cycles only, not setup/proof renewal/page progress. `nextEligibleAt` is an earliest eligibility, not a guaranteed appointment.

## Startup and DI construction

Use ordinary `androidx.work:work-runtime-ktx:2.12.0`; matching `work-testing:2.12.0` in tests. Version and configuration checked against official Android documentation below. No Hilt WorkManager integration dependency is necessary: a custom injectable `WorkerFactory` constructs only `ResearchWorker(context, params, executorProvider)`, returning null for other classes.

`PampaiApplication` implements `Configuration.Provider`. Its configuration obtains an injected factory, which holds `Provider<finite executor>` rather than eagerly constructing the scheduler. The scheduler takes a lazy WorkManager provider/getInstance adapter, never an eager WorkManager singleton in the Application injection graph. `super.onCreate()` completes Hilt injection before starting reconciliation in the existing process scope. Remove only `androidx.work.WorkManagerInitializer` metadata under the merged `androidx.startup.InitializationProvider`; retain other initializers. No explicit `WorkManager.initialize` and no duplicate initialization.

One owned observer job starts once in the application process scope. Combine local settings, plans, caps, grants and exclusions (plus scheduling status changes that actually require reconciliation), use distinct policy snapshots and serialized reconciliation. Proof expiry refreshes must not constantly replace work: policy fingerprint includes grant identity/enabled/version/authorization, not renewable proof expiry. WorkManager observer changes update observed status without forming an enqueue feedback loop. Cancellation propagates; storage failures produce safe status and no acquisition. No GlobalScope, polling loop or blocking wait.

Before scheduling, reconcile abandoned foreground ownership once through coordinator APIs, in this application's normal single-process runtime only. Check the manifest/runtime process assumption; never clear this process's markers, arbitrary IDs, or attempt reservations. A stale marker belonging to an unconfirmed live process remains suspended. This cleanup only removes confirmed abandoned priority markers; it cannot establish that a remote request stopped or refund uncertain use.

## Local admission and reconciliation rules

Missing area/configuration, disabled area, incomplete setup, absent/invalid plan, missing consent, blank model, invalid provider/helper policy, empty or mismatched reads, revoked/missing grants or exclusions means no autonomous calls. Reconcile cancels old per-area work and records a safe reason. No setup connection/discovery is initiated here.

Enabled scheduling additionally requires exact allowed model rows (including helper), positive configured area/global/background request/token caps and explicit usable windows. Money caps without authoritative tariff/currency remain suspended; bounds stay denied unless validated local policy supports the exact attempt. Scheduler may report safe local refusals before acquiring sources, but dispatch admission remains the ledger's authority. Expired configured caps are not silently ignored, and later windows cannot bypass an earlier expired policy. No recurring budget replenishment is implemented: the current UI authorizes explicit fixed windows only. A future recurring-window feature would require separately persisted explicit consent.

Future explicit windows may schedule a local wake at their intersection start; expired/unknown/unsupported windows suspend. Settings/model/scope/consent/constraint changes invalidate the old fingerprint and cancel pending work. Revoke/forget/exclude/cancel fences remain effective against responses already in flight. Reconcile also checks a stored job's Room state/epoch so global policy invalidation cannot be mistaken for resumable progress.

Use a unique periodic ordinary safety wake per enabled eligible area (`aria.schedule.<area hash>`, configured cadence, at least 15 minutes) and ordinary one-time continuation slots (`aria.tranche.<area hash>.<generation>.<slot>`). The safety wake is necessary to repair death between metadata writes and enqueue: it reconciles locally, then enters the same finite executor only if persisted eligibility allows. It does not bypass a suspended state or admit an independent cycle. DataStore next eligibility, not the periodic tick, governs cadence. Completion persists success + cadence; periodic wake repairs a missing next-cycle slot, including when its own tick precedes that eligibility.

Crucial enqueue policy: periodic policy UPDATE preserves an in-flight invocation; each one-time successor uses its own persisted slot and KEEP. Never call REPLACE on the currently running unique name, and never enqueue a continuation with KEEP on that still-running same name (which loses it). Allocate/persist successor slot before enqueue and repair it idempotently on reconciliation, cancelling stale generation tags. Changed policy cancels old-generation running work; ordinary completion must not self-cancel. A serialized process executor and Room atomic adoption/admission prevent a safety wake and continuation from creating separate jobs. Retain only bounded current/next slot metadata and let WorkManager prune completed records normally.

## Finite execution and crash recovery

At worker entry reload all persisted authorization, constraints and policy. Never trust inputData as authorization; input contains area/generation/slot identifiers only. If stale, return successful no-op after local cancellation/status reconciliation. Respect OS constraints again via request constraints (UNMETERED when wifiOnly, CONNECTED otherwise, battery-not-low as configured). Android unmetered is the precise platform guarantee; it is not proof of physical Wi-Fi. No expedited, foreground worker, notification, speech, overlay, UI launch, permission request or `AssistantRuntime.submit` path.

Each invocation executes at most one `runBackgroundTranche`, bounded by a finite wall deadline below ordinary worker limits (proposed 150 seconds; runner synthesis is already bounded at 120 seconds). Read/page/step/record limits remain runner/source invariants, with a finite number of continuation slots per cycle bounded by plan steps and existing 256 operations per step. No internal sleeps, model retry loop or source crawl loop in worker. Progress persists checkpoint then schedules a successor; success records completion then next cycle no earlier than success + configured cadence.

Resume the area's existing BACKGROUND job before admission. A DataStore pointer alone is insufficient: death between Room admission and pointer persistence can orphan a job. Add a schema-free foundation query to adopt an unfinished BACKGROUND job for the area (including future eligible and unexpired claimed jobs); use deterministic cycle identity/idempotent admission or an atomic Room get-or-create boundary to prevent duplicate new jobs. `eligibleBackgroundJobs` alone cannot find a future-due or leased orphan; scanning only its first page is not sufficient. Room transactions must not encompass source calls. Preserve the runner API separation and never recreate an existing job's completed steps.

If a job's claim is live, schedule no earlier than its lease expiry; do not steal it. Expired claims reopen through existing fence increments. Duplicate WorkRequests, metadata/enqueue crashes and reopen must converge on the same job/checkpoints. In uncertain model completion, existing reservation stays charged and the worker must not blindly dispatch synthesis again. New admission/idempotence details must be rechecked against the reader's final runner code immediately after transfer.

`TrancheResult.Progress` means continue the same job, not a successful cycle. `Completed` with durable DONE means lastSuccessAt may advance once. `Suspended(code, eligibleAt)` with known cooldown eligibility schedules one future continuation; null/Long.MAX_VALUE eligibility remains suspended pending explicit policy/availability change, never a repeated model call at each cadence. Foreground priority may use a clearly local future recheck rather than claiming a provider reset. Unknown reset stays unknown even when coordinator has persisted a labelled local backoff.

Foreground priority is currently enforced by the model ledger, but source acquisition/renewal occurs before it. After transfer add a coordinator/foundation read-only priority check and a BACKGROUND-only runner guard before each source wire and admission renewal, without routing through foreground APIs. Recheck after yields and before each synthesis wire (ledger remains authoritative there). A preflight alone cannot cancel an already dispatched source/model call; late results retain fences and honest accounting. Exact implementation location must match reader's final source/runner boundaries; source-repo and engine files remain forbidden.

Cancellation/deadline returns without replaying uncertain dispatch. Preserve job checkpoint and charge reservations; record interrupted status in NonCancellable local cleanup only if the generation still matches. WorkManager retry may be used only for local pre-dispatch infrastructure failure with bounded backoff; not to retry unknown model usage, invalid authorization or budget denial. Crash at any boundary must be covered by reopen tests.

## Minimal profile wiring

Add schedule-status flow to ProfileViewModel and its existing state. Replace UpdatesRoute's fixed unavailable line with observed state/reason, last attempt, last completed update and next eligible time. Before any observation use a literal unavailable state. Enqueued means waiting for Android/constraints, not execution. Keep existing Fluid list organization, form/picker, exact model selection, notices and temporary semantics. Area save reports choices saved, then actual scheduler observation; it must not claim a completed update. Display local reason translations (budget window, foreground priority, source login/unreachable, revoked authorization, unsupported model bounds, unknown tariff, unavailable storage) without raw codes/JSON/IDs.

## Verification after explicit runner transfer

No tests have been written or executed in this design phase. Re-read current reader/UI changes first. Implement and run focused scheduler, real WorkManager and Room-backed tests:

- Disabled/absent setup startup: zero enqueue, acquire, renew, model calls; source installation does not enable work.
- Per-area cadence, unmetered/connected and battery constraints; future windows and fixed expired caps; no inferred reset/replenishment.
- Runtime setting/model/scope/consent changes; stale generation cancellation; revoke/forget/late response fences; global invalidation across areas.
- Adoption after death between admission and metadata write; duplicate requests; expired claim; resume cursor/checkpoint; completed synthesis not replayed; uncertain attempts retained.
- Known cooldown yields future work with no immediate calls; unknown reset/storage/bounds/tariff refusals remain suspended; background-only filtering.
- Foreground priority before source and model wires; abandoned marker recovery excludes live same-process ownership and retains charges.
- Real WorkerFactory construction and custom Configuration.Provider startup; merged manifest initializer removal; actual WorkManager constraints/continuation behavior via TestDriver or TestListenableWorkerBuilder plus Room fixtures.
- Profile observed timestamps/state/reason; no success attributed to setup/enqueue; existing source/model UI flows preserved. Static forbidden-path inspection plus finite-worker execution ensures no speech/notification/foreground service/permission/temporary/AssistantRuntime path.

Then run assigned pertinent core/feature/engine/bridge tests, app assembly and relevant lint on the sole authorized JBR runner: maxworkers1, no-daemon, external build root, Kotlin in-process, nonincremental. Record executed versus retained/UP-TO-DATE results, logs, source manifest, APK hash and limitations separately. No live API/authenticated source/native/device acceptance follows from synthetic fixtures. Release owned sources and every runner process explicitly to ROOT; no commit/push or self-approval.

## Official references checked for this design

- [WorkManager releases and 2.12.0 dependency coordinates](https://developer.android.com/jetpack/androidx/releases/work).
- [Custom configuration and on-demand initialization](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration): remove default initializer and provide Application Configuration.Provider.
- [Unique work and lifecycle management](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work): unique names and policy govern replacement/continuation.
- Assignment's [ordinary work definition reference](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): cadence/Doze execution is best effort, not a precise schedule.
