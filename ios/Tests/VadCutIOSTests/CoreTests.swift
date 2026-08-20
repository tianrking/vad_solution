import XCTest
@testable import VadCutIOS

final class CoreTests: XCTestCase {
    func testSegmentPlannerAppliesPaddingAndMergesRanges() {
        var config = TrimConfig.preset(.voiceMemo)
        config.minimumSilenceDurationMilliseconds = 100
        config.minimumSpeechDurationMilliseconds = 0
        config.paddingBeforeMilliseconds = 50
        config.paddingAfterMilliseconds = 50
        let planner = ActivitySegmentPlanner(config: config)

        planner.accept(score: 1, frameStart: 200_000, frameEnd: 232_000)
        planner.accept(score: 0, frameStart: 232_000, frameEnd: 350_000)
        planner.accept(score: 1, frameStart: 360_000, frameEnd: 392_000)
        planner.accept(score: 0, frameStart: 392_000, frameEnd: 510_000)

        XCTAssertEqual(
            planner.finish(duration: 1_000_000),
            [
                TimeRangeUs(start: 150_000, end: 282_000),
                TimeRangeUs(start: 310_000, end: 442_000),
            ]
        )
    }

    func testComplementReportsRemovedRanges() {
        XCTAssertEqual(
            complement(
                [
                    TimeRangeUs(start: 100, end: 200),
                    TimeRangeUs(start: 300, end: 400),
                ],
                duration: 500
            ),
            [
                TimeRangeUs(start: 0, end: 100),
                TimeRangeUs(start: 200, end: 300),
                TimeRangeUs(start: 400, end: 500),
            ]
        )
    }

    func testStreamingResamplerUsesConstantOutputCallback() {
        var output: [Float] = []
        let resampler = StreamingMonoResampler(
            inputSampleRate: 48_000,
            outputSampleRate: 16_000,
            output: { output.append($0) }
        )
        for index in 0..<48_000 {
            resampler.accept(sin(Float(index) * 0.01))
        }
        assertApproximatelyEqual(output.count, 16_000, accuracy: 1)
        XCTAssertTrue(output.allSatisfy { $0.isFinite && $0 >= -1 && $0 <= 1 })
    }

    func testTrimErrorBridgesStableNSErrorDomainCodeAndStringCode() {
        let error = TrimError(code: .invalidTimeRanges, message: "Invalid manual range") as NSError

        XCTAssertEqual(error.domain, TrimError.errorDomain)
        XCTAssertEqual(error.code, VDTrimErrorCode.invalidTimeRanges.rawValue)
        XCTAssertEqual(
            error.userInfo[TrimError.codeUserInfoKey] as? String,
            TrimErrorCode.invalidTimeRanges.rawValue
        )
        XCTAssertEqual(error.localizedDescription, "Invalid manual range")
    }

    func testSwiftAndObjectiveCErrorCodesStayAligned() {
        let swiftCodes: [TrimErrorCode] = [
            .invalidRequest,
            .invalidTimeRanges,
            .inputOpenFailed,
            .noAudioTrack,
            .unsupportedAudioFormat,
            .modelLoadFailed,
            .modelIntegrityFailed,
            .analysisFailed,
            .noSpeechDetected,
            .exportFailed,
            .outputWriteFailed,
            .cancelled,
        ]
        let objectiveCCodes: [VDTrimErrorCode] = [
            .invalidRequest,
            .invalidTimeRanges,
            .inputOpenFailed,
            .noAudioTrack,
            .unsupportedAudioFormat,
            .modelLoadFailed,
            .modelIntegrityFailed,
            .analysisFailed,
            .noSpeechDetected,
            .exportFailed,
            .outputWriteFailed,
            .cancelled,
        ]

        XCTAssertEqual(swiftCodes.map(\.numericValue), objectiveCCodes.map(\.rawValue))
    }
}

private extension XCTestCase {
    func assertApproximatelyEqual(_ actual: Int, _ expected: Int, accuracy: Int) {
        XCTAssertLessThanOrEqual(abs(actual - expected), accuracy)
    }
}
