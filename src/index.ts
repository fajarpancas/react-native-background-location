import { Platform, NativeModules, NativeEventEmitter } from 'react-native';

const { BackgroundLocationModule, BackgroundLocation } = NativeModules;

const NativeBackgroundLocation = Platform.select({
  android: BackgroundLocationModule,
  ios: BackgroundLocation,
});

export const backgroundLocationEvents = new NativeEventEmitter(NativeBackgroundLocation);

export const BACKGROUND_LOCATION_EVENT = 'LocationUpdated';

export const startTracking = (
  baseUrl: string,
  header: string,
  params: Record<string, unknown>,
): void => {
  NativeBackgroundLocation?.startTracking(baseUrl, header, params);
};

export const stopTracking = (): void => {
  NativeBackgroundLocation?.stopTracking();
};

export const openBatteryOptimizationSettings = (): void => {
  if (Platform.OS === 'android') {
    BackgroundLocationModule?.openBatteryOptimizationSettings();
  }
};
