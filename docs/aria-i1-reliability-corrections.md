# Aria I1 reliability corrections ? R1/3

Source delivery, 2026-11-07. Read accepted P2 and both consolidated I1 R0 findings in aria-workflow.md. Applied Fluid Engine skill rules: generic contracts stay in engine; model selection, durable storage and vision policy stay in core. No AGENTS.md found in workspace, scoped modules, or parent directories. No app/feature-assistant/workflow edits, other repositories, agents, device/provider/secret access, commits or publication by this implementer. Existing approved changes were preserved, including root UsageRepository vision accounting and typed confirmation/disabled-action errors.

## Findings, implementation and regression sources

**Executed after explicit runner transfer from root:** engine-ai **248 passed**, engine-ai-bridge **25 passed**, core-assistant **133 passed**; **406 tests, zero failures/errors/skips**. Gradle invocations were serialized, max-workers=2, no build cache, in-process nonincremental Kotlin, using the root-approved isolated build output. No device or real provider verification was performed. The provider rejection regression uses only a local HTTP fixture. `git diff --check` passed for scoped core/engine source changes (only existing line-ending notices). API catalogs were regenerated from Kotlin declarations with the skill parser, restricted to the three AI catalogs and their documentation mirrors. These checks do not establish Kotlin compilation or runtime behavior.

| Finding | Root-cause correction | Executed regression sources |
|---|---|---|
| F1 P1 | `discardVersion` checks all subtree operation rows inside its Room transaction. ANY reserved write retains the version, its question/lineage and receipts; answer becomes CANCELLED even with no partial text. DISPATCHED continues to render UNCERTAIN. Explicit conversation/all deletion remains allowed. | `ConversationsTreeRepositoryTest`: cancelled reserved version retained; `OperationLedgerTest`: real repository regeneration, durable reservation, simulated external effect, discard before output, database close/reopen, receipt survives and same-lineage retry cannot dispatch another effect. |
| F2 P1 | `TracedTool` overrides BOTH run overloads through common instrumentation and forwards the supplied ToolExecution unchanged. Production RicordaTool sees the external-read trace before its safety confirmation. | `TracedToolSafetyTest`: actual ToolRegistry, production wrapper/RicordaTool/Room memory/gate, external reader fixture through 3-argument dispatch, identity retained, memory empty while confirmation waits and after rejection. |
| F3 P2 / R2-F4 | Every model-facing ToolResult uses `ToolOutput.modelText`: authoritative outcome, verified-completion flag, error code/retryability, coverage, freshness, pagination and noncompletion limitation precede bounded untrusted prose. Null/legacy metadata is UNCERTAIN. Raw structured payload is not dumped. Evidence survives even a configured limit smaller than the header; prose gets the remaining budget. Ledger still persists full output before model truncation. | `ModelToolTextTest`: adversarial success prose, uncertain status and freshness/coverage survive truncation; `AiOrchestratorTest`: next provider input explicitly nonverified, only one dispatch. Existing ledger tests cover uncertain write retry blocking. |
| F4 P2 | Additive `AskInput.prepareToolParts` is invoked for every dynamic tool output before engine capability filtering. AssistantEngine binds immutable request ModelPolicy; ModelSelection gates YES/NO/UNKNOWN conservatively and uses only an explicitly selected verified helper. Existing ImageInterpretationCache labels helper provenance and root UsageRepository records uncached helper calls/failure/cancellation. No helper sets `imageChoiceNeeded` and fails recoverably before any primary receives pixels. Screenshot prose no longer claims the primary sees an image. Screen tools retain their per-call Surface/context checks. | `DynamicImagePolicyTest`: NO/UNKNOWN primary with YES helper, labelled text only, one cached helper description, missing helper requests selection; `AiOrchestratorTest`: actual dynamic image tool is intercepted before next primary input, pixels absent. Actual UsageRepository accounting with a live helper remains runtime evidence to collect; the policy test's callback counter is not proof of billing. |
| F5 P2 / R2-F5 | Read-only `ConversationsRepository.replayContext` resolves continue/edit/regenerate using the same `exchanges(questionId)` ancestor path and last-pair attachment behavior as dispatch. Regeneration uses persisted question attachments even if supplied attachments differ. Runtime preflight occurs before startTurn/draft consumption. Dispatch always reconstructs that branch instead of reusing potentially stale cached attachments. | `ConversationsTreeRepositoryTest`: text A/later image B regeneration excludes B; image U1/text U2/later chat regeneration of A2 includes U1, matching exchanges after startTurn. |
| F6 P2 / R2-F6 | Additive per-wire `ChatRequest.beforeDispatch(ProviderDispatch)` callback is invoked by both OpenAI-compatible and Gemini providers before each HTTP attempt, including internal 400 field adaptation. Core decorates request providers across router/chat/deep/tool subcalls with a durable attempt adapter. It commits provider/model, requested policy, resolved reasoning, actual encoded reasoning field, support state and adaptation BEFORE dispatch. Exact policy rejects a provider's attempted model rewrite. Fields sent are SENT_UNVERIFIED, absent fields OMITTED; actual honored reasoning is never invented. No reliable model-specific support verification exists in current metadata, so sending a field does not assert support. | `DispatchAuditTest`: local HTTP fixture rejects reasoning field, actual provider adapter records both wire attempts, second omits field, model unchanged. No real provider access. `DispatchAuditPersistenceTest`: real app attempt adapter records captured LOW/HIGH queued policies before fake dispatch, close/reopen preserves distinct attempts. `Migration4To5Test`: new 5?6 case preserves existing ledger and audit rows; existing 4?latest test preserves conversation/message/memory. `MigrationsTest` includes both auto-migration steps. |
| F7 P2 | Remote follow catches coroutine cancellation, starts one best-effort remote cancel and rethrows the original CancellationException. Cleanup runs under NonCancellable, with a maximum 2-second caller wait. IO cleanup has a separate scope so a noncooperative blocking Binder call cannot indefinitely delay caller cancellation; its scope is cancelled after waiting. Blocking Binder itself may return later. No new operation dispatch or claim of no effect. Durable operation reservation remains uncertain through F1. | `RemoteToolTest`: running remote job cancelled by caller, host cancel once, original job cancelled, one run dispatch. Ledger cancellation/reopen regression covers durable uncertainty separately. Two-app Binder cancellation/host process behavior remains runtime evidence. |

