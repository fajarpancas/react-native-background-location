# @fajarpancas/react-native-background-location

React Native native module for background location tracking on iOS and Android. Periodically sends location data to a configured API endpoint, with built-in retry on network failure.

## Features

- Foreground service (Android) / Background location updates (iOS) with persistent notification
- Periodic location polling (every 5s) with minimum distance filter (3m)
- Auto-sends location to your API via HTTP POST
- Built-in retry for failed API calls when network is restored
- Battery optimization settings shortcut (Android)
- Service auto-restart on unexpected death (Android, up to 5 attempts with exponential backoff)
- Wake lock and Wi-Fi lock (Android) for reliable operation
- JS events on each location update

## Requirements

- React Native >= 0.70
- iOS 12.0+
- Android minSdk 21+, targetSdk 33+

## Installation

```bash
npm install @fajarpancas/react-native-background-location
```

### iOS

```bash
cd ios && pod install
```

Add keys to `ios/App/Info.plist`:

```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app needs your location to track in the background</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>This app needs your location to track</string>
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
</array>
```

### Android

Add permissions to `android/app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

## API

---

### `startTracking(baseUrl, header, params)`

Starts background location tracking.

| Param     | Type     | Required | Description |
|-----------|----------|----------|-------------|
| `baseUrl` | `string` | yes      | API endpoint to POST location data (e.g. `https://api.example.com/tracking`) |
| `header`  | `string` | yes      | Full value for the `Authorization` header (e.g. `Bearer eyJhbG...`) |
| `params`  | `object` | no       | Optional additional params merged into the payload |

The payload sent to your API:

```json
{
  "latitude": "-6.123456",
  "longitude": "106.123456"
}
```

---

### `stopTracking()`

Stops location tracking and removes the persistent notification.

---

### `requestPermissions()`

Requests location permissions. On Android this includes `ACCESS_BACKGROUND_LOCATION` for API 30+.

Returns `Promise<boolean>` — `true` if all required permissions were granted.

---

### `checkPermissions()`

Checks whether location permissions are currently granted.

Returns `Promise<boolean>`.

---

### `openBatteryOptimizationSettings()` (Android only)

Opens the system battery optimization settings so the user can exclude your app from battery restrictions. Useful to prevent the OS from killing the service.

---

## Events

```ts
import {
  backgroundLocationEvents,
  LocationUpdatedEvent,
  type LocationEvent,
} from '@fajarpancas/react-native-background-location';

const subscription = backgroundLocationEvents.addListener(
  LocationUpdatedEvent,
  (event: LocationEvent) => {
    console.log('Location:', event.latitude, event.longitude, event.timestamp);
  },
);

subscription.remove();
```

### `LocationUpdatedEvent`

Fired whenever a new location is received. Payload:

```ts
interface LocationEvent {
  latitude: number;
  longitude: number;
  timestamp: number; // Unix epoch seconds
}
```

## Example

```ts
import {
  startTracking,
  stopTracking,
  requestPermissions,
  openBatteryOptimizationSettings,
  backgroundLocationEvents,
  LocationUpdatedEvent,
  type LocationEvent,
} from '@fajarpancas/react-native-background-location';

async function start() {
  const granted = await requestPermissions();
  if (!granted) return;

  backgroundLocationEvents.addListener(LocationUpdatedEvent, (loc: LocationEvent) => {
    console.log('New location:', loc);
  });

  startTracking(
    'https://api.example.com/tracking',
    'Bearer eyJhbGciOiJIUzI1NiIs...',
    { job_id: '123', user_id: '456' },
  );
}

function stop() {
  stopTracking();
}
```

A full working example project is available in the [`example/`](./example) directory.

## Architecture

### iOS (`BackgroundLocation.m`)

- **`CLLocationManager`** configured with `kCLLocationAccuracyBest`, `allowsBackgroundLocationUpdates`, `showsBackgroundLocationIndicator`
- Timer-based polling every **5 seconds** with a **3-metre** distance filter and **5-second** API call throttle
- `NWPathMonitor` detects network restoration and retries failed API calls
- Settings (`baseURL`, `header`, `params`) persisted in `NSUserDefaults`
- Failed API calls persisted and retried on network availability
- Persistent notification shown while tracking is active
- `supportedEvents: LocationUpdated` for JS event bridge

### Android (`BackgroundLocationService.kt`)

- Foreground `Service` with `FOREGROUND_SERVICE_TYPE_LOCATION`
- **`FusedLocationProviderClient`** with 5-second interval, `PRIORITY_HIGH_ACCURACY`
- `OkHttp` for API calls with `Authorization` header
- `PowerManager.WakeLock` (partial) and `WifiManager.WifiLock` (full high perf) prevent sleep
- Settings persisted in `SharedPreferences` — survives service restart
- `RestarterBroadcastReceiver` auto-restarts service on unexpected death (up to 5 attempts, exponential backoff: 1s → 2s → 4s → ... → 5m max)
- Broadcast receiver listens for `CONNECTIVITY_ACTION` to retry failed requests
- JS events emitted via `DeviceEventManagerModule.RCTDeviceEventEmitter`

### JS Bridge (`src/index.ts`)

- iOS: native module name `BackgroundLocation`
- Android: native module name `BackgroundLocationModule`
- `NativeEventEmitter` bridges `LocationUpdated` events to JS listeners
- Permissions: Android uses `PermissionsAndroid` (built-in RN), iOS uses native `requestAlwaysAuthorization`

## Project Structure

```
react-native-background-location/
├── src/
│   └── index.ts                  # JS API
├── ios/
│   ├── BackgroundLocation.h       # iOS module header
│   ├── BackgroundLocation.m       # iOS implementation
│   └── VspiritBackgroundLocation.podspec
├── android/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/drawable/
│       │   └── vspirit_bg_location_icon.xml
│       └── java/com/vspirit/backgroundlocation/
│           ├── BackgroundLocationModule.kt
│           ├── BackgroundLocationPackage.kt
│           ├── BackgroundLocationService.kt
│           └── RestarterBroadcastReceiver.kt
├── example/
│   ├── App.tsx                   # Demo app
│   ├── index.js
│   ├── metro.config.js
│   └── package.json
├── package.json
├── tsconfig.json
├── react-native.config.js
├── CHANGELOG.md
└── LICENSE
```

## Changelog

See [CHANGELOG.md](./CHANGELOG.md).

## License

MIT
