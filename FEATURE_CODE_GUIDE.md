# Core features: code and logic guide

This guide covers all ten features from the feature list, based on the current repository. Snippets are exact source excerpts; some show only the relevant part of a function.

Source links use absolute workspace paths with `:line` targets for the Codex editor. Line numbers are a snapshot and can shift after edits. Other Markdown viewers may not support opening a file at a line with this link format.

## Start here: how the app fits together

The React screens collect settings and display alarms. `KtAlarm` connects TypeScript to the native module. Android stores, schedules, rings, and validates mission completion in Kotlin, so the ringing path does not depend on the React screen staying open. iOS has a separate Swift implementation using AlarmKit.

Related code: [index.tsx:14](/home/tushar/Documents/code/ktAlerm/src/app/index.tsx:14); [alarm.tsx:47](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:47); [index.ts:92](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/index.ts:92); [KtAlarmModule.kt:84](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:84); [KtAlarmModule.swift:205](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/ios/KtAlarmModule.swift:205).

| Feature | Android | iOS implementation in this repo |
| --- | --- | --- |
| Create, edit, delete, toggle | Implemented | Implemented using AlarmKit on iOS 26+ |
| One-time / weekday repeats | Implemented | Implemented |
| Labels, vibration, snooze length | Implemented | Label and snooze length passed to AlarmKit; vibration stored but not explicitly applied in scheduling |
| Snooze limits | Implemented | No limit field or enforcement |
| Wake-up missions | Five types; one mission per alarm | Not implemented |
| Mission targets / difficulty | Implemented | Not implemented |
| Mission preview | Implemented | Not implemented |
| Lock active mission alarms | Native guards plus UI | Not implemented |
| Uninstall protection | Optional device administrator | Not implemented |
| Permission management / lock-screen alerts | Native settings helpers, service, notification, activity | AlarmKit authorization and system presentation |

## 1. Create, edit, delete, and toggle alarms

The Add button opens `/alarm`; tapping an existing alarm passes its ID to the same editor. The editor loads that alarm into local state. Save sends the fields to the native module; an absent ID creates a UUID, and an existing ID updates the record.

[alarm.tsx:101](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:101) — Editor sends the selected settings to native code.

```tsx
await KtAlarm.saveAlarm({
  id: existing?.id,
  hour,
  minute,
  days,
  label,
  snoozeMinutes,
  vibrate,
  enabled: true,
  ...(MISSIONS_SUPPORTED ? { missions: mission ? [mission] : [], maxSnoozes } : {}),
});
router.back();
```

[AlarmController.kt:9](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:9) — Saving persists the alarm, schedules its next ring, and notifies the React UI.

```kotlin
fun save(context: Context, alarm: Alarm) {
  AlarmStore.upsert(context, alarm)
  AlarmScheduler.schedule(context, alarm)
  AlarmEvents.emit(AlarmEvents.Type.ALARMS_CHANGED)
}
```

[AlarmStore.kt:47](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmStore.kt:47) — Upsert replaces the matching ID or appends a new alarm.

```kotlin
fun upsert(context: Context, alarm: Alarm) {
  val list = all(context).toMutableList()
  val index = list.indexOfFirst { it.id == alarm.id }
  if (index >= 0) list[index] = alarm else list.add(alarm)
  write(context, list)
}
```

Delete cancels the scheduled ring before removing storage. Toggling clears pending snooze state and resets its counter. The native module rejects edits or deletion of a locked mission alarm (section 8).

Related code: [KtAlarmModule.kt:118](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:118); [AlarmController.kt:15](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:15); [AlarmController.kt:22](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:22); [index.tsx:76](/home/tushar/Documents/code/ktAlerm/src/app/index.tsx:76); [index.tsx:125](/home/tushar/Documents/code/ktAlerm/src/app/index.tsx:125).

[use-alarms.ts:15](/home/tushar/Documents/code/ktAlerm/src/hooks/use-alarms.ts:15) — The list reloads after native alarm/ringing events and when the app resumes.

```ts
const subs = [
  KtAlarm.addListener('onAlarmsChanged', refresh),
  KtAlarm.addListener('onRingingChanged', refresh),
  AppState.addEventListener('change', (s) => s === 'active' && refresh()),
];
return () => subs.forEach((s) => s.remove());
```

## 2. Set one-time alarms or repeat on selected weekdays

