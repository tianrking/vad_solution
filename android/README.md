# VadCut Android SDK

完全在 Android 本地运行的长录音语音/静音裁剪 SDK。输入任意 Android `Uri`，SDK 流式解码、检测、拼接并输出 AAC/M4A；不上传音频，不需要 FFmpeg，也不需要存储权限。

当前版本：`0.2.0`

## 能力与边界

- `SPEECH`：使用随包提供的 Silero VAD v6.2.1，只保留人声，适合会议、采访、语音备忘录。
- `NON_SILENCE`：使用 RMS 能量检测，保留人声、音乐和其他非静音声音。
- 手动区间：调用方可传入原录音时间轴上的“删除区间”或“保留区间”，SDK 校验、排序、合并后导出。
- 长音频采用两遍流式处理。PCM 缓冲区大小固定，不把整段录音载入内存；区间元数据随剪切点数量增长。
- 输出固定为 AAC 音轨的 M4A 文件；有视频轨的输入会只导出音频。
- Android 7.0+（API 24），支持 `arm64-v8a`、`armeabi-v7a`，并提供 `x86_64` 模拟器支持。
- 支持 16 KB 内存页：当前依赖中的所有 ARM/x86_64 ELF LOAD 段均为 `0x4000` 对齐，示例 APK 也通过 `zipalign -P 16`。
- 输入格式取决于设备的 `MediaExtractor`/`MediaCodec`。M4A/AAC、MP3、WAV、FLAC、Ogg 等常见格式通常可用，但厂商设备的编解码器集合可能不同。

## 推荐集成：本地 Maven 仓库

将交付包 `vadcut-maven-0.2.0.zip` 解压到项目目录，例如 `third_party/vadcut-maven`，然后在 `settings.gradle.kts` 中添加：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("$rootDir/third_party/vadcut-maven") }
    }
}
```

应用模块添加一行依赖：

```kotlin
dependencies {
    implementation("com.vadcut:vadcut-android:0.2.0")
}
```

Maven 方式会自动带入 Media3、ONNX Runtime 和 Kotlin Coroutines 的传递依赖。SDK 自身不声明任何 Android 权限。

宿主项目需在 `gradle.properties` 启用 AndroidX：

```properties
android.useAndroidX=true
```

如果只能使用裸 AAR，把 `vadcut-release.aar` 放进 `app/libs` 后，还必须显式加入这些运行时依赖：

```kotlin
implementation(files("libs/vadcut-release.aar"))
implementation("androidx.media3:media3-common:1.11.0")
implementation("androidx.media3:media3-effect:1.11.0")
implementation("androidx.media3:media3-transformer:1.11.0")
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
```

## Kotlin

```kotlin
val request = TrimRequest.Builder(inputUri, outputUri)
    .setConfig(TrimConfig.fromPreset(TrimPreset.VOICE_MEMO))
    .build()

val task = VadCut.with(context).trimAsync(request, object : TrimListener {
    override fun onProgress(progress: TrimProgress) {
        progressBar.progress = progress.percent
    }

    override fun onSuccess(result: TrimResult) {
        // result.keptRanges / removedRanges 可用于日志、预览或审计。
        println("removed ${result.removedDurationMs} ms")
    }

    override fun onError(error: TrimException) {
        // 若 outputUri 是专为本任务新建的文档，应在这里删除它。
        println("${error.code}: ${error.message}")
    }

    override fun onCancelled() {
        // 删除本任务新建的 outputUri，避免留下 0 字节或部分文件。
    }
})

// 需要时取消：
task.cancel()
```

Kotlin 协程调用也可直接使用：

```kotlin
val result = VadCut.with(context).trim(request) { progress ->
    // 回调固定在主线程。
}
```

## Java

```java
TrimConfig config = TrimConfig.fromPreset(TrimPreset.VOICE_MEMO);
TrimRequest request = new TrimRequest.Builder(inputUri, outputUri)
        .setConfig(config)
        .build();

TrimTask task = VadCut.with(context).trimAsync(request, new TrimListener() {
    @Override public void onProgress(TrimProgress progress) { }
    @Override public void onSuccess(TrimResult result) { }
    @Override public void onError(TrimException error) { /* cleanup newly-created outputUri */ }
    @Override public void onCancelled() { /* cleanup newly-created outputUri */ }
});
```

完整的可运行示例位于 `sample-kotlin` 和 `sample-java`。

## 自定义切割时间点

时间点始终基于**原始输入音频**，单位为毫秒；区间是半开区间 `[startTimeMs, endTimeMs)`。两种模式互斥：

- `setRemovedRanges(...)`：删除给定区间，保留其余内容。
- `setKeptRanges(...)`：只保留给定区间，删除其余内容。

Kotlin 删除指定区间：

```kotlin
val request = TrimRequest.Builder(inputUri, outputUri)
    .setRemovedRanges(
        AudioRange(5_200, 8_600),
        AudioRange(15_300, 21_100),
    )
    .setConfig(TrimConfig.Builder().setFadeDurationMs(8).build())
    .build()
```

Kotlin 只保留指定区间：

```kotlin
val request = TrimRequest.Builder(inputUri, outputUri)
    .setKeptRanges(
        listOf(
            AudioRange(1_000, 4_000),
            AudioRange(8_000, 12_500),
        )
    )
    .build()
