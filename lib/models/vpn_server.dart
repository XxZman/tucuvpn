enum ServerStatus { idle, trying, active, failed }

class VpnServer {
  final String id;
  final String name;
  final String flag;
  final String configPath;
  final int fileCount;
  final ServerStatus status;

  const VpnServer({
    required this.id,
    required this.name,
    required this.flag,
    required this.configPath,
    this.fileCount = 0,
    this.status = ServerStatus.idle,
  });

  VpnServer copyWith({ServerStatus? status}) => VpnServer(
        id: id,
        name: name,
        flag: flag,
        configPath: configPath,
        fileCount: fileCount,
        status: status ?? this.status,
      );
}

const List<VpnServer> kServers = [
  VpnServer(
    id: 'brazil',
    name: 'Brasil',
    flag: '🇧🇷',
    configPath: 'assets/configs/brazil.ovpn',
    fileCount: 0,
  ),
  VpnServer(
    id: 'japan',
    name: 'Japón',
    flag: '🇯🇵',
    configPath: 'assets/configs/japan.ovpn',
    fileCount: 39,
  ),
  VpnServer(
    id: 'canada',
    name: 'Canadá',
    flag: '🇨🇦',
    configPath: 'assets/configs/canada.ovpn',
    fileCount: 2,
  ),
  VpnServer(
    id: 'usa',
    name: 'EEUU',
    flag: '🇺🇸',
    configPath: 'assets/configs/usa.ovpn',
    fileCount: 3,
  ),
];