Days use `0 = Sunday` through `6 = Saturday`. An empty array means one-time. The editor offers Once, Weekdays, Every day, and individual day buttons. Native code searches for the next matching future date in the device calendar.

[Alarm.kt:57](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Alarm.kt:57) — A one-time time that already passed rolls to tomorrow; repeating alarms select the next matching weekday.

```kotlin
if (!isRepeating) {
  if (base.timeInMillis <= now) base.add(Calendar.DAY_OF_YEAR, 1)
  return base.timeInMillis
}

for (offset in 0..7) {
  val candidate = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
  val weekday = candidate.get(Calendar.DAY_OF_WEEK) - 1 // Calendar.SUNDAY == 1
  if (weekday in days && candidate.timeInMillis > now) return candidate.timeInMillis
}
// Unreachable for a non-empty day set, but keep a sane fallback.
return base.timeInMillis + 24L * 60 * 60 * 1000
```

[AlarmController.kt:36](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:36) — On firing, one-time alarms become disabled. Repeating alarms are rearmed from one minute ahead to avoid scheduling the same occurrence again.

```kotlin
val advanced = alarm.copy(
  enabled = alarm.isRepeating && alarm.enabled,
  snoozeUntil = null,
  snoozeCount = if (fromSnooze) alarm.snoozeCount else 0,
)
AlarmStore.upsert(context, advanced)
if (advanced.isRepeating && advanced.enabled) {
  // Compute from one minute ahead so we never re-arm the occurrence that is ringing now.
  AlarmScheduler.schedule(context, advanced, advanced.nextTrigger(System.currentTimeMillis() + 60_000))
}
AlarmEvents.emit(AlarmEvents.Type.ALARMS_CHANGED)
```

Related code: [alarm.tsx:68](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:68); [alarm.tsx:217](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:217); [alarm-format.ts:42](/home/tushar/Documents/code/ktAlerm/src/lib/alarm-format.ts:42).

## 3. Customize labels, vibration, and snooze duration

The editor stores a label, vibration switch, and snooze length. The snooze choices in the UI are 5, 10, 15, or 20 minutes; Android accepts and clamps native input to 1–60 minutes. The label appears on the alarm row, ringing screen, and notification.

[alarm.tsx:55](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:55) — These editor values start from the existing alarm, or use defaults.

```tsx
const [label, setLabel] = useState(existing?.label ?? '');
const [snoozeMinutes, setSnoozeMinutes] = useState(existing?.snoozeMinutes ?? 5);
const [vibrate, setVibrate] = useState(existing?.vibrate ?? true);
```

[AlarmService.kt:216](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:216) — Ringing starts sound and only starts vibration when the alarm enables it.

```kotlin
acquireWakeLock(silenceAfter + 60_000)
startSound()
if (alarm.vibrate) startVibration()
launchRingingScreen(alarm)
```

Related code: [KtAlarmModule.kt:133](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:133); [alarm.tsx:20](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:20); [AlarmService.kt:455](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:455); [AlarmService.kt:291](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:291).

The Android service uses available system alarm/ringtone/notification sound URIs, loops playback, and fades volume in over 20 seconds. There is no per-alarm custom sound picker in the current editor.

Related code: [AlarmService.kt:420](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:420); [AlarmService.kt:428](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:428); [AlarmService.kt:121](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:121).

## 4. Limit snoozes or disable snoozing

On Android, `maxSnoozes = -1` means unlimited; `0` disables snoozing. Positive values cap snoozes per occurrence. The UI offers 0, 1, 2, 3, 5, or unlimited. Choosing a mission defaults the limit to 3 until the user explicitly chooses a limit.

[Alarm.kt:33](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Alarm.kt:33) — This predicate is shared by the UI and snooze controller.

```kotlin
val canSnooze: Boolean get() = maxSnoozes == UNLIMITED_SNOOZES || snoozeCount < maxSnoozes
```

[AlarmController.kt:51](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:51) — The controller refuses an exhausted limit; otherwise it schedules a future ring and increments the counter before stopping sound.

```kotlin
fun snooze(context: Context, id: String): Boolean {
  val alarm = AlarmStore.get(context, id)
  if (alarm != null) {
    if (!alarm.canSnooze) return false
    val until = System.currentTimeMillis() + alarm.snoozeMinutes * 60_000L
    save(context, alarm.copy(enabled = true, snoozeUntil = until, snoozeCount = alarm.snoozeCount + 1))
  }
  if (AlarmService.activeAlarmId == id) stopRinging(context)
  return true
}
```

