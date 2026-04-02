import 'package:flutter/material.dart';

// ─── Target app launched immediately after VPN connects ──────────────────────
// Only place this needs to change when switching to the real package name.
const String kTargetAppPackage = 'com.google.android.youtube.tvx1x'; 

// ─── OTA updates ─────────────────────────────────────────────────────────────
const String kGithubReleasesUrl =
    'https://api.github.com/repos/XxZman/tucuvpn/releases/latest';

// ─── VPN ─────────────────────────────────────────────────────────────────────
const int kDefaultTimerSeconds = 60;
const int kVpnFailoverSeconds = 8;

// ─── SharedPreferences ───────────────────────────────────────────────────────
const String kPrefTimerSeconds = 'timer_seconds';

// ─── Color palette ───────────────────────────────────────────────────────────
const Color kBg      = Color(0xFF0A0A0F);
const Color kSurface = Color(0xFF111118);
const Color kBorder  = Color(0xFF1E1E2E);
const Color kCyan    = Color(0xFF00FFD1);
const Color kPink    = Color(0xFFFF2D6B);
const Color kText    = Color(0xFFE0E0FF);
const Color kWatermark = Color(0x30FFFFFF);
