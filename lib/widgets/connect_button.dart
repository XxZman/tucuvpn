import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../providers/servers_provider.dart';
import '../providers/vpn_provider.dart';
import 'tv_focusable.dart';

class ConnectButton extends ConsumerStatefulWidget {
  const ConnectButton({super.key});

  @override
  ConsumerState<ConnectButton> createState() => _ConnectButtonState();
}

class _ConnectButtonState extends ConsumerState<ConnectButton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _borderCtrl;
  late final Animation<double> _borderGlow;

  @override
  void initState() {
    super.initState();
    _borderCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 700),
    );
    _borderGlow = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _borderCtrl, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _borderCtrl.dispose();
    super.dispose();
  }

  void _onFocused(bool focused) {
    if (focused) {
      _borderCtrl.repeat(reverse: true);
    } else {
      _borderCtrl.stop();
      _borderCtrl.value = 0;
    }
  }

  void _handleTap() {
    final vpn = ref.read(vpnProvider);
    if (vpn.isConnected) {
      ref.read(vpnProvider.notifier).disconnect();
    } else if (!vpn.isConnecting) {
      final selected = ref.read(selectedServerProvider);
      ref.read(vpnProvider.notifier).connect(selected);
    }
  }

  @override
  Widget build(BuildContext context) {
    final vpn = ref.watch(vpnProvider);
    final isConnected = vpn.isConnected;
    final isConnecting = vpn.isConnecting;

    final label = isConnected
        ? 'DESCONECTAR'
        : isConnecting
            ? 'CONECTANDO…'
            : 'CONECTAR';
    final accent = isConnected ? kPink : kCyan;

    return TvFocusable(
      onActivate: _handleTap,
      builder: (_, focused) {
        if (focused) {
          if (!_borderCtrl.isAnimating) _borderCtrl.repeat(reverse: true);
        } else {
          if (_borderCtrl.isAnimating) {
            _borderCtrl.stop();
            _borderCtrl.value = 0;
          }
        }
        return AnimatedBuilder(
          animation: _borderGlow,
          builder: (_, __) => Container(
            width: double.infinity,
            height: 62,
            decoration: BoxDecoration(
              color: kSurface,
              border: Border.all(
                color: focused
                    ? accent.withOpacity(0.5 + 0.5 * _borderGlow.value)
                    : isConnected
                        ? kPink.withOpacity(0.7)
                        : kBorder,
                width: focused ? 2 : 1,
              ),
              boxShadow: focused
                  ? [
                      BoxShadow(
                        color: accent.withOpacity(0.25 * _borderGlow.value),
                        blurRadius: 22,
                        spreadRadius: 2,
                      ),
                    ]
                  : null,
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (isConnecting)
                  SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: AlwaysStoppedAnimation(kCyan),
                    ),
                  )
                else
                  Icon(
                    isConnected ? Icons.power_settings_new : Icons.power,
                    color: accent,
                    size: 22,
                  ),
                const SizedBox(width: 12),
                Text(
                  label,
                  style: GoogleFonts.rajdhani(
                    fontSize: 20,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 3.5,
                    color: accent,
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
