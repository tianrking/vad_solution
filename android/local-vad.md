# Android 本地 VAD 备选方案

这不是当前远程 API 的默认链路，而是需要低延迟、离线或边录边裁剪时使用的方案。

## 采集链路

```text
AudioRecord
    → 16 kHz / mono / PCM 16-bit
    → 每帧 20 ms（320 samples）
    → JNI/NDK WebRTC VAD
    → SpeechSegmenter
    → PCM range 重建
    → MediaCodec/MediaMuxer 或 WAV
```

`AudioRecord` 适合读取原始音频帧，但实际采样配置要检查设备返回值，不能假设所有麦克风都严格按请求的采样率工作。[AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord)

## 为什么优先 NDK WebRTC VAD

对于“删除长静音”，WebRTC VAD 已经足够轻量：

- 不需要下载神经网络模型；
- NDK 二进制体积小；
- 10/20/30 ms 帧接口简单；
- CPU 负担低；
- Android 和 VPS 可以复用同一类 VAD 行为。

Java/Kotlin 层只负责采集和状态机；音频帧判断放在 C/C++，避免每帧产生大量对象。

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

## 结论

当前项目应以远程处理为主：

```text
Android：录音 + 上传 + 播放
VPS：VAD + 裁剪 + 编码
```

本地 VAD 只作为未来的 `offline` 或 `realtime` mode，不与远程模式同时默认启用。
