import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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

  void _authToast(AuthResult r) {
    if (r.error != null) {
      _toast(r.error!);
    } else if (r.lockedMs > 0) {
      _toast('Too many attempts. Try again in ${(r.lockedMs / 1000).ceil()} s');
    } else {
      _toast('Wrong PIN');
    }
  }

  Future<String?> _askPin(String title) async {
    if (!mounted) return null;
    return showDialog<String>(context: context, builder: (_) => _PinDialog(title));
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
    final pin = await _askPin('Enter PIN');
    if (pin == null) return;
    final r = await NativeBridge.disarm(pin);
    if (!r.ok) _authToast(r);
    _refresh();
  }

  Future<void> _setPin() async {
    String? old;
    if (_status.hasPin) {
      old = await _askPin('Current PIN');
      if (old == null) return;
    }
    final a = await _askPin('New PIN (4-8 digits)');
    if (a == null) return;
    final b = await _askPin('Repeat new PIN');
    if (b == null) return;
    if (a != b) {
      _toast('PINs do not match');
      return;
    }
    final r = await NativeBridge.setPin(a, oldPin: old);
    if (r.ok) {
      _toast('PIN saved');
    } else {
      _authToast(r);
    }
    _refresh();
  }

  Future<void> _testAlarm() async {
    final err = await NativeBridge.testAlarm();
    _toast(err ?? 'Test alarm playing for 5 s');
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
    final disarmed = s.state == ProtectionState.disarmed;
    final alarming = s.state == ProtectionState.alarming;
    final notRunning = !disarmed && !s.serviceRunning;
    final double secs = _drag ?? s.countdownSeconds.toDouble();
    final title = s.state == ProtectionState.countdown
        ? 'COUNTDOWN  ${(s.remainingMs / 1000).ceil()} s'
        : s.state.name.toUpperCase();

    return Scaffold(
      appBar: AppBar(title: const Text('Anti-theft (Milestone 1C)')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title,
              style: Theme.of(context)
                  .textTheme
                  .headlineMedium
                  ?.copyWith(color: alarming ? Colors.red : null)),
          if (notRunning)
            const Text(
              'Monitoring service is NOT running - protection is not active',
              style: TextStyle(color: Colors.red),
            ),
          if (!s.hasPin)
            const Text('No PIN set - set one before arming',
                style: TextStyle(color: Colors.orange)),
          const SizedBox(height: 8),
          Text('Countdown: ${secs.round()} s (5-60, change while disarmed)'),
          Slider(
            min: 5,
            max: 60,
            divisions: 55,
            value: secs,
            label: '${secs.round()} s',
            onChanged: disarmed ? (v) => setState(() => _drag = v) : null,
            onChangeEnd: disarmed ? _saveSeconds : null,
          ),
          Wrap(spacing: 8, children: [
            ElevatedButton(onPressed: disarmed ? _arm : null, child: const Text('Arm')),
            ElevatedButton(
              onPressed: disarmed ? null : _disarm,
              child: Text(alarming ? 'Stop alarm' : 'Disarm'),
            ),
            OutlinedButton(
              onPressed: disarmed ? _setPin : null,
              child: Text(s.hasPin ? 'Change PIN' : 'Set PIN'),
            ),
            OutlinedButton(
              onPressed: disarmed ? _testAlarm : null,
              child: const Text('Test alarm'),
            ),
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

/// Owns its controller so it is disposed together with the dialog.
class _PinDialog extends StatefulWidget {
  final String title;
  const _PinDialog(this.title);
  @override
  State<_PinDialog> createState() => _PinDialogState();
}

class _PinDialogState extends State<_PinDialog> {
  final _c = TextEditingController();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: TextField(
        controller: _c,
        autofocus: true,
        obscureText: true,
        keyboardType: TextInputType.number,
        maxLength: 8,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        decoration: const InputDecoration(hintText: '4-8 digits'),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        TextButton(onPressed: () => Navigator.pop(context, _c.text), child: const Text('OK')),
      ],
    );
  }
}