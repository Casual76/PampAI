# Connected research consumer implementation

Connected foreground research, targeted profile retrieval, signed source acquisition and finite durable tranches are implemented. Root explicitly revoked the initial docs-only restriction and transferred core sources/tests and the serial core/engine runner. Feature/app/UI/worker/startup ownership remained outside this topic. The earlier design is archived below the live API section in `aria-research-consumer.md`.

## Production execution

AssistantEngine retains preflight, local quick commands, kill switch, exact selection, cancellation and ordinary tool behavior. For normal questions it invokes ResearchQuestionPlanner and ResearchForeground without requiring ChatViewModel to populate new request fields. Natural grades/music questions select only pertinent canonical fields and explicitly authorized saved scopes. Unrelated questions select none. Open/play/write commands remain on their existing action path. Explicit ResearchTask/ProfileQuery request arguments remain available for precise requests and resume.

ResearchSources persists the exact interactive connect probe through ResearchConnections, independently of background setup. Area plans override older connection scopes. The planner excludes missing/disabled grants, including stale DataStore plans after DB recovery. No account/student/year is adopted or changed automatically. A connected grades source can answer a natural grades question before background setup; the selected ModelPolicy remains explicit and captured.

ResearchRunner admits normal durable foreground jobs, validates a multisource vector, commits real pages/fragments/evidence/checkpoints, synthesizes once, and resumes/reclaims after reopen. AssistantEngine persists its answer through existing turn completion/state handling. Pertinent stored profile values and their validated dependencies are supplied to synthesis; ordinary orchestration also receives a bounded targeted block. Every actual provider wire checks source/profile freshness and local fences. Temporary consultation uses in-memory pages only; locked/temporary conversations receive no durable profile context. No uncalled facade is claimed as final delivery.

Automatic profile writes require a captured exact area plan revision, explicit profiling consent, enabled/setup-complete settings, authorized model and proven references. Ordinary foreground gathering can retain evidence before setup but cannot infer profiling consent. Plan/scope/model/consent edits fence active work; corrections/exclusions/tombstones remain foundation operations. Source authorization renewal preserves stable grant IDs/version/learning epoch; real authorization/incarnation/account changes revoke and invalidate. Explicit reconnect reuses stable IDs and does not remove exclusions.

## Concrete files

All new production classes are in `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/`:

| File | Connected responsibility |
|---|---|
| ResearchContracts.kt | Source selection/read scope, stable grant identity, intent, foreground input, answer/tranche results |
| ResearchTransport.kt | AiToolClient discovery/typed negotiation, signature checks, fixed package/authority/domain and per-tool argument allowlists |
| ResearchSources.kt | Actual capability/lease/read/validate flow, private receipt verification, nonblocking shared validator cache, freshness renewal/revoke |
| ResearchConnections.kt | Persisted explicit interactive read scopes, independent of autonomous enablement |
| SourcePages.kt | Fluidify immutable UTF16/UTF8-SHA256 fragments and cursor withholding; CV full typed records/detail continuation/full aggregate deduplication |
| ResearchSetup.kt | Persisted exact AreaReadPlan/model/effort/helper/revision/consent/maxRecords; area configuration and caps access |
| ResearchProfile.kt | Management flows/detail/provenance/exclusions, targeted retrieval, prompt bounds, correction/forget/exclude/re-enable and UI AreaResearchPlan adapter |
| ResearchModel.kt | Actual selected provider call and installed conservative catalogue-based bounds; no pricing authority |
| ResearchRunner.kt | Fenced finite acquisition/synthesis/profile tranches, background-specific admission/execution, explicit resume, cooldown/unknown eligibility |
| ResearchQuestionPlanner.kt | Bounded pertinent fields/scopes for normal source questions, independent of depth/model |
| ResearchForeground.kt | Production selection, durable/in-memory execution, guarded ordinary prompt retrieval |
| ResearchBindings.kt | Hilt bindings without AssistantRuntime dependencies/cycles |

Existing integration edits: runtime/AssistantEngine.kt, AssistantRuntime.kt request fields, di/DatabaseModule.kt, durable/DurableFoundation.kt and DAO query methods in DurableEntities.kt. Foundation additions are narrowly scoped multisource foreground validation, fenced evidence/context reads, background keyset eligibility, explicit retry, setup policy invalidation, profile-consent checks and canonical evidence reuse across follow-ups. No entity/schema fields changed.

Tests: new research/ResearchConsumerTest.kt; existing DurableFoundationTest publish fixtures now explicitly configure profile consent, and QuickCancellationIntegrationTest supplies the new injected foreground collaborator. Existing runtime budget implementation/tests and ModelSelection remain preserved.

## APIs for following UI/worker

