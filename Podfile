platform :osx, '13.0'

def vlckit
  use_frameworks!
  pod 'VLCKit', '~> 3.6'
end

target 'TorVideoPlayer' do
  vlckit
end

# Experimental build with TorrServer-LT as the torrent engine.
target 'FSMediaPlayerLT' do
  vlckit
end

post_install do |installer|
  installer.pods_project.targets.each do |target|
    target.build_configurations.each do |config|
      config.build_settings['MACOSX_DEPLOYMENT_TARGET'] = '13.0'
    end
  end
end
