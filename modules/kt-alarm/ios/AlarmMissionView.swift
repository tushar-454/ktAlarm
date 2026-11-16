import AVFoundation
import Combine
import CoreMotion
import SwiftUI
import UIKit

@MainActor
final class AlarmMissionPresenter {
  static let shared = AlarmMissionPresenter()
  private var controller: UIViewController?
  private var alarm: StoredAlarm?
  private var player: AVAudioPlayer?
  private var timer: Timer?
  private var previousIdleDisabled = false
  private var quietUntil = 0.0

  func present(_ alarm: StoredAlarm, preview: Bool = false) {
    if self.alarm?.id == "__preview__" && !preview {
      close("__preview__")
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { self.present(alarm) }
      return
    }
    if controller != nil { return }
    guard let presenter = Self.topController(), presenter.view.window != nil,
          !presenter.isBeingDismissed, !presenter.isBeingPresented else { return }
    self.alarm = alarm
    quietUntil = 0
    let session = MissionSession(alarm: alarm, preview: preview)
    let root = UIHostingController(rootView: AlarmMissionView(session: session))
    root.modalPresentationStyle = .fullScreen
    root.isModalInPresentation = true
    controller = root
    previousIdleDisabled = UIApplication.shared.isIdleTimerDisabled
    UIApplication.shared.isIdleTimerDisabled = true
    presenter.present(root, animated: true)
    if !preview {
      SleepSoundPlayer.shared.stop()
      timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
        MainActor.assumeIsolated { self?.refreshAudio() }
      }
      refreshAudio()
    }
  }

  func close(_ id: String) {
    guard alarm?.id == id else { return }
    timer?.invalidate(); timer = nil
    let hadPlayer = player != nil
    player?.stop(); player = nil
    if hadPlayer { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    UIApplication.shared.isIdleTimerDisabled = previousIdleDisabled
    controller?.dismiss(animated: true) { AlarmEngine.shared.presentPendingMission() }
    controller = nil; alarm = nil
  }

  func effort(seconds: Double) {
    quietUntil = ProcessInfo.processInfo.systemUptime + seconds
    player?.setVolume(0.2, fadeDuration: 0.3)
  }

  private func refreshAudio() {
    AlarmEngine.shared.expireMissions()
    guard let alarm else { return }
    guard UIApplication.shared.applicationState == .active else {
      player?.pause()
      return
    }
    // AlarmKit provides the sound until its system Stop action opens the missions.
    let systemAlerting = AlarmEngine.shared.systemIsAlerting(alarm)
    if systemAlerting {
      player?.pause()
      return
    }
    if player == nil {
      do {
        let uri = alarm.ringtoneUri ?? "preset:bell"
        player = try AVAudioPlayer(contentsOf: AlarmMedia.url(uri))
        player?.numberOfLoops = -1
      } catch { return }
    }
    if player?.isPlaying != true {
      try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
      try? AVAudioSession.sharedInstance().setActive(true)
      player?.play()
      player?.setVolume(1, fadeDuration: 20)
    }
    if ProcessInfo.processInfo.systemUptime >= quietUntil, (player?.volume ?? 1) < 1 {
      player?.setVolume(1, fadeDuration: 1)
    }
    if alarm.vibrate { AudioServicesPlaySystemSound(kSystemSoundID_Vibrate) }
  }

  static func topController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    guard let root = scenes.first(where: { $0.activationState == .foregroundActive })?
      .windows.first(where: { $0.isKeyWindow })?.rootViewController else { return nil }
    var top = root
    while let presented = top.presentedViewController, !presented.isBeingDismissed { top = presented }
    return top
  }
}

