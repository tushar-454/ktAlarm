# 05 — APK Size Analysis

All numbers are from read-only inspection (`zipinfo`, `unzip -v`, `apkanalyzer manifest print`, R8 `mapping.txt`/`usage.txt`, Gradle-cache AARs). **No build was run, no tool installed.** Estimates are labelled; "unknown" means it cannot be derived honestly without a new build.

## 1. The reported 170 MB+ — what artifact is it?

**No local artifact is 170 MB.** Local artifacts (`android/app/build/outputs/apk/`):

| Artifact | File size | ABIs inside | Freshness |
|---|---|---|---|
| `debug/app-debug.apk` | 118,992,451 B (113.5 MiB) | **x86_64 only** (`lib/x86_64/*`) | built 2026-11-16 15:05 — before commits `bb950c4`/`f37c0e4`/`d42d49e`/`f6eedf9` |
| `release/app-release.apk` | 60,750,238 B (57.9 MiB) | **x86_64 only** | built 2026-11-14 12:11 — **stale**: no `res/raw/*.wav`, R8 mapping has no `WalkMission`/`SleepSoundService`; it predates walk/sleep/preset features (`8aaf281`, `dc60b5d`) |

Both were evidently built with `-PreactNativeArchitectures=x86_64` (emulator builds), not with the committed property.

**Hypothesis H1 (most consistent with the evidence) [U — not measured]:** the 170 MB+ artifact is the EAS `preview` profile output (`eas.json`: `buildType: apk`, universal) built from `android/gradle.properties:reactNativeArchitectures=armeabi-v7a,arm64-v8a,x86,x86_64` with `expo.useLegacyPackaging=false` (native libs stored **uncompressed**, page-aligned in the APK).
Reasoning: one ABI of native code is already 42.2 MB in the release build; four ABIs ≈ 4 × (≈35–42 MB; arm64 libs are smaller than x86_64 for several libraries per AAR contents, e.g. `libbarhopper_v3` 4.9 vs 5.9 MB, `libavif_android` 0.9 vs 1.9 MB) ≈ 140–170 MB, plus ≈ 20 MB ABI-independent content (dex, bundle, models, resources) ⇒ ≈ 160–190 MB. A debug APK for 4 ABIs would be ≈ 4 × 80 MB ≈ 320 MB, a one-ABI debug APK is 119 MB, so neither fits 170 MB. **To confirm:** open the EAS build page artifact size or run `unzip -l <that.apk> | grep ' lib/' | awk -F/ '{print $2}' | sort -u`.

## 2. Measured breakdown — release APK (x86_64 only, stale; compressed bytes inside the 60.3 MB zip)

| Part | Compressed | Uncompressed | Share |
|---|---|---|---|
| Native libs `lib/x86_64` (31 files, stored) | 42,229,680 | 42,229,680 | **70 %** |
| `assets/` (22 files) | 9,370,215 | 9,419,009 | 15.5 % |
| `classes*.dex` (2) | 5,544,190 | 11,929,220 | 9.2 % |
| `resources.arsc` | 1,816,916 | 1,816,916 | 3.0 % |
| `res/` (1,002 files) | 1,175,940 | 2,052,324 | 2.0 % |
| other (META-INF, kotlin builtins, okhttp publicsuffix, commons-codec rules…) | ≈0.2 MB | | <0.5 % |

Debug (x86_64, newer): libs 79.8 MB, dex 25.4 MB compressed (65.5 MB raw), assets 6.8 MB, res 3.8 MB.