A snoozed ring retains its counter; a fresh occurrence resets it. The notification and ringing screen hide Snooze when the limit is exhausted. Snoozing a mission alarm does not remove its mission lock.

Related code: [missions.ts:92](/home/tushar/Documents/code/ktAlerm/src/lib/missions.ts:92); [alarm.tsx:71](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:71); [AlarmController.kt:35](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:35); [AlarmActivity.kt:238](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:238).

## 5. Require wake-up missions (Android)

### End-to-end flow

1. Choose a mission in the editor and configure its target.
2. Save validates camera access where needed, barcode registration, and overlay access.
3. Native validation keeps at most one mission and stores it with the alarm.
4. At alarm time: `AlarmScheduler` → `AlarmReceiver` → `AlarmService` → `AlarmActivity`.
5. The ringing screen shows Start mission. `MissionRunner.create()` selects the implementation.
6. The runner reports activity and completion through its host callbacks.
7. Completion calls `AlarmController.completeMission()`, clears pending state, and stops ringing.

The mission array is designed to allow expansion, but this version runs one mission per alarm, not a chain.

[KtAlarmModule.kt:123](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:123) — Native saving validates the mission and requires overlay access independently of the editor.

```kotlin
val missions = input.missions.take(1).map { it.toMission() ?: throw InvalidMissionException() }
if (missions.isNotEmpty() && !AlarmPermissions.hasOverlay(context)) throw OverlayPermissionRequiredException()
```

[MissionViews.kt:67](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:67) — The factory selects the correct runner; unavailable hardware falls back to math.

```kotlin
fun create(context: Context, mission: Mission, host: Host): MissionRunner = when (mission.type) {
  MissionType.MATH -> MathMission(context, mission, host)
  MissionType.TYPING -> TypingMission(context, mission, host)
  // Never leave the user with an impossible mission: no sensor / camera → math instead.
  MissionType.SHAKE ->
    if (ShakeMission.isSupported(context)) ShakeMission(context, mission, host) else fallback(context, host)
  MissionType.SQUAT ->
    if (CameraView.isAvailable(context)) SquatMission(context, mission, host) else fallback(context, host)
  MissionType.BARCODE ->
    if (CameraView.isAvailable(context) && mission.barcodeValue != null) {
      BarcodeMission(context, mission, host)
    } else {
      fallback(context, host)
    }
}
```

[AlarmActivity.kt:254](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:254) — The mission view replaces the ringing view, reduces sound, starts the runner, and starts the idle timer.

```kotlin
  missionBody.addView(r.view, FrameLayout.LayoutParams(-1, -1))
  ringingView.visibility = View.GONE
  missionView.visibility = View.VISIBLE
  if (!isPreview) AlarmService.setDucked(this, true)
  r.start()
  touch()
}
```

[AlarmActivity.kt:296](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:296) — Successful real mission completion releases the alarm and closes the native screen.

```kotlin
AlarmController.completeMission(this, id)
finishAndRemoveTask()
```

Related code: [MissionViews.kt:39](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:39); [AlarmActivity.kt:300](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:300); [AlarmController.kt:74](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:74); [AlarmController.kt:80](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:80).

### Math mission

The generator selects arithmetic by difficulty: single-digit addition, two-digit addition/subtraction, multiplication plus addition, or larger multiplication. Only a correct answer advances the solved count. Wrong answers show feedback and clear the input after a short delay.

[MissionViews.kt:196](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:196) — After enough correct answers, the runner calls its completion callback.

```kotlin
if (input.toIntOrNull() == problem.answer) {
  solved++
  input = ""
  feedbackView.text = ""
  if (solved >= mission.count) {
    done = true
    host.onMissionComplete()
    return
  }
  problem = MathProblems.generate(mission.difficulty)
  render()
```

Related code: [MissionViews.kt:92](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:92).

### Shake mission

The runner listens to the accelerometer, computes acceleration magnitude relative to gravity, and counts samples above 2.2 g with at least 250 ms between accepted shakes. The time gap prevents many adjacent sensor samples from counting as separate shakes.

[MissionViews.kt:281](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:281) — Accepted shakes update progress and the idle timer; reaching the target stops listening and completes the mission.

