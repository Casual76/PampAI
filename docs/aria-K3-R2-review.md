**K3 correction R2/3, R2 review — gpt-6.1-sol, xhigh.** Two high-severity privacy seams remain, plus an intent regression and a conditional persistence gap. Findings below are source-derived counterexamples, not executed failures.

Pre/post integrity passed independently:

- **1,431 unique entries/files**, every SHA256 and size matched; zero extras, duplicates or reparse points.
- Manifest SHA256: `54E0217F0C4DFEDC660B84D8C8C15106CAEF99A782226F7283F07EACA4AC95DB`.
- APK: **104,063,125 bytes**, SHA256 `CA9BADC28BC25B6C0FF95C7C9716835F562752D1FAC1B379E37E8917196B9FEB`.
- All 684 final input-map entries and 101 artifact-map entries matched their copies.

All successful reads stayed within the snapshot/manifest/info and expressly permitted guidance. Missing-path commands and one rejected null-`LiteralPath` command failed closed; none fell back to cwd. No memory, peers, delegates, writes, builds, tests, devices, credentials or live providers were used.

1. **P1 — Dedicated research publication lacks the final atomic privacy fence.**

   The research branch returns through [`answerLocally`](C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:279), before ordinary `TurnPrivacy` is captured. That publisher separately completes the message, records its `research` classification and publishes `Done`, without revalidating the research result. See [publication](C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:611).

   **Minimal trigger:** complete a text-only personal-source lookup, then forget/revoke after `research.answer` returns but before conversation persistence. The returned text can still be saved and displayed. A crash between message completion and run classification can additionally leave an unclassified `DONE` answer eligible for later history replay.

   **Correction/regression:** carry the research acceptance dependencies into final publication; validate local versions and persist answer plus classification in one Room transaction, with source renewal outside it. Add barriers covering both revocation before publication and interrupted classification. Existing runner tests cover synthesis/commit fences, not this final handoff.

2. **P1 — Retrieved profile content bypasses the existing `ricorda` safety confirmation.**

   Targeted profile content enters the prompt before tool-context creation. [`RicordaTool`](C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/aria/AriaTools.kt:48) requests safety confirmation only when `ctx.traces` contains an external reader. Profile retrieval creates no such trace.

   **Minimal trigger:** use the existing ordinary image/profile path with a matching stored observation; make the provider fixture return `ricorda` as its first tool call. With no preceding external tool trace, the value is saved without confirmation. Subsequent profile forget/revocation cannot remove that independent legacy-memory row, which [`MemoryRepository.promptBlock`](C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/MemoryRepository.kt:56) supplies to future unrelated conversations.

   This does **not** establish that a live provider follows injected instructions; it establishes a missing deterministic safeguard when that tool call occurs.

   **Correction/regression:** include retrieved-profile provenance in the memory safety decision and fence pending promotion. Extend the real-engine/Room fixture to assert confirmation before saving, including with “Azioni” disabled, and rejection after forget/revoke. The archived safety test covers a preceding `cerca_web` trace instead.

3. **P2, conditional — Catalog action metadata still controls immediate persistence of private reads.**

   Dispatch distinguishes known writes, but [`RemoteTool.isAction`](C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/engine/engine-ai-bridge/src/main/kotlin/dev/antigravity/fluidengine/ai/bridge/RemoteTools.kt:85) remains catalog-derived. [`AssistantEngine.onToolOutput`](C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:408) uses that flag to settle immediately through `OperationLedger`, bypassing staged-read acceptance; invalidation also preserves action-labelled traces.

   **Minimal trigger:** inconsistent/stale mounted metadata labels a supported read `action=true`, while the verified proof channel successfully serves that read; revoke after its output but before final completion. Its output can already exist as a retained “write” receipt.

   The copied producers currently declare these reads as reads. This is therefore a **conditional contract finding**, not demonstrated current producer behavior. The mislabelled-read fixture only checks denial without a turn context.

   **Correction/regression:** use one app-owned read/write classification consistently for dispatch, staging and retained traces. Test an admitted supported read with misleading metadata while retaining the genuine uncertain-write/no-replay regression.

4. **P2 — Common explicit personal-record questions are rejected by the planner.**

   **Minimal trigger:** with an exact saved music scope, ask **“Che cosa ho ascoltato ieri su Fluidify?”**. The anchored [`factualQuestion` patterns](C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchQuestionPlanner.kt:17) accept `che` or `cosa`, but cannot consume compound `che cosa`. No other admission branch matches. Research selection returns null, and mounted reads subsequently require personal-source intent.

   **Correction/regression:** support the compound interrogative while preserving the generic-advice, quotation and commuting negatives. Add the exact positive question alongside “Che cosa sono i voti in ClasseViva?” as a negative control.

