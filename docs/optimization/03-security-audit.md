# 03 — Security Audit

Method: manual source review + merged release manifest (`apkanalyzer manifest print`) + dependency/autolinking inspection. **No dynamic testing, no device, no exploit attempts.** Severity assumes the realistic threat model: *another app on the same device* or *a remote party*; a device owner modifying their own phone is out of scope except where noted.
Legend: **[V]** verified · **[P]** plausible · **[U]** unverified.

## 1. Summary

| ID | Finding | Sev. | Status | Cat. |
|---|---|---|---|---|
| S1 | `BootReceiver` exported, does not check `intent.action` | Low | [V] code, [P] impact | C |
| S2 | Release build type signed with committed debug keystore | Medium (sideload / local builds) | [V] config, [U] whether EAS overrides | B |
| S3 | `allowBackup="true"` | Low | [V] flag, [U] backup scope | B |
| S4 | Mission "lock" depends on in-memory state; window after process death | Low (self-bypass, not external) | [P] | C |
| S5 | `AlarmStore.all()` swallows errors → possible full data loss on next write | Medium (reliability) | [V] code, [P] trigger | C |
| S6 | Unused permissions: `INTERNET`, `READ/WRITE_EXTERNAL_STORAGE`(≤32) | Info/Low | [V] declared, [U] unused | C (deferred) |
| S7 | Deep link `ktalerm://` can open editor/sleep screens with an `id` | Info | [V] | — |
| S8 | No dependency vulnerability scan was possible | Gap | — | — |
| S9 | Verbose `Log.i/w` of alarm ids | Info | [V] | A |

**No critical or high findings. No externally exploitable mission bypass was found.**

## 2. Component-exposure review (A) [V]
* Only `MainActivity`, `BootReceiver`, `UninstallProtectionReceiver` (guarded by `BIND_DEVICE_ADMIN`) and permission-guarded library components are exported (doc 01 §4).
* `AlarmReceiver`, `AlarmActionReceiver`, `SleepSoundStopReceiver`, `AlarmService`, `SleepSoundService`, `AlarmActivity`, `ScanActivity` are `exported=false`. All app-created `PendingIntent`s use explicit component intents and `FLAG_IMMUTABLE` (`AlarmScheduler.kt:64-100`, `AlarmService.kt:308-338,345`, `SleepSoundService.kt:133-135,163`). No implicit internal broadcasts; the only dynamic receiver is `ACTION_AUDIO_BECOMING_NOISY` registered `RECEIVER_NOT_EXPORTED` on API 33+ (`SleepSoundService.kt:171-180`).
* `AlarmActivity` accepts `EXTRA_PREVIEW_MISSION` JSON, but it is not exported, so only in-app callers (JS `previewMission`) reach it.

### S1 — `BootReceiver` is exported and ignores the action
* **Where:** `android/…` module manifest (receiver `exported="true"`), `Receivers.kt:58-77`.
* **Why exported:** system broadcasts (BOOT_COMPLETED etc.) require a manifest receiver; exported is the conventional setting. Protected system broadcasts cannot be sent by apps, **but an app can send an explicit intent with any/no action** to this exported receiver, and `onReceive` never checks `intent.action`.
* **Precondition:** malicious/buggy app on the same device (needs to know the component name; package name is public).
* **Effect [P]:** forces `rescheduleAll` (idempotent: each alarm is cancelled and re-set to its computed next time; sets snoozed alarms to `snoozeUntil`) and `resumeInterruptedMission` (only acts if a `PendingRing` exists and nothing is ringing → schedules a re-ring in 1.5 s). It cannot dismiss, complete a mission, change alarm data or read data.
* **Mitigation (minimal):** at top of `onReceive` return unless `intent.action` ∈ the six declared actions. Behavior-preserving for real system broadcasts.
* **Category C** because it touches the recovery receiver; requires reboot / time-change / package-replace / permission-state tests on device before shipping. Priority: low.

### S9 — Logging
`Log.i("KtAlarm", "Alarm … $id")` etc. (5 `Log.d/v/i` sites + `Log.w`). Ids are random UUIDs; no personal data found. R8 does not strip logs (`proguard-android-optimize.txt` default). Optional `-assumenosideeffects` for `Log.i/d/v` is Category A but cosmetic; skip unless desired.

