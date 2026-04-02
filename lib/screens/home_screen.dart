import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../providers/timer_provider.dart'; // ensure timer starts
import '../providers/vpn_provider.dart';
import '../widgets/background.dart';
import '../widgets/server_list_panel.dart';
import '../widgets/right_panel.dart';
import '../widgets/update_banner.dart';
import 'settings_screen.dart';
import '../widgets/tv_focusable.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Initialise timer provider — countdown starts only when VPN connects.
    ref.watch(timerProvider);

    // Show a SnackBar when the target app could not be launched.
    ref.listen<VpnState>(vpnProvider, (_, next) {
      if (next.launchError != null) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              next.launchError!,
              style: const TextStyle(color: kText),
            ),
            backgroundColor: kPink,
            duration: const Duration(seconds: 4),
          ),
        );
        // Clear so the snackbar doesn't re-appear on the next rebuild.
        ref.read(vpnProvider.notifier).clearLaunchError();
      }
    });

    return Scaffold(
      backgroundColor: kBg,
      body: CyberpunkBackground(
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(32, 24, 32, 0),
            child: Column(
              children: [
                _TopBar(context),
                const SizedBox(height: 10),
                const UpdateBanner(),
                const Expanded(
                  child: _MainLayout(),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ─── Top bar (title + settings icon) ─────────────────────────────────────────

class _TopBar extends StatelessWidget {
  final BuildContext _ctx;
  const _TopBar(this._ctx);

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        // App name
        RichText(
          text: TextSpan(
            children: [
              TextSpan(
                text: 'TUCU',
                style: GoogleFonts.rajdhani(
                  fontSize: 30,
                  fontWeight: FontWeight.w800,
                  color: kCyan,
                  letterSpacing: 4,
                ),
              ),
              TextSpan(
                text: 'VPN',
                style: GoogleFonts.rajdhani(
                  fontSize: 30,
                  fontWeight: FontWeight.w800,
                  color: kPink,
                  letterSpacing: 4,
                ),
              ),
            ],
          ),
        ),
        const Spacer(),
        // Settings button
        TvFocusable(
          onActivate: () => Navigator.push(
            _ctx,
            MaterialPageRoute(builder: (_) => const SettingsScreen()),
          ),
          builder: (_, focused) => AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: kSurface,
              border: Border.all(
                color: focused ? kCyan : kBorder,
                width: focused ? 2 : 1,
              ),
              boxShadow: focused
                  ? [BoxShadow(color: kCyan.withOpacity(0.2), blurRadius: 14)]
                  : null,
            ),
            child: Icon(
              Icons.settings,
              color: focused ? kCyan : kText.withOpacity(0.55),
              size: 24,
            ),
          ),
        ),
      ],
    );
  }
}

// ─── 60 / 40 split layout ─────────────────────────────────────────────────────

class _MainLayout extends StatelessWidget {
  const _MainLayout();

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: const [
        Expanded(flex: 6, child: ServerListPanel()),
        SizedBox(width: 32),
        Expanded(flex: 4, child: RightPanel()),
      ],
    );
  }
}
