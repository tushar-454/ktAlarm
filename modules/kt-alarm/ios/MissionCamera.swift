import AVFoundation
import SwiftUI
import UIKit
import Vision

@MainActor
final class MissionCameraController: UIViewController {
  private let capture: MissionCameraCapture
  private var preview: AVCaptureVideoPreviewLayer?

  init(pose: Bool, onCode: (@MainActor (String, String) -> Void)? = nil,
       onPose: (@MainActor ([Double]) -> Void)? = nil, onError: @escaping @MainActor (String) -> Void) {
    capture = MissionCameraCapture(pose: pose, onCode: onCode, onPose: onPose, onError: onError)
    super.init(nibName: nil, bundle: nil)
  }
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .black
    let preview = AVCaptureVideoPreviewLayer(session: capture.session)
    preview.videoGravity = .resizeAspectFill
    view.layer.addSublayer(preview)
    self.preview = preview
    capture.start()
  }
  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    preview?.frame = view.bounds
    preview?.connection?.videoOrientation = .portrait
  }
  override func viewWillDisappear(_ animated: Bool) {
    super.viewWillDisappear(animated)
    capture.stop()
  }
  func stop() { capture.stop() }
  func setTorch(_ enabled: Bool) { capture.setTorch(enabled) }
}

/// Capture and Vision run on one serial worker queue, separate from UIKit's main actor.
/// Both scanner and pose detection run locally. No microphone, photos, or network access.
private final class MissionCameraCapture: NSObject, AVCaptureMetadataOutputObjectsDelegate, AVCaptureVideoDataOutputSampleBufferDelegate {
  let session = AVCaptureSession()
  private let captureQueue = DispatchQueue(label: "kt.alarm.camera", qos: .userInitiated)
  private let pose: Bool
  private let onCode: (@MainActor (String, String) -> Void)?
  private let onPose: (@MainActor ([Double]) -> Void)?
  private let onError: @MainActor (String) -> Void
  private var stopped = false
  private var lastFrame = 0.0
  private var torchRequested = false

  init(pose: Bool, onCode: (@MainActor (String, String) -> Void)? = nil,
       onPose: (@MainActor ([Double]) -> Void)? = nil, onError: @escaping @MainActor (String) -> Void) {
    self.pose = pose; self.onCode = onCode; self.onPose = onPose; self.onError = onError
    super.init()
  }
  func start() { captureQueue.async { self.configure() } }
  func stop() {
    captureQueue.async {
      self.stopped = true
      self.session.stopRunning()
    }
  }

  func setTorch(_ enabled: Bool) {
    captureQueue.async { [weak self] in
      guard let self else { return }
      self.torchRequested = enabled
      self.applyTorch()
    }
  }

  private func applyTorch() {
    guard !pose, let input = session.inputs.first as? AVCaptureDeviceInput, input.device.hasTorch else { return }
    do {
      try input.device.lockForConfiguration()
      defer { input.device.unlockForConfiguration() }
      input.device.torchMode = torchRequested ? .on : .off
    } catch { /* Torch may be unavailable while the camera is interrupted. Scanning still works. */ }
  }

  private func configure() {
    guard !stopped else { return }
    guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
      fail("Allow camera access in Settings to use this mission."); return
    }
    session.beginConfiguration()
    session.sessionPreset = .medium
    do {
      guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: pose ? .front : .back) else {
        throw alarmError("ERR_CAMERA_UNAVAILABLE", "No suitable camera is available on this device.")
      }
      let input = try AVCaptureDeviceInput(device: device)
      guard session.canAddInput(input) else { throw alarmError("ERR_CAMERA_UNAVAILABLE", "Could not open the camera.") }
      session.addInput(input)
      if pose {
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        guard session.canAddOutput(output) else { throw alarmError("ERR_CAMERA_UNAVAILABLE", "Pose detection is unavailable.") }
        session.addOutput(output)
        output.setSampleBufferDelegate(self, queue: captureQueue)
        output.connection(with: .video)?.videoOrientation = .portrait
      } else {
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { throw alarmError("ERR_CAMERA_UNAVAILABLE", "Barcode scanning is unavailable.") }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: captureQueue)
        let supported: [AVMetadataObject.ObjectType] = [.qr, .ean8, .ean13, .upce, .code39, .code93, .code128,
          .pdf417, .aztec, .dataMatrix, .interleaved2of5, .itf14]
        output.metadataObjectTypes = supported.filter { output.availableMetadataObjectTypes.contains($0) }
      }
      session.commitConfiguration()
      if !stopped { session.startRunning() }
      applyTorch()
    } catch {
      session.commitConfiguration()
      fail(error.localizedDescription)
    }
  }

  func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
    guard !stopped, ProcessInfo.processInfo.systemUptime - lastFrame > 0.5,
      let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first,
      let value = code.stringValue, !value.isEmpty else { return }
    // Keep scanning for mission validation; its callback decides whether a code matches.
    let format = code.type.rawValue
    lastFrame = ProcessInfo.processInfo.systemUptime
    DispatchQueue.main.async { [weak self] in self?.onCode?(value, format) }
  }

  func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
    let now = ProcessInfo.processInfo.systemUptime
    guard !stopped, now - lastFrame >= 0.1, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
    lastFrame = now
    let request = VNDetectHumanBodyPoseRequest()
    do {
      // The output connection already rotates pixel buffers to portrait.
      try VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up).perform([request])
      let points = try request.results?.first?.recognizedPoints(.all) ?? [:]
      let legs: [(VNHumanBodyPoseObservation.JointName, VNHumanBodyPoseObservation.JointName, VNHumanBodyPoseObservation.JointName)] = [
        (.leftHip, .leftKnee, .leftAnkle), (.rightHip, .rightKnee, .rightAnkle)]
      let ratios = legs.compactMap { hipKey, kneeKey, ankleKey -> Double? in
        guard let hip = points[hipKey], let knee = points[kneeKey], let ankle = points[ankleKey],
              min(hip.confidence, min(knee.confidence, ankle.confidence)) > 0.45 else { return nil }
        // Vision y grows upwards; Android's equivalent counter uses downward y.
        let shin = knee.location.y - ankle.location.y
        let length = hypot(knee.location.x - ankle.location.x, shin)
        guard shin >= 0.04, shin >= 0.5 * length else { return nil }
        return Double((hip.location.y - knee.location.y) / shin)
      }
      DispatchQueue.main.async { [weak self] in self?.onPose?(ratios) }
    } catch {
      DispatchQueue.main.async { [weak self] in self?.onPose?([]) }
    }
  }

  private func fail(_ message: String) {
    DispatchQueue.main.async { [weak self] in self?.onError(message) }
  }
}

