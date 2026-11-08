**Two source-level findings remain: P1 refusal recognition and P2 legacy-memory dispatch.** The exact prior refusal case is fixed, but the broader refusal obligation is incomplete.

Reviewer: **gpt-6.1-sol, xhigh**. Phase: **Connected Knowledge Integration, K4, final correction R3/3**, separate from I5/R4 and Source2 S4/R3.

I performed read-only inspection and integrity calculations. I executed no builds/tests, devices, providers or source APIs; used no memory, peers or delegates; and wrote no files.

Integrity passed **before and after**:

- **1,186 entries, 1,186 unique paths, 1,186 inventory files**; every size/SHA-256 matched. No extras, missing files, confinement failures or reparse points.
- Manifest SHA-256: `28F26D4CDC38D6186F7B222A3021647EF7C7F83DF60EC8D7267EC1B442F74C9B`.
- Final APK: **103,628,809 bytes**, SHA-256 `2A9CE8896BBD6F330E5C14D1A7B90031CF6859D5509DEB2B55152F3186A373B8`.
- All reads remained within the authorized copies/guidance. No isolation escape occurred.

1. **P1 — A clear “only supplied data” restriction can still initiate private research.**

   Concrete trigger, with an enabled saved CV grades scope and no attachment:

   ```text
   Leggi i miei voti qui sotto; usa solo questi dati.
   Matematica: 7; Italiano: 8.
   ```

   `onlyProvidedContent` requires one of its listed supplied-content nouns. Neither `dati` nor `qui sotto` qualifies. No refusal is recorded, while `Leggi i miei voti` positively selects `cv.grades`. Consequently, `ResearchForeground.select` can start source research despite the explicit restriction.

   This is a deterministic **source inference**, not an executed reproduction. Evidence: [ResearchQuestionPlanner.kt:7](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchQuestionPlanner.kt:7), [ResearchForeground.kt:24](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt:24).

   Narrow fix: recognize deictic supplied-data restrictions before positive intent, and carry that restriction through both research selection and ordinary tool exposure. Add this exact pasted-data regression alongside affirmative-source and generic-negative controls.

2. **P2 — Recognized memory refusal does not guard the memory-reading tool.**

   Concrete trigger:

   ```text
   Spiegami la media aritmetica; non usare la memoria.
   ```

   The refusal suppresses the prompt’s memory block. However, the registry remains available because only `onlyProvidedContent` empties it. If the model emits `memoria_elenco({})`, that tool unconditionally calls `ctx.memory.list()`. Its output can then enter the ordinary answer/cache/title path without becoming a captured private dependency.

   This finding requires a prohibited model tool call; I claim no observed live model behavior. Evidence: [AssistantEngine.kt:359](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:359), [AriaTools.kt:103](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/aria/AriaTools.kt:103).

   Narrow fix: enforce the captured memory restriction at dispatch, including both tool overloads. Regression: seed a sentinel memory, issue the refusal, return `memoria_elenco` from the fixture provider, and verify no memory content reaches subsequent provider input, answer, cache or title.

All following scores are **limited, provisional source/fixture scores, excluded from every mean and minimum**. They are not phase or device passes.

| Criterion | Score | Verification, evidence and limitation | Severity |
|---|---:|---|---|
| C1 product/scope | 6/10 | Positive personal routing and the supplied generic-negative corpus are supported; explicit restrictions remain incomplete under finding 1. | P1 |
| C2 tools/contracts/operations | 7/10 | App-owned source classification consistently governs normalized catalogs, dispatch, staging and ledger hooks. Known mutations retain controls. Memory refusal lacks a dispatch boundary. | P2 |
| C3 models/images/routing | 8/10 | Source inspection supports fixed research model/effort, admission/settlement hooks and conservative unsupported-bounds behavior. Provider semantics/billing remain NV. | No additional defect established |
| C4 Fluid/IA/continuity | 8/10 | Actual Fluid profile/source/setup/legacy-memory routes are wired. Nine copied PNGs inspected, including compatibility copy, provenance, music duration/timezone, 2× type, wide setup and queue. Navigation evidence uses a synthetic home/child boundary. | Runtime limits |
| C5 profile/background/budget | 8/10 | Original-descriptor acceptance and post-confirmation memory insertion use local Room fences. Default-off setup, finite worker execution and conservative budget gates remain. Actual OS scheduling is NV. | Runtime limits |
| C6 source/data fidelity | 8/10 | Saved identities/scopes, canonical evidence versus acquisition controls, fragment continuation and unknown-duration presentation are supported. Producer evidence remains historical. | Runtime limits |
| C7 checks/evidence/readiness | 9/10 | Inventory, maps, XML and logs reconcile; fresh, failed and retained runs are distinguished. Test fixtures substitute providers/transports/platform boundaries. | No integrity defect; readiness NV |

