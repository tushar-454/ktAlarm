# Phase 3 — Independent verification

Date: 2026-11-17. HEAD: `f6eedf98efa52d996a9424f09fc829cadee52eb9`.
Initial Git state: modified `package.json` (Phase 2 arm64 script), untracked `docs/`. No reset, commit, push, deployment, dependency installation, prebuild, signing change or production-source change was performed. Reports 01–13 were read and their checksums preserved/verified. Actual module, app, manifest, build and bridge source was inspected independently.

## Environment and evidence

Linux x86_64; Node 24.20.0, npm 11.19.0, OpenJDK 21.0.13; Gradle 9.4.1. Existing SDK `/home/tushar/Android/Sdk`, build-tools 37.0.0; no `android/local.properties`. SDK 58 / RN 0.88.1 remain pinned. SDK/toolchain resolved successfully without configuration edits. Android minSdk 24, targetSdk 36. The SDK 58 reference and Expo documentation index were fetched before test work.

Evidence directory: ignored `android/build/phase3/evidence/`. Raw logs, per-entry SHA-256 inventory, comparison JSON, manifest XML, certificate metadata, test XML and R8 outputs are local evidence, not committed deliverables. Initial release APK/AAB copied to `phase3/preserved/`; Phase 2 artifacts untouched. New universal outputs copied before the arm64 command overwrote the conventional APK path. Final conventional APK is arm64; conventional AAB remains universal. Build commands share an output filename: always preserve it before switching architectures.

## Independently executed gates

| Check | Phase 3 result | Evidence / limits |
|---|---|---|
| `npx --no-install tsc --noEmit` | PASS, exit 0 | `typecheck.log` |
| `CI=1 npx --no-install expo lint` | PASS, exit 0 | `lint.log`, no diagnostics |
| `./gradlew :app:assembleRelease :app:bundleRelease :kt-alarm:testDebugUnitTest --console=plain` | PASS | `universal-build.log`, 48 s, incremental (91 tasks executed, 558 up-to-date) |
| `npm run build:apk:local:arm64 -- --console=plain` | PASS | `arm64-build.log`, 18 s, 55 executed, 546 up-to-date |
| `./gradlew :kt-alarm:testDebugUnitTest --rerun --console=plain` | PASS | `unit-tests.log`, test task explicitly rerun after new tests; 38 tests, zero errors/failures/skips |
| Native module discovery/provider | PASS statically | Fresh autolinking output includes kt-alarm; generated `ExpoModulesPackageList.kt` identical to Phase 2, includes `KtAlarmModule`; no runtime bridge invocation |
| Manifest | PASS statically | Fresh APK manifests identical; no release cleartext/debuggable flag; package unchanged; alarm component flags/permissions retained |
| R8 / consumer rules | PASS statically | Minify on, resource shrink off; mapping identical to Phase 2; module/alarm classes retained; ML Kit registrar/Room constructor keep rules present in effective configuration |
| ZIP integrity / signatures / alignment | PASS | All three ZIP CRC checks; both APK v2 signatures verified; arm64 16 KiB zipalign check exit 0; all 31 arm64 ELF PT_LOAD alignments >=16 KiB |
| JS/instrumentation tests | NOT RUN | No configured framework/suite. No Android delivery claims from JVM tests |
| Expo Doctor / native advisory scan | NOT RUN | No local Doctor executable; no heavyweight tooling installed |

Initial sandbox Gradle cache-lock and ADB socket restrictions were environmental. Authorized execution with existing host caches/tooling succeeded. npm audit initially encountered DNS restrictions; the authorized retry completed. These are not app test failures. Builds reused valid cached compilation/R8 outputs: this is incremental release verification, not a clean-room rebuild.

## Artifact comparison with Phase 2

| Artifact | Bytes | MiB | ABIs | Comparison |
|---|---:|---:|---|---|
| Universal APK | 172,904,508 | 164.89 | arm64-v8a, armeabi-v7a, x86, x86_64 | Entire SHA-256 matches Phase 2 |
| Arm64 APK | 61,188,334 | 58.35 | arm64-v8a only | Entire SHA-256 matches Phase 2 |
| Universal AAB | 130,386,391 | 124.35 | All four | Entire SHA-256 matches Phase 2 |

SHA-256:
- Universal APK: `205e109a6e07082d03cc5b906cbe616ede11b541f2b72b0a0c054932c225c57f`
- Arm64 APK: `304218c039593425579fcb7a7324f1278bd7a60130109ee03f2b360a113d0a84`
- AAB: `372360760c40d3f7008d5ec4e81972ae887012947b8b7f73963aede50e7f4d30`

Universal has 124 libraries (31 per ABI); arm64 retains all 31 with identical bytes. Exactly 93 other-ABI library entries disappear; no added or changed common entry (including compression method/size). Savings: 111,716,174 bytes = 106.54 MiB. Omitted native bytes: 110,946,628; remaining 769,546 bytes are archive/alignment/signing layout overhead. Same-ABI native-code/resource removal savings: **zero**. No compression change or speculative savings counted.

DEX, resource table, all assets/models and resources match. All nine source WAV hashes occur in the APK. Hermes magic `c61fbc03c103191f`; no packaged source maps. Required ML Kit, RN, Hermes, Expo, Reanimated/Worklets libraries retained. AAB is store input, not directly installable; Play-generated split delivery and 16 KiB runtime behavior remain untested.

## Discrepancies and assurance limits

No discrepancy in reported Phase 2 sizes, ABI savings, build support or local certificate. The 28 prior Kotlin tests were independently rerun, plus 10 new tests. EAS metadata access was independently denied again; no installed/distributed certificate available. Prior performance timings were not benchmarks, and no startup, battery, latency or memory measurement exists in Phase 3.

Review confirms synchronous JSON/commit work, synchronous `MediaPlayer.prepare()` on the service thread, bounded mission volume polling, and startup library initializers. No measured regression or demonstrated leak was found. Handler/listener/sensor/camera/player cleanup exists in source; its runtime effectiveness is untested. No speculative optimization is justified.

References: [SDK 58](https://docs.expo.dev/versions/v58.0.0/), [Expo docs index](https://docs.expo.dev/llms.txt). See 15–18 for runtime gaps, signing and release gates.
