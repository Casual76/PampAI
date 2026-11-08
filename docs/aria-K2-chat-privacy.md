# K2 correction R2/3 ? ordinary chat privacy handoff

Status: SOURCE/TEST FREEZE. Files below are released to ROOT for serial integration and validation. Compiler ownership remains ROOT's. No Gradle, Java, build, test, lint, devices, live providers, credentials, subagents, commits or pushes were used. No runtime, device, provider or phase gate is claimed passed. This handoff addresses R1-F1 and R1-F2; R1-F3 remains ROOT-owned.

## Common admission API and production wiring

`ResearchForeground.captureTurn(question, query, includeProfile)` captures the real question, positive planner intersection, existing authorized-read resolver (interactive connection precedence retained), local policy epoch and exact retrieved profile items/evidence. `TurnPrivacy` is a coroutine context element scoped to one ordinary orchestration, never a global current question. `ResearchForeground` retains its original four-argument constructor so ROOT-owned consumer fixtures need no constructor edits.

`AssistantEngine` wraps the actual `AiOrchestrator.ask` in this element. The existing mounted `RemoteToolSet -> SourceBridgeCalls.run` reads it at dispatch. Missing context, generic intent, unknown/legacy tools, mismatched package/authority/domain/account/student/year/period/read arguments and direct source-proof control exposure fail closed with typed nonretryable errors. Initial changed/excluded scopes return `SOURCE_SCOPE_CHANGED_OR_EXCLUDED`. Once private dependencies have been admitted, invalidation is terminal `TurnPrivacyInvalidated : CancellationException`; it cannot trigger the provider's ordinary retry/failover path.

Admitted exact reads traverse real `ResearchSources.acquire/read`, with its unchanged hardcoded schemas, equivalent-family exclusions, signature/authority verification, signed lease and pre/post transport checks. The model never receives the `sourceProof` control payload. The app rechecks current saved scopes, local epoch, proof dependencies and exact profile values/evidence before subsequent model dispatch and after returned content. Receipt renewal runs outside Room transactions. Expired/unprovable dependencies conservatively invalidate the private turn.

Legacy MediaBrowser reads mounted by RegistryBuilder (`musica_adesso`, `musica_coda`, `musica_cerca`, `musica_playlist`, `playlist_brani`, `musica_recenti`, `musica_download`, `musica_dispositivi`) cannot prove account/read scope and return `UNSCOPED_LEGACY_SOURCE_READ`. Existing action implementations are retained. Ordinary source-proof reads require exact saved scope; there is no fuzzy legacy-to-current mapping that could guess missing account/year/period. Known equivalent raw/aggregate/playlist families remain enforced by the existing `ExclusionScopes` policy.

## Response, persistence and operations

`ResearchForeground.guarded(ready, turn)` checks complete before/after and checks stream admission, chunks and final release. Streams are buffered; AssistantEngine also uses a separate internal state flow for ordinary orchestration so tool preambles/intermediate answers cannot reach display or the asynchronous partial saver. The saver writes no ordinary partials after capture. This intentionally removes incremental ordinary output/progress; confirmation gates have their independent flow.

`DurableFoundation.withTurnPrivacy` validates epoch, grants/proofs/exclusions and exact profile items/evidence locally in the same Room DB transaction as accepted result writes. `TurnPrivacy.commit` renews/validates source authorization before opening that transaction. No source/network operations run inside it. Answer, accepted read receipts, run, conversation metadata and final publication share the guarded commit.

Read execution IDs are generated once and passed unchanged to tools. Read arguments/results are staged in memory, then `ConversationsRepository.acceptRead` persists them only inside the accepted commit. Writes still reserve/settle immediately through the existing OperationLedger, retaining confirmation behavior, stable supplied operation IDs, no replay and uncertain receipts. Invalidated responses keep the turn and write traces/receipts, omit rejected text/read traces, clear the turn's conversation cache (image-helper input interpretation and explicit model choices are unchanged), and do not dispatch a title. Failed/cancelled ordinary runs also avoid private partial/cache reuse. Provider wrappers retain the underlying dispatch/audit/lifecycle callbacks; already sent requests are not undone by rejection.

Successful private ordinary answers use run outcome `private`, are not put in the in-memory conversation cache and do not launch automatic titles. `ConversationsRepository.exchanges` excludes `private`, `research` and `privacy_invalidated` answers from future model context while retaining visible history. A later personal question must obtain fresh knowledge through the current guard. Unrelated source-free turns ignore unrelated policy changes.

