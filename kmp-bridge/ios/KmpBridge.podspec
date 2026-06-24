Pod::Spec.new do |s|
  s.name           = 'KmpBridge'
  s.version        = '1.0.0'
  s.summary        = 'Expo native module bridging the KMP shared module to React Native'
  s.author         = ''
  s.homepage       = 'https://docs.expo.dev/modules/'
  s.platforms      = {
    :ios => '16.4',
    :tvos => '16.4'
  }
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.vendored_frameworks = 'Frameworks/Shared.xcframework'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  s.source_files = "*.{h,m,mm,swift,hpp,cpp}"
end
