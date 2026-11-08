# First delivery I3 corrections ? R3/3

Status: local validation PASSED. Root completed serialized Gradle validation after Fluidify explicitly released the runner. No emulator/provider/API calls, commits or publication. I3 reviews completed on the previous339-file snapshot; its freeze is released, not retroactively modified or approved. This is final permitted correction round R3/3.

## Changes

- Common P2 cancellation: discardVersion now reports whether the empty version was actually removed. The production closeCancelledTurn closure retains the cancelled Run (actual helper provider/model/cache and typed tool traces) whenever the write-retention guard keeps the version. Truly discarded versions do not create orphan Runs. AssistantEngine uses this closure in its NonCancellable cleanup; retained ledger/replay blocking preserved.
- R2 P2 text ownership: send/edit/regeneration revalidate the captured text-preparation generation plus conversation after suspended work. Regeneration also uses the runtime request generation with an atomic conditional dispatch, so a newer request/listening owner cannot be cancelled by late admission. Stop/hide/navigation invalidate preparation without resetting the visible composer: text-preparation generation is deliberately distinct from composerEpoch.
- The production QueuedSendAdmission keeps a preparing item in the queue until dispatch succeeds. releaseQueue/unqueue can still recover text/images; Stop/hide/navigation invalidate its owned job/generation. Non-cooperative late helpers cannot consume a replacement queue or clear its preparation. Idle queue dispatch additionally requires the chat visible and app foreground, with atomic replaceBusy=false guarding a newer runtime owner. A temporarily busy runtime retains the item for the next flush.
- R2 P3 helper disclosure now says a description was prepared/reused for the primary, without asserting downstream receipt. Existing provenance captures completed conversion, not successful dispatch of all subsequent images.

## Prepared regressions (not run)

- TurnCancellationTest: production cancellation closure after actual fresh/cache conversion and durable write reservation, for edit and regeneration; reopen verifies cancelled Run, typed trace/provenance, receipt and duplicate-write prevention. Genuinely discarded empty version produces no orphan Run.
- TextAdmissionOwnershipTest: suspended edit/regenerate then navigation away-and-back cannot commit; preparing queue item remains recoverable after navigation; busy newer request leaves item queued; stopped non-cooperative helper cannot consume a replacement queue.
- RunEvidenceRenderTest: a failed stored preparation discloses prepared interpretation and does not claim that the primary received it.

Existing current validated evidence remains feature50/core143 BEFORE these changes. No new PASS claim. Root CV batching refinement also awaits scoped execution. Build/lint/tests will run serially only after explicit Fluidify release. Required final systemoverlay/modal/back/active handoff, real queue, Binder and full accessibility/device evidence remains open. Do not spend the final I4 review just to restate unchanged NV; concrete artifact/evidence or an explicit user decision on gate limitations is needed. Profile/background remain unstarted.

## Files changed in this correction

- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/ConversationsRepository.kt
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantRuntime.kt
- core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/TurnCancellation.kt (new)
- core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/runtime/TurnCancellationTest.kt (new)
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatViewModel.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/SendAdmission.kt
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/QueuedSendAdmission.kt (new)
- feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/RunSteps.kt
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/TextAdmissionOwnershipTest.kt (new)
- feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/RunEvidenceRenderTest.kt
- docs/aria-i3-corrections.md (this report)
- docs/aria-workflow.md (root state and mandatory review tables)


## Final local validation

Fresh core145 and feature55 tests pass, zero failures/errors/skips. APK assemble, app lint and feature lint pass. Lint warnings remain app39/feature16, zero errors. Sixteen fresh screenshots generated under C:/Users/casua/AppData/Local/PampAI-qa/I4-root. Log C:/Users/casua/AppData/Local/Temp/pampai-i4-root-validation.log BUILD SUCCESSFUL4m44s (exit0). APK .../PampAI-qa-build-full/app/outputs/apk/debug/app-debug.apk SHA256 E53AFBED798DF51FF9536F7B9813AE4454B8AD44DBD196BEB0480454CF931554. Earlier NOT-RUN notes describe the initial source-only handoff and are superseded by this section. Root runner released after reading final completion; no other Gradle started. New device verification starting only in the main-app path; previously stopped system-overlay driver path will not be retried/bypassed. Profile/background and final review gate remain open.


## Latest device attempt, 2026-11-07

Own AVD Classeviva_CIE_QA_35 opened via device_open as emulator-5554; physical Samsung/other AVDs untouched. Latest APK E53AFBED798DF51FF9536F7B9813AE4454B8AD44DBD196BEB0480454CF931554 installed successfully using adb install -r -t, preserving data. Bound agent-device open dev.pampa.pampai.debug --relaunch failed COMMAND_FAILED: Android app dev.pampa.pampai.debug process remained running after close. Followed supplied advice ONCE with --debug; same failure. Diagnostic IDs mw6qow8o-d4f626c0 and mw6qpa9o-1df024f8, remote diagnostics session t3-63a0bc99bcec50a86674d2a4. No UI launch/snapshot/action was certified by these failed calls. Automation path stopped, no adb/alternate-driver workaround or retry of earlier failed overlay path. Own emulator shut down via device_close to release resources. No extra live-model calls.

Latest APK is installed but final app/overlay/queue/Binder/accessibility behavior remains unverified. Requesting user decision: manual checkpoint with concrete APK versus explicit provisional progression with device validation deferred. This is necessary because user-approved plan and agentic-workflow retain mandatory unverified criteria; no implicit waiver from timeout. Continue only independent Source2 review/corrections while pending. No final I4 review or profile/background work started yet.
