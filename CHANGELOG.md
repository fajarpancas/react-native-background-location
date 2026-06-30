# Changelog

## 1.0.0 (2024-07-01)

### Features
- Background location tracking on iOS (CLLocationManager) and Android (FusedLocationProvider)
- Foreground service with persistent notification (Android)
- Periodic location polling (every 5s) with minimum distance filter (3m)
- Auto-sends location to configured API via HTTP POST
- Built-in retry mechanism for failed API calls on network restore
- JS event emission (`LocationUpdated`) with `latitude`, `longitude`, `timestamp`
- Battery optimization settings shortcut (Android)
- Service auto-restart on unexpected death (Android, up to 5 attempts with exponential backoff)
- Wake lock and Wi-Fi lock for reliable operation (Android)
- Network path monitor for connectivity detection (iOS)

### API
- `startTracking(baseUrl, header, params)` — start background location tracking
- `stopTracking()` — stop tracking and remove notification
- `requestPermissions()` — request location permissions (always/background)
- `checkPermissions()` — check if location permissions are granted
- `openBatteryOptimizationSettings()` — open battery settings (Android)
- `backgroundLocationEvents` — NativeEventEmitter for location updates