@MainActor
final class MissionSession: ObservableObject {
  let alarm: StoredAlarm
  let preview: Bool
  @Published private(set) var index: Int
  @Published private(set) var progress = 0
  @Published var entry = ""
  @Published var prompt = ""
  @Published var message = ""
  @Published var cameraAllowed = false
  @Published var busy = false
  @Published private var fallback: StoredMission?
  private var answer = ""
  private var motion = CMMotionManager()
  private var pedometer = CMPedometer()
  private var walk = MissionWalkCounter()
  private var squat = MissionSquatCounter()
  private var lastShake = 0.0
  private var shakeArmed = true
  private var active = false
  private var generation = 0
  private var observers: [NSObjectProtocol] = []
  private var idleTimer: Timer?
  private var lastEffort = ProcessInfo.processInfo.systemUptime
  private let phrases = ["I am awake and ready for today", "Today is going to be a great day",
    "I get out of bed the first time", "Small steps every morning add up", "My future self will thank me",
    "I choose energy over excuses", "The early hours belong to me", "I keep the promises I make to myself",
    "Discipline is choosing what I want most", "Rise up and make it happen",
    "A fresh start begins right now", "I am stronger than my snooze button"].shuffled()

  var missions: [StoredMission] { alarm.missions ?? [] }
  var mission: StoredMission? { fallback ?? (missions.indices.contains(index) ? missions[index] : nil) }

