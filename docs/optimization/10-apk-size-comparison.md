# 10 — Phase 2 APK size comparison

All sizes use **MiB = 1,048,576 bytes**. ZIP entry sizes below distinguish stored/compressed bytes from raw bytes. Measurements are APK/AAB file sizes, not Play download or installed sizes.

## Fresh release artifacts

Built from Phase 1 HEAD `f6eedf9`, with unchanged app source, R8 enabled, resource shrinking disabled, Hermes/New Architecture enabled and existing local debug signing. These are release builds, not debug/development-client builds, but their signing is **not verified production distribution signing**.

| Artifact | ABIs actually inspected | Bytes | MiB |
|---|---|---:|---:|
| Fresh baseline universal release APK | arm64-v8a, armeabi-v7a, x86, x86_64 | 172,904,508 | 164.89 |
| Final opt-in arm64 release APK | arm64-v8a | 61,188,334 | 58.35 |
| Fresh baseline production-type AAB | arm64-v8a, armeabi-v7a, x86, x86_64 | 130,386,391 | 124.35 |

Universal APK SHA-256: `205e109a6e07082d03cc5b906cbe616ede11b541f2b72b0a0c054932c225c57f`.

Arm64 APK SHA-256: `304218c039593425579fcb7a7324f1278bd7a60130109ee03f2b360a113d0a84`.

The AAB is a store input and **not directly installable**. Its file size must not be compared with an APK as a download saving. No bundletool/device-tailored split, Play download, installed footprint or cold-start measurement was executed. Existing `production` AAB and `preview` universal APK profiles remain unchanged; actual EAS artifacts could not be inspected because project build access was denied.

## Packaging difference, separate from optimization

The new arm64 package is **111,716,174 bytes / 106.54 MiB / 64.61% smaller** than the fresh universal APK solely because it omits the other three architectures and their archive overhead. This compares two packaging choices for the same release code; it is **not** a same-ABI code optimization result.

Exactly 93 ZIP entries were omitted, all under `lib/armeabi-v7a/`, `lib/x86/`, or `lib/x86_64/`. No entries were added. Every retained ZIP entry has the same SHA-256 as baseline: DEX, manifest, resources, assets, models, audio and all 31 arm64 native libraries. The default universal artifact remains unchanged at 164.89 MiB. **Measured code/resource removal savings: 0 bytes (0%).** No equivalent-ABI optimized-code artifact exists because those optimizations were deferred.

| ABI | Library count | Raw = stored bytes in universal APK | MiB |
|---|---:|---:|---:|
| arm64-v8a | 31 | 38,566,008 | 36.78 |
| armeabi-v7a | 31 | 26,050,336 | 24.84 |
| x86 | 31 | 42,666,612 | 40.69 |
| x86_64 | 31 | 42,229,680 | 40.27 |
| Total | 124 | 149,512,636 | 142.59 |

The three omitted ABIs account for 110,946,628 native bytes; the remaining 769,546-byte difference is archive overhead/alignment/signing-block layout. Compatibility risk: the opt-in APK cannot serve other architectures; existing universal support is preserved. Arm64 physical-device runtime tests are **NOT EXECUTED**.

## Fresh APK content

| Category | Universal stored bytes | Raw bytes | Arm64 result |
|---|---:|---:|---|
| Native libraries | 149,512,636 | 149,512,636 | 38,566,008 bytes; same arm64 files |
| DEX (2 files) | 5,557,538 | 11,955,760 | identical |
| Assets (22 files) | 9,417,814 | 9,466,845 | identical |
| Resources (1,016 files) | 5,213,970 | 6,090,512 | identical |
| Resource table | 1,824,284 | 1,824,284 | identical |
| Hermes bundle (subset of assets) | 2,623,536 | 2,623,536 | identical; magic `c61fbc03c103191f` |
| Model-related assets (subset of assets) | 6,762,550 | 6,778,150 | identical |
| Audio (subset of resources, 9 WAVs) | 3,072,396 | 3,072,396 | identical to source bytes |

Subset rows must not be added to their parent rows. Full per-entry and native-library inventories are in local `analysis.json` files. Arm64 retains barcode `libbarhopper_v3.so` (4,946,720 bytes), pose `libxeno_native.so` (10,260,032), React Native (7,048,592), Hermes VM (2,705,536), Expo, Reanimated/Worklets and all other baseline arm64 libraries. CameraX/ML Kit classes and `KtAlarmModule` are present in R8 mapping; generated module registration remains unchanged.

## Audio and unverified estimates

Source WAVs: 3 alarm tones × 8 seconds and 6 sleep loops × 12 seconds, mono PCM16 at 16 kHz; 3,072,396 bytes total (2.93 MiB). All nine hashes are unique and all nine are referenced by `PresetMedia`. Android's existing resource optimization shortens packaged filenames; byte-hash matching proves all nine source files are present despite those existing archive names. No transcoding, removal, renaming or mapping change was made.

The original “170MB+” APK was unavailable. The fresh four-ABI APK is consistent with Phase 1's hypothesis, but the identity and ABI contents of the originally reported artifact remain **unconfirmed**. The pre-existing stale x86_64 release APK (60,750,238 bytes / 57.94 MiB) was preserved but excluded from all improvement comparisons. Estimated Compose/font, decoder, shrinking, animation and audio-transcoding savings remain unmeasured; none is reported as achieved.

Local evidence: ignored `android/build/phase2/baseline/` (universal APK/AAB, stale APK, inventories, mapping, manifest, certificates, logs), `android/build/phase2/arm64/` (APK, inventories, manifest/certificate), and `android/build/phase2/comparison.json`. These binaries are not committed. APKs are suitable as inputs to physical-device testing subject to architecture and signing compatibility, not as production-qualified releases.
