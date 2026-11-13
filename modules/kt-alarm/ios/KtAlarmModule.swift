import ExpoModulesCore
import Foundation
import UIKit
#if canImport(AlarmKit)
import AlarmKit
import SwiftUI
#endif

// MARK: - Storage

struct StoredAlarm: Codable {
  var id: String
  var hour: Int
  var minute: Int
  var label: String
  var days: [Int]
  var enabled: Bool
  var vibrate: Bool
  var snoozeMinutes: Int

  func nextTrigger(after now: Date = Date()) -> Date? {
    guard enabled else { return nil }
    let cal = Calendar.current
    var comps = DateComponents()
    comps.hour = hour
    comps.minute = minute
    if days.isEmpty {
      return cal.nextDate(after: now, matching: comps, matchingPolicy: .nextTime)
    }
    return days.compactMap { day -> Date? in
      var c = comps
      c.weekday = day + 1 // Calendar: 1 = Sunday
      return cal.nextDate(after: now, matching: c, matchingPolicy: .nextTime)
    }.min()
  }

  func toDict() -> [String: Any] {
    [
      "id": id,
      "hour": hour,
      "minute": minute,
      "label": label,
      "days": days,
      "enabled": enabled,
      "vibrate": vibrate,
      "snoozeMinutes": snoozeMinutes,
      "snoozeUntil": NSNull(),
      "nextTriggerAt": nextTrigger().map { $0.timeIntervalSince1970 * 1000 as Any } ?? NSNull(),
    ]
  }
}

enum AlarmStore {
  private static let key = "kt_alarm_store"

  static func all() -> [StoredAlarm] {
    guard let data = UserDefaults.standard.data(forKey: key),
          let list = try? JSONDecoder().decode([StoredAlarm].self, from: data) else { return [] }
    return list
  }

  static func write(_ list: [StoredAlarm]) {
    if let data = try? JSONEncoder().encode(list) {
      UserDefaults.standard.set(data, forKey: key)
    }
  }

  static func upsert(_ alarm: StoredAlarm) {
    var list = all()
    if let i = list.firstIndex(where: { $0.id == alarm.id }) { list[i] = alarm } else { list.append(alarm) }
    write(list)
  }

  static func remove(_ id: String) {
    write(all().filter { $0.id != id })
  }
}

// MARK: - Records

struct AlarmInputRecord: Record {
  @Field var id: String?
  @Field var hour: Int = 7
  @Field var minute: Int = 0
  @Field var label: String = ""
  @Field var days: [Int] = []
  @Field var enabled: Bool = true
  @Field var vibrate: Bool = true
  @Field var snoozeMinutes: Int = 5
}

final class AlarmKitUnavailableException: Exception {
  override var reason: String { "Alarms need iOS 26 or newer (AlarmKit)." }
}

final class AlarmNotAuthorizedException: Exception {
  override var reason: String { "Alarm access was denied. Enable it in Settings > ktAlerm." }
}

// MARK: - AlarmKit bridge

#if canImport(AlarmKit)
@available(iOS 26.0, *)
struct KtAlarmMetadata: AlarmMetadata {}

@available(iOS 26.0, *)
enum AlarmKitBridge {
  static var manager: AlarmManager { AlarmManager.shared }

  static var isAuthorized: Bool { manager.authorizationState == .authorized }

  static func requestAuthorization() async -> Bool {
    switch manager.authorizationState {
    case .authorized: return true
    case .denied: return false
    case .notDetermined:
      return (try? await manager.requestAuthorization()) == .authorized
    @unknown default: return false
    }
  }

  static func schedule(_ alarm: StoredAlarm) async throws {
    guard let uuid = UUID(uuidString: alarm.id) else { return }
    try? manager.cancel(id: uuid)
    guard alarm.enabled else { return }
    guard await requestAuthorization() else { throw AlarmNotAuthorizedException() }

    let allDays: [Locale.Weekday] = [.sunday, .monday, .tuesday, .wednesday, .thursday, .friday, .saturday]
    let weekdays: [Locale.Weekday] = alarm.days.compactMap { allDays[safe: $0] }
    let schedule = Alarm.Schedule.relative(
      .init(
        time: .init(hour: alarm.hour, minute: alarm.minute),
        repeats: weekdays.isEmpty ? .never : .weekly(weekdays)
      )
    )

    let title = alarm.label.isEmpty ? "Alarm" : alarm.label
    let alert = AlarmPresentation.Alert(
      title: LocalizedStringResource(stringLiteral: title),
      stopButton: AlarmButton(text: "Dismiss", textColor: .white, systemImageName: "stop.circle"),
      secondaryButton: AlarmButton(
        text: LocalizedStringResource(stringLiteral: "Snooze \(alarm.snoozeMinutes) min"),
        textColor: .white,
        systemImageName: "zzz"
      ),
      secondaryButtonBehavior: .countdown
    )
    let attributes = AlarmAttributes<KtAlarmMetadata>(
      presentation: AlarmPresentation(alert: alert),
      metadata: KtAlarmMetadata(),
      tintColor: .orange
    )
    let config = AlarmManager.AlarmConfiguration<KtAlarmMetadata>(
      countdownDuration: Alarm.CountdownDuration(
        preAlert: nil,
        postAlert: TimeInterval(alarm.snoozeMinutes * 60)
      ),
      schedule: schedule,
      attributes: attributes
    )
    _ = try await manager.schedule(id: uuid, configuration: config)
  }

  static func cancel(_ id: String) {
    guard let uuid = UUID(uuidString: id) else { return }
    try? manager.cancel(id: uuid)
  }

