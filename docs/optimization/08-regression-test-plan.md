# 08 — Regression Test Plan (for the NEXT phase)

Run against the **changed release artifact** (never debug) and compare with the baseline artifact built from the same parent commit. Priority P0 = must pass to ship any batch; P1 = pass before release; P2 = nice-to-have.
Test type key: **U** unit (JVM) · **S** static (tsc/lint/doctor/manifest diff) · **B** build verification · **E** emulator · **D** physical device only · **M** manual/visual. Items marked ⚠ **cannot be validated by automated tests alone**.

## A. Static & build gates (every batch)
| # | Check | Type | Pass criteria |
|---|---|---|---|
| A1 | `npx tsc --noEmit`, `npx expo lint`, `npx expo-doctor` | S | no new errors/warnings vs baseline |
| A2 | JVM tests: `SleepDeadlineTest`, `SquatCounterTest`, `WalkCounterTest` | U | all pass (run in scratch clone/CI, not in this phase) |
| A3 | Merged-manifest diff (`apkanalyzer manifest print`) vs baseline | S | **identical** for permissions, exported flags, services, receivers, FGS types — except intentional batch edits |
| A4 | Release build succeeds; `zipinfo` category table; ABI list | B | as planned; no `lib/` ABI surprises |
| A5 | R8: `mapping.txt` still contains `KtAlarmModule`, `AlarmService`, `AlarmReceiver`, `BootReceiver`, ML Kit registrars kept; app does not crash at launch | B/E | no `NullPointerException` in `BarcodeScanning.getClient()`/`PoseDetection.getClient()`/Room init |
| A6 | `apksigner verify --print-certs` | B | expected certificate |

## B. Functional matrix (physical device, release build)
| # | Scenario | Type | Pass criteria |
|---|---|---|---|
| B1 | Create alarm (one-shot), edit time/label/days/vibrate/snooze, delete | E/D | persisted after app kill; list shows correct "rings in …" |
| B2 | Mission setup for each of 6 types + chain of ≥3 | E/D | save requires overlay permission (message shown); invalid barcode mission rejected |
| B3 | **Mission preview** for each type from editor | D | opens native screen, no sound, Close works, no alarm state change |
| B4 | Valid mission completion each type (math all difficulties, shake, walk with step detector and accelerometer fallback, squat, typing, barcode) | ⚠ D | alarm stops, service gone, notification removed, wake lock released, snooze counter reset |
| B5 | Invalid completion (wrong math/phrase/other barcode, paste in typing, idle 60 s) | D | not dismissed; idle returns to loud ringing, mission restarts |
| B6 | Camera/sensor unavailable (deny camera, no step detector) | D | falls back to math with toast; ring never stuck |
| B7 | Dismiss non-mission alarm (button, notification action, volume key=snooze) | D | per spec |
| B8 | Snooze: limit N, "x left", unlimited; mission alarm snoozed then try delete/disable/edit via UI | D | blocked with `ERR_ALARM_LOCKED` message |
| B9 | Recurring alarms (Mon–Fri, weekend, every day) incl. DST switch date | ⚠ D (clock manipulation) | next trigger correct, re-armed after each ring |
| B10 | Two/three alarms simultaneously and 1 min apart; plain alarm during mission alarm | D | mission alarm not cut short ("Missed alarm" notification for plain one) |
| B11 | Custom ringtone / background import (large file, wrong MIME, corrupt audio), presets | D | errors shown; saved media plays at ring; orphans cleaned |
| B12 | Sleep sounds: start, timer end, Stop action, alarm rings during sleep sound, headphones unplug | D | stops as specified; never alters scheduled alarms |
| B13 | Uninstall protection enable/disable, disable while locked | D | refused while locked; `onDisableRequested` re-raises mission |
| B14 | Permission card flows (notifications, exact alarm, full-screen intent, battery, overlay, auto-start) | D | state refreshes on return from settings |

