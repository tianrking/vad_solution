# 纯 Android 离线方案

本目录只描述纯 Android 本地实现：不上传音频、不依赖 VPS、不需要网络。目标是把录音中的长静音裁掉，并以一个可以集成到 Kotlin/Java 项目的 AAR module 形式提供。

## 最终链路

```text
AndroidVadRecorder
    → AudioRecord
    → 16 kHz / mono / PCM 16-bit
    → VadEngine
    → SpeechSegmenter
    → SpeechSegment sample ranges
    → WavExporter 或宿主 App 的 MediaCodec/MediaMuxer
```

当前仓库中的 [`vad-sdk/`](vad-sdk/) 是本地 SDK module。它没有 UI、网络请求、Activity 依赖、FFmpeg 或 ONNX Runtime。

## 当前实现

SDK 当前内置纯 Java `EnergyVadEngine`，用于第一版离线长静音裁剪：

- 自适应 RMS/dBFS 噪声底；
- 连续语音帧确认；
- 连续静音帧才切断；
- 前后保留安全边界；
- 输入输出只使用 PCM sample index；
- 不需要 `.so`、模型或额外运行时。

`VadEngine` 已经是后端接口。后续如果真实设备噪声测试需要更强鲁棒性，可以增加 `vad-sdk-webrtc`，把 NDK WebRTC VAD 接入同一个接口，不需要修改录音和裁剪层。

## 接入现有 Android 工程

把仓库作为 module 加入宿主工程：

```kotlin
// settings.gradle.kts
include(":vad-sdk")
project(":vad-sdk").projectDir = file("android/vad-sdk")

// app/build.gradle.kts
implementation(project(":vad-sdk"))
```

模块最低支持 `minSdk 21`，不要求 Kotlin plugin、AndroidX 或协程。

## Kotlin 使用

宿主 App 先申请运行时麦克风权限：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

然后在后台线程录音和导出：

```kotlin
val config = VadConfig.builder()
    .setSampleRate(16_000)
    .setFrameDurationMs(20)
    .setMinSpeechMs(80)
    .setMinSilenceMs(700)
    .setKeepSilenceMs(250)
    .build()

val rawPcm = File(cacheDir, "take.pcm")
val recorder = VadSdk.createRecorder(config, rawPcm)
recorder.start()

// 用户点击停止后，放到 Dispatchers.IO 或其他后台线程执行
val recording = recorder.stop()
val outputWav = File(cacheDir, "trimmed.wav")
WavExporter.export(
    recording.pcmFile,
    recording.speechSegments,
    recording.sampleRate,
    outputWav,
)
```

如果宿主 App 已经有自己的 `AudioRecord`，也可以只使用底层 API：

```kotlin
val processor = VadSdk.createDefault(config)
processor.accept(pcmShortArray, 0, validSampleCount)
val ranges = processor.finish()
```

Java 项目使用同一套 public Java API，不需要 Kotlin runtime：

```java
VadConfig config = VadConfig.builder()
        .setSampleRate(16000)
        .setFrameDurationMs(20)
        .setMinSilenceMs(700)
        .setKeepSilenceMs(250)
        .build();

AndroidVadRecorder recorder = VadSdk.createRecorder(config, rawPcmFile);
recorder.start();
LocalVadRecording recording = recorder.stop();
WavExporter.export(
        recording.getPcmFile(),
        recording.getSpeechSegments(),
        recording.getSampleRate(),
        outputWav);
```

## 音频输入和输出边界

- 本地实时采集使用 `AudioRecord`，输入统一为 16 kHz、单声道、PCM16。
- SDK 不自动申请权限，权限和前台服务由宿主 App 管理。
- `AndroidVadRecorder` 保存原始 PCM，同时在线计算语音区间，长录音不会把整段音频放进内存。
- `WavExporter` 可以直接输出裁剪后的 WAV。
- 如果必须输出 AAC/M4A，由宿主 App 使用 Android `MediaCodec`/`MediaMuxer` 编码。
- 不把 FFmpeg 放进核心 AAR，避免包体、ABI 和许可证复杂度。

## 推荐参数

| 参数 | 默认值 | 作用 |
|---|---:|---|
| sample rate | 16 kHz | 统一语音输入格式 |
| frame | 20 ms | CPU、延迟和边界精度的折中 |
| min speech | 80 ms | 防止噪声尖峰启动语音片段 |
| min silence | 700 ms | 只有长静音才切断 |
| keep silence | 250 ms | 保留语音前后的自然边界 |

短停顿不按单帧删除，真正的切断条件是 `minSilenceMs`。

## 工程验证边界

纯 Java VAD、分帧、区间状态机和 WAV 导出可以在普通 JDK 下做单元测试；`AudioRecord` 和最终 AAR 必须在 Android SDK、Android Gradle Plugin 和实体设备上验证。真实设备测试至少覆盖权限拒绝、普通麦克风、蓝牙麦克风、静音、短停顿、长停顿和长录音。
