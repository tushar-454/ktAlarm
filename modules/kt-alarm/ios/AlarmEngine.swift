import Foundation
import UIKit
#if canImport(AlarmKit)
import AlarmKit
import ActivityKit
import AppIntents
import SwiftUI

@available(iOS 26.0, *)
struct KtAlarmMetadata: AlarmMetadata {}

@available(iOS 26.0, *)
public struct KtAlarmIntentPackage: AppIntentsPackage {}

@available(iOS 26.0, *)
struct KtAlarmSnoozeIntent: LiveActivityIntent {
  static var title: LocalizedStringResource = "Snooze alarm"
  static var isDiscoverable: Bool = false
  @Parameter(title: "Alarm ID") var alarmID: String
  init() { alarmID = "" }
  init(_ id: String) { alarmID = id }
  @MainActor func perform() async throws -> some IntentResult {
    _ = try await AlarmEngine.shared.snooze(alarmID, fromSystem: true)
    return .result()
  }
}

@available(iOS 26.0, *)
struct KtAlarmDismissIntent: LiveActivityIntent {
  static var title: LocalizedStringResource = "Dismiss alarm"
  static var isDiscoverable: Bool = false
  @Parameter(title: "Alarm ID") var alarmID: String
  init() { alarmID = "" }
  init(_ id: String) { alarmID = id }
  @MainActor func perform() async throws -> some IntentResult {
    AlarmEngine.shared.systemStopped(alarmID)
    return .result()
  }
}

@available(iOS 26.0, *)
struct KtAlarmMissionIntent: LiveActivityIntent {
  static var title: LocalizedStringResource = "Complete wake-up missions"
  static var isDiscoverable: Bool = false
  static var openAppWhenRun: Bool = true
  @Parameter(title: "Alarm ID") var alarmID: String
  init() { alarmID = "" }
  init(_ id: String) { alarmID = id }
  @MainActor func perform() async throws -> some IntentResult {
    AlarmEngine.shared.systemStopped(alarmID)
    return .result()
  }
}
#endif

/// One coordinator for JS, system App Intents, alarm updates, and the native mission screen.
@MainActor
final class AlarmEngine {
  static let shared = AlarmEngine()
  var changed: (() -> Void)?
  var ringingChanged: ((String?) -> Void)?
  private var observer: Task<Void, Never>?
  private var resumeObserver: NSObjectProtocol?
  private(set) var ringingID: String?
  private var mutations = Set<String>()
  private var stoppedDeliveries = Set<String>()
  private var ready = false

