R2 review completed for **Connected Knowledge K2, correction R1/3**, using **gpt-6.1-sol, xhigh reasoning**. **Source/fixture closure is incomplete:** one high-severity privacy-routing finding and four medium-severity findings remain. No runtime or overall phase approval is granted.

All findings below are **source inference**, supported by inspected production code. I did not execute application tests or reproduce runtime behavior.

1. **P1 — Generic guitar advice can trigger private school consultation.**  
   With an authorized lessons connection, “Come posso migliorare le mie lezioni di chitarra?” matches `mie lezioni`, produces `cv.lessons`, and enters foreground research. “Come vado a scuola in bicicletta?” similarly selects grades through the broad `come…vado…scuola` rule. These requests do not ask to consult school records.

   Locations: [ResearchQuestionPlanner.kt:12](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchQuestionPlanner.kt:12>), [ResearchForeground.kt:29](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt:29>), [AssistantEngine.kt:270](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:270>).

   **Consequence:** unrelated private records can be acquired and supplied to synthesis during generic advice. The retained negative cases cover different wording and do not close these examples.  
   **Narrow correction/regression:** distinguish school-record consultation from generic lessons/commuting advice; cover these examples across planner, foreground selection and profile retrieval, asserting no private transport or evidence use.

2. **P2 — An autonomous plan suppresses independently saved interactive connections.**  
   `authorizedReads()` excludes every interactive connection whose *area* appears in any saved plan. Minimal trigger: retain a disabled plan for account A’s `history_records`, revoke A, then connect account B’s same section. B remains persisted and enabled, but its read is discarded; A is subsequently removed by the enabled-grant filter.

   Location: [ResearchForeground.kt:15](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt:15>).

   **Consequence:** the UI can show B as connected while natural personal questions cannot consult it. With an older enabled plan, its period also takes precedence over a newer interactive period.  
   **Narrow correction/regression:** resolve foreground scopes independently of autonomous area configuration, using exact identities and explicit ambiguity handling. Cover replacement accounts and periods with an existing disabled plan.

3. **P2 — Setup hides music periods produced by the corrected form.**  
   The form correctly creates string epochs. `scopeLabel()` then calls `JsonElement.toString().toLongOrNull()`, which encounters quoted JSON strings and returns null.

   Locations: [ProfilePresentation.kt:129](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfilePresentation.kt:129>), [UpdatesRoute.kt:121](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/UpdatesRoute.kt:121>).

   **Consequence:** setup displays “solo l’ambito selezionato” instead of the dates. Different saved periods can have indistinguishable selection labels.  
   **Narrow correction/regression:** parse primitive content and verify the displayed interval using a real `SourceDraft.read()` result.

4. **P2 — Reconciled Spotify observations can falsely claim a local threshold was reached.**  
   The producer sets `qualified=true` when an import matches a local record, independently of local measured duration. The consumer interprets `qualified` as a local-threshold result whenever `measuredLocalMs` is numeric.

   Locations: [ListeningRepository.kt:99](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/fluidify/app/src/main/java/dev/lelonio/square/data/ListeningRepository.kt:99>), [FluidifyToolHost.kt:154](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/fluidify/app/src/main/java/dev/lelonio/square/bridge/FluidifyToolHost.kt:154>), [MusicFactPresentation.kt:29](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/MusicFactPresentation.kt:29>).

   **Minimal trigger:** a short local measurement reconciled with a Spotify observation.  
   **Consequence:** “Soglia locale di ascolto raggiunta” asserts more than the producer establishes.  
   **Narrow correction/regression:** use wording matching the combined qualification semantics, or supply a separate local-qualification field. Cover a reconciled record below the local threshold.

5. **P2 — Provenance can label local-formatted timestamps as UTC.**  
   Fluidify evidence receives `timeZone="UTC"`, while `dateLabel()` formats instants with the device’s system zone. The detail sheet presents those formatted dates beside the evidence zone.

   Locations: [SourcePages.kt:98](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/SourcePages.kt:98>), [ProfilePresentation.kt:48](</C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfilePresentation.kt:48>).

   **Minimal trigger:** inspect production Fluidify evidence on a Europe/Rome device.  
   **Consequence:** displayed interval times and their zone label disagree, including a seasonal offset. The inspected provenance fixture uses Europe/Rome explicitly and does not expose this production mismatch.  
   **Narrow correction/regression:** format dates in the labelled zone, or label the actual display zone; cover winter and summer intervals.

All numeric scores are **provisional source/fixture scores out of 10**, excluded from approval means and minima.

