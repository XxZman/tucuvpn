import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';

/// Full-screen cyberpunk background: dark base + grid lines + scanlines.
/// Also renders the persistent watermark in the bottom-right corner.
class CyberpunkBackground extends StatelessWidget {
  final Widget child;
  const CyberpunkBackground({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        // 1. Solid base color
        const ColoredBox(color: kBg),
        // 2. Grid
        const RepaintBoundary(child: _GridOverlay()),
        // 3. Scanlines
        const RepaintBoundary(child: _ScanlineOverlay()),
        // 4. Content
        child,
        // 5. Watermark — always on top, always visible
        Positioned(
          bottom: 14,
          right: 18,
          child: Text(
            'app por BrAM',
            style: GoogleFonts.shareTechMono(
              fontSize: 11,
              color: kWatermark,
            ),
          ),
        ),
      ],
    );
  }
}

class _GridOverlay extends StatelessWidget {
  const _GridOverlay();

  @override
  Widget build(BuildContext context) {
    return CustomPaint(painter: _GridPainter());
  }
}

class _ScanlineOverlay extends StatelessWidget {
  const _ScanlineOverlay();

  @override
  Widget build(BuildContext context) {
    return CustomPaint(painter: _ScanlinePainter());
  }
}

class _GridPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = kCyan.withOpacity(0.03)
      ..strokeWidth = 0.5;
    const step = 40.0;
    for (double x = 0; x < size.width; x += step) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), paint);
    }
    for (double y = 0; y < size.height; y += step) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), paint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter _) => false;
}

class _ScanlinePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.black.withOpacity(0.07)
      ..strokeWidth = 1;
    for (double y = 0; y < size.height; y += 4) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), paint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter _) => false;
}
