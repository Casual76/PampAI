Three source-derived findings remain: **two P1 privacy gaps and one P2 compatibility regression**. These are static findings; I did not execute their triggers.

Model/effort: **gpt-6.1-sol, xhigh**. Review: **Connected Knowledge Integration, K3, correction R2/3, R1 specialty**.

Integrity passed **before and after**:

- **1,431 entries, 1,431 unique paths, 1,431 files**; every copied size/SHA matched. No missing, extra, escaping or reparse-point entries.
- Manifest SHA256: `54E0217F0C4DFEDC660B84D8C8C15106CAEF99A782226F7283F07EACA4AC95DB`.
- APK: **104063125 bytes**, SHA256 `CA9BADC28BC25B6C0FF95C7C9716835F562752D1FAC1B379E37E8917196B9FEB`.
- All **684 final input mappings**, **101 artifact mappings** and **24 correction “new” hashes** matched copied files.

Inspection-script compatibility errors and missing-path searches remained confined; none fell back to cwd. No isolation breach, writes, memory, peers, delegates, builds, test runners, devices or live source/provider calls.

1. **P1 — Explicit refusal of private consultation does not override positive keyword matching.**

   Minimal trigger: retain a `cv.grades` profile item, attach a table, and ask: **“Leggi i miei voti nella tabella allegata; usa solo quella, non consultare ClasseViva o il profilo.”**

   The planner matches `leggi i miei voti` and returns `cv.grades`; it has no exclusion for the explicit refusal. With an attachment, the ordinary path then retrieves that profile and inserts it into the model prompt. Consequently, disclosure can occur before any model-selected source tool. Without attachments, an available saved grade scope can instead select research.

   Evidence: [planner matching](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchQuestionPlanner.kt:12), [profile capture](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt:56), [prompt insertion](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:343).

   Narrow correction: explicit source/profile refusals and “only this attachment” constraints must override inferred personal-record intent before retrieval or dispatch. Add an actual engine regression asserting no private prompt material or source traffic for this trigger, while retaining affirmative consultation cases. The archived advice/quotation corpus does not cover this refusal.

2. **P1 — Research results escape the final atomic privacy acceptance boundary.**

   Minimal trigger: complete a natural source question through research, then commit forget/revocation **after `research.answer` returns but before the chat answer is saved**. `answerLocally` persists and publishes the returned text without another captured-policy validation.

   There is also a persistence gap: answer completion and the `research` run classification are separate writes. Process death between them can leave a DONE private answer without the classification used to exclude it from later model history/search.

   Evidence: [research return path](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:279), [separate final writes](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:608), [history classification filter](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/ConversationsRepository.kt:599).

   Narrow correction: carry a captured research acceptance descriptor into final persistence. Renew externally, then validate locally and save answer plus private classification in one Room transaction; publish after successful acceptance. Add regressions for revocation at this handoff and interruption between answer/classification persistence. The existing ordinary-image engine regression and runner synthesis tests stop on opposite sides of this seam.

3. **P2 — Six genuine exported CV actions are misrouted as private reads.**

   The copied producer exports these actions, absent from `knownWrite`:

   `anno_scolastico`, `voto_segna_visto`, `evento_rimuovi`, `obiettivo_rimuovi`, `orario_modifica`, `comunicazione_adesione`.

   Minimal trigger: issue an explicit removal command and dispatch `cv_evento_rimuovi` with valid arguments and operation identity. Even after confirmation, `SourceBridgeCalls` treats it as a read; no permitted `ResearchRead` can match that mutation. Source-owned description is also suppressed.

   Evidence: [consumer allowlist](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceBridgePolicy.kt:13), [actual exported registry](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/classeviva/android/core/core-assistant/src/main/kotlin/dev/antigravity/classevivaexpressive/core/assistant/tools/AllTools.kt:28), [removal action contract](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/classeviva/android/core/core-assistant/src/main/kotlin/dev/antigravity/classevivaexpressive/core/assistant/tools/AppExtraTools.kt:89).

   Narrow correction: reconcile the reviewed mutation list with the genuine producer contract, retaining confirmation, action-off, operation identity and no-replay controls. Extend mounted coverage to these six actions. The current compatibility test uses a reduced synthetic catalog and exercises `agenda_aggiungi_evento`, so it misses this production mismatch.

Scores below are **limited, provisional source/archive scores**, on a 10-point scale.