| Criterion | Score/state | Evidence and assessment |
|---|---|---|
| C1 product/scope | **5, provisional; runtime NV** | Positive-intent gate and fixed-model research exist. Finding 1 prevents privacy-routing closure; finding 2 affects natural source consultations. |
| C2 contracts/operations | **8, provisional; runtime NV** | Inspected signed host/authority allowlist, typed negotiation, inner-tool schemas, proof binding, receipt epochs and before/after-read exclusions. No additional correction-specific defect established. Installed Binder/auth/cancel/readback remains NV. |
| C3 models/images/routing | **8, provisional; runtime NV** | Research preserves exact identity; budget lifecycle checks candidates, reserves attempts, audits dispatch and settles unknown money honestly. Unsupported bounded execution remains denied. Real provider/image semantics and billing remain NV. |
| C4 Fluid/IA/continuity | **6, provisional; runtime NV** | Actual Fluid routes, grouped lists, modal portals, legacy memory entry, correction/forget controls and motion-policy integration inspected. Finding 3 impairs scope review. PNGs support limited readability only. |
| C5 profile/background/budget | **7, provisional; runtime NV** | Persisted connections, transactional commit fences, exclusions, correction precedence, explicit setup/windows and finite worker paths inspected. Finding 2 prevents full connection independence. Android lifecycle/scheduler/Doze remains NV. |
| C6 source/data fidelity | **6, provisional; runtime NV** | Nested tracks/counts/aggregates are readable; unknown imported duration remains distinct from zero. Findings 4–5 prevent semantic/provenance closure. Native/source correctness remains unapproved. |
| C7 checks/evidence/readiness | **8, provisional; readiness NV** | Integrity, input map and artifact identities reconcile; retained passing and failed execution evidence is distinguishable. Fixtures do not establish device readiness. |

**Eligible overall mean/minimum: none.** No criterion receives runtime approval.

| Prior obligation | Source/fixture closure |
|---|---|
| 1. String epoch form → consumer policy | **Supported.** Actual form/policy inspected; retained boundary regression reaches transport negotiation. |
| 2. Scoped exclusions and unrelated work | **Supported within inspected scope.** Acquisition, retrieval, commit and late-result fences inspected; retained scoped cases support separation. |
| 3. Generic versus private intent | **Open — finding 1.** |
| 4. Persisted interactive connections | **Partial.** Persistence/revocation supported; independence from existing plans remains open through finding 2. |
| 5. Real-shaped music presentation | **Partial — findings 3–5.** Readability and unknown-duration handling improve, but semantics remain incorrect in identified cases. |
| 6. Durable retry and continuation | **Supported within the stated seam.** ViewModel retry flag, engine admission, repository association and runner ownership/objective/policy/scope validation inspected. Retained 70-page case covers Room/repository/foreground/runner, not full Android lifecycle. |
| 7. Correction versus optional learning | **Supported for a pre-existing correction.** Commit skips conflicting learning while preserving the answer checkpoint; late forget/revoke remains fenced. |
| 8. Canonical content/acquisition binding | **Supported within inspected scope.** Canonical normalization and exact checkpoint binding inspected; retained collision/scope cases reviewed. |
| 9. Distinct record limits | **Supported within inspected scope.** Consumed/referenced union and synthesis/profile union count distinct IDs; retained overlap/reuse cases reviewed. |

Executed read-only checks comprised full pre/post hashing and inventory confinement, source inspection, input/artifact-map reconciliation, retained XML aggregation and visual inspection of **7 of 33 PNGs**: music observation, provenance, sources, compact setup, wide 2× setup, profile 2× and uncertain worker state. The inspected renders are readable within their captured viewport; the 2× profile subtitle truncates. They establish neither scrolling interaction nor accessibility behavior.

Retained evidence independently reconciles to **249 core, 98 feature, 264 engine-AI and 25 bridge tests**, with zero failures/errors/skips across **96 XML files**. The six targeted music cases are repeats and were not added. Earlier archives retain **5 core failures** and **1 feature failure**. Build logs distinguish successful full-4/final-5/final-6 runs from failed earlier runs. These are archived executions, **not tests I ran**.

Integrity passed **before and after**:

| Check | Result |
|---|---|
| Manifest entries / unique files / filesystem inventory | **1,192 / 1,192 / 1,192** |
| Missing, extra, mismatched or out-of-root files | **0** |
| Reparse points | **0** |
| Final input map | **680/680 match** |
| Artifact map | **103/103 match** |
| Final APK | **102,150,985 bytes; hash matches** |

Manifest SHA256: `159A316C0CA6E5848C871127B2BB58499AB8C86FD1883C1D8C421805049C9683`  
APK SHA256: `FC8FE3144E8450E18B19391D9D0E2AAAF47B25AA7683C6A2759682398E58F5E1`

No isolation breach occurred. No memory, peer reviews, mutable project sources, edits, builds, test execution, devices, providers, credentials or delegates were used. All existing runtime NV remains, including overlay/back/IME, active image handoff, TalkBack/voice, installed signed-source behavior, native/Connect/process-kill/authentication, provider accounting and OS scheduling.