## 3. Native module security (B) [V]
* `saveAlarm`: range coercion for every numeric field, `days` filtered to 0..6, label trimmed, names truncated to 120 chars, media URIs validated.
* `AlarmMediaStore.checkedUri`: accepts only `preset:` ids in an allowlist or `file:` URIs whose **canonical parent equals** the private `alarm_media` dir and `isFile` — path traversal / arbitrary-file read through the bridge is prevented (`AlarmMediaStore.kt:23-36`). Import enforces MIME class and size caps (25 MB image, 50 MB audio), downsamples images to ≤1600 px, validates audio via `MediaMetadataRetriever`, GC's orphans after 24 h.
* `pickAlarmMedia` uses SAF `ACTION_OPEN_DOCUMENT` (no broad storage permission needed).
* Bridge functions that mutate state (`snooze`, `dismiss`, `setAlarmEnabled`, `deleteAlarm`, `saveAlarm`) all route through `AlarmController`/lock checks; `dismiss` returns false for locked mission alarms (`AlarmController.kt:66-71`).
* Exceptions are coded (`ERR_ALARM_LOCKED` …) and surfaced to JS; `scanBarcode`/`pickAlarmMedia` keep one pending promise and resolve it on destroy.
* Caveat: `snooze(id)` returns `true` for an unknown id and still calls `stopRinging` if that id is the active one — harmless.

## 4. Application security (C)
* **Secrets:** none found in source (`grep` of `src/`, module, gradle, `app.json`, `eas.json`). The only credential-like item is the standard public debug keystore (S2). The Expo `projectId`/owner in `app.json` are identifiers, not secrets.
* **Network:** `usesCleartextTraffic=true` exists only in `src/debug` and `src/debugOptimized` manifests; it is **absent** from the merged release manifest [V]. The release app has no network code in `src/` [V grep]. `INTERNET` is still declared (`android/app/src/main/AndroidManifest.xml:2`) (S6).
* **WebView:** `@expo/dom-webview` is autolinked (transitive of Expo Router/Expo); no WebView use in app code [V grep].
* **Local storage:** alarms, pending ring, sleep selection in device-protected `SharedPreferences`, MODE_PRIVATE. The barcode value (a product code the user registered) is stored in plaintext inside the alarm JSON — acceptable for this threat model.

### S2 — Release signed with the debug key
* **Where:** `android/app/build.gradle` `buildTypes.release { signingConfig signingConfigs.debug }`; `android/app/debug.keystore` is tracked (added in `0b9a815`), alias `androiddebugkey`, store/key password `android` (public Android default).
* **Impact:** any APK produced by `npm run build:apk:local` (`gradlew assembleRelease`) carries a signature anyone can forge. Someone who can place a same-package APK signed with this well-known key can *update* the installed app (sideload scenario). Not Play-publishable.
* **Unknown [U]:** `eas.json` has no `credentialsSource`; EAS normally injects its own keystore at build time, in which case EAS artifacts are not affected. Verify with `apksigner verify --print-certs` on an EAS artifact.
* **Mitigation:** provide a release keystore via `gradle.properties`/env (not committed) and use it for `release`; keep debug config for debug only. **Compatibility trap:** changing the signing key makes existing debug-key-signed installs un-updatable (Android refuses the update); users would have to uninstall → alarm data lost. Category B, needs an owner decision on distribution.

### S3 — Backup
`android:allowBackup="true"` (`AndroidManifest.xml:16`). Alarm stores live in *device-protected* storage; Auto Backup is generally believed to cover only credential-protected storage [U — verify with `bmgr`/`adb backup` on a device]. Barcode values and custom media would be exposure candidates if included. Options: `allowBackup="false"` or `dataExtractionRules`; Category B (product decision — restoring alarms on a new phone may be wanted).