| Criterion | Score/state | Verification, evidence and severity |
|---|---:|---|
| C1 product/scope | **5 P** | Advice/quotation fixtures improved; explicit refusal still admits private consultation: **P1 finding 1**. |
| C2 contracts/operations | **6 P** | Missing-context, proof-control, pending-job, fragment and receipt boundaries inspected. Genuine CV mutation coverage incomplete: **P2 finding 3**. |
| C3 models/images/routing | **8 P** | Exact identity, lifecycle admission/settlement, bounded research policy and honest unknown tariff behavior inspected. Actual provider semantics/billing remain **NV**. |
| C4 Fluid/IA/continuity | **NV** | Rendering/accessibility not independently assessed in this R1 review. Buffered output is visible in source; synthetic PNGs cannot establish interaction quality. |
| C5 profile/background/budget | **5 P** | Ordinary Room guard, corrections, exclusions, record limits, renewal and finite scheduler safeguards inspected. Research-to-chat acceptance remains open: **P1 finding 2**. |
| C6 source/data fidelity | **8 P** | Limited to copied proof/scope/fragment contracts and consumer acquisition/checkpoint identity. Producer evidence is historical; native, REST and installed-source correctness remain **NV**. |
| C7 checks/evidence/readiness | **8 P** | Manifest/input/artifact consistency and archived execution records independently inspected. New findings lack closing regressions; runtime readiness remains **NV**. |

**Eligible scores for approval mean/minimum: zero. Mean/minimum: NA.** Every numeric score is provisional and excluded.

Assigned counterexample closure:

| Case | Source/fixture closure |
|---|---|
| K2 advice, commuting and quoted instructions | **Closed for the archived corpus**, including paraphrases and explicit positive consultations. Nearby refusal case remains open under finding 1. |
| Interactive scopes versus disabled/older plans | **Closed within inspected source/local fixtures** for revoked replacement accounts, latest saved period and account ambiguity rejection. Installed account transitions remain NV. |
| K2 R1 mounted reads, controls, exclusions, pending and fragments | **Partial.** Missing/generic context, exact saved scope, raw/aggregate exclusions, late-read rejection, forged receipt prefixes, unbound polling and exact turn-bound continuations have source and archived fixture support. Findings 1 and 3 prevent broader closure. |
| K2 R1 returned complete/stream, partial/cache/title and persistence | **Closed for the guarded ordinary path within local evidence; integrated closure remains partial** because research final acceptance bypasses it—finding 2. |
| K2 R1 exact acquisition cache versus canonical evidence | **Closed in source/local fixture.** Full `ResearchRead` keys remain exact; the expiry/profile-renewal regression traverses real Sources/Profile/Runner and checks both refresh variants plus checkpoint binding. |
| Corrections, distinct record limits and retry beyond 64 tranches | **Source/local fixture support retained.** Optional learning skips user corrections; consumed/referenced unions are bounded; retry validates conversation/objective/policy/read association. |
| Writes, cancellation, receipts and no replay | **Preserved for inspected supported actions/readback.** Bounded playlist readback retains original uncertainty and identity; uncertain reservations survive privacy rejection. The six omitted CV actions remain open. |

The principal regression sources are [OrdinaryTurnPrivacyTest](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/OrdinaryTurnPrivacyTest.kt:142) and [ResearchConsumerTest](/C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ResearchConsumerTest.kt:148). These exercise substantial production wiring with external transport/provider substitutes; their passing records do not establish installed Binder or provider behavior.

Archived evidence independently recounted: **272 core/44 JUnit XML**, **104 feature/19 JUnit XML**, zero failures/errors/skips; the privacy suite contains **18 methods**. Final logs record Hilt compilation, lint and assembly success; lint XML contains **0 errors**, with **24/18/39 warnings**. Engine **264** and bridge **25** remain retained K2 records, not fresh K3 runs. Earlier failures and candidate runs were not summed. The **55 unique historical producer cases**—27 Fluidify, 28 CV—match passing archived testcases; broader Fluidify assembly/lint remains historically non-green.

All existing runtime NV stays: overlay/back/IME, active text/image handoff, TalkBack/voice/queued effort, signed Binder/authentication/account drift/cancel/readback, native/Connect/process kill, actual provider billing and OS scheduling/Doze. No phase/device/runtime approval is issued, and no area was enabled.
