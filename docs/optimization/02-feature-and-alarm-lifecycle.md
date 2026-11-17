# 02 — Feature Inventory & Alarm Lifecycle

Legend: **[V]** verified in source · **[P]** plausible/needs device check · **[U]** unverified.
Paths are under `modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/` unless noted. Line numbers refer to commit `f6eedf9`.

## 1. Feature inventory (11 major features)

| # | Feature | Entry points | Persistence | Native deps / permissions | Regression risk |
|---|---|---|---|---|---|
| F1 | Alarm CRUD + repeat days, label, vibrate, snooze minutes | `src/app/index.tsx`, `src/app/alarm.tsx` → `KtAlarmModule.saveAlarm/deleteAlarm/setAlarmEnabled` | `AlarmStore` (`SharedPreferences` "kt_alarm_store", device-protected, JSON, `commit()`) | AlarmManager | **Critical** |
| F2 | Exact scheduling | `AlarmScheduler.schedule` — `setAlarmClock()`; fallback `setAndAllowWhileIdle` if exact access revoked (API 31–32 only) | AlarmManager (volatile across reboot) | `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM(≤32)` | **Critical** |
| F3 | Ringing: sound/vibrate/wake lock/notification/full-screen | `AlarmReceiver` → `AlarmService.onStartCommand` | `PendingRing` (SharedPreferences) for mission alarms | FGS types, `WAKE_LOCK`, `VIBRATE`, `USE_FULL_SCREEN_INTENT`, `POST_NOTIFICATIONS` | **Critical** |
| F4 | Ringing UI + missions (chain, ordered) | `AlarmActivity`, `MissionViews.kt` (`MissionRunner`) | none (index saved in `onSaveInstanceState`) | `SYSTEM_ALERT_WINDOW` (required to save a mission alarm) | **Critical** |
| F5 | Missions: math (4 difficulties), shake, walk (step detector, accelerometer fallback), squat (ML Kit pose), typing (paste blocked), barcode (ML Kit scan) | `Mission.kt`, `MissionViews.kt`, `WalkMission.kt`, `WalkCounter.kt`, `SquatCounter.kt`, `PoseCamera.kt`, `BarcodeScanner.kt`, `CameraView.kt` | mission JSON inside the alarm | CAMERA, ACTIVITY_RECOGNITION, sensors | High |
| F6 | Snooze with per-ring limit, mission-locked alarms | `AlarmController.snooze`, `Alarm.isLocked` | `snoozeUntil`, `snoozeCount` in alarm JSON | — | **Critical** |
| F7 | Recovery: reboot, update, clock/timezone, permission-state change, service kill | `BootReceiver`, `AlarmService` (START_STICKY for mission rings), `AlarmScheduler.scheduleResume` | `PendingRing`, alarm JSON | `RECEIVE_BOOT_COMPLETED`, Direct Boot (`directBootAware`) | **Critical** |
| F8 | Custom ringtone / background / presets | `pickAlarmMedia` (SAF `ACTION_OPEN_DOCUMENT`), `AlarmMediaStore`, `PresetMedia` | private copy in device-protected `files/alarm_media/`; 9 WAV presets in `res/raw` | none (no storage permission use) | Medium |
| F9 | Sleep sounds with timer (independent of alarms) | `src/app/sleep.tsx`, `SleepSoundService`, `SleepSoundStore` | `kt_sleep_sounds` prefs | FGS mediaPlayback, exact-alarm for stop timer | Medium |
| F10 | Permission onboarding card (notifications, exact alarm, full-screen intent, battery, overlay, vendor auto-start) | `permission-card.tsx`, `AlarmPermissions.kt` | — | settings intents (12 vendor components probed) | Medium |
| F11 | Uninstall protection (device admin, no policies) | `uninstall-protection-card.tsx`, `UninstallProtection.kt` | system DPM state | `BIND_DEVICE_ADMIN` receiver | Medium |
| (F12) | iOS AlarmKit parity | `modules/kt-alarm/ios/*` | AlarmKit | iOS 26+ | out of Android scope; not audited |

