package com.example.superapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WifiScannerHandler(private val context: Context) : MethodChannel.MethodCallHandler {
    
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "scanNetworks" -> {
                // Check if we have the permission
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    result.error("PERMISSION_DENIED", "Location permission is required to scan WiFi networks", null)
                    return
                }
                
                GlobalScope.launch(Dispatchers.Main) {
                    try {
                        val networks = scanWifiNetworks()
                        result.success(networks)
                    } catch (e: Exception) {
                        result.error("WIFI_SCAN_ERROR", "Failed to scan WiFi networks: ${e.message}", null)
                    }
                }
            }
            "getAvailableNetworks" -> {
                // Simple method to get current WiFi networks without scanning
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val scanResults = wifiManager.scanResults
                
                val networks = scanResults
                    .filter { it.SSID.isNotEmpty() }
                    .distinctBy { it.SSID }
                    .map { result ->
                        mapOf(
                            "ssid" to result.SSID,
                            "bssid" to result.BSSID,
                            "signalStrength" to result.level
                        )
                    }
                
                result.success(networks)
            }
            else -> {
                result.notImplemented()
            }
        }
    }
    
    private suspend fun scanWifiNetworks() = suspendCancellableCoroutine<List<Map<String, Any>>> { continuation ->
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        val wifiScanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    context.unregisterReceiver(this)
                } catch (e: Exception) {
                    // Ignored
                }
                
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val scanResults = wifiManager.scanResults
                    
                    val networks = scanResults
                        .filter { it.SSID.isNotEmpty() }
                        .distinctBy { it.SSID }
                        .map { result ->
                            mapOf(
                                "ssid" to result.SSID,
                                "bssid" to result.BSSID,
                                "signalStrength" to result.level
                            )
                        }
                    
                    if (continuation.isActive) {
                        continuation.resume(networks)
                    }
                }
            }
        }
        
        try {
            context.registerReceiver(
                wifiScanReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )
            
            // Start scan
            wifiManager.startScan()
            
            continuation.invokeOnCancellation {
                try {
                    context.unregisterReceiver(wifiScanReceiver)
                } catch (e: Exception) {
                    // Receiver might already be unregistered
                }
            }
        } catch (e: Exception) {
            try {
                context.unregisterReceiver(wifiScanReceiver)
            } catch (e2: Exception) {
                // Ignored
            }
            throw e
        }
    }
}