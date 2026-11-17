# 12 — Phase 2 regression results

Reference: unchanged `08-regression-test-plan.md`. Baseline HEAD: `f6eedf9`. Only executable configuration change: one opt-in local arm64 release script. No physical device was connected (`adb devices -l` returned an empty list). No app installation, launch, alarm trigger or performance benchmark was performed.

## Executed checks

| Check | Result / limits |
|---|---|
| Initial Git/HEAD/source inspection | PASS: HEAD matches Phase 1; only pre-existing untracked `docs/`. No tracked user edits overwritten. |
| Phase 1 report preservation | PASS: SHA-256 checks for all eight reports. |
| `npx --no-install tsc --noEmit` | PASS, exit 0, baseline and final. |
| `CI=1 npx --no-install expo lint` | PASS, exit 0, baseline and final. |
| `./gradlew :app:assembleRelease --console=plain` | PASS, unchanged four-ABI baseline; 25m 56s. Initial sandbox cache-lock error was environmental and resolved without changing configuration. |
| `./gradlew :app:bundleRelease :kt-alarm:testDebugUnitTest --console=plain` | PASS; 44s. Fresh universal AAB inspected. These are existing module unit tests, not device alarm tests. |
| Existing Kotlin tests | PASS: SquatCounterTest 13, SleepDeadlineTest 4, WalkCounterTest 11; total 28, zero failures/errors/skips. |
| `npm run build:apk:local:arm64 -- --console=plain` | PASS; 39s. Actual APK contains only arm64-v8a. R8 task up-to-date with unchanged inputs/rules. |
| Manifest comparison | PASS: decoded manifests identical. Raw manifest ZIP entry also byte-identical. Package `com.imtushar454.ktAlerm`, minSdk 24, targetSdk 36; release non-debuggable by default; cleartext flag absent. Alarm components/permissions/exported flags unchanged. |
| APK entry comparison | PASS: only 93 libraries for three other ABIs omitted; zero additions or changed common entries. All 31 arm64 native libraries, both DEX files, Hermes, models, resources and audio byte-identical. |
| Audio verification | PASS: all nine source WAV hashes match packaged bytes; unique source hashes, no unused duplicate removal. |
| `apksigner verify --print-certs` | PASS for preserved stale, fresh universal and fresh arm64 APKs; matching Android Debug certificate. Distributed/EAS identity remains UNVERIFIED. |
| Autolinking / registration | PASS: Android and Apple discovery include `kt-alarm`; generated Android provider contains `KtAlarmModule`. Final Android discovery/provider identical to baseline; `@expo/ui` remains linked. No Apple source/config edit. |
| R8 checks | PASS statically: mapping retains module, alarm components, CameraX and ML Kit; baseline/final mapping identical. Effective configuration includes existing ML Kit registrar and Room database rules. No keep-rule edit. This does not prove reflection paths work on device. |
| Release bundle inspection | PASS: Hermes bytecode magic verified; no `.map` files in APK. No claim about runtime dev-menu/logcat behavior without launch. |
| `git diff --check`; protected-tree diff | PASS: only one tracked line added in `package.json`; native, module, UI, iOS, app/EAS config and lockfile unchanged. |
| Approved `npm audit --omit=dev --json` | COMPLETED, exit 1: 3 moderate dependency entries for one transitive minimatch advisory; 0 high/critical entries. See 11; no remediation applied. |
| Expo Doctor | NOT RUN: not installed locally; dependencies were not reinstalled or upgraded to obtain it. |
| JS unit / instrumentation tests | NOT RUN: no configured JS test script or instrumentation suite. No test scripts invented. |
| Gradle dependency/OSV advisory scan | NOT RUN; no security clearance for all native transitive dependencies. |

Existing Gradle/AGP opt-out and Kotlin deprecation warnings occurred in the unchanged baseline. Some dependency `lintVitalAnalyzeRelease` tasks are skipped by existing tooling; app release lint and the configured Expo lint completed. Successful compilation is necessary but insufficient for alarm reliability.

## Physical/runtime matrix — all NOT EXECUTED

| Required area (report 08) | Tests still required |
|---|---|
| Alarm creation/scheduling | Create/edit/delete, one-shot and recurring, simultaneous alarms, exact-alarm permission flows. |
| Mission setup/authorization | All six mission types, previews and chains; reject invalid/incomplete work; successful completion permits dismissal; locked alarm mutation remains refused. |
| Ringing and controls | Sound/fade/vibration, snooze limits, dismissal, notification actions, custom media and every preset. |
| System state | Secure lock screen/full-screen alarm, app backgrounded, process termination, service recovery, restart before/after unlock, Doze/DND, time/timezone changes, OEM battery restrictions. Distinguish Android force-stop behavior from ordinary process death. |
| Sensors/camera | Barcode setup/completion, pose/squat, step detector and accelerometer fallback, shake, denied/unavailable sensor/camera fallbacks. |
| Permissions and protection | Exact alarms, notifications, full-screen intents, overlay, battery settings and uninstall protection. |
| Sleep sounds | Every preset, loops, timer expiry, stop notification, headphones and alarm interruption. |
| Data/update compatibility | Update over matching-certificate previous release; existing alarms, recurrence, custom media and preferences preserved. No uninstall-based test substitutes for this. |
| Android versions | Modern arm64 API 34–36 plus older API 24–30; broader API/OEM matrix from 08 before release. Universal artifact remains available for non-arm64 devices. |
| Security / bridge / UI | External component access, deep links, every bridge method/event and cleanup, list/editor/sleep/time picker/modals, release startup/reflection/logcat and backup scope. |
| Performance | Cold startup, alarm delivery latency, memory/battery and installed/download size measured on device. No performance gains claimed. |

Artifacts in ignored `android/build/phase2/` are ready for **independent physical-device testing**, with architecture and certificate compatibility checked first. They are locally debug-key signed and **not production-qualified**. Automated passes do not mark any item in the runtime matrix as passing. No Phase 3 work was begun.