  static func stop(_ id: String) {
    guard let uuid = UUID(uuidString: id) else { return }
    try? manager.stop(id: uuid)
  }

  static func snooze(_ id: String) {
    guard let uuid = UUID(uuidString: id) else { return }
    try? manager.countdown(id: uuid)
  }

  /// One-time alarms disappear from AlarmKit after they ring; mirror that as "disabled".
  static func syncFired() -> Bool {
    guard let live = try? manager.alarms else { return false }
    let liveIds = Set(live.map { $0.id.uuidString })
    var changed = false
    var list = AlarmStore.all()
    for i in list.indices where list[i].enabled && list[i].days.isEmpty && !liveIds.contains(list[i].id) {
      list[i].enabled = false
      changed = true
    }
    if changed { AlarmStore.write(list) }
    return changed
  }

  static func alertingId(in alarms: [Alarm]) -> String? {
    alarms.first(where: { $0.state == .alerting })?.id.uuidString
  }
}
#endif

private extension Array {
  subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

// MARK: - Module

public class KtAlarmModule: Module {
  private var observeTask: Task<Void, Never>?

  public func definition() -> ModuleDefinition {
    Name("KtAlarm")

    Events("onAlarmsChanged", "onRingingChanged")

    OnStartObserving {
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *) {
        self.observeTask?.cancel()
        self.observeTask = Task { [weak self] in
          var lastRinging: String?
          for await alarms in AlarmKitBridge.manager.alarmUpdates {
            guard let self else { return }
            let ringing = AlarmKitBridge.alertingId(in: alarms)
            if ringing != lastRinging {
              lastRinging = ringing
              self.sendEvent("onRingingChanged", ["alarmId": ringing.map { $0 as Any } ?? NSNull()])
            }
            if AlarmKitBridge.syncFired() {
              self.sendEvent("onAlarmsChanged", [:])
            }
          }
        }
      }
      #endif
    }

    OnStopObserving {
      self.observeTask?.cancel()
      self.observeTask = nil
    }

    Function("getAlarms") { () -> [[String: Any]] in
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *) { _ = AlarmKitBridge.syncFired() }
      #endif
      return AlarmStore.all()
        .sorted { ($0.hour, $0.minute) < ($1.hour, $1.minute) }
        .map { $0.toDict() }
    }

    AsyncFunction("saveAlarm") { (input: AlarmInputRecord) async throws -> [String: Any] in
      let alarm = StoredAlarm(
        id: input.id ?? UUID().uuidString,
        hour: max(0, min(23, input.hour)),
        minute: max(0, min(59, input.minute)),
        label: input.label.trimmingCharacters(in: .whitespacesAndNewlines),
        days: Array(Set(input.days.filter { (0...6).contains($0) })).sorted(),
        enabled: input.enabled,
        vibrate: input.vibrate,
        snoozeMinutes: max(1, min(60, input.snoozeMinutes))
      )
      try await Self.schedule(alarm)
      AlarmStore.upsert(alarm)
      return alarm.toDict()
    }

    AsyncFunction("deleteAlarm") { (id: String) in
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *) { AlarmKitBridge.cancel(id) }
      #endif
      AlarmStore.remove(id)
    }

    AsyncFunction("setAlarmEnabled") { (id: String, enabled: Bool) async throws in
      guard var alarm = AlarmStore.all().first(where: { $0.id == id }) else { return }
      alarm.enabled = enabled
      try await Self.schedule(alarm)
      AlarmStore.upsert(alarm)
    }

    Function("snooze") { (id: String) in
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *) { AlarmKitBridge.snooze(id) }
      #endif
    }

    Function("dismiss") { (id: String) in
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *) { AlarmKitBridge.stop(id) }
      #endif
    }

    Function("getRingingAlarmId") { () -> String? in
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *), let alarms = try? AlarmKitBridge.manager.alarms {
        return AlarmKitBridge.alertingId(in: alarms)
      }
      #endif
      return nil
    }

    // MARK: Permissions — on iOS a single AlarmKit authorization covers everything.

    Function("getPermissionStatus") { () -> [String: Bool] in
      var authorized = false
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *) { authorized = AlarmKitBridge.isAuthorized }
      #endif
      return [
        "notifications": authorized,
        "exactAlarm": authorized,
        "fullScreenIntent": authorized,
        "batteryOptimizationIgnored": true,
        "overlay": true,
      ]
    }

    AsyncFunction("requestNotificationPermission") { () async -> [String: Bool] in
      #if canImport(AlarmKit)
      if #available(iOS 26.0, *) {
        return ["granted": await AlarmKitBridge.requestAuthorization()]
      }
      #endif
      return ["granted": false]
    }

    Function("openExactAlarmSettings") { Self.openAppSettings() }
    Function("openFullScreenIntentSettings") { Self.openAppSettings() }
    Function("requestIgnoreBatteryOptimizations") {}
    Function("openOverlaySettings") {}
    Function("openNotificationSettings") { Self.openAppSettings() }
    Function("openAutoStartSettings") { () -> Bool in false }
  }

  private static func schedule(_ alarm: StoredAlarm) async throws {
    #if canImport(AlarmKit)
    if #available(iOS 26.0, *) {
      try await AlarmKitBridge.schedule(alarm)
      return
    }
    #endif
    throw AlarmKitUnavailableException()
  }

  private static func openAppSettings() {
    DispatchQueue.main.async {
      if let url = URL(string: UIApplication.openSettingsURLString) {
        UIApplication.shared.open(url)
      }
    }
  }
}
