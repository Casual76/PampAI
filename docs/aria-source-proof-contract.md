# Source authorization proof v1 (source implementation contract)

Status: source implementation validated by all 55 assigned tests; CV assemble/scoped lint pass. Fluidify assemble/app lint blocked outside proof scope. Runtime NV; compiler runner released to ROOT.

Both signed source registries add `source_capabilities`, `source_lease`, `source_validate`,
and `source_read`. Remote client prefixes are `fluidify_` and `cv_`. Negotiate existing
bridge typed v2 first; require structured `ToolResultDetails.data`. Text is never proof.
No global bridge protocol bump. Signature gate and original confirmation gates remain.

- `source_capabilities {}` returns contract=`aria.source-proof`, version=1, capability
  (opaque, caller/state/process bound), authorization, incarnation, domain, authority, account, student,
  ttlMillis=60000 and readTools. No credentials or credential-derived hashes.
- `source_lease {version:1, capability, tool, arguments:{...}}` captures an exact explicit
  read scope and returns a receipt (fields below). Account must be explicit. CV additionally
  requires studente, anno and sezione; no selected-year fallback. Filters and limit are exact;
  cursor/cursore, dettaglio_offset/hash and aggiorna are continuation controls, not scope.
- `source_read {version:1, capability, lease, tool, arguments:{...}}` validates the lease
  before dispatch and after result production. It executes only the hardcoded read allowlist,
  irrespective of metadata, and attaches `sourceProof` in typed result data. Legacy direct
  tools remain compatible but produce no source proof. Consumers must use source_read.
- `source_validate {version:1, capability, lease, tool, arguments:{...}}` checks the same
  exact original scope against current source state; returns a fresh point-in-time receipt.
  It never renews the lease. Reacquire capability and lease when expired.

Receipt: contract, version, domain, authority, incarnation, authorization, capability, lease, account,
student, scope={tool,arguments}, checkedAtMillis, expiresAtMillis, valid=true.
`authorization` is a source-generated random UUID, distinct from capability and lease IDs.
This field is response-only and must not replace capability/lease inputs.
It is stable for the same signed caller and unchanged current source authorization state within
this process incarnation, including across expired capability/lease replacement. Each caller
has a different authorization identity; query scope does not change that identity. Observing a
changed source state (generation/publication/account/recording/readiness) clears identities and
invalidates outstanding handles; a process restart also creates new identities. No credential
values or credential-derived hashes are involved. The identity is not a receipt or permission
by itself: fresh typed validation, exact scope, incarnation and TTL checks remain mandatory.

The identity map retains at most 128 callers for the current source state, independently of
capability TTL. To preserve renewal stability, it does not evict unchanged caller identities:
a new caller at capacity fails closed with `source_authorization_capacity`. Existing callers
can still renew. The next observed source-state transition clears the map. Capability/lease
handle eviction and TTL remain strict and do not renew old handles.

Consumers may bind the same `(incarnation, authorization)` plus exact scope to an existing
local grant for verified freshness-only renewal; this source API does not modify local DB
permission versions, jobs, evidence or profile. Missing authorization must reject renewal.

Lease and capability are opaque random in-memory handles, not self-verifying bearer tokens.
TTL is enforced using a monotonic source clock; wall times are informational. A source restart,
same-account reconnect, authorization/data generation change, recording change/deletion, or
CV CIE alias transition invalidates old handles. Missing caller/state/proof fails closed.
Fluidify captures history generation plus Web API generation and recording state; CV captures
AuthRepository.dataEpoch (not just authenticationEpoch), current username/student/auth method,
and reference identity of SessionStore's already-published in-memory AuthSnapshotLease.
This identity changes on every successful auth snapshot publication, including same-account
READY-to-READY login with an unchanged dataEpoch. No token/credential field is read or hashed;
conservative invalidation also occurs on a refresh publication. Snapshot readiness must be READY.

Fluidify permits history_records/aggregate/track_counts/artist_counts/coverage and
playlists/playlist_details/playlist_tracks. result_fragment is accepted only for a snapshot
first returned by source_read under the same lease; subsequent validate uses original scope.
Original fragment UTF16 offsets, UTF8 hash, TTL and final-page cursor rules still apply.
CV permits registro_dati only. Its original typed records, per-page full-match aggregate,
detail continuation and refresh restrictions remain. Do not sum aggregates across pages.

