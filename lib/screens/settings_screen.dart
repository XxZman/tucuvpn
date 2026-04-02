import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../providers/settings_provider.dart';
import '../providers/timer_provider.dart';
import '../widgets/background.dart';
import '../widgets/tv_focusable.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  late final TextEditingController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController(text: kDefaultTimerSeconds.toString());
    // Load persisted value
    ref.read(settingsProvider.future).then((v) {
      if (mounted) _ctrl.text = v.toString();
    });
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final v = int.tryParse(_ctrl.text);
    if (v != null && v > 0) {
      await ref.read(settingsProvider.notifier).setTimerSeconds(v);
      await ref.read(timerProvider.notifier).restart();
    }
    if (mounted) Navigator.pop(context);
  }

  void _adjust(int delta) {
    final v = int.tryParse(_ctrl.text) ?? kDefaultTimerSeconds;
    final next = v + delta;
    if (next > 0) _ctrl.text = next.toString();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvoked: (didPop) {
        if (!didPop) _save();
      },
      child: Scaffold(
        backgroundColor: kBg,
        body: CyberpunkBackground(
          child: SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _buildHeader(),
                  const SizedBox(height: 48),
                  _buildTimerRow(),
                  const SizedBox(height: 48),
                  _buildSaveButton(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Row(
      children: [
        TvFocusable(
          autofocus: false,
          onActivate: _save,
          builder: (_, focused) => Row(
            children: [
              Icon(Icons.arrow_back_ios,
                  color: focused ? kCyan : kText.withOpacity(0.7), size: 18),
              const SizedBox(width: 6),
              Text(
                'VOLVER',
                style: GoogleFonts.rajdhani(
                  color: focused ? kCyan : kText.withOpacity(0.7),
                  fontSize: 15,
                  letterSpacing: 3,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 28),
        Text(
          'CONFIGURACIÓN',
          style: GoogleFonts.rajdhani(
            color: kText,
            fontSize: 26,
            letterSpacing: 4,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    );
  }

  Widget _buildTimerRow() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'TEMPORIZADOR (SEGUNDOS)',
          style: GoogleFonts.rajdhani(
            color: kCyan.withOpacity(0.65),
            fontSize: 13,
            letterSpacing: 3.5,
          ),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            SizedBox(
              width: 180,
              child: TextField(
                controller: _ctrl,
                autofocus: true,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                style: GoogleFonts.shareTechMono(color: kCyan, fontSize: 36),
                cursorColor: kCyan,
                decoration: InputDecoration(
                  filled: true,
                  fillColor: kSurface,
                  contentPadding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.zero,
                    borderSide: const BorderSide(color: kBorder),
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.zero,
                    borderSide: const BorderSide(color: kCyan, width: 2),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 14),
            Column(
              children: [
                _StepButton(icon: Icons.add, onTap: () => _adjust(10)),
                const SizedBox(height: 8),
                _StepButton(icon: Icons.remove, onTap: () => _adjust(-10)),
              ],
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildSaveButton() {
    return TvFocusable(
      onActivate: _save,
      builder: (_, focused) => AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        padding: const EdgeInsets.symmetric(horizontal: 36, vertical: 16),
        decoration: BoxDecoration(
          color: focused ? kCyan : kSurface,
          border: Border.all(color: focused ? kCyan : kBorder),
          boxShadow: focused
              ? [BoxShadow(color: kCyan.withOpacity(0.3), blurRadius: 18)]
              : null,
        ),
        child: Text(
          'GUARDAR',
          style: GoogleFonts.rajdhani(
            color: focused ? kBg : kCyan,
            fontSize: 18,
            letterSpacing: 4,
            fontWeight: FontWeight.w800,
          ),
        ),
      ),
    );
  }
}

// ─── +10 / −10 step buttons ───────────────────────────────────────────────────

class _StepButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;
  const _StepButton({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return TvFocusable(
      onActivate: onTap,
      builder: (_, focused) => AnimatedContainer(
        duration: const Duration(milliseconds: 100),
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: kSurface,
          border: Border.all(color: focused ? kCyan : kBorder),
          boxShadow: focused
              ? [BoxShadow(color: kCyan.withOpacity(0.2), blurRadius: 10)]
              : null,
        ),
        child: Icon(icon, color: focused ? kCyan : kText.withOpacity(0.7), size: 20),
      ),
    );
  }
}