```kotlin
  val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
  val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
  val now = System.currentTimeMillis()
  if (g < THRESHOLD_G || now - lastShakeAt < MIN_GAP_MS) return
  lastShakeAt = now
  shakes++
  host.onMissionActivity()
  view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
  render()
  if (shakes >= mission.count) {
    done = true
    stop()
    host.onMissionComplete()
  }
}
```

Related code: [MissionViews.kt:239](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:239); [MissionViews.kt:270](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:270).

### Squat mission

The front camera feeds ML Kit pose detection. Visible hip, knee, and ankle points become leg measurements. `SquatCounter` calibrates a standing baseline and uses the vertical hip-to-knee distance divided by knee-to-ankle distance to estimate squat depth.

A repetition requires standing → down → standing. Five calibration frames establish the baseline; two consecutive frames confirm transitions. The down threshold is 55% of the baseline; standing again requires 80%. Smoothing and leg visibility checks reduce false counts. Losing usable legs for 1.5 seconds resets calibration/state, while the accumulated rep count remains.

[SquatCounter.kt:102](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/SquatCounter.kt:102) — A repetition is added only when a confirmed DOWN state returns to standing.

```kotlin
when (state) {
  State.STAND_UP -> if (confirm(isUp)) state = State.READY
  State.READY -> if (confirm(isDown)) state = State.DOWN
  State.DOWN -> if (confirm(isUp)) {
    state = State.READY
    reps++
    return true
  }
  State.NOT_VISIBLE -> Unit
}
return false
```

[SquatCounter.kt:116](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/SquatCounter.kt:116) — Leg measurements with too little vertical shin height are rejected.

```kotlin
private fun ratio(leg: Leg, frameHeight: Float): Float? {
  val shinDy = leg.ankle.y - leg.knee.y
  val shinLength = hypot(leg.ankle.x - leg.knee.x, shinDy)
  if (shinDy < MIN_SHIN_FRACTION * frameHeight || shinDy < 0.5f * shinLength) return null
  return (leg.knee.y - leg.hip.y) / shinDy
}
```

Related code: [PoseCamera.kt:61](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/PoseCamera.kt:61); [PoseCamera.kt:99](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/PoseCamera.kt:99); [MissionViews.kt:392](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:392); [MissionViews.kt:427](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:427); [SquatCounter.kt:133](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/SquatCounter.kt:133); [SquatCounter.kt:153](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/SquatCounter.kt:153).

Camera analysis keeps only the latest frame and uses a single analysis executor. Frames are closed after analysis finishes to keep the camera pipeline moving.

Related code: [CameraView.kt:221](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/CameraView.kt:221); [CameraView.kt:239](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/CameraView.kt:239).

### Typing mission

The runner shuffles built-in phrases and selects the requested number. Input comparison ignores letter case, leading/trailing spaces, and repeated whitespace. The target phrase must otherwise match. Paste/selection actions are disabled by the runner.

[MissionViews.kt:636](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:636) — Matching the current phrase advances to the next one; finishing the selected phrases completes the mission.

```kotlin
private fun onTyped(typed: String) {
  if (done) return
  host.onMissionActivity()
  val target = normalize(phrases[index])
  val current = normalize(typed)
  input.setTextColor(if (target.startsWith(current)) Color.WHITE else Ui.ERROR)
  if (current != target) return
  index++
  if (index >= phrases.size) {
    done = true
    hideKeyboard()
    host.onMissionComplete()
    return
  }
  input.text.clear()
  render()
}
```

Related code: [MissionViews.kt:588](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:588); [MissionViews.kt:672](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:672).

### Barcode mission

First register a code in the editor. The scanner returns its raw value, which is saved with an optional display name. At alarm time, the rear-camera scanner compares the scanned value with the stored value; scanning a different product does not complete the mission. It verifies the code value, not the object’s physical location.

[alarm.tsx:367](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:367) — Registration saves the scanner result into the mission configuration.

```tsx
const code = await KtAlarm.scanBarcode();
if (code) {
  onChange({ ...mission, barcodeValue: code.value, barcodeName: mission.barcodeName || 'My code' });
}
```

[MissionViews.kt:521](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/MissionViews.kt:521) — Only the registered code completes the running barcode mission.

```kotlin
private fun onCode(value: String) {
  if (done) return
  host.onMissionActivity()
  if (value == mission.barcodeValue) {
    done = true
    feedbackView.text = "Got it!"
    feedbackView.setTextColor(Ui.SUCCESS)
    scanner.stop()
    handler.postDelayed({ host.onMissionComplete() }, 300)
    return
  }
  val now = System.currentTimeMillis()
```

