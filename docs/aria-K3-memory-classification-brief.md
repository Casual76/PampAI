# K3 R2-F2/F3 — profile promotion safety and trusted tool classification

Fresh GPT-6.1 Sol high implementation, Connected Knowledge correction R3/3. Shared PampAI checkout; read applicable AGENTS/Fluid skill/regole. Do the work yourself, no subagents. User already authorized fixing findings and continuing. No permission question, commits/push/publication, devices/providers/source APIs/credentials, engine/schema/Gradle/source-repo changes, UI edits or unrelated work.

SOURCE ONLY: ROOT owns the sole compiler. Do not run Gradle/Java/build/test/lint until later explicit runner transfer. Prepare real integration tests; release/freeze source/tests in docs/aria-K3-memory-classification.md with APIs, exact hashes and prepared/executed distinction. No runtime/background enablement claim.

## P1: targeted profile bypasses Ricorda's safety gate

Read docs/aria-K3-R2-review.md F2. RicordaTool only checks preceding external-reader traces. Stored profile enters the ordinary image request before context creation, without a trace. A provider fixture can emit ricorda first, writing untrusted derived content to independent legacy MemoryRepository, which later enters unrelated prompts and survives a profile forget.

Make actual retrieved-profile/source provenance participate in that safety decision. The ordinary orchestration already runs in the app-owned TurnPrivacy coroutine context; its privateTurn and commit APIs provide genuine captured dependencies and an atomic local fence. Do not rely on a synthetic trace inserted by a test, model arguments or a mutable global question. Safety confirmation is independent of actionsEnabled and must still be requested with actions disabled, as the existing external-reader protection intends. Reject pending promotion after forget/revoke/correction before persistence, including after confirmation; keep temporary memory prohibition, cancellation and ledger/no-replay behavior. The actual legacy-memory insert should share the local privacy transaction when private dependencies are involved; do not perform source/network work while holding Room. A genuinely approved, currently valid promotion may remain an independent user-authorized legacy memory; don't silently bulk-delete old memory.

Add real engine/Room/provider-fixture coverage of profile-first ricorda, safety wait/refusal, actions off, late forget/revoke and accepted current promotion. Use real policy and memory DB; fake only transport/provider/irrelevant platform boundaries. Preserve old external-read TracedToolSafetyTest meaning and both execution overloads. Do not just mock a guard with the expected decision.

## Conditional P2: remote metadata controls read persistence

Copied producers currently label reads correctly; preserve that factual qualification. A stale/misleading supported read action=true is still classified as a write by generic RemoteTool.isAction and thus immediately settled by Engine, surviving late revocation as a write receipt/trace. Dispatch already uses known write names; normalize and use a single app-owned classification consistently across actual mounted dispatch, ledger preparation, staging and retained traces. No generic engine edits.

Own SourceBridgePolicy/RemoteCatalogs/RegistryBuilder if necessary. Required coordination API in SourceBridgePolicy:

`internal fun actionFor(toolName: String, fallback: Boolean): Boolean`

For namespaced cv_/fluidify_ calls return the app-owned known mutation classification, never the offered read's action bit. For local/other existing tools retain fallback. ROOT told the Engine author to use this API for LedgerAttempt isWrite, prepareToolExecution, onToolOutput and preserved-trace filters. Normalize actual mounted known-source catalogs too, so action-off/confirmation behavior and visible tool contracts agree. Do not let metadata action=false make a known mutation silently behave as a read. Preserve known source confirmations/operation ID gates, all genuine writes, bounded receipt readback, fragments, pending/cancel rules and conservative unsupported reads. Unknown names must not become authorized mutations through metadata.

ROOT already restored six real CV actions in SourceBridgePolicy: anno_scolastico, voto_segna_visto, evento_rimuovi, obiettivo_rimuovi, orario_modifica, comunicazione_adesione. Preserve them and the prior seven CV/three playlist writes. anno_scolastico is hybrid: only a nonblank seleziona is a mutation; read-only inspection remains gated. Keep ROOT's mounted six-action test semantics and year read-mode denial. Actual producer contracts may be read from immutable review-K3/snapshot/classeviva and fluidify, not unrelated mutable source sessions.

Add an admitted supported read with misleading metadata through the actual mounted/Engine path and real Room. Revoke after output before final completion; assert no raw output persisted as a write/read receipt or retained action trace. Retain real uncertain-write identity/no-replay coverage. Pure classifier tests alone don't establish Engine integration. Engine is another author's file: coordinate API/callback needs instead of editing it concurrently; ROOT can wire small remaining test seams after freezes.

## Ownership and coordination

You own tools/aria/AriaTools.kt, ToolSupport/PampaiToolContext only if needed, SourceBridgePolicy.kt, RemoteCatalogs/RegistryBuilder as necessary and NEW memory/classification integration test files. ROOT releases its current SourceBridgePolicy edits to you; preserve the six mutations/year distinction. ROOT owns ResearchQuestionPlanner.kt, existing OrdinaryTurnPrivacyTest.kt, SourceIntegrationTest.kt, ResearchConsumerTest.kt and feature SourcesScreen.kt: read but do not edit them.

The OTHER active implementer owns AssistantEngine, ResearchForeground, ResearchRunner/ResearchContracts, TurnPrivacy, DurableFoundation and ConversationsRepository. Do not edit those files. Existing TurnPrivacy APIs currentCoroutineContext[TurnPrivacy], privateTurn and commit must stay compatible; if changes are needed, coordinate before writing. ROOT already informed that author about actionFor above and memory-context requirements. For essential API coordination only, the author's thread is thread:delegated-task:command%3Amcp%3A7e3ad116-156b-4288-9b3f-790153c90e28%3Adelegate-task%3A8852f568-097f-42a2-bda5-1c89fba0057b. No other implementer may be spawned.

ROOT has source-only refusal constraints (sourceQuery vs profileQuery, no memory on explicit refusal, only-provided request registry), compound 'Che cosa ho ascoltato ieri su Fluidify?' coverage and corrected legacy availability copy. Preserve these. The other author also restores incremental output for provably source-free turns while retaining private/potentially private buffering. Neither author has compiler ownership. Final K4 freeze/reviews occur after both releases and ROOT integration/validation. All existing hardware/provider/Binder/OS NV remains open.
