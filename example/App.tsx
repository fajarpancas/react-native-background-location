import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  Button,
  SafeAreaView,
  StyleSheet,
  Platform,
} from 'react-native';
import {
  startTracking,
  stopTracking,
  requestPermissions,
  checkPermissions,
  openBatteryOptimizationSettings,
  backgroundLocationEvents,
  LocationUpdatedEvent,
} from '@fajarpancas/react-native-background-location';
import type { LocationEvent } from '@fajarpancas/react-native-background-location';

export default function App() {
  const [tracking, setTracking] = useState(false);
  const [lastLocation, setLastLocation] = useState<LocationEvent | null>(null);
  const [permissionsGranted, setPermissionsGranted] = useState(false);

  useEffect(() => {
    checkPermissions().then(setPermissionsGranted);
  }, []);

  useEffect(() => {
    const sub = backgroundLocationEvents.addListener(
      LocationUpdatedEvent,
      (event: LocationEvent) => {
        setLastLocation(event);
      },
    );
    return () => sub.remove();
  }, []);

  const handlePermissions = async () => {
    const granted = await requestPermissions();
    setPermissionsGranted(granted);
  };

  const handleStart = async () => {
    const granted = permissionsGranted || (await requestPermissions());
    if (!granted) return;

    startTracking(
      'https://your-api.example.com/location',
      'Bearer your-token',
      { source: 'mobile-app' },
    );
    setTracking(true);
  };

  const handleStop = () => {
    stopTracking();
    setTracking(false);
  };

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Background Location Example</Text>

      <View style={styles.statusRow}>
        <Text>Permissions: </Text>
        <Text style={{ color: permissionsGranted ? 'green' : 'red' }}>
          {permissionsGranted ? 'Granted' : 'Not granted'}
        </Text>
      </View>

      <View style={styles.statusRow}>
        <Text>Tracking: </Text>
        <Text style={{ color: tracking ? 'green' : 'grey' }}>
          {tracking ? 'Active' : 'Stopped'}
        </Text>
      </View>

      {lastLocation && (
        <View style={styles.locationBox}>
          <Text>Lat: {lastLocation.latitude.toFixed(6)}</Text>
          <Text>Lng: {lastLocation.longitude.toFixed(6)}</Text>
          <Text>Time: {new Date(lastLocation.timestamp * 1000).toLocaleTimeString()}</Text>
        </View>
      )}

      <View style={styles.buttons}>
        {!permissionsGranted && (
          <Button title="Request Permissions" onPress={handlePermissions} />
        )}
        {!tracking ? (
          <Button title="Start Tracking" onPress={handleStart} />
        ) : (
          <Button title="Stop Tracking" onPress={handleStop} color="red" />
        )}
        {Platform.OS === 'android' && (
          <Button title="Battery Settings" onPress={openBatteryOptimizationSettings} />
        )}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20 },
  title: { fontSize: 20, fontWeight: 'bold', marginBottom: 20, textAlign: 'center' },
  statusRow: { flexDirection: 'row', marginBottom: 8 },
  locationBox: {
    backgroundColor: '#f0f0f0',
    padding: 12,
    borderRadius: 8,
    marginVertical: 12,
  },
  buttons: { gap: 12, marginTop: 20 },
});
