package com.vspirit.backgroundlocation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException

class BackgroundLocationService : Service() {

    companion object {
        private const val TAG = "BackgroundLocationService"
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "vspirit_location_service_channel"
        private const val PREFS_NAME = "VspiritBackgroundLocationPrefs"
        private const val PREF_BASE_URL = "baseURL"
        private const val PREF_HEADER = "header"
        private const val PREF_PARAMS = "params"

        @JvmStatic var shouldRestart = true
        @JvmStatic var restartAttempts = 0
        @JvmStatic var nextRestartDelayMs = 1000L
    }

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationRequest: LocationRequest? = null
    private val client = OkHttpClient()
    private var apiBaseUrl: String? = null
    private var additionalParams: Map<String, Any>? = null
    private var header: String? = null
    private val failedRequests = mutableListOf<JSONObject>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isDestroyed = false

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isNetworkAvailable()) retryFailedRequests()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::WakeLock")
                .apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG::WifiLock")
                .apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WifiLock: ${e.message}")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            maxWaitTime = 5000
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            shouldRestart = false
            restartAttempts = 0
            nextRestartDelayMs = 1000L
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        shouldRestart = true
        restartAttempts = 0
        nextRestartDelayMs = 1000L

        // Use app's ic_notification if available, otherwise use library's default icon
        val iconRes = applicationContext.resources
            .getIdentifier("ic_notification", "drawable", applicationContext.packageName)
            .takeIf { it != 0 } ?: R.drawable.vspirit_bg_location_icon

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Tracking Active")
            .setContentText("Tracking your location in background")
            .setSmallIcon(iconRes)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (intent?.hasExtra("baseURL") == true) {
            apiBaseUrl = intent.getStringExtra("baseURL")
            header = intent.getStringExtra("header")
            intent.getStringExtra("params")?.let { p -> additionalParams = parseParams(p) }
            prefs.edit()
                .putString(PREF_BASE_URL, apiBaseUrl)
                .putString(PREF_HEADER, header)
                .putString(PREF_PARAMS, intent.getStringExtra("params"))
                .apply()
        } else {
            apiBaseUrl = prefs.getString(PREF_BASE_URL, null)
            header = prefs.getString(PREF_HEADER, null)
            prefs.getString(PREF_PARAMS, null)?.let { p -> additionalParams = parseParams(p) }
        }

        try {
            registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver: ${e.message}")
        }

        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (locationRequest == null) return
        try {
            fusedLocationClient?.requestLocationUpdates(locationRequest!!, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tracking: ${e.message}")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (isDestroyed) return
            result.locations.forEach { sendLocationToAPI(it.latitude, it.longitude) }
        }
    }

    private fun sendLocationToAPI(latitude: Double, longitude: Double) {
        val timestamp = System.currentTimeMillis() / 1000
        emitLocationEvent(latitude, longitude, timestamp)

        val url = apiBaseUrl
        val currentHeader = header
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        if (currentHeader == null) return

        val payload = buildPayload(latitude, longitude, timestamp)

        if (!isNetworkAvailable()) {
            failedRequests.add(payload)
            return
        }

        enqueueRequest(url, currentHeader, payload)
    }

    private fun emitLocationEvent(latitude: Double, longitude: Double, timestamp: Long) {
        BackgroundLocationModule.reactContextInstance?.let { ctx ->
            val params = Arguments.createMap().apply {
                putDouble("latitude", latitude)
                putDouble("longitude", longitude)
                putDouble("timestamp", timestamp.toDouble())
            }
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("LocationUpdated", params)
        }
    }

    private fun buildPayload(latitude: Double, longitude: Double, timestamp: Long): JSONObject =
        JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("timestamp", timestamp)
            additionalParams?.forEach { (k, v) -> put(k, v) }
        }

    private fun enqueueRequest(url: String, authHeader: String, payload: JSONObject) {
        val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), payload.toString())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", authHeader)
            .build()

        Log.d(TAG, "REQUEST  POST $url  body: ${payload.toString()}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "RESPONSE  ERROR  ${e.message}")
                failedRequests.add(payload)
            }
            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string() ?: "(empty)"
                Log.d(TAG, "RESPONSE  ${response.code}  body: $bodyString")
                if (!response.isSuccessful) {
                    failedRequests.add(payload)
                }
            }
        })
    }

    private fun retryFailedRequests() {
        val url = apiBaseUrl ?: return
        val currentHeader = header ?: return
        val toRetry = failedRequests.toList()
        failedRequests.clear()
        toRetry.forEach { enqueueRequest(url, currentHeader, it) }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = cm.activeNetworkInfo
        return activeNetwork?.isConnected == true
    }

    private fun parseParams(paramsString: String): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        try {
            val json = JSONObject(paramsString)
            json.keys().forEach { key -> map[key] = json.get(key) }
        } catch (_: Exception) {}
        return map
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed = true
        try { fusedLocationClient?.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        try { unregisterReceiver(networkReceiver) } catch (_: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()

        if (shouldRestart) {
            if (restartAttempts < 5) {
                sendBroadcast(Intent(this, RestarterBroadcastReceiver::class.java))
                restartAttempts++
                nextRestartDelayMs = (nextRestartDelayMs * 2).coerceAtMost(300_000L)
                Log.d(TAG, "Scheduled restart. attempts=$restartAttempts")
            } else {
                Log.d(TAG, "Max restart attempts reached.")
            }
        } else {
            cancelRestartAlarm()
        }
    }

    private fun cancelRestartAlarm() {
        try {
            val intent = Intent(applicationContext, RestarterBroadcastReceiver::class.java)
                .also { it.setPackage(packageName) }
            val pending = android.app.PendingIntent.getBroadcast(
                applicationContext, 1, intent,
                android.app.PendingIntent.FLAG_ONE_SHOT or
                android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_NO_CREATE
            )
            if (pending != null) {
                (applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                    .cancel(pending)
                pending.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling restart alarm: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
