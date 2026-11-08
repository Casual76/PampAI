R1 review: **the K2 correction is not closed within source/fixture scope**. I found two privacy gaps and one acquisition-cache defect. No runtime or phase approval is granted.

Reviewer: **gpt-6.1-sol, xhigh**. Phase: **Connected Knowledge Integration**, snapshot **K2**, correction **R1/3**. All findings below are **source inference from copied production code**, not reproduced device/provider outcomes.

**R1-F1 — P1: Existing federated reads bypass the new privacy fences.**

`ResearchSources` enforces exclusions, but ordinary chat still mounts the existing source tools. Their dispatch path does not consult saved research scopes, exclusions or the positive personal/source-intent decision.

Evidence:

- [RemoteCatalogs.kt:110](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/bridge/RemoteCatalogs.kt:110) mounts the gated catalog through `SourceBridgeCalls`.
- [RegistryBuilder.kt:156](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/RegistryBuilder.kt:156) wraps those tools with tracing; its CV routing vocabulary still includes generic school terms at line 195.
- [SourceBridgePolicy.kt:28](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceBridgePolicy.kt:28) delegates CV reads directly. Fluidify reads receive an account check, without the new exclusion/scope checks.

Minimal trigger: forget `fluidify.history_records`, then let an ordinary-chat tool call invoke the existing history read with matching account arguments. The dispatch remains permitted. An attachment-bearing personal question also bypasses the dedicated research branch at [AssistantEngine.kt:270](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:270). Whether a real model chooses that call remains unverified; the missing dispatch fence is visible in source.

Consequence: planner negatives and research-runner exclusions do not establish the claimed application-wide acquisition boundary. Forgotten data can be reacquired and supplied to ordinary chat through this path.

Narrow correction: apply a common per-turn read-admission check to the existing federated source reads, including mapped equivalent families and late results, while preserving write confirmation and operation-ledger behavior. Add regressions through the actual mounted `RemoteToolSet` for generic intent, revoked scope and forgotten raw/aggregate families.

**R1-F2 — P1: Ordinary-chat profile use lacks a response-time privacy fence.**

The attachment-bearing ordinary-chat path can inject targeted stored knowledge. Its provider wrapper checks that knowledge before dispatch, then forwards completion or stream output without another check.

Evidence:

- [AssistantEngine.kt:285](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:285) retrieves and attaches the targeted profile.
- [ResearchForeground.kt:54](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt:54) implements only a `beforeDispatch` comparison; `complete` and `stream` forward returned output.
- [AssistantEngine.kt:406](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt:406) accepts and persists the answer without revalidating those dependencies.

Minimal trigger: start a personal music question with an image and relevant stored profile evidence; after the pre-dispatch check, forget that field or revoke its source while the model request is pending.

Consequence: the affected late answer can still be displayed and persisted. The retained late-forget fixtures exercise `ResearchRunner`, not this ordinary-chat path.

Narrow correction: capture the profile generation and exact dependencies alongside the prompt block, then fence returned output and final persistence. Add delayed completion and streaming regressions that forget/revoke after admission and verify rejection of the affected result.

**R1-F3 — P2: Normalized cache identity can return a different exact acquisition read.**

The acquisition cache ignores technical controls, but cached entries retain their original `ResearchRead`. Checkpoint validation correctly requires the exact planned read.

Evidence:

- [ResearchSources.kt:70](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchSources.kt:70) keys the cache using normalized scope.
- [ResearchSources.kt:129](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchSources.kt:129) returns the cached capture without requiring `cached.read == requestedRead`.
- [ResearchProfile.kt:46](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchProfile.kt:46) renews canonical reads with technical controls removed.
- [DurableFoundation.kt:259](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt:259) rejects a checkpoint whose acquisition read differs from the planned read.

Minimal trigger: after receipt expiry, targeted profile retrieval reacquires a canonical CV read without `aggiorna`; before that capture expires, research uses the persisted form read containing `aggiorna=false`. Both share the cache key. `SourcePages` receives the cached canonical read, and commit rejects its acquisition binding.

Consequence: an otherwise valid consultation can perform a source read and then fail without a usable checkpoint. This fails closed; I found no evidence that the checkpoint accepts the mismatched binding.

The case-8 fixture at [ScopedExclusionsAndCanonicalEvidenceTest.kt:81](C:/Users/casua/AppData/Local/PampAI-qa/review-K2/snapshot/pampai/core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ScopedExclusionsAndCanonicalEvidenceTest.kt:81) constructs captures directly, bypassing this cache interaction.

Narrow correction: separate canonical-content identity from exact acquisition-cache identity. Add a real `ResearchSources`/profile/runner regression covering receipt expiry and both refresh-control variants.

All scores below are **provisional source/fixture scores on a 10-point scale**, excluded from approval means and minima.

