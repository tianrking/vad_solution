import CryptoKit
import Foundation
import onnxruntime_objc

final class SileroVadEngine: ActivityDetector {
    static let modelSHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"

    private let environment: ORTEnv
    private let session: ORTSession
    private var recurrentState = Array(repeating: Float(0), count: 256)
    private var contextWindow = Array(repeating: Float(0), count: 64)

    init(verifyIntegrity: Bool) throws {
        let modelURL = try VadCutResources.modelURL()
        if verifyIntegrity {
            do {
                let data = try Data(contentsOf: modelURL, options: .mappedIfSafe)
                let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                guard digest.caseInsensitiveCompare(Self.modelSHA256) == .orderedSame else {
                    throw TrimError(
                        code: .modelIntegrityFailed,
                        message: "Bundled Silero VAD model failed its SHA-256 integrity check"
                    )
                }
            } catch let error as TrimError {
                throw error
            } catch {
                throw TrimError(
                    code: .modelLoadFailed,
                    message: "Unable to read the bundled Silero VAD model",
                    underlying: error
                )
            }
        }

        do {
            environment = try ORTEnv(loggingLevel: .warning)
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(1)
            session = try ORTSession(
                env: environment,
                modelPath: modelURL.path,
                sessionOptions: options
            )
        } catch {
            throw TrimError(
                code: .modelLoadFailed,
                message: "Unable to initialize ONNX Runtime for Silero VAD",
                underlying: error
            )
        }
    }

    func score(samples: [Float], validSampleCount: Int) throws -> Float {
        guard samples.count >= VadFrameCollector.frameSampleCount else {
            throw TrimError(code: .analysisFailed, message: "Silero received an incomplete frame buffer")
        }

        let modelInput = contextWindow + Array(samples.prefix(VadFrameCollector.frameSampleCount))
        let sampleRate: [Int64] = [Int64(VadFrameCollector.sampleRate)]

        do {
            let inputValue = try ORTValue(
                tensorData: NSMutableData(data: Data(copyingBufferOf: modelInput)),
                elementType: .float,
                shape: [1, 576]
            )
            let stateValue = try ORTValue(
                tensorData: NSMutableData(data: Data(copyingBufferOf: recurrentState)),
                elementType: .float,
                shape: [2, 1, 128]
            )
            let sampleRateValue = try ORTValue(
                tensorData: NSMutableData(data: Data(copyingBufferOf: sampleRate)),
                elementType: .int64,
                shape: [1]
            )

            let outputs = try session.run(
                withInputs: [
                    "input": inputValue,
                    "state": stateValue,
                    "sr": sampleRateValue,
                ],
                outputNames: ["output", "stateN"],
                runOptions: nil
            )
            guard let probabilityValue = outputs["output"],
                  let stateOutput = outputs["stateN"],
                  let probability: Float = (try probabilityValue.tensorData() as Data).copiedArray().first else {
                throw TrimError(code: .analysisFailed, message: "Silero returned incomplete outputs")
            }
            let nextState: [Float] = (try stateOutput.tensorData() as Data).copiedArray()
            guard nextState.count == recurrentState.count else {
                throw TrimError(code: .analysisFailed, message: "Silero returned an invalid recurrent state")
            }
            recurrentState = nextState
            contextWindow = Array(
                samples[(VadFrameCollector.frameSampleCount - contextWindow.count)..<VadFrameCollector.frameSampleCount]
            )
            return min(1, max(0, probability))
        } catch let error as TrimError {
            throw error
        } catch {
            throw TrimError(
                code: .analysisFailed,
                message: "Silero VAD inference failed",
                underlying: error
            )
        }
    }
}

private final class VadCutBundleToken {}

enum VadCutResources {
    static func modelURL() throws -> URL {
        let directBundles = [Bundle(for: VadCutBundleToken.self), Bundle.main]
        for bundle in directBundles {
            if let url = bundle.url(forResource: "silero_vad", withExtension: "onnx") {
                return url
            }
            if let resourcesURL = bundle.url(forResource: "VadCutIOSResources", withExtension: "bundle"),
               let resourcesBundle = Bundle(url: resourcesURL),
               let url = resourcesBundle.url(forResource: "silero_vad", withExtension: "onnx") {
                return url
            }
        }
        throw TrimError(code: .modelLoadFailed, message: "Bundled Silero VAD model was not found")
    }
}

private extension Data {
    init<T>(copyingBufferOf array: [T]) {
        self = array.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    func copiedArray<T>() -> [T] {
        guard count % MemoryLayout<T>.stride == 0 else { return [] }
        return withUnsafeBytes { rawBuffer in
            Array(rawBuffer.bindMemory(to: T.self))
        }
    }
}
