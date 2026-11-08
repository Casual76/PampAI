# Read-only integration research

This is an advisory handoff from the Sol high research task. No implementation, gate approval, source lease guarantee, or live service result is implied. First-delivery device and review gates remain applicable. Source2 S3 is frozen independently.

The remaining P2 work can begin after the first delivery gate using the existing runtime and typed bridge. The smallest viable addition is an app-owned, durable research runner with fenced persistence, plus a generic provider dispatch lifecycle in the engine. The current code does **not** yet provide sufficient budget enforcement or externally verifiable source leases for unattended acquisition.

Read-only inspection completed. No files changed, builds/tests run, devices used, credentials read, or service/API requests made. This is an implementation handoff, not review or gate approval.

Paths below are relative to these roots:

- PampAI: `C:/VibeCoded Projects/PampAI`
- Fluidify: `C:/VibeCoded Projects/Fluidify`
- CV: `C:/VibeCoded Projects/classeviva-expressive/android`

**1. Minimum insertion points**

For PampAI Kotlin paths, `C` means `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant`; `E` means `engine/engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai`.

| Current file/API | Implementable insertion |
|---|---|
| `C/runtime/AssistantEngine.kt`: request preparation, `orchestrator.ask(input, live)`, `runQuick(...)` | Preserve the local quick path. Add app-level DIRECT/LOOKUP/RESEARCH selection before model orchestration. LOOKUP/RESEARCH receive an explicit objective, bounded source scopes and captured `ModelPolicy`. |
| `E/orchestrator/AiRouter.kt`: `RouterVerdict(groups, deep, category, none)`, `classifyWithTurn(...)` | Current verdict selects tool groups/depth; it does not represent research intent or multiple independent sources. Add compatible generic intent/objective fields if needed; keep source-specific planning in PampAI. Do not interpret `deep=true` as permission to research. |
| `C/runtime/ModelSelection.kt`: `ExactModel`, `ModelPolicy`, `ReadyProvider.pinTo(identity)` | Reuse exact provider/model identity. `pinTo` already fixes CHAT/DEEP/ROUTER to that identity. Capture the policy in the job; never reread “last selected model” during resume. |
| `C/runtime/DispatchAudit.kt`: `ReadyProvider.audited(..., persist)` | Compose dispatch admission with the existing audit. Current callback persists effort/model metadata, not budget reservations. |
| `C/bridge/RemoteCatalogs.kt`: `refresh()`, `SourceBridgeCalls.run(...)` in `SourceBridgePolicy.kt` | Add an execution-specific source facade for research. Capture authority/package, negotiated capabilities, scope and permission versions. Revalidate at dispatch; a mounted catalog is not authorization. |
| `E/orchestrator/AiOrchestrator.kt`: `AskInput.prepareToolExecution`, `onToolOutput`, `prepareToolParts` | Existing hooks can capture verified outputs and dynamic attachments. They do not persist a resumable research plan or enforce research-only tool permissions. |
| `C/db/PampaiDatabase.kt`, `C/di/DatabaseModule.kt` | Add job/checkpoint/evidence/profile/permission/budget tables through a migration from current schema **6**. Preserve existing chat, operation, usage and dispatch tables. |
| `C/db/MemoryRepository.kt`: `promptBlock()`, `add`, `update`, `remove` | Introduce structured profile retrieval beside legacy memories, then route edits/forget through one transactional service. Current `promptBlock()` injects a bounded list into every ordinary prompt. |
| `C/usage/UsageRepository.kt`: `onTurn`, `recordVision` | Retain presentation telemetry; introduce a separate authoritative reservation/settlement ledger. Asynchronous inserts cannot enforce caps. |
| `app/src/main/kotlin/dev/pampa/pampai/PampaiApplication.kt` | Existing startup calls `RemoteCatalogs.start()` and `ModelsMaintenance.runDaily()`. Add scheduler reconciliation here, conditional on completed setup. No existing WorkManager worker/configuration was found in the inspected app/core sources or dependency declarations. |

Do not run autonomous jobs through `AssistantRuntime.submit()`: it starts `AssistantForegroundService` and participates in voice, live conversation state and notifications. A worker should invoke a bounded research tranche directly, with a persisted job ID and no conversational side effects.

**2. Source adapters must handle two different continuation contracts**

Fluidify’s actual contract is in:

`app/src/main/java/dev/lelonio/square/bridge/{FluidifyToolHost.kt,BridgeState.kt,ResultFragments.kt}`.

