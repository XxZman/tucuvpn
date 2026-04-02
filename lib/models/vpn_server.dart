enum ServerStatus { idle, trying, active, failed }

class VpnServer {
  final String id;
  final String name;
  final String flag;
  final String configPrefix; // e.g. "JP", "CA", "US", "BR"
  final int fileCount;
  final ServerStatus status;

  const VpnServer({
    required this.id,
    required this.name,
    required this.flag,
    required this.configPrefix,
    this.fileCount = 0,
    this.status = ServerStatus.idle,
  });

  /// All asset paths for this country in order: PREFIX_1.ovpn, PREFIX_2.ovpn, …
  List<String> get configPaths => List.generate(
        fileCount,
        (i) => 'assets/configs/${configPrefix}_${i + 1}.ovpn',
      );

  VpnServer copyWith({ServerStatus? status}) => VpnServer(
        id: id,
        name: name,
        flag: flag,
        configPrefix: configPrefix,
        fileCount: fileCount,
        status: status ?? this.status,
      );
}

const List<VpnServer> kServers = [
  VpnServer(
    id: 'brazil',
    name: 'Brasil',
    flag: '🇧🇷',
    configPrefix: 'BR',
    fileCount: 0,
  ),
  VpnServer(
    id: 'japan',
    name: 'Japón',
    flag: '🇯🇵',
    configPrefix: 'JP',
    fileCount: 39,
  ),
  VpnServer(
    id: 'canada',
    name: 'Canadá',
    flag: '🇨🇦',
    configPrefix: 'CA',
    fileCount: 2,
  ),
  VpnServer(
    id: 'usa',
    name: 'EEUU',
    flag: '🇺🇸',
    configPrefix: 'US',
    fileCount: 3,
  ),
];
