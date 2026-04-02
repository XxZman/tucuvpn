import 'dart:async';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../constants.dart';
import 'settings_provider.dart';
import 'vpn_provider.dart';

class TimerNotifier extends Notifier<int> {
  Timer? _timer;

  @override
  int build() {
    ref.onDispose(() => _timer?.cancel());
    // Start as soon as the provider is first read.
    _init();
    return kDefaultTimerSeconds;
  }

  Future<void> _init() async {
    final seconds = await ref.read(settingsProvider.future);
    _start(seconds);
  }

  void _start(int seconds) {
    _timer?.cancel();
    state = seconds;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (state <= 1) {
        _timer?.cancel();
        state = 0;
        _expire();
      } else {
        state = state - 1;
      }
    });
  }

  /// Called from SettingsScreen after the user saves a new duration.
  Future<void> restart() async {
    final seconds = await ref.read(settingsProvider.future);
    _start(seconds);
  }

  void _expire() {
    // Disconnect VPN first, then close this app.
    // The external app launched earlier stays alive.
    ref.read(vpnProvider.notifier).disconnect();
    Future.delayed(const Duration(milliseconds: 500), () => exit(0));
  }
}

final timerProvider =
    NotifierProvider<TimerNotifier, int>(TimerNotifier.new);
