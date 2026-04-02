import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';

import '../constants.dart';
import '../providers/servers_provider.dart';
import 'server_card.dart';

class ServerListPanel extends ConsumerWidget {
  const ServerListPanel({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final servers = ref.watch(serversProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'SERVIDORES',
          style: GoogleFonts.rajdhani(
            fontSize: 13,
            letterSpacing: 4,
            color: kCyan.withOpacity(0.55),
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 14),
        ...List.generate(
          servers.length,
          (i) => ServerCard(
            server: servers[i],
            index: i,
            autofocus: i == 0,
            onSelect: () =>
                ref.read(selectedServerProvider.notifier).state = i,
          ),
        ),
      ],
    );
  }
}
