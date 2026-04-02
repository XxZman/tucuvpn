import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../constants.dart';
import '../models/vpn_server.dart';
import '../services/toast_service.dart';
import '../services/vpngate_service.dart';
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

// ─── Channel constants (must match MainActivity.kt / VpnHelper.kt) ───────────
const _vpnChannel      = MethodChannel('com.tucuvpn.tucuvpn/vpn');
const _vpnStageChannel = EventChannel('com.tucuvpn.tucuvpn/vpnstage');

// ─── Stage classification helpers ────────────────────────────────────────────
bool _isConnected(String s)     => s == 'connected';
bool _isError(String s)         => s == 'error' || s == 'auth_failed' || s == 'denied';
bool _isDisconnected(String s)  => s == 'disconnected' || s == 'exiting' || s == 'nonetwork';

class VpnNotifier extends Notifier<VpnState> with WidgetsBindingObserver {
  StreamSubscription<dynamic>? _stageSub;
  Timer? _failoverTimer;
  int _attemptIndex = 0; // which country (index into kServers)
  int _startIndex   = 0; // country the user picked
  int _configIndex  = 0; // which config file within the current country
  bool _isConnecting = false;

  // Fresh servers downloaded from VPN Gate at connect time.
  // Keyed by CountryShort (JP, CA, US, BR). Empty if download failed.
  Map<String, List<VpnGateServer>> _freshConfigs = {};

