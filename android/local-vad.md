# 纯 Android 本地 VAD

本方案完全离线，不走网络：

```text
AudioRecord
  → 16 kHz / mono / PCM16
  → VadEngine
  → SpeechSegmenter
  → sample ranges
  → WAV 或 Android MediaCodec/MediaMuxer
```

## 目前使用的后端

首版 `vad-sdk` 使用纯 Java 自适应能量 VAD：

- 不需要模型；
- 不需要 NDK 和 `.so`；
- 不需要 FFmpeg；
- CPU 和 APK 体积都很低；
- 适合删除长静音，而不是语音识别。

它通过噪声底、最短语音时长、最长允许静音间隔和前后保留边界来处理短停顿。不要把每个 `false` 帧直接删除。

## SDK 入口

主要类型：

```text
VadSdk
  ├── createRecorder(config, pcmFile)
  │     └── AndroidVadRecorder
  │           └── LocalVadRecording
  └── createDefault(config)
        └── PcmVadProcessor
              ├── VadEngine
              └── SpeechSegmenter
```

录音权限由宿主 App 申请：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

录音、停止和 `WavExporter.export()` 都放在后台线程执行。

## 参数建议

```text
frameDurationMs = 20
minSpeechMs = 80~120
minSilenceMs = 600~800
keepSilenceMs = 200~300
```

先用真实手机录音调参，再固定产品默认值。不同手机的麦克风增益、自动增益和环境噪声会影响能量阈值。

## 后续增强边界

如果真实设备测试发现噪声环境误判较多，再增加单独的 `vad-sdk-webrtc` AAR：

```text
vad-sdk-core
  Java API + segmenter + recorder

vad-sdk-webrtc
  NDK WebRTC VAD + arm64-v8a
```

业务层不需要改变。ONNX 只在实测确实需要更强噪声鲁棒性时作为可选 provider，不进入核心 SDK。
