# Aria I1 UI corrections - R1/3

Scoped correction delivery after I1 R0, 2026-11-07. Read accepted P2, both review lists in aria-workflow.md, Fluid Engine SKILL/regole/menu-modali, Material3 skill, and reliability correction API notes. No AGENTS.md found in repository/parents or owned modules. Preserve concurrent work. No core/engine/workflow edits, devices, providers, secrets, agents, commits or publication. Runner transferred explicitly after reliability finished; exclusive serialized validation described below.

## Finding mapping

- **R2 F1 P1:** production PampaiSession now mounts SessionModalRoot. It installs LocalFluidGlassModalHostState, records the underlying overlay as the backdrop, hides underlying accessibility nodes while a modal is open and mounts the real FluidGlassModalHost. SessionComposeHost is now an OnBackPressedDispatcherOwner with a view-tree owner; VoiceInteractionSession back dispatches to Fluid's predictive/modal back first, with controller selection/overlay dismissal as fallback. The host is recreated per showing stamp so abandoned sheets cannot survive reopening. Existing PampaiTheme reduced-motion policy applies. Picker header, catalog and effort/close footer share a bounded lazy scroll viewport, so large fonts and IME-reduced space can reach all controls without eagerly rendering a whole catalog. Uses actual Fluid sheet/composer, no settings workaround.
- **R2 F2 P2:** retained root current-text comparison and strengthened it to compare the exact captured raw text (whitespace edits count as edits). onSend false keeps draft; only accepted unchanged input clears it. ChatViewModel captures attachment references and destination before awaiting model load; it consumes only those references after successful preflight. SessionBar now reads controller-owned current text on return, and SessionController likewise removes only submitted attachment references. New screen attachment objects survive an older preflight. Visibility and generation guards reject an overlay preflight after hide/show/handoff, including voice preflight and a send coroutine that starts only after hiding.
- **R2 F3 P1:** explicit process-owned token transfer described below. Expansion carries pending text, copied image bytes, conversation and exact policy without sending. New/empty overlay explicitly targets a new app draft rather than whichever chat the app happens to display. Existing in-flight session conversation ID wait remains; if no ID appears within the existing timeout, keep the overlay/draft and show a retry notice instead of opening an unrelated chat or losing the draft. Composer epoch resets pending text/attachments on an explicit new chat/change of conversation, preventing consumed drafts from appearing in the next chat. Final handoff snapshot occurs after the ID wait, so edits made while waiting are included. The destination composer starts with a new epoch and imports by filling rather than clearing, so a user edit before its composition effect also survives.
- **R2 F7 P2:** retained root favorite action+model contentDescription, Role.Checkbox and ToggleableState. Explicit minimum width/height of 48dp. Regression inspects/clicks actual picker nodes and observes persisted favorite state.
- Preserved root ToolResultCards readable fallback titles, localized dates and no raw entity IDs. Consumed source2 typed Spotify readback: playlist name from data.playlist.name; items[].item.track.name and explicit null/unavailable entries. The Spotify entity enables existing ReceiptActions.open signed readback, never MediaBrowser lookup of Spotify IDs. The original ToolOutcome remains authoritative; current readback never promotes an uncertain write or implies playback. Added pure regression coverage for these source fields and local flat MediaSession compatibility. Removed the obsolete MODEL_UNAVAILABLE phrase claiming an automatic model switch.

## Handoff API and lifetime

`DraftHandoff(conversationId, text, attachments, policy)` and `DraftHandoffs.put/consume/discard` are feature-owned, with no core changes. `put` copies attachment byte arrays and creates an opaque UUID; only the latest explicit expansion is deliverable. `consume(token, intendedConversationId)` removes it once only if the destination matches. No implicit lookup by latest conversation. A failed Activity launch discards the token and leaves the overlay visible. Closing alone creates no transfer. `ScreenContextStore.clear()`/session hide cannot invalidate the copied JPEG/crop.

MainActivity carries `DraftHandoffs.EXTRA` into EntryRequest.handoffToken; token and destination stay in the Activity Intent through recreation before consumption. A stale consumed token does nothing on recreation. PampaiRoot routes a handoff exclusively through ChatViewModel.acceptHandoff (no voice/submit path). That method selects the same conversation or explicitly creates a new draft, imports attachments/text, and restores policy through ModelSelection.save with temporary-conversation semantics. A pending exact-policy override covers the asynchronous save for subsequent explicit send/voice. Persistence work is guarded against navigation to another draft. No new AssistantRequest is generated by import; an existing request remains owned by its existing conversation/runtime.

The transfer survives VoiceInteractionSession hide/destruction and Activity recreation within the same app process. It deliberately does not persist unsent pixels/temporary drafts to disk or survive process death; the existing runtime request/service durability remains unchanged. No automatic replay of writes or sends. ModelSelection public defaults remain compatible with reliability R1 (including dynamic imageChoiceNeeded recovery).

## Regression sources

`UiCorrectionsTest` uses the production SessionModalRoot plus a real ModelSelection with empty local key stores and no provider calls. It covers picker opening, catalog search, actual favorite role/state/action toggle, root back closure, incompatible-image prompt, explicitly selected helper preserving the primary, short viewport with 1.8x font and reduced motion, long SessionCard response scroll, suspended onSend edits, and false-preflight explicit retry. Short viewport is a deterministic keyboard-space fixture, not an IME/device claim.

`DraftHandoffsTest` covers text/image byte ownership and exact effort/helper policy, wrong destination rejection, consume once, new/empty overlay targeting, cancellation and stale/superseded token isolation. These are transfer-boundary tests; full live Activity/controller handoff during an active provider request is still runtime evidence to collect.

