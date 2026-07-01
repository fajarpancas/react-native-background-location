import { Platform, NativeModules, NativeEventEmitter, PermissionsAndroid, EmitterSubscription } from 'react-native';

const { BackgroundLocationModule, BackgroundLocation } = NativeModules;

const NativeBackgroundLocation = Platform.select({
  android: BackgroundLocationModule,
  ios: BackgroundLocation,
});

const createEventEmitter = (): NativeEventEmitter | null => {
  if (!NativeBackgroundLocation) return null;
  try {
    return new NativeEventEmitter(NativeBackgroundLocation);
  } catch {
    return null;
  }
};

let eventEmitter: NativeEventEmitter | null;

export const backgroundLocationEvents = {
  addListener: (eventType: string, listener: (...args: any[]) => any, context?: any): EmitterSubscription => {
    if (!eventEmitter) eventEmitter = createEventEmitter();
    if (eventEmitter) return eventEmitter.addListener(eventType, listener, context);
    return { remove: () => {} } as EmitterSubscription;
  },
  removeAllListeners: (eventType: string) => {
    if (!eventEmitter) eventEmitter = createEventEmitter();
    eventEmitter?.removeAllListeners(eventType);
  },
  listeners: (eventType: string) => {
    if (!eventEmitter) eventEmitter = createEventEmitter();
    return eventEmitter?.listeners(eventType) ?? [];
  },
  emit: (eventType: string, ...params: any[]) => {
    if (!eventEmitter) eventEmitter = createEventEmitter();
    eventEmitter?.emit(eventType, ...params);
  },
  removeSubscription: (subscription: EmitterSubscription) => {
    if (!eventEmitter) eventEmitter = createEventEmitter();
    eventEmitter?.removeSubscription(subscription);
  },
};

export const LocationUpdatedEvent = 'LocationUpdated';

export interface LocationEvent {
  latitude: number;
  longitude: number;
  timestamp: number;
}

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

export const requestPermissions = async (): Promise<boolean> => {
  if (Platform.OS === 'android') {
    const apiLevel = Platform.Version as number;
    const grants = await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION,
      ...(apiLevel >= 30 ? [PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION] : []),
    ]);

    const fine = grants[PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION] === PermissionsAndroid.RESULTS.GRANTED;
    const coarse = grants[PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION] === PermissionsAndroid.RESULTS.GRANTED;
    const background = apiLevel < 30 || grants[PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION] === PermissionsAndroid.RESULTS.GRANTED;

    return (fine || coarse) && background;
  }

  return NativeBackgroundLocation?.requestPermissions();
};

export const checkPermissions = async (): Promise<boolean> => {
  if (Platform.OS === 'android') {
    const fine = await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION);
    const background = Platform.Version < 30 ||
      await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION);
    return fine && background;
  }

  return NativeBackgroundLocation?.checkPermissions();
};
