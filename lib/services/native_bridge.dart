import 'package:flutter/services.dart';
import '../models/protection_state.dart';

class NativeBridge {
  static const _ch = MethodChannel('anti_theft/native');

  static Future<bool> requestPermissions() async =>
      await _ch.invokeMethod<bool>('requestPermissions') ?? false;

  /// These return null on success, or an error message.
  static Future<String?> arm() => _call('arm');
  static Future<String?> disarm() => _call('disarm');
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

  static Future<NativeStatus> getStatus() async {
    final m = await _ch.invokeMapMethod<String, dynamic>('getStatus');
    return NativeStatus(
      state: ProtectionState.parse(m?['state'] as String?),
      serviceRunning: (m?['serviceRunning'] as bool?) ?? false,
      remainingMs: (m?['remainingMs'] as num?)?.toInt() ?? 0,
      countdownSeconds: (m?['countdownSeconds'] as num?)?.toInt() ?? 10,
    );
  }

  static Future<String> getLog() async =>
      await _ch.invokeMethod<String>('getLog') ?? '';
  static Future<void> clearLog() => _ch.invokeMethod('clearLog');
}