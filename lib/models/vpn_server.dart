enum ServerStatus { idle, trying, active, failed }

class VpnServer {
  final String id;
  final String name;
  final String flag;
  final String configPath;
  final ServerStatus status;

  const VpnServer({
    required this.id,
    required this.name,
    required this.flag,
    required this.configPath,
    this.status = ServerStatus.idle,
  });

  VpnServer copyWith({ServerStatus? status}) => VpnServer(
        id: id,
        name: name,
        flag: flag,
        configPath: configPath,
        status: status ?? this.status,
      );
}

const List<VpnServer> kServers = [
  VpnServer(
    id: 'brazil',
    name: 'Brasil',
    flag: '🇧🇷',
    configPath: 'assets/configs/brazil.ovpn',
  ),
  VpnServer(
    id: 'japan',
    name: 'Japón',
    flag: '🇯🇵',
    configPath: 'assets/configs/japan.ovpn',
  ),
  VpnServer(
    id: 'canada',
    name: 'Canadá',
    flag: '🇨🇦',
    configPath: 'assets/configs/canada.ovpn',
  ),
  VpnServer(
    id: 'usa',
    name: 'EEUU',
    flag: '🇺🇸',
    configPath: 'assets/configs/usa.ovpn',
  ),
];
