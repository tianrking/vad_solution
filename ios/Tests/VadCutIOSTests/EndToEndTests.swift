import AVFoundation
import XCTest
@testable import VadCutIOS

final class EndToEndTests: XCTestCase {
    func testSileroTrimsRealWavToPlayableM4A() async throws {
        let fixture = try fixtureURL(named: "vad-smoke", extension: "wav")
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

        try await assertPlayableAudio(output, expectedDurationMilliseconds: result.outputDurationMilliseconds)
    }

    func testEnergyModeKeepsOriginalWhenFixtureHasNoActivityAtImpossibleThreshold() async throws {
        let fixture = try fixtureURL(named: "vad-smoke", extension: "wav")
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
        try await assertPlayableAudio(output, expectedDurationMilliseconds: result.outputDurationMilliseconds)
    }

    func testManuallyRemovesRequestedOriginalTimelineRanges() async throws {
        let fixture = try fixtureURL(named: "vad-smoke", extension: "wav")
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("vadcut-ios-manual-remove-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: output) }

        let removed = [
            AudioRange(startMilliseconds: 1_000, endMilliseconds: 3_000),
            AudioRange(startMilliseconds: 5_000, endMilliseconds: 6_500),
        ]
        let result = try await VadCut.trim(
            TrimRequest(
                inputURL: fixture,
                outputURL: output,
                manualTrimPlan: .removeRanges(removed)
            )
        )

        XCTAssertEqual(result.inputDurationMilliseconds, 11_478)
        XCTAssertEqual(result.removedDurationMilliseconds, 3_500)
        XCTAssertEqual(result.outputDurationMilliseconds, 7_978)
        XCTAssertEqual(result.removedRanges, removed)
        XCTAssertEqual(
            result.keptRanges,
            [
                AudioRange(startMilliseconds: 0, endMilliseconds: 1_000),
                AudioRange(startMilliseconds: 3_000, endMilliseconds: 5_000),
                AudioRange(startMilliseconds: 6_500, endMilliseconds: 11_478),
            ]
        )
        XCTAssertTrue(result.warnings.isEmpty)
        try await assertPlayableAudio(output, expectedDurationMilliseconds: result.outputDurationMilliseconds)
    }

    func testManuallyKeepsOnlyRequestedOriginalTimelineRanges() async throws {
        let fixture = try fixtureURL(named: "vad-smoke", extension: "wav")
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("vadcut-ios-manual-keep-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: output) }

        let kept = [
            AudioRange(startMilliseconds: 1_000, endMilliseconds: 3_000),
            AudioRange(startMilliseconds: 5_000, endMilliseconds: 6_500),
        ]
        let result = try await VadCut.trim(
            TrimRequest(
                inputURL: fixture,
                outputURL: output,
                manualTrimPlan: .keepRanges(kept)
            )
        )

        XCTAssertEqual(result.inputDurationMilliseconds, 11_478)
        XCTAssertEqual(result.removedDurationMilliseconds, 7_978)
        XCTAssertEqual(result.outputDurationMilliseconds, 3_500)
        XCTAssertEqual(result.keptRanges, kept)
        XCTAssertEqual(
            result.removedRanges,
            [
                AudioRange(startMilliseconds: 0, endMilliseconds: 1_000),
                AudioRange(startMilliseconds: 3_000, endMilliseconds: 5_000),
                AudioRange(startMilliseconds: 6_500, endMilliseconds: 11_478),
            ]
        )
        XCTAssertTrue(result.warnings.isEmpty)
        try await assertPlayableAudio(output, expectedDurationMilliseconds: result.outputDurationMilliseconds)
    }

    func testSileroTrimsInsertedSilenceFromRealMandarinMP3() async throws {
        let fixture = try fixtureURL(named: "mandarin-silence-demo", extension: "mp3")
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("vadcut-ios-mandarin-\(UUID().uuidString).m4a")
        let untrimmedOutput = FileManager.default.temporaryDirectory
            .appendingPathComponent("vadcut-ios-mandarin-untrimmed-\(UUID().uuidString).m4a")
        defer {
            try? FileManager.default.removeItem(at: output)
            try? FileManager.default.removeItem(at: untrimmedOutput)
        }

        let result = try await VadCut.trim(
            TrimRequest(inputURL: fixture, outputURL: output, config: .preset(.voiceMemo))
        )

        XCTAssertTrue((15_000...15_400).contains(result.inputDurationMilliseconds))
        XCTAssertGreaterThanOrEqual(result.removedDurationMilliseconds, 4_000)
        XCTAssertLessThanOrEqual(
            result.outputDurationMilliseconds,
            result.inputDurationMilliseconds - 4_000
        )
        XCTAssertEqual(result.keptRanges.count, 2)
        XCTAssertTrue(
            result.removedRanges.contains { range in
                range.startMilliseconds <= 7_110 && range.endMilliseconds >= 11_110
            }
        )
        XCTAssertTrue(result.warnings.isEmpty)
        try await assertPlayableAudio(output, expectedDurationMilliseconds: result.outputDurationMilliseconds)

        let untrimmedResult = try await VadCut.trim(
            TrimRequest(
                inputURL: fixture,
                outputURL: untrimmedOutput,
                manualTrimPlan: .keepRanges([
                    AudioRange(
                        startMilliseconds: 0,
                        endMilliseconds: result.inputDurationMilliseconds
                    ),
                ])
            )
        )
        XCTAssertEqual(untrimmedResult.outputDurationMilliseconds, result.inputDurationMilliseconds)
        try await assertPlayableAudio(
            untrimmedOutput,
            expectedDurationMilliseconds: untrimmedResult.outputDurationMilliseconds
        )

        let trimmedBytes = try XCTUnwrap(
            output.resourceValues(forKeys: [.fileSizeKey]).fileSize
        )
        let untrimmedBytes = try XCTUnwrap(
            untrimmedOutput.resourceValues(forKeys: [.fileSizeKey]).fileSize
        )
        XCTAssertLessThan(trimmedBytes, untrimmedBytes)
        print(
            "VadCutE2E fixture=mandarin-silence-demo.mp3 " +
                "inputDurationMs=\(result.inputDurationMilliseconds) " +
                "outputDurationMs=\(result.outputDurationMilliseconds) " +
                "removedDurationMs=\(result.removedDurationMilliseconds) " +
                "inputMp3Bytes=\((try? fixture.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) " +
                "untrimmedM4aBytes=\(untrimmedBytes) trimmedM4aBytes=\(trimmedBytes) " +
                "keptRanges=\(result.keptRanges) removedRanges=\(result.removedRanges)"
        )
    }

    private func fixtureURL(named name: String, extension fileExtension: String) throws -> URL {
        let bundle = Bundle(for: Self.self)
        if let direct = bundle.url(forResource: name, withExtension: fileExtension) {
            return direct
        }
        return try XCTUnwrap(
            bundle.url(forResource: name, withExtension: fileExtension, subdirectory: "Fixtures"),
            "\(name).\(fileExtension) is missing from the VadCutIOSTests bundle"
        )
    }

    private func assertPlayableAudio(
        _ output: URL,
        expectedDurationMilliseconds: Int64
    ) async throws {
        XCTAssertTrue(FileManager.default.fileExists(atPath: output.path))
        XCTAssertGreaterThan(try output.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0, 1_000)
        let outputAsset = AVURLAsset(url: output)
        let tracks = try await outputAsset.loadTracks(withMediaType: .audio)
        let duration = try await outputAsset.load(.duration)
        XCTAssertEqual(tracks.count, 1)
        XCTAssertGreaterThan(duration.seconds, 0.5)
        XCTAssertLessThanOrEqual(
            abs(Int64(duration.seconds * 1_000) - expectedDurationMilliseconds),
            500
        )
    }
}
