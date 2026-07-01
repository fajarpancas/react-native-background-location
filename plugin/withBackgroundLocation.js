const { withPodfileProperties } = require('@expo/config-plugins');

module.exports = function withBackgroundLocation(config) {
  config = withPodfileProperties(config, (config) => {
    config.modResults['EXPO_USE_COMMUNITY_AUTOLINKING'] = '1';
    return config;
  });
  return config;
};