This is source-local authorization at a point in time, not upstream token validation and not
a distributed lock. Cached reads can work offline inside a reachable authorized source; a
failed/unreachable validation is unknown and must reject consumption. Remote server-side
revocation unobserved by the source is not detected. Revalidate before local commit and
retrieval/prompt and bound receipt age; source revocation can race the following local write.
Aria's local atomic forget/revoke fence belongs to the DB author and remains separately required.
No source lease selects an account, changes UI year, writes a profile, or authorizes a mutation.

## Example sequence

Fluidify, after signed bridge v2 negotiation:

```json
{"name":"source_capabilities","args":{}}
{"name":"source_lease","args":{"version":1,"capability":"<returned>","tool":"history_records","arguments":{"account":"<exact>","from":"0","until":"1000","limit":20}}}
{"name":"source_read","args":{"version":1,"capability":"<same>","lease":"<returned>","tool":"history_records","arguments":{"account":"<exact>","from":"0","until":"1000","limit":20}}}
{"name":"source_validate","args":{"version":1,"capability":"<same>","lease":"<same>","tool":"history_records","arguments":{"account":"<exact>","from":"0","until":"1000","limit":20}}}
```

CV replaces the inner tool/arguments with e.g. `registro_dati` and
`{"account":"<exact>","studente":"<exact>","anno":"2026-2027","sezione":"agenda","limite":20}`.
Supply bridge `protocol:2` and `capabilities:["typed_results"]` on run requests.
A proof's `scope.arguments` is the source-captured exact logical argument object; preserve it.
Keep limit omitted if originally omitted (original defaults remain Fluidify 50 / CV 20).
For Fluidify fragments dispatch `source_read` with tool=`result_fragment` and original fragment
arguments; validate before commit against the original scope tool/arguments, not result_fragment.
No proof renews an expired immutable fragment or cursor. Restart bounded acquisition when expired.
The source TTL is 60 seconds from capability issuance (lease acquisition does not extend it).
Eviction (128 capabilities, 256 leases or fragment bindings) also rejects old handles.
Failures are typed ERROR with no data/sourceProof; missing/old version, caller, capability,
lease, wrong scope and unavailable source never authorize consumption.

## Source handoff and executed validation

ROOT explicitly transferred the sole compiler runner after the DB author's release. All
owned invocations have now ended; the source owner releases the compiler runner back to ROOT.
Every invocation used Android Studio JBR, --no-daemon, --max-workers=1 and --console=plain.
No PampAI builds, native builds, source suppressions, device work, live APIs or real-account
access occurred. No scoped compilation/test defect required a source change during validation.
The API and implementation remain exactly as specified above, including authorization UUIDs.

All **55 assigned tests executed and passed**: 26 SourceProofRegistryTest cases in each app,
2 RegistroSourceProofTest cases, and the added sourceProofToolsAreInActualRegistryWithoutWeakeningWriteConfirmation
case in FluidifyToolValidationTest. They are included in the app/assistant counts below, not
additional to those totals. `new-tests-evidence.json` records every assigned testcase and XML.

| Downstream suite | Tests / failures | Current invocation |
|---|---:|---|
| Fluidify app bridge/data/auth | 115 / 0 | Executed |
| Fluidify core | 18 / 0 | UP-TO-DATE; existing result reused |
| Fluidify engine-ai | 244 / 0 | Executed |
| Fluidify engine-ai-bridge | 26 / 0 | UP-TO-DATE; existing result reused |
| CV core-assistant | 62 / 0 | Executed |
| CV core-domain | 53 / 0 | Executed |
| CV core-data | 181 / 0 | UP-TO-DATE; existing result reused |
| CV core-datastore | 33 / 0 | Executed |
| CV core-network | 137 / 0 | UP-TO-DATE; existing result reused |
| CV engine-ai-bridge | 25 / 0 | Executed |

The first Fluidify test invocation compiled app/core and passed. The subsequent assemble
invocation failed app compilation outside the proof scope, in AirPodsModels.kt,
AirPodsProperties.kt, AirPodsAutomationCoordinator.kt and AirPodsStartGate.kt. Diagnostics
include unresolved property constants/modelDeclared, type inference and nullable receiver/type
mismatches. These files were not modified or excluded by the proof owner. This later result
prevents a claim that the current complete Fluidify app assembles. No fresh Fluidify APK is
claimed; app lint is blocked by that compilation failure.