  init(alarm: StoredAlarm, preview: Bool) {
    self.alarm = alarm; self.preview = preview
    index = preview ? 0 : min(alarm.missionIndex ?? 0, max(0, (alarm.missions ?? []).count - 1))
    observers.append(NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification,
      object: nil, queue: .main) { [weak self] _ in MainActor.assumeIsolated { self?.pause() } })
    observers.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
      object: nil, queue: .main) { [weak self] _ in MainActor.assumeIsolated { self?.resume() } })
  }

  deinit {
    idleTimer?.invalidate()
    motion.stopAccelerometerUpdates()
    pedometer.stopUpdates()
    for observer in observers { NotificationCenter.default.removeObserver(observer) }
  }

  func begin() {
    active = true
    startMission()
    idleTimer?.invalidate()
    idleTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
      MainActor.assumeIsolated { self?.checkIdle() }
    }
  }
  func pause() {
    active = false
    generation += 1
    motion.stopAccelerometerUpdates()
    pedometer.stopUpdates()
  }
  func resume() {
    guard !active else { return }
    active = true
    startSensors()
  }
  func stop() { pause(); idleTimer?.invalidate(); idleTimer = nil }

  private func startMission() {
    progress = 0; entry = ""; message = ""
    fallback = nil
    lastEffort = ProcessInfo.processInfo.systemUptime
    walk = MissionWalkCounter(); squat = MissionSquatCounter()
    setPrompt()
    startSensors()
  }

  func setPrompt() {
    guard let mission else { return }
    if mission.type == "typing" {
      prompt = phrases[progress % phrases.count]; answer = prompt
    } else if mission.type == "math" {
      switch mission.difficulty {
      case 0:
        let a = Int.random(in: 1...9), b = Int.random(in: 1...9)
        prompt = "\(a) + \(b)"; answer = String(a + b)
      case 2:
        let a = Int.random(in: 10...29), b = Int.random(in: 2...9), c = Int.random(in: 10...49)
        prompt = "\(a) × \(b) + \(c)"; answer = String(a * b + c)
      case 3:
        let a = Int.random(in: 20...99), b = Int.random(in: 10...49)
        prompt = "\(a) × \(b)"; answer = String(a * b)
      default:
        let a = Int.random(in: 10...99), b = Int.random(in: 10...99)
        prompt = "\(a) + \(b)"; answer = String(a + b)
      }
    }
  }

  func submit() {
    guard active, let mission, mission.type == "math" || mission.type == "typing" else { return }
    noteEffort()
    let correct = mission.type == "math" ? Int(entry.trimmingCharacters(in: .whitespacesAndNewlines)) == Int(answer) :
      normalize(entry) == normalize(answer)
    guard correct else {
      message = "Try again."; return
    }
    advance()
    entry = ""
    setPrompt()
  }

  func scanned(_ value: String) {
    guard active, mission?.type == "barcode" else { return }
    noteEffort()
    if value == mission?.barcodeValue { advance() }
    else { message = "That is a different code. Scan \(mission?.barcodeName ?? "your registered code")." }
  }

  func editEntry(_ text: String) {
    entry = text
    noteEffort()
    if mission?.type == "typing", normalize(text) == normalize(answer) { submit() }
  }

  private func normalize(_ text: String) -> String {
    text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ").lowercased()
  }

  private func noteEffort() {
    lastEffort = ProcessInfo.processInfo.systemUptime
    guard !preview, let mission else { return }
    let seconds = mission.type == "barcode" ? 90.0 : (mission.type == "walk" || mission.type == "squat" ? 60.0 : 30.0)
    AlarmMissionPresenter.shared.effort(seconds: seconds)
  }

  private func checkIdle() {
    guard active, let mission else { return }
    let timeout = mission.type == "barcode" ? 90.0 : (mission.type == "walk" || mission.type == "squat" ? 60.0 : 30.0)
    if ProcessInfo.processInfo.systemUptime - lastEffort >= timeout, progress > 0 || !entry.isEmpty {
      progress = 0; entry = ""; lastEffort = ProcessInfo.processInfo.systemUptime
      message = "No activity — this mission has restarted."
      setPrompt(); startSensors()
    }
  }

  func detectedPose(_ ratios: [Double]) {
    guard active, mission?.type == "squat" else { return }
    if squat.update(ratios: ratios, now: ProcessInfo.processInfo.systemUptime) { advance() }
    message = ratios.isEmpty ? "Step back until your hips, knees and ankles are visible." :
      squat.phase == .calibrating ? "Stand up straight to calibrate." :
      squat.phase == .down ? "Stand back up." : "Squat until your hips are near knee height."
  }

  func useMathFallback(_ reason: String) {
    guard active else { return }
    generation += 1
    motion.stopAccelerometerUpdates(); pedometer.stopUpdates()
    fallback = StoredMission(type: "math", difficulty: 1, count: 3)
    progress = 0; entry = ""
    message = "\(reason) Solve three math problems instead."
    setPrompt()
  }

  private func advance() {
    guard let mission, !busy else { return }
    progress += 1; message = ""
    noteEffort()
    guard progress >= mission.count else { return }
    generation += 1
    motion.stopAccelerometerUpdates(); pedometer.stopUpdates()
    squat = MissionSquatCounter(); lastShake = 0; shakeArmed = true
    index += 1
    if !preview, var stored = AlarmStore.get(alarm.id) {
      stored.missionIndex = index
      AlarmStore.upsert(stored)
    }
    if index >= missions.count {
      active = false
      if preview { AlarmMissionPresenter.shared.close(alarm.id) }
      else { _ = AlarmEngine.shared.dismiss(alarm.id, missionCompleted: true) }
    } else { startMission() }
  }

  func snooze() {
    guard !busy else { return }
    busy = true
    Task {
      do {
        let snoozed = try await AlarmEngine.shared.snooze(alarm.id)
        if !snoozed { message = "No snoozes left. Complete your missions." }
      } catch { message = error.localizedDescription }
      busy = false
    }
  }

  private func startSensors() {
    guard active, let mission else { return }
    generation += 1
    let generation = self.generation
    motion.stopAccelerometerUpdates(); pedometer.stopUpdates()
    squat = MissionSquatCounter(); lastShake = 0; shakeArmed = true
    if mission.type == "squat" || mission.type == "barcode" {
      cameraAllowed = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
      if !cameraAllowed {
        Task {
          let allowed = await AVCaptureDevice.requestAccess(for: .video)
          guard self.generation == generation else { return }
          cameraAllowed = allowed
          if !allowed { useMathFallback("Camera access is off.") }
        }
      }
    }
    if mission.type == "walk", CMPedometer.isStepCountingAvailable(), CMPedometer.authorizationStatus() != .denied,
      CMPedometer.authorizationStatus() != .restricted {
      let baseline = progress
      pedometer.startUpdates(from: Date()) { [weak self] data, error in
        Task { @MainActor in
          guard let self, self.generation == generation, self.active else { return }
          if error != nil {
            self.pedometer.stopUpdates()
            self.startAccelerometer(generation: generation)
            return
          }
          let counted = min(mission.count, baseline + (data?.numberOfSteps.intValue ?? 0))
          if counted >= mission.count { self.progress = mission.count - 1; self.advance() }
          else {
            if counted > self.progress { self.noteEffort() }
            self.progress = counted
          }
        }
      }
    } else if mission.type == "walk" || mission.type == "shake" { startAccelerometer(generation: generation) }
  }

  private func startAccelerometer(generation: Int) {
    guard motion.isAccelerometerAvailable else { useMathFallback("Motion sensors are unavailable on this device."); return }
    walk = MissionWalkCounter()
    motion.accelerometerUpdateInterval = 0.02
    motion.startAccelerometerUpdates(to: .main) { [weak self] data, error in
      MainActor.assumeIsolated {
        guard let self, self.active, self.generation == generation else { return }
        if error != nil { self.useMathFallback("Motion tracking failed."); return }
        guard let data else { return }
        let a = data.acceleration
        if self.mission?.type == "shake" {
          let magnitude = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
          if magnitude < 1.3 { self.shakeArmed = true }
          if magnitude > 2.2 && self.shakeArmed && data.timestamp - self.lastShake > 0.35 {
            self.shakeArmed = false; self.lastShake = data.timestamp; self.advance()
          }
        } else if self.walk.update(x: a.x * 9.81, y: a.y * 9.81, z: a.z * 9.81, now: data.timestamp) {
          self.advance()
        }
      }
    }
  }
}

