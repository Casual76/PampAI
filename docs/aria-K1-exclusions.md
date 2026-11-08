# K1 R1 A ? exclusions, canonical evidence and consumed-record limits

Source freeze/release to ROOT: 2026-11-08. All seven files below are released. ROOT remains the sole compiler runner. No Gradle, Java, build, lint, tests, device, provider/network, commits, pushes or delegates were run. No schema/entity/migration change; schema 7 retained. ROOT's F7 `acceptedItems` / `USER_STATEMENT` filtering and its source/evidence checks remain present. ROOT-owned CorrectionCommitTest and ResearchConnectionsPersistenceTest were untouched. B-owned runner/runtime/chat and ROOT-owned UI/ResearchConnections were untouched.

## Implementation

- Replaced no-argument wildcard exclusion gates with persisted ResearchStep.query read/grant-scoped gates for creation, retry, claim, active lease validation, snapshot, checkpoint and completed-result retrieval. This metadata survives restart independently of mutable UI plans.
- Added app-owned ExclusionScopes: canonical source fields come from actual SourcePages/ResearchRead identities. All history read variants are an equivalent family; playlists/details/tracks form another family; CV sections are separate. Record exclusions conservatively block the related family because sources expose no exclusion-aware record filter and aggregates can disclose forgotten facts. Source/account/student/year aliases cannot bypass an exclusion by changing area. Explicitly unmapped exclusions remain global. Legacy/unknown read identities remain conservatively blocked when a matching scoped exclusion cannot be narrowed safely.
- Exclusion mutation keeps the global learning-epoch cancellation of preexisting jobs and scrubs checkpoints/objectives. It also deletes equivalent-family evidence and scrubs dependent profile values, including aliases. New unrelated jobs are admitted against the new epoch. Optional profile writes to globally excluded fields are skipped, preserving valid unrelated answers and F7 correction behavior; excluded dependencies still reject.
- ResearchScheduler evaluates each area's explicit reads via the same readAllowed API, so a music exclusion does not permanently suspend independently authorized CV work. Global policy generation changes can still cancel/recreate existing unrelated work conservatively.
- Added EvidenceAcquisition.canonicalRead/canonicalProvenance. Canonical provenance retains source identity, semantic query arguments, tool, coverage and limitations. Only acquisition controls (refresh permission, refresh flag and continuation/detail controls) are normalized. Equality on content/type/unit/hash, grant/record/period/field and canonical provenance is retained; unique-key collisions cannot silently resolve to different content/scope. Legacy schema7 provenance is normalized for comparison without rewriting records or requiring a migration.
- SourcePages stores exact full ResearchRead, grant, generation and lease in cursor.acquisition, including fragmented checkpoints; evidence retains semantic read provenance. Durable commit validates exact checkpoint acquisition against the immutable step read and captured source generation, and validates incoming/reused references against semantic read identity. Signed source proof validation remains ResearchSources' responsibility and is not conflated with semantic canonical equality.
- maxRecords now counts the distinct union of all canonical rows belonging to the job, all proposed evidence IDs, and references across existing/proposed steps. Reused IDs count even when insertion is ignored. Overlap counts once. Checkpoints cannot discard prior consumed references to evade later accounting. Snapshot also rejects oversized legacy consumed unions. Failure occurs before checkpoint/evidence/profile writes in the transaction.

## API and required B/ROOT integration

New public API: `suspend DurableFoundation.readAllowed(read: ResearchRead): Boolean`. It checks persisted exclusions only; it does not authorize sources, renew proofs, or grant consent.

**B integration required before declaring the complete K1 flow integrated:** call readAllowed before source renewal/acquisition/read and recheck after accepting source responses, including temporary consultation. ResearchSources and ResearchRunner were outside A's edit ownership; their source transport paths currently do not call this new API. Durable jobs and scheduler are guarded here, but the transport/temporary admission seam must be connected by B/ROOT. Keep proof/account/version/learning/cancellation checks in addition to this gate.

**B/ROOT profile consumer compatibility:** canonical provenance now contains a semantic ResearchRead with refresh/continuation controls normalized. ResearchProfile's existing exact `storedRead in allowedReads` comparison must compare `EvidenceAcquisition.canonicalRead` on both sides. This helper is internal to core-assistant and available to that consumer. Renewing semantic reads must not turn on technical refresh. No profile/runtime file was edited by A.

ROOT/B should also retain acquisition metadata when rebuilding a cursor during runner recovery. SourcePages preserves it during normal fragment/page continuation; a fresh complete page writes a new exact acquisition binding. Existing legacy referenced checkpoints with no acquisition binding fail closed when recommitted; no proof binding is fabricated.

## Prepared regressions (unexecuted)

New `ScopedExclusionsAndCanonicalEvidenceTest` uses real Room and actual SourcePages conversion with synthetic source results:

1. Two authorized sources, record/field music exclusion, equivalent aggregate/artist-count rejection, independent CV positive, late lease/commit rejection and unmapped global block.
2. Source exclusion with unrelated-source positive; revocation rejects late commit.
3. Same CV canonical record with refresh false -> true, including a legacy full-read provenance row: same ID/content, exact new acquisition checkpoint, successful reuse/snapshot; wrong acquisition binding, changed content and changed field/scope reject.
4. Seed R1/R2, maxRecords=1 rejects two reused IDs atomically; page union rejects the second distinct reused ID; overlapping page/step references succeed without double-counting; cross-step union rejects distinct ID 2.
5. Forgotten raw record scrubs equivalent aggregate evidence and its dependent profile value, denies retrieval, and permits an independent CV snapshot.
6. File-backed Room close/reopen preserves scoped exclusion and immutable read plan; unrelated claim/read remains usable.

Added `ResearchSchedulerTest.fieldAndRecordExclusionsSuspendOnlyTheRelatedAreaAfterReopen`: two authorized areas, scoped music suspension, CV enqueued/eligible after recreating scheduler/store, unmapped broad suspension, no runner/source dispatch.

Validation performed: source/call-site inspection, seven-file trailing-whitespace scan, git diff --check (no whitespace errors; shared-checkout CRLF warnings only). No compiler or test outcomes are claimed. The new source tests are triggers for ROOT's later authorized execution. Existing legacy synthetic tests without ResearchRead envelopes remain supported when no matching exclusions exist.

## Frozen files (SHA-256)

- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt` ? `e624cb86770c53bed0a23211ec6f43129e6df1c66d70941f5bd169a83ba39aba`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/ExclusionScopes.kt` ? `cb62fdec37d320e894b0f42762c9a107bb8d3c9ea6e3c3287d471dd1da4e9631`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/EvidenceAcquisition.kt` ? `ead3d69afe16796f54a5dc0e0cdf8025f31cd74952460201997f95993c6dece9`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/SourcePages.kt` ? `1fe0a5584a55b927f084e06e4595603a34ce2b9256ab8f49806706eca33df39c`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchScheduler.kt` ? `6b8fe0a44362ff89bef61eb917adc6c33f2038c2648b060d6f0ac4a1a59b1850`
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ScopedExclusionsAndCanonicalEvidenceTest.kt` ? `b9fbc9ba4cbf36618f346f4ab8ea947174c8be2caf1c7530de4d9798117aab93`
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ResearchSchedulerTest.kt` ? `84b0d2f3809d25e19a50b39ae35086febe4441ea1f2e1492fe8d3ac641d61338`