struct MissionCameraView: View {
  let pose: Bool
  var onCode: (@MainActor (String, String) -> Void)?
  var onPose: (@MainActor ([Double]) -> Void)?
  let onError: @MainActor (String) -> Void
  @State private var torch = false
  var body: some View {
    CameraControllerView(pose: pose, onCode: onCode, onPose: onPose, onError: onError, torch: torch)
      .overlay(alignment: .bottomTrailing) {
        if !pose {
          Button(torch ? "Flashlight off" : "Flashlight") { torch.toggle() }
            .buttonStyle(.borderedProminent).tint(.orange).padding()
        }
      }
  }
}

private struct CameraControllerView: UIViewControllerRepresentable {
  let pose: Bool
  var onCode: (@MainActor (String, String) -> Void)?
  var onPose: (@MainActor ([Double]) -> Void)?
  let onError: @MainActor (String) -> Void
  let torch: Bool
  func makeUIViewController(context: Context) -> MissionCameraController {
    MissionCameraController(pose: pose, onCode: onCode, onPose: onPose, onError: onError)
  }
  func updateUIViewController(_ controller: MissionCameraController, context: Context) { controller.setTorch(torch) }
  static func dismantleUIViewController(_ controller: MissionCameraController, coordinator: ()) { controller.stop() }
}

@MainActor
final class BarcodeRegistration {
  static let shared = BarcodeRegistration()
  private var controller: UIViewController?
  private var pending: ((Result<[String: String]?, Error>) -> Void)?

  func scan(presenter: UIViewController?, completion: @escaping (Result<[String: String]?, Error>) -> Void) async {
    guard pending == nil, let presenter, presenter.view.window != nil else {
      completion(.failure(alarmError("ERR_NO_ACTIVITY", "Close the current scanner and try again."))); return
    }
    guard await AVCaptureDevice.requestAccess(for: .video) else {
      completion(.failure(alarmError("ERR_CAMERA_PERMISSION", "Allow camera access in Settings to register a code."))); return
    }
    // Another call may have arrived while authorization was displayed.
    guard pending == nil else {
      completion(.failure(alarmError("ERR_NO_ACTIVITY", "A scanner is already open."))); return
    }
    pending = completion
    let root = UIHostingController(rootView: BarcodeRegistrationView { [weak self] result in self?.finish(result) })
    root.modalPresentationStyle = .fullScreen
    controller = root
    presenter.present(root, animated: true)
  }

  private func finish(_ result: Result<[String: String]?, Error>) {
    guard let pending else { return }
    self.pending = nil
    controller?.dismiss(animated: true) { pending(result) }
    controller = nil
  }
}

private struct BarcodeRegistrationView: View {
  let completion: (Result<[String: String]?, Error>) -> Void
  @State private var finished = false
  var body: some View {
    VStack(spacing: 20) {
      HStack {
        Button("Cancel") { complete(.success(nil)) }
        Spacer()
        Text("Register code").font(.headline)
      }
      Text("Point the rear camera at a barcode or QR code.")
      MissionCameraView(pose: false, onCode: { value, format in
        complete(.success(["value": value, "format": format]))
      }, onError: { complete(.failure(alarmError("ERR_CAMERA_UNAVAILABLE", $0))) })
      .clipShape(RoundedRectangle(cornerRadius: 20))
    }
    .padding().background(Color.black).foregroundStyle(.white).tint(.orange)
  }
  private func complete(_ result: Result<[String: String]?, Error>) {
    guard !finished else { return }
    finished = true
    completion(result)
  }
}
