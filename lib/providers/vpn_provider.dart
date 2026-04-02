import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:openvpn_flutter/openvpn_flutter.dart';

import '../constants.dart';
import '../models/vpn_server.dart';
import '../services/toast_service.dart';
import 'log_provider.dart';
import 'servers_provider.dart';
import 'settings_provider.dart';

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

class VpnNotifier extends Notifier<VpnState> with WidgetsBindingObserver {
  late final OpenVPN _vpn;
  Timer? _failoverTimer;
  int _attemptIndex = 0; // which country (index into kServers)
  int _startIndex = 0;   // country the user picked
  int _configIndex = 0;  // which config file within the current country
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
    // Clean up any leftover VPN session from a previous run.
    _vpn.disconnect();

    WidgetsBinding.instance.addObserver(this);
    ref.onDispose(() {
      WidgetsBinding.instance.removeObserver(this);
      _failoverTimer?.cancel();
      _vpn.disconnect();
    });
    return const VpnState();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState lifecycleState) {
    if (lifecycleState == AppLifecycleState.detached) {
      disconnect();
    }
  }

  // ─── Public API ────────────────────────────────────────────────────────────

  Future<void> connect(int serverIndex) async {
    if (_isConnecting || state.isConnected) return;
    _startIndex = serverIndex;
    _attemptIndex = serverIndex;
    _configIndex = 0;
    ref.read(logProvider.notifier).add('› Lanzando VPN...');
    await _tryCurrentConfig();
  }

  Future<void> disconnect() async {
    _failoverTimer?.cancel();
    _isConnecting = false;
    _attemptIndex = 0;
    _configIndex = 0;
    _startIndex = 0;
    _vpn.disconnect();
    ref.read(serversProvider.notifier).setAllIdle();
    state = state.copyWith(
      connectionState: VpnConnectionState.idle,
      statusMessage: 'Desconectado',
      clearActive: true,
    );
  }

  // ─── Two-level failover: config files within a country, then next country ──

  Future<void> _tryCurrentConfig() async {
    _isConnecting = true;
    final servers = ref.read(serversProvider);
    final server = servers[_attemptIndex];
    final configs = server.configPaths;

    // Country has no config files — skip straight to next country.
    if (configs.isEmpty) {
      debugPrint('=== TUCUVPN: No configs for ${server.name}, skipping country');
      ref.read(logProvider.notifier).add('› ${server.name}: sin configs, saltando...');
      await _tryNextCountry();
      return;
    }

    // All configs for this country exhausted.
    if (_configIndex >= configs.length) {
      await _tryNextCountry();
      return;
    }

    final configPath = configs[_configIndex];
    final configNum = _configIndex + 1;
    final total = configs.length;

    ref.read(serversProvider.notifier).setAllIdle();
    ref.read(serversProvider.notifier).setStatus(_attemptIndex, ServerStatus.trying);

    state = state.copyWith(
      connectionState: VpnConnectionState.connecting,
      statusMessage: 'Conectando a ${server.name} ($configNum/$total)…',
      clearActive: true,
    );

    ref.read(logProvider.notifier)
        .add('› Contactando ${server.configPrefix}_$configNum ($configNum/$total)');

    try {
      debugPrint('=== TUCUVPN: Trying ${server.name} $configNum/$total');
      debugPrint('=== TUCUVPN: Config path: $configPath');

      final config = await rootBundle.loadString(configPath);

      debugPrint('=== TUCUVPN: Config loaded, length: ${config.length}');
      debugPrint('=== TUCUVPN: Calling _vpn.connect()');

      // Append compatibility flags for legacy SoftEther/VPN Gate servers.
      final patchedConfig = config
          + '\ntls-client'
          + '\ntls-version-min 1.0'
          + '\nallow-compression yes'
          + '\ndata-ciphers AES-128-CBC'
          + '\ndata-ciphers-fallback AES-128-CBC\n';

      ref.read(logProvider.notifier).add('› Autenticando...');
      _vpn.connect(
        patchedConfig,
        server.name,
        certIsRequired: false,
        username: 'vpn',
        password: 'vpn',
      );

      debugPrint('=== TUCUVPN: connect() called successfully');

      _failoverTimer?.cancel();
      _failoverTimer = Timer(
        const Duration(seconds: kVpnFailoverSeconds),
        () {
          debugPrint('=== TUCUVPN: Failover timer fired for ${server.name} $configNum/$total');
          if (_isConnecting) _tryNextConfig();
        },
      );
    } catch (e) {
      debugPrint('=== TUCUVPN: Error loading config: $e');
      ref.read(logProvider.notifier).add('› Error cargando config: $e');
      _tryNextConfig();
    }
  }

  /// Move to the next config file within the same country.
  /// Falls through to _tryNextCountry() when all files are exhausted.
  Future<void> _tryNextConfig() async {
    _failoverTimer?.cancel();
    _vpn.disconnect();

    final server = ref.read(serversProvider)[_attemptIndex];
    final nextNum = _configIndex + 2; // 1-based next index
    _configIndex++;

    final configs = server.configPaths;
    if (_configIndex < configs.length) {
      ref.read(logProvider.notifier)
          .add('› Fallo, probando ${server.configPrefix}_$nextNum...');
      await Future.delayed(const Duration(milliseconds: 400));
      await _tryCurrentConfig();
    } else {
      ref.read(logProvider.notifier)
          .add('› ${server.name}: todos los configs fallaron');
      await _tryNextCountry();
    }
  }

  /// Mark the current country failed and move to the next one.
  Future<void> _tryNextCountry() async {
    _failoverTimer?.cancel();
    _vpn.disconnect();

    ref.read(serversProvider.notifier).setStatus(_attemptIndex, ServerStatus.failed);

    final failedName = kServers[_attemptIndex].name;
    _configIndex = 0;
    _attemptIndex = (_attemptIndex + 1) % kServers.length;

    if (_attemptIndex == _startIndex) {
      _handleAllFailed();
      return;
    }

    ref.read(logProvider.notifier)
        .add('› $failedName fallido, cambiando país...');
    await Future.delayed(const Duration(milliseconds: 400));
    await _tryCurrentConfig();
  }

  void _handleAllFailed({String reason = 'Todos los servidores fallaron'}) {
    _isConnecting = false;
    ref.read(serversProvider.notifier).setAllIdle();
    ref.read(logProvider.notifier).add('✗ Todos los servidores fallaron');
    state = state.copyWith(
      connectionState: VpnConnectionState.failed,
      statusMessage: reason,
      clearActive: true,
    );
  }

  // ─── OpenVPN callbacks ────────────────────────────────────────────────────

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

      ref.read(logProvider.notifier)
          .add('✓ Conectado: ${kServers[_attemptIndex].name}');
      await _launchTargetApp();
      await ToastService.show('✅ VPN Conectada');
      final timerSeconds = await ref.read(settingsProvider.future);
      await Future.delayed(const Duration(seconds: 4));
      await ToastService.show('⏱️ Timer iniciado — $timerSeconds segundos');
    } else if (stage == VPNStage.error && _isConnecting) {
      debugPrint('=== TUCUVPN: VPN error stage — raw: $rawStage');
      ref.read(logProvider.notifier)
          .add('✗ Error: ${rawStage ?? "error"} — cambiando servidor...');
      _failoverTimer?.cancel();
      _tryNextConfig();
    } else if (stage == VPNStage.disconnected && _isConnecting) {
      // Disconnected unexpectedly while still trying to connect.
      debugPrint('=== TUCUVPN: Unexpected disconnect while connecting — raw: $rawStage');
      ref.read(logProvider.notifier)
          .add('✗ Desconectado inesperado (${rawStage ?? "?"})');
    } else if (stage == VPNStage.disconnected && !_isConnecting) {
      ref.read(serversProvider.notifier).setAllIdle();
      state = state.copyWith(
        connectionState: VpnConnectionState.idle,
        statusMessage: 'Desconectado',
        clearActive: true,
      );
    } else if (stage != null && stage != VPNStage.connected) {
      // Log all intermediate stages (authenticating, wait_connection, etc.)
      debugPrint('=== TUCUVPN: Stage — $stage / raw: $rawStage');
      ref.read(logProvider.notifier).add('  $rawStage');
    }
  }

  void _onStatusChange(VpnStatus? status) {}

  /// Called by the UI after it has displayed the snackbar.
  void clearLaunchError() {
    state = state.copyWith(clearLaunchError: true);
  }

  // ─── Launch external app ───────────────────────────────────────────────────

  // Channel must match the constant defined in MainActivity.kt
  static const _launcherChannel = MethodChannel('com.tucuvpn.tucuvpn/launcher');

  /// Uses PackageManager on the native side to find the real launcher Activity
  /// (CATEGORY_LAUNCHER then CATEGORY_LEANBACK_LAUNCHER as fallback) and
  /// starts it with FLAG_ACTIVITY_NEW_TASK — the same way a TV launcher opens apps.
  Future<void> _launchTargetApp() async {
    try {
      final success = await _launcherChannel.invokeMethod<bool>(
        'launchApp',
        {'packageName': kTargetAppPackage},
      );

      if (success != true) {
        debugPrint('launchApp returned false — app not found: $kTargetAppPackage');
        state = state.copyWith(launchError: 'App destino no encontrada: $kTargetAppPackage');
      }
    } catch (e) {
      debugPrint('launchApp channel error: $e');
      state = state.copyWith(launchError: 'Error al abrir app: $e');
    }
  }
}

final vpnProvider =
    NotifierProvider<VpnNotifier, VpnState>(VpnNotifier.new);
