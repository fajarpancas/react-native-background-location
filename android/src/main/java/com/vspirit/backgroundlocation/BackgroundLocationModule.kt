package com.vspirit.backgroundlocation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import org.json.JSONArray
import org.json.JSONObject

class BackgroundLocationModule(reactContext: ReactApplicationContext) :
    RCTEventEmitter(reactContext) {

    private var trackingActive = false

    override fun getName() = "BackgroundLocationModule"

    override fun getSupportedEvents(): List<String> = listOf("LocationUpdated")

    @ReactMethod
    fun startTracking(baseURL: String, header: String, params: ReadableMap) {
        reactContextInstance = reactContext

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
            putExtra("params", readableMapToJsonString(params))
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

    private fun readableMapToJsonString(map: ReadableMap): String {
        val json = JSONObject()
        val iterator = map.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            when (map.getType(key)) {
                ReadableType.String -> json.put(key, map.getString(key))
                ReadableType.Number -> json.put(key, map.getDouble(key))
                ReadableType.Boolean -> json.put(key, map.getBoolean(key))
                ReadableType.Array -> json.put(key, readableArrayToJsonArray(map.getArray(key)))
                ReadableType.Map -> json.put(key, readableMapToJsonString(map.getMap(key)))
                ReadableType.Null -> json.put(key, JSONObject.NULL)
            }
        }
        return json.toString()
    }

    private fun readableArrayToJsonArray(array: ReadableArray): JSONArray {
        val json = JSONArray()
        for (i in 0 until array.size()) {
            when (array.getType(i)) {
                ReadableType.String -> json.put(array.getString(i))
                ReadableType.Number -> json.put(array.getDouble(i))
                ReadableType.Boolean -> json.put(array.getBoolean(i))
                ReadableType.Array -> json.put(readableArrayToJsonArray(array.getArray(i)))
                ReadableType.Map -> json.put(readableMapToJsonString(array.getMap(i)))
                ReadableType.Null -> json.put(JSONObject.NULL)
            }
        }
        return json
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
    fun checkPermissions(promise: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            promise.resolve(true)
            return
        }

        val hasFine = ContextCompat.checkSelfPermission(
            reactContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasBackground = hasBackgroundPermission()

        promise.resolve(hasFine && hasBackground)
    }

    private fun hasBackgroundPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(
                reactContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
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

    override fun onCatalystInstanceDestroy() {
        reactContextInstance = null
        super.onCatalystInstanceDestroy()
    }

    companion object {
        private const val TAG = "BackgroundLocationModule"
        var reactContextInstance: ReactApplicationContext? = null
            private set
    }
}
