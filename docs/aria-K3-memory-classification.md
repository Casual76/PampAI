# K3 R2-F2/F3 memory and trusted classification - source freeze

Connected Knowledge correction R3/3. Released to ROOT for integration and its sole compiler runner on 2026-11-08. These four files are frozen by this author; ROOT may now compile, execute, repair or integrate them. This release does not transfer runner ownership to this author.

## Implementation

- `RicordaTool` reads the actual coroutine `TurnPrivacy`, checks it before safety, and requests `confirmSafety` whenever captured retrieved profile or consumed source dependencies make `privateTurn` true. Existing external-reader trace protection remains additive. It never derives provenance from model arguments, a global question or a manufactured reader trace.
- Safety uses the existing real confirmation gate, independently of Actions. After confirmation, `TurnPrivacy.commit` renews proof outside Room, then validates captured local dependencies and inserts into the legacy memory repository under the same Room transaction. Temporary-chat prohibition remains before any gate/insert. Cancellation remains terminal. A valid approved promotion is independently authorized legacy memory; subsequent profile forget does not silently remove it.
- `SourceBridgePolicy.actionFor(toolName: String, fallback: Boolean): Boolean` is the shared Engine API. For `cv_`/`fluidify_` names it returns the app-owned known mutation classification; local and other names retain fallback. Unknown source names are reads subject to conservative admission, never metadata-authorized mutations.
- `knownWrite` uses that same map and checks catalog presence, without depending on the offered action bit. The hybrid `cv_anno_scolastico` is conservatively write-classified in the argument-free API, while dispatch requires a nonblank `seleziona`; inspection still follows read admission and cannot call the source unscoped.
- Actual catalog normalization happens in `SourceBridgePolicy.catalog`, already called by `RemoteCatalogs` before `RemoteToolSet.of`: known mutations become actions even when offered false; other source tools become reads even when offered true. Read confirmation metadata is stripped to avoid pre-admission action/describe behavior; genuine writes retain their source confirmation contract (including unconfirmed `voto_segna_visto`). Typed-result/operation-ID capability gates run after normalization.
- All prior seven CV mutations, six restored CV mutations (including hybrid year selection), and three playlist mutations remain classified. SourceBridgeCalls identity, status/cancel binding, bounded receipt readback and fragment implementation are unchanged.

## Coordination and ownership

No changes to AssistantEngine, ResearchForeground, ResearchRunner/Contracts, TurnPrivacy, DurableFoundation, ConversationsRepository, engine, schemas, Gradle, UI, planner or existing tests. No edits to RemoteCatalogs/RegistryBuilder were needed: their existing mounted path consumes the normalized catalog. No new agents, commits, pushes, credentials, devices or live providers.

Essential API coordination sent to the Engine author. Current shared source was inspected using the actionFor API in ledger isWrite, prepareToolExecution, onToolOutput, retained trace filters and quick write handling, and withContext(turnPrivacy) around orchestration. Those edits belong to the other author, not this release.

## Prepared regressions - NOT EXECUTED

`MemoryClassificationIntegrationTest` uses real AssistantEngine, real in-memory Room, MemoryRepository, DurableFoundation, ResearchProfile/Runner/Sources, OperationLedger, TurnPrivacy and confirmation gate. Source proof transport and provider are fixtures; unrelated runtime/platform boundaries are mocked. The seeded profile is synthesized from source evidence with actual persisted provenance and enabled authorization; it is not a synthetic tool trace.

Eight test methods prepare: first-tool ricorda safety/refusal with Actions off; pending forget/revoke/correction; accepted current promotion and temporary prohibition; forget during proof renewal after actual approval but before insert; pending cancellation; both tool execution overloads with real provenance; admitted metadata-action read reaching the provider before late revocation with no persisted receipt/trace; Room insert rollback plus a genuinely dispatched uncertain mutation retaining operation identity/no-replay.

`SourceClassificationTest` prepares three tests for catalog/ledger agreement, false-action genuine mutation identity/confirmation/Actions behavior, and year read/write distinction. Existing TracedToolSafetyTest and ROOT-owned OrdinaryTurnPrivacyTest/SourceIntegrationTest/ResearchConsumerTest are unchanged.

Prepared selection for ROOT's runner: MemoryClassificationIntegrationTest, SourceClassificationTest, TracedToolSafetyTest, OrdinaryTurnPrivacyTest, SourceIntegrationTest, ResearchConsumerTest. No test counts or pass claims follow from source inspection.

## Verification and limits

Executed here: source reads and manual source inspection, file writes, SHA256/size capture only. No Gradle, Java, build, tests or lint. The code/tests are uncompiled and must be validated by ROOT against the integrated shared checkout; this handoff is not closure evidence from execution. No runtime approval is issued.

Default autonomous background remains off; fixture-only enabled source/profile configuration is not a product runtime change. All previous NV gates remain NV: signed installed Binder/authentication/account drift/cancel/readback, device UI/overlay/back/IME/active text-image handoff/TalkBack/voice/queued effort, native/Connect/process kill, provider semantics/billing, OS scheduling/Doze. Copied producers currently classify supported reads correctly; misleading metadata remains a conditional contract regression fixture.

## Released file hashes

| Path | Bytes | SHA256 |
|---|---:|---|
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/tools/aria/AriaTools.kt` | 8420 | `B6CC1DDD4341DCF3F0A11F33C8590F0BF133FCFCE7D94C9A2486B9E44E6CEEDF` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceBridgePolicy.kt` | 6853 | `F2F658840CF8A152D64EBBA263DD9FABC0E8EC75F32B95733F5097FD1F27DA82` |
| `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/MemoryClassificationIntegrationTest.kt` | 25131 | `60E852B1FA605D636746181A5A7A12D4F577BB9B081068D04A57796BF391D5C9` |
| `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceClassificationTest.kt` | 4124 | `252DC1C3E00C2048CC0F2380F80B9566E3DBA5F0A189CFA14727763F2E4CC0B0` |
