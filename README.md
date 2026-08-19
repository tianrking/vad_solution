# vad_solution

跨平台“长静音裁剪”方案，拆成三个相互独立的目录：

```text
vps/       CPU 后端：VAD + PCM 重建 + M4A/WAV 输出
android/   完整 Android 离线 SDK：Silero VAD + Media3 + M4A 输出
ios/       完整 iOS 离线 SDK：Silero VAD + AVFoundation + M4A 输出
```

## Android 推荐主链路

Android 默认使用 `android/` 中的纯本地 SDK，不上传录音：

```text
Android Uri（设备支持的音频格式）
    → MediaExtractor / MediaCodec 流式解码
    → Silero VAD 检测语音区间
    → 删除长静音，保留短停顿和语音边界
    → Media3 导出 AAC/M4A
```

SDK 支持 Kotlin 和 Java，包含进度、取消、保留/删除区间报告、Kotlin/Java 示例和 Maven 发布配置。模型与处理均在设备端运行，不依赖网络或 VPS。

## VPS 可选链路

需要让 Android、iOS 或其他客户端统一由服务器处理时，可以使用 `vps/`：

```text
Android / iOS
    → 录音文件（M4A/AAC）
    → multipart 上传到 VPS
    → VPS CPU 跑 VAD
    → 删除长静音，保留短停顿
    → 返回处理后的 M4A/WAV
```

VPS 方案适合希望统一模型和阈值、降低客户端包体或跨平台复用同一处理结果的场景；它不是 Android SDK 的必需依赖。

## 目录

- [VPS 服务](vps/README.md)
- [Android 离线 VAD SDK](android/README.md)
- [iOS 离线 VAD SDK](ios/README.md)

## API 契约

```http
POST /v1/audio/remove-long-silence
Content-Type: multipart/form-data
X-API-Key: <private-api-key>
```

字段：

```text
file              音频文件
output_format     m4a 或 wav
frame_ms          10、20 或 30
aggressiveness    0 到 3
min_silence_ms    长静音阈值
keep_silence_ms   长静音两端保留的停顿
padding_ms        语音前后保护区间
min_speech_ms     最短语音区间
```

成功响应是音频二进制，不是 Base64 JSON：

```http
200 OK
Content-Type: audio/mp4
```

## 当前状态

- VPS 版本已经实现并在本地真实音频和 HTTP 接口上验证。
- Android 已实现完整本地离线 SDK，支持 Silero VAD、能量非静音模式、Android `Uri` 输入和 AAC/M4A 导出；正式投产前仍需完成真机矩阵与长录音压力测试。
- iOS 已实现与 Android 对齐的本地离线 SDK，使用同一 Silero 模型，并由 macOS GitHub Actions 执行 Xcode 模拟器构建、推理和 M4A 端到端测试；正式投产前仍需真实 iPhone 验证。