```

Java：

```java
List<AudioRange> removed = Arrays.asList(
        new AudioRange(5_200L, 8_600L),
        new AudioRange(15_300L, 21_100L)
);
TrimRequest request = new TrimRequest.Builder(inputUri, outputUri)
        .setManualTrimPlan(ManualTrimPlan.removeRanges(removed))
        .build();
```

手动区间规则：

1. 区间可乱序、重叠或首尾相接；SDK 会排序并合并。
2. 区间必须为正长度，且不能超过解码得到的原音频时长。
3. 不允许删除整段音频；这种请求返回 `INVALID_TIME_RANGES`，不会生成一个伪装成功的空音频。
4. 手动模式不会加载或运行 Silero/能量检测器；`TrimConfig` 中仅 `fadeDurationMs` 影响切点，其余检测参数被忽略。
5. `TrimResult.keptRanges` 和 `removedRanges` 返回最终规范化后、真正用于导出的原时间轴区间。
6. 在同一个 Builder 上多次设置手动模式时，最后一次设置生效；调用 `useAutomaticDetection()` 可恢复自动 VAD/能量检测。

编译过的调用示例分别位于：

```text
sample-kotlin/.../ManualTrimExamples.kt
sample-java/.../ManualTrimExamples.java
```

## 参数选择

推荐先使用预设：

- `CONSERVATIVE`：静音至少 1.2 秒才切，保留更宽的人声边缘。
- `VOICE_MEMO`：默认；静音至少 700 ms 才切，前置 180 ms、后置 250 ms。
- `AGGRESSIVE`：静音约 350 ms 即切，成品更紧凑。

自定义示例：

```kotlin
val config = TrimConfig.Builder()
    .setMode(TrimMode.SPEECH)
    .setMinimumSilenceDurationMs(800)
    .setPaddingBeforeMs(200)
    .setPaddingAfterMs(300)
    .setFadeDurationMs(8)
    .setNoSpeechPolicy(NoSpeechPolicy.KEEP_ORIGINAL)
    .build()
```

默认使用双阈值滞回（开始 `0.55`、结束 `0.35`）以减少边界抖动；每个保留区间的两端应用短淡入淡出，避免切点爆音。未检测到活动时默认保留整段并在 `warnings` 中返回 `NO_ACTIVITY_DETECTED_KEPT_ORIGINAL`，不会意外生成空文件。

## 长任务与生命周期

SDK 不擅自创建 Service 或通知。前台页面内可直接使用 `TrimTask`；需要在锁屏、切后台或进程重启后继续的长任务，应由宿主应用放入 WorkManager 或 Foreground Service，并持久化 SAF URI 权限。不要把输入和输出设为同一个 URI。

`ACTION_CREATE_DOCUMENT` 会在处理开始前先创建目标。取消或失败时，宿主只应删除“专为当前任务新建”的输出 Uri；Document Uri 优先使用 `DocumentsContract.deleteDocument()`，普通 `ContentResolver.delete()` 在部分厂商 Downloads Provider 上不会真正删除。Kotlin/Java Example 已包含并真机验证这套清理逻辑。

处理需要两遍完整读取：自动模式第一遍检测区间；手动模式第一遍只解码并取得准确时长，不加载 Silero；第二遍删除区间并只编码一次 AAC。实际速度与耗电取决于 SoC、输入编解码器和录音时长。缓存目录必须容纳一份完整的临时 M4A；写入最终 URI 后临时文件会删除。

## 体积成本

标准包使用完整 ONNX Runtime，优先追求兼容性和可靠性。当前通用调试 APK 约 91 MB，因为它同时包含三个 ABI；Play App Bundle 或 ABI split 只向设备交付一个 ABI。模型本身约 2.3 MB，原生运行库按 ABI 约 20–35 MB。若最终产品对包体极敏感，可在后续版本使用仅包含 Silero 所需算子的自定义 reduced-operator ONNX Runtime 构建，公开 API 无需变化。

## 为什么第一版不使用 FFmpeg

Android 平台已提供硬件/系统解码器，Media3 Transformer 负责稳定的导出、取消和进度；再打入 FFmpeg 会增加包体、Native ABI 维护和许可证组合成本。对于“无损分段复制”“极少见容器格式”或完全一致的跨设备软件编码，才值得增加可选 FFmpeg 后端。

## 构建与验证

要求 JDK 17 和 Android SDK 36：

```bash
./gradlew :vadcut:testDebugUnitTest \
  :vadcut:assembleDebugAndroidTest \
  :vadcut:assembleRelease \
  :sample-kotlin:assembleDebug \
  :sample-java:assembleDebug
```

验证 APK 中所有 ELF 的 16 KB 段对齐：

```bash
python scripts/verify_elf_alignment.py sample-kotlin/build/outputs/apk/debug/sample-kotlin-debug.apk
```

连接真机或启动模拟器后，可运行真实 WAV 的自动 VAD、手动删除区间、手动保留区间三条 Media3 → M4A 全链路设备测试：

```bash
./gradlew :vadcut:connectedDebugAndroidTest
```

本仓库源码采用 Apache-2.0。第三方组件和模型见 `THIRD_PARTY_NOTICES.md`。
