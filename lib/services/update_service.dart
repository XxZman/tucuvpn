import 'dart:convert';

import 'package:http/http.dart' as http;

import '../constants.dart';

class ReleaseInfo {
  final String tagName;
  final String apkUrl;
  const ReleaseInfo({required this.tagName, required this.apkUrl});
}

Future<ReleaseInfo?> fetchLatestRelease() async {
  try {
    final response = await http
        .get(Uri.parse(kGithubReleasesUrl))
        .timeout(const Duration(seconds: 10));

    if (response.statusCode != 200) return null;

    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final tagName = json['tag_name'] as String? ?? '';
    final assets = json['assets'] as List<dynamic>? ?? [];

    final apkAsset = assets.cast<Map<String, dynamic>>().firstWhere(
          (a) => (a['name'] as String).endsWith('.apk'),
          orElse: () => {},
        );

    if (apkAsset.isEmpty) return null;

    return ReleaseInfo(
      tagName: tagName,
      apkUrl: apkAsset['browser_download_url'] as String,
    );
  } catch (_) {
    return null;
  }
}