`SignedPlaylistReader` uses a trusted bounded receipt hook: exact Spotify account/entity, saved operation identity, exact details/tracks names, exact supplied readback request ID and restricted argument keys. A model cannot acquire this authorization by forging a prefix. Existing snapshot brackets, real cursors, uncertain outcomes and no write replay remain. Only explicitly known playlist writes retain the direct write transport. Polling is accepted only for jobs actually returned by that write transport; unbound/legacy read polls return `UNBOUND_SOURCE_JOB`. The source-proof read channel requires checked Done and has no raw pending/poll fallback; generic engine v1 transport is unchanged.

## Prepared regressions ? NOT EXECUTED

`OrdinaryTurnPrivacyTest` contains 16 test methods (some loop over multiple cases), using actual Room, ResearchForeground/Profile/Sources, positive planner, scope resolver, mounted RemoteToolSet/SourceBridgeCalls and actual AssistantEngine image wiring. External source transport and provider boundaries use fixtures. Production admission is not mocked.

- Missing context, generic intent, unknown reads and direct proof controls; exact valid read and wrong period.
- Saved scope replacement/revoke; equivalent forgotten raw/aggregate families; delayed read forgotten while pending.
- CV legacy denial, missing school year, and valid exact school read.
- Delayed complete/stream after user-statement correction; no released chunks, commit or extra provider call.
- Source-free unrelated forget and fresh user statement; rejected commit; local revoke and source authorization change after consumed read.
- Actual ordinary image/profile stream with forget after admission: no partial/final text on disk, no exchange/cache/title reuse, no further provider call.
- Uncertain write ledger retention and no replay during invalidation.
- Accepted private answer remains visible but is omitted from subsequent model context; mounted legacy MediaBrowser denial.
- Forged readback request prefix fails while trusted receipt readback retains original uncertain outcome/operation.
- Legacy pending-read/unbound status regression proves neither raw dispatch nor polling can release its late private status.

Existing SourceIntegrationTest, SourceReceiptPersistenceTest, QuickCancellationIntegrationTest, TracedToolSafetyTest and ROOT consumer/research tests remain relevant integration coverage to run serially. They have not been executed or certified by this implementer.

## Limits and ROOT validation

Compilation, all prepared/retained tests, lint and assembly are NV. Live signatures/Binder, installed source versions, real pending jobs/cancellation, provider HTTP lifecycle/accounting, Compose display/lifecycle and process death are NV. The real image integration fixture uses a provider stub, not a live provider or actual image interpretation. The policy deliberately rejects unsupported legacy/pending reads rather than inventing a scope. Private output is buffered in memory until commit; process death loses it. The preserved write receipt/readback authorization remains independently bounded to the existing operation, rather than expanding model read consent.

Historical answers without a stored `private`/`research` classification cannot retrospectively acquire exact dependency provenance under schema7; no mass history deletion or guessed migration is performed. Existing visible history remains. ROOT should assess that compatibility limit explicitly before K3. Full UI behavior of nonincremental ordinary answers and source-proof pagination needs runtime validation. No background area is enabled, no engine/schema/pins or source repository changed.

ResearchSources.kt and ResearchConsumerTest.kt were read but never edited. ROOT's intent grammar, scope precedence and cache identity edits were preserved. Existing shared-checkout changes outside this manifest are not owned or certified by this handoff.

## Frozen file manifest

SHA-256 is over final source bytes at release; the handoff itself is additional documentation and is not self-hashed.

| File | SHA-256 |
|---|---|
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceBridgePolicy.kt` | `192f5e62017b92bd8c9b69f09a4899908f6db4b7e22d487f6e9f7d79206476d9` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/bridge/SignedPlaylistReader.kt` | `07cdb16c762a0ff8d45b921858059804477d2cb091eebaafbc817d3bd1f14868` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/ConversationsRepository.kt` | `1a9f26881ebfecc008877594818093f0cdf0feabd4b65ec65f906dec0e5a0e74` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt` | `06324c956b662b1eb8d140ea84ad5c5dacb9b82a7c24161b087c5873aa8d4c94` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt` | `a98d4de8caffd21169af801a0cf71ef018e7d2030986f1d8bb76e86653bcd4b4` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchProfile.kt` | `894ca6fbdd478dfd5936c757bf5dc4621f050cbe1b6831c49200ca3069603fe9` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/TurnPrivacy.kt` | `9694291d557cb028ebc601ed1af5749da51c71a8b4693dc82e343fd97a638a9c` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt` | `9758b98df32d312e5bd4b234121fa6b32e2874a1be4d45600c01b1708c5b5457` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/RegistryBuilder.kt` | `b61b304c92422b54fd0cf32f6ca171675d388e159bedcd212eff80e96ccc3d9c` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/LegacySourceReadGuard.kt` | `d3abb13927023a21252db82d6e185a25062aedb015d61051091ab373343dc159` |
| `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/OrdinaryTurnPrivacyTest.kt` | `744c3b6efe088c77a8b55bba85c642033848229f8296ab4b9dbf7e65593c21ce` |
