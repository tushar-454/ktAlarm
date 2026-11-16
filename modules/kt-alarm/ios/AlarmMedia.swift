import AVFoundation
import ExpoModulesCore
import ImageIO
import UniformTypeIdentifiers
import UIKit

enum AlarmMedia {
  static let tones = ["chime", "sunrise", "bell"]
  static let sleep = ["white", "pink", "brown", "rain", "ocean", "fan"]
  static let backgrounds = ["dawn", "forest", "midnight", "sea"]

  static var directory: URL {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("KtAlarmMedia", isDirectory: true)
  }
  static var sounds: URL {
    FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("Sounds", isDirectory: true)
  }

  static func presetURL(_ key: String) throws -> URL {
    let prefix = tones.contains(key) ? "kt_tone_" : "kt_sleep_"
    let host = Bundle(for: KtAlarmModule.self)
    let bundles = [host, Bundle.main].compactMap {
      $0.url(forResource: "KtAlarmMedia", withExtension: "bundle").flatMap(Bundle.init(url:))
    } + [host, Bundle.main]
    guard let url = bundles.compactMap({ $0.url(forResource: prefix + key, withExtension: "wav") }).first else {
      throw alarmError("ERR_MEDIA_IMPORT", "The bundled sound is missing. Rebuild the iOS app.")
    }
    return url
  }

  static func checked(_ uri: String?, kind: String) throws -> String? {
    guard let uri, !uri.isEmpty else { return nil }
    if uri.hasPrefix("preset:") {
      let key = String(uri.dropFirst(7))
      let choices = kind == "image" ? backgrounds : kind == "sleep" ? sleep : tones
      guard choices.contains(key) else { throw alarmError("ERR_MEDIA_IMPORT", "Unknown media preset.") }
      return uri
    }
    return try importedURL(uri).absoluteString
  }

  static func url(_ uri: String) throws -> URL {
    if uri.hasPrefix("preset:") { return try presetURL(String(uri.dropFirst(7))) }
    return try importedURL(uri)
  }

  /// iOS may relocate an app's data container after an update or restore. Resolve an imported
  /// UUID filename against today's container instead of depending on the old absolute prefix.
  private static func importedURL(_ uri: String) throws -> URL {
    guard let source = URL(string: uri), source.isFileURL,
      source.deletingLastPathComponent().lastPathComponent == directory.lastPathComponent,
      UUID(uuidString: source.deletingPathExtension().lastPathComponent) != nil else {
      throw alarmError("ERR_MEDIA_IMPORT", "Choose a file from your phone again; this file is unavailable.")
    }
    let current = directory.appendingPathComponent(source.lastPathComponent)
    guard FileManager.default.fileExists(atPath: current.path) else {
      throw alarmError("ERR_MEDIA_IMPORT", "Choose a file from your phone again; this file is unavailable.")
    }
    return current
  }

  static func currentURI(_ uri: String?) -> String? {
    guard let uri, !uri.hasPrefix("preset:") else { return uri }
    return (try? importedURL(uri).absoluteString) ?? uri
  }

