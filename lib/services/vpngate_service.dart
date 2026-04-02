import 'dart:convert';

import 'package:http/http.dart' as http;

class VpnGateServer {
  final String ip;
  final String country; // CountryShort: JP, CA, US, BR
  final double speedMbps;
  final int pingMs;
  final String configData; // decoded OpenVPN config text

  const VpnGateServer({
    required this.ip,
    required this.country,
    required this.speedMbps,
    required this.pingMs,
    required this.configData,
  });
}

const _kAllowedCountries = {'JP', 'CA', 'US', 'BR'};
const _kVpnGateUrl = 'https://www.vpngate.net/api/iphone/';

/// Downloads fresh servers from VPN Gate, filters by country, decodes base64
/// configs, sorts by speed descending, and returns a map keyed by CountryShort.
///
/// Throws on network error or non-200 HTTP status.
Future<Map<String, List<VpnGateServer>>> fetchVpnGateServers() async {
  final response = await http
      .get(Uri.parse(_kVpnGateUrl))
      .timeout(const Duration(seconds: 15));

  if (response.statusCode != 200) {
    throw Exception('VPN Gate returned HTTP ${response.statusCode}');
  }

  // CSV format:
  //   line 0: starts with '*' (comment / column header with leading *)
  //   line 1: real header row (same columns, no *)
  //   line 2+: data rows
  //
  // Columns (0-indexed):
  //   0  HostName
  //   1  IP
  //   2  Score
  //   3  Ping (ms)
  //   4  Speed (bytes/sec)
  //   5  CountryLong
  //   6  CountryShort
  //   7  NumVpnSessions
  //   8  Uptime
  //   9  TotalUsers
  //   10 TotalTraffic
  //   11 LogType
  //   12 Operator
  //   13 Message
  //   14 OpenVPN_ConfigData_Base64
  final lines = const LineSplitter().convert(response.body);

  final servers = <VpnGateServer>[];

  for (int i = 2; i < lines.length; i++) {
    final line = lines[i].trim();
    if (line.isEmpty) continue;

    final parts = line.split(',');
    if (parts.length < 15) continue;

    final countryShort = parts[6].trim().toUpperCase();
    if (!_kAllowedCountries.contains(countryShort)) continue;

    final configBase64 = parts[14].trim();
    if (configBase64.isEmpty) continue;

    String configData;
    try {
      configData = utf8.decode(base64.decode(configBase64));
    } catch (_) {
      continue; // skip rows with malformed base64
    }

    final speedBps = int.tryParse(parts[4].trim()) ?? 0;
    final ping = int.tryParse(parts[3].trim()) ?? 999;

    servers.add(VpnGateServer(
      ip: parts[1].trim(),
      country: countryShort,
      speedMbps: speedBps / 1000000.0,
      pingMs: ping,
      configData: configData,
    ));
  }

  // Sort all servers by speed descending before grouping.
  servers.sort((a, b) => b.speedMbps.compareTo(a.speedMbps));

  // Group by CountryShort — order within each group already sorted by speed.
  final result = <String, List<VpnGateServer>>{};
  for (final server in servers) {
    result.putIfAbsent(server.country, () => []).add(server);
  }
  return result;
}