The archived evidence independently reconciles as follows:

- Final XML/case maps: **303 core / 47 XML**, **104 feature / 19 XML**, zero failures/errors/skips.
- **264 engine / 31 XML** and **25 bridge / 4 XML** remain retained K2 executions, not fresh K4 tests.
- Lint XML: **0 errors**, **24/18/39 warnings** for core/feature/app; feature/app each also contain one hint.
- `full-5.log` records actual Hilt Java compilation, debug assembly and success in **5m54s**. I rehashed all **688 copied declared inputs**, all **104 artifact-map entries**, and the **18 final source/test deltas** successfully.
- Earlier compile failures and the separate **full-3: 303 cases/3 failures** and **full-4: 303 cases/1 failure** remain separate.
- The **234 historical producer entries** are present. Their assigned proof map reconciles to **27 Fluidify + 28 CV = 55 unique archived passing cases**. Historical Fluidify assembly/lint remains non-green; this is neither a fresh failure reproduction nor a fresh producer APK claim.

Evidence: [final execution log](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/docs/validation-K4/full-5.log:197), [results](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/docs/validation-K4/results.json).

Prior obligations close as follows:

1. **Refusal:** the exact attached-table counterexample is closed in source and archived fixtures. Profile-only refusal still permits authorized source consultation without stored-profile retrieval/learning. **General closure remains open** because of findings 1–2.
2. **Research handoff:** locally closed. The descriptor originates before synthesis, persists with the checkpoint and survives resumed DONE. Acceptance validates original epoch/grants/profile fingerprints; answer DONE and `research` classification commit together. UI publication follows successful acceptance; research bypasses replay cache/title. Archived rollback, drift, temporary and resumed cases support this.
3. **Profile promotion:** locally closed for the assigned race. Actual captured provenance triggers safety confirmation with Actions off. Forget/revoke/correction after confirmation is checked before insertion, and insertion shares the local transaction. Approved independent legacy memory may survive later profile forget.
4. **Metadata classification:** locally closed, **conditional-contract qualification retained**. Mislabelled admitted reads use the read path and do not become write receipts. The copied real producers already label these reads correctly; the contrary metadata is fixture-injected.
5. **Six CV mutations:** restored in the actual allowlist and genuine copied contract: `anno_scolastico`, `voto_segna_visto`, `evento_rimuovi`, `obiettivo_rimuovi`, `orario_modifica`, `comunicazione_adesione`. Only nonblank `seleziona` takes year mutation dispatch; inspection remains read-gated. Confirmation, action-off, identity and no-replay controls remain.
6. **Compound question/saved semantics:** locally closed for the assigned pair. “Che cosa ho ascoltato ieri su Fluidify?” selects saved reads; the generic CV definition stays ordinary. Saved account/student/year and period arguments are retained rather than inferred anew. This does not establish that a saved period covers “ieri”.
7. **Streaming/UI:** locally closed for the assigned paths. Source-free turns regain incremental publication/partial saving; potentially private turns buffer from capture and cannot upgrade after a later connection. Revised compatibility copy is visible in the copied PNG. Queue generations, one-shot draft claims and ownership checks remain present.

Earlier canonical-acquisition, scoped-exclusion, correction-precedence, distinct-record union, durable retry, exact-model/accounting and silent-worker safeguards remain supported by the copied implementation/evidence. Existing unclassified historical answers retain their explicitly documented provenance limitation.

**All runtime NV remains:** overlay/back/IME, active text-image handoff, voice/TalkBack/queued effort, installed signed Binder/auth/account drift/cancel/readback, native/Connect/process-kill/upstream permissions, provider billing/semantics and OS/Doze scheduling. No stopped driver was bypassed, no area was enabled, and no runtime waiver or overall static phase/device approval is granted.
