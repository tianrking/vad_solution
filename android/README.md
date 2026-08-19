# Android 方案

## 结论

对于当前 VPS 后端，Android 最优的默认方案是：

```text
MediaRecorder 录 M4A/AAC
    → OkHttp multipart 上传
    → VPS CPU 跑 WebRTC VAD + FFmpeg
    → 流式保存返回的 M4A/WAV
```

Android 端不需要再跑一遍 VAD。这样避免了：

- 手机上的 NDK/ONNX 依赖；
- 本地 VAD 和服务端 VAD 参数不一致；
- 额外的 CPU、电量和 App 体积；
- 本地裁剪后又上传、服务端再次解码的问题。

Android 官方的 `MediaRecorder` 支持配置音频源、输出格式、AAC 编码器和输出文件；录音需要运行时申请 `RECORD_AUDIO` 权限。[MediaRecorder API](https://developer.android.com/reference/android/media/MediaRecorder)

## 依赖

在现有 Android App 的 module 中加入主流依赖：

```kotlin
implementation("com.squareup.okhttp3:okhttp:<version>")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<version>")
```

不要把 API Key 写进公开发布的 APK。当前 VPS 的 `X-API-Key` 适合内测、私有 App 或已有用户鉴权的环境；公开 App 应由自己的登录态换取短期上传 token。

## 权限

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

`RECORD_AUDIO` 必须在运行时申请；`INTERNET` 是普通安装权限。生产环境只使用 HTTPS，不开启 cleartext traffic。

## 录音

本目录的 [`VoiceRecorder.kt`](remote-trim-client/src/main/kotlin/com/tianrking/vadsolution/VoiceRecorder.kt) 是一个不绑定 UI 的录音会话类：

```kotlin
val recorder = VoiceRecorder(context)
val inputFile = recorder.start()

// 用户点击停止后
val completedFile = recorder.stop()
```

录音文件写入 `cacheDir`，不直接写公共存储。录音结束后上传；取消或异常时删除临时文件。

不要在主线程调用 `stop()` 后的上传和文件 I/O。上传放到 `lifecycleScope`/`Dispatchers.IO` 中。

## 上传和下载

[`RemoteTrimClient.kt`](remote-trim-client/src/main/kotlin/com/tianrking/vadsolution/RemoteTrimClient.kt) 使用 OkHttp：

```kotlin
val api = RemoteTrimClient(
    baseUrl = "https://audio.example.com",
    apiKey = sessionUploadToken,
)

val result = api.trim(
    input = completedFile,
    output = File(cacheDir, "trimmed.m4a"),
    options = TrimOptions(
        minSilenceMs = 800,
        keepSilenceMs = 250,
        paddingMs = 80,
    ),
)

// result.outputFile 可以交给 MediaPlayer、ExoPlayer 或上传到业务层
```

客户端采用流式响应写文件，不把处理后的整段音频加载到内存。请求使用 `multipart/form-data`，不使用 Base64。

## 生命周期和后台上传

短录音、前台操作：

```text
点击停止 → Dispatchers.IO 上传 → 返回后播放
```

需要离开页面后仍继续上传时使用 WorkManager。Android 官方把 WorkManager 作为持久后台工作的推荐库；单个 Worker 有执行时长限制，因此特别长的音频要采用“上传完成后轮询服务端任务”的异步协议，或者使用前台服务。[WorkManager API](https://developer.android.com/reference/androidx/work/WorkManager.html)

## 错误处理

```text
401：API Key/上传 token 无效
413：文件过大
422：音频损坏、没有检测到语音、参数不合法
429：服务器并发队列已满
5xx：服务器处理或编码失败，可重试
```

重试只对网络错误、408、429 和部分 5xx 做指数退避；不要对 401、413、422 盲目重试。

## 测试矩阵

至少覆盖：

- Android 物理机而不是只测模拟器；
- 麦克风权限首次允许、拒绝、永久拒绝；
- 普通麦克风和蓝牙麦克风；
- 10 秒、10 分钟、接近服务端上限的录音；
- 无语音、全是静音、短停顿、长停顿；
- Wi-Fi、移动网络、中途断网；
- 服务端 401、413、422、429；
- 返回的 M4A 在 Android 播放器中可解码。

## 什么时候才用本地 VAD

如果要做到“边录边静音裁剪”、断网可用、减少上传流量，才启用 [`local-vad.md`](local-vad.md) 中的 `AudioRecord + NDK WebRTC VAD` 路径。否则默认使用服务端处理即可。
