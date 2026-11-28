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
  NativeStatus _status = const NativeStatus(
    state: ProtectionState.disarmed,
    serviceRunning: false,
  );
  String _log = '';
  double? _drag; // slider value while the finger is down
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(milliseconds: 500), (_) => _refresh());
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

  Future<void> _saveSeconds(double v) async {
    final err = await NativeBridge.setCountdownSeconds(v.round());
    if (err != null) _toast(err);
    _drag = null;
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    final notRunning = s.state != ProtectionState.disarmed && !s.serviceRunning;
    final double secs = _drag ?? s.countdownSeconds.toDouble();
    final title = s.state == ProtectionState.countdown
        ? 'COUNTDOWN  ${(s.remainingMs / 1000).ceil()} s'
        : s.state.name.toUpperCase();

    return Scaffold(
      appBar: AppBar(title: const Text('Anti-theft (Milestone 1B)')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: Theme.of(context).textTheme.headlineMedium),
          if (notRunning)
            const Text(
              'Monitoring service is NOT running - protection is not active',
              style: TextStyle(color: Colors.red),
            ),
          const SizedBox(height: 8),
          Text('Countdown: ${secs.round()} s (5-60, change while disarmed)'),
          Slider(
            min: 5,
            max: 60,
            divisions: 55,
            value: secs,
            label: '${secs.round()} s',
            onChanged: s.state == ProtectionState.disarmed
                ? (v) => setState(() => _drag = v)
                : null,
            onChangeEnd: s.state == ProtectionState.disarmed ? _saveSeconds : null,
          ),
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