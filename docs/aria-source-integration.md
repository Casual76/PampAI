# Aria source2 client integration

2026-11-07. Authorized implementation only. No publication, child agents, Gradle, device,
provider, Spotify or school calls were started by this implementer. UI owns the sole runner.
All PampAI core files were frozen after the explicit readiness notification to the UI thread.

## PampAI manifest

Paths below are relative to `core-assistant/src/`:

- `main/kotlin/dev/pampa/pampai/core/assistant/bridge/RemoteCatalogs.kt`: mount filtered catalogs through the source account adapter; show mounted tool count.
- `main/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceBridgePolicy.kt`: negotiated transport gates and invocation-scoped signed account discovery.
- `main/kotlin/dev/pampa/pampai/core/assistant/bridge/SignedPlaylistReader.kt`: dedicated signed Spotify readback with bounded paging and snapshot checks.
- `main/kotlin/dev/pampa/pampai/core/assistant/receipts/ReceiptActions.kt`: route Spotify entities to signed readback in both ledger reconciliation and receipt opening; persist changes using the existing reactive operation DAO.
- `test/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceIntegrationTest.kt`: six fake transport/contract regressions and reusable source fixture.
- `test/kotlin/dev/pampa/pampai/core/assistant/bridge/SourceReceiptPersistenceTest.kt`: file-backed operation receipt reopening/readback/replay-blocking regression.
- This report: `docs/aria-source-integration.md`.

No app, feature, runtime, DB, canonical engine or local MediaSession source was changed.
Existing TracedTool already overrides both run overloads and forwards ToolExecution unchanged;
RegistryHolder mounts the entire remote catalog through TracedTool. No extra domain tools were added.

## Client behavior

Catalog negotiation is distinct from authenticated domain capabilities. Legacy functions remain
mounted. Fluidify capabilities/history/playlist functions and CV registro_dati require negotiated
protocol >=2 plus typed_results. New action/confirmation functions also require operation_identity.
Catalog names already provide concrete tools: `fluidify_capabilities`, `fluidify_history_records`,
`fluidify_history_aggregate`, `fluidify_history_track_counts`, `fluidify_history_artist_counts`,
`fluidify_history_coverage`, `fluidify_playlists`, `fluidify_playlist_details`,
`fluidify_playlist_tracks`, `fluidify_playlist_create`, `fluidify_playlist_append`,
`fluidify_playlist_rename`, and `cv_registro_dati`. These use domain_underscore names, not slash names.
The source catalogs retain their descriptions, group hints, vocabulary and complete parameter schemas.
The model can discover explicit account/year/cursor/filter arguments through ordinary tool traversal.
There is no research worker or profile/background task.

For every Fluidify data/write invocation, SourceBridgeCalls first calls the same signed authority's
capabilities with {}. It requires completed typed discovery, source=spotify, and matching attributed
account. A caller-supplied account must match; it cannot select/change the source account. No account
identity is stored globally. The actual call keeps the exact parent operation ID; only the extra
read-only discovery gets a derived suffix. Source authentication errors remain typed errors;
unreadable/transport discovery becomes source_not_ready and never an empty collection. Host-side
account-generation fences remain responsible for changes while a call is executing. No retries,
local playlist writes, reconciliation mutations, playback or preview are introduced.

Signed playlist entities use source=spotify, type=playlist, Spotify ID, spotify:playlist URI, and
an account-attributed spotify source reference. They never pass through MediaBrowser.item.
Receipt opening first rereads the current persisted row. Both opening and ledger reconciliation
perform read-only playlist_details, playlist_tracks with actual nextCursor, then playlist_details.
The reader requires matching source/account/entity and stable snapshotId/trackCount, complete finite
cursor traversal (maximum 20 pages/2,000 entries), and a count matching the collected entries.
Missing pagination, snapshot, account, IDs, failed later pages and changed snapshots retain the
previous data with an explicit uncertain/partial error. Unknown archive coverage stays unknown/false.
The original write outcome and operation identity never upgrade merely because current state is readable.

Successful readback keeps source playlist JSON at data.playlist, snapshotId and trackCount, and full
source data.items=[{position,item:<PlaylistTrackDto>}], including unavailable/null tracks. Original
receipt data is merged rather than discarded. The existing DAO finish invalidates its observed receipt
flow; no DB/schema changes. Opening launches Fluidify using its supported app launch intent, without
pretending that a Spotify ID is a local media ID or launching a fake preview. Local source=fluidify
playback receipts retain MediaSession readback. Timer externalClock and existing calendar verification
are unchanged. The reader conservatively refuses ambiguous installed stable/debug host discovery;
the existing open intent targets the stable Fluidify package.

