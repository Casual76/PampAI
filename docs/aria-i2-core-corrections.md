# I2 core corrections - R2/3, first delivery

2026-11-07. Scope: core-assistant source/tests and this report only. Existing approved work retained. Profile/background remain unstarted. This report provides implementation/test evidence, not a new readiness or review-gate claim.

## Findings and contracts

- R1-N2: `PampaiToolTrace.outcome: ToolOutcome? = null` is a trailing compatible field. `TracedTool` takes structured outcome as authoritative, retaining ACCEPTED, COMPLETED, PARTIAL, UNCERTAIN and ERROR. Compatibility `ok` is true for typed ACCEPTED/COMPLETED only; the legacy prose-prefix fallback applies only without structured metadata. Unknown legacy positive prose retains its boolean but has null outcome, never verified completion. Exceptions and cancellation rethrow and record UNCERTAIN/false because effects cannot be inferred from an exception. Both overloads remain wrapped and identity forwarding/external-read memory confirmation remain covered.
- R2-N4: `Run.imageInterpretations: List<RunImageInterpretation> = emptyList()` is trailing/default-compatible. `db.RunImageInterpretation(provider: ProviderId, model: String, cached: Boolean)` describes each actual successful image-to-text conversion, not selected helper policy. The cache callback runs on actual conversions, including hits. It reports the exact helper identity and whether this conversion reused a stored description. It includes no pixels, image hashes, keys or prompts.
- Initial attachments, replay attachments and dynamic `prepareToolParts` conversions feed the attempt-local collection. Successful, failed and retained cancelled runs persist it. A helper configured on a text-only turn yields no evidence. Current configuration changes never rewrite historical runs. Runtime replay is reconstructed into a separate conversation; no source messages or stored attachment bytes are mutated.
- Preflight can populate the cache before message creation. Repeated preflights are not appended to a run; subsequent execution honestly records cache reuse. `cached=false` identifies a fresh conversion in that execution, not a billing counter. Uncached VISION_HELPER accounting (including exceptions/cancellation) remains in the existing `UsageRepository.recordVision` finally path; cache hits bypass it. Failed conversions create no successful-interpretation claim. Existing temporary-chat deletion and empty regenerated-version cancellation behavior are preserved: discarded attempts have no retained Run by design.

## Persistence

No Room migration, table, schema version or destructive fallback change. Optional outcome string lives in existing `toolTracesJson`, alongside preserved `ok`; unknown outcome strings decode to null. Optional interpretation records live in existing extensible `groupsJson`. Both the old groups string-array form and the existing object form decode, with empty provenance for legacy rows. Exported schemas 4/5/6, chats, memory, operations, usage and dispatch-audit tables are untouched.

## Files

Main Kotlin paths below are relative to `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/`:

- `tools/ToolSupport.kt`: authoritative outcome, truthful exception/cancel traces, compatible field.
- `db/ConversationsRepository.kt`: compatible public model, JSON codecs, successful/failed run persistence.
- `runtime/ImageInterpretationCache.kt`: actual conversion evidence callback, overload preserving existing callers.
- `runtime/ModelSelection.kt`: forwards evidence through image interpretation and policy preparation.
- `runtime/AssistantEngine.kt`: collects initial/replay/dynamic evidence and persists all retained run outcomes.
- `runtime/AssistantRuntime.kt`: requested behavior-preserving shared preflight extraction for feature admission tests (verified by the final core suite).

Tests relative to `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/`:

- `tools/TracedToolSafetyTest.kt`: all five typed outcomes through real wrapper/both overloads, positive ERROR prose, UNCERTAIN success prose, Room persistence/reopen, legacy boolean/group decoding, exception/cancellation truthfulness; existing execution identity and external-memory confirmation regression retained.
- `runtime/ImageProvenanceTest.kt`: fresh/cache conversions, repeated preflight-style cache preparation with one synthetic paid conversion, dynamic policy boundary, no-image attribution, later helper change and database reopen; partial successful conversions before failure/cancel and retained failed/cancelled repository records.

Synthetic descriptions/providers only; no network, keys, device or feature builds. Tests exercise cache/policy/wrapper/repository boundaries, not a live provider or full service/device end-to-end run. App/overlay rendering and actual send admission regressions belong to the UI implementer. Real Binder/provider/device and other I2 runtime evidence remain outside this core test result.

## Validation and runner handoff

Final source freeze followed by `:core-assistant:testDebugUnitTest` passed: **143 tests, 0 failures, 0 errors, 0 skipped**, across 31 XML suites. This preserves the prior 140 tests and adds three regression methods. Final Gradle exit 0, BUILD SUCCESSFUL in 57s. Initial pass before the shared preflight extraction also passed 143/0/0/0 (64s); the final run supersedes it.

Command (only core test target):

```powershell
./gradlew.bat :core-assistant:testDebugUnitTest '-Ppampai.buildRoot=C:/Users/casua/AppData/Local/PampAI-qa-build-full' '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' --no-daemon --no-build-cache --max-workers=2
```

Final log: `C:/Users/casua/AppData/Local/Temp/pampai-i2-core-r2-final.log`. Initial log: `C:/Users/casua/AppData/Local/Temp/pampai-i2-core-r2.log`. XML: `C:/Users/casua/AppData/Local/PampAI-qa-build-full/core-assistant/test-results/testDebugUnitTest/TEST-*.xml`. No feature/app compile or device/provider execution was performed.

The requested public `runtime.preflightAssistantRequest(request: AssistantRequest, conversations: ConversationsRepository, modelSelection: ModelSelection)` contains the unchanged shared preflight body; `AssistantRuntime.preflight` delegates to it. This lets feature tests invoke actual shared admission with Room without creating service/voice dependencies.

**Core source/tests stable; sole Gradle runner explicitly released.** No further core edits or Gradle commands are planned by this implementer. Root owns transfer to UI. Profile/background and mandatory runtime evidence remain unchanged; no new readiness claim.
