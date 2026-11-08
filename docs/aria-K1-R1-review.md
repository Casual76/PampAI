R1 → ROOT: **provisional K1 initial R0 review — five actionable findings: one P1 and four P2.** All triggers below are derived from copied production code; none was executed during this read-only review.

All paths refer to `C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/`.

1. **P1 — The production UI cannot connect period-based Fluidify history.**  
   [ProfilePresentation.kt:124](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfilePresentation.kt:124) emits `from` and `until` as JSON numbers. `ResearchReadPolicy.check`, in copied `pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchTransport.kt:76–82`, requires strings for those arguments. The actual producer also declares both as strings in copied `fluidify/app/src/main/java/dev/lelonio/square/bridge/FluidifyToolHost.kt:40`.

   **Minimal trigger:** select Fluidify’s history records, aggregate, track counts or artist counts, enter valid dates, and connect. The consumer rejects the generated request with `INVALID_ARGUMENT_TYPE` before source dispatch.

   **Fixture boundary:** core fixtures supply string timestamps (`ResearchConsumerTest.kt:106–107`); UI tests mock `ResearchSources` (`ProfileViewModelTest.kt:18–20`). Neither crosses this production boundary.  
   **Narrow remedy:** serialize both timestamps with `toString()`, retaining the producer and consumer schemas.

2. **P2 — A saved correction can make subsequent research discard its entire answer.**  
   [DurableFoundation.kt:187](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt:187) returns `false` for the whole commit when any automatic profile candidate targets a field with an active `USER_STATEMENT`.

   **Minimal trigger:** correct field F, then run an authorized synthesis that returns a valid answer plus an otherwise valid observation/inference for F. Copied `ResearchRunner.kt:123–124` treats the rejected commit as `COMMIT_FENCED`; its catch path at `148–164` suspends the job with unknown eligibility. The already-accounted model response is discarded.

   **Evidence versus inference:** rejection of the whole transaction follows directly from the code; occurrence depends on the model returning that permitted profile candidate. Existing correction tests verify retrieval precedence, without a subsequent synthesis containing the corrected field.  
   **Narrow remedy:** suppress conflicting automatic profile writes transactionally while allowing the answer and synthesis checkpoint to commit.

3. **P2 — Canonical record reuse conflicts with changed acquisition provenance.**  
   [DurableFoundation.kt:198](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt:198) rejects an existing evidence ID unless its provenance equals the new acquisition’s provenance. However, copied `SourcePages.kt:85–89` excludes acquisition settings from the ID while including the entire `ResearchRead` in provenance.

   **Minimal trigger:** acquire a ClasseViva record, reconnect the same account/student/year/section with technical refresh changed from false to true, then acquire the unchanged record. Same authorization preserves the grant (`ResearchSources.kt:86–87`). The record receives the same ID but different provenance, so the `require` at `DurableFoundation.kt:200–203` throws.

   **Impact:** a legitimate scope/settings change can break research on already-known records. The exception also bypasses the runner’s `ResearchUnavailable` handling.  
   **Narrow remedy:** separate canonical record content from acquisition/proof provenance, preserving explicit acquisition-to-checkpoint binding rather than weakening the provenance check.

4. **P2 — Reused evidence bypasses the configured `maxRecords` limit.**  
   [DurableFoundation.kt:182](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt:182) counts rows owned by the current job plus newly inserted canonical rows. Existing canonical evidence contributes zero.

   **Minimal trigger:** a previous job stores records R1 and R2; a new job with `maxRecords=1` receives the same two records. Both resolve to existing evidence, so the count check passes. Copied `DurableFoundation.kt:66–72` subsequently reloads both through checkpoint references and supplies them to synthesis.

   **Evidence versus inference:** acceptance of both records follows from the counting and snapshot paths. No execution was performed. The retained deduplication test explicitly expects zero newly owned rows, without testing the current job’s record limit.  
   **Narrow remedy:** enforce the distinct evidence-ID union consumed by the current job, including referenced canonical evidence across all steps.

5. **P2 — Forgetting one mapped field blocks unrelated connected research.**  
   [DurableFoundation.kt:102](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt:102) treats omitted field/grant/record arguments as wildcards. Calls to `blocked()` without scope in `createJob:142` and `claim:160` therefore match every exclusion.

   **Minimal trigger:** forget `fluidify.history_records`, then request a new ClasseViva grades lookup. The unrelated job cannot be created or claimed. Copied `ResearchScheduler.kt:65` independently suspends every area for any field exclusion whose `grantId` is null.

   **Impact:** the UI’s category exclusion becomes a global connected-research stop until that exclusion is removed.  
   **Narrow remedy:** evaluate mapped exclusions against the job’s actual fields and source scopes; retain global suspension for genuinely unmapped exclusions and retain the existing race fences.

