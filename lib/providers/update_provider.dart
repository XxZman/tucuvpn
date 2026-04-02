import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../services/update_service.dart';

class UpdateInfo {
  final bool hasUpdate;
  final String? apkUrl;
  final String? newVersion;
  const UpdateInfo({this.hasUpdate = false, this.apkUrl, this.newVersion});
}

final updateProvider = FutureProvider<UpdateInfo>((ref) async {
  final release = await fetchLatestRelease();
  if (release == null) return const UpdateInfo();

  final info = await PackageInfo.fromPlatform();
  final latest = release.tagName.replaceFirst(RegExp(r'^v'), '');

  if (_isNewer(latest, info.version)) {
    return UpdateInfo(
      hasUpdate: true,
      apkUrl: release.apkUrl,
      newVersion: release.tagName,
    );
  }
  return const UpdateInfo();
});

bool _isNewer(String latest, String current) {
  try {
    final l = latest.split('.').map(int.parse).toList();
    final c = current.split('.').map(int.parse).toList();
    for (int i = 0; i < 3; i++) {
      final lv = i < l.length ? l[i] : 0;
      final cv = i < c.length ? c[i] : 0;
      if (lv > cv) return true;
      if (lv < cv) return false;
    }
    return false;
  } catch (_) {
    return false;
  }
}
