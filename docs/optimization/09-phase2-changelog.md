# 09 — Phase 2 changelog

Baseline: `f6eedf98efa52d996a9424f09fc829cadee52eb9`; work date: 2026-11-17. HEAD was unchanged from Phase 1. Initially, only `docs/` was untracked; all eight supplied reports were preserved and their SHA-256 checks passed. No commits or pushes were made.

## Accepted change

`package.json`: added exactly one script:

```json
"build:apk:local:arm64": "cd android && ./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a"
```

Reason: Z1, provide a separately named, directly installable arm64 release APK for physical-device testing without reducing default architecture support. The installed RN Gradle plugin's `NdkConfiguratorUtils.kt` applies this project property to ABI filters when ABI splits are disabled; actual output inspection confirmed one ABI. This creates one filtered APK, not an APK split set.

Existing local/cloud commands, both EAS profiles, default four-ABI configuration, production AAB, emulator support, dependency versions/lockfile, native packaging mode, app identity and signing configuration remain unchanged. No source/configuration files in `android/`, `modules/kt-alarm/`, `src/`, or `ios/` were edited. Generated build outputs in ignored directories are not source changes.

The command writes the usual `android/app/build/outputs/apk/release/app-release.apk`. Preserve/copy that output before switching build commands; the next build can overwrite it. Use the existing universal command for other architectures. Arm64 runtime compatibility is **pending physical-device testing**, not established by the build.

## Batch disposition and evidence

| Batch | Result |
|---|---|
| 0 — fresh baseline | PASS: unchanged four-ABI minified release APK and AAB built. Existing stale APK copied before building. Config snapshots, manifest, R8 mapping/configuration, generated module provider, certificate and ZIP measurements preserved locally. Initial sandbox cache-lock failure was resolved by authorized access to existing Gradle caches; no build setting was changed. |
| 1 — ABI packaging | Accepted opt-in script. Release build passed. APK contains 31 arm64 libraries; exactly 93 other-ABI entries removed. Every common ZIP entry is byte-identical to baseline, as are decoded manifest, certificate, module discovery/provider and R8 mapping. Packaging difference: 106.54 MiB; code/resource removal: zero. |
| 2 — signing | Investigated; no signing change. Fresh and stale local release APKs have the same Android Debug certificate. EAS build access denied; distributed identity unknown. See 11. |
| 3 — Android `@expo/ui` exclusion | Deferred. Router has actual Android imports through its optional toolbar helper; device behavior without native UI registration not validated. See 13. |
| 4 — GIF / WebP / resource shrinking | Each evaluated separately; none applied. No measured savings or device format/resource regression evidence sufficient to retain a change. |
| 5 — Reanimated / Worklets | Dependency and Babel investigation completed; removal deferred. |
| 6 — audio | Nine unique referenced WAVs measured; packaged hashes match source. No bytes, mappings or resource names changed. |
| 7 — S9 / release security cleanup | No demonstrated sensitive payload in five informational alarm diagnostic calls; stripping them was not justified. No ProGuard/security configuration change. Approved npm scan recorded a moderate transitive advisory; no dependency remediation attempted. |

The pre-edit package snapshot matches HEAD. Initial Git state and baseline configuration snapshots provide the starting state; `batch1-start-state.json` records that identity and `batch1-prebuild.diff` records the single script addition before its build. Batches 2–7 retained no additional source/configuration patch; their relevant configuration remains the baseline configuration. All evidence is under ignored `android/build/phase2/`.

## Verification and rollback

TypeScript and Expo lint passed before and after the script change. All 28 existing Kotlin unit tests passed (13 squat, 4 sleep deadline, 11 walk). Universal APK build: PASS; universal AAB build: PASS; opt-in arm64 APK build: PASS. Manifest, ABI, archive-entry hashes, audio hashes, certificate, R8 and module registration checks passed. See 10 and 12 for exact measurements and limits.

Rollback: remove only the new script entry from `package.json`; keep any other user edits. Existing build commands need no restoration. To regenerate a universal local APK, run the unchanged `npm run build:apk:local`. No data migration or signing change needs rollback.

New reports: `09-phase2-changelog.md`, `10-apk-size-comparison.md`, `11-security-hardening-results.md`, `12-regression-results.md`, `13-deferred-improvements.md`. No generated artifact is intended for Git. No production readiness or physical alarm-reliability claim is made.

References checked before configuration work: [SDK 58](https://docs.expo.dev/versions/v58.0.0/), [Expo documentation index](https://docs.expo.dev/llms.txt), [APK build options](https://docs.expo.dev/build-reference/apk/). The ABI override was additionally verified against this project's installed RN plugin and generated APK.