CV registro_dati already traverses the generic registry. Its typed data includes full records,
source IDs, account/student/year/section, aggregate/coverage/freshness and real pagination. The wire
codec, RemoteTool, TracedTool and receipt codec retain these fields without a second school adapter.
School event entities use source=cv and their actual section/type and ID; scoped source references
include student/year/section plus account. Existing source event write/readback failures stay uncertain;
no parent-side conversion to empty or completion was added. Parent codec regression checks full body,
scope, pagination, error and entity preservation, rather than school REST behavior.

## Selective downstream propagation

Both embedded engine HEADs were checked: a055257fc4b559bee54b9ce114f265bcd237adfd, version 3.0.5.
Heads/version files remain unchanged. Existing player/theme/README, CV README/screenshots and
preexisting contract sync patches were preserved.

Each destination was compared directly to canonical current files before replacement. Fluidify host
had the write identity guard already and differed only in blank-read-ID fallback. Its RemoteTools
already had the two-argument write guard and lacked bounded cancellation. CV lacked both identity
guards and cancellation. No other independent differences occurred in these selected production files.
Exact copied/reconciled files, relative to each downstream engine root:

- `engine-ai-bridge/src/main/kotlin/dev/antigravity/fluidengine/ai/bridge/AiToolHostProvider.kt`
- `engine-ai-bridge/src/main/kotlin/dev/antigravity/fluidengine/ai/bridge/RemoteTools.kt`
- `engine-ai-bridge/src/test/kotlin/dev/antigravity/fluidengine/ai/bridge/RemoteToolTest.kt`

RemoteToolTest retained Fluidify's additional legacyWriteWithoutParentExecutionIdentityNeverLeavesClient
test. Existing action/confirmation/long-job fixtures use explicit parent identity; cancellation and
legacy-read/no-ephemeral-write tests were added from canonical. Legacy v1 callers providing a nonblank
string requestId remain admitted. Generic read calls retain fallback identity. Searches of downstream
app/core production sources found no RemoteTool, AiToolClient or RemoteCatalog consumers, so there is
no actually-used remote two-argument write caller to migrate. Their old local orchestrator run(args,ctx)
path was preserved; these generic guards apply to remote transport, not all local tools.

The source apps' own assistants still had model-facing plain prose, so the following minimal generic
envelope changes were necessary (no provider/model/audit hooks or full orchestrator replacement):

- `engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/tools/ToolResults.kt`: prior contents compared equal to canonical prefix; append modelText envelope implementation.
- `engine-ai/src/main/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiOrchestrator.kt`: one import and replace only the Message.ToolResult content expression with modelText(config.toolTextChars); all 3.0.5 dispatch/provider/model behavior retained.
- `engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/tools/ModelToolTextTest.kt`: copied focused bounded-envelope regression.
- `engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiOrchestratorTest.kt`: add canonical legacy-success-to-provider regression and update one prose-prefix assertion.
- `engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiHierarchyTest.kt`: three envelope/prose assertions.
- `engine-ai/src/test/kotlin/dev/antigravity/fluidengine/ai/orchestrator/AiToolTimeoutTest.kt`: assert envelope before extracting source prose for existing timeout assertions.

Public catalogs regenerated with each destination's gen_api_catalog.py into temporary output; copied
only relevant ai-bridge.md, ai-orchestrator.md and generated README.md into both
`skill/fluid-engine/references/api/` and `docs/riferimento/api/`. Generator: exit 0, 870 public
declarations, 27 generated pages for each engine. Other generated families were left untouched.
No downstream app source or canonical PampAI engine was edited by this implementer.

## Validation and serialized commands

All seven new PampAI tests and downstream changed tests are **UNRUN by this implementer**. UI/root
must compile/run them after source freeze coordination. The two compilation diagnostics reported by
UI while files were in flight were corrected before readiness: explicit items.addAll(page) and
fresh.error?.let avoid overload ambiguity and cross-module public-property smart casts, respectively.
Static diff whitespace checks passed in both downstream engines and the PampAI core scope (only
existing LF/CRLF notices). Documentation generation is not a build/test or runtime validation.

Runner owner, from PampAI, retaining the chosen isolated build-root properties:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :core-assistant:testDebugUnitTest
```

After finishing PampAI, from Fluidify, then separately from classeviva-expressive/android:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :engine-ai:testDebugUnitTest :engine-ai-bridge:testDebugUnitTest
```

These commands were recorded, not executed here. Existing source-app validation reports predate this
new downstream patch and cannot certify it. New fixtures exercise transport adapters and durable
receipt/ledger storage, not actual signer/Binder/Spotify or school services. Real signed installation,
authenticated account drift during Binder calls, live playlist paging/snapshots/permissions, lost
responses/process death, source-app navigation, school account/year refresh failures and MediaSession
continuity remain runtime evidence gaps. No implementation/review gate or release readiness is claimed.
