# 07 — Implementation Roadmap (FUTURE PLAN — NOT EXECUTED)

Nothing here has been started. Each batch is independent, small, reversible, and measured against the baseline from doc 05 §6. **No batch touches alarm scheduling, receivers, services, store, activity, manifest flags or the bridge.** Run order reflects priority: verified security → behavior-preserving size → verified resource problems → maintainability.

## Batch 0 — Baseline & facts (no code change; prerequisite)
* **Objective:** replace hypotheses with measurements.
* **Actions:** (1) identify the 170 MB artifact (EAS preview APK?): `unzip -l`, ABI list, `apkanalyzer apk file-size`; (2) build fresh *release* artifacts from `HEAD` (universal, arm64-only, AAB) and record the doc 05 §2 table; (3) `apksigner verify --print-certs` on an EAS artifact (resolves S2); (4) `npm audit --omit=dev` + OSV for Gradle deps (resolves doc 03 §5); (5) `adb shell bmgr` test of backup scope (S3); (6) cold-start baseline (doc 04 §6).
* **Files:** none. **Risk:** none. **Success:** a baseline table committed under `docs/optimization/` and H1 confirmed/refuted.
* **Rollback:** n/a.

## Batch 1 — Ship the right artifact (Category A/B, build config only)
* **Objective:** cut tester/store download size without touching code.
* **Changes:** (a) distribute `production` AAB for Play/internal-track testing (already configured); (b) for `preview` APK add a per-profile ABI limit, e.g. `eas.json` build `env`/gradle command or `android/gradle.properties:reactNativeArchitectures=arm64-v8a` (+`armeabi-v7a` if 32-bit devices matter). Decide device-support policy with the owner first.
* **Files:** `eas.json` and/or `android/gradle.properties`.
* **Expected:** universal APK from ≈160–190 MB (H1) to ≈55–70 MB arm64-only (estimates).
* **Risk:** B (device support). **Tests:** doc 08 §A–C on an arm64 phone (+armv7 if kept); `unzip -l` shows only intended ABIs.
* **Rollback:** revert the single line / profile; rebuild.
* **Success:** APK ABI list as intended; every doc 08 P0 case passes; size within estimate ±10 MB.

## Batch 2 — Release signing hygiene (B; owner decision required)
* **Objective:** stop signing `release` with the public debug key (S2).
* **Precondition:** Batch 0 step (3). If EAS already signs with its own key, only the local `assembleRelease` path needs fixing (use an uncommitted keystore via `~/.gradle/gradle.properties`). If installed copies are debug-key-signed, a key change forces uninstall → warn user (alarms are lost); do it only at a release boundary.
* **Files:** `android/app/build.gradle` (signing block), docs. **Not** `debug.keystore` removal (debug builds need it).
* **Tests:** build signed release; `apksigner verify`; update-over-install test from previous build.
* **Rollback:** restore signing block. **Success:** `apksigner` reports non-debug cert for release.

## Batch 3 — Remove Android-dead Expo UI (B)
* **Objective:** exclude `@expo/ui` from Android only (Z3).
* **Change:** `package.json` → `"expo": { "autolinking": { "android": { "exclude": ["@expo/ui"] } } }` (confirm accepted for a `dependencies` package; docs show module names — test). No source edits.
* **Expected:** dex ≲ 3 MB raw, − Material Symbols font 0.97 MB (est.), ABI-independent.
* **Tests:** `npx expo-doctor`, `tsc`, Android release build, `expo-modules-autolinking resolve --platform android` no longer lists `@expo/ui`, launch → list/editor/sleep/time picker, Expo Router modals.
* **Rollback:** revert `package.json` line. **Success:** size drop measured; app screens identical; iOS autolinking unchanged.

## Batch 4 — Trim unused image formats & resources (B)
* **Changes (separate commits, separate measurements):** (4a) `expo.gif.enabled=false`, `expo.webp.enabled=false` (Z5); (4b) `android.enableShrinkResourcesInReleaseBuilds=true` (Z6).
* **Files:** `android/gradle.properties`.
* **Tests:** release build; open every screen; mission preview of all six types; splash/launcher icons; sleep sounds start/stop.
* **Rollback:** flip property. **Success:** Δ recorded; zero visual/audio differences.

## Batch 5 — Evaluate reanimated/worklets removal (B, investigation first)
* **Objective:** decide with evidence whether `react-native-reanimated`/`-worklets`/`-gesture-handler` can go (Z4).
* **Step 1 (no change):** run Metro with `--dev false` and Expo Atlas/`npm ls`/`why` to see who imports them; test uniwind `active:`/`transition` classes in a throwaway branch.
* **Step 2 only if proven unneeded:** remove packages (dependency change), rebuild.
* **Rollback:** `git revert` + `npm ci`. **Success:** bundle + release build succeed, no runtime `undefined module` errors, Δ ≈3 MB/ABI.

## Batch 6 — Re-encode audio presets (B)
* **Files:** `scripts/generate-audio-presets.py`, `modules/kt-alarm/android/src/main/res/raw/*`, possibly iOS bundle counterpart.
* **Constraints:** keep `R.raw.kt_*` names and `preset:*` ids; verify seamless looping, sample-rate/format support on API 24+.
* **Tests:** listen test; sleep timer finish; alarm with each preset ringtone; `MediaPlayer` error path fallback.
* **Rollback:** restore WAVs. **Success:** ≥2 MB saved, loops gapless.

## Batch 7 — Optional low-value cleanups (A)
* Strip `Log.i/d/v` in release via `proguard-rules.pro` (S9). Only if desired.

## Explicitly DEFERRED (Category C — not to be implemented without a separate compatibility plan)
* `BootReceiver` action validation (S1) · `PendingRing`-aware lock (S4) · non-destructive `AlarmStore` parsing (S5) · permission removals (`INTERNET`, external-storage) (S6) · startup-initializer removal · ML Kit unbundling (Z8) · any scheduler/service/activity/store/manifest-flag/keep-rule/`androidx.work` change · `AlarmStore` caching (P1).
  *If the owner wants S5 or S1 anyway:* each as its own PR with unit tests (corrupt-JSON fixtures; action matrix) and the full physical-device matrix (doc 08 §D–F).

## Order summary
0 → 1 → 2 → 3 → 4 → 5 → 6 → 7. Batches 3–6 can be reordered; never combine them. Gate every batch on: `npx tsc --noEmit`, `npx expo lint`, JVM unit tests (`./gradlew :kt-alarm:testDebugUnitTest` in a scratch clone), release build, doc 08 P0 pass.

## Prerequisites
Physical Android devices: ≥1 modern (API 34–36) arm64 phone, ≥1 older (API 24–30), ideally one aggressive-OEM device (Xiaomi/Oppo/Samsung). `adb`, `bundletool`, `apksigner`, `apkanalyzer` (present), emulator images for API 31–36.
