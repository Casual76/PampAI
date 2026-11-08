# Provider lifecycle implementation handoff

Completed the generic chat/generate lifecycle scope in the shared PampAI embedded engine. Existing worktree edits were retained; app/core/UI/source repositories, credentials and immutable reviewer copies were not edited. No devices, live provider calls, commits, pushes or publication were used.

## Final API and integration

See [aria-provider-lifecycle.md](aria-provider-lifecycle.md) for final signatures, semantics and a concrete adapter example. Public types are in `dev.antigravity.fluidengine.ai.provider`:

- `ProviderAttemptLifecycle.beforeAttempt(ProviderAttempt)` and `afterAttempt(ProviderSettlement)`: suspend, default no-op.
- `ChatRequest.lifecycle`, default `ProviderAttemptLifecycle.None`.
- `ProviderExecutionContext(executionId, purpose, lifecycle, beforeDispatch = {})`: coroutine element inherited by internally created router/round/helper chat calls. Context and request hooks compose; identical lifecycle instances are not duplicated.
- `ProviderAdmissionDenied(reason)` and `ProviderObserverFailure(attemptId, cause)`: terminal control signals derived from CancellationException. Existing provider fallback handlers only catch BadRequest, and orchestrator router/round/stream/tool cancellation barriers rethrow them. Do not convert them into ordinary retryable failures in app wrappers.

Preserve the existing request `beforeDispatch` audit; the additional context audit executes first and does not replace it. Root can compose the app DispatchAudit at context level for router/helper calls, with the durable budget adapter as lifecycle. Admission must persist atomically before returning, and roll back its own incomplete work if it throws. Settlement must persist idempotently by attemptId; failures after a successful request must be reconciled without redispatch.

Each actual chat transport call receives a fresh UUID, actual encoded provider/model/reasoning and bounds/features from its payload. This includes parameter fallback, outer retry, context trim, stream-to-complete recovery and web-enabled chat requests. Model candidates expose server-side model fallback routing to admission. Token input bound is honestly null. Missing/dropped/invalid output limits and absent encoded web limits remain null. Byte counts are not token limits; encoded web results are not a paid search-operation bound. No price/quota authority is embedded.

Each admitted observer receives one NonCancellable settlement invocation. A later observer's denied admission settles earlier reservations as NOT_DISPATCHED. Usage stays nullable on success, failure and cancellation. Status and rate headers are captured before HTTP error mapping/JSON decode, preserving response certainty for malformed JSON. All admitted observers are attempted even if a settlement observer fails; a terminal control signal then prevents replay.

## Exact files

Owned production changes (relative to embedded engine root):

- `engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/provider/ProviderAttemptLifecycle.kt`
- `engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/provider/Model.kt`
- `engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/provider/OpenAiCompatProvider.kt`
- `engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/provider/GeminiProvider.kt`
- `engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/net/AiHttp.kt`

Owned tests:

- `engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/provider/ProviderAttemptLifecycleTest.kt`
- `engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiFailoverOrchestratorTest.kt`

Generated catalog pages (only the two affected API families, mirrored in docs):

- `skill/fluid-engine/references/api/ai-provider.md`
- `skill/fluid-engine/references/api/ai-chiavi-rete-voce.md`
- `docs/riferimento/api/ai-provider.md`
- `docs/riferimento/api/ai-chiavi-rete-voce.md`

Task documentation: `docs/aria-provider-lifecycle.md`, this report, and `docs/validation-provider-lifecycle/` evidence. `validation-provider-lifecycle/results.json` freezes final source hashes.

## Observed verification

Serial compiler ownership was maintained with Android Studio JBR, `--max-workers=1 --no-daemon`, Kotlin in-process/nonincremental, and the required external build root. Final command:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :engine-ai:testDebugUnitTest :engine-ai:lintDebug :engine-ai:assembleDebug --max-workers=1 --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' '-Ppampai.buildRoot=C:/Users/casua/AppData/Local/PampAI-qa-build-full'
```

Final run-5: BUILD SUCCESSFUL in 1m 51s; 264 tests, zero failures/errors/skips. 16 added lifecycle/control tests: 14 in ProviderAttemptLifecycleTest plus 2 in AiFailoverOrchestratorTest. The real-orchestrator fixture runs three scenarios: outer retry, context trim and partial-stream recovery; each produces distinct reservations and settlements. Remaining 248 tests retained. Lint: zero errors and one NewerVersionAvailable warning for the existing coroutines dependency. Debug AAR assembled.

Run-4 also passed 264 tests plus lint/assemble; its XML is archived separately. Run-2 and run-3 passed test/lint during incremental implementation (see logs; their XML was not separately frozen). Run-1 failed before compilation because PowerShell split unquoted Gradle -P arguments; later runs quote them. This was an invocation issue, not a passing build.

Evidence: `validation-provider-lifecycle/run-5.log`, `run-5-results/TEST-*.xml`, `run-5-lint.xml`, `results.json`. Catalog generation succeeded using the installed Fluid skill generator against this embedded engine, copying only affected API family pages. API documentation symbol/link check passed against this embedded source (`docs-check.log`). No release version was changed. Git diff whitespace check passed on tracked engine changes.

## Limits and follow-on work

This is engine primitive acceptance, not complete app budgeting or integrated Aria/runtime acceptance. The app must implement durable admission/settlement, trusted tariffs, authorization/model/candidate checks, input/media/search charge bounds, cooldowns and crash recovery. Process death can prevent settlement callbacks; unknown or unsettled use must retain reservations. NonCancellable does not guarantee storage success or termination of a hanging observer.

The seam covers built-in ChatRequest chat/generate paths. Legacy audio transcribe and catalog/key GET APIs are outside this chat contract; they must not be treated as budget-covered autonomous generation. Custom ChatProvider adapters must explicitly honor the lifecycle contract. Detached coroutine scopes must explicitly carry ProviderExecutionContext. Provider server billing/actual model obedience and real account behavior remain unverified.

Compiler runner: RELEASED. Every owned Gradle invocation has completed; no compiler/test/lint process from this task remains pending. Root may transfer runner ownership to the DB author.
