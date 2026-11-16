# iOS feature parity

The iOS module previously accepted only time, repeat days, label, vibration and snooze length.
The editor gated all other settings behind Android, and the Swift module lacked the corresponding
methods. Both the editor and the native bridge now support the following features.

| Feature | iOS implementation |
| --- | --- |
| Save/edit/toggle/delete; once and weekday repeats | `AlarmEngine.swift`, `AlarmStore.swift`; AlarmKit on iOS 26+ |
| Snooze limits (including zero and unlimited) | Stored counters; custom App Intent schedules a separate fixed snooze alert without replacing the weekly alarm; hides Snooze at the limit |
| Math and typing missions | `AlarmMissionView.swift`; native answer validation and four math difficulty levels |
| Shake and walk missions | Core Motion; CMPedometer for steps, conservative accelerometer fallback when unavailable or denied |
| Squat mission | `MissionCamera.swift`, `MissionCounters.swift`; on-device Vision landmarks, standing calibration, full down/up cycle and lost-tracking reset |
| Barcode registration and validation | Rear camera, common barcode and QR formats, registered value comparison, cancellation and permission errors |
| Multiple missions and previews | Ordered native mission screen; completed mission index persisted for relaunch; preview can be closed |
| Active mission lock | Native guards reject save, delete, toggle, and ordinary dismiss while pending or snoozed |
| Mission effort / recovery | Audio quiets during effort and returns after inactivity; idle mission progress resets; the one-hour safety cap persists across relaunch |
| Sensor/camera failure | Three math problems substitute for an unavailable sensor or camera so the user can finish the mission |
| Ringtones | Built-in tones and Files imports; imported audio converted to a 29-second PCM CAF in Library/Sounds for AlarmKit |
| Backgrounds | Presets and imported photos on the in-app mission screen; photos downsampled to at most 2048 pixels |
| Sleep sounds | `SleepSoundPlayer.swift`; six offline loops or imported audio, 1–720 minute monotonic timer, background playback and lock-screen Stop/Pause |
| Alarm takes priority over sleep audio | Deadline capped at the earliest enabled alarm, recalculated after edits; playback stops on alarm observation, interruptions, or headphone disconnection |
| Permissions and compatibility | AlarmKit access, Camera and Motion & Fitness usage descriptions; clear iOS 26 requirement; sleep sounds and previews remain available on older supported iOS |

Existing basic iOS alarms still decode: all new persisted fields are optional with defaults.
Store access and alarm transitions are serialized on the main actor. Concurrent updates to the same
alarm are rejected while scheduling is in flight. Native events refresh the shared React screens.

## Platform limits

- iOS owns its lock-screen Stop control. For a mission alarm, its Stop intent opens the app and
  retains an app-level pending mission. The in-app screen resumes audio after the system stops it.
  A user can still silence the system alarm, leave the app or force-quit it. iOS cannot provide
  Android's forced overlay, Home-button interception, or uninstall protection.
- Photos appear inside the app; the system alarm's background is controlled by iOS.
- The vibration preference applies to the in-app mission screen. AlarmKit does not expose the
  Android per-alarm vibration control for system alerts.
- Custom snoozes use new scheduled alerts, not a Live Activity countdown. The app displays their
  pending time. Weekly recurrence keeps its original AlarmKit identifier.
- Sleep audio stops when the process is terminated and does not restart by itself. AlarmKit
  scheduling is independent of sleep playback.

Apple references: [AlarmKit](https://developer.apple.com/documentation/alarmkit),
[alarm configuration](https://developer.apple.com/documentation/alarmkit/alarmmanager/alarmconfiguration),
[system Stop control](https://developer.apple.com/documentation/alarmkit/alarmpresentation/alert-swift.struct),
[App Intents packages](https://developer.apple.com/documentation/appintents/appintentspackage), and
[background audio](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playback).

## Build and verify on an iPhone

No dependency was added. The podspec bundles the existing original audio files from the Android
module's resource directory and links Apple's native frameworks. `app.json` and the committed iOS
Info.plist contain camera/motion usage messages and background audio mode. The AppDelegate includes
the module's App Intents package; the local config plugin preserves that registration if prebuild
is used in the future. Android native source is unchanged.

Use Xcode 26.4+ as required by Expo SDK 58. On macOS, run `npx expo run:ios --device` to install pods
and rebuild, or build iOS through the existing EAS profile. A rebuild is required; Expo Go and an
OTA JavaScript update cannot include these changes.

Before treating this as device-verified, check:

1. Save a one-shot and a weekday repeat; edit, toggle and delete; deny then grant AlarmKit access.
2. Ring while locked and with the app terminated; Stop should open pending missions. Finish a
   full chain; ensure save/delete/toggle are refused while pending or snoozed.
3. Test snooze limits 0, 1, 3 and unlimited from both system and in-app controls. Confirm snoozed
   one-shots display their pending time and repeats keep their regular schedule afterward.
4. Preview every mission. Test real steps, shaking, full squats, tracking loss, correct/incorrect
   barcodes, scanner cancellation, and camera/motion denial or revocation. Simulator sensor
   behavior cannot establish physical-device correctness.
5. Import audio and a photo from Files/iCloud. Relaunch and confirm media is retained. Test built-in
   tones and imported MP3/M4A/WAV ringtones while locked.
6. Play each sleep preset and imported audio while locked. Check timer expiry, manual Stop/Pause,
   interruption by a wake-up alarm/call, headphone removal, and editing an alarm to an earlier time.
7. Launch on iOS below 26: show the compatibility message, retain sleep audio/previews, and report
   a clear error if scheduling is attempted.

This workspace runs on Linux and has no Xcode or attached iPhone. Lint, TypeScript, config/plugin
checks and Swift syntax parsing can run here; native SDK typechecking and the device checks above
require a Mac/iOS build and physical hardware.
