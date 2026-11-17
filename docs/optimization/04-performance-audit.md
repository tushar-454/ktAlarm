# 04 — Performance & Resource Audit

Everything below is from code reading and artifact inspection; **no profiling, benchmark or device run was performed**, and no timing numbers are claimed. Categories refer to doc 06.

## 1. Proven (by code) — inefficiency exists, magnitude unmeasured

| ID | Observation | Evidence | Real-world weight |
|---|---|---|---|
| P1 | `AlarmStore` re-parses the entire alarms JSON on every `all/get/upsert/remove`; `upsert` = parse + serialize + synchronous `commit()` | `AlarmStore.kt:33-57,84-89`; `KtAlarmModule.getAlarms` calls `all()` then sorts/maps; `Alarm.toMap` calls `nextTrigger()` (Calendar math) and `isLocked()` per alarm | O(n) with tiny n (a handful of alarms). Sync `getAlarms`/`snooze`/`dismiss` run on the JS thread. Likely sub-millisecond–few ms; **needs measurement before any change**. Cat. C to touch (persistence). |
| P2 | Sync bridge calls on every refresh: `getAlarms()` + `getPermissionStatus()` (7 system-service queries incl. `canScheduleExactAlarms`, `canUseFullScreenIntent`) on each `AppState` active and each native event | `use-alarms.ts:12-38`, `AlarmPermissions.status` | Low frequency; fine. |
| P3 | `volumeGuard` polls every 400 ms on the main looper while a *mission* alarm rings (≤ 60 min cap) | `AlarmService.kt:106-119` | Bounded, necessary for the "can't lower volume" feature; wake lock is held anyway. Leave. |
| P4 | `useNow()` re-renders the home screen every 30 s (all `AlarmRow`s) | `use-alarms.ts:52-57`, `index.tsx:20-24` | Trivial (few rows); React Compiler is enabled. No change recommended. |

## 2. Likely issues that need measurement

1. **Cold start work at process creation.** The merged manifest carries `androidx.startup.InitializationProvider` (WorkManager initializer, ProfileInstaller) plus ML Kit/Room/WorkManager that arrive only for the pose-detection dependency (module `build.gradle` comment). WorkManager initialises at app start although the app never schedules work directly [U whether ML Kit uses it at runtime]. Measure: `adb shell am start -W`, Perfetto/`reportFullyDrawn`, compare with/without. Removing initializers is a manifest change → Category C until measured.
2. **Dex size / class loading.** Release dex = 11.9 MB uncompressed (2 files). Retained classes by package (R8 `mapping.txt`, stale release build): `com.google.android` 2,379; `androidx.compose.*` ≈3,181 (ui 1,037 / foundation 957 / material3 748 / runtime 275 / animation 164) + `expo.modules.ui` 682; `kotlin.reflect.jvm` 1,026; `com.facebook.react` 968; `androidx.camera` ≈480. Compose + `expo.modules.ui` come from `@expo/ui` Android which **no JS code uses on Android** (only `time-picker.ios.tsx` imports `@expo/ui/swift-ui`). Effect on startup is [U]; effect on size in doc 05.
3. **ML Kit models.** Pose and barcode detectors are created in `CameraView.start()` only when a camera mission starts (not at app start) [V]. First-detection model load is covered by a 30 s watchdog. Timing on low-end phones unmeasured.
4. **Alarm UI startup.** `AlarmActivity` is pure code-built views (no XML inflation, no RN) [V]; background JPEG decode is on `AlarmMediaStore.io` executor; imported images capped at 1600 px. Time-to-first-frame after full-screen intent is unmeasured.
5. **JS bundle size/parse.** 2.58 MB Hermes bytecode (stale release APK). Hermes mmaps bytecode; parse cost small. Not measured. Expo Atlas could show contributors.
6. **Wake-lock lifetime.** `PARTIAL_WAKE_LOCK` with timeout `silenceAfter+60 s` (≤ 61 min mission, 11 min plain), released in `onDestroy` [V]. Good. `SleepSoundService` uses `MediaPlayer.setWakeMode` for up to 720 min by user choice.

## 3. Resource lifecycle review (native) — findings: none critical

| Area | Result |
|---|---|
| `AlarmEvents` listeners | `KtAlarmModule` adds in `OnCreate`, removes in `OnDestroy` [V]; `AlarmActivity` add/remove in `onCreate/onDestroy` [V]. `CopyOnWriteArraySet` thread-safe. |
| Handlers | `AlarmService.onDestroy` → `removeCallbacksAndMessages(null)`; activity same [V]. |
| MediaPlayer | Released in `stopFeedback`; failed candidates released in the fallback loop [V]. |
| Sensors | `ShakeMission/WalkMission.stop()` unregister; `onHostHidden` stops, `onHostShown` restarts [V]. |
| Camera | `CameraView.stop()` clears analyzer, unbinds, destroys custom lifecycle, shuts executor, closes detector; watchdog covers stalls [V]. Well engineered. |
| Static singletons | `SleepSoundService.instance` (cleared in `onDestroy`), `AlarmMediaStore.io` (single thread executor, never shut down — process-lifetime, acceptable), `AlarmStore` `@Synchronized`. |
| Receivers | One dynamic receiver, registered/unregistered symmetrically [V]. |
| Concurrency | `AlarmService.activeAlarmId` and `SleepSoundStore` fields are `@Volatile`. `AlarmStore` operations synchronized; read-modify-write across calls (e.g. `get` then `upsert` in `onFired`) is not atomic across methods [P, low]. |
| Main-thread I/O | `AlarmStore.commit()` runs on whichever thread calls it: JS thread for sync functions, receiver main thread for `onFired`. Tiny writes; keep as is (comment explains why). |

## 4. Low-risk opportunities (Category A/B candidates)
* **Release `shrinkResources`** (currently off): small effect (res ≈1.2 MB compressed) [size unknown until measured]; Category B because it needs a full release regression pass.
* **Audio presets** (3.07 MB raw WAV in `res/raw`): conversion to a compressed loop-safe format could save space; looping continuity and `MediaPlayer` gapless behavior must be verified → Category B (size doc 05).
* No JS-side change is justified by evidence: no large lists, no heavy computation, no unreleased subscriptions found (`useAlarms`, `useSleepSound`, `useNow` all clean up). Do **not** add `memo/useCallback` (React Compiler on; no demonstrated problem).

## 5. Speculative micro-optimizations (do not pursue)
Caching parsed alarms in memory, batching `commit()`→`apply()` (would weaken kill-safety guarantee), debouncing the 30 s tick, lazy-loading screens in a 3-route app.

## 6. Measurement gaps / plan
Cold start (`am start -W`, 10 runs, release APK, before/after any manifest/dependency change) · time from `AlarmReceiver.onReceive` to first `AlarmActivity` frame (Perfetto/`logcat -v time` markers) · process RSS (`dumpsys meminfo`) idle vs during squat mission · battery (Battery Historian) for 60-min mission ring · installed size · JS bundle composition (Atlas) · `getAlarms()` latency with 1/10/50 alarms (microbenchmark).
