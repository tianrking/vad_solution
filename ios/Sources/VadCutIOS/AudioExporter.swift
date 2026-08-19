import AVFoundation
import CoreMedia
import Foundation

final class AudioExporter {
    func export(
        inputURL: URL,
        temporaryOutputURL: URL,
        keptRanges: [TimeRangeUs],
        fadeDurationMicroseconds: Int64,
        onProgress: @escaping (Int) -> Void
    ) async throws {
        let asset = AVURLAsset(url: inputURL)
        let tracks: [AVAssetTrack]
        do {
            tracks = try await asset.loadTracks(withMediaType: .audio)
        } catch {
            throw TrimError(code: .inputOpenFailed, message: "Unable to load audio for export", underlying: error)
        }
        guard let sourceTrack = tracks.first else {
            throw TrimError(code: .noAudioTrack, message: "The input contains no audio track")
        }

        let composition = AVMutableComposition()
        guard let compositionTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw TrimError(code: .exportFailed, message: "Unable to create the output audio track")
        }

        let audioParameters = AVMutableAudioMixInputParameters(track: compositionTrack)
        var outputCursor = CMTime.zero
        do {
            for range in keptRanges where range.end > range.start {
                try Task.checkCancellation()
                let sourceRange = CMTimeRange(
                    start: CMTime(value: range.start, timescale: 1_000_000),
                    duration: CMTime(value: range.duration, timescale: 1_000_000)
                )
                try compositionTrack.insertTimeRange(sourceRange, of: sourceTrack, at: outputCursor)

                let fade = min(fadeDurationMicroseconds, range.duration / 2)
                if fade > 0 {
                    let fadeTime = CMTime(value: fade, timescale: 1_000_000)
                    audioParameters.setVolumeRamp(
                        fromStartVolume: 0,
                        toEndVolume: 1,
                        timeRange: CMTimeRange(start: outputCursor, duration: fadeTime)
                    )
                    let fadeOutStart = CMTimeAdd(outputCursor, CMTimeSubtract(sourceRange.duration, fadeTime))
                    audioParameters.setVolumeRamp(
                        fromStartVolume: 1,
                        toEndVolume: 0,
                        timeRange: CMTimeRange(start: fadeOutStart, duration: fadeTime)
                    )
                }
                outputCursor = CMTimeAdd(outputCursor, sourceRange.duration)
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw TrimError(code: .exportFailed, message: "Unable to compose retained audio ranges", underlying: error)
        }
        guard outputCursor > .zero else {
            throw TrimError(code: .exportFailed, message: "No audio ranges are available for export")
        }

        try? FileManager.default.removeItem(at: temporaryOutputURL)
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            throw TrimError(code: .exportFailed, message: "AAC/M4A export is unavailable on this device")
        }
        guard exporter.supportedFileTypes.contains(.m4a) else {
            throw TrimError(code: .exportFailed, message: "The device does not support M4A export")
        }
        exporter.outputURL = temporaryOutputURL
        exporter.outputFileType = .m4a
        let audioMix = AVMutableAudioMix()
        audioMix.inputParameters = [audioParameters]
        exporter.audioMix = audioMix
        exporter.shouldOptimizeForNetworkUse = false

        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
                timer.schedule(deadline: .now(), repeating: .milliseconds(200))
                timer.setEventHandler {
                    onProgress(Int(exporter.progress * 100))
                }
                timer.resume()

                exporter.exportAsynchronously {
                    timer.cancel()
                    switch exporter.status {
                    case .completed:
                        onProgress(100)
                        continuation.resume()
                    case .cancelled:
                        continuation.resume(throwing: CancellationError())
                    default:
                        continuation.resume(
                            throwing: TrimError(
                                code: .exportFailed,
                                message: "AVFoundation M4A export failed",
                                underlying: exporter.error
                            )
                        )
                    }
                }
            }
        } onCancel: {
            exporter.cancelExport()
        }
    }
}