  /// AlarmKit resolves custom sounds only in the main bundle or Library/Sounds.
  /// Convert imported music to a short PCM CAF alarm sound before scheduling it.
  static func prepareRingtone(_ uri: String?) async throws -> String? {
    guard let uri else { return nil }
    try FileManager.default.createDirectory(at: sounds, withIntermediateDirectories: true)
    if uri.hasPrefix("preset:") {
      let source = try url(uri)
      let target = sounds.appendingPathComponent(source.lastPathComponent)
      if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: source, to: target) }
      return target.lastPathComponent
    }
    let source = try url(uri)
    let target = sounds.appendingPathComponent(source.deletingPathExtension().lastPathComponent + ".caf")
    if FileManager.default.fileExists(atPath: target.path) { return target.lastPathComponent }
    // AVAssetReader accepts common imported formats (including mp3/m4a), unlike AVAudioFile.
    let asset = AVURLAsset(url: source)
    guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
      throw alarmError("ERR_MEDIA_IMPORT", "This file has no playable audio track.")
    }
    let reader = try AVAssetReader(asset: asset)
    reader.timeRange = CMTimeRange(start: .zero, duration: CMTime(seconds: 29, preferredTimescale: 600))
    let settings: [String: Any] = [AVFormatIDKey: kAudioFormatLinearPCM,
      AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16,
      AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false,
      AVLinearPCMIsNonInterleaved: false]
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
    reader.add(output)
    let writer = try AVAssetWriter(outputURL: target, fileType: .caf)
    let input = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
    writer.add(input)
    guard writer.startWriting(), reader.startReading() else {
      try? FileManager.default.removeItem(at: target)
      throw alarmError("ERR_MEDIA_IMPORT", "Could not decode the selected ringtone.")
    }
    writer.startSession(atSourceTime: .zero)
    do {
      while let buffer = output.copyNextSampleBuffer() {
        while !input.isReadyForMoreMediaData {
          guard writer.status == .writing else { throw writer.error ?? alarmError("ERR_MEDIA_IMPORT", "Audio conversion failed.") }
          try await Task.sleep(nanoseconds: 5_000_000)
        }
        guard input.append(buffer) else { throw writer.error ?? alarmError("ERR_MEDIA_IMPORT", "Audio conversion failed.") }
      }
      guard reader.status == .completed else { throw reader.error ?? alarmError("ERR_MEDIA_IMPORT", "Audio conversion failed.") }
      input.markAsFinished()
      await writer.finishWriting()
      guard writer.status == .completed else { throw writer.error ?? alarmError("ERR_MEDIA_IMPORT", "Audio conversion failed.") }
      return target.lastPathComponent
    } catch {
      reader.cancelReading()
      writer.cancelWriting()
      try? FileManager.default.removeItem(at: target)
      throw error
    }
  }
}

@MainActor
final class AlarmMediaPicker: NSObject, UIDocumentPickerDelegate {
  static let shared = AlarmMediaPicker()
  private var pending: Promise?
  private var kind = "audio"

  func pick(_ kind: String, presenter: UIViewController?, promise: Promise) {
    guard kind == "audio" || kind == "image" else {
      promise.reject(alarmError("ERR_MEDIA_IMPORT", "Unsupported media type.")); return
    }
    guard pending == nil, let presenter, presenter.view.window != nil else {
      promise.reject(alarmError("ERR_MEDIA_PICKER", "Another picker is open, or the app is not in the foreground.")); return
    }
    self.kind = kind
    pending = promise
    let picker = UIDocumentPickerViewController(forOpeningContentTypes: [kind == "audio" ? .audio : .image], asCopy: true)
    picker.delegate = self
    picker.allowsMultipleSelection = false
    presenter.present(picker, animated: true)
  }

  func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
    pending?.resolve(nil as [String: String]?)
    pending = nil
  }

  func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
    guard let promise = pending else { return }
    pending = nil
    let kind = self.kind
    // File providers may download from iCloud. Copy and validate away from the UI thread.
    DispatchQueue.global(qos: .userInitiated).async {
      do {
        guard let source = urls.first else { promise.resolve(nil as [String: String]?); return }
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= 100 * 1024 * 1024 else {
          throw alarmError("ERR_MEDIA_IMPORT", "Choose a nonempty file smaller than 100 MB.")
        }
        var imageData: Data?
        if kind == "image" {
          guard let imageSource = CGImageSourceCreateWithURL(source as CFURL, nil),
            let thumbnail = CGImageSourceCreateThumbnailAtIndex(imageSource, 0, [
              kCGImageSourceCreateThumbnailFromImageAlways: true,
              kCGImageSourceCreateThumbnailWithTransform: true,
              kCGImageSourceThumbnailMaxPixelSize: 2048] as CFDictionary),
            let data = UIImage(cgImage: thumbnail).jpegData(compressionQuality: 0.85) else {
            throw alarmError("ERR_MEDIA_IMPORT", "This image format is not supported.")
          }
          imageData = data
        }
        if kind == "audio" {
          let player = try AVAudioPlayer(contentsOf: source)
          guard player.duration > 0 else { throw alarmError("ERR_MEDIA_IMPORT", "This audio file is empty.") }
        }
        try FileManager.default.createDirectory(at: AlarmMedia.directory, withIntermediateDirectories: true)
        let target = AlarmMedia.directory.appendingPathComponent(UUID().uuidString)
          .appendingPathExtension(kind == "image" ? "jpg" : source.pathExtension)
        if let imageData { try imageData.write(to: target, options: .atomic) }
        else { try FileManager.default.copyItem(at: source, to: target) }
        promise.resolve(["uri": target.absoluteString, "name": String(source.lastPathComponent.prefix(120))])
      } catch { promise.reject(alarmError("ERR_MEDIA_IMPORT", error.localizedDescription)) }
    }
  }
}
