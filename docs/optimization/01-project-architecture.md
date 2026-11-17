# 01 — Project Architecture (Phase 1 audit)

Status: **investigation only**. Nothing in this repo was changed except the creation of `docs/optimization/*.md`.
Legend used across all documents: **[V] verified** against a file/artifact · **[P] plausible risk** (code reading, not reproduced) · **[U] unverified hypothesis**.

Audit baseline: commit `f6eedf9` (branch `main`, working tree clean at start and end).

## 1. Actual versions [V]

| Item | Value | Source |
|---|---|---|
| Expo SDK | `expo ~58.0.25` (SDK 58) | `package.json` |
| React Native | `0.88.1`, New Architecture on (`newArchEnabled=true`) | `package.json`, `android/gradle.properties` |
| React | `19.3.0`; React Compiler experiment **on** (`experiments.reactCompiler`) | `package.json`, `app.json`; `babel-plugin-react-compiler` is installed |
| Router | Expo Router `~58.0.31`, typed routes on, routes in `src/app/` | `app.json`, `src/app/` |
| JS engine | Hermes (`hermesEnabled=true`); shipped bundle is Hermes bytecode (magic `c6 1f bc 03 c1 03 19 1f`) | `gradle.properties`, release APK |
| Language | TypeScript `~6.0.3`, `strict: true` | `package.json`, `tsconfig.json` |
| Styling | Tailwind 4 + `uniwind` 1.13 (`className`), `src/global.css` | `metro.config.js` |
| Package manager | npm (`package-lock.json`, 457 KB); no bun/yarn lock | repo root |
| Android Gradle Plugin | `9.2.1` (from RN version catalog; AGP 9.2.1 is present in the Gradle cache) | `node_modules/react-native/gradle/libs.versions.toml` |
| Gradle | `9.4.1` | `android/gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | `2.2.10`, JVM target 17 | `android/app/build/kotlinToolingMetadata` |
| Android SDK | minSdk **24**, targetSdk **36**, compileSdk **37**, build-tools 37.0.0, NDK 27.1.12297006 | merged manifest (apkanalyzer) + RN catalog |
| Native build | `android/` and `ios/` are **committed and hand-maintained** (README: "EAS builds these native projects; it does not regenerate them"). `expo prebuild` must not be run. | `README.md`, `.gitignore` |
| EAS | `preview` = **APK** (internal), `production` = **app-bundle** (AAB); `appVersionSource: local` | `eas.json` |
| Applications id | `com.imtushar454.ktAlerm`, versionCode 1 / 1.0.0 | `android/app/build.gradle` |

## 2. Repository structure

```
src/app/            _layout.tsx (Stack), index.tsx (alarm list), alarm.tsx (editor, 640 lines), sleep.tsx (sleep sounds)
src/components/     permission-card, uninstall-protection-card, sleep-sound-controls, time-picker{,.android,.ios}
src/hooks/          use-alarms.ts (alarms, permissions, clock tick), use-sleep-sound.ts
src/lib/            missions.ts (mission metadata mirror of Mission.kt), alarm-format.ts, media-presets.ts
modules/kt-alarm/   local Expo module "KtAlarm" — JS API (index.ts), Android Kotlin, iOS Swift
  android/src/main/java/expo/modules/ktalarm/   22 Kotlin files (~165 KB) — THE alarm engine
  android/src/main/res/raw/                      9 synthesized WAV presets (3.07 MB)
  android/src/test/                              3 JVM unit tests
