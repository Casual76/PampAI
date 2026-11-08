Two **P2 source-level findings remain**. The principal research-acceptance, stale-memory-promotion and source-classification corrections are supported by the copied production code and archived fixtures. This is **not phase or device approval**.

Reviewer: **gpt-6.1-sol, xhigh**. Phase: **Connected Knowledge Integration — K4 — FINAL correction R3/3**, separate from I5/R4 and Source2 S4/R3.

**Integrity and evidence**

Both corrected pre-review and final post-review checks independently verified **1,186 unique files**, every size/SHA-256, path confinement and exact inventory. No missing, extra or mismatched files; no reparse points.

- Manifest SHA-256: `28F26D4CDC38D6186F7B222A3021647EF7C7F83DF60EC8D7267EC1B442F74C9B`
- APK: **103,628,809 bytes**, SHA-256 `2A9CE8896BBD6F330E5C14D1A7B90031CF6859D5509DEB2B55152F3186A373B8`

The initial PowerShell verifier failed and its result was discarded. Null-path operations were rejected; no working-tree fallback or out-of-scope read occurred. The subsequent Python checks completed successfully.

I independently recounted the archived XML:

| Evidence | Cases | Failures/errors/skips | Provenance |
|---|---:|---|---|
| Core | 303 | 0/0/0 | Fresh archived full-5 |
| Feature | 104 | 0/0/0 | Fresh archived full-5 |
| Engine | 264 | 0/0/0 | Retained K2 |
| Bridge | 25 | 0/0/0 | Retained K2 |

The case map matches every XML testcase. All **688 input-map entries**, **104 artifact-map entries**, and **18 delta new hashes** match the copied files. There are **234 historical producer entries** and **34 synthetic PNGs**.

The UTF-16 full-5 log records actual Hilt compilation, assembly and `BUILD SUCCESSFUL in 5m 54s`; lint XML contains **0 errors**, with **24/18/39 warnings** and feature/app each having one hint. Full-1/2 compilation failures and full-3/4 test failures remain separate. These are **archived executions**, not tests I ran. See [copied validation evidence](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/docs/validation-K4/results.json).

**Findings**

1. **P2 — Ordinary private answers publish Done before Room commits.**  
   In [AssistantEngine.kt:437](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:437), cache handling and `live.value = Done` remain inside `turnPrivacy.commit`. The Unconfined forwarder at line 185 publishes that state synchronously, while the enclosing `db.withTransaction` has not returned.

   **Minimal trigger:** an ordinary private/image turn completes its SQL statements, publishes Done, then transaction completion fails. Room can roll back answer/classification after the private answer has already reached public runtime state.

   **Narrow fix:** keep persistence inside the transaction; publish Done and update the replay cache only after `commit` returns successfully. Add an Engine regression injecting failure at transaction completion and assert that no Done/private text or new cache entry escapes.

   **Verification:** production-source inference; not dynamically reproduced. The corrected dedicated-research path already publishes after `acceptResearch` returns. Its archived SQLite-trigger regression tests failure between answer/classification inserts, not this ordinary-chat transaction-completion boundary.

2. **P2 — Ricorda’s conditional safety confirmation is omitted from queued-confirmation budgeting.**  
   [RicordaTool:34](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/aria/AriaTools.kt:34) can wait in `confirmSafety`, but declares neither `awaitsConfirmation` nor `needsConfirmation`; its ARIA group also leaves the default false. Consequently, [AiOrchestrator.kt:1084](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/engine/engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiOrchestrator.kt:1084) excludes it from the confirmation count and applies the ordinary tool timeout.

   **Minimal trigger:** two distinct Ricorda calls in one private APP round, with sufficient overall budget. The first confirmation is approved around 55 seconds; the second would be approved around 100 seconds, within its own 60-second gate window. Both tool timers began together, so the second is cancelled around the APP tool limit of 90 seconds.

   **Narrow fix:** declare `override val awaitsConfirmation = true` on Ricorda. This preserves conditional safety prompting. Add a queued two-Ricorda regression through the real orchestrator/gate, including cancellation and no unintended insert.

   **Verification:** source-derived timing counterexample, not an observed device failure. Existing memory fixtures resolve single confirmations promptly.

**C1–C7 assessment**

Scores are **/10, provisional source/fixture assessments**, excluded from every mean/minimum.