I independently inspected seven copied PNGs: music-period setup, provenance, music observation, profile at 2× font, wide setup at 2× font, dark chat and compact large-type chat. They show actual Fluid composition and readable corrected period/provenance labels. They establish **synthetic rendering only**. The navigation fixture uses a synthetic editable home and placeholder child routes; it does not establish live chat/overlay continuity.

Two disclosed product limits remain material:

- Buffering suppresses incremental text and tool progress for **all ordinary turns**, including source-free chat.
- Legacy readers deliberately deny access, while [SourcesScreen](C:/Users/casua/AppData/Local/PampAI-qa/review-K3/snapshot/pampai/feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/SourcesScreen.kt:21) still says previous tools remain available. That copy overstates read compatibility. Classified private replies also require fresh consultation; older unclassified history remains outside reliable dependency fencing.

Scores use **0–10**, with **P = provisional, limited source/fixture assessment**. S = source inspection; A = archived execution; V = my read-only verification; I = inspected PNGs.

| Criterion | Score/state | Verification, evidence and limitation | Severity |
|---|---:|---|---|
| C1 product/scope | **7 P** | S+A: supplied advice/quote/commuting negatives corrected; explicit positive coverage remains incomplete, finding 4. | P2 |
| C2 contracts/operations | **7 P** | S+A: mounted scope/proof/control/pending/receipt gates and known-write confirmations present; conditional persistence gap in finding 3; legacy-read compatibility narrowed. | Conditional P2 |
| C3 models/images/routing | **8 P** | S+A: exact identity, audit/lifecycle hooks and conservative unsupported bounds inspected. Provider semantics/billing remain NV. | None newly established |
| C4 Fluid/continuity | **7 P** | S+A+I: real Fluid profile/setup/navigation, correction/forget/source controls; rendering and synthetic navigation coverage only. Buffering and legacy copy limits above. | P2 UX |
| C5 profile/background/budget | **5 P** | S+A: saved connections independent of autonomous plans, local transactional ordinary fences, finite worker/startup wiring. Findings 1–2 prevent privacy closure. | P1 |
| C6 data fidelity | **8 P** | S+A+I: string epochs, labelled timezone conversion/fallback, measured versus imported duration and reconciled counting corrected. Producer evidence is historical K2. | None newly established |
| C7 checks/readiness | **8 P** | V+A: integrity/maps/XML/lint/logs consistent; new counterexamples lack closing fixtures. No runtime readiness established. | Coverage gaps |

**All numeric scores are provisional and excluded from approval mean/minimum. The eligible set is empty; mean/minimum are NA.**

| Assigned counterexample | Source/fixture closure |
|---|---|
| Generic advice, quotations, guitar lessons and commuting | Supplied K2 negatives closed by source and archived fixture; positive-intent coverage remains partial, finding 4. |
| Interactive replacement account and period versus disabled/older autonomous plans | Closed at source/fixture level; interactive reads take precedence. Multiple enabled identities fail ambiguity checks. |
| Actual string-epoch setup labels | Closed: actual form → primitive numeric content → distinct labelled intervals; archived test and inspected PNG. |
| Reconciled music meaning | Closed: combined local-or-Spotify qualification; measured time and unknown imported duration remain distinct. |
| UTC, winter/summer, named-zone conversion and unknown-zone fallback | Closed at source/fixture/render level. |
| Ordinary mounted reads, controls, aliases, pending jobs, fragments and bounded receipt readback | Covered by source and archived mounted fixtures, including sequential fragment consumption; conditional finding 3 remains. |
| Ordinary delayed output, persistence, history and write interactions | Baseline ordinary complete/stream and image/profile fences covered; broader closure remains incomplete through findings 1–2. |
| Canonical sharing versus exact acquisition cache | Closed at source/fixture level: full-read cache key and real profile-renewal/form/runner regression cover both technical-refresh variants. |

I parsed the archived final XML: **272 core / 104 feature**, zero failures/errors/skips. Privacy-7 records Hilt compilation, debug assembly and successful build; copied lint XML contains **0 errors**, with 24/18/39 warnings and the declared hints. Engine **264** and bridge **25** remain retained K2 evidence. Earlier candidates, privacy-4 compilation failure and privacy-5’s two failures were inspected separately and not added to final totals. Producer archives retain the broader Fluidify AirPods/build and core-lint failures; no fresh source APK/runtime claim follows.

All existing runtime gates remain **NV**: overlay/back/IME, active text+image handoff, TalkBack/voice/queued effort, installed signed Binder/authentication/account drift/cancel/readback, native/Connect/process kill, provider semantics/billing and OS scheduling/Doze. **No phase or device approval is issued, and no autonomous area was enabled.**
