import 'package:flutter/material.dart';
import '../services/native_bridge.dart';
import 'pin_dialog.dart';

final _pinRe = RegExp(r'^\d{4,8}$');

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

/// Asks for the PIN and disarms. Returns true if protection was disarmed / the alarm stopped.
Future<bool> showDisarmDialog(BuildContext context, {required bool alarming}) async {
  final st = await NativeBridge.pinStatus();
  if (!context.mounted) return false;
  final ok = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) => PinDialog(
      title: alarming ? 'Enter PIN to stop the alarm' : 'Enter PIN to disarm',
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
  return ok == true;
}

/// Set a new PIN, or change the existing one. Returns true if a PIN was saved.
Future<bool> showSetPinDialog(BuildContext context, {required bool hasPin}) async {
  final st = await NativeBridge.pinStatus();
  if (!context.mounted) return false;
  final saved = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) => PinDialog(
      title: hasPin ? 'Change PIN' : 'Set PIN',
      labels: [if (hasPin) 'Current PIN', 'New PIN (4-8 digits)', 'Repeat new PIN'],
      initialLockedMs: hasPin ? st.lockedMs : 0,
      initialMessage: hasPin ? _attemptsNote(st) : null,
      onSubmit: (v) async {
        final old = hasPin ? v[0] : null;
        final a = v[hasPin ? 1 : 0];
        final b = v[hasPin ? 2 : 1];
        if (old != null && !_pinRe.hasMatch(old)) {
          return const PinFeedback('Enter your current PIN (4-8 digits)');
        }
        if (!_pinRe.hasMatch(a)) return const PinFeedback('New PIN must be 4-8 digits');
        if (a != b) return const PinFeedback('PINs do not match');
        return _feedback(await NativeBridge.setPin(a, oldPin: old));
      },
    ),
  );
  return saved == true;
}