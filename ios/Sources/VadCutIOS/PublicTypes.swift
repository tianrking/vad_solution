import Foundation

public enum TrimMode: String, Sendable {
    case speech
    case nonSilence
}

public enum TrimPreset: String, Sendable {
    case conservative
    case voiceMemo
    case aggressive
}

public enum NoSpeechPolicy: String, Sendable {
    case keepOriginal
    case fail
}

public enum TrimPhase: String, Sendable {
    case analyzing
    case exporting
    case writingOutput
    case completed
}

public enum TrimWarning: String, Sendable {
    case inputDurationUnknown
    case noActivityDetectedKeptOriginal
}

public struct AudioRange: Equatable, Sendable {
    public let startMilliseconds: Int64
    public let endMilliseconds: Int64

    public init(startMilliseconds: Int64, endMilliseconds: Int64) {
        self.startMilliseconds = startMilliseconds
        self.endMilliseconds = endMilliseconds
    }

    public var durationMilliseconds: Int64 {
        max(0, endMilliseconds - startMilliseconds)
    }
}

public struct TrimProgress: Sendable {
    public let phase: TrimPhase
    public let percent: Int
    public let processedDurationMilliseconds: Int64
    public let totalDurationMilliseconds: Int64

    public init(
        phase: TrimPhase,
        percent: Int,
        processedDurationMilliseconds: Int64,
        totalDurationMilliseconds: Int64
    ) {
        self.phase = phase
        self.percent = min(100, max(0, percent))
        self.processedDurationMilliseconds = processedDurationMilliseconds
        self.totalDurationMilliseconds = totalDurationMilliseconds
    }
}

public struct TrimConfig: Sendable {
    public var mode: TrimMode
    public var speechStartThreshold: Float
    public var speechEndThreshold: Float
    public var minimumSpeechDurationMilliseconds: Int64
    public var minimumSilenceDurationMilliseconds: Int64
    public var paddingBeforeMilliseconds: Int64
    public var paddingAfterMilliseconds: Int64
    public var fadeDurationMilliseconds: Int64
    public var energyThresholdDecibels: Float
    public var noSpeechPolicy: NoSpeechPolicy
    public var verifyModelIntegrity: Bool

    public init(
        mode: TrimMode = .speech,
        speechStartThreshold: Float = 0.55,
        speechEndThreshold: Float = 0.35,
        minimumSpeechDurationMilliseconds: Int64 = 96,
        minimumSilenceDurationMilliseconds: Int64 = 700,
        paddingBeforeMilliseconds: Int64 = 180,
        paddingAfterMilliseconds: Int64 = 250,
        fadeDurationMilliseconds: Int64 = 8,
        energyThresholdDecibels: Float = -45,
        noSpeechPolicy: NoSpeechPolicy = .keepOriginal,
        verifyModelIntegrity: Bool = true
    ) {
        self.mode = mode
        self.speechStartThreshold = speechStartThreshold
        self.speechEndThreshold = speechEndThreshold
        self.minimumSpeechDurationMilliseconds = minimumSpeechDurationMilliseconds
        self.minimumSilenceDurationMilliseconds = minimumSilenceDurationMilliseconds
        self.paddingBeforeMilliseconds = paddingBeforeMilliseconds
        self.paddingAfterMilliseconds = paddingAfterMilliseconds
        self.fadeDurationMilliseconds = fadeDurationMilliseconds
        self.energyThresholdDecibels = energyThresholdDecibels
        self.noSpeechPolicy = noSpeechPolicy
        self.verifyModelIntegrity = verifyModelIntegrity
    }

    public static func preset(_ preset: TrimPreset) -> TrimConfig {
        switch preset {
        case .conservative:
            return TrimConfig(
                minimumSilenceDurationMilliseconds: 1_200,
                paddingBeforeMilliseconds: 250,
                paddingAfterMilliseconds: 350
            )
        case .voiceMemo:
            return TrimConfig()
        case .aggressive:
            return TrimConfig(
                speechStartThreshold: 0.60,
                speechEndThreshold: 0.40,
                minimumSilenceDurationMilliseconds: 350,
                paddingBeforeMilliseconds: 100,
                paddingAfterMilliseconds: 140
            )
        }
    }
}

public struct TrimRequest: Sendable {
    public let inputURL: URL
    public let outputURL: URL
    public let config: TrimConfig

    public init(inputURL: URL, outputURL: URL, config: TrimConfig = .preset(.voiceMemo)) {
        self.inputURL = inputURL
        self.outputURL = outputURL
        self.config = config
    }
}

public struct TrimResult: Sendable {
    public let outputURL: URL
    public let inputDurationMilliseconds: Int64
    public let outputDurationMilliseconds: Int64
    public let removedDurationMilliseconds: Int64
    public let keptRanges: [AudioRange]
    public let removedRanges: [AudioRange]
    public let warnings: [TrimWarning]
}

public enum TrimErrorCode: String, Sendable {
    case invalidRequest
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

public struct TrimError: LocalizedError, Sendable {
    public let code: TrimErrorCode
    public let message: String
    public let underlyingDescription: String?

    public init(code: TrimErrorCode, message: String, underlying: Error? = nil) {
        self.code = code
        self.message = message
        self.underlyingDescription = underlying.map(String.init(describing:))
    }

    public var errorDescription: String? { message }
}