- Use `capabilities`, `history_records`, `history_aggregate`, `history_track_counts`, `history_artist_counts`, `history_coverage`, and explicitly permitted playlist reads.
- History queries use `account`, epoch-millisecond `from`/`until`, `cursor`, `limit`.
- `SnapshotPager.page(...)` creates in-memory snapshots with a five-minute lifetime. Process death, expiry or access changes invalidate continuation.
- Large results use **`result_fragment`**, not ordinary page cursors. Persist snapshot/hash/queryHash/offset/encoding and accumulated fragments. Concatenate by UTF-16 offsets, verify SHA-256 over UTF-8, then parse JSON.
- Advance `nextPageCursor` only after finishing the result fragments. Deduplicate records/aggregates independently of fragment delivery.
- Expired continuation means restart that bounded read and deduplicate; never claim the previous cursor guarantees resume.
- `historyArchiveComplete=false` and unknown imported duration must survive aggregation and synthesis.

CV’s actual contract is:

`core/core-assistant/src/main/kotlin/dev/antigravity/classevivaexpressive/core/assistant/tools/SchoolDataTools.kt`: `RegistroDatiTool.run(args, ctx)`.

It delegates to:

- `core/core-domain/src/main/kotlin/dev/antigravity/classevivaexpressive/core/domain/model/SchoolDataAccess.kt`: `SchoolDataAccess.read(query: SchoolDataQuery): SchoolDataPage`.
- `core/core-data/src/main/kotlin/dev/antigravity/classevivaexpressive/core/data/repository/ScopedSchoolDataAccess.kt`.

Important differences:

- Pin `account`, `studente`, `anno`, `sezione`, dates and filters explicitly. Omitting `anno` uses the source UI’s current selection.
- CV uses `cursore` plus `dettaglio_offset`/`dettaglio_hash`; continuation instructions currently live in output text, while typed data contains the full page records.
- Decode the typed records first. Do not treat `pagination.hasMore=true` with no cursor as an empty/end page: it can mean remaining text detail.
- Detail continuation rereads the page; its hash rejects changed content. It is not a durable immutable fragment store.
- `aggiorna=true` is technical refresh and must be explicitly permitted, only on the first page. Historical refresh is restricted by section; unsupported history is a limitation, not empty data.
- `SchoolDataPaging.aggregates(...)` aggregates all matching records. Do not sum the same page-level full aggregate across pages.

`C/bridge/SignedPlaylistReader.kt` supplies useful bounded traversal and snapshot-bracketing patterns through `readSignedPlaylist(...)`. It currently expects `data.playlist`/`data.items` directly and does not assemble Fluidify fragment envelopes. Do not reuse it as a general research reader unchanged.

**3. Durable jobs and evidence**

Add small app-owned `research/` and `profile/` packages, with Room entities/DAOs under `C/db/`. Suggested names here are new work, not existing APIs.

Persist a job’s objective, DIRECT/LOOKUP/RESEARCH intent, exact model policy, source scopes, permission-version vector, state, retry time and bounded work allowance. Persist each step’s normalized query, page cursor, fragment state, source snapshot/hash, outcome and committed record keys.

A tranche should:

1. Claim the job transactionally with a recoverable execution lease.
2. Check setup, cancellation, local permissions and source readiness.
3. Perform one bounded read/continuation or synthesis step.
4. Commit evidence and checkpoint together, conditional on unchanged versions.
5. Yield with a persisted next eligible time.

After process death, reclaim expired execution leases and resume the checkpoint. Model-call uncertainty requires separate handling: an interrupted dispatched synthesis may already have consumed quota. Preserve its reservation and charge conservatively; a replacement call needs a new reservation.

Canonical profile/evidence fields should include:

- Stable item ID, canonical field key, typed value/unit and optional display text.
- Kind: **user statement**, **observation**, **inference**.
- App/package/authority, source account, student where applicable, source record/entity IDs and evidence links.
- Period bounds with timezone/boundary semantics; observed/synced timestamps; coverage complete/partial/unknown and stale/unknown.
- Revision, creation/review timestamps, superseded-by relationship.
- For inference: generating provider/model, derivation version, confidence/limitations and supporting evidence IDs.
- Captured local permission versions and source lease identity where available.

Keep deterministic observations separate from model interpretation. For example, a measured listening count is an observation; “prefers this artist” is an inference. Local measured duration and imported unknown duration cannot share a numeric total that implies complete listening time.

Retrieval should select canonical fields and evidence matching the question’s subject, source, period and freshness requirements, then enforce token limits. Do not append the full profile. Replace both ordinary prompt construction and `AssistantEngine.estimateContext()`’s current `memory.promptBlock()` treatment coherently.

