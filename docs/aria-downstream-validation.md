# Selective downstream Aria validation

2026-11-07, completed 17:34 Europe/Rome. **Gradle runner RELEASED.** All five owned
Gradle invocations exited before release; no owned Gradle, Kotlin compiler or test
process remains. The preexisting Gradle/Kotlin daemons (PIDs 46768/48968, started
15:46/15:49) were left untouched. No daemon shutdown, cleanup, device, provider,
Spotify/school calls, secrets, subagents, commits, pushes or publication.

## Scope and inspected inputs

Read ClasseViva's applicable root AGENTS.md; no nested AGENTS.md was found in the
downstream Android workspaces or PampAI. Read Fluidify docs/aria-bridge.md and
docs/listening-history-port.md, ClasseViva docs/aria-data-access.md, and canonical
PampAI docs/aria-source-integration.md, including its exact selective propagation
manifest. Used the Fluid Engine skill. This report is the only PampAI file written.
No PampAI application, feature, engine or build was changed/run.

Both embedded engines still have HEAD
`a055257fc4b559bee54b9ce114f265bcd237adfd` and ENGINE_VERSION **3.0.5**.
Selected host/RemoteTools/ToolResults production files in both downstreams compare
byte-for-byte equal to the current canonical PampAI copies. The orchestrator diff
against each embedded HEAD is only the modelText import and replacement of the
Message.ToolResult content expression. No whole-engine/provider/model overwrite.
Fresh production app/core searches found no RemoteTool/AiToolClient/RemoteCatalog
consumers. Existing local two-argument orchestration remains in place; tests and
APK builds found no consuming-app compile or write integration regression.

## Actual tests (JUnit XML)

| Repo / selected suite | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| Fluidify engine-ai | 244 | 0 | 0 | 0 |
| Fluidify engine-ai-bridge | 26 | 0 | 0 | 0 |
| Fluidify app AiToolHostProviderGuardTest, final rerun | 6 | 0 | 0 | 0 |
| **Fluidify selected total** | **276** | **0** | **0** | **0** |
| ClasseViva engine-ai | 244 | 0 | 0 | 0 |
| ClasseViva engine-ai-bridge | 25 | 0 | 0 | 0 |
| ClasseViva core-assistant | 29 | 0 | 0 | 0 |
| **ClasseViva selected total** | **298** | **0** | **0** | **0** |
| **Combined selected total** | **574** | **0** | **0** | **0** |

Counts were summed from tests/failures/errors/skipped attributes in TEST-*.xml,
not inferred from Gradle task counts. The initial Fluidify host pass and final
corrected rerun each passed six tests; counted once above. Both engine suites
actually executed in both repos. Unchanged history/domain/network/data suites
from the earlier 304/687-test reports were not rerun and are not included here.

Passing regressions include model-facing bounded evidence envelopes, legacy
success remaining uncertain to the provider, supplied stable operation identity,
remote two-argument write refusal versus legacy read compatibility, cancellation
of a running remote job with cancellation propagation, v1 default/v2 negotiated
typed envelopes, argument/confirmation/signature gates, and CV school event
identity forwarding/readback through TracedTool. These are JVM/fake/Robolectric
checks, not live Binder or service evidence.

## Exact correction

Only downstream source file edited by this validation:

`C:/VibeCoded Projects/Fluidify/app/src/test/java/dev/lelonio/square/bridge/AiToolHostProviderGuardTest.kt`

In `malformed args and missing confirmation have no effects`, supplied
`requestId:"parent"` for the missing-confirmation and two malformed-argument
requests; added an immediate zero-effects assertion after the first two cases.
This prevents the new missing-identity guard from masking confirmation/argument
coverage. Retained the explicit confirmed request without an identity and its
zero-effects assertion, followed by the successful confirmed request with parent
identity. Existing assertions were preserved. Final six-test rerun passed.

No production correction or generic canonical propagation is required by these
results. No canonical PampAI changes were made. Unrelated README, player, theme,
screenshots and existing downstream changes were preserved. Diff whitespace checks
passed for both embedded engines and the corrected fixture (existing line-ending
notices only). No suppressions, lint baselines or engine-ui rewrite.

## Assembly and scoped lint

Both `:app:assembleDebug` tasks **PASS**. APKs were built, not installed/published:

| Repo | APK | Bytes |
| --- | --- | ---: |
| Fluidify | C:/VibeCoded Projects/Fluidify/app/build/outputs/apk/debug/app-debug.apk | 69,806,723 |
| ClasseViva | C:/VibeCoded Projects/classeviva-expressive/android/app/build/outputs/apk/debug/app-debug.apk | 46,178,656 |