### Native libraries (release, x86_64, bytes)
| Lib | Size | Origin | Note |
|---|---|---|---|
| `libxeno_native.so` | 11,436,600 | ML Kit `mediapipe-internal 17.0.0-beta10` (found in Gradle cache AAR; pulled by `pose-detection`) | Squat mission. Largest single item (27 % of native). |
| `libreactnative.so` | 7,257,672 | RN 0.88 | required |
| `libbarhopper_v3.so` | 5,909,280 | ML Kit `barcode-scanning` | barcode mission + registration |
| `libhermesvm.so` | 2,832,176 | Hermes | required |
| `libavif_android.so` | 1,892,960 | `org.aomedia.avif` (expo-image) | AVIF decoder; app uses AVIF? [U] |
| `libreanimated.so` + `libworklets.so` | 1,719,320 + 1,241,408 | react-native-reanimated/worklets | no `src/` import; see §4 |
| `libexpo-modules-core.so` | 1,542,400 | Expo | required |
| `libc++_shared.so` | 1,252,080 | NDK | required |
| `libreact_codegen_rnscreens.so` + `librnscreens.so` | 1,247,712 + 100,768 | react-native-screens | required by Expo Router stack |
| `libappmodules.so` | 892,256 | RN app codegen | |
| `libzstd-kmp.so` | 816,568 | `com.squareup.zstd:zstd-kmp-android 0.4.0` | origin dependency not traced [U] |
| `libnative-imagetranscoder` 720,432, `libstatic-webp` 561,176, `libnative-filters` 270,784, `libgifimage` 303,032, `libanimation-decoder-gif` 261,216 | ≈2.1 MB | Fresco (RN `Image`) — enabled by `expo.gif.enabled`/`expo.webp.enabled=true` | |
| remaining (gesture-handler 88 KB, jsi 419 KB, fbjni 173 KB, safe-area codegen 156 KB, expo-modules-v2/jsi/worklets ≈0.56 MB …) | ≈2.6 MB | | |

### Assets
ML Kit pose models 2.96 + 2.81 MB (+ benchmarks/allowlist ≈0.12 MB); `index.android.bundle` 2.58 MB (Hermes bytecode); ML Kit barcode models ≈0.88 MB; `mobile_acceleration/gpu_compatibility.bin` 49 KB.
Newer-than-release additions (from source): 9 WAV presets in `res/raw` = 6×384,044 + 3×256,044 = **3,072,396 B** (noise loops compress poorly in zip); present in debug APK, absent in the stale release APK.

### Resources
`res/V6.ttf` 973,756 B = **"Material Symbols Regular"** (checked with `fc-scan`) — not referenced by app JS; its source is presumably the `@expo/ui` Android (Compose Material) module [U — origin not traced]. WebP/PNG launcher + misc ≈0.3 MB. Current tracked splash PNGs total ≈0.97 MB across 10 density files (night variants have the same byte size as day variants) — impact on a fresh build unmeasured because the stale APK predates the splash refresh.

### Dex / R8
R8 ran (`android.enableMinifyInReleaseBuilds=true`; mapping + usage files exist). Retained: 13,816 classes; Compose-related ≈3,181 + `expo.modules.ui` 682 ⇒ ≈ 28 % of retained classes by count (rough proxy only; bytes unknown; proportional guess ≤ 3 MB of the 11.9 MB raw dex — **estimate**). `usage.txt` shows R8 already removed 4,203 further Compose classes. `kotlin.reflect.jvm` 1,026 classes (Expo Modules). `androidx.work` 155, Glide 271 (via expo-image), Fresco imagepipeline 203, okhttp 176.

## 3. Release configuration findings

| Setting | Value | Assessment |
|---|---|---|
| R8 minify | on, `proguard-android-optimize.txt` | ✅ |
| `shrinkResources` | **off** (property unset → default `false`, `android/app/build.gradle`) | small potential; unknown size |
| PNG crunch | on | ✅ |
| Hermes | on, bytecode verified | ✅ |
| `reactNativeArchitectures` | **4 ABIs** | ❗ dominant driver of the universal APK size |
| `expo.useLegacyPackaging` | `false` (uncompressed libs) | Bigger APK *file*, smaller install & Play download; correct for AAB; for sideloaded APK a compressed APK would be smaller on the wire (install bigger). Trade-off, not a bug. |
| Native debug symbols | separate `native-debug-symbols.zip` (27.9 MB), not in APK | ✅ |
| Source maps | none found in APK | ✅ |
| Baseline profile | `.dm` files produced | ✅ |
| Dev tooling in release | none found; `EX_DEV_CLIENT_NETWORK_INSPECTOR=true` has no dev-client package; `usesCleartextTraffic` only in debug manifests | ✅ |
| Gif/WebP in RN `Image` | enabled (Fresco libs ≈2.1 MB per ABI + dex) | app assets are PNG; only `expo-image` import is 1 file — see §4 |
| AAB | `production` profile = `app-bundle` | ✅ Play splits per ABI/density |