private struct AlarmMissionView: View {
  @StateObject var session: MissionSession
  var body: some View {
    ZStack {
      background.ignoresSafeArea()
      ScrollView {
        VStack(spacing: 24) {
          if session.preview {
            HStack {
              Text("Mission preview").font(.headline)
              Spacer()
              Button("Close") { AlarmMissionPresenter.shared.close(session.alarm.id) }
            }
          }
          Text(session.alarm.label.isEmpty ? "Time to wake up" : session.alarm.label)
            .font(.title2.bold()).multilineTextAlignment(.center)
          Text(String(format: "%02d:%02d", session.alarm.hour, session.alarm.minute))
            .font(.system(size: 64, weight: .light, design: .rounded)).monospacedDigit()
          if let mission = session.mission {
            Text("Mission \(session.index + 1) of \(session.missions.count) · \(mission.type.capitalized)")
              .font(.headline)
            Text("\(session.progress) / \(mission.count)").font(.largeTitle.bold()).monospacedDigit()
            ProgressView(value: Double(session.progress), total: Double(mission.count)).tint(.orange)
            if mission.type == "math" || mission.type == "typing" {
              Text(session.prompt).font(mission.type == "math" ? .largeTitle : .title3).multilineTextAlignment(.center)
              MissionEntryField(text: session.entry, numeric: mission.type == "math", onEdit: session.editEntry)
                .frame(height: 36)
                .padding().background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
                .accessibilityLabel(mission.type == "math" ? "Math answer" : "Type the phrase")
              Button("Check answer", action: session.submit).buttonStyle(.borderedProminent)
            } else if mission.type == "walk" {
              Text("Keep your phone in your trouser pocket and walk naturally. Motion & Fitness access improves step detection. Without it, the first few strides warm up a motion estimate.")
            } else if mission.type == "shake" {
              Text("Hold your phone securely and shake it until the counter is full.")
            } else if session.cameraAllowed {
              Text(mission.type == "squat" ? "Stand your phone up and step back about 2 m so your full body is visible." :
                "Scan \(mission.barcodeName ?? "your registered code") to complete this mission.")
              MissionCameraView(pose: mission.type == "squat", onCode: { value, _ in session.scanned(value) },
                onPose: session.detectedPose, onError: session.useMathFallback)
                .id(session.index).frame(height: 300).clipShape(RoundedRectangle(cornerRadius: 20))
            } else {
              Button("Open camera settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
              }
            }
          }
          if !session.message.isEmpty { Text(session.message).foregroundStyle(.orange).multilineTextAlignment(.center) }
          if !session.preview {
            Button("Snooze \(session.alarm.snoozeMinutes) min", action: session.snooze)
              .disabled(session.busy || AlarmStore.get(session.alarm.id)?.canSnooze == false)
              .buttonStyle(.bordered)
          }
        }
        .padding(24)
      }
    }
    .foregroundStyle(.white).tint(.orange).preferredColorScheme(.dark)
    .onAppear { session.begin() }.onDisappear { session.stop() }
  }

