# K1 correction R1: routing and durable chat retry

Status: SOURCE FROZEN / owned files released to ROOT. Prepared tests have NOT been compiled or executed. ROOT retains the sole compiler runner; no Gradle, Java, build, test or lint was invoked. Runtime/device/provider validation remains NV, including the old runtime. No delegates, commits, pushes, devices, live providers, secrets, engine changes or migrations.

## F3: positive private-source intent

ResearchQuestionPlanner requires personal musical/school intent or an explicit consultation request naming the source before selecting private fields. Domain words, brand definitions and deep analysis alone do not qualify. Natural questions such as `come vanno i miei voti?`, `come vado a scuola?`, `quali brani ascolto?`, `quale musica preferisco?` and multi-source personal comparisons remain eligible. `cosa devo fare nella vita?` remains ordinary chat. The classifier is deliberately conservative and deterministic: unsupported natural phrasings can remain ordinary chat.

ResearchForeground applies the planner before source authorization resolution, including explicit task metadata. Answer admission rechecks positive intent and the exact authorized pertinent reads before temporary or durable collection. AssistantEngine intersects any supplied profile query with positively planned fields before targeted retrieval. Deep intent changes collection depth only. Quick commands retain their existing earlier precedence; selected exact model/effort/helper policy is preserved.

## F6: reachable persisted retry

AssistantRequest now carries `retryResearch`. ChatViewModel sets it for an interrupted/failed assistant response with no explicit provider override. Existing generation, conversation, preflight and submitIfCurrent fences remain in place. Completed regeneration or an explicit provider/model override creates a fresh research job. Changing the selected policy while retrying a saved job fails with CAPTURED_MODEL_MISMATCH rather than silently substituting a model.

Before the first collection tranche, ResearchForeground invokes onAdmitted. AssistantEngine uses it to persist a research_checkpoint run with backwards-compatible groupsJson metadata `{researchCheckpoint:{questionId,jobId}}`. The association contains no evidence or objective text. Conversation/message ids are already the Run row foreign keys. It is stored before acquisition, retained through trace compaction, and looked up from the exact replaced assistant response. Checkpoint marker rows are hidden from conversation trace presentation. They are still Run rows and therefore contribute to the existing raw run-count total.

On retry, ConversationsRepository checks conversation membership, assistant role, parent question and failed/cancelled/interrupted status. ResearchRunner validates captured objective, conversation, foreground execution, exact policy and read set before collection. Current authorized scopes must still allow every read. Source revocation and forgotten/invalidated jobs fail closed. The retry links the new assistant sibling to the same job before continuing. Completed jobs can supply saved synthesis to a failed/interrupted answer without repeating acquisition or model generation; a completed chat response is never treated as retry. Job ids are never parsed from prose.

Temporary consultation exits before onAdmitted, so no research association, durable job or profile is created. Repository association additionally rejects temporary conversations. Existing operation-ledger attempts and no-replay code remain intact. No runtime ownership transfer or cancellation policy changed.

## Synthesis union and A boundary

ResearchRunner counts the deduplicated union of snapshot evidence and targeted profile dependencies against job.maxRecords before model execution. Exceeding it suspends with RECORD_LIMIT. The profile text is rendered from that same captured dependency set; no second uncounted profile block is added. Dispatch rechecks profile item/evidence content while allowing technical proof renewal. A owns SourcePages, DurableFoundation, optional DurableEntities, exclusions and scheduler. None were edited here. Integrated A's public foundation.readAllowed API before and after renewal/acquisition/response and model dispatch/response, including temporary consultation. Cursor recovery preserves the saved acquisition object verbatim alongside consumed evidence, without fabricating a binding. ROOT owns ResearchSources/ResearchProfile and their additional exclusion/canonical-read gates. This also uses existing snapshot.evidence and job.maxRecords APIs. A's canonical acquisition and reused-reference union accounting remains necessary and independently owned.

## Prepared regression coverage

All additions are in ResearchConsumerTest, using existing Robolectric/Room and typed fake source transport. NOT RUN:

- generalDomainWordsNeverCrossForegroundOrProfileBoundary: connected grade/music sources, general vote/music/artist/brand/deep negatives, zero additional transport calls; natural personal, explicit-source, deep and multi-source positives.
- persistedChatRetryResumesBeyond64PagesWithoutInitialReread: actual file-backed Room database closed/reopened after 64 pages; persisted association loaded through repository retry admission; same job continues pages 64..69 and synthesizes once. Exact acquisition sequence 0..69 is asserted. Includes completed-job result reuse without additional model/read calls, changed model, conversation, objective, turn and scopes rejection, plus source revocation.
- temporaryForegroundDoesNotPersistAssociationAndCompletedRegenerationDoesNotRetry: real temporary foreground execution never calls association callback or writes Run association; completed assistant regeneration returns no retry association.
- targetedProfileDependenciesCountAgainstSynthesisRecordLimit: real acquired record plus a different stored profile dependency exceed maxRecords=1; no second model call.
- temporaryConsultationRejectsExcludedReadBeforeAcquisitionAndLateModelResponse: exclusion prevents any acquisition and rejects a response excluded during fake model execution.
- forgottenSourceCheckpointCannotBeResumed: saved checkpoint invalidated by forget rejects validation with no further source read.

Existing ResearchConsumerTest personal-grade and targeted-profile cases are retained. Actual AssistantEngine execution and ChatViewModel lifecycle were source-reviewed only; the prepared resume test exercises their repository/foreground/runner admission boundaries, not a fully instantiated ViewModel or Engine. Existing ownership/queue/operation tests should be run by ROOT after both writer freezes. Source syntax/typechecking and all new assertions remain unverified until ROOT's compiler handoff.

## Frozen owned files

- core-assistant/.../research/ResearchQuestionPlanner.kt
- core-assistant/.../research/ResearchForeground.kt
- core-assistant/.../research/ResearchRunner.kt
- core-assistant/.../db/ConversationsRepository.kt
- core-assistant/.../runtime/AssistantRuntime.kt
- core-assistant/.../runtime/AssistantEngine.kt
- feature-assistant/.../chat/ChatViewModel.kt
- core-assistant/src/test/.../research/ResearchConsumerTest.kt
- docs/aria-K1-routing-resume.md

ResearchContracts, ChatMessages and RunSteps needed no edits. ROOT profile/compiler/ResearchConnections files and A-owned files are untouched by this implementation. Shared-checkout pre-existing work is preserved.

## Final source freeze fingerprints

- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchQuestionPlanner.kt`: `07306c2c86388fbf310c58ae5d296684c2b7fd92714706f4cab0e53577ab919b`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt`: `750cadbce0b768ccd1c5c92055a330795cfe3d6d28154ddc8e860beda2ae2f4b`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchRunner.kt`: `8e172873d1b03c15fbf051239e1bf395c1e7a49567a317320739c87aa6cbffe8`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/ConversationsRepository.kt`: `72c047026e763d20fc6e1c9fe204c090e656071d23a680d6be60d60c5022da2e`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantRuntime.kt`: `ae558f0503329bac47898a5e8a69725ae75322d401e4deef0affe205c8508f85`
- `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt`: `bb2dbab423f1e5474bbc184f075751c7cee308a06623ae116c6b2df15fc4ade9`
- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatViewModel.kt`: `f39d983ba89e67bac54357eab62d4025b01e7863ed2bb434463b99bcba050433`
- `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ResearchConsumerTest.kt`: `c8e2d9c82e883b1ce8532815ea8d8dabf5e996c0abf3339cb2aa905c2afe8c95`