## 4. Size reduction opportunities (all unmeasured unless stated)

| # | Opportunity | Evidence | Estimated gain | Risk / impact | Cat. |
|---|---|---|---|---|---|
| Z1 | Restrict `reactNativeArchitectures` for APK builds (e.g. `arm64-v8a` only, or `arm64-v8a,armeabi-v7a`); keep AAB for Play | doc §1–2 | Dropping x86+x86_64: ≈ −40–45 % of universal APK (≈ −65–75 MB if H1 holds). arm64-only: universal ≈ 55–70 MB (**estimate** based on one-ABI release 60.7 MB + newer assets ≈ 3 MB − smaller arm64 libs). | Build-config only; no Kotlin/JS change. Loses x86/x86_64 emulators/Chromebooks and 32-bit-only phones (if armeabi-v7a dropped). Android 7+ ARM phones fine. Alarm behavior unaffected. | **B** (device-support decision) |
| Z2 | Use the **AAB** (already `production`) for any store/tester distribution; APK only for sideload | Expo docs (below): Play download is device-tailored; SDK 50 sample release APK 62 MB vs Play 11.7 MB | Download size per device ≈ one ABI + shared assets ⇒ roughly the one-ABI figure (≈ 55–65 MB here, uncompressed-libs counted compressed by Play ⇒ likely lower) [estimate] | None for code | **A** (process) |
| Z3 | Exclude `@expo/ui` from **Android** autolinking (iOS-only use) | `time-picker.ios.tsx` only import; Compose+`expo.modules.ui` ≈ 28 % of retained classes; `Material Symbols` font 0.97 MB (origin unconfirmed) | dex ≲ 3 MB raw, ≈1–1.5 MB compressed (guess) + font 0.97 MB; ABI-independent so applies to AAB too | Docs: `expo.autolinking.android.exclude` in `package.json` (Expo autolinking page); applies to Expo modules; whether it covers a `dependencies` package is **not stated** → test. `expo-router` lists `@expo/ui` as *optional* peer — verify no Android runtime import (grep of `expo-router/build` found none matching but not exhaustively checked). Must confirm `time-picker.android.tsx` uses only `kt-alarm`'s native view (it does, by file list). | **B** |
| Z4 | Remove/disable reanimated + worklets (+ possibly gesture-handler) | no `src/` import; `uniwind` dist has no reanimated reference; expo-router lists them as **optional** peers; `libreanimated`+`libworklets` = 2.96 MB/ABI, dex `com.swmansion.reanimated` 95 classes | ≈ 3 MB × ABI count native + dex | Dependency removal (forbidden in this phase). `@expo/ui` has optional peer `react-native-worklets`; Babel preset may require worklets plugin; uniwind `active:` classes were not verified to be reanimated-free at runtime. Needs bundle + all-screen test. | **B** |
| Z5 | Disable Fresco GIF/WebP (`expo.gif.enabled=false`, `expo.webp.enabled=false`) | docs call them out; app images are PNG; `expo-image` handles its own formats | ≈ 1.8–2.1 MB native per ABI + small dex | Any RN `<Image>` GIF/WebP would break; mipmap WebP is system-decoded, unaffected. Check `expo-image` uses (1 import) | **B** |
| Z6 | `android.enableShrinkResourcesInReleaseBuilds=true` | currently off | unknown (res ≈1.2 MB compressed; some may be unused library resources) | Can strip resources referenced via reflection/dynamic ids; mission UI is code-built (low risk); needs full regression | **B** |
| Z7 | Re-encode 9 WAV presets (3.07 MB) | `res/raw`, `PresetMedia.kt` | ≈ 2–2.7 MB (assumes ~10:1 vs PCM; **estimate**) | Loop gaplessness for `MediaPlayer` looping; sleep sounds are 8-bit-ish synthesized noise; must listen-test; ids/`R.raw` names must stay stable | **B** |
| Z8 | ML Kit pose/barcode: switch to *unbundled* (Play-services) variants | `libxeno_native` 11.4 MB + `libbarhopper` 5.9 MB + models ≈ 6.7 MB per ABI ≈ 24 MB/ABI | up to ≈ 24 MB per ABI | **Breaks offline / first-launch guarantee** documented in `build.gradle`; needs Google Play services (not on all devices) and runtime model download. Squat/barcode must still fall back. Product decision | **C** (defer) |
| Z9 | Drop `expo-image` AVIF decoder / unused expo modules (`dom-webview`, `web-browser`, `device`, `keep-awake`…) | autolinked, no direct JS use | small | These are Expo Router/Expo transitive peers; removal needs dependency-graph proof | **B/C** (defer) |
| Z10 | Replace uncompressed `useLegacyPackaging=false` with `true` for the sideload APK | docs: RN ≥0.73 default changed | file size ↓ (libs compress ≈ 2–2.5×? **unknown**) but install size ↑ and slower start | Behavior tradeoff; irrelevant for AAB | **B** |

