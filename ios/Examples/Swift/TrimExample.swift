import Foundation
import VadCutIOS

func trimRecording(inputURL: URL, outputURL: URL) async throws -> TrimResult {
    try await VadCut.trim(
        TrimRequest(inputURL: inputURL, outputURL: outputURL, config: .preset(.voiceMemo))
    ) { progress in
        print("\(progress.phase.rawValue): \(progress.percent)%")
    }
}
