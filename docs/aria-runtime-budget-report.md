# Runtime budget implementation and validation handoff

Final engine/core validation completed after ROOT's explicit sole compiler transfer on 2026-11-08. Source is frozen and the owned runner is RELEASED. No further compiler or core source/test work will start without a new assignment. The research-consumer author may now proceed within ROOT's assigned scope.

## Result

Final run-3: BUILD SUCCESSFUL in 1m47s. 185 core tests passed, zero failures/errors/skips; this includes all 15 RuntimeBudgetIntegrationTest cases (the original 14 plus ROOT's required foreground-job fixture). Core lint passed with zero errors and 22 warnings. Core debug AAR rebuilt successfully, 2,192,095 bytes, SHA-256 ff4fe6a53964c96e19f5e0ca0233eb07ff2cc4f2280b251ae9943fd4b1d2335b.

Engine suite was explicitly forced to execute in successful run-2: 264 tests passed, zero failures/errors/skips. Engine sources/tests/catalog hashes still match the prior frozen engine implementation; run-3 changed only app/core sources and tests. These are separate suite counts, not cumulative additions to historical core170/engine264 prerequisite results. Source-proof author's55 tests are not counted here.

ROOT's three lint fixes in InfoTools.kt, FluidifyClient.kt and core AndroidManifest.xml were preserved and included in compiled/linted core. The prior MissingPermission, ExactAlarm and UnsafeOptInUsageError errors are absent in the final lint XML. Existing22 warnings remain. Schema7 SHA-256 matches the durable-foundation freeze; no migration/entity/schema changes were introduced.

## Scope and stable APIs

See [aria-runtime-budget.md](aria-runtime-budget.md) for exact APIs, setup semantics and caller obligations. RuntimeBudgetCoordinator is connected through AssistantRuntime preflight, AssistantEngine execution and detached title, and ModelSelection helper cache misses. Structured engine router/round/retry/web calls inherit admission and settlement. The coordinator also exposes fenced job execution, validated-bounds setup, persisted cap configuration, last-stop diagnostics and explicit abandoned-foreground recovery.

ROOT clarified foreground durable research while validation was in progress. Final behavior allows explicitly uncapped accounting for ordinary foreground and user-requested FOREGROUND jobs only before ANY global cap has been configured. Foreground jobs retain job identity, exact model/source/owner/lease/learning fences before admission. Configured/expired global limits cannot fall back to uncapped. BACKGROUND always requires global/background/area limits, area setup and allowlisted models, independent of purpose.

The added foreground fixture uses the actual engine provider lifecycle, Room and local HTTP transport. It proves successful uncapped foreground synthesis with a captured source vector, retention of job ID/observed usage, before-wire owner/proof rejection, denial after configured-global expiry and priority cleanup. Existing background fixture proves no-global refusal, foreground priority yield, capped success and cancelled-job fencing. All15 integration assertions executed successfully. Other cases cover real-wire last-slot concurrency, input/money refusal, internal/outer attempts, dropped output bounds, model candidates, inherited scopes/cache, cooldown, cancellation/reopen, observer failure and explicit recovery.

Production files owned by this topic:
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/RuntimeBudgetCoordinator.kt (new)
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantRuntime.kt
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/ModelSelection.kt
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/BudgetLedger.kt (narrow policy/recovery APIs)
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableEntities.kt (DAO query only)

Tests: new core runtime/RuntimeBudgetIntegrationTest.kt and test-local TinyHttpServer.kt; existing core runtime/QuickCancellationIntegrationTest.kt constructor. Feature chat SendAdmissionTest.kt and UiCorrectionsTest.kt constructor fixtures were adapted during source implementation but were NOT compiled or run under this restricted transfer. No feature/UI production code was changed. Existing Hilt bindings supply the injected coordinator's ledger/database dependencies.

## Executed commands and retained failures

Every build used Android Studio JBR, external build root C:/Users/casua/AppData/Local/PampAI-qa-build-full, --max-workers=1 --no-parallel --no-daemon --no-watch-fs, quoted Kotlin in-process/nonincremental and KSP nonincremental properties. Only engine/core tasks were requested. Full command output is retained in validation-runtime-budget/run-*.log.

Run-1 requested core/engine tests, core lint/AAR with --continue. Production compiled, lint/AAR passed, but new integration test compilation failed because the fixture used Closeable.use on RoomDatabase. This author corrected it to an inline try/finally database close helper. Run-1 is FAILED, not a passing test run; core tests did not execute and engine tests were cached. Its full compiler errors and successful lint XML are retained.

Run-2 command:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :core-assistant:testDebugUnitTest :engine-ai:testDebugUnitTest --rerun :core-assistant:lintDebug :core-assistant:assembleDebug --continue --no-daemon --no-watch-fs --no-parallel --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' '-Pksp.incremental=false' '-Ppampai.buildRoot=C:/Users/casua/AppData/Local/PampAI-qa-build-full'
```

Run-2 passed in1m7s:184 core tests (14 integration),264 freshly executed engine tests, lint and AAR. Its XML is archived separately. ROOT then required the fenced uncapped foreground-job behavior. After that scoped change, run-3 executed the same flags with only :core-assistant:testDebugUnitTest :core-assistant:lintDebug :core-assistant:assembleDebug, passing185 core tests and fresh lint/AAR.

Evidence directory: validation-runtime-budget/results.json (source hashes, artifact metadata, counts and schema/engine comparisons); run-1.log and run-1-lint.xml; run-2.log, run-2-core-assistant-results, run-2-engine-ai-results and run-2-lint.xml; run-3.log, run-3-core-assistant-results and run-3-lint.xml. Final source/test hashes freeze this topic's exact inputs, including preserved ROOT lint files and excluded feature constructor fixtures. Process-release evidence distinguishes owned Gradle8.14.3 work from unrelated Gradle9.3 JVMs, which were not touched.

## Limits and release

This establishes local engine/core compilation, synthetic integration tests, lint and AAR only. The scope did not authorize feature/app compile/assemble, downstream builds, live provider/device/authentication calls, credentials, commits or pushes. No background enablement or integrated runtime approval is granted.

Unknown bounds default Deny. No trusted tariff registry is installed: active money caps deny, with no invented price/currency/search/media guarantee. Setup must provide validated local bounds before bounded dispatch. Source-consuming follow-on integration must inject the same verified SourceProofValidator into both foundation and ledger; default Deny is preserved. Source adapters, reader/research/worker/UI integration and feature fixture/UI validation remain ROOT-owned follow-on work. Helper/router/title/web context tests do not substitute for full production end-to-end UI acceptance.

All owned compiler/test/lint/AAR commands have returned; no owned build process remains. Source freeze and runner release are explicit and final for this assignment.
