import 'package:flutter/services.dart';

/// Shows a native Android Toast via the platform channel.
/// Toast.makeText is a system-level overlay visible on top of any app.
class ToastService {
  static const _channel = MethodChannel('com.tucuvpn.tucuvpn/launcher');

  static Future<void> show(String message) async {
    try {
      await _channel.invokeMethod<void>('showToast', {'message': message});
    } catch (_) {}
  }
}