See the live section of `aria-research-consumer.md` for exact signatures. UI requested APIs are implemented: ResearchProfile management/settings/grants/exclusions Flows, detail/provenance, correct/forget/exclude/re-enable, AreaResearchPlan load/save/disable/global caps, ResearchSources discovery/connect/reconnect/renew/revoke, and ResearchSetup.caps Flow. Explicit source connection never enables autonomous areas.

Worker must use admitBackground(area,objective,maxRecords), eligibleBackgroundJobs(now,after,limit), runBackgroundTranche(jobId), cancel(jobId). Do not invoke AssistantRuntime.submit or the foreground admission path. BACKGROUND requires current explicit exact plan revision/model/consent, enabled configuration and real active global/background/area windows. The ledger still admits each actual model attempt. Yielded/429 retains checkpoints and known eligibility; null eligibility is unknown and requires explicit reconciliation, never an invented schedule/reset. Snapshot expiry restarts a bounded read twice and deduplicates canonical evidence; it never replays a write.

No worker, startup scheduling or notifications were added. Root's following worker topic must enforce saved network/battery/cadence constraints and reconcile source/budget/settings state. Saved policy is authorization, not proof of running recurrence.

## Validation

Final release invocation **passed**: 210 freshly executed core tests, including all 25 new consumer cases; 0 failures/errors/skips. Core lint passes with **0 errors / 22 warnings**, and core AAR assembly passes (2,509,901 bytes). The final source/test snapshot preserves unknown CV timezone rather than asserting UTC, with that assertion included. `validation-research-consumer/results.json` records exact input hashes, artifact SHA256/size, invariant comparisons, per-case names and process release. Release JUnit XML and lint XML are archived alongside it.

Engine-ai: 264 tests freshly executed and passed in this topic's full-1 run; later core runs reuse unchanged engine artifacts and do not claim fresh engine execution again. Source-proof's 55 assigned tests are earlier prerequisite evidence only and were not rerun here. Counts are separate, not cumulative.

The 25 new cases cover real Room/facade/runner/foreground paths with source-shaped fake signed transport; cross-source/follow-up canonical reuse; pre-setup foreground; stable renewal versus authorization change; wrong account/student/year/host/capability/tool/argument refusal; source/forget/consent barriers; temporary exclusion; persisted setup/caps/restart; Unicode hash fragments/cursor withholding; CV detail/repeated aggregates; bounded snapshot restart; structured reference rejection; and natural grades/music routing plus irrelevant-question exclusion. One fixture uses the actual provider HTTP/lifecycle adapter against a local synthetic server, checks reservation/settlement, and verifies 429 eligibility without another wire/fallback. This is not live provider or Android Binder acceptance.

Commands use Android Studio JBR, external build root, --no-daemon --no-watch-fs --no-parallel --max-workers=1, in-process Kotlin, nonincremental Kotlin/KSP. Only core tests/lint/AAR and engine tests were requested; no feature/app builds, source-repository/native builds, device operations, real accounts, credentials, live APIs, commits or pushes.

Retained logs describe failures honestly: compile-1 had initial adapter/import errors; tests-1 had a fixture syntax error; tests-2 exposed canonical follow-up evidence identity handling, fixed in foundation; final attempt1 exposed a Windows-long-path reopen fixture, renamed; final attempt3's natural routing case exposed missing-grant stale saved plans, fixed with grant filtering. Full-1, final attempt2 and final attempt4 passed their respective snapshots. No failed log is counted as passing evidence.

## Limits and ownership

Point-in-time source proof is not upstream authorization/billing certainty or a distributed lock. Actual signed cross-app runtime, source-native behavior, provider semantics and Android process death remain NV. Unknown imported duration stays null; music archive completeness is never inferred. CV date/year scope remains in source provenance, and its unreported timezone is UNKNOWN. Source cache/record/fragment/input bounds are finite and can return a visible suspension rather than omit records.

The deterministic question planner intentionally covers bounded school/music consultation patterns, not unrestricted semantic intent classification. Unknown or ambiguous account/source scopes fail closed. Custom source application IDs outside the hardcoded production allowlist require explicit support. The production transport rechecks signing/package/authority on every dispatch; fixture transport is not hardware signature proof.

Bounds accept only text-only Groq Llama catalogue entries with explicit positive context/output ceilings and no reasoning flag; the entire context ceiling is reserved as input. Unknown model/media/reasoning/search/tool charge behavior is unsupported for bounded calls. Monetary caps remain denied without trusted tariffs; uncapped foreground remains explicitly no-guarantee. No online catalogue/provider verification was performed. Existing data, exact model/cancel/ledger behavior, schema7 and root lint fixes are preserved. Earlier runtime gates remain NV; no integrated phase approval is claimed.

All owned compiler invocations and sessions have ended. The process check found **zero owned Gradle8.14.3/worker processes** remaining; unrelated preexisting Java/Gradle processes were left untouched. **Core sources/tests and the sole compiler runner are released to ROOT now.** Feature/app/worker validation is root-owned afterward. No further source or compiler work remains owned by this author.
