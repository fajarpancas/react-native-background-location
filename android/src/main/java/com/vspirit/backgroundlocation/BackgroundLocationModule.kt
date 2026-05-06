package com.vspirit.backgroundlocation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap

class BackgroundLocationModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var trackingActive = false

    override fun getName() = "BackgroundLocationModule"

    @ReactMethod
    fun startTracking(baseURL: String, header: String, params: ReadableMap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasFine = ContextCompat.checkSelfPermission(
                reactContext, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                reactContext, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                Log.e(TAG, "Location permissions not granted.")
                return
            }
        }

        val intent = Intent(reactContext, BackgroundLocationService::class.java).apply {
            putExtra("baseURL", baseURL)
            putExtra("header", header)
            putExtra("params", params.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
            trackingActive = true
            Log.d(TAG, "Tracking started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
        }
    }

    @ReactMethod
    fun stopTracking() {
        if (!trackingActive) return
        val intent = Intent(reactContext, BackgroundLocationService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        reactContext.startService(intent)
        trackingActive = false
        Log.d(TAG, "Tracking stopped.")
    }

    @ReactMethod
    fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:" + reactContext.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                reactContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open battery settings: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "BackgroundLocationModule"
    }
}
