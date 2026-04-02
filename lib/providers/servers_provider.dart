import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/vpn_server.dart';

class ServersNotifier extends Notifier<List<VpnServer>> {
  @override
  List<VpnServer> build() => List.from(kServers);

  void setAllIdle() {
    state = state.map((s) => s.copyWith(status: ServerStatus.idle)).toList();
  }

  void setStatus(int index, ServerStatus status) {
    final updated = List<VpnServer>.from(state);
    updated[index] = updated[index].copyWith(status: status);
    state = updated;
  }
}

final serversProvider =
    NotifierProvider<ServersNotifier, List<VpnServer>>(ServersNotifier.new);

/// Tracks the server card the user has highlighted in the list.
final selectedServerProvider = StateProvider<int>((ref) => 0);
