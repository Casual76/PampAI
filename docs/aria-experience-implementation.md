# Aria experience implementation - R1/3 architecture increment

Source delivery, 2026-11-07. This is an implementation handoff to root, not a passing review or runtime claim. Read the full working architecture brief, accepted P2 and I1 findings, tested I1 UI foundation, installed Fluid SKILL/regole/navigation/list/modal/AI settings references and Material3 SKILL. No AGENTS.md found in repo, parents or owned modules.

## Navigation and layout mapping

| Working brief | Actual implementation |
|---|---|
| Focused settings | Hub: AI e modelli, Voce e assistente, App e azioni, Aspetto, Informazioni; memory/reminders linked separately. AI hub links separate keys/privacy, model defaults, provider order, preferences and usage pages. App/actions has real action/local command toggles and dedicated connections, permissions, trusted-action pages. No long combined appearance/AI/voice list. |
| Direct recovery | Composer missing-key action opens `settings?section=chiavi`. Existing settings chips map to actual category routes, including keys, models, provider order, preferences/fallback, voice, actions, permissions, trusted actions, connections and appearance. No 30-step scroll sweep or lazy-key scanning. Each small page starts at its own relevant content; privacy follows keys, default order is its own page. Unconfigured model/order pages link honestly to key setup. |
| Back continuity | `openSettingsSection` inserts a real hub entry before a directly opened category; sibling category changes replace the current category. Category Back (toolbar/system/Fluid edge pop) reaches hub; hub Back returns to the existing home entry. Existing composer Saver, chat scroll, ViewModel and consumed entry stamp are retained; navigation methods contain no submit/regenerate/new-conversation operation. |
| Preserve settings | Existing engine appearance controls; Aria enable/consent; encrypted provider keys and OpenRouter details/data policy; chat/STT orders; tier/default/STT models and catalog refresh; thinking/failover/recent requests; quick commands/actions; voice/STT/TTS/start-in-text; system-assistant role; permission/trusted-action/connection sections; usage/memory; version/source/updates/licenses all retain their real implementations. |
| Drawer hierarchy | Stable top new-chat then search; history/results take the weighted remainder; fixed secondary memory/reminders and settings group below history. Removed redundant title overhead and usage destination from drawer; usage lives in AI settings. Existing rename/pin/delete/search-result behavior retained. |
| Composer intent | Attachment section camera/photos/files; separate Conversation section context/plugins and explicit New temporary chat. Removed attachment reasoning entry and its competing pill. Temporary action always starts a new temporary conversation through existing ViewModel method, including when already in a temporary chat; no relabeling stored chat. Existing shared app/overlay Composer layout and screenshot entry ordering remain. |
| Picker hierarchy | Same real ExactModelControl used by app and SessionModalRoot. Selected primary and active attempt have distinct labels. Effort chips wrap before search/catalog and explicitly apply to subsequent sends. Separate optional visual-helper disclosure preserves explicit consent; YES/NO/UNKNOWN capability filtering retained. Favorites/recent/all headings replace per-row labels. Inner catalog bounded to 280dp; outer sheet remains scrollable above IME and at large font. Exact provider/model visible as secondary text; favorite checkbox semantics and 48dp targets retained. |
| Materials/content | Existing Fluid glass modal host, composer and theme retained. Focused settings and fixed drawer secondary group use actual FluidListGroup/FluidListRow glass; no new decorative hero. Response/receipt surfaces untouched. |

## Tests and evidence

**No Gradle/build/test/lint execution in this task.** Runner remained exclusively with downstream validator. Root explicitly directed source/fixture handoff without waiting if no transfer arrives. No device, provider, live API, credential access, subagents, commits, pushes or publication. No profile/background implementation. Source is stable for integration.

Existing feature regressions remain in place, including SessionModalRoot picker, helper recovery, favorite semantics, suspended-send edits, DraftHandoffs and typed policy/ledger cards. The previous feature29/core140 green result predates these changes and is not a result for this increment. Core sources/tests were not changed.

Added/updated meaningful fixture coverage (all **UNRUN**):

- `ExperienceArchitectureTest.composerKeysRecoveryAndCategoryBackPreserveDraftAndViewportWithoutSend`: real composer warning and production navigation operations in FluidNavHost, real category shell and engine keys controls, home composer Saver plus list position restoration, keys instead of appearance, category->hub->home and zero sends. This dependency-free graph uses the production route operations and shells; it is not the Hilt PampaiRoot/device.
- `ExperienceArchitectureTest.drawerSearchKeepsNewChatAndGlobalNavigationPositions`: production DrawerWorkspace positions compared before/after search result replacement.
- `ExperienceArchitectureTest.attachmentMenuSeparatesConversationAndHasNoCompetingEffort`: actual composer/menu in production SessionModalRoot; section labels, attachment entries, explicit temporary callback and absence of competing effort.
- `UiCorrectionsTest.realPickerGroupsSearchAndEffortBeforeLongCatalog`: real ModelSelection and SessionModalRoot; favorite/recent/catalog headings, catalog of 62 entries, requested effort persistence without changing primary, search for last model while effort remains available.
- Existing `UiCorrectionsTest` retained; short-viewport scroll locator now names outer sheet because catalog is independently bounded.
- `SettingsSectionKeysTest` retains default engine sections invariant and adds focused-keys-only item registration with verified providers. This complements rendered route tests, not their replacement.

