# Android 本地 VAD 方案

这是需要低延迟、离线或边录边裁剪时使用的方案。可直接集成仓库中的 [`vad-sdk/`](vad-sdk/)，远程 API 仍然保留在 [`remote-trim-client/`](remote-trim-client/)。

## 采集链路

```text
AudioRecord
    → 16 kHz / mono / PCM 16-bit
    → 每帧 20 ms（320 samples）
    → VadEngine（默认自适应能量 VAD；可替换为 NDK WebRTC/ONNX）
    → SpeechSegmenter
    → PCM range 重建
    → MediaCodec/MediaMuxer 或 WAV
```

`AudioRecord` 适合读取原始音频帧，但实际采样配置要检查设备返回值，不能假设所有麦克风都严格按请求的采样率工作。[AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord)

## 后端取舍

### 默认：自适应能量 VAD

对于“删除长静音”，首版 SDK 使用纯 Java 的自适应 RMS/能量检测：

- 不需要模型和 native `.so`；
- AAR 小，接入简单，Java/Kotlin 项目都能直接使用；
- CPU 和电量开销很低；
- 通过噪声底估计、连续语音帧、连续静音帧和保留边界，避免把短停顿误删。

它不是通用语音识别，也不承诺在强噪声环境下达到神经网络 VAD 的效果。

### 需要增强时：NDK WebRTC VAD

WebRTC VAD 适合作为同一 `VadEngine` 接口的可选 native backend：

- 不需要下载神经网络模型；
- NDK 二进制体积小；
- 10/20/30 ms 帧接口简单；
- CPU 负担低；
- Android 和 VPS 可以复用同一类 VAD 行为。

Java/Kotlin 层只负责采集和状态机；音频帧判断放在 C/C++，避免每帧产生大量对象。官方 VAD C 接口要求固定的 10/20/30 ms 帧，常用 16 kHz 单声道 PCM。

## 必须保留的状态机

不要把 `false` 帧直接删除。建议：

```text
frame_ms = 20
start_after = 80~120 ms speech
close_after = 400~700 ms non-speech
remove_only_if_gap >= 600~1000 ms
keep_silence = 200~400 ms
padding = 80~150 ms
```

采集线程只做快速复制，VAD 和区间整理放到专用后台线程。长录音结束后，按 sample index 重建 PCM；不要以 wall-clock 时间拼接。

## Silero/ONNX 什么时候值得

在嘈杂环境、远距离说话、轻声说话时，如果 WebRTC VAD 误判较多，再考虑 Silero VAD + ONNX Runtime CPU。它会增加模型、运行时和 APK 体积；对于已经上传到 VPS 的录音，手机端再跑一遍通常没有收益。

## TEN VAD 什么时候值得

TEN VAD 在 Android 上提供 ARM 预编译库和 Java 接口，技术上可以做成 `TenVadEngine`。但它的 Apache 2.0 许可包含额外部署限制，尤其不适合未经审查就作为面向第三方开发者的通用 SDK 默认依赖。仓库只保留后端扩展边界，不把 TEN 的二进制复制进公共 AAR。

## 结论

当前项目的推荐默认仍是远程处理：

```text
Android：录音 + 上传 + 播放
VPS：VAD + 裁剪 + 编码
```

本地 SDK 用于 `offline` 或 `realtime` mode，不与远程模式同时默认启用。