| Criterion | Score | Verification state and reason | Problems / limits |
|---|---:|---|---|
| C1 product/scope | 6/10 provisional | Personal-intent planner and saved-scope selection inspected; generic negative fixtures retained. | F1 leaves ordinary federated dispatch outside that boundary. |
| C2 contracts/operations | 6/10 provisional | Signed host allowlist, typed negotiation, producer caller/scope/lease validation and consumer pre/post-transport checks inspected. | F1 and F2 prevent privacy closure across current root integration. Installed Binder/auth/cancel/readback: **NV**. |
| C3 models/images/routing | 8/10 provisional | Actual provider lifecycle hooks, atomic admission, settlement, helper context, exact-model checks and conservative bounded-model support inspected. Unknown money/bounds deny capped execution. | Real provider identity, semantics and billing: **NV**. |
| C4 Fluid/UI/continuity | 8/10 provisional | Actual Fluid screens, source form, correction/forget controls and presentation code inspected. Two retained synthetic PNGs visually inspected. Period inputs emit string epochs. | Overlay, IME, active handoff, TalkBack and real voice: **NV**. |
| C5 profile/background/budget | 6/10 provisional | Default-off setup, durable fences, correction precedence, distinct-record accounting, finite worker scheduling, constraints, cooldown and uncertain-result suspension inspected. | F1–F3 remain. OS scheduling/Doze and real process lifecycle: **NV**. |
| C6 source/data fidelity | 8/10 provisional | Copied Fluidify and CV producers compared with consumer handling: nested music records, local measured time, unknown imported duration, CV pages/aggregates and partial coverage remain distinguishable. | F3 affects acquisition continuity. No live-source/native correctness certification. |
| C7 evidence/readiness | 8/10 provisional | File integrity, final input/artifact maps, retained XML counts, lint and run provenance independently checked. | Review has the disclosed search-method deviation; source findings remain open and runtime readiness is **NV**. |

No criterion is NA. **No overall approval mean or minimum is computed.**

| Prior source case | Closure within reviewed source/fixture scope |
|---|---|
| 1 — period-form contract | **Closed:** string timestamps cross the real consumer policy; copied producer schema agrees. |
| 2 — scoped exclusions and late responses | **Not closed:** dedicated research checks are supported, but F1/F2 expose other production paths. |
| 3 — generic versus personal routing | **Partial:** planner negatives are supported; ordinary tool dispatch remains outside that fence under F1. |
| 4 — persisted interactive connections | **Closed within scope:** persisted connection flow is independent of autonomous plans and filters revoked grants. |
| 5 — actual music shapes and meanings | **Closed within scope:** producer shapes and presentation retain measured/unknown-duration distinctions. |
| 6 — durable retry beyond 64 tranches | **Closed within source/retained fixture scope:** turn association, captured context and continuation inspected. Full ViewModel/Android lifecycle remains NV. |
| 7 — correction versus optional learning | **Closed for the assigned prior-correction case:** conflicting learning is skipped transactionally while the valid checkpoint survives. Runner late-revocation fencing remains supported; F2 prevents broader late-result closure. |
| 8 — canonical/acquisition identity | **Not closed:** canonical comparison is improved, but the real cache introduces F3. |
| 9 — distinct consumed `maxRecords` | **Closed within scope:** references across pages/steps are unioned; synthesis includes profile dependencies; overlap counts once. |

The checks I executed were read-only hashing, inventory/confinement checks, JSON-map comparisons, XML/log inspection and limited inspection of existing PNGs. **I ran no tests, builds, devices, providers or delegates and wrote no files.**

Retained execution evidence was independently recounted:

- **Core:** 249 tests, 43 XML, final-6.
- **Feature:** 98 tests, 18 XML, full-4.
- **Engine AI:** 264 tests, 31 XML, full-4.
- **Bridge:** 25 tests, 4 XML, full-4.
- All four archives have zero failures, errors and skips. The six final-5 music cases are repeats, **not 104 feature tests**.
- The **55 unique assigned source-proof cases** resolve to passing retained XML entries; they remain historical evidence.
- Lint archives contain zero errors: core 24 warnings; feature 18 warnings/1 hint; app 39 warnings/1 hint.
- All **680 final-6 input entries** and **103 artifact-map entries** match copied bytes. Full-4 → final-6 has exactly the four declared source/test changes.
- Failed full-1/full-2/full-3 logs remain distinguishable from successful full-4/final-5/final-6 logs.

Integrity before and after all source reads:

| Check | Before | Final after |
|---|---:|---:|
| Manifest entries / unique paths | 1,192 / 1,192 | 1,192 / 1,192 |
| Actual files / directories | 1,192 / 495 | 1,192 / 495 |
| SHA or size mismatches | 0 | 0 |
| Missing / extra files | 0 / 0 | 0 / 0 |
| Reparse points / confinement violations | 0 / 0 | 0 / 0 |
| APK bytes | 102,150,985 | 102,150,985 |

Manifest SHA256 remained `159A316C0CA6E5848C871127B2BB58499AB8C86FD1883C1D8C421805049C9683`. APK SHA256 remained `FC8FE3144E8450E18B19391D9D0E2AAAF47B25AA7683C6A2759682398E58F5E1`. The snapshot contains 33 PNGs.

The review is **procedurally qualified**: one malformed `rg` command searched implicitly from its cwd, contrary to the absolute-search-only instruction. That command’s cwd was the frozen snapshot, so the search remained confined there. No mutable-project or other-review evidence was used. The complete final integrity check passed.

All mandatory runtime NV remains, including overlay/back/IME, active text+image handoff, voice/TalkBack/queued effort, installed source authorization/account drift, native/Connect/process-kill behavior, real provider accounting and OS scheduling/Doze. The retained broader Fluidify build failure and three core lint errors remain limitations; this review supplies no fresh source APK claim or waiver.