Not recommended: touching `proguard-rules.pro`/consumer rules to "shrink more" (load-bearing, see doc 02 §6).

## 5. Terminology — which "size"?
1. **APK file size** — bytes of the `.apk` you hand over (what 170 MB is likely measuring). Includes all ABIs in a universal APK; native libs stored uncompressed here.
2. **Download size** — bytes Google Play/EAS actually sends: for an AAB, a tailored split set (one ABI, one density) with transport compression; Expo docs: "the size of the APK that Google Play generates from your AAB, which is tailored to the user's device" (their SDK 50 sample: release APK 62.1 MB → Play 11.7 MB).
3. **Installed size** — on-device footprint after install (extracted libs if legacy packaging, `oat`/odex compiled code, caches, user data). Uncompressed-in-APK libs avoid extraction duplication.
4. **Debug vs production** — debug builds are unminified (dex 65.5 MB raw vs 11.9 MB), larger RN native libs (`libreactnative` 23.2 MB vs 7.3 MB), no R8; 119 MB vs 60.7 MB here with the same ABI. Never compare across variants.
5. **Universal vs device-specific** — universal = every ABI/density; device-specific (AAB-derived or ABI-filtered) contains one.

## 6. Baseline & comparison methodology (for Phase 2)
1. Fix commit; clean checkout; same Gradle/Node. Build **release** only (never debug) with the *production-like* config.
2. Capture three artifacts per change: (a) universal release APK (committed ABI list), (b) single-ABI `arm64-v8a` release APK, (c) AAB via `eas build --profile production` (or `./gradlew bundleRelease` in a scratch clone).
3. Measure with `apkanalyzer apk file-size` / `download-size`, `apkanalyzer files list`, `bundletool get-size total --device-spec=<arm64 phone>`, installed size with `adb shell dumpsys package <pkg> | grep codePath` + `du -sh /data/app/...`; record `zipinfo` category table as in §2.
4. Keep a table: baseline → change → Δ file size, Δ download size, Δ installed size, cold-start time. One variable per build.
5. Include native debug symbols and `mapping.txt` archives outside the size figure.
6. Always rerun the regression plan (doc 08) on the *changed* artifact; size wins are void if behavior regresses.

## 7. Limits of this analysis
No 4-ABI artifact exists locally; no `apkanalyzer apk compare`/`download-size` run (would need artifact copies; nothing else attempted beyond zip listing); Compose dex byte share is a class-count proxy; origin of `libzstd-kmp` and the Material Symbols font are not traced; arm64/armv7 per-lib sizes are only known for four libs from AARs.

Sources used for docs claims: <https://docs.expo.dev/distribution/app-size/> (gif/webp properties, `useLegacyPackaging`, APK vs AAB vs Play download) and <https://docs.expo.dev/modules/autolinking/> (`expo.autolinking.android.exclude`). The app-size page does not cover `reactNativeArchitectures`, shrink flags or per-ABI APK splits; those come from the repo's own `gradle.properties`/`build.gradle` comments and standard AGP behavior [not re-verified for SDK 58].
