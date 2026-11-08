# Aria adaptive layout ? pre-I4 correction R3/3

2026-11-07. Implementation handoff; no I4 freeze or review approval is claimed. This remains the existing R3/3 correction; no counter reset. Profile/background remain UNSTARTED.

## Evidence and intent

Viewed the actual tablet baseline PNGs under `C:/Users/casua/AppData/Local/PampAI-qa/tablet-I4/`: `queue-source-model-retained.png`, `queue-gemini-after-groq.png`, `vision-helper-response.png`, `vision-helper-details.png`. Also viewed prior `I4-root/flow-dark.png`. The physical landscape captures show prose and composer spanning almost the entire window, with queue/model/actions spread across that width. The change gives the existing conversation a stable reading and control region; no new pane or settings architecture.

Read Fluid Engine SKILL, regole and schermata references, Material3 skill, and actual `FluidReadingWidth.kt` / `FluidScreenDefaults.ContentMaxWidth` sources. No applicable AGENTS.md was found in the project or its ancestors.

## Exact file manifest for this implementation

Paths relative to repository root. These files already contained other work; that work was retained.

| File | Change made in this task |
| --- | --- |
| `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatRoute.kt` | Import Fluid reading modifier; constrain conversation LazyColumn after body backdrop registration and composer host before its existing padding. |
| `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatTopBar.kt` | Center toolbar content using the same Fluid reading width; retain full-width progressive glass sibling. |
| `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/session/SessionOverlay.kt` | Constrain existing material panel to 640 dp before fill/material/input modifiers. |
| `feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/PampaiRoot.kt` | Add BoxWithConstraints around drawer sheet and set actual sheet width to min(86% of available width, 360 dp). Preserve drawer content, focus, backdrop, navigation and modal ownership. |
| `feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/chat/ChatScreenshots.kt` | Update existing stateless chat fixtures to matching Fluid width placement; add wide queue/control assertions, tablet prose capture and compact 1.8x text capture. |
| `docs/aria-adaptive-layout.md` | This handoff report only. |

Inspected but did not change ChatMessages, ChatComposer, AssistantComposer and AriaDrawer. No engine, source2 Fluidify/CV, core, app/runtime, dependency, domain, queue/send/cancel/attachment policy, shared modal host, handoff or model-policy changes. No commits, push, publication, provider/device/adb operations or credentials/signing reads.

## Bounds and modifier order

- Chat: engine default `FluidScreenDefaults.ContentMaxWidth = 760.dp`, through existing `Modifier.fluidReadingWidth()`. Existing 16 dp list padding gives 728 dp message content; 12 dp composer margin gives 736 dp capsule width. Toolbar controls use the same 760 dp envelope. Below that width, existing phone dimensions remain unchanged.
- Overlay: actual glass panel content/material constrained to 640 dp, centered within the existing 12 dp outer margins. Existing maximum height (85% of available height), bottom anchoring, navigation/IME padding and weighted scrolling stay intact. No extra inner composer cap.
- Drawer: actual sheet capped at 360 dp; compact windows retain 86% width. Applying this at the sheet avoids an oversized blank panel around capped children.
- Full-window background and ambient/backdrop layers remain full window. The chat body backdrop registration precedes its content cap; top-bar material remains on its full-width sibling. Existing composer/header measured heights and list vertical inset calculations remain intact.

## Validation

Sole runner used JBR (`C:/Program Files/Android/Android Studio/jbr`) and serial Gradle with the existing external build root. Command:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
$env:SCREENSHOT_DIR='C:/Users/casua/AppData/Local/PampAI-qa/I4-adaptive'
./gradlew.bat :feature-assistant:testDebugUnitTest :app:assembleDebug :feature-assistant:lintDebug :app:lintDebug -Pscreenshots '-Ppampai.buildRoot=C:/Users/casua/AppData/Local/PampAI-qa-build-full' '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' --no-build-cache --no-daemon --max-workers=1 --console=plain
```

Result: BUILD SUCCESSFUL in 2m 6s; 531 tasks, 36 executed / 495 up-to-date. Exit 0. Stable log: `C:/Users/casua/AppData/Local/PampAI-qa/I4-adaptive-build.log`.

Feature tests: **58 passed, 0 failed/errors/skipped**, across 12 suites. Includes 14 ChatScreenshots tests, 7 ExperienceArchitecture tests, 11 UiCorrections tests, 4 TextAdmissionOwnership tests, 3 DraftHandoffs tests and existing send admission / voice preparation coverage. Previous 55 feature tests retained plus 3 new layout fixtures. Core unchanged and core tests were not rerun; the earlier physical/core145 result is root-provided context, not verification by this implementation agent.

Lint: feature **0 errors, 16 warnings, 1 hint**; app **0 errors, 39 warnings, 1 hint**. Reports under `C:/Users/casua/AppData/Local/PampAI-qa-build-full/{feature-assistant,app}/reports/lint-results-debug.{html,txt}`. Existing deprecation/build warnings remain outside this layout change. `git diff --check` passed (only Git line-ending notices).

19 PNGs generated under `C:/Users/casua/AppData/Local/PampAI-qa/I4-adaptive`. Visually inspected:

- `adaptive-tablet-queue.png` ? 2560x1600 / 1280x800 dp fixture: centered composer/messages, queued question and stop/send controls visible; semantics assertions confirm composer width below 65% of viewport, centered within 2 pixels, and queue above composer.
- `adaptive-tablet-prose.png` ? same wide viewport, light theme: readable bounded prose, user/assistant hierarchy and shared toolbar/composer alignment.
- `adaptive-compact-large-type.png` ? 1200x2580 / 400x860 dp fixture, text 1.8x: model wraps, stop/send and plus remain visible, error actions wrap.

Pillow pixel comparisons against supplied I4-root fixtures: `chat-light.png`, `chat-dark.png`, `flow-light.png`, `flow-dark.png` are **pixel-identical**, all 1200x2580. Existing large-type control and measured-composer/queue assertions also passed.

APK: `C:/Users/casua/AppData/Local/PampAI-qa-build-full/app/outputs/apk/debug/app-debug.apk`, **100794929 bytes**.
SHA-256: `A8154BF3521D63DA3456E5802F3A90499B715022ED1C1A28BD565BC755633537`.

## NV / root follow-up

No device or emulator was touched. Physical tablet install/retest, real glass optics, keyboard/IME motion, overlay panel reach/height and drawer gestures are **NV** here. Robolectric screenshots use production rendering components in stateless fixtures; they do not prove real overlay window focus/input, native blur, or live provider behavior. The root-reported overlay driver failure (false escape then repeated COMMAND_FAILED) was not reproduced or resolved here. No claim of overlay driver recovery or independent reviewer approval.

The existing source2 S3 manifest has no feature-assistant/PampaiRoot entries, per task context. This report does not change that manifest or imply these changes are covered by its frozen review; root must include the manifest above in the final I4 review boundary.

Runner **released**: serial Gradle command exited successfully, owned exec session ended, no owned background helper/service started and no processes required termination. Other-session processes/devices were left untouched.
