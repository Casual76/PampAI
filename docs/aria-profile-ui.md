# Profile UI contract and handoff

Current status: ROOT transferred the compiler after core release; validation is complete and all feature sources/processes/runner are released back to ROOT. See [aria-profile-ui-validation.md](aria-profile-ui-validation.md) for executed counts, artifacts, hashes and limitations. The SOURCE ONLY notes below are retained as the earlier handoff history.

Feature owns new `profile/` screens, ViewModel and tests, plus narrow root/drawer/settings entry changes. Legacy MemoryRoute and chat/session draft ownership remain intact. Profile hub -> facts/deductions + details/correction/forget, sources, per-area updates, exclusions, explicit memories/reminders. Reading width 760dp, FluidScreen/grouped glass surfaces and shared host.

Required actual service boundary (please expose from ResearchProfile; no UI DAO access):
- `management: Flow<List<ProfileItem>>`, `settings: Flow<List<AreaConfiguration>>`, `grants: Flow<List<SourceGrant>>`, `exclusions: Flow<List<ProfileExclusion>>`.
- `suspend detail(id: String): RetrievedProfile?` for management provenance, including invalid/partial state (not prompt retrieval).
- `correct(id,field,value,conversationId=null)`, `forget(field,grantId=null,recordKey=null)`, `exclude(ProfileExclusion)`, `reenable(key)`.
- Persisted `AreaResearchPlan(configuration: AreaConfiguration, policy: ModelPolicy, reads: List<ResearchRead>, maxRecords: Int)`; `suspend plan(area): AreaResearchPlan?`, `suspend savePlan(plan)`, `suspend disable(area)` via invalidation. Read plan must survive process death; no UI-only boolean.
- `suspend configureGlobal(cap: BudgetCap)` delegates existing budget service (reject overlapping prior unknown traffic); UI uses explicit future window, additional background/area caps.

ResearchSources actual contract already inspected: discover(): List<SourceCandidate>, connect(read,reconnect=false): SourceGrant, renew(reads), revoke(grantId). Explicit user identity selection only, no account adoption on failure. UI discovery errors are visible, missing typed sources explain that ordinary tools remain available.

Model catalog is existing ModelSelection.state.options, READ ONLY; never choose/save conversation models from area setup. Area policy starts null regardless of last conversation choice. All defaults off, zero budget, setup incomplete. Exact read scopes selected explicitly; no guessed whole-account authorization. UI shows autonomous model work paused for unknown tariffs/bounds; enabling policy is not an execution success. Root must wire scheduling before claiming active recurrence.

Pending final actual API reconciliation, test fixtures and source static checks. No executed tests/screenshots/builds claimed.

## Source freeze / handoff, 2026-11-08

Feature source is stable for ROOT's later compiler transfer. **No compiler/test runner ownership was acquired, and no build/test/screenshot capture ran.** Core author still owns the serial runner. No processes were started by this topic. No device/provider/credential/config-store access, commits or push. All work outside feature listed below was read-only; this document is the authorized coordination exception.

Actual APIs reconciled against current production source:

- ResearchProfile.management/settings/grants/exclusions Flows, detail, correct/forget/exclude/reenable. Exclusions also has the actual suspend snapshot helper; no raw DAO is used.
- ResearchSources.discover/connect(ResearchRead,reconnect)/renew(List<ResearchRead>)/revoke. SourceSelection and SourceCandidate use the actual research package. Connect uses immutable explicit identities; failed calls do not publish a connected read or close the editor.
- ResearchSetup.plans and **caps: Flow<List<BudgetCap>>** are observed together. Saved caps are displayed and prefill the editor on reopening; edits preserve the selected cap ID. configuration/models/plan read existing persisted choices. configure(false) precedes savePlan and configure(final) so partial multi-store failure leaves the area off. AreaReadPlan persists exact reads, primary/helper/effort, profilingConsent and maxRecords.
- ModelSelection.state.options is read-only. No conversation model selection/save call. DurableFoundation.grants is only a high-level snapshot helper; feature never accesses PampaiDatabase or DurableDao.
- Verified downstream SchoolDataQuery.Sections: grades, agenda, lessons, communications, absences, notes, materials, documents. Human Italian labels map to those exact keys. Fluidify read areas match the actual core allowlist, including exact playlist ID where required and bounded history periods. Default source batch sizes remain 50 music / 20 school.