## Room migration and UI API

`PAMPAI_DB_VERSION = 6`. Keep existing exported `schemas/dev.pampa.pampai.core.assistant.db.PampaiDatabase/4.json` and `5.json` unchanged. Database declares BOTH `AutoMigration(4,5)` and `AutoMigration(5,6)`; existing manual 1?2?3?4 registration is unchanged. No destructive migration.

The new `provider_dispatches` table has autoincrement INTEGER primary key `id`; non-null TEXT `attemptId`, `provider`, `model`, `requested`, `resolved`, `support`; non-null INTEGER `conversationId`, `messageId`, `atMillis`; nullable TEXT `effective`, `adaptation`; index `index_provider_dispatches_messageId`. No foreign keys or alteration of earlier tables. `effective` is wire serialization, nullable for omission, not evidence of honored thinking. `adaptation` identifies dropped fields/Gemini fallback. Each row is a before-dispatch audit; a crash can leave a row whose network dispatch did not happen. Explicit conversation/all deletion removes audits; silent version discard retains them.

**Executed:** KSP exported schema **6.json**, retained in the schema directory; SHA256 `1a1b940a1997a61ba7224d47c83355d849487955d6b7ae969e1b1e6ddab27ee6`. Both the 4?latest preservation test and the 5?6 preservation/reopen test passed. Prior exported schemas were preserved. No handwritten generated schema.

No feature changes required to compile existing APIs: new engine constructor fields have defaults. Future UI can use `PampaiDatabase.dispatches().forMessage(messageId)` to list attempt/dispatch audit rows, rendering requested/resolved/effective separately and SENT_UNVERIFIED/OMITTED honestly. ModelSelection.imageChoiceNeeded already exposes dynamic selection recovery; the failed persisted turn retains question/attachments for explicit retry after selection. Do not automatically replay writes to recover an image. Root/UI implementer still owns draft/handoff/modal integration and runtime presentation.

## Exact R1 changed files

Paths below are relative to PampAI. Other dirty files predate this correction and are not claimed here.

Core production:
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/ConversationsRepository.kt`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/PampaiDatabase.kt`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/DispatchAuditEntity.kt` (new)
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantRuntime.kt`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/ModelSelection.kt`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/DispatchAudit.kt` (new)
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/ToolSupport.kt`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/screen/ScreenTools.kt`

Core tests:
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/db/ConversationsTreeRepositoryTest.kt`
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/db/MigrationsTest.kt`
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/receipts/OperationLedgerTest.kt`
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/receipts/Migration4To5Test.kt`
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/runtime/DynamicImagePolicyTest.kt` (new)
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/runtime/DispatchAuditPersistenceTest.kt` (new)
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/tools/TracedToolSafetyTest.kt` (new)
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/tools/BridgeHostIdentityGuardTest.kt` (new; generic Robolectric host guards adapted from Fluidify into core test scope)
- `core-assistant/schemas/dev.pampa.pampai.core.assistant.db.PampaiDatabase/6.json` (KSP export)