Fluidify core lint reproduces **3 UnsafeOptInUsageError errors / 9 warnings**, in
AudioReactive.kt:428, PlayQueue.kt:404 and QueueItems.kt:43, consistent with the earlier
validation-S3-R3-build-lint.log baseline. Fluidify engine-ai lint passes (0 errors / 1 warning)
and engine-ai-bridge lint passes (0 errors / 2 warnings). No suppression was added.

CV app assembleDebug **passes**. CV core-assistant lint passes (0 errors / 6 warnings),
and app lint passes (0 errors / 84 warnings). Reports contain no findings in the owned proof
implementation/provider files. The historical full engine-ui lint baseline was not rerun;
scoped lint success does not claim a clean full-repository lint run.

Source files: Fluidify app bridge/SourceProofRegistry.kt and FluidifyToolHost.kt; CV
core-assistant pampai/SourceProofRegistry.kt, RegistroSourceProof.kt, RegistroToolHostProvider.kt,
tools/AssistantToolContext.kt and core-assistant/build.gradle. Tests: SourceProofRegistryTest.kt
in each downstream, CV RegistroSourceProofTest.kt and Fluidify FluidifyToolValidationTest.kt.
CV depends on existing core-datastore only for its already-published in-memory auth identity;
no auth/datastore/DB implementation was changed. The duplicate proof implementations remain
identical except for their package declarations. Both engine pins remain
3.0.5 / a055257fc4b559bee54b9ce114f265bcd237adfd.

Evidence is archived separately in each downstream's `docs/validation-source-proof/`:

- Fluidify: fluidify-plan.log, fluidify-tests-1.log, fluidify-assemble-lint.log,
  tests-summary.json, new-tests-evidence.json, junit/, lint-summary.json,
  core/engine-ai/engine-ai-bridge-lint-results-debug.xml, source-artifact-manifest.json,
  native-before.json, native-after.json and runner-release.json.
- CV: cv-tests-1.log, cv-assemble-lint.log, tests-summary.json, new-tests-evidence.json,
  junit/, lint-summary.json, core-assistant/app-lint-results-debug.xml,
  source-artifact-manifest.json and runner-release.json.

The native manifests verify all **7 existing Fluidify native outputs byte-identical** before
and after validation. Native task execution was explicitly excluded, not treated as tested.
The CV artifact manifest records the APK SHA256 and size from the successful assemble.
Logs are PowerShell UTF-16; JSON manifests and this contract are UTF-8.

Reproduction commands, from the respective repo (CV from android/), with
JAVA_HOME=C:/Program Files/Android/Android Studio/jbr:

```powershell
# Fluidify; native exclusions are mandatory for this source-only assignment.
$nativeExclusions = @('-x', ':core:cargoBuild', '-x', ':app:configureCMakeDebug[arm64-v8a]', '-x', ':app:buildCMakeDebug[arm64-v8a]', '-x', ':app:configureCMakeDebug[x86_64]', '-x', ':app:buildCMakeDebug[x86_64]')
./gradlew.bat --no-daemon --max-workers=1 --console=plain :app:testDebugUnitTest --tests 'dev.lelonio.square.bridge.*' --tests 'dev.lelonio.square.data.*' --tests 'dev.lelonio.square.auth.*' :core:testDebugUnitTest :engine-ai:testDebugUnitTest :engine-ai-bridge:testDebugUnitTest @nativeExclusions
./gradlew.bat --no-daemon --max-workers=1 --console=plain --continue :app:assembleDebug :app:lintDebug :core:lintDebug :engine-ai:lintDebug :engine-ai-bridge:lintDebug @nativeExclusions

# CV android/
./gradlew.bat --no-daemon --max-workers=1 --console=plain :core:core-assistant:testDebugUnitTest :core:core-domain:testDebugUnitTest :core:core-data:testDebugUnitTest :core:core-datastore:testDebugUnitTest :core:core-network:testDebugUnitTest :engine-ai-bridge:testDebugUnitTest
./gradlew.bat --no-daemon --max-workers=1 --console=plain --continue :app:assembleDebug :core:core-assistant:lintDebug :app:lintDebug
```

Executed static checks also confirm implementation/test parity and whitespace. No result here
establishes signed cross-app runtime, upstream authorization, process death on hardware,
profile/job integration acceptance or next-phase acceptance. These remain NV. DB freshness
renewal, local atomic revoke/forget and worker/UI integration remain separately owned work.
