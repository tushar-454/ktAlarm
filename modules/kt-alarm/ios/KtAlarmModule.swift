import AVFoundation
import CoreMotion
import ExpoModulesCore
import UIKit

public class KtAlarmModule: Module {
  private let permissionPedometer = CMPedometer()

  public func definition() -> ModuleDefinition {
    Name("KtAlarm")
    Events("onAlarmsChanged", "onRingingChanged", "onSleepSoundChanged")

    OnCreate {
      onAlarmMain {
        AlarmEngine.shared.start()
        AlarmEngine.shared.changed = { [weak self] in self?.sendEvent("onAlarmsChanged", [:]) }
        AlarmEngine.shared.ringingChanged = { [weak self] id in
          self?.sendEvent("onRingingChanged", ["alarmId": id as Any? ?? NSNull()])
        }
        SleepSoundPlayer.shared.changed = { [weak self] in self?.sendEvent("onSleepSoundChanged", $0) }
      }
    }
    OnDestroy {
      onAlarmMain {
        AlarmEngine.shared.changed = nil
        AlarmEngine.shared.ringingChanged = nil
        SleepSoundPlayer.shared.changed = nil
      }
    }

    Function("getAlarms") { () -> [[String: Any]] in
      onAlarmMain {
        AlarmEngine.shared.sync()
        return AlarmStore.all().sorted { ($0.hour, $0.minute) < ($1.hour, $1.minute) }.map { $0.toDict() }
      }
    }
    AsyncFunction("saveAlarm") { (input: AlarmInputRecord) async throws -> [String: Any] in
      try await AlarmEngine.shared.save(input)
    }
    AsyncFunction("deleteAlarm") { (id: String) async throws in
      try await MainActor.run { try AlarmEngine.shared.delete(id) }
    }
    AsyncFunction("setAlarmEnabled") { (id: String, enabled: Bool) async throws in
      try await AlarmEngine.shared.setEnabled(id, enabled)
    }
    AsyncFunction("snooze") { (id: String) async throws -> Bool in
      try await AlarmEngine.shared.snooze(id)
    }
    Function("dismiss") { (id: String) -> Bool in onAlarmMain { AlarmEngine.shared.dismiss(id) } }
    Function("getRingingAlarmId") { () -> String? in
      onAlarmMain { AlarmEngine.shared.sync(); return AlarmEngine.shared.ringingID }
    }

    Function("previewMission") { (input: MissionInputRecord, backgroundUri: String?) throws in
      let mission = try input.mission()
      let background = try AlarmMedia.checked(backgroundUri, kind: "image")
      onAlarmMain {
        guard !AlarmStore.all().contains(where: { $0.locked }) else { return }
        var alarm = StoredAlarm(id: "__preview__", hour: Calendar.current.component(.hour, from: Date()),
          minute: Calendar.current.component(.minute, from: Date()), label: "Mission preview",
          days: [], enabled: false, vibrate: false, snoozeMinutes: 5)
        alarm.missions = [mission]
        alarm.backgroundUri = background
        AlarmMissionPresenter.shared.present(alarm, preview: true)
      }
    }
    AsyncFunction("scanBarcode") { (promise: Promise) in
      Task { @MainActor in
        await BarcodeRegistration.shared.scan(presenter: AlarmMissionPresenter.topController()) { result in
          switch result {
          case .success(let code): promise.resolve(code)
          case .failure(let error): promise.reject(error)
          }
        }
      }
    }
    AsyncFunction("pickAlarmMedia") { (kind: String, promise: Promise) in
      onAlarmMain { AlarmMediaPicker.shared.pick(kind, presenter: AlarmMissionPresenter.topController(), promise: promise) }
    }.runOnQueue(.main)

    Function("getSleepSoundState") { () -> [String: Any] in onAlarmMain { SleepSoundPlayer.shared.state } }
    AsyncFunction("startSleepSound") { (input: SleepSoundInputRecord) in
      try onAlarmMain { try SleepSoundPlayer.shared.start(input) }
    }.runOnQueue(.main)
    AsyncFunction("stopSleepSound") { onAlarmMain { SleepSoundPlayer.shared.stop() } }.runOnQueue(.main)

    Function("getPermissionStatus") { () -> [String: Bool] in
      onAlarmMain {
        let authorized = AlarmEngine.shared.authorized
        return ["notifications": authorized, "exactAlarm": authorized, "fullScreenIntent": authorized,
          "batteryOptimizationIgnored": true, "overlay": true,
          "camera": AVCaptureDevice.authorizationStatus(for: .video) == .authorized,
          "activityRecognition": CMPedometer.authorizationStatus() == .authorized,
          "stepDetector": CMPedometer.isStepCountingAvailable(),
          "alarmsSupported": AlarmEngine.shared.supported]
      }
    }
    AsyncFunction("requestNotificationPermission") { () async -> [String: Bool] in
      ["granted": await AlarmEngine.shared.requestAuthorization()]
    }
    AsyncFunction("requestCameraPermission") { () async -> [String: Bool] in
      let granted = await AVCaptureDevice.requestAccess(for: .video)
      return ["granted": granted, "canAskAgain": AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined]
    }
    AsyncFunction("requestActivityRecognitionPermission") { (promise: Promise) in
      guard CMPedometer.isStepCountingAvailable() else { promise.resolve(["granted": false]); return }
      self.permissionPedometer.queryPedometerData(from: Date().addingTimeInterval(-60), to: Date()) { _, _ in
        promise.resolve(["granted": CMPedometer.authorizationStatus() == .authorized])
      }
    }

    Function("openExactAlarmSettings") { Self.openAppSettings() }
    Function("openFullScreenIntentSettings") { Self.openAppSettings() }
    Function("openNotificationSettings") { Self.openAppSettings() }
    Function("requestIgnoreBatteryOptimizations") {}
    Function("openOverlaySettings") {}
    Function("openAutoStartSettings") { false }
    // There is no consumer iOS device-admin API for uninstall protection.
    Function("isUninstallProtectionEnabled") { false }
    Function("enableUninstallProtection") {}
    AsyncFunction("disableUninstallProtection") {}
  }

  private static func openAppSettings() {
    DispatchQueue.main.async {
      if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
  }
}
