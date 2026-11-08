# Aria integration 2 — first-delivery correction R1/3

2026-11-07. This records integration evidence, not independent approval. Profile/background remain unstarted.

## Architecture build and fixtures

The first architecture compile found five FluidListRow calls missing the required subtitle. Root supplied empty subtitles for compact destination rows. The next run compiled and generated the actual architecture screenshots, but two new fixtures failed: the page-title assertion matched both Fluid title representations, and synthetic picker options were replaced by the real empty-key catalogue after saving effort. Root narrowed the page assertion to its heading semantics and restored only the fixture's synthetic catalogue after the actual preference emission; route, effort, identity, draft, scroll and zero-send assertions remain.

`C:/Users/casua/AppData/Local/Temp/pampai-ia-integration-3.log`: **40 feature tests passed**, zero failures/errors/skips, APK assembled, app/feature/engine-ai-ui lint passed with zero errors. Properties: isolated `PampAI-qa-build-full`, in-process/nonincremental Kotlin, no build cache, no daemon, maximum2 workers. Core140, engine248 and bridge25 were the preceding passing runs; root did not rerun unchanged suites just to inflate counts.

Actual screenshots in `C:/Users/casua/AppData/Local/PampAI-qa/integration2`: experience-hub-light/dark/large-font and experience-picker-light/dark/large-font, plus existing chat/flow/memory fixtures. Root opened hub-light, picker-light and picker-large-font. They are production composables rendered by Robolectric, not device screenshots or mockups. Font/short-height fixtures do not prove actual TalkBack or system IME behavior.

Root added a visible action to disable a selected visual helper while retaining the primary model, with a real picker regression. Engine docs07-ai and the ai-ui API documentation mirror describe the default-compatible focused-settings API.

## Device and live provider sample

Device opened from inventory by its own AVD name: local Classeviva_CIE_QA_35, Android15, assigned emulator-5556 for this run. Bound CLI session `t3-b73bc6bab5bf6e050bc1ec7b`. No other devices/sessions touched. Installed the integration-3 APK with adb install -r -t, preserving existing test keys and conversations. All UI actions used the returned agent-device CLI; adb was used only for install, synthetic media fixture setup and read-only database diagnostics. No credentials were printed, copied into source, or passed to delegates.

Observed on the actual app:

- Entered `Bozza da conservare`, opened drawer/settings/AI/keys, used system Back to hub and then chat. The exact unsent draft remained. Existing timer test conversations were still in history after the upgrade.
- Opened the actual model picker, searched `gpt-oss-20b` with the real keyboard visible and selected Groq openai/gpt-oss-20b. Effort/search/model/close controls were visible above the keyboard. The original draft remained after model selection.
- Attached a deterministic synthetic image: ARIA427 text, blue rectangle and red circle. The first send with UNKNOWN image support and no helper opened compatibility selection rather than dispatching/consuming the draft. It exposed a real recovery bug: the preceding text-model search survived and hid all compatible choices. Evidence `device-image-preflight-query.png`.
- After manually clearing that stale search, selected Gemini2.5Flash in explicit visual-helper mode. Primary remained Groq20b; text/image draft was retained and required another explicit send.
- Live helper and final answer completed. The answer described the two forms but omitted the requested text, so semantic task completion is **partial**, not a passed intelligence/quality benchmark. `device-vision-helper-response.png`.

Read-only app ledger export verified actual provider/model and accounting, rather than inferring backend from UI labels. Sanitized selected columns are in `device-model-usage.json`; the temporary complete database/WAL diagnostics were removed after extraction. Results:

- VISION_HELPER Gemini gemini-2.5-flash:287 prompt tokens,1487 completion tokens,10,021ms, no error.
- ROUTER Groq openai/gpt-oss-20b:400 json_validate_failed,1,823ms; recorded as failure rather than hidden as success.
- Final DEEP tier Groq openai/gpt-oss-20b:3192 prompt tokens,627 completion tokens,1,130ms, no error. Tier change retained exact selected model.
- Before-dispatch rows preserve requested AUTO, resolved NONE/HIGH, actual wire reasoning field and omission after rejection; selected Groq20b unchanged. SENT_UNVERIFIED means encoded, not proof of honored reasoning.

These durations are individual calls, not a measured end-to-end latency. One live helper usage row is consistent with caching across repeated preflights; cache correctness is also covered by existing tests. No raw request/prompt or key export is included in the sanitized diagnostic.

The tool's expired-ref error was recovered with a fresh snapshot; a PowerShell-quoted selector error was recovered using a fresh ref. The earlier failed system-overlay input path was not retried or bypassed. This run tested the main app only. Emulator shut down via device_close after evidence capture to free resources.

## Root corrections after the device sample

- Automatic incompatible-image recovery now clears stale search/helper browsing mode when imageChoiceNeeded becomes true. A production-root regression first leaves a text-model search behind and then requires visible compatible choices.
- The live helper took ten seconds before the runtime request began. Root added immediate `Preparo la richiesta…` status, polite accessibility announcement and a Stop action during suspended preflight, in shared app/overlay composition. Cancelling preserves the draft and prevents a delayed send; a suspended-send cancellation regression exercises the app composer. This changes feedback and cancellation, not claimed network speed.

Final `C:/Users/casua/AppData/Local/Temp/pampai-ia-integration-4.log`: **BUILD SUCCESSFUL**,41 feature tests/0 failures/0 errors/0 skipped; app assemble and app/feature/engine-ai-ui lint passed. The earlier installed integration-3 APK does **not** demonstrate these final feedback/search corrections on device; production-root fixtures do. All root-owned runners finished.

Frozen review snapshot **I2**, correction round **R1/3**:324 files in `C:/Users/casua/AppData/Local/PampAI-qa/review-I2/manifest.json`, SHA256 `DDF0FD9438BDDA33EAA14CD5D6B4F788029F0385D5668E141507FFF5790C60EE`. Latest APK SHA256 `466BAA0D020BE59D6AB4FA17B7F2703D2ABC4BBABA36FFD335C9DF94C37FEAA5`. This snapshot includes the architecture increment and root corrections; it supersedes I1 for the next two independent reviews. No source writes while those reviews run.

## Remaining evidence

Actual final overlay model sheet/back, draft+image transfer to app while a request runs, two-app Binder cancellation/readback, active queue model changes, TalkBack and the full theme/motion/device matrix remain unverified. Source2 tests/builds are documented separately in aria-downstream-validation.md (574 affected tests), with no claim of actual Spotify/school services. Full ClasseViva lint still has six unchanged pre-existing engine-ui errors. No new review gate is inferred from these checks.
