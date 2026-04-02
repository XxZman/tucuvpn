import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../providers/vpn_provider.dart';
import 'connect_button.dart';
import 'status_dot.dart';
import 'timer_display.dart';

class RightPanel extends ConsumerWidget {
  const RightPanel({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final vpn = ref.watch(vpnProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // ── Status row ──────────────────────────────────────────────────────
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            StatusDot(connectionState: vpn.connectionState),
            const SizedBox(width: 10),
            Flexible(
              child: Text(
                vpn.statusMessage.toUpperCase(),
                overflow: TextOverflow.ellipsis,
                style: GoogleFonts.shareTechMono(
                  fontSize: 12,
                  letterSpacing: 1.8,
                  color: vpn.isConnected
                      ? kCyan
                      : kText.withOpacity(0.55),
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 28),
        // ── Glowing countdown ───────────────────────────────────────────────
        const Center(child: TimerDisplay()),
        const SizedBox(height: 6),
        Center(
          child: Text(
            'TIEMPO RESTANTE',
            style: GoogleFonts.rajdhani(
              fontSize: 11,
              letterSpacing: 4,
              color: kText.withOpacity(0.35),
            ),
          ),
        ),
        const Spacer(),
        // ── Connect / Disconnect button ──────────────────────────────────────
        const ConnectButton(),
        const SizedBox(height: 12),
      ],
    );
  }
}
