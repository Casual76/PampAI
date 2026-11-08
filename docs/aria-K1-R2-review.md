# K1 initial R0 — independent R2, gpt-6.1-sol xhigh

R2 → ROOT: **provisional review with seven actionable findings**. No phase or device approval is granted.

**Isolation caveat:** one malformed read-only search fell back to the mutable working directory and returned out-of-scope snippets, including historical documentation. I excluded that output from the assessment. This run therefore cannot be certified as perfectly isolated; ROOT should treat it as a qualified review.

All finding locations below refer to the verified copy under `C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/`. Findings are source-derived; I did not execute their triggers.

1. **P1 — The normal music-history connection form produces rejected arguments.**  
   [ProfilePresentation.kt:121](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfilePresentation.kt:121) converts dates to epoch milliseconds, then inserts numeric JSON values at line 124. [ResearchTransport.kt:79](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchTransport.kt:79) accepts numbers only for pagination fields and requires strings for `from`/`until`. `ResearchSources.connect()` invokes this validation before contacting the source.  
   **Minimal trigger:** connect any of the four period-based history sections through the UI with valid dates. The inferred result is `INVALID_ARGUMENT_TYPE`, preventing connection. Fluidify’s copied producer schema also declares these fields as strings. Consumer fixtures use `"0"`/`"1000"` strings; UI tests mock `ResearchSources`, explaining the missed seam.  
   **Narrow remedy:** serialize the form’s epoch values as strings and cover form output through the real read-policy boundary.

2. **P2 — A scoped forget/exclusion disables unrelated persistent research.**  
   [DurableFoundation.kt:102](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt:102) treats omitted lookup dimensions as wildcards. Calls to `blocked()` without arguments therefore match **every** exclusion. Job creation, claiming and validation use that unrestricted check at lines 142, 160 and 168.  
   **Minimal trigger:** forget `fluidify.history_records`, then ask about an independently authorized ClasseViva grades scope. New persistent research is rejected despite the unrelated field/source. [ResearchScheduler.kt:65](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchScheduler.kt:65) separately suspends every area for any field exclusion with a null grant.  
   **Narrow remedy:** evaluate mapped exclusions against the job’s captured reads/dependencies. Preserve broad suspension for genuinely unmapped exclusions.

3. **P2 — Topic keywords are mistaken for requests to consult personal knowledge.**  
   [ResearchQuestionPlanner.kt:5](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchQuestionPlanner.kt:5) matches domain words without checking personal-data intent. [ResearchForeground.kt:23](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt:23) converts any matching authorized read into a research task; the engine returns through that path before normal conversation handling.  
   **Minimal trigger:** with grades connected, ask “Che cos’è un voto di fiducia?”. `voto` selects school grades, so an unrelated general question reads and sends private school evidence to the selected model. Music-history connections similarly affect general music questions.  
   **Evidence:** deterministic source path; actual provider output remains unverified. The retained relevance test checks a completely unrelated cake question, not an unrelated question containing a domain keyword.  
   **Narrow remedy:** distinguish personal/source consultation from general discussion before selecting reads or retrieving profile knowledge.

4. **P2 — Saved interactive connection scopes disappear from the setup UI after recreation.**  
   [ResearchConnections.kt:16](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchConnections.kt:16) persists exact reads independently of autonomous setup. However, [ProfileViewModel.kt:16](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfileViewModel.kt:16) initializes `connectedReads` empty and populates it only after a connection made in that ViewModel. It never loads the persisted connection store.  
   **Minimal trigger:** connect a school section, leave before creating an area plan, then recreate the profile ViewModel and open area setup. `UpdatesRoute.kt:82–84` has no available read and asks the user to connect again, although the connection remains persisted and usable by foreground research. Saved-connection renewal also lacks those reads.  
   **Narrow remedy:** expose persisted authorized reads to profile state and reuse them for setup and renewal.

5. **P2 — Real structured music facts become unreadable placeholders.**  
   [ProfilePresentation.kt:83](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfilePresentation.kt:83) renders only a small set of top-level primitive fields. The actual copied [FluidifyToolHost.kt:154](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/fluidify/app/src/main/java/dev/lelonio/square/bridge/FluidifyToolHost.kt:154) emits nested `track` objects plus `startedAt`, `measuredLocalMs`, `qualified` and provenance. Aggregate records use fields such as `countedPlays` and `importedOnlyPlays`, also absent from the renderer.  
   **Minimal trigger:** store a valid `OBSERVATION` containing an actual history record or aggregate. The profile and its “Fatto alla base” show “Dettagli leggibili non disponibili” instead of the fact.  
   **Evidence distinction:** the provenance screenshot uses synthetic, flattened `title`/`artist` fields, which this renderer supports. It does not demonstrate readability of the production payload.  
   **Narrow remedy:** add domain-aware presentation for the copied typed payloads, preserving unknown duration and coverage.

6. **P2 — Foreground checkpoint resume is implemented internally but unreachable from chat retry.**  
   [ResearchForeground.kt:38](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt:38) resumes only when `resumeJobId` is supplied. Automatic selection creates a task without it. [ChatViewModel.kt:708](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatViewModel.kt:708) regenerates a plain request, leaving `research` null. No production UI consumer of `resumeJobId` was found in the scoped copy.  
   **Minimal trigger:** suspend foreground research after saving partial pages, then tap retry/regenerate. A new job starts instead of continuing the checkpoint. A query exceeding the 64-tranche foreground loop repeatedly starts over.  
   **Narrow remedy:** persist the research-job association with the turn and supply it through an explicit, conversation/model-validated resume action.

