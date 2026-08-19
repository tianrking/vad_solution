import Foundation

@objc public enum VDTrimMode: Int {
    case speech
    case nonSilence
}

@objc public enum VDNoSpeechPolicy: Int {
    case keepOriginal
    case fail
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

    fileprivate init(_ result: TrimResult) {
        outputURL = result.outputURL
        inputDurationMilliseconds = result.inputDurationMilliseconds
        outputDurationMilliseconds = result.outputDurationMilliseconds
        removedDurationMilliseconds = result.removedDurationMilliseconds
    }
}

@objcMembers
public final class VadCutObjC: NSObject {
    @discardableResult
    public static func trim(
        inputURL: URL,
        outputURL: URL,
        configuration: VDTrimConfiguration = VDTrimConfiguration(),
        progress: ((Int, String) -> Void)? = nil,
        completion: @escaping (VDTrimResult?, NSError?) -> Void
    ) -> TrimTask {
        VadCut.start(
            TrimRequest(inputURL: inputURL, outputURL: outputURL, config: configuration.swiftValue),
            onProgress: { update in
                progress?(update.percent, update.phase.rawValue)
            },
            completion: { result in
                switch result {
                case .success(let value):
                    completion(VDTrimResult(value), nil)
                case .failure(let error):
                    completion(nil, error as NSError)
                }
            }
        )
    }
}
