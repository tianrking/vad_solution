# Third-party notices

VadCut Android 0.2.0 uses the following third-party components. They are not relicensed by the VadCut Apache-2.0 license.

## Silero VAD

- Project: https://github.com/snakers4/silero-vad
- Model: `silero_vad.onnx`, release/tag `v6.2.1`
- Bundled model SHA-256: `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`
- License: MIT License
- Copyright: Silero Team and contributors

## Microsoft ONNX Runtime

- Artifact: `com.microsoft.onnxruntime:onnxruntime-android:1.28.0`
- Project: https://github.com/microsoft/onnxruntime
- License: MIT License
- Copyright: Microsoft Corporation

## AndroidX Media3

- Artifacts: `media3-common`, `media3-effect`, `media3-transformer` version `1.11.0`
- Project: https://github.com/androidx/media
- License: Apache License 2.0
- Copyright: The Android Open Source Project

## Kotlin Coroutines

- Artifact: `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`
- Project: https://github.com/Kotlin/kotlinx.coroutines
- License: Apache License 2.0
- Copyright: JetBrains and contributors

## Google FLEURS Mandarin test fixture

- Test/documentation-only artifacts: `vadcut/src/androidTest/assets/mandarin-silence-demo.mp3` and the Android-generated derivative `demo-audio/mandarin-silence-demo-after-android.m4a`; neither is packaged in the release AAR.
- Dataset: [Google FLEURS](https://huggingface.co/datasets/google/fleurs), revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`
- Selection: `cmn_hans_cn`, `validation`, rows 1 (`id=1605`) and 3 (`id=1648`)
- Citation: Conneau et al., [FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech](https://arxiv.org/abs/2205.12446), 2022
- License: [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)
- Changes: trimmed only outer silence, inserted 4.000 seconds of generated digital silence between the two utterances, concatenated them, and encoded the result as 16 kHz mono 64 kbps MP3.
- Fixture SHA-256: `3fb81d09f37e7648559009a9a324b31a0fb1558fe6aaef14947b9e18a366e0d7`
- Android-generated derivative: automatically removed the detected non-speech ranges with the SDK's default `VOICE_MEMO + SPEECH` mode and encoded the kept ranges as AAC/M4A.
- Android-generated derivative SHA-256: `f8f03f98834771577ee58e81d1b38706ef1cbdc29926b6ffa717433882ae62e2`

The input fixture manifest records the selected rows, transcripts, source hashes, used ranges, and exact output timeline in `mandarin-silence-demo.json`. Device and output provenance are recorded in `demo-audio/mandarin-silence-demo-after-android.json`.

The complete license texts are available from the linked projects and their Maven artifacts.
