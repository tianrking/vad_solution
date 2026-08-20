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

public enum ManualTrimMode: String, Sendable {
    /// Delete the supplied ranges and keep their complement.
    case removeRanges

    /// Keep only the supplied ranges and delete their complement.
    case keepRanges
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

/// A manual edit plan whose half-open ranges use the original input timeline in milliseconds.
///
/// Ranges may be supplied in any order and may overlap or touch. VadCut validates, sorts, and
/// merges them after decoding the exact input duration. Detector parameters are ignored while a
/// manual plan is present; `TrimConfig.fadeDurationMilliseconds` still applies during export.
public struct ManualTrimPlan: Sendable {
    public let mode: ManualTrimMode
    public let ranges: [AudioRange]

    public init(mode: ManualTrimMode, ranges: [AudioRange]) {
        self.mode = mode
        self.ranges = ranges
    }

    public static func removeRanges(_ ranges: [AudioRange]) -> ManualTrimPlan {
        return ManualTrimPlan(mode: .removeRanges, ranges: ranges)
    }

    public static func keepRanges(_ ranges: [AudioRange]) -> ManualTrimPlan {
        return ManualTrimPlan(mode: .keepRanges, ranges: ranges)
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
    public let manualTrimPlan: ManualTrimPlan?

    public init(inputURL: URL, outputURL: URL, config: TrimConfig = .preset(.voiceMemo)) {
        self.inputURL = inputURL
        self.outputURL = outputURL
        self.config = config
        self.manualTrimPlan = nil
    }

    public init(
        inputURL: URL,
        outputURL: URL,
        config: TrimConfig = .preset(.voiceMemo),
        manualTrimPlan: ManualTrimPlan
    ) {
        self.inputURL = inputURL
        self.outputURL = outputURL
        self.config = config
        self.manualTrimPlan = manualTrimPlan
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

    /// Stable numeric value used by the Objective-C `NSError.code` bridge.
    public var numericValue: Int {
        switch self {
        case .invalidRequest: return 1
        case .invalidTimeRanges: return 2
        case .inputOpenFailed: return 3
        case .noAudioTrack: return 4
        case .unsupportedAudioFormat: return 5
        case .modelLoadFailed: return 6
        case .modelIntegrityFailed: return 7
        case .analysisFailed: return 8
        case .noSpeechDetected: return 9
        case .exportFailed: return 10
        case .outputWriteFailed: return 11
        case .cancelled: return 12
        }
    }
}

public struct TrimError: LocalizedError, CustomNSError, Sendable {
    public static let errorDomain = "com.vadcut.ios"
    public static let codeUserInfoKey = "VadCutErrorCode"
    public static let underlyingDescriptionUserInfoKey = "VadCutUnderlyingErrorDescription"

    public let code: TrimErrorCode
    public let message: String
    public let underlyingDescription: String?

    public init(code: TrimErrorCode, message: String, underlying: Error? = nil) {
        self.code = code
        self.message = message
        self.underlyingDescription = underlying.map(String.init(describing:))
    }

    public var errorDescription: String? { message }
    public var errorCode: Int { code.numericValue }
    public var errorUserInfo: [String: Any] {
        var values: [String: Any] = [
            NSLocalizedDescriptionKey: message,
            Self.codeUserInfoKey: code.rawValue,
        ]
        if let underlyingDescription {
            values[Self.underlyingDescriptionUserInfoKey] = underlyingDescription
        }
        return values
    }
}
