import 'dart:async';
import 'dart:io';

import 'package:android_intent_plus/android_intent.dart';
import 'package:android_intent_plus/flag.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/foundation.dart';
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
  // Non-null while a "app not installed" snackbar is pending; cleared by the UI.
  final String? launchError;

  const VpnState({
    this.connectionState = VpnConnectionState.idle,
    this.statusMessage = 'Desconectado',
    this.activeServerIndex,
    this.launchError,
  });

  bool get isConnected => connectionState == VpnConnectionState.connected;
  bool get isConnecting => connectionState == VpnConnectionState.connecting;

  VpnState copyWith({
    VpnConnectionState? connectionState,
    String? statusMessage,
    int? activeServerIndex,
    bool clearActive = false,
    String? launchError,
    bool clearLaunchError = false,
  }) =>
      VpnState(
        connectionState: connectionState ?? this.connectionState,
        statusMessage: statusMessage ?? this.statusMessage,
        activeServerIndex:
            clearActive ? null : (activeServerIndex ?? this.activeServerIndex),
        launchError:
            clearLaunchError ? null : (launchError ?? this.launchError),
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
      onVpnStatusChanged: _onStatusChange,
      onVpnStageChanged: _onStageChange,
    );
    _vpn.initialize(
      groupIdentifier: 'group.com.tucuvpn.vpn',
      localizedDescription: 'TucuVPN',
      lastStage: (_) {},
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
    _vpn.disconnect(); // no-op when in emulator mode — safe to call
    ref.read(serversProvider.notifier).setAllIdle();
    state = state.copyWith(
      connectionState: VpnConnectionState.idle,
      statusMessage: 'Desconectado',
      clearActive: true,
    );
  }

  // ─── Emulator detection ────────────────────────────────────────────────────

  /// Returns true when running on an Android emulator.
  /// Checks Build.HARDWARE == "ranchu" (QEMU/AVD) or a generic fingerprint.
  Future<bool> _isEmulator() async {
    if (!Platform.isAndroid) return false;
    try {
      final info = await DeviceInfoPlugin().androidInfo;
      return !info.isPhysicalDevice ||
          info.hardware == 'ranchu' ||
          info.fingerprint.contains('generic') ||
          info.fingerprint.contains('emulator');
    } catch (_) {
      return false;
    }
  }

  // ─── Emulator simulation ───────────────────────────────────────────────────

  Future<void> _simulateConnection(int index) async {
    // Fake the 2-second "handshake" so the UI looks realistic.
    await Future.delayed(const Duration(seconds: 2));

    if (!_isConnecting) return; // user disconnected while waiting

    _failoverTimer?.cancel();
    _isConnecting = false;

    ref.read(serversProvider.notifier).setAllIdle();
    ref.read(serversProvider.notifier).setStatus(index, ServerStatus.active);

    state = state.copyWith(
      connectionState: VpnConnectionState.connected,
      statusMessage: 'Conectado (modo prueba) · ${kServers[index].name}',
      activeServerIndex: index,
    );

    await _launchTargetApp();
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

    // On emulator: skip real OpenVPN and simulate a successful connection.
    if (await _isEmulator()) {
      await _simulateConnection(index);
      return;
    }

    try {
      final config = await rootBundle.loadString(servers[index].configPath);

      // The plugin requests VPN permission internally on Android when connect() is called.
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

  // ─── OpenVPN callbacks (real device only) ─────────────────────────────────

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

  /// Called by the UI after it has displayed the snackbar.
  void clearLaunchError() {
    state = state.copyWith(clearLaunchError: true);
  }

  // ─── Launch external app ───────────────────────────────────────────────────

  Future<void> _launchTargetApp() async {
    // Strategy 1: explicit component name (most reliable when known).
    try {
      final intent = AndroidIntent(
        action: 'android.intent.action.MAIN',
        package: kTargetAppPackage,
        componentName: '$kTargetAppPackage/.activities.MainActivity',
        flags: <int>[Flag.FLAG_ACTIVITY_NEW_TASK],
      );
      await intent.launch();
      return; // success — done
    } catch (e1) {
      debugPrint('Launch strategy 1 failed: $e1');
    }

    // Strategy 2: package only, let Android resolve the launcher activity.
    try {
      final intent = AndroidIntent(
        action: 'android.intent.action.MAIN',
        package: kTargetAppPackage,
        flags: <int>[Flag.FLAG_ACTIVITY_NEW_TASK],
      );
      await intent.launch();
      return; // success — done
    } catch (e2) {
      debugPrint('Launch strategy 2 failed: $e2');
      // Surface the real error so it's visible while debugging.
      state = state.copyWith(
        launchError: 'Error al abrir app: $e2',
      );
    }
  }
}

final vpnProvider =
    NotifierProvider<VpnNotifier, VpnState>(VpnNotifier.new);
