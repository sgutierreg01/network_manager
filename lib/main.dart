import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'services/network_proxy_service.dart';
import 'screens/app_selection_screen.dart';
import 'screens/network_config_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'POS Super App',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({Key? key}) : super(key: key);

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final NetworkProxyService _proxyService = NetworkProxyService();
  bool _isProxyRunning = false;

  @override
  void initState() {
    super.initState();
    _initializeProxy();
  }

  Future<void> _initializeProxy() async {
    // Start proxy when app launches
    bool success = await _proxyService.startProxy();
    setState(() {
      _isProxyRunning = success;
    });
  }

  @override
  void dispose() {
    _proxyService.stopProxy();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('POS Super App'),
        actions: [
          Switch(
            value: _isProxyRunning,
            onChanged: (value) async {
              bool success;
              if (value) {
                success = await _proxyService.startProxy();
              } else {
                await _proxyService.stopProxy();
                success = false;
              }
              setState(() {
                _isProxyRunning = success;
              });
            },
          ),
        ],
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                'Network Proxy Status:',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 10),
              Text(
                _isProxyRunning ? 'Active' : 'Inactive',
                style: TextStyle(
                  color: _isProxyRunning ? Colors.green : Colors.red,
                  fontWeight: FontWeight.bold,
                  fontSize: 18,
                ),
              ),
              const SizedBox(height: 40),
              
              // App Selection Button
              ElevatedButton.icon(
                onPressed: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (context) => const AppSelectionScreen()),
                  );
                },
                icon: const Icon(Icons.apps),
                label: const Text('Select Apps for Network A'),
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                  minimumSize: const Size(double.infinity, 50),
                ),
              ),
              
              const SizedBox(height: 16),
              
              // Network Configuration Button
              ElevatedButton.icon(
                onPressed: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (context) => const NetworkConfigScreen()),
                  );
                },
                icon: const Icon(Icons.wifi),
                label: const Text('Configure Networks'),
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                  minimumSize: const Size(double.infinity, 50),
                ),
              ),
              
              const SizedBox(height: 32),
              
              // Test App Launch Button
              // In your main.dart file, update the "Launch Test App" button to:
              OutlinedButton.icon(
                onPressed: () {
                  // Use Chrome which is probably installed
                  _launchApp('com.android.settings');
                  // Alternative: Use the Settings app
                  // _launchApp('com.android.settings');
                },
                icon: const Icon(Icons.launch),
                label: const Text('Launch Test App (Chrome)'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                  minimumSize: const Size(double.infinity, 50),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _launchApp(String packageName) async {
    const platform = MethodChannel('com.example.superapp/app_launcher');
    try {
      await platform.invokeMethod('launchApp', {'packageName': packageName});
    } on PlatformException catch (e) {
      print('Failed to launch app: ${e.message}');
    }
  }
}