All numeric scores below are **limited and provisional**. They are not an approval calculation; no mean/minimum is supplied.

| Criterion | Score | Verification state | Reason/evidence | Problems / severity |
|---|---:|---|---|---|
| C1 Product/scope | 6/10 | Source inspected | Quick commands precede research; deterministic planning uses saved scopes and captured policy; background remains silent. | Music connection failure F1; unrelated research blocked by F5. P1/P2. |
| C2 Tools/contracts/actions | 7/10 | Source inspected; archived tests checked | Signed host allowlist, typed negotiation, exact scope receipts and read-only inner-tool allowlist are connected in production. | UI/consumer/producer mismatch F1; acquisition binding conflict F3. P1/P2. |
| C3 Models/images/routing | 8/10 | Source inspected; retained fixture evidence | Chat transports admit and settle individual wires; helper identity, retries, web and title paths inherit accounting. Unknown bounds/tariffs refuse capped execution. | No additional source-proven escape identified in inspected paths. Live billing, capabilities and semantic quality NV. |
| C4 Fluid/IA/UI/continuity | 6/10 | Limited source/render review | Actual Fluid profile/source/setup routes inspected; three copied renders inspected: light profile, dark deduction and wide 2× setup. | Functional flows affected by F1/F2. TalkBack, overlay/IME, handoff and actual continuity remain NV. |
| C5 Profile/background/budget | 5/10 | Source inspected; archived fixtures | Lease fences, temporary-job rejection, proof renewal, conservative reservations, finite WorkManager tranches and startup wiring are present. | Correction commit failure F2, record-limit bypass F4, global exclusion F5. P2. |
| C6 Source/data fidelity | 6/10 | Producer/consumer source inspected | Actual Fluidify fragment envelopes and CV typed records/aggregates examined; imported unknown durations remain unknown. | Canonical acquisition collision F3 and count bypass F4; music schema mismatch F1. P1/P2. |
| C7 Evidence/readiness | 8/10 | Integrity and retained artifacts independently checked | Complete before/after manifest verification; XML counts and worker/root provenance separated. | Fixtures miss the production boundaries above. Runtime readiness NV; Fluidify app build remains blocked outside proof scope. |

Integrity verification **before and after** produced identical results:

- **1,064/1,064 files** matched their copied SHA-256 and size; **169,741,221 bytes** total; zero missing, unlisted or mismatched files.
- Manifest SHA-256: `8B69D9B7E7E8F944E59E025AB827BE55CFC5E3038BA58DFB26B74676EA31EA49`.
- Final PampAI APK: **100,571,185 bytes**, SHA-256 `40005BC7CDBB539099C24F29A298D7FEE9B8844868FDE8C2F049B6D749C4753C`.
- Worker input map: **666/667 hashes match**; the sole changed file is `UpdatesRoute.kt`. This confirms the file boundary, without independently reconstructing its earlier text.

Retained execution evidence was parsed, not rerun:

- Worker: **90 JUnit XML files** — core **39/231 tests**, feature **16/91**, engine **31/264**, bridge **4/25**; all zero failures/errors/skips. Feature comprises **61 standard + 30 screenshot cases**; **32 PNGs** are copied.
- Root: **one XML, two screenshot cases**: `workerUncertainStatusDark` and `workerQueuedStatusLight`. They are repetitions, not 93 feature tests. The copied root log records feature/app lint, APK assembly and `BUILD SUCCESSFUL`; it does not establish a fresh core/engine/bridge rerun.
- All **55 unique assigned source-proof cases** resolve to passing copied XML. Broader retained source archives contain Fluidify **55 XML/403 cases** and CV **59 XML/491 cases**; these are archived evidence, not new execution here.
- Earlier failed worker runs remain distinguishable from successful final runs. CV’s copied build/lint log succeeds. Fluidify’s later copied app build fails in AirPods files, and its core lint XML retains **three errors**.

No files were written; no builds, tests, devices, credentials, live APIs/providers, delegation, commits or peer opinions were used. Advancement authorization remains unchanged. Phase/device approval is not issued, and all stated runtime/hardware gaps remain **NV**.