| Criterion | Score/state | Evidence, reason and severity |
|---|---|---|
| C1 product/scope | **9 provisional** | Refusal precedes inference; supplied-only input removes memory/tool exposure. Exact refusal, affirmative consultation and generic negatives have production wiring plus archived coverage. No additional finding. |
| C2 contracts/operations | **8.5 provisional** | App-owned normalization governs dispatch, ledger/staging and retained traces; confirmation floors and operation identity remain. **P2 F2** affects Ricorda waiting. Installed transport NV. |
| C3 models/images/routing | **8.5 provisional** | Exact research model/policy binding and per-wire budget lifecycle inspected. Bounds remain conservative. Actual provider semantics/billing and runtime image behavior NV. |
| C4 Fluid/continuity | **NV** | SourcesScreen uses actual Fluid components and accurately explains narrowed reads. No independent visual/interaction validation sufficient to score this criterion. **F2** is a static interaction finding. |
| C5 profile/background/budget | **8 provisional** | Original research descriptors and post-confirmation memory transactions are present; default-off finite scheduler/worker guards remain. **P2 F1** leaves ordinary publication ordering incomplete. OS execution NV. |
| C6 source/data fidelity | **8.5 provisional** | Exact acquisition, canonical evidence separation, fragment bindings and provenance inspected. Producer evidence is historical; native/REST/runtime fidelity remains NV. |
| C7 evidence/readiness | **9 provisional** | Independent integrity, XML, logs and map checks agree. Fixtures do not cover the two findings above. No readiness or phase pass. |

**Prior-case closure**

1. **Explicit refusal — closed within source/fixture scope.** The exact supplied counterexample is exercised through ordinary text/image Engine prompts and mounted dispatch, alongside source-only/profile-only distinctions. See [OrdinaryTurnPrivacyTest.kt:142](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/OrdinaryTurnPrivacyTest.kt:142).

2. **Original research acceptance and atomic classification — closed within source/fixture scope.** Capture precedes synthesis, `_acceptance` survives checkpoint/DONE reconstruction, and temporary research retains its original descriptor. Local validation follows external renewal; answer/classification commit together; Engine publication follows successful persistence. Forget, revoke/reconnect, remote drift, correction, stable renewal and rollback have archived regressions. See [ResearchRunner.kt:154](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchRunner.kt:154), [ConversationsRepository.kt:451](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/ConversationsRepository.kt:451), and [ResearchAcceptanceTest.kt:150](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ResearchAcceptanceTest.kt:150).

3. **Retrieved-profile → Ricorda safety — stale-insert obligation closed; queued waiting incomplete under F2.** Captured provenance triggers safety with Actions off. Post-confirmation renewal and the local check/insert share the Room boundary. Forget/revoke/correction, cancellation, temporary exclusion, both overloads and approved independent-memory survival are covered. The external-reader trace safeguard remains. See [MemoryClassificationIntegrationTest.kt:217](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/MemoryClassificationIntegrationTest.kt:217).

4. **Trusted read/write classification — closed conditionally.** [SourceBridgePolicy.kt:31](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceBridgePolicy.kt:31) supplies normalization and Engine ownership decisions. The admitted misleading-action read fixture reaches the provider before revocation and then leaves no read receipt/trace. Current copied producers label reads correctly; misleading metadata remains a conditional-contract case.

5. **Six CV mutations — closed within copied-contract scope.** I inspected the genuine [AppExtraTools.kt:24](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/classeviva/android/core/core-assistant/src/main/kotlin/dev/antigravity/classevivaexpressive/core/assistant/tools/AppExtraTools.kt:24) and [BoardExtraTools.kt:19](C:/Users/casua/AppData/Local/PampAI-qa/review-K4/snapshot/classeviva/android/core/core-assistant/src/main/kotlin/dev/antigravity/classevivaexpressive/core/assistant/tools/BoardExtraTools.kt:19), including their confirmation declarations. Year mutation requires nonblank `seleziona`; inspection remains conservatively read-gated. Installed mutation effects are NV.

6. **Compound personal question/general definition — closed for the specified cases.** The Fluidify question selects the exact saved read; the ClasseViva definition remains ordinary. This proves saved-scope selection, not arbitrary temporal interpretation of “ieri.”

7. **Ordinary streaming/UI compatibility — partially closed.** Public partial saving is restored; private-capable turns remain buffered; a captured public turn cannot upgrade after a new connection. Sources copy is accurate. **F1** remains at final ordinary publication. Other signed domains retain generic transport, while known CV/Fluidify packages cannot evade protection by changing advertised domain.

Retained source/archival support also remains for exact acquisition versus canonical evidence, exclusions/correction precedence, distinct-record union limits, persisted retry beyond 64 tranches, model/lifecycle accounting and finite silent WorkManager behavior. The 70-page retry fixture reopens Room; it does not prove process-kill recovery.

No memory, peers, mutable originals, credentials/config stores, subagents, writes, builds/tests, devices or live providers were used. All older overlay/back/IME/handoff/voice/TalkBack/queued-effort, installed Binder/auth/account-drift/cancel/readback, native/Connect/process-kill, provider billing/semantics and OS/Doze gates remain **NV**. No stopped driver was bypassed, area enabled or runtime waiver granted.
