import Foundation
import VadCutIOS

func trimRecording(inputURL: URL, outputURL: URL) async throws -> TrimResult {
    try await VadCut.trim(
        TrimRequest(inputURL: inputURL, outputURL: outputURL, config: .preset(.voiceMemo))
    ) { progress in
        print("\(progress.phase.rawValue): \(progress.percent)%")
    }
}

func removeKnownRanges(inputURL: URL, outputURL: URL) async throws -> TrimResult {
    let manualPlan = ManualTrimPlan.removeRanges([
        AudioRange(startMilliseconds: 10_000, endMilliseconds: 15_000),
        AudioRange(startMilliseconds: 42_000, endMilliseconds: 44_500),
    ])
    return try await VadCut.trim(
        TrimRequest(
            inputURL: inputURL,
            outputURL: outputURL,
            manualTrimPlan: manualPlan
        )
    )
}

func keepKnownRanges(inputURL: URL, outputURL: URL) async throws -> TrimResult {
    try await VadCut.trim(
        TrimRequest(
            inputURL: inputURL,
            outputURL: outputURL,
            manualTrimPlan: .keepRanges([
                AudioRange(startMilliseconds: 0, endMilliseconds: 8_000),
                AudioRange(startMilliseconds: 12_000, endMilliseconds: 20_000),
            ])
        )
    )
}
