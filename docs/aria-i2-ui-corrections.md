# I2 UI corrections, R2/3, first delivery

Authorized scope: app/ and feature-assistant source/tests plus this report. Existing unrelated working-tree changes preserved. No core/engine/workflow/other-repository changes, subagents, devices, live provider calls, credential changes, commits or pushes. No profile/background work.

## Finding mapping

- **N1 shared P2:** preserve root removal of the screen-wide image scan. `ChatViewModel.send` constructs one complete `AssistantRequest`, passes it through `SendAdmission` to the actual engine/shared preflight, retains the identical request in the queue and dispatches it unchanged. Immediate dispatch does not run a redundant second preflight; a queued send is rechecked against the same request when released. No helper/model rewrite. Regression uses the production admission boundary, real Room repository via the app DatabaseModule factory/DAO providers, real ModelSelection and real shared runtime preflight (not a copied eligibility boolean). U1 image/A1, U2 text/A2, U3 text admits both NO and UNKNOWN primary without helper; attached or genuinely replayed image blocks.
- **R2-N2 P2:** shared feature-owned `VoicePreparation` owns one job with an independent invalidation generation. Repeated requests while preparing do nothing. Stop cancels and invalidates preparation without cancelling a request newly owned by another surface. A non-cooperative helper cannot commit after Stop, hide, handoff or conversation change, nor clear a newer preparation. App/session expose preparingVoice to the existing shared composer status and Stop control. Pending input is consumed only when listening actually starts; preparation never submits. Session hide/reopen within its continuation window preserves an unsent draft even before a conversation exists. App lifecycle STOP/disposal invalidates preparation; existing active listening/answer ownership is retained.
- **R2-N3 P2:** session settings chip carries section through its expansion/wait path and Intent/EntryRequest. `importEntryHandoff` opens a category only after a successful one-shot `ChatViewModel.acceptHandoff`. Existing active-conversation wait and launch-failure discard remain. `canClaim` makes recreated MainActivity ignore consumed/stale handoff Intents before navigation. Unknown categories open the hub. Category Back -> hub -> same Home entry, unsent text/pixels/policy and zero dispatch are fixture-covered.
- **R2-N4 P2:** shared app/overlay `RunSteps` header/details render only stored `Run.imageInterpretations`, including original provider/model and cached-description reuse. Details explicitly distinguish interpreter pixels from the description received by the primary. Helper use suppresses "Ho risposto da sola"; configured-but-unused helpers create no attribution. Stored failed/stopped attempts with provenance retain the disclosure. Persistence is core-owned.
- **R1-N2 P2:** trace rows consume stored authoritative `ToolOutcome` with distinct error, uncertain, partial, accepted and completed labels. Old rows retain a clearly marked legacy boolean label. Neither label nor error color uses prose inference. Positive ERROR prose and UNCERTAIN "done" render regressions included.
- **R2-N5 P2:** remove ignored global thinking/failover preference controls from Aria settings, retain useful recent requests and all provider/key/default/STT/action/privacy controls. Preferences show the same functional `ExactModelControl` as chat, scoped to the existing Home ChatViewModel. Text describes future sends and unchanged active/queued snapshots. Conversation settings/control return and exact queued policy snapshot preservation fixture included. Generic engine defaults unchanged.

## Coordinated API

Core owns default-compatible trailing `PampaiToolTrace.outcome: ToolOutcome" = null`, `Run.imageInterpretations: List<RunImageInterpretation> = emptyList()`, `RunImageInterpretation(provider, model, cached)`. No schema migration. Requested narrow public `runtime.preflightAssistantRequest(request, conversations, modelSelection)` contains the existing shared body; runtime delegates to it. UI calls production engine preflight; tests bind the actual shared function to real repository/selection.

Feature additions: `VoicePreparation`, `SendAdmission`, `QueuedQuestion.asRequest`, retained optional request snapshot, `EntryRequest.settingsSection`, `DraftHandoffs.SETTINGS_EXTRA/canClaim`, successful-import boolean, `importEntryHandoff`, `ConversationPolicySettings`. These do not introduce dispatch or implicit model fallback.

## Exact edit manifest for this correction

Paths below are relative to repository root; previously modified unrelated files are not claimed as this correction.

