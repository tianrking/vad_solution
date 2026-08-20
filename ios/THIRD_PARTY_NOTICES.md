# Third-party notices

VadCutIOS includes or depends on the following projects:

- [Silero VAD](https://github.com/snakers4/silero-vad), MIT License
- [ONNX Runtime](https://github.com/microsoft/onnxruntime), MIT License

The Silero ONNX model is bundled in `Sources/VadCutIOS/Resources`. ONNX Runtime
is resolved as the `onnxruntime-objc` CocoaPod and is not copied into this
repository.

## Test-only Mandarin audio fixture

`Tests/Fixtures/mandarin-silence-demo.mp3` is derived from the Google FLEURS
dataset (`google/fleurs`, revision
`70bb2e84b976b7e960aa89f1c648e09c59f894dd`, configuration `cmn_hans_cn`,
validation rows 1 and 3), licensed under CC BY 4.0.

The two source utterances were trimmed only at their outer silence, a generated
4.000-second digital-silence interval was inserted between them, and the result
was encoded as 16 kHz mono 64 kbps MP3. The fixture is test-only and is not
included in the released SDK resource bundle. Exact row IDs, transcripts,
ranges, source hashes, output hash, and modification details are recorded in
`Tests/Fixtures/mandarin-silence-demo.json`.
