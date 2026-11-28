import 'dart:async';
import 'package:flutter/material.dart';
import '../models/protection_state.dart';
import '../services/native_bridge.dart';
import '../widgets/permission_flow.dart';
import '../widgets/pin_flows.dart';
import '../widgets/status_card.dart';
import 'event_log_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  NativeStatus _status = const NativeStatus(
    state: ProtectionState.disarmed,
    serviceRunning: false,
  );
  // Right after arming, the service needs a moment to start; don't flash a false warning.
  DateTime _grace = DateTime.fromMillisecondsSinceEpoch(0);
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startPolling();
  }

  void _startPolling() {
    _timer?.cancel();
    _refresh();
    _timer = Timer.periodic(const Duration(milliseconds: 500), (_) => _refresh());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _startPolling();
    } else {
      _timer?.cancel(); // nobody can see the screen, so don't waste battery refreshing it
      _timer = null;
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final s = await NativeBridge.getStatus();
    if (!mounted) return;
    setState(() => _status = s);
  }

  void _toast(String m) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));
  }

  void _open(Widget page) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));

  Future<void> _arm() async {
    if (!await ensurePermissions(context)) return;
    _grace = DateTime.now().add(const Duration(seconds: 3));
    final err = await NativeBridge.arm();
    if (err != null) _toast(err);
    _refresh();
  }

  Future<void> _disarm() async {
    await showDisarmDialog(context, alarming: _status.state == ProtectionState.alarming);
    _refresh();
  }

  Future<void> _setPin() async {
    final saved = await showSetPinDialog(context, hasPin: _status.hasPin);
    if (saved) _toast('PIN saved');
    _refresh();
  }

  Widget _bigButton(IconData icon, String label, VoidCallback onTap, {Color? color}) {
    return SizedBox(
      width: double.infinity,
      height: 64,
      child: FilledButton.icon(
        onPressed: onTap,
        style: color == null ? null : FilledButton.styleFrom(backgroundColor: color),
        icon: Icon(icon),
        label: Text(label, style: const TextStyle(fontSize: 18)),
      ),
    );
  }

  Widget _mainButton() {
    final s = _status;
    switch (s.state) {
      case ProtectionState.disarmed:
        if (!s.hasPin) return _bigButton(Icons.pin, 'Set a PIN to begin', _setPin);
        return _bigButton(Icons.shield, 'Arm protection', _arm);
      case ProtectionState.armed:
      case ProtectionState.countdown:
        return _bigButton(Icons.lock_open, 'Disarm', _disarm);
      case ProtectionState.alarming:
        return _bigButton(Icons.notifications_off, 'Stop alarm', _disarm,
            color: Colors.red.shade700);
    }
  }

  String _hint() {
    final s = _status;
    switch (s.state) {
      case ProtectionState.disarmed:
        return s.hasPin
            ? 'Arm before you leave. If the SIM is removed while armed, the alarm '
            'sounds ${s.countdownSeconds} s later.'
            : 'You need a PIN to arm, disarm or stop the alarm.';
      case ProtectionState.armed:
        return 'You can close the app and lock the phone. Monitoring continues in the background.';
      case ProtectionState.countdown:
        return 'Putting the same SIM back before the countdown ends cancels it.';
      case ProtectionState.alarming:
        return 'Only the PIN stops the alarm. Reinserting the SIM does not.';
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    final starting = s.state != ProtectionState.disarmed &&
        !s.serviceRunning &&
        DateTime.now().isBefore(_grace);
    final notRunning = s.state != ProtectionState.disarmed && !s.serviceRunning && !starting;

    return Scaffold(
      appBar: AppBar(
        title: const Text('SIM Sentinel'),
        actions: [
          IconButton(
            icon: const Icon(Icons.receipt_long),
            tooltip: 'Debug log',
            onPressed: () => _open(const EventLogScreen()),
          ),
          IconButton(
            icon: const Icon(Icons.settings),
            tooltip: 'Settings',
            onPressed: () => _open(const SettingsScreen()),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(padding: const EdgeInsets.all(16), children: [
          StatusCard(status: s, showNotRunning: notRunning, starting: starting),
          const SizedBox(height: 16),
          _mainButton(),
          const SizedBox(height: 16),
          Text(
            _hint(),
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ]),
      ),
    );
  }
}