## 2. Alarm lifecycle trace (Android)

| Step | What happens | Where |
|---|---|---|
| A/B/C Create/configure | JS state in `AlarmEditor` (`alarm.tsx:64-84`); `save()` pre-checks: barcode registered, camera permission, overlay permission (Android + any mission), notifications | `alarm.tsx:117-160` |
| D Save | `saveAlarm` validates hour/minute, rejects edit of locked alarm (`ERR_ALARM_LOCKED`), maps missions (`ERR_INVALID_MISSION`), requires overlay when missions exist (`ERR_OVERLAY_REQUIRED`), coerces ranges (snooze 1–60, maxSnoozes 0–10, sleep 1–720), validates media URIs (`AlarmMediaStore.checkedUri`) | `KtAlarmModule.kt:~150-185` |
| D/E Persist + schedule | `AlarmController.save` → `AlarmStore.upsert` (sync `commit`) → `AlarmScheduler.schedule` (cancel + `setAlarmClock`) → emit `ALARMS_CHANGED` | `AlarmController.kt:9-13`, `AlarmScheduler.kt:20-36` |
| F Trigger | AlarmManager delivers broadcast `ACTION_FIRE` to non-exported `AlarmReceiver` via IMMUTABLE explicit `PendingIntent` | `AlarmScheduler.kt:78-88`, `Receivers.kt:10-29` |
| G Start service | `startForegroundService(AlarmService, ACTION_RING)`; permitted because trigger came from `setAlarmClock` | `Receivers.kt:21-28` |
| G/H Ring | `AlarmService.onStartCommand`: `AlarmController.onFired` advances state **before** ringing (repeaters re-armed from now+60 s; one-shots disabled) → `startInForeground` (type `systemExempted` on 34+ if exact-alarm allowed, else `mediaPlayback`) → `startRinging`: wake lock (`silenceAfter+60 s`), stops sleep sound, `MediaPlayer` on USAGE_ALARM with 20 s fade-in (falls back through default alarm/ringtone/notification URIs), vibration, `launchRingingScreen` | `AlarmService.kt:134-226` |
| I Lock screen | Notification with full-screen intent + `AlarmActivity` (`showWhenLocked`/`turnScreenOn`); direct `startActivity` when overlay permission granted | `AlarmService.kt:290-323,365-377`, manifest |
| J Background | Entire path is native; JS/React Native is not required to ring | verified by control flow |
| K Mission execution | `AlarmActivity.startMission` → `MissionRunner.create` (per type). Sound ducked to 0.3 while working; idle timeout returns to loud ringing + restarts mission; Home/Recents relaunches the activity (300 ms); volume keys swallowed; Back ignored | `AlarmActivity.kt:111-131,160-210,274-292` |
| L Verification | In-activity, per mission class: math answer match, shake/walk/squat counters, typing normalized string match, barcode `value == mission.barcodeValue` (`MissionViews.kt:523-540`). Camera/sensor failure → **fallback to math** (`onMissionUnavailable`) | `AlarmActivity.kt:309-317` |
| M Dismiss | Last mission → `AlarmController.completeMission` → `release` (reset snooze, `stopRinging`: clear `PendingRing`, cancel resume, `stopService`). Non-mission alarm: Dismiss button / notification action → `AlarmController.dismiss` | `AlarmActivity.kt:319-349`, `AlarmController.kt:62-105` |
| N Snooze | `AlarmController.snooze`: refuses when out of snoozes; stores `snoozeUntil`; reschedules; stops ringing. Volume keys snooze **non-mission** alarms | `AlarmController.kt:51-60` |
| O Edit/cancel | Locked (= has mission AND (ringing OR `snoozeUntil>now`)) alarms cannot be edited, deleted, disabled; uninstall-protection disable refused while any alarm locked | `Alarm.kt:49-50`, `KtAlarmModule.kt` |
| P Recurring | `onFired` re-arms next occurrence; `Alarm.nextTrigger` scans 0..7 days using `Calendar` (DST-safe via `add(DAY_OF_YEAR)`) | `AlarmController.kt:32-48`, `Alarm.kt:53-77` |
| Q Process death | Alarm fires regardless (AlarmManager + manifest receiver). A killed ringing mission service restarts (`START_STICKY`, null intent → `scheduleResume` in 1 s) | `AlarmService.kt:143-151,170` |
| R Reboot | `BootReceiver` re-arms all alarms and, if a mission ring was pending (<60 min old), schedules resume at +1.5 s | `Receivers.kt:58-77` |
| S Time/zone change | `TIME_SET`/`TIMEZONE_CHANGED` → same re-arm path | manifest + `BootReceiver` |
| T Permission change | `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` → re-arm; scheduler falls back to inexact if revoked; JS re-reads `getPermissionStatus` on app resume | `AlarmScheduler.kt:27-32`, `use-alarms.ts` |
| U Recovery | `PendingRing` + resume alarm + `START_STICKY`; 60-min safety cap (`MISSION_CAP_MS`); non-mission rings auto-silence after 10 min and post "Missed alarm" | `AlarmService.kt:59-62,98-103` |
| V Native↔JS | Commands: Expo Modules functions (above). Events: `AlarmEvents` bus → `sendEvent`. JS refreshes on events and `AppState==active` | `KtAlarmModule.kt:`bridgeListener, `use-alarms.ts` |

