import Foundation

@objc public enum VDTrimMode: Int {
    case speech
    case nonSilence
}

@objc public enum VDNoSpeechPolicy: Int {
    case keepOriginal
    case fail
}

@objc public enum VDManualTrimMode: Int {
    case removeRanges
    case keepRanges
}

/// Values returned through `NSError.code`. The domain is `com.vadcut.ios`.
@objc public enum VDTrimErrorCode: Int {
    case invalidRequest = 1
    case invalidTimeRanges
    case inputOpenFailed
    case noAudioTrack
    case unsupportedAudioFormat
    case modelLoadFailed
    case modelIntegrityFailed
    case analysisFailed
    case noSpeechDetected
    case exportFailed
    case outputWriteFailed
    case cancelled
}

@objcMembers
public final class VDAudioRange: NSObject {
    public let startMilliseconds: Int64
    public let endMilliseconds: Int64

    public init(startMilliseconds: Int64, endMilliseconds: Int64) {
        self.startMilliseconds = startMilliseconds
        self.endMilliseconds = endMilliseconds
    }

    public var durationMilliseconds: Int64 {
        max(0, endMilliseconds - startMilliseconds)
    }

    fileprivate init(_ range: AudioRange) {
        startMilliseconds = range.startMilliseconds
        endMilliseconds = range.endMilliseconds
    }

    fileprivate var swiftValue: AudioRange {
        AudioRange(
            startMilliseconds: startMilliseconds,
            endMilliseconds: endMilliseconds
        )
    }
}

@objcMembers
public final class VDManualTrimPlan: NSObject {
    public let mode: VDManualTrimMode
    public let ranges: [VDAudioRange]

    private init(mode: VDManualTrimMode, ranges: [VDAudioRange]) {
        self.mode = mode
        self.ranges = ranges
    }

    @objc(removeRanges:)
    public static func removeRanges(_ ranges: [VDAudioRange]) -> VDManualTrimPlan {
        return VDManualTrimPlan(mode: .removeRanges, ranges: ranges)
    }

    @objc(keepRanges:)
    public static func keepRanges(_ ranges: [VDAudioRange]) -> VDManualTrimPlan {
        return VDManualTrimPlan(mode: .keepRanges, ranges: ranges)
    }

    fileprivate var swiftValue: ManualTrimPlan {
        ManualTrimPlan(
            mode: mode == .removeRanges ? .removeRanges : .keepRanges,
            ranges: ranges.map(\.swiftValue)
        )
    }
}

@objcMembers
public final class VDTrimConfiguration: NSObject {
    public var mode: VDTrimMode = .speech
    public var speechStartThreshold: Float = 0.55
    public var speechEndThreshold: Float = 0.35
    public var minimumSpeechDurationMilliseconds: Int64 = 96
    public var minimumSilenceDurationMilliseconds: Int64 = 700
    public var paddingBeforeMilliseconds: Int64 = 180
    public var paddingAfterMilliseconds: Int64 = 250
    public var fadeDurationMilliseconds: Int64 = 8
    public var energyThresholdDecibels: Float = -45
    public var noSpeechPolicy: VDNoSpeechPolicy = .keepOriginal
    public var verifyModelIntegrity = true

    fileprivate var swiftValue: TrimConfig {
        TrimConfig(
            mode: mode == .speech ? .speech : .nonSilence,
            speechStartThreshold: speechStartThreshold,
            speechEndThreshold: speechEndThreshold,
            minimumSpeechDurationMilliseconds: minimumSpeechDurationMilliseconds,
            minimumSilenceDurationMilliseconds: minimumSilenceDurationMilliseconds,
            paddingBeforeMilliseconds: paddingBeforeMilliseconds,
            paddingAfterMilliseconds: paddingAfterMilliseconds,
            fadeDurationMilliseconds: fadeDurationMilliseconds,
            energyThresholdDecibels: energyThresholdDecibels,
            noSpeechPolicy: noSpeechPolicy == .keepOriginal ? .keepOriginal : .fail,
            verifyModelIntegrity: verifyModelIntegrity
        )
    }
}

@objcMembers
public final class VDTrimResult: NSObject {
    public let outputURL: URL
    public let inputDurationMilliseconds: Int64
    public let outputDurationMilliseconds: Int64
    public let removedDurationMilliseconds: Int64
    public let keptRanges: [VDAudioRange]
    public let removedRanges: [VDAudioRange]
    public let warnings: [String]

    fileprivate init(_ result: TrimResult) {
        outputURL = result.outputURL
        inputDurationMilliseconds = result.inputDurationMilliseconds
        outputDurationMilliseconds = result.outputDurationMilliseconds
        removedDurationMilliseconds = result.removedDurationMilliseconds
        keptRanges = result.keptRanges.map(VDAudioRange.init)
        removedRanges = result.removedRanges.map(VDAudioRange.init)
        warnings = result.warnings.map(\.rawValue)
    }
}

@objcMembers
public final class VadCutObjC: NSObject {
    @discardableResult
    @objc(trimWithInputURL:outputURL:configuration:progress:completion:)
    public static func trim(
        inputURL: URL,
        outputURL: URL,
        configuration: VDTrimConfiguration = VDTrimConfiguration(),
        progress: ((Int, String) -> Void)? = nil,
        completion: @escaping (VDTrimResult?, NSError?) -> Void
    ) -> TrimTask {
        return start(
            inputURL: inputURL,
            outputURL: outputURL,
            configuration: configuration,
            manualPlan: nil,
            progress: progress,
            completion: completion
        )
    }

    @discardableResult
    @objc(trimWithInputURL:outputURL:configuration:manualPlan:progress:completion:)
    public static func trim(
        inputURL: URL,
        outputURL: URL,
        configuration: VDTrimConfiguration = VDTrimConfiguration(),
        manualPlan: VDManualTrimPlan,
        progress: ((Int, String) -> Void)? = nil,
        completion: @escaping (VDTrimResult?, NSError?) -> Void
    ) -> TrimTask {
        return start(
            inputURL: inputURL,
            outputURL: outputURL,
            configuration: configuration,
            manualPlan: manualPlan,
            progress: progress,
            completion: completion
        )
    }

    private static func start(
        inputURL: URL,
        outputURL: URL,
        configuration: VDTrimConfiguration,
        manualPlan: VDManualTrimPlan?,
        progress: ((Int, String) -> Void)?,
        completion: @escaping (VDTrimResult?, NSError?) -> Void
    ) -> TrimTask {
        let request: TrimRequest
        if let manualPlan {
            request = TrimRequest(
                inputURL: inputURL,
                outputURL: outputURL,
                config: configuration.swiftValue,
                manualTrimPlan: manualPlan.swiftValue
            )
        } else {
            request = TrimRequest(
                inputURL: inputURL,
                outputURL: outputURL,
                config: configuration.swiftValue
            )
        }
        return VadCut.start(
            request,
            onProgress: { update in
                progress?(update.percent, update.phase.rawValue)
            },
            completion: { result in
                switch result {
                case .success(let value):
                    completion(VDTrimResult(value), nil)
                case .failure(let error):
                    if error is CancellationError {
                        completion(
                            nil,
                            TrimError(code: .cancelled, message: "The trim operation was cancelled") as NSError
                        )
                    } else {
                        completion(nil, error as NSError)
                    }
                }
            }
        )
    }
}