Native Robolectric/Roborazzi screenshot fixtures added, **not captured**:

- `experience-hub-light.png`, `experience-hub-dark.png`, `experience-hub-large-font.png` in ExperienceArchitectureTest.
- `experience-picker-light.png`, `experience-picker-dark.png`, `experience-picker-large-font.png` in UiCorrectionsTest; actual production SessionModalRoot picker, not generated artwork.
- Destination is `SCREENSHOT_DIR` if supplied, else test working directory `build/screenshots` (normally feature-assistant/build/screenshots). Root should supply an absolute `SCREENSHOT_DIR` to archive new evidence unambiguously. No new PNG paths can be claimed as existing at this handoff.

Static checks executed: scoped git diff --check (clean after removing trailing blank lines); engine catalog generator completed successfully, 872 declarations/27 pages. Generated into TEMP and copied only ai-ui.md to preserve other owners' API families. No Kotlin compile, lint, API-wide freshness/check_docs/sync_docs verification has run; root must run these with integration tooling/runner. The checkout lacks the full skill scripts/family reference tree; installed skill generator was invoked with explicit engine-root.

Root validation pending: same approved isolated buildRoot/in-process/nonincremental/no-cache/no-daemon/max-workers=2 flags; feature/core tests including all prior29/140, app assemble, feature/app lint and affected engine-ai-ui lint/tests; `-Pscreenshots` and explicit screenshot output directory. Review actual PNGs and resolve fixture/build issues before two fresh independent integrated reviews. No pass count asserted here.

Remaining device evidence: real keyboard/narrow viewport/large-font picker reachability and nested catalog scrolling; app category back and same conversation/draft/scroll; drawer keyboard/global navigation; temporary chat lifecycle; overlay modal/back, draft+image handoff, exact active attempt/effort and explicit visual helper; TalkBack and glass/motion. Previously failed system-overlay driver must be resolved by root, not bypassed. Human learnability has not been tested.

## Additive engine API

`FluidAiSettingsPart` and trailing optional `parts: Set<FluidAiSettingsPart>` on `fluidAiSettingsSection`; default includes all groups, existing callers and default behavior remain source compatible. Slots still execute in their original order regardless of selected groups. No engine-ai, bridge or core changes. App controls remain attached to existing AssistantSettingsViewModel/stores, not duplicated preference state. API family catalog regenerated; broader engine docs/checks remain root integration work.

## Exact manifest for this task

Only the following files were written by this increment (plus this report). Other dirty files and prior changes within these files predate the task and remain intact. No app/ source change was needed.

- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/PampaiRoot.kt` SHA256 `96698ea51536e10d5ef5d0a6fcec22d5b46f1086bb7da250800b415fda03eed8`
- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/settings/SettingsRoute.kt` SHA256 `2eabac85a4a8d5941c48b0e497d9e588a4d4c2ca6ccf30e19f1f0adbefd93118`
- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/settings/AssistantSettingsItems.kt` SHA256 `8d43a7920b3e035766d8e4eaab7f3af066a1028ee9ea235935037ecd4c836483`
- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/history/AriaDrawer.kt` SHA256 `c48ebab07fb494122454f230696e29f61989b26fa6f0327df767df790e55a6b3`
- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatComposer.kt` SHA256 `8b94d122d40f0519e8d5009f10e32ba7b410d9934116eb5ffded9938913b0f00`
- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatRoute.kt` SHA256 `9284364330a92c0659f4ed26857be43b1f8f868ab0521f3a2bb5fabcfabaa9f6`
- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ComposerMenus.kt` SHA256 `effa246edb2ad1768f63191206717517932bc3a1daa1337e222bd092647e588c`
- `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ExactModelPicker.kt` SHA256 `15f41ae7121f99ac70077247316abbc04e153dbc4b3a2b8f7c739f9e48ff9a94`
- `feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/settings/SettingsSectionKeysTest.kt` SHA256 `bdf9fbe8e3028dfd926aa9b1e5718b8fe4fb6c4340f109f88f8aa9745906cd3c`
- `feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/UiCorrectionsTest.kt` SHA256 `b4ea6775f7503f91a450ee2431184d358eb7e2cd34aaddec7cae805ce852ef9b`
- `feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/ExperienceArchitectureTest.kt` SHA256 `4166cf4afc70a945a32d4a9eb5c5043e94dc1c2a6ab9a50227025ceaf8fdd5c4`
- `engine/engine-ai-ui/src/main/kotlin/dev/antigravity/fluidengine/aiui/settings/FluidAiSettingsSection.kt` SHA256 `c80ed1295f953244e2d6bd0852923c6b4c3e3aa1b704b074496117d8c93fecd3`
- `engine/skill/fluid-engine/references/api/ai-ui.md` SHA256 `ffd792cc8fba20808e9a9a12d0acf06bde191939bf0ba415d2f82b49cb73dfd3`

- `docs/aria-experience-implementation.md` (this report; no self hash).

Runner: never acquired, no Gradle process started. No runner retained; root can take over these stable sources immediately after downstream release.