## 3. Source of truth
* **Alarm state:** `AlarmStore` JSON in device-protected `SharedPreferences` (`kt_alarm_store`, key `alarms`) — authoritative; JS holds a read-through copy only.
* **Ringing state:** in-memory `AlarmService.activeAlarmId` (volatile static) + persisted `PendingRing` for mission alarms.
* **Mission completion:** decided only inside `AlarmActivity`/`MissionRunner`; JS cannot report completion — **there is no bridge function that completes a mission** [V: `KtAlarmModule` exposes none].
* **Lock (cannot-dismiss) rule:** derived in `Alarm.isLocked()` from alarm JSON + `activeAlarmId`.

## 4. Independent of the JS runtime [V]
Scheduling after first save, reboot recovery, ringing, notification actions, ringing UI, all missions, mission chain, snooze/dismiss, recovery, sleep-sound timer stop. JS is needed only to create/edit/list alarms, show permission state and start sleep sounds/previews.

## 5. Observed behaviors worth knowing (not changes) [V by reading, device behavior [P]]
1. A one-shot alarm whose time passes while the phone is **off** is re-armed for the *next day* after boot (`nextTrigger` adds a day when `base ≤ now`; no missed-alarm notification on that path). Same for an expired `snoozeUntil`.
2. `AlarmStore.all()` returns `emptyList()` on *any* JSON exception (`AlarmStore.kt:33-41`); a following `upsert` would then persist only the new alarm.
3. `isLocked()` reads the in-process `activeAlarmId`, so between process death and service resume (~1–1.5 s) a ringing mission alarm is not "locked" from JS's point of view (see doc 03 S4).
4. Camera/step-sensor failure intentionally downgrades to a math mission.

## 6. DO NOT CHANGE casually (alarm-critical)
`AlarmScheduler.kt` (setAlarmClock choice, request codes `id.hashCode()*8+slot`, resume PI), `AlarmController.kt` (advance-before-ring ordering in `onFired`), `AlarmService.kt` (FGS type selection, `startForeground` before anything can fail, wake-lock timeout, START_STICKY logic, volume guard), `Receivers.kt`, `AlarmStore.kt` (sync `commit`, device-protected storage), `Alarm.kt` (`isLocked`, `nextTrigger`), `AlarmActivity.kt` (relaunch/idle/ducking logic), the manifest component flags (`directBootAware`, `showWhenLocked`, `foregroundServiceType`, `taskAffinity`, `singleInstance`), `modules/kt-alarm/android/proguard-rules.pro`, the `androidx.work 2.11.2` pin, and the `index.ts` bridge contract (JS and Kotlin `Mission` mirrors: `src/lib/missions.ts` ↔ `Mission.kt`).
