import 'dart:async';
import 'package:flutter/material.dart';
import '../models/protection_state.dart';
import '../services/native_bridge.dart';
import '../widgets/permission_flow.dart';
import '../widgets/pin_flows.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  NativeStatus _status = const NativeStatus(
    state: ProtectionState.disarmed,
    serviceRunning: false,
  );
  PermStatus _perms = const PermStatus(false, false);
  double? _drag; // slider value while the finger is down
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
    final p = await NativeBridge.permissionStatus();
    if (!mounted) return;
    setState(() {
      _status = s;
      _perms = p;
    });
  }

  void _toast(String m) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));
  }

  Future<void> _saveSeconds(double v) async {
    final err = await NativeBridge.setCountdownSeconds(v.round());
    if (err != null) _toast(err);
    if (mounted) setState(() => _drag = null);
    _refresh();
  }

  Future<void> _setPin() async {
    final saved = await showSetPinDialog(context, hasPin: _status.hasPin);
    if (saved) _toast('PIN saved');
    _refresh();
  }

  Future<void> _testAlarm() async {
    final err = await NativeBridge.testAlarm();
    _toast(err ?? 'Test alarm playing for 5 s');
  }

  Widget _section(String title) => Padding(
    padding: const EdgeInsets.only(top: 12, bottom: 4),
    child: Text(title, style: Theme.of(context).textTheme.titleMedium),
  );

  Widget _permRow(String name, String why, bool granted) => ListTile(
    contentPadding: EdgeInsets.zero,
    leading: Icon(
      granted ? Icons.check_circle : Icons.cancel,
      color: granted ? Colors.green : Colors.red,
    ),
    title: Text(name),
    subtitle: Text(why),
  );

  @override
  Widget build(BuildContext context) {
    final disarmed = _status.state == ProtectionState.disarmed;
    final double secs = _drag ?? _status.countdownSeconds.toDouble();

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: SafeArea(
        child: ListView(padding: const EdgeInsets.all(16), children: [
          if (!disarmed)
            const Padding(
              padding: EdgeInsets.only(bottom: 8),
              child: Text('Disarm to change these settings.'),
            ),
          _section('Countdown'),
          Text('The alarm starts ${secs.round()} s after the SIM is removed.'),
          Slider(
            min: 5,
            max: 60,
            divisions: 55,
            value: secs,
            label: '${secs.round()} s',
            onChanged: disarmed ? (v) => setState(() => _drag = v) : null,
            onChangeEnd: disarmed ? _saveSeconds : null,
          ),
          const Divider(),
          _section('Security'),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.pin),
            title: Text(_status.hasPin ? 'Change PIN' : 'Set PIN'),
            subtitle: const Text('4-8 digits. Needed to disarm or stop the alarm.'),
            enabled: disarmed,
            onTap: _setPin,
          ),
          const Divider(),
          _section('Alarm sound'),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.volume_up),
            title: const Text('Test alarm'),
            subtitle: const Text('Plays for 5 s at full volume.'),
            enabled: disarmed,
            onTap: _testAlarm,
          ),
          const Divider(),
          _section('Permissions'),
          _permRow(
            'Phone',
            'Lets SIM Sentinel see whether your SIM is present. It does not read your number or make calls.',
            _perms.phone,
          ),
          _permRow(
            'Notifications',
            'Shows the ongoing "armed" notification that Android requires for background monitoring.',
            _perms.notifications,
          ),
          if (!_perms.all)
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(
                onPressed: () async {
                  await ensurePermissions(context);
                  _refresh();
                },
                child: const Text('Review permissions'),
              ),
            ),
          const Divider(),
          _section('Limits'),
          Text(
            'SIM Sentinel is a best-effort deterrent. It cannot survive power-off, '
                'force-stop, uninstall or a factory reset, and Android or the phone maker '
                'may stop background apps. It supports single-SIM phones only for now.',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ]),
      ),
    );
  }
}