package com.example.superapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap

class ProxyService : VpnService() {
    companion object {
        const val ACTION_START = "com.example.superapp.START_PROXY"
        const val ACTION_STOP = "com.example.superapp.STOP_PROXY"
        const val ACTION_UPDATE_APPS = "com.example.superapp.UPDATE_APPS"
        const val ACTION_UPDATE_NET_A_CONFIG = "com.example.superapp.UPDATE_NET_A_CONFIG"
        const val ACTION_UPDATE_NET_B_CONFIG = "com.example.superapp.UPDATE_NET_B_CONFIG"
        
        const val EXTRA_NET_A_APPS = "net_a_apps"
        const val EXTRA_NET_A_CONFIG = "net_a_config"
        const val EXTRA_NET_B_CONFIG = "net_b_config"
        
        const val NOTIFICATION_CHANNEL_ID = "proxy_service_channel"
        const val NOTIFICATION_ID = 1001
        
        var isRunning = false
        
        // Pending data for VPN permission request
        var pendingResult: MethodChannel.Result? = null
        var pendingNetAApps: List<String>? = null
        var pendingNetAConfig: Map<String, Any>? = null
        var pendingNetBConfig: Map<String, Any>? = null
        
        // Connection tracking
        private val activeConnections = ConcurrentHashMap<Int, ConnectionInfo>()
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var selector: Selector? = null
    
    // Configuration
    private var netAApps: List<String> = emptyList()
    private var netAConfig: Map<String, Any> = emptyMap()
    private var netBConfig: Map<String, Any> = emptyMap()
    
    // Store network references
    private var networkA: android.net.Network? = null
    private var networkB: android.net.Network? = null
    
    // Network management
    private val connectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    
    private var netACallback: ConnectivityManager.NetworkCallback? = null
    private var netBCallback: ConnectivityManager.NetworkCallback? = null
    
    data class ConnectionInfo(
        val sourceAddress: String,
        val sourcePort: Int,
        val destinationAddress: String,
        val destinationPort: Int,
        val protocol: Int,
        val appUid: Int,
        val appPackage: String,
        var useNetworkA: Boolean
    )
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val apps = intent.getStringArrayListExtra(EXTRA_NET_A_APPS) ?: ArrayList()
                val netAConfig = intent.getSerializableExtra(EXTRA_NET_A_CONFIG) as? HashMap<String, Any> ?: HashMap()
                val netBConfig = intent.getSerializableExtra(EXTRA_NET_B_CONFIG) as? HashMap<String, Any> ?: HashMap()
                
                startProxy(apps, netAConfig, netBConfig)
            }
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
            }
            ACTION_UPDATE_APPS -> {
                val apps = intent.getStringArrayListExtra(EXTRA_NET_A_APPS) ?: ArrayList()
                updateNetAApps(apps)
            }
            ACTION_UPDATE_NET_A_CONFIG -> {
                val netAConfig = intent.getSerializableExtra(EXTRA_NET_A_CONFIG) as? HashMap<String, Any> ?: HashMap()
                updateNetAConfig(netAConfig)
            }
            ACTION_UPDATE_NET_B_CONFIG -> {
                val netBConfig = intent.getSerializableExtra(EXTRA_NET_B_CONFIG) as? HashMap<String, Any> ?: HashMap()
                updateNetBConfig(netBConfig)
            }
        }
        
        return START_STICKY
    }
    
    private fun startProxy(apps: List<String>, netAConfig: Map<String, Any>, netBConfig: Map<String, Any>) {
        if (isRunning) return
        
        this.netAApps = apps
        this.netAConfig = netAConfig
        this.netBConfig = netBConfig
        
        // Start as a foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Establish both network connections
        setupNetworkA()
        setupNetworkB()
        
        // Start VPN interface
        setupVpnInterface()
        
        // Start packet processing
        scope.launch {
            processPackets()
        }
        
        isRunning = true
    }
    
    private fun stopProxy() {
        if (!isRunning) return
        
        // Release network callbacks
        netACallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        netBCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        
        // Clear network references
        networkA = null
        networkB = null
        
        // Close VPN interface
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Close selector
        try {
            selector?.close()
            selector = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Clear connection tracking
        activeConnections.clear()
        
        // Stop foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        isRunning = false
    }
    
    private fun updateNetAApps(apps: List<String>) {
        this.netAApps = apps
        
        // Update existing connections
        for (connection in activeConnections.values) {
            connection.useNetworkA = netAApps.contains(connection.appPackage)
        }
    }
    
    private fun updateNetAConfig(config: Map<String, Any>) {
        netAConfig = config
        // Reestablish network connection if needed
        netACallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        netACallback = null
        networkA = null
        setupNetworkA()
    }

    private fun updateNetBConfig(config: Map<String, Any>) {
        netBConfig = config
        // Reestablish network connection if needed
        netBCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        netBCallback = null
        networkB = null
        setupNetworkB()
    }
    
    private fun setupVpnInterface() {
        if (vpnInterface != null) return
        
        try {
            // Create a VPN interface builder
            val builder = Builder()
                .setSession("SuperApp Network Proxy")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setBlocking(true)
                .setMtu(1500)
            
            // Allow apps to bypass the VPN
            for (app in netAApps) {
                try {
                    builder.addDisallowedApplication(app)
                } catch (e: PackageManager.NameNotFoundException) {
                    // Package not found, ignore
                }
            }
            
            // Establish the VPN interface
            vpnInterface = builder.establish()
            
            // Create a selector for non-blocking IO
            selector = Selector.open()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun setupNetworkA() {
        val ssid = netAConfig["ssid"] as? String ?: return
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        netACallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Store the network reference
                networkA = network
            }
            
            override fun onLost(network: android.net.Network) {
                if (networkA == network) {
                    networkA = null
                }
            }
        }
        
        // Register for network callbacks instead of requesting specific networks
        connectivityManager.registerNetworkCallback(request, netACallback!!)
    }
    
    private fun setupNetworkB() {
        val ssid = netBConfig["ssid"] as? String ?: return
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        netBCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Store the network reference
                networkB = network
            }
            
            override fun onLost(network: android.net.Network) {
                if (networkB == network) {
                    networkB = null
                }
            }
        }
        
        // Register for network callbacks instead of requesting specific networks
        connectivityManager.registerNetworkCallback(request, netBCallback!!)
    }
    
    private fun processPackets() {
        val vpnInterface = this.vpnInterface ?: return
        val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor)
        
        val packet = ByteBuffer.allocate(32767)
        
        while (isRunning) {
            try {
                // Read packet from VPN interface
                packet.clear()
                val length = vpnInput.read(packet.array())
                if (length < 1) continue
                
                packet.limit(length)
                
                // Process the packet (simplified example)
                val sourceIp = packet.getInt(12)
                val destIp = packet.getInt(16)
                val protocol = packet.get(9).toInt() and 0xFF
                
                // For TCP/UDP, get ports
                var sourcePort = 0
                var destPort = 0
                if (protocol == OsConstants.IPPROTO_TCP || protocol == OsConstants.IPPROTO_UDP) {
                    // Header length is in 4-byte words, stored in bits 4-7 of byte 0
                    val ipHeaderLength = (packet.get(0).toInt() and 0x0F) * 4
                    sourcePort = ((packet.get(ipHeaderLength).toInt() and 0xFF) shl 8) or
                            (packet.get(ipHeaderLength + 1).toInt() and 0xFF)
                    destPort = ((packet.get(ipHeaderLength + 2).toInt() and 0xFF) shl 8) or
                            (packet.get(ipHeaderLength + 3).toInt() and 0xFF)
                }
                
                // Determine which app is responsible for this connection
                val uid = getConnectionUid(sourceIp, sourcePort, destIp, destPort, protocol)
                val packageName = getPackageNameForUid(uid)
                
                // Determine which network to use
                val useNetworkA = netAApps.contains(packageName)
                
                // Create or update connection tracking
                val connId = (sourceIp shl 16) or sourcePort
                activeConnections[connId] = ConnectionInfo(
                    sourceAddress = int2ip(sourceIp),
                    sourcePort = sourcePort,
                    destinationAddress = int2ip(destIp),
                    destinationPort = destPort,
                    protocol = protocol,
                    appUid = uid,
                    appPackage = packageName ?: "unknown",
                    useNetworkA = useNetworkA
                )
                
                // Route the packet through the appropriate network
                routePacket(packet, useNetworkA)
                
                // Write response back to VPN
                vpnOutput.write(packet.array(), 0, packet.limit())
            } catch (e: IOException) {
                e.printStackTrace()
                break
            }
        }
    }
    
    private fun routePacket(packet: ByteBuffer, useNetworkA: Boolean) {
        try {
            val channel = DatagramChannel.open()
            protect(channel.socket()) // Important: prevents VPN loop
            
            // Bind to the appropriate network using stored network references
            if (useNetworkA && networkA != null) {
                networkA?.bindSocket(channel.socket())
            } else if (networkB != null) {
                networkB?.bindSocket(channel.socket())
            }
            
            // Actual packet routing would be implemented here
            // This is a simplified implementation - in reality, you would:
            // 1. Determine the destination address from the packet
            // 2. Create a socket connection to that address
            // 3. Forward the packet data to that socket
            // 4. Read the response and write it back to the VPN tunnel
            
            channel.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun getConnectionUid(sourceIp: Int, sourcePort: Int, destIp: Int, destPort: Int, protocol: Int): Int {
        // In a real implementation, you would use the system's proc files to 
        // determine which UID (and thus, which app) owns this connection
        // This is a simplified placeholder
        return 10000  // Example UID
    }
    
    private fun getPackageNameForUid(uid: Int): String? {
        val pm = packageManager
        val packages = pm.getPackagesForUid(uid)
        return packages?.firstOrNull()
    }
    
    private fun int2ip(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Network Proxy Service"
            val description = "Controls network routing for the Super App"
            val importance = NotificationManager.IMPORTANCE_LOW
            
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                this.description = description
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Network Proxy Active")
            .setContentText("Managing network connections for Super App")
            .setSmallIcon(R.drawable.ic_notification) // Make sure this exists
            .setContentIntent(pendingIntent)
            .build()
    }
    
    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }
}