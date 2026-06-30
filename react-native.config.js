module.exports = {
  dependency: {
    platforms: {
      android: {
        packageInstance: 'new BackgroundLocationPackage()',
      },
      ios: {
        podspecPath: 'ios/VspiritBackgroundLocation.podspec',
      },
    },
  },
};
