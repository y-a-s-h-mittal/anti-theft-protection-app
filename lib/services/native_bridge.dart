import 'package:flutter/services.dart';
import '../models/protection_state.dart';

class NativeBridge {
  static const _ch = MethodChannel('anti_theft/native');

  static Future<bool> requestPermissions() async =>
      await _ch.invokeMethod<bool>('requestPermissions') ?? false;

  /// Returns null on success, or an error message.
  static Future<String?> arm() => _call('arm');
  static Future<String?> disarm() => _call('disarm');

  static Future<String?> _call(String method) async {
    try {
      await _ch.invokeMethod(method);
      return null;
    } on PlatformException catch (e) {
      return e.message ?? e.code;
    }
  }

  static Future<NativeStatus> getStatus() async {
    final m = await _ch.invokeMapMethod<String, dynamic>('getStatus');
    return NativeStatus(
      ProtectionState.parse(m?['state'] as String?),
      (m?['serviceRunning'] as bool?) ?? false,
    );
  }

  static Future<String> getLog() async =>
      await _ch.invokeMethod<String>('getLog') ?? '';
  static Future<void> clearLog() => _ch.invokeMethod('clearLog');
}