  var supported: Bool {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *) { return true }
    #endif
    return false
  }
  var authorized: Bool {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *) { return AlarmManager.shared.authorizationState == .authorized }
    #endif
    return false
  }
  var isAlerting: Bool {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *) {
      return (try? AlarmManager.shared.alarms.contains { $0.state == .alerting }) == true
    }
    #endif
    return false
  }

  func start() {
    guard !ready else { return }
    ready = true
    resumeObserver = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
      object: nil, queue: .main) { [weak self] _ in
      MainActor.assumeIsolated {
        self?.sync()
        self?.presentPendingMission()
        SleepSoundPlayer.shared.capAtNextAlarm()
      }
    }
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *) {
      observer = Task { [weak self] in
        for await _ in AlarmManager.shared.alarmUpdates {
          guard !Task.isCancelled else { return }
          self?.sync()
          self?.presentPendingMission()
        }
      }
    }
    #endif
    sync()
    DispatchQueue.main.async { self.presentPendingMission() }
  }

  func requestAuthorization() async -> Bool {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *) {
      switch AlarmManager.shared.authorizationState {
      case .authorized: return true
      case .denied: return false
      case .notDetermined: return (try? await AlarmManager.shared.requestAuthorization()) == .authorized
      @unknown default: return false
      }
    }
    #endif
    return false
  }

  func save(_ input: AlarmInputRecord) async throws -> [String: Any] {
    let id = input.id ?? UUID().uuidString
    guard UUID(uuidString: id) != nil, (0...23).contains(input.hour), (0...59).contains(input.minute) else {
      throw alarmError("ERR_INVALID_ALARM", "Choose a valid alarm time and identifier.")
    }
    sync()
    guard AlarmStore.get(id)?.locked != true else { throw lockedError() }
    guard mutations.insert(id).inserted else { throw busyError() }
    defer { mutations.remove(id) }
    var alarm = StoredAlarm(id: id, hour: input.hour, minute: input.minute,
      label: input.label.trimmingCharacters(in: .whitespacesAndNewlines),
      days: Array(Set(input.days.filter { (0...6).contains($0) })).sorted(),
      enabled: input.enabled, vibrate: input.vibrate, snoozeMinutes: min(60, max(1, input.snoozeMinutes)))
    alarm.missions = try input.missions.map { try $0.mission() }
    alarm.maxSnoozes = input.maxSnoozes < 0 ? -1 : min(10, input.maxSnoozes)
    alarm.snoozeCount = 0
    alarm.snoozeId = AlarmStore.get(id)?.snoozeId ?? UUID().uuidString
    alarm.ringtoneUri = try AlarmMedia.checked(input.ringtoneUri, kind: "audio")
    alarm.ringtoneName = input.ringtoneName.map { String($0.prefix(120)) }
    alarm.backgroundUri = try AlarmMedia.checked(input.backgroundUri, kind: "image")
    alarm.backgroundName = input.backgroundName.map { String($0.prefix(120)) }
    alarm.sleepSoundUri = try AlarmMedia.checked(input.sleepSoundUri, kind: "sleep")
    alarm.sleepSoundName = input.sleepSoundName.map { String($0.prefix(120)) }
    alarm.sleepSoundMinutes = min(720, max(1, input.sleepSoundMinutes))
    alarm.scheduledAt = alarm.nextTrigger()
    try await schedule(alarm)
    cancelSnooze(AlarmStore.get(id))
    AlarmStore.upsert(alarm)
    notify()
    return alarm.toDict()
  }

  func setEnabled(_ id: String, _ enabled: Bool) async throws {
    sync()
    guard var alarm = AlarmStore.get(id) else { return }
    guard !alarm.locked else { throw lockedError() }
    guard mutations.insert(id).inserted else { throw busyError() }
    defer { mutations.remove(id) }
    alarm.enabled = enabled; alarm.snoozeUntil = nil; alarm.snoozeCount = 0
    alarm.pendingMission = false; alarm.missionIndex = 0; alarm.scheduledAt = nil
    alarm.pendingSince = nil
    alarm.scheduledAt = alarm.nextTrigger()
    try await schedule(alarm)
    cancelSnooze(alarm)
    AlarmStore.upsert(alarm)
    notify()
  }

  func delete(_ id: String) throws {
    sync()
    guard AlarmStore.get(id)?.locked != true else { throw lockedError() }
    guard !mutations.contains(id) else { throw busyError() }
    try cancel(id)
    cancelSnooze(AlarmStore.get(id))
    AlarmStore.remove(id)
    notify()
  }

  func snooze(_ id: String, fromSystem: Bool = false) async throws -> Bool {
    sync()
    guard var alarm = AlarmStore.get(id), alarm.canSnooze,
          fromSystem || ringingID == id || alarm.pendingMission == true else { return false }
    if let until = alarm.snoozeUntil, until > Date() { return false }
    guard mutations.insert(id).inserted else { return false }
    defer { mutations.remove(id) }
    alarm.snoozeCount = (alarm.snoozeCount ?? 0) + 1
    alarm.enabled = true
    alarm.snoozeUntil = Date().addingTimeInterval(Double(alarm.snoozeMinutes * 60))
    alarm.snoozeId = UUID().uuidString
    alarm.pendingMission = alarm.hasMissions
    if alarm.hasMissions { alarm.pendingSince = alarm.pendingSince ?? alarm.latestOccurrence() ?? Date() }
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *), let snoozeID = alarm.snoozeId, let uuid = UUID(uuidString: snoozeID),
       let until = alarm.snoozeUntil {
      let sound = try await AlarmMedia.prepareRingtone(alarm.ringtoneUri)
      // Schedule the next alert BEFORE silencing this one. Recurrence keeps its own ID.
      _ = try await AlarmManager.shared.schedule(id: uuid,
        configuration: configuration(alarm, schedule: .fixed(until), sound: sound))
      stopSystemAlarm(id)
      cancelSnooze(AlarmStore.get(id))
      AlarmStore.upsert(alarm)
      AlarmMissionPresenter.shared.close(id)
      ringingID = nil
      notify()
      return true
    }
    #endif
    throw unavailableError()
  }

  func dismiss(_ id: String, missionCompleted: Bool = false) -> Bool {
    sync()
    guard var alarm = AlarmStore.get(id), !mutations.contains(id) else { return false }
    guard missionCompleted || !alarm.locked else { return false }
    stopSystemAlarm(id)
    cancelSnooze(alarm)
    alarm.snoozeUntil = nil; alarm.snoozeCount = 0; alarm.pendingMission = false; alarm.missionIndex = 0
    alarm.pendingSince = nil
    if alarm.days.isEmpty { alarm.enabled = false }
    AlarmStore.upsert(alarm)
    AlarmMissionPresenter.shared.close(id)
    if ringingID == id { ringingID = nil }
    notify()
    presentPendingMission()
    return true
  }

  /// The system always owns Stop. Mission alarms open the app and keep their app-level lock.
  func systemStopped(_ id: String) {
    guard var alarm = AlarmStore.get(id) else { return }
    if alarm.hasMissions {
      alarm.pendingMission = true
      alarm.pendingSince = alarm.pendingSince ?? alarm.snoozeUntil ?? alarm.latestOccurrence() ?? Date()
      alarm.snoozeUntil = nil
      if alarm.days.isEmpty { alarm.enabled = false }
      AlarmStore.upsert(alarm)
      SleepSoundPlayer.shared.stop()
      notify()
      presentPendingMission()
    } else { _ = dismiss(id) }
  }

  func sync() {
    expireMissions()
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *), authorized, let live = (try? AlarmManager.shared.alarms) {
      var changedStore = false
      var alarms = AlarmStore.all()
      let liveIDs = Set(live.map { $0.id.uuidString.lowercased() })
      let systemAlertIDs = Set(live.filter { $0.state == .alerting }.map { $0.id.uuidString.lowercased() })
      // A system update can briefly retain the alerting state after stop() succeeds.
      // Do not turn a completed mission back into a pending one during that transition.
      stoppedDeliveries.formIntersection(systemAlertIDs)
      let alertIDs = systemAlertIDs.subtracting(stoppedDeliveries)
      var active: String?
      for i in alarms.indices where !mutations.contains(alarms[i].id) {
        let mainAlert = alertIDs.contains(alarms[i].id.lowercased())
        let snoozeAlert = alarms[i].snoozeId.map { alertIDs.contains($0.lowercased()) } == true
        if mainAlert || snoozeAlert {
          active = active ?? alarms[i].id
          if alarms[i].hasMissions && alarms[i].pendingMission != true {
            alarms[i].pendingMission = true; alarms[i].missionIndex = 0; changedStore = true
            alarms[i].pendingSince = alarms[i].snoozeUntil ?? alarms[i].latestOccurrence() ?? Date()
          }
          if mainAlert && alarms[i].snoozeUntil == nil && ringingID != alarms[i].id {
            if (alarms[i].snoozeCount ?? 0) != 0 { alarms[i].snoozeCount = 0; changedStore = true }
          }
          if alarms[i].snoozeUntil != nil { alarms[i].snoozeUntil = nil; changedStore = true }
          if alarms[i].days.isEmpty && alarms[i].enabled { alarms[i].enabled = false; changedStore = true }
        } else {
          // A one-shot remains in AlarmKit while alerting. Disable only after removal,
          // never infer that it fired just because a new nextDate would be tomorrow.
          if alarms[i].enabled && alarms[i].days.isEmpty && alarms[i].snoozeUntil == nil &&
             !liveIDs.contains(alarms[i].id.lowercased()) {
            alarms[i].enabled = false; changedStore = true
          }
          if let until = alarms[i].snoozeUntil, until <= Date(),
             !liveIDs.contains((alarms[i].snoozeId ?? "").lowercased()) {
            alarms[i].snoozeUntil = nil; changedStore = true
          }
        }
      }
      if changedStore { AlarmStore.write(alarms); changed?() }
      let pending = alarms.first { $0.pendingMission == true && $0.snoozeUntil == nil }?.id
      let newID = active ?? pending
      if newID != ringingID { ringingID = newID; ringingChanged?(newID) }
      if active != nil { SleepSoundPlayer.shared.stop() }
    }
    #endif
  }

  /// Match Android's one-hour mission safety cap, including after a process relaunch.
  func expireMissions() {
    for var alarm in AlarmStore.all() where alarm.pendingMission == true && !mutations.contains(alarm.id) {
      guard let since = alarm.pendingSince, Date().timeIntervalSince(since) >= 3600 else { continue }
      var freshRepeat = false
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *), !alarm.days.isEmpty, let occurrence = alarm.latestOccurrence(), occurrence > since,
        let live = (try? AlarmManager.shared.alarms) {
        freshRepeat = live.contains { $0.id.uuidString.lowercased() == alarm.id.lowercased() && $0.state == .alerting }
        if freshRepeat { alarm.pendingSince = occurrence; alarm.missionIndex = 0; alarm.snoozeCount = 0 }
      }
      #endif
      if !freshRepeat {
        stopSystemAlarm(alarm.id)
        cancelSnooze(alarm)
        alarm.pendingMission = false; alarm.pendingSince = nil; alarm.snoozeUntil = nil
        alarm.snoozeCount = 0; alarm.missionIndex = 0
        if alarm.days.isEmpty { alarm.enabled = false }
      }
      AlarmStore.upsert(alarm)
      AlarmMissionPresenter.shared.close(alarm.id)
      changed?()
    }
  }

  func presentPendingMission() {
    guard UIApplication.shared.applicationState == .active,
      let alarm = AlarmStore.all().first(where: { $0.pendingMission == true && $0.snoozeUntil == nil }) else { return }
    AlarmMissionPresenter.shared.present(alarm)
  }

  func systemIsAlerting(_ alarm: StoredAlarm) -> Bool {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *) {
      return (try? AlarmManager.shared.alarms.contains {
        ($0.id.uuidString.lowercased() == alarm.id.lowercased() ||
         $0.id.uuidString.lowercased() == alarm.snoozeId?.lowercased()) && $0.state == .alerting
      }) == true
    }
    #endif
    return false
  }

  private func schedule(_ alarm: StoredAlarm) async throws {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *), let id = UUID(uuidString: alarm.id) {
      if !alarm.enabled { try cancel(alarm.id); return }
      guard await requestAuthorization() else {
        throw alarmError("ERR_ALARM_NOT_AUTHORIZED", "Allow alarm access in Settings > Apps > ktAlerm.")
      }
      let sound = try await AlarmMedia.prepareRingtone(alarm.ringtoneUri)
      let previous = AlarmStore.get(alarm.id)
      let live = try AlarmManager.shared.alarms
      if previous?.hasMissions == true, live.contains(where: { $0.id == id && $0.state == .alerting }) {
        systemStopped(alarm.id)
        throw lockedError()
      }
      let existed = live.contains { $0.id == id }
      var previousSound: String?
      if existed { previousSound = try? await AlarmMedia.prepareRingtone(previous?.ringtoneUri) }
      // AlarmKit has no update API. Replace an existing delivery only after media and
      // authorization succeed, and restore its previous configuration if scheduling fails.
      if existed { try AlarmManager.shared.cancel(id: id) }
      do {
        _ = try await AlarmManager.shared.schedule(id: id,
          configuration: configuration(alarm, schedule: relativeSchedule(alarm), sound: sound))
      } catch {
        if existed, let previous, previous.enabled {
          do {
            _ = try await AlarmManager.shared.schedule(id: id,
              configuration: configuration(previous, schedule: relativeSchedule(previous), sound: previousSound))
          } catch {
            var disabled = previous
            disabled.enabled = false
            AlarmStore.upsert(disabled)
            changed?()
            throw alarmError("ERR_ALARM_SCHEDULE", "The change could not be saved and the previous alarm could not be restored. Enable the alarm again.")
          }
        }
        throw error
      }
      return
    }
    #endif
    throw unavailableError()
  }

  #if canImport(AlarmKit)
  @available(iOS 26.0, *)
  private func relativeSchedule(_ alarm: StoredAlarm) -> Alarm.Schedule {
    let days: [Locale.Weekday] = [.sunday, .monday, .tuesday, .wednesday, .thursday, .friday, .saturday]
    return .relative(.init(time: .init(hour: alarm.hour, minute: alarm.minute),
      repeats: alarm.days.isEmpty ? .never : .weekly(alarm.days.map { days[$0] })))
  }

  @available(iOS 26.0, *)
  private func configuration(_ alarm: StoredAlarm, schedule: Alarm.Schedule, sound: String?) -> AlarmManager.AlarmConfiguration<KtAlarmMetadata> {
    let title = LocalizedStringResource(stringLiteral: alarm.label.isEmpty ? "Alarm" : alarm.label)
    let secondary: AlarmButton? = alarm.canSnooze ? AlarmButton(
        text: LocalizedStringResource(stringLiteral: "Snooze \(alarm.snoozeMinutes) min"),
        textColor: .white, systemImageName: "zzz") : nil
    let alert: AlarmPresentation.Alert
    if #available(iOS 26.1, *) {
      alert = AlarmPresentation.Alert(title: title, secondaryButton: secondary,
        secondaryButtonBehavior: alarm.canSnooze ? .custom : nil)
    } else {
      alert = AlarmPresentation.Alert(title: title,
        stopButton: AlarmButton(text: "Stop", textColor: .white, systemImageName: "stop.circle"),
        secondaryButton: secondary, secondaryButtonBehavior: alarm.canSnooze ? .custom : nil)
    }
    let attributes = AlarmAttributes(presentation: AlarmPresentation(alert: alert), metadata: KtAlarmMetadata(), tintColor: .orange)
    let stopIntent: any LiveActivityIntent = alarm.hasMissions ? KtAlarmMissionIntent(alarm.id) : KtAlarmDismissIntent(alarm.id)
    // Fixed snooze alerts avoid requiring a separate Widget/Live Activity extension.
    return .alarm(schedule: schedule, attributes: attributes, stopIntent: stopIntent,
      secondaryIntent: alarm.canSnooze ? KtAlarmSnoozeIntent(alarm.id) : nil,
      sound: sound.map { .named($0) } ?? .default)
  }
  #endif

  private func stopSystemAlarm(_ id: String) {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *), let alarm = AlarmStore.get(id), let live = (try? AlarmManager.shared.alarms) {
      for item in live where (item.id.uuidString.lowercased() == id.lowercased() ||
        item.id.uuidString.lowercased() == alarm.snoozeId?.lowercased()) && item.state == .alerting {
        do {
          try AlarmManager.shared.stop(id: item.id)
          stoppedDeliveries.insert(item.id.uuidString.lowercased())
        } catch { /* Keep observing the live alarm if the system did not accept Stop. */ }
      }
    }
    #endif
  }

  private func cancel(_ id: String) throws {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *), let uuid = UUID(uuidString: id),
       (try AlarmManager.shared.alarms).contains(where: { $0.id == uuid }) {
      try AlarmManager.shared.cancel(id: uuid)
    }
    #endif
  }
  private func cancelSnooze(_ alarm: StoredAlarm?) {
    if let id = alarm?.snoozeId { try? cancel(id) }
  }
  private func notify() {
    changed?()
    SleepSoundPlayer.shared.capAtNextAlarm()
    sync()
    ringingChanged?(ringingID)
  }
  private func lockedError() -> Error { alarmError("ERR_ALARM_LOCKED", "Complete the wake-up missions before changing this alarm.") }
  private func busyError() -> Error { alarmError("ERR_ALARM_BUSY", "This alarm is being updated. Try again in a moment.") }
  private func unavailableError() -> Error { alarmError("ERR_ALARM_KIT_UNAVAILABLE", "Wake-up alarms require iOS 26 or newer. Sleep sounds and mission previews are still available.") }
}