### S5 — Silent data loss path (reliability/security-adjacent)
`AlarmStore.all()` catches *every* exception and returns `emptyList()`; `upsert`/`remove` then rewrite the whole list from that. One malformed alarm entry (`Alarm.fromJson` uses `getString("id")`, `getInt("hour")`) would make *all* alarms vanish on the next write, and the scheduler would then have nothing to re-arm after reboot. Trigger requires a corrupt/unknown-schema entry (e.g. downgrade after a future schema change). Fix idea: per-entry parse with skip, never overwrite on parse failure. **Category C** (persistence).

## 5. Dependency security (D)
* **Not performed:** no network lookup (`npm audit`, OSV) was run; no vulnerability is therefore claimed or ruled out. Versions in use are recent for the SDK (Expo 58, RN 0.88.1, CameraX 1.4.2, ML Kit barcode 17.3.0).
* Risk notes [V]: `pose-detection 18.0.0-beta5` is a **beta** artifact and pulls `mediapipe-internal 17.0.0-beta10` (source of the 11.4 MB `libxeno_native.so`) and `acceleration 16.0.0-beta2`. `androidx.work 2.11.2` is deliberately newer than ML Kit's transitive version.
* `react-native-web`, `react-dom` are listed as dependencies although this is a native app (web output configured as `server` in `app.json`) — attack-surface-neutral but unnecessary for Android builds [U whether needed for Expo Router web].
* Next phase: run `npm audit --omit=dev` and a Gradle dependency-check offline/online per team policy.

## 6. Mission-bypass threat model (E)

| Attack path | Result |
|---|---|
| Other app → exported component | Only `BootReceiver` (S1) and `MainActivity` deep link are reachable. Neither can mark a mission complete or dismiss. |
| Other app → `AlarmActionReceiver` (Dismiss/Snooze) | Not exported → unreachable. Dismiss refused for mission alarms anyway (`dismiss` checks `isLocked`; notification has no Dismiss button for mission alarms, `AlarmService.kt:317`). |
| JS/bridge misuse (`dismiss`, `deleteAlarm`, `setAlarmEnabled(false)`, `saveAlarm` edit) | All refused while locked (`ERR_ALARM_LOCKED`) [V]. |
| Spoof completion | No completion API exists on the bridge; completion is only invoked inside `AlarmActivity.onMissionComplete`. |
| `previewMission` with ringing alarm | Preview ignored if a real alarm is on screen (`AlarmActivity.kt:218-221`); preview never touches alarm state. |
| Deep link `ktalerm://alarm?id=<id>` | Opens the editor; save/delete still lock-checked. `ktalerm://sleep?id=` opens sleep-sound screen; whether it can *start* playback without a tap is [U] (not examined beyond `sleep.tsx` outline). |
| Reboot/kill during mission | Resumes via `PendingRing` (cap 60 min). |
| Uninstall to escape | Opt-in device-admin protection; `onDisableRequested` re-raises the mission; disabling refused while locked. Cannot stop a determined owner (documented in source). |
| **Owner-level** weakenings (out of external scope) | S4 window; revoking camera/step permission or force-stop (Android-imposed; force-stop cancels AlarmManager alarms until next launch — platform behavior, not a defect); airplane/DND do not matter because the alarm stream bypasses DND (`setBypassDnd`). |

### S4 — Lock window after process death [P]
`Alarm.isLocked()` uses `AlarmService.activeAlarmId` (static, lost on process death) or `snoozeUntil`. If the process dies while a mission rings and the user opens the app within the ~1–1.5 s before the resume alarm restarts the service, JS could `deleteAlarm`/`setAlarmEnabled(false)`. Also `release()` is reachable. Mitigation idea: treat a persisted `PendingRing` for this id (< `MISSION_CAP_MS`) as locked. Category C; owner-level only; priority low.

## 7. Unverified concerns / not examined
Device-level Auto Backup scope (S3); EAS signing (S2); whether `ktalerm://sleep` auto-starts anything; `ScanActivity` and `AlarmTimePickerView` line-by-line review; iOS Swift security; effect of OEM battery managers; Android 15/16 FGS-start restrictions on `AlarmReceiver → startForegroundService` (source comments assert `setAlarmClock` exemption — needs device confirmation on API 35/36).