Temporary conversations must never become profile extraction inputs or durable research evidence. Carry this exclusion into repositories and job creation, not only the existing `RicordaTool` check.

**4. Corrections, forgetting and fencing**

Current memory deletion and normalized-text matching cannot prevent semantic relearning. Introduce:

- Explicit correction precedence over observations/inferences.
- Tombstones keyed by canonical field/scope and, when applicable, source-record identity.
- Dependency tracking to invalidate derived items supported by forgotten evidence.
- Explicit user re-enable to remove an exclusion.

Canonical keys make suppression deterministic but cannot cover every paraphrase automatically. Keep unmapped free-text corrections/exclusions visible; block ambiguous extraction instead of pretending substring matching provides semantic forgetting. Tombstones should retain minimal exclusion metadata, not the forgotten prose.

Use **separate version dimensions**:

- Local profile learning/use permissions and correction/forget epochs.
- Source connection/account permissions.
- Source-owned authentication/account generation or lease.

The current `AssistantRuntime.generation` protects live UI ownership only; it is process-local and does not replace any of these.

Every source result, evidence/checkpoint commit, synthesis result, retrieval and final prompt dispatch must check the captured version vector. Revocation/forget transactions increment affected versions and invalidate jobs plus derived items atomically. Late results remain rejected; already dispatched consumption is still settled.

There is a concrete source-contract gap:

- Fluidify internally checks `HistoryAccess` and `webApi.tokens.generation`; `capabilities` returns account/source and limits, **not an externally usable generation lease**.
- CV internally captures and commits account work leases, but `SchoolDataPage`/typed bridge results do not expose that lease.
- `ToolSourceRef(id, uri, account)` contains no generation.
- `RemoteCatalogs.start()` observes package changes, not source logout/account transitions.

Consequently, checking the same account string cannot prove “no logout and reconnect occurred,” particularly across PampAI process death. A capability-gated source lease/validation contract is needed to meet the full requirement. It must provide opaque source-generation identity and validation, without exposing credentials.

While source repos remain frozen, implement the consumer, fixtures and fail-closed capability handling in PampAI. Leave unattended acquisition/use suspended when the required proof is unavailable. A local permission epoch cannot manufacture source-owned proof.

**5. Dispatch budgets and shared backoff**

`E/provider/Model.kt` already declares:

`ChatRequest.beforeDispatch: suspend (ProviderDispatch) -> Unit`

Both `OpenAiCompatProvider` and `GeminiProvider` invoke it before their internal parameter/effort fallback retries. This is the correct admission boundary to extend.

However, `ProviderDispatch` currently supplies provider/model/effort metadata, not a reservation identity, complete request bounds or per-attempt settlement. Add a generic, additive dispatch lifecycle sufficient to:

- Supply effective input/output bounds and enabled chargeable features.
- Persist a reservation before each wire attempt.
- Identify that attempt in headers/result/error callbacks.
- Settle usage and persist cooldowns even on cancellation.

The app owns budgets, tariffs and Room transactions; the engine owns generic callback plumbing.

Cover all actual model paths:

- Router and each orchestrator round.
- Parameter rejection, trimmed-context, empty-response and streaming-to-completion retries.
- `ModelSelection.interpretImages(...)`, including dynamically returned tool images.
- `tools/web/WebTools.kt` helper calls.
- `AssistantEngine.autoTitle(...)`.
- Any newly introduced summarizer/extractor.

`prepareToolParts` already reaches `ModelSelection.prepareParts(...)`; it must receive the same execution/budget context. Helper calls currently build providers separately and do not inherit `ReadyProvider.audited(...)`.

Reserve request count, bounded input/output tokens and worst-case money atomically. Use integer monetary units, tariff provenance/version/expiry, and explicit settlement states. Unknown usage after dispatch must not release the reservation as unused. A helper cache hit makes no dispatch and consumes no new reservation.

`UsageRepository.CostTable` is dated `2026-09` and is an estimate table. It is unsuitable for enforcing a monetary guarantee. With an active money cap, absent/stale/untrusted tariffs—or unbounded extra charges—must suspend autonomous model calls. Disabling the money cap requires explicit setup with request/token limits.

Foreground and background share provider/account quota cooldowns; background has its own spending allocation. Persist Retry-After/reset-derived eligibility or bounded backoff with jitter. Foreground gets priority before the next autonomous dispatch; worker-local delays and in-memory counters are insufficient. Budget denial must produce a suspended checkpoint, not enter generic failover/retry loops.

