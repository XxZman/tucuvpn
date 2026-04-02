import 'dart:async';
import 'dart:io';

import 'package:android_intent_plus/android_intent.dart';
import 'package:android_intent_plus/flag.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:openvpn_flutter/openvpn_flutter.dart';

import '../constants.dart';
import '../models/vpn_server.dart';
import 'servers_provider.dart';

enum VpnConnectionState { idle, connecting, connected, failed }

class VpnState {
  final VpnConnectionState connectionState;
  final String statusMessage;
  final int? activeServerIndex;

  const VpnState({
    this.connectionState = VpnConnectionState.idle,
    this.statusMessage = 'Desconectado',
    this.activeServerIndex,
  });

  bool get isConnected => connectionState == VpnConnectionState.connected;
  bool get isConnecting => connectionState == VpnConnectionState.connecting;

  VpnState copyWith({
    VpnConnectionState? connectionState,
    String? statusMessage,
    int? activeServerIndex,
    bool clearActive = false,
  }) =>
      VpnState(
        connectionState: connectionState ?? this.connectionState,
        statusMessage: statusMessage ?? this.statusMessage,
        activeServerIndex:
            clearActive ? null : (activeServerIndex ?? this.activeServerIndex),
      );
}

class VpnNotifier extends Notifier<VpnState> {
  late final OpenVPN _vpn;
  Timer? _failoverTimer;
  int _attemptIndex = 0;
  int _startIndex = 0;
  bool _isConnecting = false;

  @override
  VpnState build() {
    _vpn = OpenVPN(
      onVpnStatusChange: _onStatusChange,
      onVpnStageChange: _onStageChange,
    );
    _vpn.initialize(
      groupIdentifier: 'group.com.tucuvpn.vpn',
      localizedDescription: 'TucuVPN',
      lastStage: (_) {},
      lastConfig: (_) {},
    );
    ref.onDispose(() {
      _failoverTimer?.cancel();
      _vpn.disconnect();
    });
    return const VpnState();
  }

  // ─── Public API ────────────────────────────────────────────────────────────

  Future<void> connect(int serverIndex) async {
    if (_isConnecting || state.isConnected) return;
    _startIndex = serverIndex;
    _attemptIndex = serverIndex;
    await _tryServer(_attemptIndex);
  }

  Future<void> disconnect() async {
    _failoverTimer?.cancel();
    _isConnecting = false;
    _vpn.disconnect();
    ref.read(serversProvider.notifier).setAllIdle();
    state = state.copyWith(
      connectionState: VpnConnectionState.idle,
      statusMessage: 'Desconectado',
      clearActive: true,
    );
  }

  // ─── Failover loop ─────────────────────────────────────────────────────────

  Future<void> _tryServer(int index) async {
    _isConnecting = true;
    final servers = ref.read(serversProvider);

    ref.read(serversProvider.notifier).setAllIdle();
    ref.read(serversProvider.notifier).setStatus(index, ServerStatus.trying);

    state = state.copyWith(
      connectionState: VpnConnectionState.connecting,
      statusMessage: 'Conectando a ${servers[index].name}…',
      clearActive: true,
    );

    try {
      final config = await rootBundle.loadString(servers[index].configPath);

      if (Platform.isAndroid) {
        final granted = await OpenVPN.requestPermissionAndroid();
        if (granted != true) {
          _handleAllFailed(reason: 'Permiso VPN denegado');
          return;
        }
      }

      _vpn.connect(config, servers[index].name, certIsRequired: false);

      _failoverTimer?.cancel();
      _failoverTimer = Timer(
        const Duration(seconds: kVpnFailoverSeconds),
        () {
          if (_isConnecting) _tryNextServer();
        },
      );
    } catch (_) {
      _tryNextServer();
    }
  }

  Future<void> _tryNextServer() async {
    _failoverTimer?.cancel();
    _vpn.disconnect();

    ref.read(serversProvider.notifier).setStatus(_attemptIndex, ServerStatus.failed);

    _attemptIndex = (_attemptIndex + 1) % kServers.length;

    if (_attemptIndex == _startIndex) {
      _handleAllFailed();
      return;
    }

    await Future.delayed(const Duration(milliseconds: 400));
    await _tryServer(_attemptIndex);
  }

  void _handleAllFailed({String reason = 'Todos los servidores fallaron'}) {
    _isConnecting = false;
    ref.read(serversProvider.notifier).setAllIdle();
    state = state.copyWith(
      connectionState: VpnConnectionState.failed,
      statusMessage: reason,
      clearActive: true,
    );
  }

  // ─── OpenVPN callbacks ─────────────────────────────────────────────────────

  Future<void> _onStageChange(VPNStage? stage, String? rawStage) async {
    if (stage == VPNStage.connected) {
      _failoverTimer?.cancel();
      _isConnecting = false;

      ref.read(serversProvider.notifier).setAllIdle();
      ref.read(serversProvider.notifier).setStatus(_attemptIndex, ServerStatus.active);

      state = state.copyWith(
        connectionState: VpnConnectionState.connected,
        statusMessage: 'Conectado · ${kServers[_attemptIndex].name}',
        activeServerIndex: _attemptIndex,
      );

      await _launchTargetApp();
    } else if (stage == VPNStage.error && _isConnecting) {
      _failoverTimer?.cancel();
      _tryNextServer();
    } else if (stage == VPNStage.disconnected && !_isConnecting) {
      ref.read(serversProvider.notifier).setAllIdle();
      state = state.copyWith(
        connectionState: VpnConnectionState.idle,
        statusMessage: 'Desconectado',
        clearActive: true,
      );
    }
  }

  void _onStatusChange(VpnStatus? status) {}

  // ─── Launch external app ───────────────────────────────────────────────────

  Future<void> _launchTargetApp() async {
    try {
      const intent = AndroidIntent(
        action: 'android.intent.action.MAIN',
        package: kTargetAppPackage,
        flags: <int>[Flag.FLAG_ACTIVITY_NEW_TASK],
      );
      await intent.launch();
    } catch (_) {
      // App not installed or not launchable — silently ignore.
    }
  }
}

final vpnProvider =
    NotifierProvider<VpnNotifier, VpnState>(VpnNotifier.new);