## Exact files changed by this correction agent

- app/src/main/kotlin/dev/pampa/pampai/MainActivity.kt
- app/src/main/kotlin/dev/pampa/pampai/assist/PampaiSession.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/PampaiRoot.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/AssistantTexts.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatComposer.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatRoute.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatViewModel.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ToolResultCards.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ExactModelPicker.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/DraftHandoffs.kt (new)
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionBar.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionComposeHost.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionController.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionModalRoot.kt (new)
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionOverlay.kt
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/ToolResultCardsTest.kt
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/UiCorrectionsTest.kt (new)
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/session/DraftHandoffsTest.kt (new)
- docs/aria-i1-ui-corrections.md (new)

Other already modified files, including ChatScreenshots queued viewport fixes, were preserved. ToolResultCards was extended only as documented above.

## Validation

**Final delivery: runner and app/feature source freeze RELEASED.** Latest `pampai-ui-r1-final3.log`: BUILD SUCCESSFUL in 2m 2s; feature tests 29 passed (including 13 screenshot fixtures), app assembleDebug passed, feature/app lint passed with zero errors. Lint: feature 16 warnings + 1 hint; app 39 warnings + 1 hint. `git diff --check` passed on owned files. Latest XML confirms no failures/errors/skips. Core suite ran in `pampai-ui-r1-final2.log`: 140 passed, including 6 SourceIntegrationTest and 1 SourceReceiptPersistenceTest; no core source changed afterwards, and this agent made no core edits. Engine248/bridge25 are the reliability implementer's results, not this agent's executions.

APK: `C:/Users/casua/AppData/Local/PampAI-qa-build-full/app/outputs/apk/debug/app-debug.apk`, 98,925,933 bytes, SHA256 `7c2b2a52280006fb98c1bb6b08d10dc2cb164b8c7dad4f9dfef5738e188ea054`.
Reports: `C:/Users/casua/AppData/Local/PampAI-qa-build-full/{app,feature-assistant}/reports/lint-results-debug.{xml,html}`; test XML under each module's `test-results/testDebugUnitTest`.

Final command (all root-approved flags retained): `:feature-assistant:testDebugUnitTest :app:assembleDebug :feature-assistant:lintDebug :app:lintDebug -Pscreenshots`; preceding final2 invocation also included `:core-assistant:testDebugUnitTest`. Both used `-Ppampai.buildRoot=C:/Users/casua/AppData/Local/PampAI-qa-build-full -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false --no-build-cache --no-daemon --max-workers=2 --console=plain`.

Validation history (superseded by the green result above):

First execution compiled app/feature/new tests and ran 14 nonscreenshot feature tests: 13 passed; rootPickerFavoriteSearchAndImageRecovery timed out waiting for DataStore favorite emission. Added explicit Compose/main-loop idle pumping to its persistence waits; retest not reached yet. Next invocation was blocked by concurrent out-of-scope core compilation errors in SignedPlaylistReader.kt:69 (val reassignment/List vs MutableList) and ReceiptActions.kt:51 (cross-module smart cast). Root/source2 fixed and froze those core files. Targeted rootPickerFavoriteSearchAndImageRecovery retest then passed (pampai-ui-r1-picker-retest2.log); it exercises favorite persistence, root back and explicit helper choice. First full screenshot run passed 28/29 tests; the short-viewport fixture hit the same ModelSelection startup main-loop wait before interactions. Applied explicit idle pumping consistently to initial load waits. Final invocation in pampai-ui-r1-final2.log includes the requested core suite and reports XML totals: feature 29/0 failures/0 errors/0 skipped; core 140/0/0/0, including SourceIntegrationTest 6 and SourceReceiptPersistenceTest 1. The same invocation assembled app-debug.apk but feature lint reported one pre-existing-in-wave composition read: SessionOverlay picker used controller.conversationId.value rather than its already lifecycle-collected conversationId. Corrected that access (also ensures picker updates with conversation); final3 reruns feature suite/assemble/app+feature lint. The subsequent final3 result above passed all requested checks. Commands use root-approved isolated buildRoot, in-process/nonincremental Kotlin, no cache/no daemon and max-workers=2. Log: C:/Users/casua/AppData/Local/Temp/pampai-ui-r1-validation.log. No device or provider evidence collected by this agent. Earlier 120 core + 19 feature results are pre-correction evidence only; reliability separately reports core133/engine248/bridge25. This agent ran the latest core140/feature29 suites; engine suites were not rerun by this agent.

Remaining runtime evidence: actual overlay sheet/back and keyboard on supported device tooling, overlay draft+JPEG to app unsent, active request expansion with no second dispatch, temporary conversation lifecycle, live explicit helper image flow/usage and TalkBack. Do not bypass the previously failed system-overlay driver. No profile/background gate is passed by these source/fixture changes.


## Architecture handoff to next focused increment

Read docs/aria-experience-architecture.md after root steering. This delivery is the correctness foundation, not the full IA acceptance. Preserve SessionModalRoot/SessionComposeHost back/lifecycle ownership, the shared app/overlay composer controls, explicit model/effort/helper policy, token handoff isolation, draft edit guards, typed card rendering and these regressions. Settings/drawer reorganization is intentionally left to the fresh root-assigned increment. Navigation must preserve conversation/scroll/draft and must never submit/regenerate. The next picker hierarchy should keep search and effort reachable without traversing the catalog; this correction currently makes the entire sheet predictably scrollable but does not claim that final hierarchy. Temporary chat remains an explicit new-conversation behavior. No placeholder profile/background destinations; first-delivery integrated checkpoint and independent review remain open.
