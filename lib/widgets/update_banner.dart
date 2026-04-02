import 'package:android_intent_plus/android_intent.dart';
import 'package:android_intent_plus/flag.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../providers/update_provider.dart';
import 'tv_focusable.dart';

class UpdateBanner extends ConsumerStatefulWidget {
  const UpdateBanner({super.key});

  @override
  ConsumerState<UpdateBanner> createState() => _UpdateBannerState();
}

class _UpdateBannerState extends ConsumerState<UpdateBanner> {
  bool _dismissed = false;

  @override
  Widget build(BuildContext context) {
    if (_dismissed) return const SizedBox.shrink();

    return ref.watch(updateProvider).when(
          data: (info) =>
              info.hasUpdate ? _Banner(info: info, onDismiss: _dismiss) : const SizedBox.shrink(),
          loading: () => const SizedBox.shrink(),
          error: (_, __) => const SizedBox.shrink(),
        );
  }

  void _dismiss() => setState(() => _dismissed = true);
}

class _Banner extends StatelessWidget {
  final UpdateInfo info;
  final VoidCallback onDismiss;
  const _Banner({required this.info, required this.onDismiss});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      decoration: BoxDecoration(
        color: kSurface,
        border: Border.all(color: kCyan.withOpacity(0.45)),
        boxShadow: [BoxShadow(color: kCyan.withOpacity(0.08), blurRadius: 14)],
      ),
      child: Row(
        children: [
          const Icon(Icons.system_update_alt, color: kCyan, size: 18),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              'NUEVA VERSIÓN DISPONIBLE  ${info.newVersion ?? ''}',
              style: GoogleFonts.rajdhani(
                color: kCyan,
                fontSize: 15,
                letterSpacing: 1.5,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          TvFocusable(
            onActivate: () => _launchApk(info.apkUrl!),
            builder: (_, focused) => Text(
              'ACTUALIZAR',
              style: GoogleFonts.rajdhani(
                color: focused ? kCyan : kPink,
                fontSize: 14,
                letterSpacing: 2,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 14),
          GestureDetector(
            onTap: onDismiss,
            child: Icon(Icons.close, color: kText.withOpacity(0.45), size: 17),
          ),
        ],
      ),
    );
  }

  Future<void> _launchApk(String url) async {
    try {
      await AndroidIntent(
        action: 'android.intent.action.VIEW',
        data: url,
        flags: <int>[Flag.FLAG_ACTIVITY_NEW_TASK],
      ).launch();
    } catch (_) {}
  }
}