  @override
  VpnState build() {
    // Subscribe to stage events from VpnHelper.kt via EventChannel.
    _stageSub = _vpnStageChannel
        .receiveBroadcastStream()
        .listen((dynamic raw) => _onRawStage(raw as String));

    // Clean up any leftover VPN session from a previous run.
    // Delayed so the EventChannel onListen fires first (Bug 3 race condition).
    Future.delayed(const Duration(milliseconds: 500), () async {
      try {
        await _vpnChannel.invokeMethod<void>('disconnect');
      } catch (_) {}
    });

    WidgetsBinding.instance.addObserver(this);
    ref.onDispose(() async {
      WidgetsBinding.instance.removeObserver(this);
      _failoverTimer?.cancel();
      _stageSub?.cancel();
      try { await _vpnChannel.invokeMethod<void>('disconnect'); } catch (_) {}
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
    _startIndex    = serverIndex;
    _attemptIndex  = serverIndex;
    _configIndex   = 0;
    _freshConfigs  = {};
    ref.read(logProvider.notifier).add('› Lanzando VPN...');
    await _downloadFreshServers();
    await _tryCurrentConfig();
  }

  Future<void> disconnect() async {
    _failoverTimer?.cancel();
    _isConnecting = false;
    _attemptIndex = 0;
    _configIndex  = 0;
    _startIndex   = 0;
    _freshConfigs = {};
    try { await _vpnChannel.invokeMethod<void>('disconnect'); } catch (_) {}
    ref.read(serversProvider.notifier).setAllIdle();
    state = state.copyWith(
      connectionState: VpnConnectionState.idle,
      statusMessage: 'Desconectado',
      clearActive: true,
    );
  }

  // ─── VPN Gate fresh server download ────────────────────────────────────────

  Future<void> _downloadFreshServers() async {
    ref.read(logProvider.notifier).add('› Descargando servidores frescos...');
    try {
      _freshConfigs = await fetchVpnGateServers();
      final total = _freshConfigs.values
          .fold<int>(0, (sum, list) => sum + list.length);
      ref.read(logProvider.notifier).add('✓ $total servidores encontrados');
    } catch (e) {
      debugPrint('=== TUCUVPN: VPN Gate download failed: $e');
      ref.read(logProvider.notifier)
          .add('› Sin conexión, usando configs locales...');
      _freshConfigs = {};
    }
  }

  // ─── Two-level failover: config files within a country, then next country ──

  Future<void> _tryCurrentConfig() async {
    _isConnecting = true;
    final servers = ref.read(serversProvider);
    final server  = servers[_attemptIndex];
    final prefix  = server.configPrefix;

    // Prefer fresh VPN Gate servers for this country; fall back to bundled assets.
    final liveList   = _freshConfigs[prefix];
    final usingLive  = liveList != null && liveList.isNotEmpty;
    final totalConfigs = usingLive ? liveList!.length : server.fileCount;

    if (totalConfigs == 0) {
      debugPrint('=== TUCUVPN: No configs for ${server.name}, skipping country');
      ref.read(logProvider.notifier)
          .add('› ${server.name}: sin configs, saltando...');
      await _tryNextCountry();
      return;
    }

    if (_configIndex >= totalConfigs) {
      await _tryNextCountry();
      return;
    }

    final configNum = _configIndex + 1;

    ref.read(serversProvider.notifier).setAllIdle();
    ref.read(serversProvider.notifier).setStatus(_attemptIndex, ServerStatus.trying);

    state = state.copyWith(
      connectionState: VpnConnectionState.connecting,
      statusMessage: 'Conectando a ${server.name} ($configNum/$totalConfigs)…',
      clearActive: true,
    );

    if (usingLive) {
      ref.read(logProvider.notifier)
          .add('› ${server.name} [live] ($configNum/$totalConfigs)');
    } else {
      ref.read(logProvider.notifier)
          .add('› Contactando ${server.configPrefix}_$configNum ($configNum/$totalConfigs)');
    }

    try {
      String config;

      if (usingLive) {
        final liveServer = liveList![_configIndex];
        debugPrint(
            '=== TUCUVPN: Trying ${server.name} live $configNum/$totalConfigs (${liveServer.ip})');
        config = liveServer.configData;
      } else {
        final configPath = server.configPaths[_configIndex];
        debugPrint('=== TUCUVPN: Trying ${server.name} $configNum/$totalConfigs');
        debugPrint('=== TUCUVPN: Config path: $configPath');
        config = await rootBundle.loadString(configPath);
        debugPrint('=== TUCUVPN: Config loaded, length: ${config.length}');
      }

      // Uncomment auth-user-pass (VPN Gate configs ship with it commented out)
      // and append legacy cipher compatibility for SoftEther servers.
      final patchedConfig = config
              .replaceAll('#auth-user-pass', 'auth-user-pass') +
          '\ndata-ciphers AES-128-CBC:AES-256-CBC\ndata-ciphers-fallback AES-128-CBC\nreneg-sec 0\n';

      ref.read(logProvider.notifier).add('› Autenticando...');

      await _vpnChannel.invokeMethod<void>('connect', {
        'config': patchedConfig,
        'name': server.name,
      });

      debugPrint('=== TUCUVPN: connect() called successfully');

      _failoverTimer?.cancel();
      _failoverTimer = Timer(
        const Duration(seconds: kVpnFailoverSeconds),
        () {
          debugPrint(
              '=== TUCUVPN: Failover timer fired for ${server.name} $configNum/$totalConfigs');
          if (_isConnecting) _tryNextConfig();
        },
      );
    } catch (e) {
      debugPrint('=== TUCUVPN: Error loading/starting config: $e');
      ref.read(logProvider.notifier).add('› Error cargando config: $e');
      _tryNextConfig();
    }
  }

  /// Move to the next config file within the same country.
  /// Falls through to _tryNextCountry() when all files are exhausted.
  Future<void> _tryNextConfig() async {
    _failoverTimer?.cancel();
    try { await _vpnChannel.invokeMethod<void>('disconnect'); } catch (_) {}

    final server     = ref.read(serversProvider)[_attemptIndex];
    final prefix     = server.configPrefix;
    final liveList   = _freshConfigs[prefix];
    final usingLive  = liveList != null && liveList.isNotEmpty;
    final totalConfigs = usingLive ? liveList!.length : server.fileCount;
    final nextNum    = _configIndex + 2; // 1-based next index
    _configIndex++;

    if (_configIndex < totalConfigs) {
      if (usingLive) {
        ref.read(logProvider.notifier)
            .add('› Fallo, probando servidor live $nextNum...');
      } else {
        ref.read(logProvider.notifier)
            .add('› Fallo, probando ${server.configPrefix}_$nextNum...');
      }
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
    try { await _vpnChannel.invokeMethod<void>('disconnect'); } catch (_) {}

    ref.read(serversProvider.notifier).setStatus(_attemptIndex, ServerStatus.failed);

    final failedName = kServers[_attemptIndex].name;
    _configIndex  = 0;
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

  // ─── ics-openvpn stage callbacks ──────────────────────────────────────────

  Future<void> _onRawStage(String stage) async {
    debugPrint('=== TUCUVPN: stage=$stage  connecting=$_isConnecting');

    // Debug log lines from VpnHelper — show in console, don't change state.
    if (stage.startsWith('log:')) {
      ref.read(logProvider.notifier).add('  [dbg] ${stage.substring(4)}');
      return;
    }

    // Detailed error messages from VpnHelper (e.g. "error:IOException: …").
    if (stage.startsWith('error:') && _isConnecting) {
      ref.read(logProvider.notifier).add('✗ ${stage}');
      _failoverTimer?.cancel();
      _tryNextConfig();
      return;
    }

    if (_isConnected(stage)) {
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
    } else if (_isError(stage) && _isConnecting) {
      debugPrint('=== TUCUVPN: VPN error stage — raw: $stage');
      ref.read(logProvider.notifier)
          .add('✗ Error: $stage — cambiando servidor...');
      _failoverTimer?.cancel();
      _tryNextConfig();
    } else if (_isDisconnected(stage) && _isConnecting) {
      // Disconnected unexpectedly while still trying to connect.
      debugPrint('=== TUCUVPN: Unexpected disconnect while connecting — $stage');
      ref.read(logProvider.notifier)
          .add('✗ Desconectado inesperado ($stage)');
    } else if (_isDisconnected(stage) && !_isConnecting) {
      ref.read(serversProvider.notifier).setAllIdle();
      state = state.copyWith(
        connectionState: VpnConnectionState.idle,
        statusMessage: 'Desconectado',
        clearActive: true,
      );
    } else {
      // Intermediate stages: connecting, auth, wait_connection, assign_ip, etc.
      debugPrint('=== TUCUVPN: intermediate stage=$stage');
      ref.read(logProvider.notifier).add('  $stage');
    }
  }

  /// Called by the UI after it has displayed the snackbar.
  void clearLaunchError() {
    state = state.copyWith(clearLaunchError: true);
  }

  // ─── Launch external app ───────────────────────────────────────────────────

  static const _launcherChannel = MethodChannel('com.tucuvpn.tucuvpn/launcher');

  Future<void> _launchTargetApp() async {
    try {
      final success = await _launcherChannel.invokeMethod<bool>(
        'launchApp',
        {'packageName': kTargetAppPackage},
      );

      if (success != true) {
        debugPrint('launchApp returned false — app not found: $kTargetAppPackage');
        state = state.copyWith(
            launchError: 'App destino no encontrada: $kTargetAppPackage');
      }
    } catch (e) {
      debugPrint('launchApp channel error: $e');
      state = state.copyWith(launchError: 'Error al abrir app: $e');
    }
  }
}

final vpnProvider =
    NotifierProvider<VpnNotifier, VpnState>(VpnNotifier.new);
