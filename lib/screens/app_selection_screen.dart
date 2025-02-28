import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/network_proxy_service.dart';

class AppInfo {
  final String name;
  final String packageName;
  final ImageProvider? icon;

  AppInfo({required this.name, required this.packageName, this.icon});
}

class AppSelectionScreen extends StatefulWidget {
  const AppSelectionScreen({Key? key}) : super(key: key);

  @override
  State<AppSelectionScreen> createState() => _AppSelectionScreenState();
}

class _AppSelectionScreenState extends State<AppSelectionScreen> {
  final NetworkProxyService _proxyService = NetworkProxyService();
  final List<AppInfo> _installedApps = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadInstalledApps();
  }

  Future<void> _loadInstalledApps() async {
    setState(() {
      _isLoading = true;
    });

    try {
      final List<dynamic> apps = await const MethodChannel('com.example.superapp/app_info')
          .invokeMethod('getInstalledApps');
      
      setState(() {
        _installedApps.clear();
        for (var app in apps) {
          _installedApps.add(AppInfo(
            name: app['appName'],
            packageName: app['packageName'],
            icon: app['iconBytes'] != null 
                ? MemoryImage(app['iconBytes']) 
                : null,
          ));
        }
        _isLoading = false;
      });
    } catch (e) {
      print('Failed to load installed apps: $e');
      setState(() {
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Select Apps for Network A'),
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : ListView.builder(
              itemCount: _installedApps.length,
              itemBuilder: (context, index) {
                final app = _installedApps[index];
                final isSelected = _proxyService.getNetAApps().contains(app.packageName);
                
                return CheckboxListTile(
                  title: Text(app.name),
                  subtitle: Text(app.packageName),
                  secondary: app.icon != null 
                      ? Image(image: app.icon!, width: 40, height: 40) 
                      : const Icon(Icons.android),
                  value: isSelected,
                  onChanged: (bool? value) {
                    if (value == true) {
                      _proxyService.addAppToNetA(app.packageName);
                    } else {
                      _proxyService.removeAppFromNetA(app.packageName);
                    }
                    setState(() {});
                  },
                );
              },
            ),
    );
  }
}