Related code: [BarcodeScanner.kt:19](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/BarcodeScanner.kt:19); [BarcodeScanner.kt:59](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/BarcodeScanner.kt:59); [ScanActivity.kt:86](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/ScanActivity.kt:86); [Mission.kt:83](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Mission.kt:83).

### Idle behavior, fallback, and time limits

The activity lowers playback while a mission is being attempted. Touches and mission activity callbacks reset its idle timer. Inactivity returns to the full ringing screen and discards the runner, so starting the mission again starts progress over. The default idle timeout is 30 seconds; squat uses 60 seconds and barcode uses 90 seconds.

Missing accelerometer or unavailable camera at startup substitutes three medium math problems. A camera failure during a mission also switches to math. Preview mode can be closed without completing a mission.

[AlarmActivity.kt:101](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:101) — Idle handling restarts a preview or returns a real alarm to loud ringing.

```kotlin
private val idle = Runnable {
  val preview = previewMission
  if (preview != null) {
    Toast.makeText(this, "No activity — mission restarted", Toast.LENGTH_SHORT).show()
    startMission(preview)
  } else {
    AlarmService.setDucked(this, false)
    showRinging()
  }
}
```

[AlarmActivity.kt:277](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:277) — A runtime camera failure starts the fallback mission.

```kotlin
override fun onMissionUnavailable() {
  handler.post {
    if (runner == null) return@post
    cameraFailed = true
    Toast.makeText(this, "Camera unavailable — solve these instead", Toast.LENGTH_LONG).show()
    startMission(MissionRunner.FALLBACK)
  }
}
```

The service auto-silences ordinary alarms after 10 minutes. Mission alarms have a 60-minute safety cap; reaching that cap releases the alarm and posts a missed-alarm notification. Thus mission completion is the normal dismissal path, but it is not an unlimited guarantee that sound continues forever.

Related code: [AlarmService.kt:59](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:59); [AlarmService.kt:62](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:62); [AlarmService.kt:98](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:98); [AlarmService.kt:413](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:413).

## 6. Adjust mission difficulty and completion targets

Only math uses difficulty (0–3). Each type has a count range: math 1–10 problems, shake 10–100 shakes, squat 5–50 reps, typing 1–5 phrases, barcode exactly one code. The editor uses stepping buttons; native normalization clamps values again, so callers cannot bypass the ranges by skipping the UI.

[Mission.kt:37](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Mission.kt:37) — Native constraints apply to both difficulty and count.

```kotlin
fun normalized(): Mission = copy(
  difficulty = difficulty.coerceIn(0, MAX_DIFFICULTY),
  count = count.coerceIn(type.minCount, type.maxCount),
)
```

Related code: [missions.ts:15](/home/tushar/Documents/code/ktAlerm/src/lib/missions.ts:15); [missions.ts:68](/home/tushar/Documents/code/ktAlerm/src/lib/missions.ts:68); [alarm.tsx:311](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:311); [Mission.kt:6](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Mission.kt:6); [Mission.kt:75](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Mission.kt:75).

## 7. Preview missions before setting an alarm

Preview uses the same native activity and runner as a real alarm. The editor checks camera permission where required and passes the current mission configuration. The activity recognizes a preview extra, starts the mission without a ringing service, and lets Back or Close exit. Completing a preview only closes the preview; it does not dismiss an actual alarm.

[alarm.tsx:420](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:420) — Preview checks camera access and invokes the native bridge.

```tsx
function PreviewButton({ mission }: { mission: Mission }) {
  return (
    <Pressable
      onPress={async () => {
        if (usesCamera(mission.type) && !(await ensureCamera())) return;
        KtAlarm?.previewMission(mission);
      }}
      accessibilityRole="button"
```

[KtAlarmModule.kt:160](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:160) — The native bridge validates the mission and launches the activity in preview mode.

```kotlin
Function("previewMission") { input: MissionInput ->
  val mission = input.toMission() ?: throw InvalidMissionException()
  launcherContext.startActivity(AlarmActivity.previewIntent(launcherContext, mission))
}
```

Related code: [AlarmActivity.kt:61](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:61); [AlarmActivity.kt:197](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:197); [AlarmActivity.kt:286](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:286).

## 8. Prevent edits, disabling, deletion, or ordinary dismissal of active mission alarms

