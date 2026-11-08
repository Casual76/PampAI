# Profile UI validation and runner release

ROOT explicitly transferred feature writes and the sole compiler runner after the core author's release. This report supersedes the SOURCE ONLY / prepared-only status in aria-profile-ui.md. All owned invocations have ended; feature source ownership and the compiler runner are released back to ROOT now. Worker/startup remains the following topic.

| Executed validation | Result | Evidence |
|---|---|---|
| Full standard feature suite | 60 tests, 0 failures/errors/skips | validation-profile-ui/full-2.log and full-2-tests/testDebugUnitTest XML |
| New profile screenshot suite | 12 tests, 0 failures/errors/skips | screenshots-2.log and screenshots-2-tests XML |
| Feature lint | 0 errors, 18 warnings | feature-assistant-lint.xml |
| App lint | 0 errors, 39 warnings | app-lint.xml |
| App debug assemble and actual Hilt compile | Passed, no missing core binding | full-2.log |
| Frozen inputs | 233 compared, zero changes, schema 7 identical | frozen-inputs.json and results.json |

Standard Gradle configuration excludes screenshot classes. The 60 freshly executed standard cases include all 12 new ViewModel cases and the real Fluid route/back/draft fixture. The additional 12 profile screenshot cases contain 11 PNG captures and one hub dispatch fixture. No count is inflated by repeats or cached prerequisite tests. Core's 210 and engine's 264 tests remain earlier evidence supplied by their author; they were not rerun here.

APK: C:/Users/casua/AppData/Local/PampAI-qa/knowledge-ui/PampAI-knowledge-ui-debug.apk

Size: 99,652,004 bytes. SHA256: 8f205fbaaa11b7deb7704a46141261ddc19e235b7dc50db4c8661e7e0adb8ae6

Per-file feature/source/log/PNG/APK hashes, counts and invariant comparisons are recorded in validation-profile-ui/results.json. Lint XML and JUnit XML are archived in the same validation directory. No suppressions or Gradle edits were added; deprecation/toolchain warnings remain visible.

Retained failures: full-1.log stopped before compilation because PowerShell split an unquoted dotted Gradle property; the invocation was quoted correctly in full-2.log. screenshots-1.log and screenshots-1-tests contain 12 cases with one failure: partialDeduction looked for a row before LazyColumn composed it. The only source change during validation is that fixture's use of actual lazy-list performScrollToNode. Production UI compiled unchanged. The corrected complete screenshot suite and feature lint passed in screenshots-2.log. App production inputs were unchanged by the fixture fix, so the passed APK and app lint remain valid.

Eleven new synthetic profile PNGs were generated and individually inspected in C:/Users/casua/AppData/Local/PampAI-qa/knowledge-ui. Existing experience-*.png files there belong to earlier work and are excluded from fresh capture counts:

- profile-light.png and profile-dark.png: statement and observed-fact separation, readable grouped Fluid surfaces.
- profile-deduction-dark.png: scrolled deduction with explicit verification language.
- profile-empty-light.png and profile-empty-dark.png: honest empty state and temporary-chat exclusion.
- profile-sources-light.png: explicit source/account scope; connection does not imply background enablement.
- profile-provenance-dark.png: source, period, partial coverage, unknown freshness and readable structured evidence.
- profile-font-2x.png: rows wrap and the page scrolls at large type. Shared FluidScreen subtitle uses its existing ellipsis.
- profile-wide-dark.png: centered 760dp reading column.
- profile-setup-compact.png: 320dp layout, no implicitly selected model.
- profile-setup-wide-2x-dark.png: 1024dp layout at 2x font; primary/helper remain unselected.

Captures establish static synthetic appearance. Device keyboard, gesture, animation timing, source Binder authentication and live provider behavior remain NV. No screenshot uses real personal data. There is no claim of active background recurrence; root's following worker/startup topic must wire real scheduler/job status.

Commands used Android Studio JBR, external root C:/Users/casua/AppData/Local/PampAI-build, --no-daemon --no-watch-fs --no-parallel --max-workers=1, in-process Kotlin and nonincremental Kotlin/KSP. SCREENSHOT_DIR was the exact requested knowledge-ui directory. All four owned invocation sessions ended. Scoped process evidence is in process-release.json: no owned JBR Gradle/worker/Kotlin process remains. Unrelated existing Gradle9.3/Kotlin and a concurrent FluidWeather JDK17 Gradle8.14.3/Kotlin tree were identified by parent/command and left untouched; no broad stop was used.

No app/core/worker/engine/source/Gradle changes, devices, live APIs, credential/config-store reads, commits or push. Default setup remains incomplete/off with null model and zero budget. Explicit money-cap disablement is separate from unknown tariff/bounds status. This is build, fixture and static visual validation, not live/runtime approval.

All feature source ownership and the sole compiler runner are explicitly released to ROOT now. No owned process/session is left running.
