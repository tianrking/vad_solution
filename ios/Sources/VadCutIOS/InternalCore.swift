import Foundation

struct TimeRangeUs: Equatable, Sendable {
    let start: Int64
    let end: Int64

    var duration: Int64 { max(0, end - start) }
}

protocol ActivityDetector: AnyObject {
    func score(samples: [Float], validSampleCount: Int) throws -> Float
}

final class EnergyActivityDetector: ActivityDetector {
    private let threshold: Float

    init(thresholdDecibels: Float) {
        threshold = thresholdDecibels
    }

    func score(samples: [Float], validSampleCount: Int) throws -> Float {
        guard validSampleCount > 0 else { return 0 }
        let sum = samples.prefix(validSampleCount).reduce(0.0) { partial, value in
            partial + Double(value * value)
        }
        let rms = sqrt(sum / Double(validSampleCount))
        let decibels = 20.0 * log10(max(rms, 1e-9))
        return decibels >= Double(threshold) ? 1 : 0
    }
}

final class ActivitySegmentPlanner {
    private let config: TrimConfig
    private var rawRanges: [TimeRangeUs] = []
    private var active = false
    private var segmentStart: Int64 = 0
    private var lastActivityEnd: Int64 = 0

    init(config: TrimConfig) {
        self.config = config
    }

    func accept(score: Float, frameStart: Int64, frameEnd: Int64) {
        if !active {
            if score >= config.speechStartThreshold {
                active = true
                segmentStart = frameStart
                lastActivityEnd = frameEnd
            }
            return
        }

        if score >= config.speechEndThreshold {
            lastActivityEnd = frameEnd
            return
        }

        let quietDuration = frameEnd - lastActivityEnd
        if quietDuration >= config.minimumSilenceDurationMilliseconds * 1_000 {
            commitCurrentRange()
            active = false
        }
    }

    func finish(duration: Int64) -> [TimeRangeUs] {
        if active {
            commitCurrentRange()
            active = false
        }
        guard duration > 0, !rawRanges.isEmpty else { return [] }

        let before = config.paddingBeforeMilliseconds * 1_000
        let after = config.paddingAfterMilliseconds * 1_000
        let padded = rawRanges.compactMap { range -> TimeRangeUs? in
            let start = max(0, range.start - before)
            let end = min(duration, range.end + after)
            return end > start ? TimeRangeUs(start: start, end: end) : nil
        }

        var merged: [TimeRangeUs] = []
        for range in padded {
            guard let previous = merged.last else {
                merged.append(range)
                continue
            }
            if range.start > previous.end {
                merged.append(range)
            } else {
                merged[merged.count - 1] = TimeRangeUs(
                    start: previous.start,
                    end: max(previous.end, range.end)
                )
            }
        }
        return merged
    }

    private func commitCurrentRange() {
        let minimum = config.minimumSpeechDurationMilliseconds * 1_000
        if lastActivityEnd - segmentStart >= minimum {
            rawRanges.append(TimeRangeUs(start: segmentStart, end: lastActivityEnd))
        }
    }
}

final class StreamingMonoResampler {
    private let step: Double
    private let lowPassAlpha: Double
    private let output: (Float) -> Void

    private var inputIndex: Int64 = -1
    private var nextOutputPosition = 0.0
    private var previous: Float = 0
    private var filterOne = 0.0
    private var filterTwo = 0.0
    private var initialized = false

    init(inputSampleRate: Int, outputSampleRate: Int, output: @escaping (Float) -> Void) {
        step = Double(inputSampleRate) / Double(outputSampleRate)
        if inputSampleRate > outputSampleRate {
            let cutoff = Double(outputSampleRate) * 0.45
            lowPassAlpha = 1.0 - exp(-2.0 * .pi * cutoff / Double(inputSampleRate))
        } else {
            lowPassAlpha = 1
        }
        self.output = output
    }

    func accept(_ sample: Float) {
        let filtered = filter(min(1, max(-1, sample)))
        inputIndex += 1
        if !initialized {
            initialized = true
            previous = filtered
            output(filtered)
            nextOutputPosition += step
            return
        }

        let previousPosition = Double(inputIndex) - 1
        while nextOutputPosition <= Double(inputIndex) {
            let fraction = min(1, max(0, nextOutputPosition - previousPosition))
            let interpolated = previous + (filtered - previous) * Float(fraction)
            output(min(1, max(-1, interpolated)))
            nextOutputPosition += step
        }
        previous = filtered
    }

    private func filter(_ sample: Float) -> Float {
        guard lowPassAlpha < 1 else { return sample }
        if !initialized {
            filterOne = Double(sample)
            filterTwo = Double(sample)
        } else {
            filterOne += lowPassAlpha * (Double(sample) - filterOne)
            filterTwo += lowPassAlpha * (filterOne - filterTwo)
        }
        return Float(filterTwo)
    }
}

final class VadFrameCollector {
    static let sampleRate = 16_000
    static let frameSampleCount = 512

    private let detector: ActivityDetector
    private let planner: ActivitySegmentPlanner
    private var frame = Array(repeating: Float(0), count: frameSampleCount)
    private var frameSampleCount = 0
    private(set) var totalOutputSamples: Int64 = 0

    init(detector: ActivityDetector, planner: ActivitySegmentPlanner) {
        self.detector = detector
        self.planner = planner
    }

    var outputDuration: Int64 {
        totalOutputSamples * 1_000_000 / Int64(Self.sampleRate)
    }

    func accept(_ sample: Float) throws {
        frame[frameSampleCount] = sample
        frameSampleCount += 1
        totalOutputSamples += 1
        if frameSampleCount == frame.count {
            try analyzeFrame(validSamples: frame.count)
            frameSampleCount = 0
        }
    }

    func finish(duration: Int64) throws -> [TimeRangeUs] {
        if frameSampleCount > 0 {
            for index in frameSampleCount..<frame.count { frame[index] = 0 }
            try analyzeFrame(validSamples: frameSampleCount)
            frameSampleCount = 0
        }
        return planner.finish(duration: duration)
    }

    private func analyzeFrame(validSamples: Int) throws {
        let end = outputDuration
        let start = max(
            0,
            (totalOutputSamples - Int64(validSamples)) * 1_000_000 / Int64(Self.sampleRate)
        )
        planner.accept(
            score: try detector.score(samples: frame, validSampleCount: validSamples),
            frameStart: start,
            frameEnd: end
        )
    }
}

func complement(_ ranges: [TimeRangeUs], duration: Int64) -> [TimeRangeUs] {
    var result: [TimeRangeUs] = []
    var cursor: Int64 = 0
    for range in ranges {
        if range.start > cursor {
            result.append(TimeRangeUs(start: cursor, end: range.start))
        }
        cursor = max(cursor, range.end)
    }
    if cursor < duration {
        result.append(TimeRangeUs(start: cursor, end: duration))
    }
    return result
}

func validateConfig(_ config: TrimConfig) throws {
    guard (0...1).contains(config.speechStartThreshold),
          (0...1).contains(config.speechEndThreshold),
          config.speechEndThreshold <= config.speechStartThreshold,
          config.minimumSpeechDurationMilliseconds >= 0,
          config.minimumSilenceDurationMilliseconds >= 0,
          config.paddingBeforeMilliseconds >= 0,
          config.paddingAfterMilliseconds >= 0,
          config.fadeDurationMilliseconds >= 0,
          (-96...0).contains(config.energyThresholdDecibels) else {
        throw TrimError(code: .invalidRequest, message: "Invalid trim configuration")
    }
}
