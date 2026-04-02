import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../providers/timer_provider.dart';

class TimerDisplay extends ConsumerStatefulWidget {
  const TimerDisplay({super.key});

  @override
  ConsumerState<TimerDisplay> createState() => _TimerDisplayState();
}

class _TimerDisplayState extends ConsumerState<TimerDisplay>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double> _glow;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2000),
    )..repeat(reverse: true);
    _glow = Tween<double>(begin: 0.45, end: 1.0).animate(
      CurvedAnimation(parent: _ctrl, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  String _fmt(int s) {
    final m = s ~/ 60;
    final sec = s % 60;
    return '${m.toString().padLeft(2, '0')}:${sec.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final seconds = ref.watch(timerProvider);
    // Turn the timer red when ≤ 10 s left
    final color = seconds <= 10 ? kPink : kCyan;
    return AnimatedBuilder(
      animation: _glow,
      builder: (_, __) => Text(
        _fmt(seconds),
        style: GoogleFonts.shareTechMono(
          fontSize: 76,
          color: color,
          shadows: [
            Shadow(
              color: color.withOpacity(0.75 * _glow.value),
              blurRadius: 22 * _glow.value,
            ),
            Shadow(
              color: color.withOpacity(0.35 * _glow.value),
              blurRadius: 46 * _glow.value,
            ),
          ],
        ),
      ),
    );
  }
}
