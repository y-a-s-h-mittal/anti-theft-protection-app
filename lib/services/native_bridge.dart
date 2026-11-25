import 'package:flutter/services.dart';

class NativeBridge {
  static const _ch = MethodChannel('anti_theft/native');

  static Future<void> startService() => _ch.invokeMethod('startService');
  static Future<void> stopService() => _ch.invokeMethod('stopService');
  static Future<bool> isRunning() async => await _ch.invokeMethod<bool>('isRunning') ?? false;
  static Future<String> getLog() async => await _ch.invokeMethod<String>('getLog') ?? '';
  static Future<void> clearLog() => _ch.invokeMethod('clearLog');
  static Future<bool> requestPermissions() async =>
      await _ch.invokeMethod<bool>('requestPermissions') ?? false;
}