**6. Enforce read-only execution independently of catalog labels**

`RemoteTool` currently trusts `info.action`/`needsConfirmation`; `SourceBridgePolicy.catalog(...)` primarily gates typed capabilities. `actionsEnabled=false` cannot protect against a misclassified tool.

Create an explicit research allowlist by source identity + tool + argument policy. Apply it to catalog exposure **and immediately before `BridgeCalls.run(...)`**, including continuations and dynamically opened groups. Deny unknown tools by default.

Permit source refresh only as an explicitly approved technical read operation. Exclude playback, playlist mutations, UI-opening tools, notifications, reminders, permission prompts and local memory-writing tools from the research tool set. Profile persistence is a fenced repository operation, not a model-selected general tool.

**7. Scheduling and setup**

Add per-area configuration containing enabled/setup-complete state, frequency, source scopes, exact authorized primary/helper models, request/token/money budgets and network/battery preferences.

Defaults can be implemented now:

- All autonomous areas disabled; setup incomplete.
- No inferred budget or model authorization.
- Wi-Fi preference implemented with the appropriate network policy, battery-not-low enabled.
- Unique scheduled work per area; finite tranches, checkpoints and persisted retry times.
- No autonomous messages, notifications, permission requests or source actions.
- Startup reconciles enabled schedules; workers independently recheck configuration.

Use the existing `feature-assistant/.../memory/MemoryRoute.kt`, `MemoryViewModel.kt`, and `settings/{SettingsRoute,ConnectedAppsSettings,AssistantSettingsViewModel}.kt` as UI integration points. Preserve reminder behavior separately. Apply real Fluid lists, controls and navigation; domain-specific profile/source components stay in PampAI.

**8. Bounded order and ownership for two Sol-high implementers**

| Increment | Implementer A | Implementer B | Acceptance |
|---|---|---|---|
| 1 | Generic dispatch lifecycle in `engine-ai`; adapter retry fixtures | New app Room tables/DAOs, permission epochs, corrections/tombstones | Migration from DB6 preserves all existing rows; no autonomous dispatch possible |
| 2 | App reservation/cooldown service, audit/helper integration | Fluidify/CV read adapters, fragment/page checkpoints, explicit read-only policy | Real source-shaped fixtures resume correctly; denied/misclassified tools never reach transport |
| 3 | DIRECT/LOOKUP/RESEARCH integration in `AssistantEngine` | Profile retrieval/edit/forget repository integration | One bounded foreground cross-source research job, fixed model, verified references, no temporary extraction |
| 4 | WorkManager dependency/wiring, worker/scheduler | Setup/profile UI in `feature-assistant` | Disabled-before-setup behavior and worker interruption/restart tests |

Give A exclusive ownership of existing runtime/provider integration files; B owns new persistence/research/profile files and UI. A single designated owner should integrate `PampaiDatabase.kt`/`DatabaseModule.kt` migration changes. Source repos remain read-only. Do not start children as part of this preparation.

Meaningful acceptance fixtures should extend existing `DispatchAuditTest`, `DispatchAuditPersistenceTest`, `VisionUsageTest`, `SourceIntegrationTest` and migration test patterns:

- Two concurrent reservations against one remaining allowance: exactly one reaches fake transport.
- Adapter fallback/retry and stream fallback: every wire attempt has a committed reservation.
- Revocation/forget paused at barriers before source return, evidence commit, synthesis return and prompt dispatch; no excluded result becomes retrievable after restart.
- Same account with a changed source generation; missing lease capability; disconnected/offline sources.
- Fluidify escaped Unicode fragments, expired snapshots and page continuation; CV detail-only continuation, changed hashes and repeated full aggregates.
- Worker duplicate execution/process death: no duplicate committed evidence; uncertain dispatched usage remains reserved.
- Missing/stale tariffs, shared 429 cooldown, foreground arrival and cancellation.
- Cross-topic retrieval, correction precedence, tombstone paraphrase/canonical mapping and temporary-conversation exclusion.

Already fixed by P2: scope, setup requirement, no spontaneous behavior, exact model choice, budget regimes, revocation semantics and preservation of current data. Still missing in code: durable research/profile state, admission/settlement, worker setup, enforced read-only policy and external source-generation proof.

All consumer code, disabled defaults, migrations and fixture acceptance can be implemented without real accounts or further user answers. Actual account binding, model/budget choices and autonomous enablement remain setup inputs; full source-lease guarantees require the later capability contract.
