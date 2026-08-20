import Foundation

/// Converts and validates public millisecond ranges against the decoded input duration.
enum ManualRangePlanner {
    static func validateBasics(plan: ManualTrimPlan) throws {
        guard !plan.ranges.isEmpty else {
            throw invalid("Manual trim ranges must not be empty")
        }
        for range in plan.ranges {
            guard range.startMilliseconds >= 0,
                  range.endMilliseconds > range.startMilliseconds else {
                throw invalid("Manual trim ranges must have a non-negative start and positive duration")
            }
        }
    }

    static func resolveKeptRanges(
        plan: ManualTrimPlan,
        durationMicroseconds: Int64
    ) throws -> [TimeRangeUs] {
        guard durationMicroseconds > 0 else {
            throw invalid("The decoded input duration must be positive")
        }
        try validateBasics(plan: plan)

        let normalized = try normalize(
            plan.ranges,
            durationMicroseconds: durationMicroseconds
        )
        let keptRanges: [TimeRangeUs]
        switch plan.mode {
        case .removeRanges:
            keptRanges = complement(normalized, duration: durationMicroseconds)
        case .keepRanges:
            keptRanges = normalized
        }
        guard !keptRanges.isEmpty else {
            throw invalid("The manual trim plan removes the complete audio")
        }
        return keptRanges
    }

    private static func normalize(
        _ ranges: [AudioRange],
        durationMicroseconds: Int64
    ) throws -> [TimeRangeUs] {
        let durationMilliseconds = roundedMilliseconds(durationMicroseconds)
        var converted: [TimeRangeUs] = []
        converted.reserveCapacity(ranges.count)
        for range in ranges {
            guard range.endMilliseconds <= durationMilliseconds else {
                let bounds = "[\(range.startMilliseconds), \(range.endMilliseconds))"
                throw invalid(
                    "Manual trim range \(bounds) exceeds " +
                    "the input duration of \(durationMilliseconds) ms"
                )
            }

            let (startMicroseconds, startOverflow) = range.startMilliseconds
                .multipliedReportingOverflow(by: 1_000)
            let (scaledEndMicroseconds, endOverflow) = range.endMilliseconds
                .multipliedReportingOverflow(by: 1_000)
            guard !startOverflow, !endOverflow else {
                throw invalid("Manual trim range is too large")
            }
            let endMicroseconds = range.endMilliseconds == durationMilliseconds
                ? durationMicroseconds
                : scaledEndMicroseconds
            guard startMicroseconds < durationMicroseconds,
                  endMicroseconds > startMicroseconds else {
                let bounds = "[\(range.startMilliseconds), \(range.endMilliseconds))"
                throw invalid(
                    "Manual trim range \(bounds) is outside " +
                    "the input duration of \(durationMilliseconds) ms"
                )
            }
            converted.append(
                TimeRangeUs(
                    start: startMicroseconds,
                    end: min(endMicroseconds, durationMicroseconds)
                )
            )
        }
        converted.sort { lhs, rhs in
            lhs.start == rhs.start ? lhs.end < rhs.end : lhs.start < rhs.start
        }

        var merged: [TimeRangeUs] = []
        for range in converted {
            guard let previous = merged.last else {
                merged.append(range)
                continue
            }
            if range.start > previous.end {
                merged.append(range)
            } else {
                merged[merged.count - 1] = TimeRangeUs(
                    start: previous.start,
                    end: max(previous.end, range.end)
                )
            }
        }
        return merged
    }

    private static func roundedMilliseconds(_ microseconds: Int64) -> Int64 {
        microseconds / 1_000 + (microseconds % 1_000 >= 500 ? 1 : 0)
    }

    private static func invalid(_ message: String) -> TrimError {
        TrimError(code: .invalidTimeRanges, message: message)
    }
}