Generic engine changes for **root's selective synchronization to Fluidify/ClasseViva**, not copied to other repositories by this implementer:
- `engine/engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiOrchestrator.kt`: defaulted `AskInput.prepareToolParts`, model-facing evidence rendering.
- `engine/engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/tools/ToolResults.kt`: `ToolOutput.modelText(maxChars)`.
- `engine/engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/provider/Model.kt`: defaulted `ChatRequest.beforeDispatch` and additive `ProviderDispatch`.
- `engine/engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/provider/OpenAiCompatProvider.kt`: per-wire callbacks including rejection retries.
- `engine/engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/provider/GeminiProvider.kt`: per-wire callbacks including thinking fallback.
- `engine/engine-ai-bridge/src/main/kotlin/dev/antigravity/fluidengine/ai/bridge/RemoteTools.kt`: bounded cancellation cleanup and fail-closed two-argument writes.
- `engine/engine-ai-bridge/src/main/kotlin/dev/antigravity/fluidengine/ai/bridge/AiToolHostProvider.kt`: nonblank string requestId required for action/confirmation tools; reconcile Fluidify guard without whole-file replacement.
- `engine/engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiOrchestratorTest.kt`
- `engine/engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiHierarchyTest.kt` (existing assertions retain prose checks and now verify evidence prefix)
- `engine/engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiToolTimeoutTest.kt` (existing timeout/confirmation assertions preserved behind evidence-prefix assertion)
- `engine/engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/tools/ModelToolTextTest.kt` (new)
- `engine/engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/provider/DispatchAuditTest.kt` (new)
- `engine/engine-ai-bridge/src/test/kotlin/dev/antigravity/fluidengine/ai/bridge/RemoteToolTest.kt`
- Generated `ai-provider.md`, `ai-orchestrator.md`, `ai-bridge.md` in BOTH `engine/skill/fluid-engine/references/api/` and `engine/docs/riferimento/api/` (six files).

Report: `docs/aria-i1-reliability-corrections.md`. No bridge protocol bump: v1 remains default; no signature, permission or confirmation gate relaxed.

## Remaining verification owned by root

Engine/bridge/core compilation and all 406 tests are complete; sole runner is released to root. Root still owns the combined app/feature integration build/tests after UI is integrated. Schema 6 and both migration paths, queued attempt and cancellation/reopen regressions passed locally. Check dynamic screenshot helper calls/provenance/UsageRepository on configured providers and no-helper recovery on both app/session; verify exact branch preflight and request/draft recoverability. Exercise real provider rejection adaptations, actual selected model, and two-app remote cancellation without asserting cancellation means no side effect. Then obtain the two new independent reviews. No readiness score or review gate is claimed by this source delivery.


## Identity guard reconciliation and downstream compatibility

Read the latest Fluidify `docs/aria-bridge.md` and compared its two generic guards. Canonical PampAI now rejects action/confirmation host requests unless requestId is a nonblank STRING, before context/dispatch. Generic read requests still get a fallback ID (including absent/blank IDs). Two-argument RemoteTool action/confirmation calls return an error before host dispatch; reads keep legacy fallback. Three-argument calls forward the parent's ToolExecution unchanged. Existing v1 clients already passing requestId continue to work. Signature checks, argument validation and confirmation gate remain in place. Core BridgeHostIdentityGuardTest tests caller trust, signature comparisons, missing identity/no effect and valid legacy requestId/effect. RemoteToolTest covers fail-closed two-argument writes plus legacy reads and preserves existing action/confirmation assertions with supplied parent identities.

**Propagation is coupled:** sync AiToolHostProvider.kt and RemoteTools.kt together with the canonical orchestrator's three-argument dispatch and the receiving app's durable operation-identity adapter. An older orchestrator that still invokes RemoteTool.run(args, ctx) will intentionally stop remote writes after receiving the guard; migrate that caller before enabling required remote actions. Do not restore ephemeral UUID writes as a compatibility workaround. Local legacy AiTool implementations overriding two-argument run remain compatible because AiTool's three-argument default forwards their validated parent identity path. PampAI model dispatch and quick commands already use three-argument calls. Fluidify/ClasseViva source changes are root-owned; none were made here.

## Execution log

- `docs/aria-r1-tests.log`: first compilation exposed use of copy() on non-data AskInput in the new test fixture; fixed test helper.
- `docs/aria-r1-tests-2.log`: initial engine run exposed nine old raw-text assertions after adding the evidence envelope; updated those assertions while retaining timeout/confirmation/prose checks.
- `docs/aria-r1-tests-3.log`: engine 248 and bridge 25 green; three new core fixture failures (image object identity vs byte equality and file database fixture setup) corrected.
- `docs/aria-r1-core-retest.log`: final core suite BUILD SUCCESSFUL; 133 green. Final core fixtures compare image bytes and use short database names with existing database directories; reopen/side-effect assertions remain intact.
- `docs/aria-r1-test-summary.json`: XML-derived final counts (406/0/0/0).

No test is left unrun in the delivered scoped suites. Full app/feature integration, lint, live multimodal usage, provider behavior and two-app Binder runtime evidence remain root-owned. No review gate or release readiness is inferred from JVM success.