Changed feature files (relative to feature-assistant/src):

- main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfilePresentation.kt
- main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfileViewModel.kt
- main/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfileRoute.kt
- main/kotlin/dev/pampa/pampai/feature/assistant/profile/SourcesScreen.kt
- main/kotlin/dev/pampa/pampai/feature/assistant/profile/UpdatesRoute.kt
- main/kotlin/dev/pampa/pampai/feature/assistant/PampaiRoot.kt: profile routes and shared profile VM; legacy memory route stays `memory`.
- main/kotlin/dev/pampa/pampai/feature/assistant/history/AriaDrawer.kt: profile entry label only in this topic.
- main/kotlin/dev/pampa/pampai/feature/assistant/settings/SettingsRoute.kt: profile entry label/destination.
- test/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfileViewModelTest.kt
- test/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfileNavigationTest.kt
- test/kotlin/dev/pampa/pampai/feature/assistant/profile/ProfileScreenshots.kt
- test/kotlin/dev/pampa/pampai/feature/assistant/chat/ExperienceArchitectureTest.kt: footer stability fixture updated to new profile label.

Legacy MemoryRoute/MemoryViewModel and all chat/overlay/model-picker/draft/queue implementation files are untouched by this topic. Existing concurrent changes in shared files were preserved.

Prepared, NOT executed:

| Fixture | Cases / expected observation |
|---|---|
| ProfileViewModelTest | 12 cases: unconfigured off/no last-chat model; explicit exact plan save order; partial save stays off; failed connect no adoption; mismatched identity before service; renewal versus revoke; correct/forget/re-enable canonical services; partial/invalid/loading plus load failure/retry; explicit scope/budget/period; prior unknown traffic window refusal; actual school sections/year; pending connect with queued revoke not dropped. |
| ProfileNavigationTest | 1 real FluidNavHost fixture: editable synthetic home draft -> hub -> sources -> back -> legacy memory -> hub -> home retains draft. This is a navigation boundary fixture, not a full ChatViewModel runtime claim. |
| ProfileScreenshots | 12 methods: 11 planned PNG captures plus hub dispatch fixture. Light/dark profile, partial deduction, light/dark empty, sources, provenance, 2x font, 1024dp wide, 320dp compact setup, 1024dp/2x/dark setup. All values/accounts are synthetic. No PNG exists from this topic yet. |

Executed static checks: git diff --check on feature passed; source scan of the five new production files found no raw DB/DAO, WorkManager, AiHttp, AssistantRuntime, or conversation model choose/save references and no trailing whitespace. Source/API reconciliation confirmed all callable methods and Room-backed caps flow in actual core files. These are text/static checks only; Kotlin type checking, Hilt, Compose behavior, bounds/layout assertions and fixture outcomes remain **NV** until ROOT transfers the runner.

Product states remain honest: default null model/zero budgets/off/incomplete; monetary cap requires verified tariff/currency (currently paused), explicit request/token-only choice provides no money guarantee; unknown bounds are not reported supported. Saved authorization is labelled authorization, never running background. Missing next/last timestamps stay unavailable. Windows are explicit, do not renew implicitly and are not provider quota resets. Connecting/re-enabling never enables scheduling. Root's following worker/startup topic must replace the explicit unavailable scheduling state with actual scheduler/job status before claiming recurrence is active. No spontaneous notifications/messages are introduced.

Feature source ownership is released to ROOT at this freeze. Compiler ownership remains with the core author; this report requests the later explicit transfer only after their release. ROOT owns integrated verification and WorkManager/startup. No integrated approval is claimed.