The Android model marks a mission alarm locked while it is currently ringing or has a future snooze timestamp. This prevents snoozing and then deleting it to skip the mission. The editor and list show errors, and the native bridge independently rejects changes. These are app-level guards; they do not make Android system controls impossible to use.

[Alarm.kt:42](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Alarm.kt:42) — Both ringing and pending snooze state keep the mission lock active.

```kotlin
fun isLocked(now: Long = System.currentTimeMillis()): Boolean =
  hasMission && (AlarmService.activeAlarmId == id || (snoozeUntil ?: 0L) > now)
```

[KtAlarmModule.kt:143](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:143) — Native delete and disable operations check the lock before changing state.

```kotlin
AsyncFunction("deleteAlarm") { id: String ->
  if (AlarmStore.get(context, id)?.isLocked() == true) throw AlarmLockedException()
  AlarmController.delete(context, id)
}

AsyncFunction("setAlarmEnabled") { id: String, enabled: Boolean ->
  if (!enabled && AlarmStore.get(context, id)?.isLocked() == true) throw AlarmLockedException()
  AlarmController.setEnabled(context, id, enabled)
}
```

[AlarmController.kt:66](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmController.kt:66) — Ordinary dismissal is refused for locked mission alarms; mission completion uses a separate release path.

```kotlin
fun dismiss(context: Context, id: String): Boolean {
  val alarm = AlarmStore.get(context, id)
  if (alarm != null && alarm.isLocked()) return false
  release(context, id)
  return true
}
```

The ringing notification omits Dismiss for mission alarms. Back only closes previews. Mission volume-key presses are consumed, the service attempts to hold alarm-stream volume, and leaving the activity while the screen is on schedules a relaunch after 300 ms. These mechanisms explain why overlay access is required when saving a mission alarm.

Related code: [AlarmService.kt:313](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:313); [AlarmActivity.kt:165](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:165); [AlarmActivity.kt:173](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:173); [AlarmActivity.kt:145](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:145); [AlarmService.kt:106](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:106).

## 9. Optional uninstall protection (Android)

The protection card opens Android’s device-administrator activation screen. The app checks whether its receiver is active. The app’s disable action refuses to proceed while any mission alarm is locked. The receiver requests no administrator policies; it adds a deactivation step before uninstalling rather than providing an absolute uninstall block.

[UninstallProtection.kt:27](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/UninstallProtection.kt:27) — This launches the system opt-in flow with an explanation.

```kotlin
val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
  .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
  .putExtra(
    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
    "Stops ktAlerm from being uninstalled to skip a wake-up mission. " +
      "You can turn this off in the app at any time when no mission alarm is ringing.",
  )
```

[KtAlarmModule.kt:184](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:184) — In-app disabling is blocked while a mission alarm is ringing or snoozed.

```kotlin
AsyncFunction("disableUninstallProtection") {
  // Switching protection off is the first step to uninstalling, so not while a mission is due.
  if (AlarmStore.all(context).any { it.isLocked() }) throw AlarmLockedException()
  UninstallProtection.disable(context)
}
```

If deactivation is requested from system settings during an actively ringing mission, the receiver attempts to bring the mission screen forward and returns warning text. It cannot guarantee that system administrator deactivation is prevented.

Related code: [uninstall-protection-card.tsx:29](/home/tushar/Documents/code/ktAlerm/src/components/uninstall-protection-card.tsx:29); [UninstallProtection.kt:52](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/UninstallProtection.kt:52); [kt_alarm_device_admin.xml:3](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/res/xml/kt_alarm_device_admin.xml:3).

## 10. Permissions, reliable scheduling, and lock-screen alerts

### Permission UI → native check → system settings

`useAlarmPermissions()` reads native status. `PermissionCard` filters out granted items and shows Allow actions for missing ones. Returning from settings triggers an AppState refresh so the card can disappear after access is granted. The fallback status when the native module is missing is a UI default, not an actual OS permission grant.

[permission-card.tsx:47](/home/tushar/Documents/code/ktAlerm/src/components/permission-card.tsx:47) — Only missing permission items are displayed, and the card is hidden when no native module exists.

```tsx
export function PermissionCard({ status }: { status: PermissionStatus }) {
  const missing = ITEMS.filter((i) => !status[i.key]);
  if (missing.length === 0 || !KtAlarm) return null;
```

[use-alarms.ts:39](/home/tushar/Documents/code/ktAlerm/src/hooks/use-alarms.ts:39) — Permission status is rechecked when the application becomes active.

