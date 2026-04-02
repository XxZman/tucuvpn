import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import 'constants.dart';
import 'screens/home_screen.dart';
import 'screens/settings_screen.dart';

class TucuVpnApp extends StatelessWidget {
  const TucuVpnApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'TucuVPN',
      debugShowCheckedModeBanner: false,
      theme: _buildTheme(),
      initialRoute: '/',
      routes: {
        '/': (_) => const HomeScreen(),
        '/settings': (_) => const SettingsScreen(),
      },
    );
  }

  ThemeData _buildTheme() {
    final base = ThemeData.dark();
    return base.copyWith(
      scaffoldBackgroundColor: kBg,
      colorScheme: base.colorScheme.copyWith(
        surface: kSurface,
        primary: kCyan,
        secondary: kPink,
      ),
      textTheme: GoogleFonts.rajdhaniTextTheme(base.textTheme).apply(
        bodyColor: kText,
        displayColor: kText,
      ),
      focusColor: kCyan.withOpacity(0.15),
      splashColor: Colors.transparent,
      highlightColor: Colors.transparent,
    );
  }
}
