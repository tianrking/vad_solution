# Changelog

## 0.2.0 - 2026-08-20

- 新增原始时间轴手动删除区间和手动保留区间。
- 手动模式只流式解码时长，不加载 Silero 模型或创建 ONNX Runtime session。
- Objective-C bridge 新增 `VDAudioRange`、`VDManualTrimPlan`、完整结果区间和 warnings。
- 新增稳定的 `NSError` domain、数字错误码与字符串错误码。
- 新增手动区间单测、Swift/Objective-C API 测试及 M4A 端到端测试。
- 新增真人普通话 MP3 + 4 秒数字静音的默认 Silero 模拟器回归测试。
- 时长统一以实际解码 PCM 样本数为编辑时间轴，避免容器 padding 影响手动尾点。

## 0.1.0

- 首个 iOS 离线 SDK。
- 支持 Silero VAD、RMS 能量模式、AVFoundation 流式解码和 AAC/M4A 输出。
- 支持 Swift async/callback、取消、进度、区间结果和 Objective-C callback。
