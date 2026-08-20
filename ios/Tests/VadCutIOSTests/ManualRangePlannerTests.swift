import XCTest
@testable import VadCutIOS

final class ManualRangePlannerTests: XCTestCase {
    func testRemoveModeSortsAndMergesOverlappingRanges() throws {
        let plan = ManualTrimPlan.removeRanges([
            AudioRange(startMilliseconds: 4_000, endMilliseconds: 6_000),
            AudioRange(startMilliseconds: 1_000, endMilliseconds: 2_500),
            AudioRange(startMilliseconds: 2_000, endMilliseconds: 4_500),
        ])

        XCTAssertEqual(
            try ManualRangePlanner.resolveKeptRanges(
                plan: plan,
                durationMicroseconds: 10_000_000
            ),
            [
                TimeRangeUs(start: 0, end: 1_000_000),
                TimeRangeUs(start: 6_000_000, end: 10_000_000),
            ]
        )
    }

    func testKeepModeSortsAndMergesTouchingRanges() throws {
        let plan = ManualTrimPlan.keepRanges([
            AudioRange(startMilliseconds: 5_000, endMilliseconds: 8_000),
            AudioRange(startMilliseconds: 1_000, endMilliseconds: 3_000),
            AudioRange(startMilliseconds: 3_000, endMilliseconds: 5_000),
        ])

        XCTAssertEqual(
            try ManualRangePlanner.resolveKeptRanges(
                plan: plan,
                durationMicroseconds: 10_000_000
            ),
            [TimeRangeUs(start: 1_000_000, end: 8_000_000)]
        )
    }

    func testPublicRoundedEndMapsBackToExactDecodedDuration() throws {
        let duration: Int64 = 10_000_499
        let plan = ManualTrimPlan.keepRanges([
            AudioRange(startMilliseconds: 9_000, endMilliseconds: 10_000),
        ])

        XCTAssertEqual(
            try ManualRangePlanner.resolveKeptRanges(
                plan: plan,
                durationMicroseconds: duration
            ),
            [TimeRangeUs(start: 9_000_000, end: duration)]
        )
    }

    func testOutOfBoundsRangeReturnsSpecificError() {
        let plan = ManualTrimPlan.removeRanges([
            AudioRange(startMilliseconds: 9_000, endMilliseconds: 10_001),
        ])

        assertInvalidTimeRanges {
            try ManualRangePlanner.resolveKeptRanges(
                plan: plan,
                durationMicroseconds: 10_000_000
            )
        }
    }

    func testRemovingCompleteInputIsRejected() {
        let plan = ManualTrimPlan.removeRanges([
            AudioRange(startMilliseconds: 0, endMilliseconds: 10_000),
        ])

        assertInvalidTimeRanges {
            try ManualRangePlanner.resolveKeptRanges(
                plan: plan,
                durationMicroseconds: 10_000_000
            )
        }
    }

    func testEmptyAndInvalidRangesAreRejectedBeforeDecode() {
        assertInvalidTimeRanges {
            try ManualRangePlanner.validateBasics(
                plan: .removeRanges([])
            )
        }
        assertInvalidTimeRanges {
            try ManualRangePlanner.validateBasics(
                plan: .keepRanges([
                    AudioRange(startMilliseconds: -1, endMilliseconds: 5),
                ])
            )
        }
        assertInvalidTimeRanges {
            try ManualRangePlanner.validateBasics(
                plan: .keepRanges([
                    AudioRange(startMilliseconds: 5, endMilliseconds: 5),
                ])
            )
        }
    }

    private func assertInvalidTimeRanges(
        file: StaticString = #filePath,
        line: UInt = #line,
        _ operation: () throws -> Void
    ) {
        XCTAssertThrowsError(try operation(), file: file, line: line) { error in
            XCTAssertEqual((error as? TrimError)?.code, .invalidTimeRanges, file: file, line: line)
        }
    }
}
