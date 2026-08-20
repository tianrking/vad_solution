import Foundation

@objcMembers
public final class TrimTask: NSObject {
    private let operation: Task<TrimResult, Error>

    fileprivate init(operation: Task<TrimResult, Error>) {
        self.operation = operation
    }

    public func cancel() {
        operation.cancel()
    }
}

public enum VadCut {
    public static func trim(
        _ request: TrimRequest,
        onProgress: @escaping (TrimProgress) -> Void = { _ in }
    ) async throws -> TrimResult {
        try await Task.detached(priority: .userInitiated) {
            try await TrimCoordinator().trim(request: request, onProgress: onProgress)
        }.value
    }

    @discardableResult
    public static func start(
        _ request: TrimRequest,
        onProgress: @escaping (TrimProgress) -> Void = { _ in },
        completion: @escaping (Result<TrimResult, Error>) -> Void
    ) -> TrimTask {
        let operation = Task.detached(priority: .userInitiated) {
            try await TrimCoordinator().trim(request: request, onProgress: onProgress)
        }
        Task {
            do {
                let result = try await operation.value
                await MainActor.run { completion(.success(result)) }
            } catch {
                await MainActor.run { completion(.failure(error)) }
            }
        }
        return TrimTask(operation: operation)
    }
}

final class TrimCoordinator {
    private let analyzer = AudioAnalyzer()
    private let exporter = AudioExporter()

    func trim(
        request: TrimRequest,
        onProgress: @escaping (TrimProgress) -> Void
    ) async throws -> TrimResult {
        try validateRequestShape(request)

        let inputAccess = request.inputURL.startAccessingSecurityScopedResource()
        let outputAccess = request.outputURL.startAccessingSecurityScopedResource()
        defer {
            if inputAccess { request.inputURL.stopAccessingSecurityScopedResource() }
            if outputAccess { request.outputURL.stopAccessingSecurityScopedResource() }
        }
        guard FileManager.default.fileExists(atPath: request.inputURL.path) else {
            throw TrimError(code: .inputOpenFailed, message: "The input file does not exist or is inaccessible")
        }

        emit(onProgress, TrimProgress(
            phase: .analyzing,
            percent: 0,
            processedDurationMilliseconds: 0,
            totalDurationMilliseconds: 0
        ))
        let analysisProgress: (Int64, Int64) -> Void = { processed, total in
            let percentage = total > 0 ? Int(min(total, processed) * 60 / total) : 0
            self.emit(onProgress, TrimProgress(
                phase: .analyzing,
                percent: percentage,
                processedDurationMilliseconds: processed / 1_000,
                totalDurationMilliseconds: total / 1_000
            ))
        }
        let analysis: AnalysisResult
        if request.manualTrimPlan == nil {
            analysis = try await analyzer.analyze(
                inputURL: request.inputURL,
                config: request.config,
                onProgress: analysisProgress
            )
        } else {
            analysis = try await analyzer.analyzeDuration(
                inputURL: request.inputURL,
                onProgress: analysisProgress
            )
        }
        try Task.checkCancellation()

        var warnings: [TrimWarning] = []
        if !analysis.durationWasKnown { warnings.append(.inputDurationUnknown) }
        let keptRanges: [TimeRangeUs]
        if let manualTrimPlan = request.manualTrimPlan {
            keptRanges = try ManualRangePlanner.resolveKeptRanges(
                plan: manualTrimPlan,
                durationMicroseconds: analysis.duration
            )
        } else if analysis.keptRanges.isEmpty {
            switch request.config.noSpeechPolicy {
            case .fail:
                throw TrimError(code: .noSpeechDetected, message: "No requested audio activity was detected")
            case .keepOriginal:
                warnings.append(.noActivityDetectedKeptOriginal)
                keptRanges = [TimeRangeUs(start: 0, end: analysis.duration)]
            }
        } else {
            keptRanges = analysis.keptRanges
        }

        emit(onProgress, TrimProgress(
            phase: .exporting,
            percent: 60,
            processedDurationMilliseconds: 0,
            totalDurationMilliseconds: analysis.duration / 1_000
        ))
        let temporaryURL = request.outputURL.deletingLastPathComponent()
            .appendingPathComponent(".vadcut-\(UUID().uuidString).m4a")
        do {
            try await exporter.export(
                inputURL: request.inputURL,
                temporaryOutputURL: temporaryURL,
                keptRanges: keptRanges,
                fadeDurationMicroseconds: request.config.fadeDurationMilliseconds * 1_000
            ) { percentage in
                self.emit(onProgress, TrimProgress(
                    phase: .exporting,
                    percent: 60 + min(100, max(0, percentage)) * 35 / 100,
                    processedDurationMilliseconds: analysis.duration / 1_000 * Int64(percentage) / 100,
                    totalDurationMilliseconds: analysis.duration / 1_000
                ))
            }
            try Task.checkCancellation()
            emit(onProgress, TrimProgress(
                phase: .writingOutput,
                percent: 95,
                processedDurationMilliseconds: 0,
                totalDurationMilliseconds: analysis.duration / 1_000
            ))
            try commitOutput(temporaryURL: temporaryURL, outputURL: request.outputURL)
        } catch is CancellationError {
            try? FileManager.default.removeItem(at: temporaryURL)
            throw CancellationError()
        } catch let error as TrimError {
            try? FileManager.default.removeItem(at: temporaryURL)
            throw error
        } catch {
            try? FileManager.default.removeItem(at: temporaryURL)
            throw TrimError(code: .outputWriteFailed, message: "Unable to commit the output file", underlying: error)
        }

        let keptDuration = keptRanges.reduce(Int64(0)) { $0 + $1.duration }
        let removedRanges = complement(keptRanges, duration: analysis.duration)
        let result = TrimResult(
            outputURL: request.outputURL,
            inputDurationMilliseconds: roundedMilliseconds(analysis.duration),
            outputDurationMilliseconds: roundedMilliseconds(keptDuration),
            removedDurationMilliseconds: roundedMilliseconds(max(0, analysis.duration - keptDuration)),
            keptRanges: keptRanges.map(publicRange),
            removedRanges: removedRanges.map(publicRange),
            warnings: warnings
        )
        emit(onProgress, TrimProgress(
            phase: .completed,
            percent: 100,
            processedDurationMilliseconds: result.inputDurationMilliseconds,
            totalDurationMilliseconds: result.inputDurationMilliseconds
        ))
        return result
    }

