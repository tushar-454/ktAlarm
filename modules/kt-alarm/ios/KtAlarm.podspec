Pod::Spec.new do |s|
  s.name           = 'KtAlarm'
  s.version        = '1.0.0'
  s.summary        = 'Native alarm engine for ktAlerm'
  s.description    = 'Schedules real system alarms with AlarmKit (iOS 26+).'
  s.author         = ''
  s.homepage       = 'https://docs.expo.dev/modules/'
  s.platforms      = { :ios => '16.4' }
  s.source         = { git: '' }
  s.static_framework = true
  s.swift_version  = '5.9'

  s.dependency 'ExpoModulesCore'
  # AlarmKit only exists on iOS 26+; weak-link so older iOS versions still launch.
  s.weak_frameworks = 'AlarmKit'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  s.source_files = "**/*.{h,m,swift}"
  s.exclude_files = 'Tests/**/*'
  # Reuse the original audio loops without changing Android's resources.
  s.resource_bundles = { 'KtAlarmMedia' => ['../android/src/main/res/raw/*.wav'] }
  s.frameworks = 'AVFoundation', 'CoreMotion', 'Vision', 'MediaPlayer', 'AppIntents', 'SwiftUI'
end
