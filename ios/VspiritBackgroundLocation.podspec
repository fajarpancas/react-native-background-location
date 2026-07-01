require "json"

package = JSON.parse(File.read(File.join(__dir__, "..", "package.json")))

Pod::Spec.new do |s|
  s.name         = "VspiritBackgroundLocation"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = "https://github.com/fajarpancas/vspirit-react-native-background-location"
  s.license      = package["license"]
  s.authors      = package["author"]
  s.platforms    = { :ios => "12.0" }
  s.source       = { :git => "https://github.com/fajarpancas/vspirit-react-native-background-location.git", :tag => "#{s.version}" }

  s.source_files = "**/*.{h,m}"
  s.frameworks   = "CoreLocation", "Network", "UserNotifications"

  s.dependency "React-Core"
end