```ts
const refresh = useCallback(() => setStatus(KtAlarm?.getPermissionStatus() ?? ALL_GRANTED), []);

useEffect(() => {
  const sub = AppState.addEventListener('change', (s) => s === 'active' && refresh());
  return () => sub.remove();
}, [refresh]);
```

| Access | What current code checks / does | Check location | Settings or request location |
| --- | --- | --- | --- |
| Notifications | On Android 13+, checks runtime POST_NOTIFICATIONS, then checks app notifications enabled. | [AlarmPermissions.kt:31](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:31) | [KtAlarmModule.kt:198](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:198) |
| Exact alarms | On Android 12+, checks canScheduleExactAlarms; earlier versions return true. | [AlarmPermissions.kt:38](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:38) | [KtAlarmModule.kt:215](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:215) |
| Full-screen intent | On Android 14+, checks canUseFullScreenIntent; earlier versions return true. | [AlarmPermissions.kt:43](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:43) | [KtAlarmModule.kt:216](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:216) |
| Battery exemption | Checks whether this package is excluded from battery optimization. | [AlarmPermissions.kt:47](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:47) | [KtAlarmModule.kt:217](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:217) |
| Overlay | Checks whether the app may display over other apps. | [AlarmPermissions.kt:50](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:50) | [KtAlarmModule.kt:218](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/KtAlarmModule.kt:218) |

Camera permission is separate from the five reliability items: the editor requests it for squat/barcode missions. The permission card does not list it. A missing or failed camera at ring time leads to the math fallback described above.

[alarm.tsx:32](/home/tushar/Documents/code/ktAlerm/src/app/alarm.tsx:32) — The editor requests camera access only when native status explicitly reports it missing.

```tsx
async function ensureCamera(): Promise<boolean> {
  if (!KtAlarm || KtAlarm.getPermissionStatus().camera !== false) return true;
  const { granted } = await KtAlarm.requestCameraPermission();
  if (granted) return true;
```

[AlarmPermissions.kt:43](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:43) — Full-screen access is checked separately from notifications.

```kotlin
fun hasFullScreenIntent(context: Context): Boolean =
  Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
    context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
```

Related code: [AlarmPermissions.kt:57](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:57); [AlarmPermissions.kt:65](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:65); [AlarmPermissions.kt:74](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:74); [AlarmPermissions.kt:80](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:80).

The vendor Auto-start shortcut tries a list of known settings activities and falls back to app details. It does not read or verify a vendor’s Auto-start state. Currently the shortcut is inside PermissionCard, so it is hidden when all five listed permissions are granted.

Related code: [AlarmPermissions.kt:105](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmPermissions.kt:105); [permission-card.tsx:76](/home/tushar/Documents/code/ktAlerm/src/components/permission-card.tsx:76).

### Scheduling: access checks do not ring the alarm by themselves

The scheduler cancels the old pending intent and arms the next trigger. Its main path uses `setAlarmClock`. If exact-alarm access is missing on Android 12+, it falls back to an inexact idle-allowed alarm. This fallback can be delayed; the app does not guarantee exact timing without access.

[AlarmScheduler.kt:27](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmScheduler.kt:27) — Exact access selects between the inexact fallback and the alarm-clock scheduling path.

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
  // Exact-alarm access was revoked (only possible on Android 12/12L). Best effort.
  Log.w(TAG, "Exact alarm permission missing, falling back to inexact alarm for ${alarm.id}")
  am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fire)
  return
}