- app/src/main/kotlin/dev/pampa/pampai/MainActivity.kt
- app/src/main/kotlin/dev/pampa/pampai/assist/PampaiSession.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/PampaiRoot.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatViewModel.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatComposer.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatRoute.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/RunSteps.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/SendAdmission.kt (new)
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/VoicePreparation.kt (new)
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionController.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionBar.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionOverlay.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/DraftHandoffs.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/settings/AssistantSettingsItems.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/settings/SettingsRoute.kt
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/SendAdmissionTest.kt (new)
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/VoicePreparationTest.kt (new)
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/RunEvidenceRenderTest.kt (new)
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/UiCorrectionsTest.kt
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/ExperienceArchitectureTest.kt
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/session/DraftHandoffsTest.kt
- docs/aria-i2-ui-corrections.md (new)

## Validation and artifacts

Source work completed before runner transfer. Root explicitly transferred the sole Gradle runner after core source stability and final core143/0 failures/errors/skipped; that prior result belongs to the core report, not UI execution.

**Final integrated verification PASSED**, Gradle exit 0, BUILD SUCCESSFUL in 1m36s. Feature **50 tests, 0 failures/errors/skipped**, including all prior41 and nine added regression methods. Core **143 tests, 0 failures/errors/skipped** in retained XML; the combined command invoked its task UP-TO-DATE against the unchanged final core source (fresh execution belongs to the core final log). App assembleDebug, app lintDebug and feature lintDebug passed. Lint XML: app 0 errors/39 warnings, feature 0 errors/16 warnings. No claim that warnings are eliminated. Command uses `-Ppampai.buildRoot=C:/Users/casua/AppData/Local/PampAI-qa-build-full -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false -Pscreenshots --no-build-cache --no-daemon --max-workers=2` with core/feature testDebugUnitTest, app assembleDebug and app/feature lintDebug. Final log: C:/Users/casua/AppData/Local/Temp/pampai-i2-ui-r2-integrated-final.log. SCREENSHOT_DIR: C:/Users/casua/AppData/Local/PampAI-qa/I2-R2-ui. Scoped git diff --check passed before build.

Fixtures exercise production admission/state/navigation/rendering boundaries; they are not full Hilt/service/device E2E. Actual overlay-chip expansion/wait/hide, app/background lifecycle, microphone permission/STT, live helper timing, Binder sources, TalkBack and final device theme/motion matrix remain runtime evidence gaps. No gate approval is inferred from local tests or screenshots.

Intermediate verification issues retained in logs: integrated attempts 1/2 failed fixture compilation because Room is a core implementation dependency; the final fixture uses the public DatabaseModule factory/DAO providers and reflective database close without changing dependencies. Attempt 3 exposed a Unicode locator problem and a Robolectric main-thread fixture deadlock; the locator now checks name plus typed outcome, and the real ModelSelection fixture installs an unconfined main dispatcher with a bounded load wait. Only that run's owned Gradle test worker was stopped; no unrelated daemon/session was stopped. Attempt 4 includes the final owned-preparation Stop behavior.

Attempt 4 also identified the Navigation Compose lint requirement to remember the Home back-stack entry with the current NavBackStackEntry key; corrected without changing scope or selected policy. Final run enables -Pscreenshots (default test task excludes the dedicated screenshot suites).

Artifact paths:

- APK: C:/Users/casua/AppData/Local/PampAI-qa-build-full/app/outputs/apk/debug/app-debug.apk
- APK SHA256: 7AAE2DFA445C32F7B0ABF659F57D9A7612AB78AB64C1E7DC6CDCD023F99F17B1
- Feature XML: C:/Users/casua/AppData/Local/PampAI-qa-build-full/feature-assistant/test-results/testDebugUnitTest/
- Core XML: C:/Users/casua/AppData/Local/PampAI-qa-build-full/core-assistant/test-results/testDebugUnitTest/
- Lint: C:/Users/casua/AppData/Local/PampAI-qa-build-full/{app,feature-assistant}/reports/lint-results-debug.{xml,html}
- 16 actual PNGs in C:/Users/casua/AppData/Local/PampAI-qa/I2-R2-ui/: chat/empty/details/flow/memory light+dark; experience-hub and experience-picker light+dark+large-font. Inspected picker-large-font and hub-dark directly: readable controls, wrapping effort and grouped hub. New provenance/error regressions assert rendered semantics; these PNGs do not constitute device evidence for them.

An initial local.properties read inadvertently returned existing signing values in tool output. No values are copied into this report or sources and no credentials were changed. This was an execution mistake, not an authorized secret inspection.

**Sources stable and sole runner released.** All owned Gradle invocations exited. Final process query found no GradleWrapperMain, GradleDaemon or GradleWorkerMain Java processes. No background worker/server/device remains owned by this correction. Root may resume integration/review; no implementation gate or device E2E approval is claimed.
