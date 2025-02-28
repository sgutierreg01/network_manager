import 'dart:async';
import 'package:flutter/services.dart';

class NetworkProxyService {
  // Singleton pattern
  static final NetworkProxyService _instance = NetworkProxyService._internal();
  factory NetworkProxyService() => _instance;
  NetworkProxyService._internal();

  // Method channel for communication with native code
  static const MethodChannel _channel = MethodChannel('com.example.superapp/network_proxy');

  // Status tracking
  bool _isRunning = false;
  
  // List of apps that should use Network A - stored in memory instead of SharedPreferences
  final List<String> _netAApps = [
    'com.example.superapp',  // Our app
    'com.liverpool.superapp1',      // Other whitelisted apps
    'com.android.settings',      
    'com.example.app3',
  ];

  // Network configurations
  Map<String, dynamic> _netAConfig = {
    'ssid': 'LIV_SERVICIOS',
    'type': 'WiFi',
    'priority': 1,
  };

  Map<String, dynamic> _netBConfig = {
    'ssid': 'EPL-Invitados',
    'type': 'WiFi',
    'priority': 2,
  };

  // Start the proxy service
  Future<bool> startProxy() async {
    if (_isRunning) return true;
    
    try {
      final bool result = await _channel.invokeMethod('startProxy', {
        'netAApps': _netAApps,
        'netAConfig': _netAConfig,
        'netBConfig': _netBConfig,
      });
      
      _isRunning = result;
      return result;
    } catch (e) {
      print('Failed to start proxy: $e');
      return false;
    }
  }

  // Stop the proxy service
  Future<bool> stopProxy() async {
    if (!_isRunning) return true;
    
    try {
      final bool result = await _channel.invokeMethod('stopProxy');
      _isRunning = !result;
      return result;
    } catch (e) {
      print('Failed to stop proxy: $e');
      return false;
    }
  }

  // Add an app to use Network A
  Future<void> addAppToNetA(String packageName) async {
    if (!_netAApps.contains(packageName)) {
      _netAApps.add(packageName);
      
      // Update the running service if it's active
      if (_isRunning) {
        await _channel.invokeMethod('updateNetAApps', {
          'netAApps': _netAApps,
        });
      }
    }
  }

  // Remove an app from using Network A
  Future<void> removeAppFromNetA(String packageName) async {
    if (_netAApps.contains(packageName)) {
      _netAApps.remove(packageName);
      
      // Update the running service if it's active
      if (_isRunning) {
        await _channel.invokeMethod('updateNetAApps', {
          'netAApps': _netAApps,
        });
      }
    }
  }

  // Check proxy status
  Future<bool> isProxyRunning() async {
    try {
      final bool result = await _channel.invokeMethod('isProxyRunning');
      _isRunning = result;
      return result;
    } catch (e) {
      print('Failed to check proxy status: $e');
      return false;
    }
  }
  
  // Get the list of apps using Network A
  List<String> getNetAApps() {
    return List.from(_netAApps); // Return a copy to prevent direct modification
  }
  
  // Get Network A configuration
  Map<String, dynamic> getNetAConfig() {
    return Map.from(_netAConfig);
  }
  
  // Get Network B configuration
  Map<String, dynamic> getNetBConfig() {
    return Map.from(_netBConfig);
  }
  
  // Set Network A configuration
  Future<void> setNetAConfig(Map<String, dynamic> config) async {
    _netAConfig = Map.from(config);
    
    // Update the running service if it's active
    if (_isRunning) {
      await _channel.invokeMethod('updateNetAConfig', {
        'netAConfig': _netAConfig,
      });
    }
  }
  
  // Set Network B configuration
  Future<void> setNetBConfig(Map<String, dynamic> config) async {
    _netBConfig = Map.from(config);
    
    // Update the running service if it's active
    if (_isRunning) {
      await _channel.invokeMethod('updateNetBConfig', {
        'netBConfig': _netBConfig,
      });
    }
  }
}