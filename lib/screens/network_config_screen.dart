import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/network_proxy_service.dart';

class WifiNetwork {
  final String ssid;
  final String bssid;
  final int signalStrength;

  WifiNetwork({
    required this.ssid,
    required this.bssid,
    required this.signalStrength,
  });
}

class NetworkConfigScreen extends StatefulWidget {
  const NetworkConfigScreen({Key? key}) : super(key: key);

  @override
  State<NetworkConfigScreen> createState() => _NetworkConfigScreenState();
}

class _NetworkConfigScreenState extends State<NetworkConfigScreen> {
  final NetworkProxyService _proxyService = NetworkProxyService();
  List<WifiNetwork> _availableNetworks = [];
  bool _isScanning = false;
  bool _hasLocationPermission = false;
  
  String? _selectedNetworkA;
  String? _selectedNetworkB;

  @override
  void initState() {
    super.initState();
    _loadCurrentConfig();
    
    // Try to get available networks without scanning first
    _getAvailableNetworks();
  }

  Future<void> _loadCurrentConfig() async {
    final netAConfig = _proxyService.getNetAConfig();
    final netBConfig = _proxyService.getNetBConfig();
    
    setState(() {
      _selectedNetworkA = netAConfig['ssid'] as String?;
      _selectedNetworkB = netBConfig['ssid'] as String?;
    });
  }
  
  Future<void> _getAvailableNetworks() async {
    try {
      final List<dynamic> networks = await const MethodChannel('com.example.superapp/wifi_scanner')
          .invokeMethod('getAvailableNetworks');
      
      setState(() {
        _availableNetworks = networks.map((network) => WifiNetwork(
          ssid: network['ssid'],
          bssid: network['bssid'],
          signalStrength: network['signalStrength'],
        )).toList();
        
        // Sort by signal strength
        _availableNetworks.sort((a, b) => b.signalStrength.compareTo(a.signalStrength));
      });
    } catch (e) {
      print('Failed to get available networks: $e');
      
      // If we can't get networks, fall back to using the current configs
      final netAConfig = _proxyService.getNetAConfig();
      final netBConfig = _proxyService.getNetBConfig();
      
      setState(() {
        _availableNetworks = [
          WifiNetwork(
            ssid: netAConfig['ssid'] as String, 
            bssid: "00:00:00:00:00:00", 
            signalStrength: -50
          ),
          WifiNetwork(
            ssid: netBConfig['ssid'] as String, 
            bssid: "00:00:00:00:00:01", 
            signalStrength: -60
          ),
        ];
      });
    }
  }

  Future<void> _scanNetworks() async {
    setState(() {
      _isScanning = true;
    });

    try {
      final List<dynamic> networks = await const MethodChannel('com.example.superapp/wifi_scanner')
          .invokeMethod('scanNetworks');
      
      if (mounted) {
        setState(() {
          _availableNetworks = networks.map((network) => WifiNetwork(
            ssid: network['ssid'],
            bssid: network['bssid'],
            signalStrength: network['signalStrength'],
          )).toList();
          
          // Sort by signal strength
          _availableNetworks.sort((a, b) => b.signalStrength.compareTo(a.signalStrength));
          
          _isScanning = false;
        });
      }
    } catch (e) {
      print('Failed to scan networks: $e');
      if (mounted) {
        setState(() {
          _isScanning = false;
          
          // Show a snackbar to inform the user about the error
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Failed to scan networks: ${e.toString()}'),
              duration: const Duration(seconds: 3),
            ),
          );
        });
      }
    }
  }

  void _saveNetworkConfig() {
    if (_selectedNetworkA != null) {
      _proxyService.setNetAConfig({
        'ssid': _selectedNetworkA!,
        'type': 'WiFi',
        'priority': 1,
      });
    }
    
    if (_selectedNetworkB != null) {
      _proxyService.setNetBConfig({
        'ssid': _selectedNetworkB!,
        'type': 'WiFi',
        'priority': 2,
      });
    }
    
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Network configuration saved')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Network Configuration'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _isScanning ? null : _scanNetworks,
            tooltip: 'Scan Networks',
          ),
        ],
      ),
      body: _isScanning
          ? const Center(child: CircularProgressIndicator())
          : Padding(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Network A (For selected apps)',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 8),
                  _buildNetworkDropdown(_selectedNetworkA, (value) {
                    setState(() {
                      _selectedNetworkA = value;
                      // Make sure we don't have the same network for both
                      if (_selectedNetworkA == _selectedNetworkB) {
                        _selectedNetworkB = null;
                      }
                    });
                  }),
                  
                  const SizedBox(height: 24),
                  
                  Text(
                    'Network B (For all other apps)',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 8),
                  _buildNetworkDropdown(_selectedNetworkB, (value) {
                    setState(() {
                      _selectedNetworkB = value;
                      // Make sure we don't have the same network for both
                      if (_selectedNetworkB == _selectedNetworkA) {
                        _selectedNetworkA = null;
                      }
                    });
                  }),
                  
                  const Spacer(),
                  
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      onPressed: (_selectedNetworkA != null && _selectedNetworkB != null)
                          ? _saveNetworkConfig
                          : null,
                      child: const Text('Save Configuration'),
                    ),
                  ),
                ],
              ),
            ),
    );
  }

  Widget _buildNetworkDropdown(String? selectedValue, Function(String?) onChanged) {
    // Check if selectedValue exists in the items
    bool valueExists = _availableNetworks.any((network) => network.ssid == selectedValue);
    final effectiveValue = valueExists ? selectedValue : null;
    
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      decoration: BoxDecoration(
        border: Border.all(color: Colors.grey),
        borderRadius: BorderRadius.circular(4),
      ),
      child: DropdownButton<String>(
        value: effectiveValue,
        isExpanded: true,
        hint: const Text('Select a network'),
        underline: const SizedBox(),
        onChanged: onChanged as void Function(String?)?,
        items: _availableNetworks.map((network) {
          return DropdownMenuItem<String>(
            value: network.ssid,
            child: Row(
              children: [
                Icon(
                  Icons.wifi,
                  size: 20,
                  color: _getSignalColor(network.signalStrength),
                ),
                const SizedBox(width: 8),
                Text(network.ssid),
              ],
            ),
          );
        }).toList(),
      ),
    );
  }

  Color _getSignalColor(int strength) {
    if (strength > -50) return Colors.green;
    if (strength > -70) return Colors.yellow;
    return Colors.red;
  }
}