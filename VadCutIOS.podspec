Pod::Spec.new do |spec|
  spec.name = 'VadCutIOS'
  spec.version = '0.2.0'
  spec.summary = 'Offline long-recording speech and silence trimming for iOS.'
  spec.description = <<-DESC
    VadCutIOS streams audio through AVFoundation, detects speech locally with
    Silero VAD and ONNX Runtime, supports caller-supplied keep/remove ranges,
    and exports the retained ranges as AAC/M4A.
  DESC
  spec.homepage = 'https://github.com/tianrking/vad_solution'
  spec.license = { :type => 'Apache-2.0', :file => 'ios/LICENSE' }
  spec.author = { 'tianrking' => '10758833+tianrking@users.noreply.github.com' }
  spec.source = { :git => 'https://github.com/tianrking/vad_solution.git', :tag => spec.version.to_s }

  spec.ios.deployment_target = '15.1'
  spec.swift_version = '5.10'
  spec.static_framework = true
  spec.source_files = 'ios/Sources/VadCutIOS/**/*.swift'
  spec.resource_bundles = {
    'VadCutIOSResources' => ['ios/Sources/VadCutIOS/Resources/**/*']
  }
  spec.frameworks = 'AVFoundation', 'CoreMedia', 'CryptoKit'
  spec.dependency 'onnxruntime-objc', '1.28.0'

  spec.test_spec 'Tests' do |test_spec|
    test_spec.source_files = 'ios/Tests/VadCutIOSTests/**/*.{swift,m}'
    test_spec.resources = 'ios/Tests/Fixtures/**/*'
  end
end
