Pod::Spec.new do |s|
  s.name           = 'KmpBridge'
  s.version        = '1.0.0'
  s.summary        = 'A sample project summary'
  s.description    = 'A sample project description'
  s.author         = ''
  s.homepage       = 'https://docs.expo.dev/modules/'
  s.platforms      = {
    :ios => '16.4',
    :tvos => '16.4'
  }
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # KMP shared module, built as `Shared.xcframework`
  # (cd shared && ./gradlew assembleSharedReleaseXCFramework, then copied into ios/Frameworks).
  s.vendored_frameworks = 'Frameworks/Shared.xcframework'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  # Compile the module's own Swift/ObjC sources, but not the vendored framework's headers.
  s.source_files = "*.{h,m,mm,swift,hpp,cpp}"
end
