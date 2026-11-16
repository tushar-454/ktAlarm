import AVFoundation
import MediaPlayer
import UIKit

@MainActor
final class SleepSoundPlayer: NSObject, AVAudioPlayerDelegate {
  static let shared = SleepSoundPlayer()
  var changed: (([String: Any]) -> Void)?
  private var player: AVAudioPlayer?
  private var timer: DispatchSourceTimer?
  private let clock = ContinuousClock()
  private var deadline: ContinuousClock.Instant?
  private var wallDeadline: Date?
  private var uri = "preset:pink"
  private var name = "Pink noise"
  private var minutes = 30
  private var alarmId: String?
  private var error: String?
  private var observers: [NSObjectProtocol] = []
  private var remoteTargets: [(MPRemoteCommand, Any)] = []

  override private init() {
    super.init()
    let center = NotificationCenter.default
    observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
      guard let type = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            type == AVAudioSession.InterruptionType.began.rawValue else { return }
      // Never resume sleep audio over a wake-up alarm or an incoming call.
      MainActor.assumeIsolated { self?.stop() }
    })
    observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
      guard let reason = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
            reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue else { return }
      MainActor.assumeIsolated { self?.stop() }
    })
    observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
      MainActor.assumeIsolated { self?.tick() }
    })
  }

  var state: [String: Any] {
    ["playing": player?.isPlaying == true, "preparing": false, "uri": uri, "name": name,
     "minutes": minutes, "alarmId": alarmId as Any? ?? NSNull(),
     "endsAt": wallDeadline.map { $0.timeIntervalSince1970 * 1000 as Any } ?? NSNull(),
     "error": error as Any? ?? NSNull()]
  }

  func start(_ input: SleepSoundInputRecord) throws {
    guard (1...720).contains(input.minutes) else {
      throw alarmError("ERR_SLEEP_DURATION", "Choose a duration between 1 and 720 minutes.")
    }
    guard !AlarmStore.all().contains(where: { $0.pendingMission == true }), !AlarmEngine.shared.isAlerting else {
      throw alarmError("ERR_ALARM_LOCKED", "Finish your wake-up alarm before starting sleep sounds.")
    }
    guard let checked = try AlarmMedia.checked(input.uri, kind: "sleep") else {
      throw alarmError("ERR_MEDIA_IMPORT", "Choose a sleep sound.")
    }
    let newPlayer = try AVAudioPlayer(contentsOf: AlarmMedia.url(checked))
    stop()
    uri = checked; name = String(input.name.prefix(120)); minutes = input.minutes; alarmId = input.alarmId
    error = nil
    let session = AVAudioSession.sharedInstance()
    do {
      player = newPlayer
      try session.setCategory(.playback, mode: .default)
      try session.setActive(true)
      newPlayer.numberOfLoops = -1
      newPlayer.volume = 0.55
      newPlayer.delegate = self
      newPlayer.prepareToPlay()
      guard newPlayer.play() else { throw alarmError("ERR_SLEEP_PLAYBACK", "Could not start this audio file.") }
      deadline = clock.now.advanced(by: .seconds(minutes * 60))
      wallDeadline = Date().addingTimeInterval(Double(minutes * 60))
      capAtNextAlarm()
      let timer = DispatchSource.makeTimerSource(queue: .main)
      timer.schedule(deadline: .now(), repeating: .seconds(1), leeway: .milliseconds(100))
      timer.setEventHandler { [weak self] in MainActor.assumeIsolated { self?.tick() } }
      self.timer = timer
      timer.resume()
      installRemoteCommands()
      changed?(state)
    } catch {
      self.error = error.localizedDescription
      stop(clearError: false)
      throw error
    }
  }

  /// Edits to any wake-up alarm can shorten an active sleep session immediately.
  func capAtNextAlarm() {
    guard player != nil, let deadline else { return }
    let now = Date()
    let upcoming = AlarmStore.all().compactMap { $0.nextTrigger() }.filter { $0 > now }.min()
    if let upcoming {
      let capped = min(deadline, clock.now.advanced(by: .seconds(upcoming.timeIntervalSince(now))))
      self.deadline = capped
      let remaining = clock.now.duration(to: capped).components
      wallDeadline = now.addingTimeInterval(Double(remaining.seconds) + Double(remaining.attoseconds) / 1e18)
    }
    changed?(state)
  }

  private func tick() {
    guard let deadline else { return }
    if clock.now >= deadline || wallDeadline.map({ Date() >= $0 }) == true {
      stop()
    }
  }

  func stop(clearError: Bool = true) {
    guard player != nil || deadline != nil || error != nil else { return }
    let wasPlaying = player != nil
    timer?.cancel(); timer = nil
    player?.stop(); player = nil
    deadline = nil; wallDeadline = nil
    if clearError { error = nil }
    for (command, target) in remoteTargets { command.removeTarget(target) }
    remoteTargets.removeAll()
    if wasPlaying {
      MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
      try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    changed?(state)
  }

  private func installRemoteCommands() {
    MPNowPlayingInfoCenter.default().nowPlayingInfo = [MPMediaItemPropertyTitle: name,
      MPMediaItemPropertyArtist: "ktAlerm · Sleep sounds", MPNowPlayingInfoPropertyIsLiveStream: true,
      MPNowPlayingInfoPropertyPlaybackRate: 1.0]
    let center = MPRemoteCommandCenter.shared()
    for command in [center.stopCommand, center.pauseCommand] {
      command.isEnabled = true
      let target = command.addTarget { [weak self] _ in
        Task { @MainActor in self?.stop() }
        return .success
      }
      remoteTargets.append((command, target))
    }
  }

  nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
    Task { @MainActor in
      guard self.player === player else { return }
      self.error = error?.localizedDescription ?? "Could not play this sound."
      self.stop(clearError: false)
    }
  }
}
