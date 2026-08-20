import AVFoundation
import CoreMedia
import Foundation

struct AnalysisResult {
    let duration: Int64
    let keptRanges: [TimeRangeUs]
    let durationWasKnown: Bool
}

final class AudioAnalyzer {
    func analyze(
        inputURL: URL,
        config: TrimConfig,
        onProgress: (Int64, Int64) -> Void
    ) async throws -> AnalysisResult {
        return try await decode(inputURL: inputURL, config: config, onProgress: onProgress)
    }

    /// Decodes the stream to obtain an exact duration without loading an activity detector.
    func analyzeDuration(
        inputURL: URL,
        onProgress: (Int64, Int64) -> Void
    ) async throws -> AnalysisResult {
        return try await decode(inputURL: inputURL, config: nil, onProgress: onProgress)
    }

    private func decode(
        inputURL: URL,
        config: TrimConfig?,
        onProgress: (Int64, Int64) -> Void
    ) async throws -> AnalysisResult {
        let asset = AVURLAsset(url: inputURL)
        let durationTime: CMTime
        let audioTracks: [AVAssetTrack]
        do {
            durationTime = try await asset.load(.duration)
            audioTracks = try await asset.loadTracks(withMediaType: .audio)
        } catch {
            throw TrimError(
                code: .inputOpenFailed,
                message: "Unable to load the input audio asset",
                underlying: error
            )
        }
        guard let audioTrack = audioTracks.first else {
            throw TrimError(code: .noAudioTrack, message: "The input contains no audio track")
        }

        let declaredDuration = durationTime.isNumeric && durationTime.seconds.isFinite && durationTime.seconds > 0
            ? Int64(durationTime.seconds * 1_000_000)
            : 0
        let detector: ActivityDetector?
        if let config {
            switch config.mode {
            case .speech:
                detector = try SileroVadEngine(verifyIntegrity: config.verifyModelIntegrity)
            case .nonSilence:
                detector = EnergyActivityDetector(thresholdDecibels: config.energyThresholdDecibels)
            }
        } else {
            detector = nil
        }
        let collector: VadFrameCollector?
        if let detector, let config {
            collector = VadFrameCollector(
                detector: detector,
                planner: ActivitySegmentPlanner(config: config)
            )
        } else {
            collector = nil
        }

        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            throw TrimError(code: .analysisFailed, message: "Unable to create AVAssetReader", underlying: error)
        }
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: VadFrameCollector.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let output = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: outputSettings)
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else {
            throw TrimError(code: .unsupportedAudioFormat, message: "AVFoundation cannot decode this audio track")
        }
        reader.add(output)
        guard reader.startReading() else {
            throw TrimError(
                code: .analysisFailed,
                message: "AVAssetReader failed to start",
                underlying: reader.error
            )
        }

        var lastProgressTime = Date.distantPast
        var validatedFormat = false
        var decodedOutputSamples: Int64 = 0
        do {
            while let sampleBuffer = output.copyNextSampleBuffer() {
                try Task.checkCancellation()
                if !validatedFormat {
                    try validatePCMFormat(sampleBuffer)
                    validatedFormat = true
                }
                guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
                    throw TrimError(code: .analysisFailed, message: "Decoder returned audio without PCM data")
                }
                let byteCount = CMBlockBufferGetDataLength(dataBuffer)
                guard byteCount % MemoryLayout<Float>.stride == 0 else {
                    throw TrimError(code: .unsupportedAudioFormat, message: "Decoder returned unaligned Float32 PCM")
                }
                let sampleCount = byteCount / MemoryLayout<Float>.stride
                decodedOutputSamples += Int64(sampleCount)
                if let collector {
                    var samples = Array(repeating: Float(0), count: sampleCount)
                    let copyStatus = samples.withUnsafeMutableBytes { bytes in
                        CMBlockBufferCopyDataBytes(
                            dataBuffer,
                            atOffset: 0,
                            dataLength: byteCount,
                            destination: bytes.baseAddress!
                        )
                    }
                    guard copyStatus == kCMBlockBufferNoErr else {
                        throw TrimError(code: .analysisFailed, message: "Unable to copy decoded PCM data")
                    }
                    for sample in samples {
                        try collector.accept(sample)
                    }
                }

                let now = Date()
                if now.timeIntervalSince(lastProgressTime) >= 0.1 {
                    lastProgressTime = now
                    let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
                    let processed = timestamp.isNumeric && timestamp.seconds.isFinite
                        ? max(0, Int64(timestamp.seconds * 1_000_000))
                        : decodedOutputSamples * 1_000_000 / Int64(VadFrameCollector.sampleRate)
                    onProgress(processed, declaredDuration)
                }
            }
        } catch is CancellationError {
            reader.cancelReading()
            throw CancellationError()
        } catch let error as TrimError {
            reader.cancelReading()
            throw error
        } catch {
            reader.cancelReading()
            throw TrimError(code: .analysisFailed, message: "Audio analysis failed", underlying: error)
        }

        switch reader.status {
        case .completed:
            break
        case .cancelled:
            throw CancellationError()
        default:
            throw TrimError(
                code: .analysisFailed,
                message: "AVAssetReader did not complete",
                underlying: reader.error
            )
        }
        guard validatedFormat, decodedOutputSamples > 0 else {
            throw TrimError(code: .unsupportedAudioFormat, message: "The decoder produced no PCM audio")
        }

        // The decoded PCM sample count is the editing timeline used by both automatic and manual
        // modes. Container metadata remains useful for progress, but may include encoder padding.
        let decodedDuration = decodedOutputSamples * 1_000_000 / Int64(VadFrameCollector.sampleRate)
        let duration = max(1, decodedDuration)
        onProgress(duration, declaredDuration)
        return AnalysisResult(
            duration: duration,
            keptRanges: try collector?.finish(duration: duration) ?? [],
            durationWasKnown: declaredDuration > 0
        )
    }

    private func validatePCMFormat(_ sampleBuffer: CMSampleBuffer) throws {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer),
              let description = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)?.pointee else {
            throw TrimError(code: .unsupportedAudioFormat, message: "Decoder did not report a PCM format")
        }
        let isFloat = description.mFormatFlags & kAudioFormatFlagIsFloat != 0
        guard description.mFormatID == kAudioFormatLinearPCM,
              isFloat,
              description.mChannelsPerFrame == 1,
              abs(description.mSampleRate - Double(VadFrameCollector.sampleRate)) < 0.5 else {
            throw TrimError(
                code: .unsupportedAudioFormat,
                message: "AVFoundation did not produce 16 kHz mono Float32 PCM"
            )
        }
    }
}
