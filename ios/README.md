# iOS 方案

Android 和 iOS 共用同一套 HTTP 契约：

```text
POST /v1/audio/remove-long-silence
multipart: file + processing parameters
response: audio/mp4 或 audio/wav
```

后续 iOS 默认实现建议：

```text
AVAudioRecorder 录 M4A
    → URLSession multipart 上传
    → 下载返回音频到临时文件
    → AVAudioPlayer/AVAudioFile 播放或继续业务处理
```

如果需要实时音频帧，则切换为 `AVAudioEngine.inputNode` 的 tap；如果需要本地 VAD，再将 PCM 帧送入共享的 WebRTC VAD 核心。iOS 不应为了已经在 VPS 上完成的 VAD 再默认跑一次本地模型。

本目录先固定跨平台接口和职责边界，具体 Swift client 将沿用 Android 的：流式上传、流式落盘、临时文件原子替换、HTTPS 和短期 token。
