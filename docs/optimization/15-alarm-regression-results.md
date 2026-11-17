# Phase 3 — Alarm regression results

Date: 2026-11-17. Baseline/commit and artifact hashes: report 14. Runtime result vocabulary: PASS = executed with stated scope, FAIL = observed failure, BLOCKED = required environment/access absent, NOT RUN = no execution. Static review is not runtime PASS.

Environment for automated rows: Linux/JDK 21 host JVM, JUnit 4.13.2, kt-alarm debug unit-test task; no Android framework or AlarmManager delivery simulation. Evidence: `android/build/phase3/evidence/unit-tests.log`, `TEST-*.xml`, `tests.json`. Existing 28 tests rerun successfully (13 squat, 11 walk, 4 elapsed-time sleep deadline). They test algorithms, not real sensors/audio. Ten new tests in `AlarmDecisionTest.kt` use production Alarm/Mission decisions, fixed times and restored timezone defaults. No production code/dependency change.

## Automated decision cases

| ID | Executed test / expected result | Actual / status | Relevant code |
|---|---|---|---|
| U01 | `oneShotUsesTodayBeforeConfiguredMinute`: Today when time remains | Expected assertions met; **PASS** | `Alarm.nextTrigger` |
| U02 | `equalMinuteRollsOneShotToTomorrow`: Strictly after now, next day | Expected assertions met; **PASS** | `Alarm.nextTrigger` |
| U03 | `futureSnoozeOverridesRepeatTime`: Future snooze wins | Expected assertions met; **PASS** | `Alarm.nextTrigger` |
| U04 | `expiredSnoozeReturnsToWallClockSchedule`: Expired snooze returns to normal next day | Expected assertions met; **PASS** | `Alarm.nextTrigger` |
| U05 | `repeatUsesSundayZeroAndSkipsCurrentOccurrence`: Sunday index 0; exact occurrence advances a week | Expected assertions met; **PASS** | `Alarm.nextTrigger` |
| U06 | `repeatKeepsLocalHourAcrossSpringDst`: 08:30 next day, 23 elapsed hours | Expected assertions met; **PASS** | `Alarm.nextTrigger` |
| U07 | `repeatKeepsLocalHourAcrossAutumnDst`: 08:30 next day, 25 elapsed hours | Expected assertions met; **PASS** | `Alarm.nextTrigger` |
| U08 | `snoozeLimitAndUnlimitedRemainDistinct`: Finite exhaustion refuses; unlimited remains available | Expected assertions met; **PASS** | `Alarm.canSnooze/snoozesLeft` |
| U09 | `barcodeRequiresRegisteredValueAndNormalizesCount`: Empty barcode rejected; count/difficulty/name normalized | Expected assertions met; **PASS** | `Mission.create` |
| U10 | `unknownMissionIsRejectedAndOtherTypesDropBarcodeFields`: Unknown type rejected; walk range clamped; barcode fields absent | Expected assertions met; **PASS** | `Mission.create` |

JVM repeat cases do not validate device DST alarm delivery. Serialization, real SharedPreferences persistence, receiver input dispatch, permission transitions and boot/service recovery have no executable framework-backed regression coverage yet. No heavyweight framework was introduced to fill these with mocks. See isolated S5 fixture proposal in 17.

## Critical runtime matrix

All rows below: **actual = not executed; environment = no connected device; status = BLOCKED**. `adb devices -l` completed successfully with no devices after resolving host socket restrictions. No emulator session, installation, app launch, device settings mutation, ringing, data deletion or logcat collection occurred. Each row lists the exact expected observation and source path; none is inferred as a runtime success from source. Module paths are `modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/`.

