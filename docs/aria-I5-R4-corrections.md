# I5 — authorized fourth correction

2026-11-08. User explicitly authorized fixing the remaining findings and then advancing to the next phase regardless of the earlier gate. This overrides the advancement block, not the truth of missing runtime evidence. No past NV is converted into a pass. No device path bypass or publication.

## Implemented fixes

- **I4-R1-F1:** actual `SessionController.askPrepared` captures the shared runtime generation before model-loading/image preparation can suspend. Final admission uses `runtime.submitIfCurrent(..., replaceBusy=false)`. Newer or already-busy work is not cancelled; a rejected admission preserves draft and captured attachments. Attachments are consumed only after successful admission. Existing visibility/draft/conversation checks remain.
- **I4-R1-F2:** `runQuick` registers its real tool context with outer answer cleanup immediately after construction, before durable preparation/tool execution can suspend. The existing real `TracedTool` cancellation trace therefore reaches `closeCancelledTurn` and the retained cancelled Run. Durable receipt/uncertainty/no-replay behavior is unchanged.

## Production-path regressions

`SessionAdmissionTest` instantiates the real session controller with runtime/model/flow boundaries mocked: deferred preflight followed by newer B, an already-busy other conversation, Stop, hide, and successful unchanged-owner admission. Tests assert zero unconditional submit calls, exact conditional generation/replaceBusy values, draft preservation and attachment consumption only on acceptance. Real screen store/image encoding is used. The first run's Stop-case spy assertion incorrectly expected a conditional call even though the earlier draft-generation guard correctly rejected it; the assertion now requires zero calls for Stop/hide, while preserving rejection/draft/image assertions. No production guard was weakened.

`QuickCancellationIntegrationTest` executes the real `AssistantEngine.execute` quick matching, real registry/traced music tool, Room repository/operation ledger and cancellation cleanup. Only runtime/settings/dependencies/media transport are mocked. Pause suspends after the durable write reservation; cancel, reopen DB, then assert cancelled Run's actual uncertain trace, uncertain receipt and blocked replay. This is not a direct call to cleanup with a manufactured trace.

MockK JVM1.14.11 is added to core/feature test dependencies only (already locally cached); no production dependency/API or database migration is added.

## Validation

Initial log `docs/aria-I5-R4-tests-01.log`: core146 passed; non-screenshot feature47 had one incorrect spy expectation as described above. Final log `docs/aria-I5-R4-validation.log`: BUILD SUCCESSFUL2m35s, core146/feature63,0failures/errors/skips; app assembled and app/feature lint passed. The core task was UP-TO-DATE on final invocation after its fresh successful first run; feature63 is fresh with screenshots enabled. Do not add old145/58/47 counts.19 PNGs under `C:/Users/casua/AppData/Local/PampAI-qa/I5-R4`. JBR, external build root, serial max-workers1/no-daemon/in-process/nonincremental Kotlin used. No device/provider/API call or secret/config-store read. Root runner released after command completion.

Changed sources/tests: SessionController.kt; AssistantEngine.kt; core-assistant/build.gradle; feature-assistant/build.gradle; SessionAdmissionTest.kt; QuickCancellationIntegrationTest.kt; this report. Scoped diff check and frozen artifact hashes are recorded by root separately.

## Review isolation and authorized advance

Two fresh Sol xhigh reviews assess an immutable copied source/evidence snapshot of this validated I5 increment. It is an artifact snapshot, not a new checkout/worktree. The main checkout may advance to research/profile/background under the user's new instruction; those later changes are outside this I5 review. Snapshot paths/hash map define what was actually reviewed. Runtime NV and Source2 validation limits remain listed in earlier reports. Background remains disabled until explicit user setup.
