import 'dart:async';
import 'package:flutter/material.dart';
import '../models/protection_state.dart';
import '../services/native_bridge.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  NativeStatus _status = const NativeStatus(ProtectionState.disarmed, false);
  String _log = '';
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
    final s = await NativeBridge.getStatus();
    final log = await NativeBridge.getLog();
    if (!mounted) return;
    setState(() {
      _status = s;
      _log = log;
    });
  }

  void _toast(String m) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));
  }

  Future<void> _arm() async {
    if (!await NativeBridge.requestPermissions()) {
      _toast('Permissions missing - not armed');
      return;
    }
    final err = await NativeBridge.arm();
    if (err != null) _toast(err);
    _refresh();
  }

  Future<void> _disarm() async {
    final err = await NativeBridge.disarm();
    if (err != null) _toast(err);
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    final notRunning = s.state != ProtectionState.disarmed && !s.serviceRunning;
    return Scaffold(
      appBar: AppBar(title: const Text('Anti-theft (Milestone 1A)')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(s.state.name.toUpperCase(),
              style: Theme.of(context).textTheme.headlineMedium),
          if (notRunning)
            const Text(
              'Monitoring service is NOT running - protection is not active',
              style: TextStyle(color: Colors.red),
            ),
          const SizedBox(height: 8),
          Wrap(spacing: 8, children: [
            ElevatedButton(onPressed: _arm, child: const Text('Arm')),
            ElevatedButton(onPressed: _disarm, child: const Text('Disarm')),
            OutlinedButton(
              onPressed: () async {
                await NativeBridge.clearLog();
                _refresh();
              },
              child: const Text('Clear log'),
            ),
          ]),
          const Divider(),
          Expanded(
            child: SingleChildScrollView(
              child: SelectableText(_log,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            ),
          ),
        ]),
      ),
    );
  }
}