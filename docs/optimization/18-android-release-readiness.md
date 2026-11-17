# Phase 3 — Android release readiness

**Decision: READY FOR DEVICE TESTING.** This is permission to proceed to controlled QA preparation, not certification for distribution. Static/build verification is satisfactory; local artifacts are debug-signed and runtime alarm reliability remains untested. No device was available. Neither limited distribution nor production release readiness is established.

## Consolidated checklist

| Category | Independently established | Remaining release gate |
|---|---|---|
| Build correctness | TypeScript/lint pass; universal APK/AAB + opt-in arm64 APK build pass; 38 JVM tests pass | Incremental builds only; clean CI reproducibility/Doctor desirable |
| Alarm reliability | Production control flow reviewed; deterministic next-time/snooze/mission decisions pass | All triggering/audio/dismiss/mission/locked/background/process-death/boot cases in 15 blocked |
| Native integration | Discovery/provider/mapping preserved, bridge contracts unchanged | Release startup, event/function invocation, camera/sensor/Room reflection smoke |
| Packaging | Exact Phase 2 hashes/sizes; only intended ABIs; resources/models/audio intact; 16 KiB ZIP/ELF alignment pass | Actual native loading, 16 KiB runtime, Play split delivery |
| Signing | Same Android Debug identity on local APKs | Trusted distributed/EAS identity UNVERIFIED; no installed-certificate or update test |
| Permissions | Manifest/branches reviewed; exact/FGS/full-screen/notification declarations intact | Real deny/restore and Android/OEM background-start matrix |
| Data integrity | No format, identifiers, application package, native or user-data change | S5 malformed-data overwrite risk unresolved; S4 recovery gap; update and restore tests |
| Security | Six requested findings dispositioned; fresh audit: one advisory propagated through three moderate entries | No native advisory scan; S1/S3/S4/S5 runtime validation; signing correction needs authorization |
| Distribution | Universal/arm64 APK and AAB available; universal defaults retained | Debug-key local artifacts must not be promoted to trusted production; suitable signing/store versionCode and split testing |
| Real-device readiness | Artifacts preserved; consent and compatibility test protocol documented | No physical-device certification; ADB reports no devices |
| Performance/stability | No source optimization; byte-identical artifacts; cleanup paths inspected | No measured startup/latency/memory/battery/crash-free runtime evidence |

## Gates before limited distribution

1. Connect a consented test device, identify ABI/OS and current installed package/certificate/version; do not uninstall or overwrite an incompatible install.
2. Obtain an actual EAS/distributed artifact under the authorized account; compare public certificates, including Play upload versus app-signing identities. Agree a secure compatible signing plan separately if debug signing is what users have. Preserve credentials and existing user data.
3. Run the physical matrix in 15, especially exact delivery, secure lock screen, all mission validation, snooze, boot before unlock, service/process death, permission restoration, simultaneous alarms and OEM power behavior.
4. Demonstrate data-preserving update over an existing compatible installation. Record before/after IDs, mission/media settings and scheduled entries, not just install exit status.
5. Reproduce S4/S5 safely on isolated fixtures/test environments; minimally remediate with separate authorization/review, or record explicit risk acceptance with supporting evidence. No production clearance while data-loss behavior lacks an adequate disposition.

## Additional production prerequisites

Complete older/modern Android and OEM coverage, backup/restore policy and tests, release performance measurements, supported dependency security review and Play-generated split/R8 camera smoke. Confirm secure signing recovery/access and increasing store versionCode relative to the published version (local versionCode currently 1). No change to signing, versions or publication is authorized or performed here.

## Change and evidence accounting

Phase 3 adds only `AlarmDecisionTest.kt` (10 isolated JVM tests) and reports **14–18**. Existing `package.json` edit and reports 01–13 were preserved. No production Kotlin, JS/UI, manifests, EAS/Gradle configuration, lockfile, media or credentials changed. No commit/push/deploy.

Raw reproducible evidence is local under ignored `android/build/phase3/`; binaries `universal-release.apk`, `arm64-release.apk`, `universal-release.aab` are retained there, with original conventional outputs under `preserved/`. Do not mistake cached/incremental build success for device certification. Final Git state: pre-existing modified package.json; untracked docs/ (including the five new reports) and new AlarmDecisionTest.kt. Previous documentation checksums pass and `git diff --check` passes.

Phase 3 investigation is complete within available environment access. Device/signing certification remains incomplete, with precise execution and evidence requirements in 15–17. No alarm engine changes were justified or attempted.
