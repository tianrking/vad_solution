# vad_solution

跨平台“长静音裁剪”方案，拆成三个相互独立的目录：

```text
vps/       CPU 后端：VAD + PCM 重建 + M4A/WAV 输出
android/   Android 客户端：录音、上传、下载处理结果
ios/       iOS 客户端接口约定和实现计划
```

## 推荐的主链路

```text
Android / iOS
    → 录音文件（M4A/AAC）
    → multipart 上传到 VPS
    → VPS CPU 跑 VAD
    → 删除长静音，保留短停顿
    → 返回处理后的 M4A/WAV
```

移动端默认不重复跑 VAD。这样模型、阈值和音频切割结果由服务器统一控制，手机只承担录音、网络和播放，功耗和包体都更低。

## 目录

- [VPS 服务](vps/README.md)
- [Android 集成](android/README.md)
- [Android 本地 VAD 备选方案](android/local-vad.md)
- [iOS 方案](ios/README.md)

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
- Android 现在实现服务端处理客户端；本地 VAD 作为独立备选，不混入默认路径。
- iOS 保持同一 HTTP 契约，后续使用 AVAudioRecorder/AVAudioEngine + URLSession/原生上传实现。
