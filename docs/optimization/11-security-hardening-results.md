# 11 — Phase 2 security results

Baseline: `f6eedf98efa52d996a9424f09fc829cadee52eb9`. Investigation date: 2026-11-17. No security configuration, credential, permission, exported component, backup rule, Kotlin source, or ProGuard rule was changed.

## Findings

| Finding | Verified evidence / disposition |
|---|---|
| S2: local release signing | `android/app/build.gradle` explicitly uses `signingConfigs.debug` for release and `android/app/debug.keystore` is tracked. The preserved stale, fresh universal and fresh arm64 release APKs pass `apksigner verify` and share the same Android Debug certificate. This confirms local signing exposure and unchanged local identity; it does not identify the production signing certificate. |
| EAS signing | `preview` builds an internal APK; `production` builds an AAB. Neither profile specifies `credentialsSource`. The connected Expo build-list request was denied with `Entity Not Authorized`. No EAS artifact, build-time signing injection, remote credential, or installed production certificate could be inspected. Production identity remains **UNVERIFIED**. |
| S9: release logging | Five informational calls in `AlarmScheduler.kt`, `Receivers.kt`, and `AlarmService.kt` report schedule/resume/trigger/recovery/auto-silence events using alarm identifiers, timestamps, and actions. These are useful alarm-delivery diagnostics. No label, barcode value, or credential was found in those five calls. Global log stripping offers no demonstrated security benefit sufficient to justify losing these diagnostics. Deferred; warnings/errors also retained. |
| S3: backup | App manifest still declares `allowBackup=true`; no backup migration or device restore assessment was available. Unchanged. Do not infer the actual device-protected backup scope from this flag alone. |
| S1 / S4 / S5 | Receiver action validation, process-death mission locking, and parse-failure data preservation remain explicitly deferred under the safety contract. They are unresolved findings, not fixed by packaging. |
| S6 and other native hardening | Permissions, exported components, foreground-service lifecycle, wake behavior, bridge and persistence contracts remain unchanged. |

Certificate SHA-256 of the preserved stale local release APK (public certificate metadata):
`fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`.
Certificate fingerprints are public identifiers; no private keys or credential values were extracted for these reports.
An initial raw configuration read in the work session included public default debug-signing strings; those should have been redacted. No production credentials were accessed, and subsequent signing checks used certificate metadata only.

## Signing handoff

Obtain the actual distributed APK (or Play-generated installed APK), verify its public signing certificate, and compare with both the local artifact and a fresh EAS artifact. For Play App Signing, distinguish the upload certificate from the app-signing certificate used on device. Record which identity existing users have before changing any signing configuration.

Preserve that identity for updates. A future signing correction needs a separately reviewed compatibility plan, secure credential storage/injection, and an update-over-install data-preservation test. Replacing the debug certificate now could prevent updates and lead to uninstall/data loss. No keystore was created, replaced, removed, or rotated.

## Scope of assurance

With explicit user approval, `npm audit --omit=dev --json` completed against npm's advisory service. It exited 1 because it reported **3 moderate entries, 0 high, 0 critical**: `minimatch`, `@expo/fingerprint`, and `expo`. These are one advisory and its dependency propagation, not three independently demonstrated app vulnerabilities. Installed chain: `expo@58.0.25 → @expo/fingerprint@0.21.10 → minimatch@10.2.6`.

[GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249) describes glob-pattern amplification; affected versions are `>=7.0.0 <11.0.1`. The advisory requires attacker-controlled patterns and a relevant optimization setting. Reachability from untrusted app input was not established. npm's suggested resolution targets Expo 52, which is outside the pinned stack and this task's no-upgrade contract; it was **not applied**. No `audit fix`, override, reinstall, or lockfile edit was performed. Raw scan and dependency tree are retained under ignored `android/build/phase2/baseline/`. A Gradle/OSV vulnerability scan was not executed, so this is not a complete dependency-security clearance.

Static inspection and compilation do not establish exploit resistance or alarm reliability. Device backup/restore, external-component access, release logcat, mission authorization, and update compatibility tests remain required. See report 12 for executed checks and report 13 for deferred work. No production-readiness claim is made.
