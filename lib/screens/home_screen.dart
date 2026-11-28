import 'dart:async';
import 'package:flutter/material.dart';
import '../models/protection_state.dart';
import '../services/native_bridge.dart';
import '../widgets/pin_dialog.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static final _pinRe = RegExp(r'^\d{4,8}$');

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

  /// Turns Kotlin's answer into text for the dialog.
  PinFeedback _feedback(AuthResult r) {
    if (r.ok) return const PinFeedback.ok();
    if (r.error != null) return PinFeedback(r.error!);
    if (r.lockedMs > 0) return PinFeedback('Too many attempts', lockedMs: r.lockedMs);
    final n = r.attemptsLeft;
    if (n <= 0) return const PinFeedback('Wrong PIN');
    return PinFeedback(n == 1 ? 'Wrong PIN. 1 attempt left' : 'Wrong PIN. $n attempts left');
  }

  String? _attemptsNote(PinStatus st) {
    if (st.lockedMs > 0 || st.attemptsLeft >= 5) return null;
    return st.attemptsLeft == 1 ? '1 attempt left' : '${st.attemptsLeft} attempts left';
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
    final st = await NativeBridge.pinStatus();
    if (!mounted) return;
    await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => PinDialog(
        title: _status.state == ProtectionState.alarming
            ? 'Enter PIN to stop the alarm'
            : 'Enter PIN to disarm',
        labels: const ['PIN'],
        initialLockedMs: st.lockedMs,
        initialMessage: _attemptsNote(st),
        onSubmit: (v) async {
          // Obviously malformed PINs are rejected here without using up an attempt.
          if (!_pinRe.hasMatch(v[0])) return const PinFeedback('PIN must be 4-8 digits');
          return _feedback(await NativeBridge.disarm(v[0]));
        },
      ),
    );
    _refresh();
  }

  Future<void> _setPin() async {
    final hasOld = _status.hasPin;
    final st = await NativeBridge.pinStatus();
    if (!mounted) return;
    final saved = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => PinDialog(
        title: hasOld ? 'Change PIN' : 'Set PIN',
        labels: [if (hasOld) 'Current PIN', 'New PIN (4-8 digits)', 'Repeat new PIN'],
        initialLockedMs: hasOld ? st.lockedMs : 0,
        initialMessage: hasOld ? _attemptsNote(st) : null,
        onSubmit: (v) async {
          final old = hasOld ? v[0] : null;
          final a = v[hasOld ? 1 : 0];
          final b = v[hasOld ? 2 : 1];
          if (old != null && !_pinRe.hasMatch(old)) return const PinFeedback('Enter your current PIN (4-8 digits)');
          if (!_pinRe.hasMatch(a)) return const PinFeedback('New PIN must be 4-8 digits');
          if (a != b) return const PinFeedback('PINs do not match');
          return _feedback(await NativeBridge.setPin(a, oldPin: old));
        },
      ),
    );
    if (saved == true) _toast('PIN saved');
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
      appBar: AppBar(title: const Text('Anti-theft (Milestone 1C.1)')),
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