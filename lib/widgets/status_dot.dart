import 'package:flutter/material.dart';

import '../constants.dart';
import '../providers/vpn_provider.dart';

class StatusDot extends StatefulWidget {
  final VpnConnectionState connectionState;
  const StatusDot({super.key, required this.connectionState});

  @override
  State<StatusDot> createState() => _StatusDotState();
}

class _StatusDotState extends State<StatusDot>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double> _pulse;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1100),
    );
    _pulse = Tween<double>(begin: 0.55, end: 1.0).animate(
      CurvedAnimation(parent: _ctrl, curve: Curves.easeInOut),
    );
    _sync();
  }

  @override
  void didUpdateWidget(StatusDot old) {
    super.didUpdateWidget(old);
    if (old.connectionState != widget.connectionState) _sync();
  }

  void _sync() {
    final animate = widget.connectionState == VpnConnectionState.connecting ||
        widget.connectionState == VpnConnectionState.connected;
    if (animate) {
      _ctrl.repeat(reverse: true);
    } else {
      _ctrl.stop();
      _ctrl.value = 1.0;
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Color get _color {
    switch (widget.connectionState) {
      case VpnConnectionState.connected:
        return kCyan;
      case VpnConnectionState.connecting:
        return kPink;
      case VpnConnectionState.failed:
        return kPink.withOpacity(0.6);
      case VpnConnectionState.idle:
        return Colors.grey.withOpacity(0.5);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _pulse,
      builder: (_, __) => Container(
        width: 14,
        height: 14,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: _color.withOpacity(_pulse.value),
          boxShadow: widget.connectionState != VpnConnectionState.idle
              ? [
                  BoxShadow(
                    color: _color.withOpacity(0.55 * _pulse.value),
                    blurRadius: 10,
                    spreadRadius: 2,
                  ),
                ]
              : null,
        ),
      ),
    );
  }
}
