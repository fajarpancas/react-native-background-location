const {
  withDangerousMod,
  withPlugins,
} = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

function withBackgroundLocationPod(config) {
  return withDangerousMod(config, [
    'ios',
    async (config) => {
      const podfilePath = path.join(
        config.modRequest.platformProjectRoot,
        'Podfile',
      );

      if (!fs.existsSync(podfilePath)) {
        return config;
      }

      let podfile = fs.readFileSync(podfilePath, 'utf-8');

      const podLine =
        "\n  pod 'VspiritBackgroundLocation', :path => '../node_modules/@fajarpancas/react-native-background-location/ios'\n";

      if (!podfile.includes('VspiritBackgroundLocation')) {
        podfile = podfile.replace(
          /(use_expo_modules!.*?\n)/,
          `$1${podLine}`,
        );
        fs.writeFileSync(podfilePath, podfile);
      }

      return config;
    },
  ]);
}

module.exports = function withBackgroundLocation(config) {
  config = withBackgroundLocationPod(config);
  return config;
};