android/            committed native project (MainActivity/MainApplication only + manifest/resources)
ios/                committed Xcode project (AlarmKit, iOS 26+)
plugins/            with-kt-alarm-ios.js (iOS AppDelegate edit only)
scripts/            branding / preset generators (build-time helpers, not shipped)
FEATURE_CODE_GUIDE.md (48 KB), IOS_PARITY.md, README.md — existing docs (not modified)
example/            git-ignored scratch dir (3 subfolders: assets, scripts, src) — template leftover, not tracked [V]
```
JS is small: 1,496 lines across 15 files. The product is mostly the native module.

## 3. Native integration architecture

* `modules/kt-alarm` is an **Expo Modules API** module (`expo-module.config.json` → `expo.modules.ktalarm.KtAlarmModule`), autolinked (`kt-alarm@1.0.0` appears in `expo-modules-autolinking resolve`). It is also referenced from TS via `tsconfig.json` path `kt-alarm`.
* JS entry: `requireOptionalNativeModule('KtAlarm')` (`modules/kt-alarm/index.ts:end`) → `null` on web/Expo Go.
* Bridge surface (`KtAlarmModule.kt`): sync `getAlarms`, `snooze`, `dismiss`, `previewMission`, `getSleepSoundState`, `getPermissionStatus`, settings openers, uninstall-protection, `getRingingAlarmId`; async `saveAlarm`, `deleteAlarm`, `setAlarmEnabled`, `scanBarcode`, `pickAlarmMedia`, `startSleepSound`, `stopSleepSound`, permission requests; native view `AlarmTimePickerView`; events `onAlarmsChanged`, `onRingingChanged`, `onSleepSoundChanged` (fan-out from the in-process `AlarmEvents` bus).
* **Alarm engine does not depend on the JS runtime** (see doc 02): AlarmManager → `AlarmReceiver` → `AlarmService` (foreground) → `AlarmActivity` (plain `android.app.Activity`, UI built in code) → mission views → `AlarmController`. State lives in device-protected `SharedPreferences` JSON (`AlarmStore`).
* Other autolinked Android packages in the build [V]: `@expo/dom-webview`, `@expo/log-box`, `@expo/ui`, `expo`, `expo-asset`, `-constants`, `-device`, `-file-system`, `-font`, `-image`, `-keep-awake`, `-linking`, `-modules-core`, `-router`, `-splash-screen`, `-status-bar`, `-system-ui`, `-web-browser` (+ RN autolinked: screens, safe-area-context, gesture-handler, reanimated, worklets).
* Direct JS imports found in `src/` + module: `kt-alarm`, `react`, `react-native`, `expo-router`, `react-native-safe-area-context`, `expo`, `expo-image` (1), `@expo/ui` (iOS-only file). No JS import of `expo-constants/-device/-font/-linking/-web-browser/-status-bar/-symbols/-glass-effect`, reanimated, worklets, gesture-handler, react-dom, react-native-web. These are **not** thereby removable (Expo Router peer deps, config plugins, transitive native autolinking — see doc 05).

## 4. Android component map (merged release manifest, `apkanalyzer manifest print`, stale release APK — see doc 05)

| Component | Type | Exported | Role |
|---|---|---|---|
| `com.imtushar454.ktAlerm.MainActivity` | Activity | **true** (LAUNCHER + `ktalerm://` VIEW) | RN host, portrait, `singleTask` |
| `expo.modules.ktalarm.AlarmActivity` | Activity | false | Full-screen ringing + all missions, `showWhenLocked`, `turnScreenOn`, `singleInstance`, own task affinity |
| `…ScanActivity` | Activity | false | Barcode registration (camera) |
| `…AlarmService` | Service | false | FGS `mediaPlayback|systemExempted`; sound, vibration, wake lock, notification, volume guard |
| `…SleepSoundService` | Service | false | FGS `mediaPlayback`; user-started sleep sounds |
| `…AlarmReceiver` | Receiver | false | AlarmManager target (`ACTION_FIRE`, `ACTION_RESUME`) |
| `…AlarmActionReceiver` | Receiver | false | Notification Snooze/Dismiss |
| `…BootReceiver` | Receiver | **true** | BOOT/LOCKED_BOOT/PACKAGE_REPLACED/TIME_SET/TIMEZONE_CHANGED/SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED → re-arm |
| `…SleepSoundStopReceiver` | Receiver | false | Sleep timer stop |
| `…UninstallProtectionReceiver` | Receiver | true (+`BIND_DEVICE_ADMIN`) | Device admin with no policies |
| Library components | — | exported ones: `androidx.work…SystemJobService` (BIND_JOB_SERVICE), `androidx.profileinstaller.ProfileInstallReceiver` and `androidx.work…DiagnosticsReceiver` (both `DUMP`-protected) | Standard, permission-guarded |

Permissions (module manifest + app manifest): `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM(max 32)`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW`, `WAKE_LOCK`, `VIBRATE`, `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE(+_MEDIA_PLAYBACK,+_SYSTEM_EXEMPTED)`, `RECEIVE_BOOT_COMPLETED`, `CAMERA`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `INTERNET`, `READ/WRITE_EXTERNAL_STORAGE(max 32)`.

## 5. Critical dependencies of the native module (`modules/kt-alarm/android/build.gradle`)
CameraX 1.4.2 (camera2, lifecycle, view) · ML Kit `barcode-scanning 17.3.0` (bundled model) · ML Kit `pose-detection 18.0.0-beta5` (bundled model; beta) · `androidx.work 2.11.2` (pinned only to fix R8 crash with ML Kit's old WorkManager/Room) · consumer R8 keep rules in `modules/kt-alarm/android/proguard-rules.pro` (ML Kit `ComponentRegistrar`, Room `RoomDatabase`). **These keep rules are load-bearing: release-only crashes occur without them (documented in comments).**

## 6. Build & distribution configuration [V]
* `android/gradle.properties`: `reactNativeArchitectures=armeabi-v7a,arm64-v8a,x86,x86_64`, `android.enableMinifyInReleaseBuilds=true`, `android.enablePngCrunchInReleaseBuilds=true`, `expo.useLegacyPackaging=false`, `expo.gif.enabled=true`, `expo.webp.enabled=true`, `edgeToEdgeEnabled=true`, `EX_DEV_CLIENT_NETWORK_INSPECTOR=true` (dev-client property; no dev-client package installed).
* `android/app/build.gradle`: `shrinkResources` defaults to **false** (property `android.enableShrinkResourcesInReleaseBuilds` not set); **release uses `signingConfigs.debug`** (committed `android/app/debug.keystore`, password `android`); `proguard-android-optimize.txt` + `android/app/proguard-rules.pro` (reanimated + turbomodule keeps only).
* `package.json` scripts: `build:apk` → `eas build --profile preview` (universal APK), `build:apk:local` → `./gradlew assembleRelease`.
* Tests: 3 JVM unit tests (`SleepDeadlineTest`, `SquatCounterTest`, `WalkCounterTest`); no JS tests; no instrumentation tests. **Not executed in this phase** (running Gradle would create build output).
* `npx tsc --noEmit` was run as a read-only baseline: it printed no diagnostics (exit code not separately captured). `expo lint` not run.

## 7. Areas not fully investigated
iOS Swift sources (~85 KB; AlarmKit) read only at file-list level; `AlarmTimePickerView.kt`, `ScanActivity.kt`, `SquatCounter.kt`, `WalkCounter.kt`, `Ui.kt`, parts of `MissionViews.kt` (math/typing/squat UI) were skimmed by outline/grep, not line-by-line; no device or emulator was used.
