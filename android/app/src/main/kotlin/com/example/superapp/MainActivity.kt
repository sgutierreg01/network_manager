package com.example.superapp

import android.content.Intent
import android.net.VpnService
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private val APP_LAUNCHER_CHANNEL = "com.example.superapp/app_launcher"
    private val NETWORK_PROXY_CHANNEL = "com.example.superapp/network_proxy"
    private val APP_INFO_CHANNEL = "com.example.superapp/app_info"
    private val WIFI_SCANNER_CHANNEL = "com.example.superapp/wifi_scanner"
    private val VPN_REQUEST_CODE = 1000

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // App launcher channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, APP_LAUNCHER_CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "launchApp") {
                val packageName = call.argument<String>("packageName")
                if (packageName != null) {
                    try {
                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                        if (intent != null) {
                            startActivity(intent)
                            result.success(true)
                        } else {
                            result.error("APP_NOT_FOUND", "Application not installed", null)
                        }
                    } catch (e: Exception) {
                        result.error("LAUNCH_FAILED", e.message, null)
                    }
                } else {
                    result.error("INVALID_ARGUMENT", "Package name is required", null)
                }
            } else {
                result.notImplemented()
            }
        }
        
        // Network proxy channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, NETWORK_PROXY_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startProxy" -> {
                    val netAApps = call.argument<List<String>>("netAApps")
                    val netAConfig = call.argument<Map<String, Any>>("netAConfig")
                    val netBConfig = call.argument<Map<String, Any>>("netBConfig")
                    
                    if (netAApps != null && netAConfig != null && netBConfig != null) {
                        startVpnService(netAApps, netAConfig, netBConfig, result)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Missing required arguments", null)
                    }
                }
                "stopProxy" -> {
                    stopVpnService(result)
                }
                "updateNetAApps" -> {
                    val netAApps = call.argument<List<String>>("netAApps")
                    if (netAApps != null) {
                        updateVpnServiceApps(netAApps, result)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Missing required arguments", null)
                    }
                }
                "updateNetAConfig" -> {
                    val netAConfig = call.argument<Map<String, Any>>("netAConfig")
                    if (netAConfig != null) {
                        updateVpnServiceNetAConfig(netAConfig, result)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Missing required arguments", null)
                    }
                }
                "updateNetBConfig" -> {
                    val netBConfig = call.argument<Map<String, Any>>("netBConfig")
                    if (netBConfig != null) {
                        updateVpnServiceNetBConfig(netBConfig, result)
                    } else {
                        result.error("INVALID_ARGUMENTS", "Missing required arguments", null)
                    }
                }
                "isProxyRunning" -> {
                    result.success(ProxyService.isRunning)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
        
        // App info channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, APP_INFO_CHANNEL)
            .setMethodCallHandler(AppInfoHandler(context))
        
        // WiFi scanner channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, WIFI_SCANNER_CHANNEL)
            .setMethodCallHandler(WifiScannerHandler(context))
    }
    
    private fun startVpnService(
        netAApps: List<String>, 
        netAConfig: Map<String, Any>, 
        netBConfig: Map<String, Any>,
        result: MethodChannel.Result
    ) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // VPN permission not yet granted, request it
            ProxyService.pendingResult = result
            ProxyService.pendingNetAApps = netAApps
            ProxyService.pendingNetAConfig = netAConfig
            ProxyService.pendingNetBConfig = netBConfig
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
        } else {
            // VPN permission already granted, start service
            startProxyService(netAApps, netAConfig, netBConfig, result)
        }
    }
    
    private fun startProxyService(
        netAApps: List<String>, 
        netAConfig: Map<String, Any>, 
        netBConfig: Map<String, Any>,
        result: MethodChannel.Result
    ) {
        val serviceIntent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
            putStringArrayListExtra(ProxyService.EXTRA_NET_A_APPS, ArrayList(netAApps))
            putExtra(ProxyService.EXTRA_NET_A_CONFIG, HashMap(netAConfig))
            putExtra(ProxyService.EXTRA_NET_B_CONFIG, HashMap(netBConfig))
        }
        
        startService(serviceIntent)
        result.success(true)
    }
    
    private fun stopVpnService(result: MethodChannel.Result) {
        val serviceIntent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        
        startService(serviceIntent)
        result.success(true)
    }
    
    private fun updateVpnServiceApps(netAApps: List<String>, result: MethodChannel.Result) {
        val serviceIntent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_UPDATE_APPS
            putStringArrayListExtra(ProxyService.EXTRA_NET_A_APPS, ArrayList(netAApps))
        }
        
        startService(serviceIntent)
        result.success(true)
    }
    
    private fun updateVpnServiceNetAConfig(netAConfig: Map<String, Any>, result: MethodChannel.Result) {
        val serviceIntent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_UPDATE_NET_A_CONFIG
            putExtra(ProxyService.EXTRA_NET_A_CONFIG, HashMap(netAConfig))
        }
        
        startService(serviceIntent)
        result.success(true)
    }
    
    private fun updateVpnServiceNetBConfig(netBConfig: Map<String, Any>, result: MethodChannel.Result) {
        val serviceIntent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_UPDATE_NET_B_CONFIG
            putExtra(ProxyService.EXTRA_NET_B_CONFIG, HashMap(netBConfig))
        }
        
        startService(serviceIntent)
        result.success(true)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // VPN permission granted
                val pendingResult = ProxyService.pendingResult
                val pendingNetAApps = ProxyService.pendingNetAApps
                val pendingNetAConfig = ProxyService.pendingNetAConfig
                val pendingNetBConfig = ProxyService.pendingNetBConfig
                
                if (pendingResult != null && pendingNetAApps != null && 
                    pendingNetAConfig != null && pendingNetBConfig != null) {
                    startProxyService(pendingNetAApps, pendingNetAConfig, pendingNetBConfig, pendingResult)
                    
                    // Clear pending data
                    ProxyService.pendingResult = null
                    ProxyService.pendingNetAApps = null
                    ProxyService.pendingNetAConfig = null
                    ProxyService.pendingNetBConfig = null
                }
            } else {
                // VPN permission denied
                ProxyService.pendingResult?.error("PERMISSION_DENIED", "VPN permission denied", null)
                
                // Clear pending data
                ProxyService.pendingResult = null
                ProxyService.pendingNetAApps = null
                ProxyService.pendingNetAConfig = null
                ProxyService.pendingNetBConfig = null
            }
        }
    }
}