7. **P2 — A legitimate profile correction can make subsequent answers fail to commit.**  
   [DurableFoundation.kt:187](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt:187) returns `false` for the entire commit when a generated item’s field already has a user statement. [ResearchRunner.kt:121](C:/Users/casua/AppData/Local/PampAI-qa/review-K1/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchRunner.kt:121) commits the answer checkpoint and optional profile writes together, interpreting that rejection as `COMMIT_FENCED`.  
   **Minimal trigger:** correct a field, then run research with profiling consent whose otherwise valid synthesis includes an observation/inference for that field. The optional write conflicts with the correction and suspends the whole answer/update. This consequence is conditional on model output, not a reproduced runtime result.  
   **Narrow remedy:** retain correction precedence by dropping conflicting optional profile writes while committing a valid answer checkpoint.

All numeric scores below are **provisional and limited to inspected source, retained execution records and static fixtures**. They are not eligible for an overall mean/min approval.

| Criterion | Score | Verification state | Reason/evidence | Problems/severity |
|---|---:|---|---|---|
| C1 product/scope | 4/10 | Source inspected; semantic runtime NV | Quick commands precede research; exact-model research path exists | Overbroad routing and unreachable chat resume, P2 |
| C2 tools/contracts/actions | 6/10 | Scoped interfaces inspected; installed Binder NV | Signature/host checks, typed negotiation and inner-tool allowlist are connected; technical refresh is explicitly gated | UI→contract date mismatch, P1 |
| C3 models/images/routing | 7/10 | Source and retained tests; provider runtime NV | Exact identity reaches the wire guard and budget context; provider lifecycle handles admission/settlement; limited bounds policy fails closed | Generic questions can be diverted into research, P2; full image/helper runtime not established |
| C4 Fluid/UI/continuity/accessibility | 6/10 | All 32 PNGs inspected; limited navigation fixture | Real Fluid routes/components; readable light/dark layouts, large text and bounded wide layouts; legacy memory route retained | Scope restoration and production fact presentation, P2; TalkBack/IME/handoff NV |
| C5 profile/background/budget | 4/10 | Production wiring inspected; archived fixtures | Explicit setup, persisted policies, fixed windows, conservative unknown charges, finite worker and startup reconciliation exist | Scoped exclusions become global; corrections can reject valid synthesis, P2 |
| C6 source/data fidelity | 6/10 | Consumer/producer interface scope only | Account/student/year checks, fragment hashes, continuation checks and unknown imported durations are represented | Real structured facts lose usefulness in presentation, P2; native/history/REST correctness outside scope |
| C7 evidence/readiness | 6/10 | Integrity and retained XML verified | Exact artifact mapping and execution provenance are recoverable | Tests miss production seams; isolation incident qualifies this review; runtime gates NV |

I inspected the actual copied light/dark, empty, deduction/provenance, large-font, compact/wide setup, chat/adaptive, legacy-memory and worker-state PNGs. The worker images inject `AreaScheduleStatus` fixtures in `ProfileScreenshots.kt:105–125`; they do **not** demonstrate real scheduling or successful execution. Production WorkManager factory, application startup, network/battery constraints and one-tranche execution are wired in source. Actual installed scheduler status and OS/Doze timing remain **NV**. The navigation test uses a synthetic editable home, so it is limited evidence for draft continuity.

Integrity and retained evidence:

- **Before and after:** 1,064 files; every copied SHA256 and size matched; final check also found 1,064 unique manifest paths, all inside the snapshot.
- Manifest SHA256: `8B69D9B7E7E8F944E59E025AB827BE55CFC5E3038BA58DFB26B74676EA31EA49`.
- Final APK: **100,571,185 bytes**, SHA256 `40005BC7CDBB539099C24F29A298D7FEE9B8844868FDE8C2F049B6D749C4753C`.
- Snapshot inventory: **32 PNGs, 2 APKs, 245 XML files**.
- Worker final JUnit: core **231 / 39 XML**, feature **91 / 16 XML**, engine **264 / 31 XML**, bridge **25 / 4 XML**; all failures/errors/skips zero. Feature includes **30 screenshot cases**, leaving 61 other cases.
- Root targeted XML: **2 cases**, zero failures/errors/skips. These are repeated worker-state cases, **not 93 feature tests**. Root’s log records targeted tests, feature/app lint and APK assembly; it does not establish a core/engine/bridge rerun.
- Compared all **667 worker input hashes** with the copied final files: only `UpdatesRoute.kt` differs. This independently establishes the file-level delta.
- Source-proof assignment: **27 Fluidify + 28 ClasseViva = 55 methods**, matched to four copied JUnit XMLs without missing/nonpassing assigned cases. ClasseViva’s internal three-entry source/APK hash-and-size map also matches.
- Retained CV build/lint succeeded. Fluidify’s later app build is blocked in AirPods code outside this review scope; its retained core lint summary has three errors. No fresh Fluidify proof APK is established.
- Final PampAI lint XMLs contain no errors: app **39 warnings + 1 hint**; feature **18 warnings + 1 hint**.

I ran only read-only inspection, hashing and evidence parsing. No edits, builds, tests, devices, credentials, live APIs/providers, delegation or commits were performed. Existing runtime NV remains NV. User-authorized advancement remains valid and is separate from runtime verification.
