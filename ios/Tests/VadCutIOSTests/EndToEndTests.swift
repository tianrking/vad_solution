import AVFoundation
import XCTest
@testable import VadCutIOS

final class EndToEndTests: XCTestCase {
    func testSileroTrimsRealWavToPlayableM4A() async throws {
        let fixture = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "vad-smoke", withExtension: "wav")
        )
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("vadcut-ios-e2e-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: output) }

        let result = try await VadCut.trim(
            TrimRequest(inputURL: fixture, outputURL: output, config: .preset(.voiceMemo))
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: output.path))
        XCTAssertGreaterThan(try output.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0, 1_000)
        XCTAssertGreaterThan(result.outputDurationMilliseconds, 500)
        XCTAssertLessThan(result.outputDurationMilliseconds, result.inputDurationMilliseconds)
        XCTAssertFalse(result.keptRanges.isEmpty)
        XCTAssertFalse(result.removedRanges.isEmpty)

        let outputAsset = AVURLAsset(url: output)
        let tracks = try await outputAsset.loadTracks(withMediaType: .audio)
        let duration = try await outputAsset.load(.duration)
        XCTAssertEqual(tracks.count, 1)
        XCTAssertGreaterThan(duration.seconds, 0.5)
    }

    func testEnergyModeKeepsOriginalWhenFixtureHasNoActivityAtImpossibleThreshold() async throws {
        let fixture = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "vad-smoke", withExtension: "wav")
        )
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("vadcut-ios-energy-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: output) }

        var config = TrimConfig.preset(.voiceMemo)
        config.mode = .nonSilence
        config.energyThresholdDecibels = 0
        let result = try await VadCut.trim(
            TrimRequest(inputURL: fixture, outputURL: output, config: config)
        )

        XCTAssertTrue(result.warnings.contains(.noActivityDetectedKeptOriginal))
        XCTAssertEqual(result.keptRanges.count, 1)
        XCTAssertGreaterThan(result.outputDurationMilliseconds, 10_000)
    }
}
