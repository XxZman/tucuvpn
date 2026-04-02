import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../providers/log_provider.dart';

class LogConsole extends ConsumerStatefulWidget {
  const LogConsole({super.key});

  @override
  ConsumerState<LogConsole> createState() => _LogConsoleState();
}

class _LogConsoleState extends ConsumerState<LogConsole> {
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(
          _scroll.position.maxScrollExtent,
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final logs = ref.watch(logProvider);
    _scrollToBottom();

    return Container(
      height: 108,
      decoration: BoxDecoration(
        color: kBg,
        border: Border.all(color: kCyan.withOpacity(0.3)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      child: ListView.builder(
        controller: _scroll,
        itemCount: logs.length,
        itemBuilder: (_, i) => Text(
          logs[i],
          style: GoogleFonts.shareTechMono(
            fontSize: 11,
            height: 1.5,
            color: kCyan.withOpacity(0.7),
          ),
        ),
      ),
    );
  }
}
