package com.example.signallocator.manager

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.example.signallocator.data.SignalData
import com.example.signallocator.data.SignalType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object WifiHelper {

    private const val SCAN_TIMEOUT_MS = 10_000L

    suspend fun scanWifi(context: Context): List<SignalData> = withContext(Dispatchers.IO) {
        val hasFine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasWifiState = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
        if (!(hasFine || hasCoarse) || !hasWifiState) return@withContext emptyList()

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@withContext emptyList()
        if (!wifiManager.isWifiEnabled) return@withContext emptyList()

        val appContext = context.applicationContext

        return@withContext suspendCancellableCoroutine { continuation ->
            var resumed = false
            val lock = Any()

            fun collectResults(): List<SignalData> {
                val results = try { wifiManager.scanResults } catch (_: SecurityException) { emptyList() }
                return results.mapNotNull { sr: ScanResult ->
                    sr.SSID?.takeIf { it.isNotEmpty() }?.let { ssid ->
                        SignalData(SignalType.WIFI, sr.level, ssid, sr.BSSID ?: "UNKNOWN")
                    }
                }
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                    appContext.unregisterReceiver(this)
                    synchronized(lock) {
                        if (!resumed && continuation.isActive) { resumed = true; continuation.resume(collectResults()) }
                    }
                }
            }

            appContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

            @Suppress("DEPRECATION")
            val success = wifiManager.startScan()
            if (!success) {
                try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
                synchronized(lock) {
                    if (!resumed && continuation.isActive) { resumed = true; continuation.resume(collectResults()) }
                }
                return@suspendCancellableCoroutine
            }

            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                synchronized(lock) {
                    if (!resumed && continuation.isActive) {
                        resumed = true; try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
                        continuation.resume(collectResults())
                    }
                }
            }, SCAN_TIMEOUT_MS)

            continuation.invokeOnCancellation {
                synchronized(lock) { if (!resumed) try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {} }
                handler.removeCallbacksAndMessages(null)
            }
        }
    }
}