## C. System-state matrix (physical device — ⚠ all)
| # | State | Pass criteria |
|---|---|---|
| C1 | Screen locked (secure PIN) | full-screen UI over keyguard, screen turns on, sound on ALARM stream |
| C2 | Screen unlocked, other app foreground | AlarmActivity appears (overlay permission) |
| C3 | App process swiped away / `adb shell am kill` | alarm still fires |
| C4 | **Force-stop** (Settings) | document platform behavior: alarms cancelled until next launch/boot — **not a failure** of this app; verify no worse than baseline |
| C5 | Device reboot (before first unlock = Direct Boot, after unlock) | alarms re-armed; unlocked-before-boot alarm rings; interrupted mission alarm resumes ≤ cap |
| C6 | Time / timezone change, 12h↔24h | re-armed to correct wall time |
| C7 | Doze (`adb shell dumpsys deviceidle force-idle`), battery saver, app standby bucket "restricted" | alarm exact (±seconds) |
| C8 | DND on, silent mode, media volume 0 | alarm audible (alarm stream), vibrate per setting |
| C9 | Kill service while mission ringing (`am stopservice` / low-memory) | resumes in ~1–1.5 s, same `PendingRing` start time |
| C10 | App update install over previous version (`adb install -r`) | `MY_PACKAGE_REPLACED` re-arm; alarms/media/sleep prefs preserved ⚠ same signing key required |
| C11 | Data preservation across update, and (if S3 changed) backup/restore | alarms intact; no duplicate scheduling |
| C12 | Exact-alarm permission revoked (API 31–32), notification permission revoked, full-screen-intent revoked (API 34+) | graceful fallback as in source; status card reflects |
| C13 | Missed one-shot while powered off | record current behavior (rolls to next day, no notification) — must be unchanged |

## D. Android-version matrix
API 24/26 (no notif channels pre-26 handled), 29–30, 31–33 (exact-alarm model), 34 (FGS types, full-screen-intent revocable), 35–36 (FGS-start restrictions — confirm `AlarmReceiver → startForegroundService` still allowed) — each: B1, B4 (math), C1, C5, C7.

## E. Bridge & JS tests
| # | Check | Type |
|---|---|---|
| E1 | Every function in `modules/kt-alarm/index.ts` callable; events `onAlarmsChanged/onRingingChanged/onSleepSoundChanged` fire once per change | E/D |
| E2 | Error codes `ERR_ALARM_LOCKED`, `ERR_OVERLAY_REQUIRED`, `ERR_INVALID_MISSION`, `ERR_CAMERA_PERMISSION` reach JS with `.code` | E |
| E3 | Subscription cleanup: navigate editor↔list repeatedly, no duplicate refreshes (logcat counters) | E |
| E4 | Web/Expo Go: `KtAlarm` null branch renders the "Native alarm module not found" message | E |

## F. Security regression
| # | Check | Pass |
|---|---|---|
| F1 | `adb shell am broadcast -n …/.AlarmReceiver`, `…/.AlarmActionReceiver`, `am start -n …/.AlarmActivity`, `am startservice …/.AlarmService` from adb shell (non-root) | refused (not exported) |
| F2 | `am broadcast -n …/.BootReceiver` (no action; with random action) | record baseline behavior (S1); after any future fix: no effect |
| F3 | `am start -a VIEW -d ktalerm://alarm` / `ktalerm://sleep?id=x` | opens screens only; no state change without user action |
| F4 | Attempt `dismiss`/`delete`/`disable` on a ringing mission alarm through UI and bridge | refused |
| F5 | Merged manifest has no new exported component; `usesCleartextTraffic` absent in release | S |
| F6 | `run-as`/backup inspection on release (non-debuggable) | no readable app data (`debuggable` false) |

## G. Release verification
Release build is minified, Hermes bytecode (`assets/index.android.bundle` starts with bytes `c6 1f bc 03 c1 03 19 1f`), no source maps, no dev menu, no network inspector; cold start under baseline ±10 %; no crash in `logcat` on first launch of each mission type (R8 reflection cases: ML Kit, Room/WorkManager).

## H. Pass/fail rules
* **Fail the batch** on any P0 failure, any change in A3 not intended by the batch, any alarm that fails to ring in B/C, or size gain smaller than half the estimate with no explanation.
* ⚠ Physical-device-only items (cannot be automated): B4, B9, C1–C13, D, F6, OEM battery-manager behavior, camera/pose accuracy, audio loop quality.
* Record: device model, OS version, build id, artifact checksum, tester, result, logcat excerpt for each failure.
