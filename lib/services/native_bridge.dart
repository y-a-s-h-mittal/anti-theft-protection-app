import 'package:flutter/services.dart';
import '../models/protection_state.dart';

class AuthResult {
  final bool ok;
  final int lockedMs;
  final int attemptsLeft;
  final String? error;
  const AuthResult(this.ok, this.lockedMs, this.attemptsLeft, {this.error});
}

class PinStatus {
  final int lockedMs;
  final int attemptsLeft;
  const PinStatus(this.lockedMs, this.attemptsLeft);
}

class PermStatus {
  final bool phone;
  final bool notifications;
  const PermStatus(this.phone, this.notifications);
  bool get all => phone && notifications;
}

class NativeBridge {
  static Future<PermStatus> permissionStatus() async {
    final m = await _ch.invokeMapMethod<String, dynamic>('permissionStatus');
    return PermStatus(
      (m?['phone'] as bool?) ?? false,
      (m?['notifications'] as bool?) ?? false,
    );
  }

  static Future<void> openAppSettings() => _ch.invokeMethod('openAppSettings');

  static const _ch = MethodChannel('anti_theft/native');

  static Future<bool> requestPermissions() async =>
      await _ch.invokeMethod<bool>('requestPermissions') ?? false;

  /// These return null on success, or an error message.
  static Future<String?> arm() => _call('arm');
  static Future<String?> testAlarm() => _call('testAlarm');
  static Future<String?> setCountdownSeconds(int seconds) =>
      _call('setCountdownSeconds', {'seconds': seconds});

  static Future<String?> _call(String method, [Map<String, dynamic>? args]) async {
    try {
      await _ch.invokeMethod(method, args);
      return null;
    } on PlatformException catch (e) {
      return e.message ?? e.code;
    }
  }

  /// Disarms and stops any alarm. The PIN is checked in Kotlin, not here.
  static Future<AuthResult> disarm(String pin) => _auth('disarm', {'pin': pin});

  static Future<AuthResult> setPin(String pin, {String? oldPin}) =>
      _auth('setPin', {'pin': pin, 'oldPin': ?oldPin});

  static Future<AuthResult> _auth(String method, Map<String, dynamic> args) async {
    try {
      final m = await _ch.invokeMapMethod<String, dynamic>(method, args);
      return AuthResult(
        (m?['ok'] as bool?) ?? false,
        (m?['lockedMs'] as num?)?.toInt() ?? 0,
        (m?['attemptsLeft'] as num?)?.toInt() ?? 0,
      );
    } on PlatformException catch (e) {
      return AuthResult(false, 0, 0, error: e.message ?? e.code);
    }
  }

  static Future<PinStatus> pinStatus() async {
    final m = await _ch.invokeMapMethod<String, dynamic>('pinStatus');
    return PinStatus(
      (m?['lockedMs'] as num?)?.toInt() ?? 0,
      (m?['attemptsLeft'] as num?)?.toInt() ?? 5,
    );
  }

  static Future<NativeStatus> getStatus() async {
    final m = await _ch.invokeMapMethod<String, dynamic>('getStatus');
    return NativeStatus(
      state: ProtectionState.parse(m?['state'] as String?),
      serviceRunning: (m?['serviceRunning'] as bool?) ?? false,
      remainingMs: (m?['remainingMs'] as num?)?.toInt() ?? 0,
      countdownSeconds: (m?['countdownSeconds'] as num?)?.toInt() ?? 10,
      hasPin: (m?['hasPin'] as bool?) ?? false,
    );
  }

  static Future<String> getLog() async =>
      await _ch.invokeMethod<String>('getLog') ?? '';
  static Future<void> clearLog() => _ch.invokeMethod('clearLog');
}