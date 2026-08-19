# vad-sdk

一个纯 Android、离线、不绑定 UI 的 Android library module，用于录音、检测语音区间并删除长静音。

它面向已有 Kotlin/Java Android 项目，不负责网络请求、不负责申请麦克风权限、不负责 Activity 生命周期，也不把 FFmpeg 或 ONNX Runtime 放进核心包。

## 当前实现

当前模块内置 `EnergyVadEngine`：

```text
PCM 16-bit
  → RMS / dBFS
  → 自适应噪声底
  → VadFrameResult
  → SpeechSegmenter
  → List<SpeechSegment>
```

这个实现针对的是“裁掉长静音”，不是语音识别。它用连续语音帧、连续静音帧和保留边界来避免短停顿被误删。默认参数针对手机录音的 16 kHz、20 ms 帧，可以按产品样本调整。

后端通过 `VadEngine` 接口隔离。需要更强噪声鲁棒性时，可以增加一个独立的 `vad-sdk-webrtc` AAR，把 NDK WebRTC VAD 映射到同一接口；如果应用已有 ONNX Runtime，再增加 Silero provider。业务层不需要改 `PcmVadProcessor` 和区间裁剪逻辑。

模块现在也提供 `AndroidVadRecorder`：它直接使用 Android `AudioRecord` 录制 mono PCM16，在录音线程中同步运行 VAD，并把原始 PCM 写入文件。停止后通过 `WavExporter` 只导出语音区间，因此整个流程不需要服务器。

## 集成到现有工程

把本目录作为 module 加入宿主工程：

```kotlin
// settings.gradle.kts
include(":vad-sdk")
project(":vad-sdk").projectDir = file("android/vad-sdk")

// app/build.gradle.kts
implementation(project(":vad-sdk"))
```

module 的最小要求是 `minSdk 21`。它只使用 Java 标准类和 Android library plugin，不需要 Kotlin plugin、AndroidX 或协程。

## Kotlin 示例

```kotlin
val config = VadConfig.builder()
    .setSampleRate(16_000)
    .setFrameDurationMs(20)
    .setMinSpeechMs(80)
    .setMinSilenceMs(700)
    .setKeepSilenceMs(250)
    .build()

val processor = VadSdk.createDefault(config)

// AudioRecord 在后台线程读取 PCM_SHORT，避免在这里分配 ByteArray。
while (recording) {
    val count = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
    if (count > 0) {
        processor.accept(pcmBuffer, 0, count)
    }
}

val ranges = processor.finish()
for (range in ranges) {
    println("${range.startSample}..${range.endSample}")
}
```

`AudioRecord` 的配置要确认设备实际返回的采样率和声道数；如果录音输入不是 16 kHz 单声道 PCM，应在送入 SDK 前重采样/转单声道。SDK 不偷偷改变 sample index，返回的区间始终以输入 PCM 的采样点为单位。

如果需要 SDK 直接管理本地录音：

```kotlin
val rawPcm = File(cacheDir, "take.pcm")
val recorder = VadSdk.createRecorder(config, rawPcm)
recorder.start()

// 用户点击停止后，在后台线程调用
val recording = recorder.stop()
val output = File(cacheDir, "trimmed.wav")
WavExporter.export(
    recording.pcmFile,
    recording.speechSegments,
    recording.sampleRate,
    output,
)
```

宿主 App 仍然要自己申请：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

并在运行时获取权限。录音、停止、文件 I/O 和 WAV 导出都放在后台线程，不要阻塞主线程。

## Java 示例

```java
VadConfig config = VadConfig.builder()
        .setSampleRate(16000)
        .setFrameDurationMs(20)
        .setMinSilenceMs(700)
        .setKeepSilenceMs(250)
        .build();

PcmVadProcessor processor = VadSdk.createDefault(config);
processor.accept(pcm, 0, readCount);
List<SpeechSegment> ranges = processor.finish();
```

所有 public 类型都是 Java API，因此 Kotlin 和 Java 调用方使用同一套二进制接口。

## 输出音频

SDK 返回的是语音 sample ranges，而不是重新编码后的 M4A：

- WAV/PCM：按区间复制样本，再写 WAV header；
- AAC/M4A：用 Android `MediaCodec`/`MediaMuxer` 编码；
- 已有压缩文件：先用 `MediaExtractor` + `MediaCodec` 解码成 PCM，再送入 SDK。

当前 `WavExporter` 可以直接生成本地 WAV；如果业务必须输出 AAC/M4A，再由宿主 App 使用 Android `MediaCodec`/`MediaMuxer` 编码。不建议为了这一个功能把 FFmpeg 放进默认 AAR。

## 参数建议

| 参数 | 默认值 | 作用 |
|---|---:|---|
| sample rate | 16 kHz | 语音 VAD 的统一输入格式 |
| frame | 20 ms | 延迟、CPU、边界精度的折中 |
| min speech | 80 ms | 防止一个噪声尖峰启动片段 |
| min silence | 700 ms | 只有达到该长度才切断当前片段 |
| keep silence | 250 ms | 每个语音片段前后保留的安全边界 |
| absolute threshold | -48 dBFS | 无噪声底时的最低能量门槛 |

短停顿不要按单帧直接删除；真正决定剪切的是 `minSilenceMs`。建议先用真实手机录音调参，再固定为产品配置。

## 发布形态

开发阶段用 `implementation(project(":vad-sdk"))`；稳定后可以发布为：

```kotlin
implementation("com.example:vad-sdk:0.1.0")
```

若加入 NDK backend，建议拆成 `vad-sdk-core` 与 `vad-sdk-webrtc` 两个 artifact，按 ABI 提供 `arm64-v8a`，必要时再提供 `armeabi-v7a`，不要让默认 core 被 native/ONNX 依赖绑死。
