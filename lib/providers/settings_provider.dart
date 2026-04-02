import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../constants.dart';

class SettingsNotifier extends AsyncNotifier<int> {
  @override
  Future<int> build() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(kPrefTimerSeconds) ?? kDefaultTimerSeconds;
  }

  Future<void> setTimerSeconds(int seconds) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(kPrefTimerSeconds, seconds);
    state = AsyncValue.data(seconds);
  }
}

final settingsProvider =
    AsyncNotifierProvider<SettingsNotifier, int>(SettingsNotifier.new);