am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showAppPendingIntent(context, alarm.id)), fire)
Log.i(TAG, "Scheduled ${alarm.id} at $triggerAt")
```

The receiver starts the native service. The service immediately posts a foreground notification, then starts audio/vibration and the native ringing screen. A partial wake lock keeps the CPU active while ringing; activity window flags handle showing and keeping the screen on.

Related code: [Receivers.kt:10](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Receivers.kt:10); [AlarmService.kt:163](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:163); [AlarmService.kt:243](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:243); [AlarmService.kt:379](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:379).

### Notification and activity cooperate on lock-screen presentation

The service creates a high-importance ringing channel and a public alarm notification. Its full-screen pending intent targets AlarmActivity; tapping the notification targets that activity too. If overlay access is granted, the service also attempts to open the activity directly, including while another app is on screen.

[AlarmService.kt:310](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:310) — The notification opens the alarm screen, exposes allowed snoozes, and excludes Dismiss for mission alarms.

```kotlin
b.setFullScreenIntent(fullScreen, true).setContentIntent(fullScreen)
if (alarm.canSnooze) b.addAction(action(alarm, AlarmActionReceiver.ACTION_SNOOZE, "Snooze", 3))
// Mission alarms can only be turned off from the mission screen.
if (!alarm.hasMission) b.addAction(action(alarm, AlarmActionReceiver.ACTION_DISMISS, "Dismiss", 4))
```

[AlarmActivity.kt:321](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmActivity.kt:321) — The activity requests lock-screen visibility and screen wake-up, with legacy flags for older Android versions.

```kotlin
private fun showOverLockScreen() {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
    setShowWhenLocked(true)
    setTurnScreenOn(true)
  } else {
    @Suppress("DEPRECATION")
    window.addFlags(
      WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
    )
  }
  window.addFlags(
    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
      WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
  )
```

Related code: [AlarmService.kt:260](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:260); [AlarmService.kt:361](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:361); [AndroidManifest.xml:69](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/AndroidManifest.xml:69).

These are requested presentation behaviors. Actual appearance still depends on system access and notification settings. The channel requests DND bypass, but this project does not include a separate DND-access request/check flow; the request should not be read as a universal DND guarantee.

### Reboot, app update, time changes, and interrupted missions

Alarm settings live in device-protected SharedPreferences, available before the first unlock after reboot. Manifest receivers listen for reboot, app replacement, clock/timezone changes, and exact-alarm access changes. BootReceiver reschedules stored alarms.

While a mission rings, PendingRing stores its ID, original start time, and original volume. After reboot, an unfinished mission still within its cap is scheduled to resume. A system service restart also attempts a resume through the scheduler. This restores ringing, not the runner’s progress; mission progress is held in memory.

[AlarmStore.kt:27](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmStore.kt:27) — Device-protected storage lets the native receiver read alarms before user unlock.

```kotlin
private fun prefs(context: Context): SharedPreferences =
  context.applicationContext
    .createDeviceProtectedStorageContext()
    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
```

[Receivers.kt:71](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/Receivers.kt:71) — Only an unfinished mission within the service cap is resumed; stale pending state is cleared.

```kotlin
if (alarm?.hasMission == true && now - pending.startedAt in 0 until AlarmService.MISSION_CAP_MS) {
  AlarmScheduler.scheduleResume(context, alarm.id, now + 1_500)
} else {
  AlarmStore.clearPendingRing(context)
}
```

Related code: [AlarmStore.kt:71](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmStore.kt:71); [AlarmService.kt:143](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmService.kt:143); [AlarmScheduler.kt:48](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/java/expo/modules/ktalarm/AlarmScheduler.kt:48); [AndroidManifest.xml:53](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/android/src/main/AndroidManifest.xml:53).

### iOS follows a different path

The Swift bridge requests AlarmKit authorization, schedules a relative time with optional weekly repeats, and supplies system Dismiss/Snooze presentation. This implementation requires iOS 26+ and AlarmKit availability. Its permission status maps that single authorization to the shared notifications/exactAlarm/fullScreenIntent fields; battery and overlay fields return true because Android’s settings flow does not apply here.

[KtAlarmModule.swift:112](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/ios/KtAlarmModule.swift:112) — AlarmKit authorization is requested once when undetermined; a denied state returns false.

```swift
static func requestAuthorization() async -> Bool {
  switch manager.authorizationState {
  case .authorized: return true
  case .denied: return false
  case .notDetermined:
    return (try? await manager.requestAuthorization()) == .authorized
  @unknown default: return false
  }
```

Related code: [KtAlarmModule.swift:122](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/ios/KtAlarmModule.swift:122); [KtAlarmModule.swift:302](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/ios/KtAlarmModule.swift:302); [KtAlarmModule.swift:333](/home/tushar/Documents/code/ktAlerm/modules/kt-alarm/ios/KtAlarmModule.swift:333).

## Suggested reading order

1. Alarm editor save flow and the TypeScript native interface.
2. Android bridge validation, Alarm model, store, and controller.
3. Scheduler, receivers, and ringing service.
4. AlarmActivity and MissionRunner, then the individual mission you want to understand.
5. PermissionCard, permission hook, native checks/settings, notification construction, and activity window flags.

This is a source-code walkthrough, not a device verification report. No application behavior was changed to produce this guide.
