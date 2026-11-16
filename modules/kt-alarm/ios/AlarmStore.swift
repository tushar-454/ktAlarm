import Foundation
import ExpoModulesCore

func alarmError(_ code: String, _ message: String) -> Exception {
  Exception(name: "KtAlarmException", description: message, code: code)
}

/// All store access is serialized on the main thread, including synchronous JS calls.
func onAlarmMain<T>(_ body: @MainActor () throws -> T) rethrows -> T {
  if Thread.isMainThread { return try MainActor.assumeIsolated(body) }
  return try DispatchQueue.main.sync { try MainActor.assumeIsolated(body) }
}

struct StoredMission: Codable, Equatable {
  var type: String
  var difficulty: Int
  var count: Int
  var barcodeValue: String?
  var barcodeName: String?

  var dictionary: [String: Any] {
    ["type": type, "difficulty": difficulty, "count": count,
     "barcodeValue": barcodeValue as Any? ?? NSNull(), "barcodeName": barcodeName as Any? ?? NSNull()]
  }
}

struct MissionInputRecord: Record {
  @Field var type: String = ""
  @Field var difficulty: Int = 1
  @Field var count: Int?
  @Field var barcodeValue: String?
  @Field var barcodeName: String?

  func mission() throws -> StoredMission {
    let ranges = ["math": (1, 10, 3), "shake": (10, 100, 30), "walk": (10, 300, 30),
                  "squat": (5, 50, 10), "typing": (1, 5, 2), "barcode": (1, 1, 1)]
    guard let range = ranges[type], type != "barcode" || !(barcodeValue ?? "").isEmpty else {
      throw alarmError("ERR_INVALID_MISSION", "Choose a valid mission and register a code for barcode missions.")
    }
    return StoredMission(type: type, difficulty: min(3, max(0, difficulty)),
      count: min(range.1, max(range.0, count ?? range.2)),
      barcodeValue: type == "barcode" ? barcodeValue : nil,
      barcodeName: type == "barcode" ? barcodeName.map { String($0.prefix(40)) } : nil)
  }
}

struct AlarmInputRecord: Record {
  @Field var id: String?
  @Field var hour: Int = 7
  @Field var minute: Int = 0
  @Field var label: String = ""
  @Field var days: [Int] = []
  @Field var enabled: Bool = true
  @Field var vibrate: Bool = true
  @Field var snoozeMinutes: Int = 5
  @Field var missions: [MissionInputRecord] = []
  @Field var maxSnoozes: Int = -1
  @Field var ringtoneUri: String?
  @Field var ringtoneName: String?
  @Field var backgroundUri: String?
  @Field var backgroundName: String?
  @Field var sleepSoundUri: String?
  @Field var sleepSoundName: String?
  @Field var sleepSoundMinutes: Int = 30
}

struct SleepSoundInputRecord: Record {
  @Field var uri: String = "preset:pink"
  @Field var name: String = "Pink noise"
  @Field var minutes: Int = 30
  @Field var alarmId: String?
}

struct StoredAlarm: Codable {
  var id: String
  var hour: Int
  var minute: Int
  var label: String
  var days: [Int]
  var enabled: Bool
  var vibrate: Bool
  var snoozeMinutes: Int
  // Optional additions keep alarms saved by the original iOS app decodable.
  var missions: [StoredMission]?
  var maxSnoozes: Int?
  var snoozeCount: Int?
  var snoozeUntil: Date?
  var snoozeId: String?
  var scheduledAt: Date?
  var pendingMission: Bool?
  var pendingSince: Date?
  var missionIndex: Int?
  var ringtoneUri: String?
  var ringtoneName: String?
  var backgroundUri: String?
  var backgroundName: String?
  var sleepSoundUri: String?
  var sleepSoundName: String?
  var sleepSoundMinutes: Int?

  var hasMissions: Bool { !(missions ?? []).isEmpty }
  var locked: Bool { hasMissions && (pendingMission == true || snoozeUntil != nil) }
  var canSnooze: Bool { (maxSnoozes ?? -1) < 0 || (snoozeCount ?? 0) < (maxSnoozes ?? -1) }

  func latestOccurrence(before now: Date = Date()) -> Date? {
    if days.isEmpty { return scheduledAt }
    var components = DateComponents()
    components.hour = hour; components.minute = minute; components.second = 0
    return days.compactMap { day -> Date? in
      var c = components; c.weekday = day + 1
      return Calendar.current.nextDate(after: now.addingTimeInterval(1), matching: c,
        matchingPolicy: .nextTime, direction: .backward)
    }.max()
  }

  func nextTrigger(after now: Date = Date()) -> Date? {
    if let snoozeUntil { return snoozeUntil }
    guard enabled else { return nil }
    if days.isEmpty, let scheduledAt { return scheduledAt }
    var components = DateComponents()
    components.hour = hour
    components.minute = minute
    if days.isEmpty {
      return Calendar.current.nextDate(after: now, matching: components, matchingPolicy: .nextTime)
    }
    return days.compactMap { day -> Date? in
      var c = components
      c.weekday = day + 1
      return Calendar.current.nextDate(after: now, matching: c, matchingPolicy: .nextTime)
    }.min()
  }

  func toDict() -> [String: Any] {
    ["id": id, "hour": hour, "minute": minute, "label": label, "days": days,
     "enabled": enabled, "vibrate": vibrate, "snoozeMinutes": snoozeMinutes,
     "snoozeUntil": snoozeUntil.map { $0.timeIntervalSince1970 * 1000 as Any } ?? NSNull(),
     "nextTriggerAt": nextTrigger().map { $0.timeIntervalSince1970 * 1000 as Any } ?? NSNull(),
     "missions": (missions ?? []).map { $0.dictionary }, "maxSnoozes": maxSnoozes ?? -1,
     "snoozeCount": snoozeCount ?? 0, "locked": locked,
     "ringtoneUri": AlarmMedia.currentURI(ringtoneUri) as Any? ?? NSNull(), "ringtoneName": ringtoneName as Any? ?? NSNull(),
     "backgroundUri": AlarmMedia.currentURI(backgroundUri) as Any? ?? NSNull(), "backgroundName": backgroundName as Any? ?? NSNull(),
     "sleepSoundUri": AlarmMedia.currentURI(sleepSoundUri) as Any? ?? NSNull(), "sleepSoundName": sleepSoundName as Any? ?? NSNull(),
     "sleepSoundMinutes": sleepSoundMinutes ?? 30]
  }
}

@MainActor
enum AlarmStore {
  private static let key = "kt_alarm_store"
  static func all() -> [StoredAlarm] {
    guard let data = UserDefaults.standard.data(forKey: key),
          let alarms = try? JSONDecoder().decode([StoredAlarm].self, from: data) else { return [] }
    return alarms
  }
  static func get(_ id: String) -> StoredAlarm? { all().first { $0.id == id } }
  static func write(_ alarms: [StoredAlarm]) {
    guard let data = try? JSONEncoder().encode(alarms) else { return }
    UserDefaults.standard.set(data, forKey: key)
  }
  static func upsert(_ alarm: StoredAlarm) {
    var alarms = all()
    if let i = alarms.firstIndex(where: { $0.id == alarm.id }) { alarms[i] = alarm }
    else { alarms.append(alarm) }
    write(alarms)
  }
  static func remove(_ id: String) { write(all().filter { $0.id != id }) }
}
