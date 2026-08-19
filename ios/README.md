# VadCutIOS 0.1.0

纯 iOS、离线、面向长录音的语音/静音裁剪 SDK。它与仓库中的 Android
`vadcut-android` 使用同一份 Silero VAD 模型和同一套默认阈值，但媒体处理使用
Apple 原生 AVFoundation。

```text
文件 URL
  → AVAssetReader 流式解码为 16 kHz mono Float32 PCM
  → Silero VAD 或能量非静音检测
  → 生成保留/删除区间
  → AVMutableComposition + AVAssetExportSession
  → AAC/M4A 输出
```

音频不会整体加载进内存。SDK 仅保存固定大小 PCM buffer、Silero recurrent
state 和最终时间区间。

## 能力

- iOS 15.1+
- Swift async/await、可取消任务和 Objective-C callback API
- Silero `SPEECH` 模式，以及无模型的 `NON_SILENCE` 能量模式
- 文件 URL 输入；支持格式取决于设备上的 AVFoundation 解码能力
- AAC/M4A 输出
- 进度、取消、无语音策略、保留/删除区间和切口淡入淡出
- security-scoped URL 访问
- 临时文件完成后再替换目标文件
- Swift/Objective-C 接入示例
- Apache-2.0 SDK；Silero 和 ONNX Runtime 第三方声明

## CocoaPods 接入

ONNX Runtime 官方 iOS artifact 通过 CocoaPods 提供，因此 0.1.0 以 CocoaPods
作为经过 CI 验证的正式依赖方式：

```ruby
platform :ios, '15.1'

target 'YourApp' do
  use_frameworks! :linkage => :static
  pod 'VadCutIOS', :git => 'https://github.com/tianrking/vad_solution.git', :branch => 'main'
end
```

私有仓库需要开发机已经拥有对应 GitHub 访问权限。发布 tag 后应把 `:branch`
改为固定 tag。

## Swift

```swift
import VadCutIOS

let request = TrimRequest(
    inputURL: inputURL,
    outputURL: outputURL,
    config: .preset(.voiceMemo)
)

let result = try await VadCut.trim(request) { progress in
    print(progress.phase, progress.percent)
}

print(result.outputURL)
print(result.removedDurationMilliseconds)
```

不使用 async/await 时：

```swift
let task = VadCut.start(request, onProgress: { progress in
    print(progress.percent)
}) { result in
    print(result)
}

task.cancel()
```

## Objective-C

```objc
#import <VadCutIOS/VadCutIOS-Swift.h>

VDTrimConfiguration *config = [VDTrimConfiguration new];
config.minimumSilenceDurationMilliseconds = 700;

TrimTask *task = [VadCutObjC trimWithInputURL:inputURL
                                    outputURL:outputURL
                                 configuration:config
                                       progress:^(NSInteger percent, NSString *phase) {
  NSLog(@"%@ %ld", phase, (long)percent);
} completion:^(VDTrimResult *result, NSError *error) {
  if (error != nil) {
    NSLog(@"%@", error);
  } else {
    NSLog(@"%@", result.outputURL);
  }
}];
```

生成的 Objective-C selector 以 Xcode 产生的 `VadCutIOS-Swift.h` 为准；CI 会编译
Swift framework 和测试，正式发布前仍应在一个真实 Objective-C 宿主 App 中做
一次 consumer build。

## 配置预设

- `conservative`：只删除较长静音，保留更多自然停顿
- `voiceMemo`：默认语音备忘录/访谈参数
- `aggressive`：更积极地删除停顿

`TrimConfig.mode = .speech` 使用 Silero VAD；`.nonSilence` 只按能量判断，适合
环境录音但会保留音乐、键盘和其他明显声音。

## 本地开发（macOS）

```bash
cd ios
brew install xcodegen
xcodegen generate
pod install
xcodebuild test \
  -workspace VadCutIOS.xcworkspace \
  -scheme VadCutIOS \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  CODE_SIGNING_ALLOWED=NO
```

Windows 可以编辑全部源码，但 AVFoundation、CocoaPods、Xcode simulator 和签名
必须在 macOS 上运行。本仓库的 GitHub Actions 使用 macOS runner 执行这些检查。

## 已知边界

- 输出会重新编码为 AAC/M4A，不是无损码流复制。
- App 退到后台后，iOS 不保证几小时 CPU 任务持续运行；宿主必须处理取消、恢复或
  使用合适的 BackgroundTasks 策略。
- SDK 不录音；实时 `AVAudioEngine`/麦克风模式应作为后续独立模块。
- 当前依赖完整 ONNX Runtime；后续可以为 Silero 制作 reduced-operator runtime
  以降低 App 包体。
- GitHub Actions 能证明模拟器编译、测试和媒体导出链路，但不能替代真实 iPhone
  的功耗、温度、后台行为和设备编解码兼容测试。

## 许可证

- SDK：Apache License 2.0，见 [LICENSE](LICENSE)
- 第三方：见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