Current scoped lint tasks all **PASS**, with counts parsed from lint XML:

| Repo / module | Errors | Warnings | Hints |
| --- | ---: | ---: | ---: |
| Fluidify app | 0 | 213 | 6 |
| Fluidify engine-ai | 0 | 1 | 0 |
| Fluidify engine-ai-bridge | 0 | 2 | 0 |
| ClasseViva app | 0 | 84 | 1 |
| ClasseViva core-assistant | 0 | 6 | 0 |
| ClasseViva engine-ai | 0 | 1 | 0 |
| ClasseViva engine-ai-bridge | 0 | 2 | 0 |

**Full ClasseViva lint is not claimed green.** The previous full-lint XML was
inspected and preserved: six preexisting engine-ui errors, RestrictedApi x5
(FluidNavHost.kt 136/175/185/213, FluidPageNavigator.kt 29), RememberReturnType x1
(FluidTextReveal.kt 82). Engine-ui has no local source diff. This validation ran
the affected scoped lint tasks, not full `lintDebug` or engine-ui's own lint task.
The previous full-lint failure remains separate from today's passing scoped lint.
Existing AGP/SDK/deprecation/Kotlin warnings remain visible in logs; the CV JVM
class-sharing warning is not a test failure.

## Serialized commands and evidence

Log root: `C:/Users/casua/AppData/Local/Temp/aria-downstream-validation-20261107/`.
All invocations used `--no-daemon --max-workers=1 --console=plain`; invocations
2-5 additionally used `-Pkotlin.compiler.execution.strategy=in-process`.
Only one invocation ran at a time, in this exact order:

1. Fluidify: `:engine-ai:testDebugUnitTest :engine-ai-bridge:testDebugUnitTest :app:testDebugUnitTest --tests '*AiToolHostProviderGuardTest*'`. PASS, 2m1s. Log `fluidify-tests-01.log`.
2. Fluidify: `:app:assembleDebug :app:lintDebug :engine-ai:lintDebug :engine-ai-bridge:lintDebug`. PASS, 3m6s. Log `fluidify-build-lint-01.log`.
3. Fluidify: `:app:testDebugUnitTest --tests '*AiToolHostProviderGuardTest*'`. Corrected fixture PASS, 43s. Log `fluidify-host-tests-02.log`.
4. ClasseViva from android/: `:engine-ai:testDebugUnitTest :engine-ai-bridge:testDebugUnitTest :core:core-assistant:testDebugUnitTest`. PASS, 1m45s. Log `classeviva-tests-01.log`.
5. ClasseViva from android/: `:app:assembleDebug :app:lintDebug :core:core-assistant:lintDebug :engine-ai:lintDebug :engine-ai-bridge:lintDebug`. PASS, 3m22s. Log `classeviva-build-lint-01.log`.

No invocation failed. The fixture correction addressed a coverage issue found
during inspection, not a failing production test. Each log contains its full
Gradle output and BUILD SUCCESSFUL result.

Frozen XML copies under that log root:

- `fluidify-tests-01-engine-engine-ai/testDebugUnitTest/`
- `fluidify-tests-01-engine-engine-ai-bridge/testDebugUnitTest/`
- `fluidify-tests-01-app/testDebugUnitTest/` (initial host pass)
- `fluidify-host-tests-02/testDebugUnitTest/` (final corrected host pass)
- `classeviva-tests-01-engine-engine-ai/testDebugUnitTest/`
- `classeviva-tests-01-engine-engine-ai-bridge/testDebugUnitTest/`
- `classeviva-tests-01-core-core-assistant/testDebugUnitTest/`
- `final-test-counts.json`: actual final XML counts, excluding duplicate host run.
- `fluidify-final-lint-*.xml`, `classeviva-final-lint-*.xml`: current scoped lint reports.
- `classeviva-prior-lint-engine-engine-ui.xml`: preserved earlier full-lint errors.

Original XML/HTML test results remain in each module's
`build/test-results/testDebugUnitTest` / `build/reports/tests/testDebugUnitTest`;
lint XML/HTML/TXT remain under each listed module's `build/reports/`.

## Handoff and limits

**Runner explicitly RELEASED for root to transfer to the PampAI IA implementer.**
All owned sessions have completed, including their single-use daemons/compiler
workers. No additional validation process was left running. Existing unrelated
daemons were not stopped. PampAI combined tests/assemble/lint remain for that
implementer; downstream results do not certify PampAI's current application code.

Actual installed same-signer cross-app Binder/runtime behavior, live account drift,
Spotify permissions/pagination/snapshots, school service behavior, process death
and MediaSession continuity remain unverified. No runtime or publication readiness
claim is made from these local checks.
