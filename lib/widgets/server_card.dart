import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../models/vpn_server.dart';
import 'tv_focusable.dart';

class ServerCard extends StatefulWidget {
  final VpnServer server;
  final int index;
  final bool autofocus;
  final VoidCallback? onSelect;

  const ServerCard({
    super.key,
    required this.server,
    required this.index,
    this.autofocus = false,
    this.onSelect,
  });

  @override
  State<ServerCard> createState() => _ServerCardState();
}

class _ServerCardState extends State<ServerCard>
    with SingleTickerProviderStateMixin {
  late final AnimationController _glowCtrl;

  @override
  void initState() {
    super.initState();
    _glowCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1300),
    );
    _syncGlow();
  }

  @override
  void didUpdateWidget(ServerCard old) {
    super.didUpdateWidget(old);
    if (old.server.status != widget.server.status) _syncGlow();
  }

  void _syncGlow() {
    if (widget.server.status == ServerStatus.active) {
      _glowCtrl.repeat(reverse: true);
    } else {
      _glowCtrl.stop();
      _glowCtrl.value = 0;
    }
  }

  @override
  void dispose() {
    _glowCtrl.dispose();
    super.dispose();
  }

  Color get _statusColor {
    switch (widget.server.status) {
      case ServerStatus.active:
        return kCyan;
      case ServerStatus.trying:
        return kPink;
      case ServerStatus.failed:
        return kPink.withOpacity(0.5);
      case ServerStatus.idle:
        return Colors.transparent;
    }
  }

  String get _statusLabel {
    switch (widget.server.status) {
      case ServerStatus.active:
        return 'ACTIVO';
      case ServerStatus.trying:
        return 'PROBANDO';
      case ServerStatus.failed:
        return 'FALLIDO';
      case ServerStatus.idle:
        return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    return TvFocusable(
      autofocus: widget.autofocus,
      onActivate: widget.onSelect,
      builder: (_, focused) => AnimatedBuilder(
        animation: _glowCtrl,
        builder: (_, __) {
          final isActive = widget.server.status == ServerStatus.active;
          final gv = _glowCtrl.value;
          return AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            margin: const EdgeInsets.symmetric(vertical: 5),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 15),
            transform: Matrix4.identity()..scale(focused ? 1.025 : 1.0),
            transformAlignment: Alignment.centerLeft,
            decoration: BoxDecoration(
              color: focused
                  ? kCyan.withOpacity(0.07)
                  : kSurface,
              border: Border.all(
                color: focused
                    ? kCyan
                    : isActive
                        ? kCyan.withOpacity(0.45)
                        : kBorder,
                width: focused ? 2 : 1,
              ),
              boxShadow: [
                if (focused)
                  BoxShadow(
                    color: kCyan.withOpacity(0.22),
                    blurRadius: 18,
                    spreadRadius: 1,
                  ),
                if (isActive)
                  BoxShadow(
                    color: kCyan.withOpacity(0.12 + 0.1 * gv),
                    blurRadius: 28,
                    spreadRadius: 4,
                  ),
              ],
            ),
            child: Row(
              children: [
                Text(widget.server.flag,
                    style: const TextStyle(fontSize: 26)),
                const SizedBox(width: 14),
                Expanded(
                  child: Text(
                    widget.server.name.toUpperCase(),
                    style: GoogleFonts.rajdhani(
                      fontSize: 22,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 2.5,
                      color: focused || isActive ? kCyan : kText,
                    ),
                  ),
                ),
                if (_statusLabel.isNotEmpty)
                  Text(
                    _statusLabel,
                    style: GoogleFonts.shareTechMono(
                      fontSize: 11,
                      letterSpacing: 1.5,
                      color: _statusColor,
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}
