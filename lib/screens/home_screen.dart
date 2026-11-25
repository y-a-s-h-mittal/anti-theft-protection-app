import 'dart:async';
import 'package:flutter/material.dart';
import '../services/native_bridge.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String _log = '';
  bool _running = false;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final log = await NativeBridge.getLog();
    final running = await NativeBridge.isRunning();
    if (mounted) setState(() { _log = log; _running = running; });
  }

  Future<void> _start() async {
    final granted = await NativeBridge.requestPermissions();
    if (!granted) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Permissions missing - not started')),
      );
      return;
    }
    await NativeBridge.startService();
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Spike: SIM monitor')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Service running: $_running'),
          const SizedBox(height: 8),
          Wrap(spacing: 8, children: [
            ElevatedButton(onPressed: _start, child: const Text('Start')),
            ElevatedButton(onPressed: () async { await NativeBridge.stopService(); _refresh(); }, child: const Text('Stop')),
            OutlinedButton(onPressed: () async { await NativeBridge.clearLog(); _refresh(); }, child: const Text('Clear log')),
          ]),
          const Divider(),
          Expanded(child: SingleChildScrollView(child: SelectableText(_log, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)))),
        ]),
      ),
    );
  }
}