  @ViewBuilder private var background: some View {
    if let uri = session.alarm.backgroundUri, !uri.hasPrefix("preset:"), let url = try? AlarmMedia.url(uri),
       let image = UIImage(contentsOfFile: url.path) {
      GeometryReader { geometry in
        Image(uiImage: image).resizable().scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped()
      }
      Color.black.opacity(0.6)
    } else {
      let palettes: [String: [Color]] = [
        "preset:dawn": [Color(red: 0.17, green: 0.1, blue: 0.26), Color(red: 0.77, green: 0.39, blue: 0.38)],
        "preset:forest": [Color(red: 0.03, green: 0.1, blue: 0.12), Color(red: 0.1, green: 0.3, blue: 0.27)],
        "preset:midnight": [Color(red: 0.03, green: 0.06, blue: 0.17), Color(red: 0.13, green: 0.16, blue: 0.36)],
        "preset:sea": [Color(red: 0.03, green: 0.12, blue: 0.22), Color(red: 0.11, green: 0.35, blue: 0.44)]]
      LinearGradient(colors: palettes[session.alarm.backgroundUri ?? ""] ?? [.black, Color(white: 0.08)],
        startPoint: .top, endPoint: .bottom)
    }
  }
}

/// Match Android's mission input: no paste, suggestions, autocorrect, or phrase copying.
private final class MissionTextField: UITextField {
  override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool { false }
  override func paste(_ sender: Any?) {}
}

private struct MissionEntryField: UIViewRepresentable {
  let text: String
  let numeric: Bool
  let onEdit: @MainActor (String) -> Void
  func makeCoordinator() -> Coordinator { Coordinator(onEdit) }
  func makeUIView(context: Context) -> UITextField {
    let field = MissionTextField()
    field.textColor = .white
    field.font = .systemFont(ofSize: 20)
    field.autocorrectionType = .no
    field.autocapitalizationType = .none
    field.spellCheckingType = .no
    field.smartQuotesType = .no
    field.smartDashesType = .no
    field.addTarget(context.coordinator, action: #selector(Coordinator.edit(_:)), for: .editingChanged)
    return field
  }
  func updateUIView(_ field: UITextField, context: Context) {
    field.keyboardType = numeric ? .numberPad : .default
    field.placeholder = numeric ? "Answer" : "Type the phrase above"
    if field.text != text { field.text = text }
    context.coordinator.onEdit = onEdit
  }
  @MainActor final class Coordinator: NSObject {
    var onEdit: @MainActor (String) -> Void
    init(_ onEdit: @escaping @MainActor (String) -> Void) { self.onEdit = onEdit }
    @objc func edit(_ field: UITextField) { onEdit(field.text ?? "") }
  }
}