| ID | Procedure and expected result | Status / evidence | Relevant paths |
|---|---|---|---|
| A1 | Create future one-shot with distinctive fixture label; save, navigate list/editor/sleep and back; same ID/settings and one scheduled entry retained | BLOCKED; no device | `alarm.tsx`, `KtAlarmModule.saveAlarm`, `AlarmController.save`, `AlarmStore`, `AlarmScheduler.schedule` |
| A2 | Restart app; existing alarms, all mission parameters/media/settings unchanged | BLOCKED | `AlarmStore`, `use-alarms.ts` |
| B1 | Foreground trigger at configured minute: native FGS, alarm-stream audio, notification and alarm UI | BLOCKED | `Receivers.kt`, `AlarmService.onStartCommand/startRinging` |
| B2 | Background + other app foreground; same delivery, no JS dependency | BLOCKED | `AlarmScheduler`, `AlarmReceiver`, `AlarmService.launchRingingScreen` |
| B3 | Securely locked screen: audible alarm, screen wake/full-screen UI according to access | BLOCKED | manifest, `AlarmActivity`, full-screen notification |
| C1 | Dismiss plain alarm from activity and notification; stop sound/vibration/FGS, clear notification/wake lock | BLOCKED | `AlarmController.dismiss/release`, `AlarmService.onDestroy` |
| C2 | Each math difficulty, shake, walk, squat, typing, barcode: invalid work cannot dismiss; valid full chain stops ring | BLOCKED | `MissionViews`, `WalkMission`, `SquatCounter`, `BarcodeScanner`, `AlarmActivity.onMissionComplete` |
| C3 | Mission notification omits Dismiss; bridge/UI edit/delete/disable/dismiss refused while locked | BLOCKED | `KtAlarmModule`, `Alarm.isLocked`, `AlarmController.dismiss` |
| C4 | Snooze finite/zero/unlimited cases; stop current ring, one pending snooze, correct count; locked mission configuration preserved | BLOCKED | `AlarmController.snooze/release`, `AlarmScheduler` |
| C5 | Repeat alarm fires then next day/weekday armed once; ordinary one-shot disabled on fire | BLOCKED | `AlarmController.onFired`, `Alarm.nextTrigger` |
| C6 | Two alarms together/one minute apart; plain cannot cut short active mission; record mission-to-mission replacement behavior | BLOCKED | `AlarmService.startRinging` |
| D1 | Swipe from recents; scheduled alarm still triggers | BLOCKED | native manifest receiver/scheduler |
| D2 | Kill background app process; next alarm still fires; distinguish OS kill from force-stop | BLOCKED | `AlarmReceiver`, `AlarmService` |
| D3 | Terminate process while mission rings; persisted pending ring resumes, same original cap, no authorization bypass during gap | BLOCKED | `AlarmStore.PendingRing`, sticky service, `Alarm.isLocked` (S4) |
| D4 | Reboot before trigger; test before first unlock and after unlock; alarms rearmed once | BLOCKED | directBootAware manifest, device-protected store, `BootReceiver` |
| D5 | Reboot during mission; resume within cap; expired/invalid pending ring cleared | BLOCKED | `BootReceiver.resumeInterruptedMission`, `AlarmService.resumable` |
| D6 | Clock/timezone change, Doze, battery saver, OEM restrictions; record delivery and battery state | BLOCKED | `BootReceiver`, `AlarmScheduler`, `AlarmPermissions` |
| D7 | One-shot missed while powered off: record current next-day roll-forward behavior | BLOCKED | `Alarm.nextTrigger` |
| E1 | Notification permission deny/restore on API 33+: status refresh; record notification/UI/audio consequences | BLOCKED | `AlarmPermissions`, `useAlarmPermissions`, `AlarmService` |
| E2 | Exact access deny/restore API 31–32: status accurate; inexact fallback may delay, restore rearm | BLOCKED | `AlarmScheduler`, permission-state receiver |
| E3 | API 34+: full-screen deny/restore, overlay deny/restore; mission save requires overlay; record FGS types/start restrictions API 35–36 | BLOCKED | `AlarmPermissions`, `KtAlarmModule`, `AlarmService.startInForeground` |
| E4 | Camera/motion deny, unavailable sensor and offline model launch: approved math fallback, no stuck mission | BLOCKED | `AlarmActivity.onMissionUnavailable`, camera/sensor classes |
| F1 | Matching-identity update over existing install preserves alarm IDs/media/settings/missions and one scheduled occurrence | BLOCKED; installed identity absent | `AlarmStore`, `AlarmMediaStore`, package-replaced receiver |
| F2 | Backup/restore + device transfer on disposable profile preserves intentional data and does not revive stale pending ring | BLOCKED | store/backup manifest (S3) |
| F3 | No duplicate schedules after repeated navigation/rearm/reboot; check several UUIDs and concurrent alarms | BLOCKED | request-code calculation / cancellation in `AlarmScheduler` |
| G1 | All nine audio presets, custom media, sleep sound interruption/timer/notification cleanup | BLOCKED | `PresetMedia`, `AlarmService`, `SleepSoundService` |
| G2 | Startup, each mission/preview, bridge functions/events and navigation in minified release; no native/reflection crash | BLOCKED | R8/ML Kit/Room, provider, `KtAlarmModule`, JS hook cleanup |
| S1 | External explicit BootReceiver random/missing action delivery; no unintended state changes; six genuine actions still recover | BLOCKED | `Receivers.kt`; severity caveat in 17 |
| S4 | Race mutation against mission recovery without completing mission; must retain protection | BLOCKED | `Alarm.isLocked`, controller/bridge guards |
| S5 | Malformed mixed JSON fixture read then write in isolated storage; preserve raw input as evidence, never real data | NOT RUN; fixture harness absent | `AlarmStore.all/upsert/remove`; source failure chain confirmed |