    private func validateRequestShape(_ request: TrimRequest) throws {
        guard request.inputURL.isFileURL, request.outputURL.isFileURL else {
            throw TrimError(code: .invalidRequest, message: "Input and output must be file URLs")
        }
        guard request.inputURL.standardizedFileURL != request.outputURL.standardizedFileURL else {
            throw TrimError(code: .invalidRequest, message: "In-place overwrite is not supported")
        }
        try validateConfig(request.config)
        if let manualTrimPlan = request.manualTrimPlan {
            try ManualRangePlanner.validateBasics(plan: manualTrimPlan)
        }
    }

    private func commitOutput(temporaryURL: URL, outputURL: URL) throws {
        let manager = FileManager.default
        if manager.fileExists(atPath: outputURL.path) {
            _ = try manager.replaceItemAt(outputURL, withItemAt: temporaryURL)
        } else {
            try manager.moveItem(at: temporaryURL, to: outputURL)
        }
    }

    private func emit(_ callback: @escaping (TrimProgress) -> Void, _ progress: TrimProgress) {
        DispatchQueue.main.async { callback(progress) }
    }

    private func roundedMilliseconds(_ microseconds: Int64) -> Int64 {
        (microseconds + 500) / 1_000
    }

    private func publicRange(_ range: TimeRangeUs) -> AudioRange {
        AudioRange(
            startMilliseconds: roundedMilliseconds(range.start),
            endMilliseconds: roundedMilliseconds(range.end)
        )
    }
}