## Manual execution sequence (not executed by Phase 3)

Use a spare/test phone and obtain owner consent for alarm ringing, data edits, permission changes, reboot/Doze and recovery experiments. Retain current personal alarms; use labelled fixtures and record their IDs. Do not clear data or uninstall. Record tester, model, serial, OS/API/build, ABI, artifact SHA-256, certificate, configured/observed trigger times and per-row status/evidence. Runtime lateness needs actual timestamps; no invented latency threshold/result.

Read-only preparation:

```sh
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell getconf PAGE_SIZE
adb shell pm path com.imtushar454.ktAlerm
adb shell dumpsys package com.imtushar454.ktAlerm > /tmp/ktalerm-package.txt
```

Follow report 16 to verify installed certificate and version before installation. If no package exists, installation of the test artifact still requires consent. Only after matching certificate/compatible ABI/version is established and update is authorized:

```sh
adb install -r android/build/phase3/arm64-release.apk
```

Do not execute that command if any compatibility prerequisite is uncertain; do not uninstall or pass downgrade flags. Non-arm64 test devices require the preserved universal APK. AAB split tests require a separately verified bundletool/Play-delivered artifact.

After approval, create a fixture alarm 2–3 minutes ahead via the UI. Record settings, navigate away, inspect schedule, observe ring and execute C1–C5 in separate runs. Capture locally (may contain alarm labels/URIs; redact before sharing):

```sh
adb shell dumpsys alarm > /tmp/ktalerm-alarms.txt
adb logcat -v threadtime -s KtAlarm ActivityManager AndroidRuntime > /tmp/ktalerm-release-logcat.txt
adb shell dumpsys activity services com.imtushar454.ktAlerm
adb shell dumpsys meminfo com.imtushar454.ktAlerm
```

Stop logcat with Ctrl-C; do not clear existing logs. Filter dumpsys to this package when interpreting entries (system alarms are unrelated). Repeat background test after consenting to `adb shell am kill com.imtushar454.ktAlerm` while app is backgrounded; confirm the process actually died. This command may not kill an active FGS: absence of termination is not a passed process-death test. Use controlled OS process-death testing on a disposable test environment for ringing recovery, rather than force-stop as a substitute.

For D4/D5, consent then `adb reboot`; leave device locked for Direct Boot observation. For Doze only with consent: `adb shell dumpsys deviceidle force-idle`, then always restore with `adb shell dumpsys deviceidle unforce`. Change permissions via Android Settings and restore each original state. Force-stop has special platform cancellation semantics; record separately, never claim ordinary process-death reliability from it.

Run on modern arm64 API 34–36, API 31–32 exact-access model, older API 24–30 and an OEM with aggressive battery handling. Include a 16 KiB-page device for native compatibility. Audio audibility/loop quality, secure keyguard presentation, camera/pose accuracy, sensor accuracy and OEM survival require physical observation. Emulator tests may supplement these but cannot certify them. Expand with preserved report 08 cases B1–B14/C1–C13/E1–E4/F1–F6 before production signoff.
