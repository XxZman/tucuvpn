#!/usr/bin/env python3
"""
descargar_vpn.py
Descarga la lista de servidores VPN Gate, filtra por pais y guarda los
archivos .ovpn, mostrando un resumen ordenado por velocidad.
"""

import urllib.request
import csv
import base64
import os
import io
import sys

# Forzar UTF-8 en la consola de Windows para poder mostrar emojis
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ── Configuracion ────────────────────────────────────────────────────────────

VPN_GATE_URL   = "http://www.vpngate.net/api/iphone/"
PAISES_FILTRO  = {"BR", "JP", "CA", "US"}
OUTPUT_DIR     = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                              "ovpn_descargados")
RESUMEN_FILE   = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                              "resumen_vpn.txt")

BANDERAS = {
    "BR": "Brasil   🇧🇷",
    "JP": "Japon    🇯🇵",
    "CA": "Canada   🇨🇦",
    "US": "EE.UU.   🇺🇸",
}

# ── Helpers ──────────────────────────────────────────────────────────────────

def bytes_to_mbps(bps: str) -> float:
    """Convierte bytes/seg a Mbps."""
    try:
        return round(int(bps) * 8 / 1_000_000, 2)
    except (ValueError, TypeError):
        return 0.0


def ping_quality(ms: str) -> str:
    """Devuelve calidad de senal segun latencia."""
    try:
        p = int(ms)
    except (ValueError, TypeError):
        return "Desconocido"
    if p <= 30:
        return "Excelente"
    if p <= 80:
        return "Bueno"
    if p <= 150:
        return "Regular"
    return "Malo"


def detect_protocol(ovpn_content: str) -> str:
    """Detecta protocolo OpenVPN (TCP/UDP) desde el contenido del config."""
    content_lower = ovpn_content.lower()
    if "proto tcp" in content_lower:
        return "OpenVPN TCP"
    if "proto udp" in content_lower:
        return "OpenVPN UDP"
    return "OpenVPN"


def uptime_days(seconds: str) -> float:
    """Convierte segundos de uptime a dias."""
    try:
        return round(int(seconds) / 86400, 1)
    except (ValueError, TypeError):
        return 0.0


def status(speed_mbps: float, uptime: float) -> str:
    return "Activo" if uptime > 0 and speed_mbps > 0 else "Sin datos"


def format_users(n: str) -> str:
    try:
        return f"{int(n):,}"
    except (ValueError, TypeError):
        return "0"


# ── Descarga y parseo ─────────────────────────────────────────────────────────

def download_csv() -> str:
    print("Descargando lista de servidores VPN Gate...")
    req = urllib.request.Request(
        VPN_GATE_URL,
        headers={"User-Agent": "Mozilla/5.0"}
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
    print(f"  {len(raw):,} bytes recibidos.\n")
    return raw


def parse_servers(raw_csv: str) -> list[dict]:
    """Parsea el CSV de VPN Gate (primera linea es comentario '#')."""
    lines = raw_csv.splitlines()
    # La primera linea empieza con '#'; la segunda es la cabecera real
    header_idx = next(
        (i for i, l in enumerate(lines)
         if l.startswith("HostName") or l.startswith("#HostName")), None
    )
    if header_idx is None:
        raise ValueError("No se encontro la cabecera del CSV.")

    # Eliminar el '#' del inicio de la cabecera si existe
    header_lines = list(lines[header_idx:])
    header_lines[0] = header_lines[0].lstrip("#")

    reader = csv.DictReader(io.StringIO("\n".join(header_lines)))
    servers = []
    for row in reader:
        # La ultima linea suele ser un marcador '*'
        if row.get("HostName", "").startswith("*"):
            continue
        servers.append(row)
    return servers


# ── Guardado de archivos .ovpn ───────────────────────────────────────────────

def save_ovpn_files(servers: list[dict]) -> dict[str, list[dict]]:
    """
    Filtra por pais, decodifica Base64 y guarda .ovpn.
    Retorna dict  pais -> [info, ...]  ordenado por velocidad.
    """
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # Contador por pais para numeracion
    counters: dict[str, int] = {cc: 0 for cc in PAISES_FILTRO}
    result:   dict[str, list] = {cc: [] for cc in PAISES_FILTRO}

    for row in servers:
        cc = row.get("CountryShort", "").strip().upper()
        if cc not in PAISES_FILTRO:
            continue

        b64 = row.get("OpenVPN_ConfigData_Base64", "").strip()
        if not b64:
            continue

        try:
            ovpn_bytes   = base64.b64decode(b64)
            ovpn_content = ovpn_bytes.decode("utf-8", errors="replace")
        except Exception:
            continue

        counters[cc] += 1
        filename = f"{cc}_{counters[cc]}.ovpn"
        filepath = os.path.join(OUTPUT_DIR, filename)

        with open(filepath, "w", encoding="utf-8") as f:
            f.write(ovpn_content)

        speed   = bytes_to_mbps(row.get("Speed", "0"))
        ping_ms = row.get("Ping", "0").strip()
        uptime  = uptime_days(row.get("Uptime", "0"))

        info = {
            "file":     filename,
            "ip":       row.get("IP", "").strip(),
            "country":  BANDERAS.get(cc, cc),
            "cc":       cc,
            "speed":    speed,
            "ping_ms":  ping_ms,
            "ping_q":   ping_quality(ping_ms),
            "users":    format_users(row.get("NumVpnSessions", "0")),
            "uptime":   uptime,
            "protocol": detect_protocol(ovpn_content),
            "status":   status(speed, uptime),
        }
        result[cc].append(info)

    # Ordenar cada pais por velocidad desc
    for cc in result:
        result[cc].sort(key=lambda x: x["speed"], reverse=True)

    return result


# ── Formateo de resumen ───────────────────────────────────────────────────────

def server_line(rank: int, s: dict) -> str:
    return (
        f"  #{rank:<3} | {s['ip']:<16} | "
        f"⚡ {s['speed']:>8.2f} Mbps | "
        f"📶 {s['ping_q']:<10} ({s['ping_ms']}ms) | "
        f"👥 {s['users']:>7} usuarios | "
        f"⏱️  {s['uptime']:>6.1f} dias | "
        f"🔒 {s['protocol']:<15} | "
        f"✅ {s['status']}"
    )


def build_summary(result: dict[str, list[dict]]) -> str:
    lines = []
    lines.append("=" * 100)
    lines.append("  RESUMEN VPN GATE — servidores filtrados y ordenados por velocidad")
    lines.append("=" * 100)

    country_order = ["BR", "JP", "CA", "US"]
    for cc in country_order:
        servers = result.get(cc, [])
        flag_name = BANDERAS.get(cc, cc)
        lines.append(f"\n{flag_name} — {len(servers)} servidor(es) encontrado(s)")
        lines.append("-" * 100)
        if not servers:
            lines.append("  (ninguno disponible en este momento)")
            continue
        for i, s in enumerate(servers, 1):
            lines.append(server_line(i, s))

    lines.append("\n" + "=" * 100)
    return "\n".join(lines)


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    raw      = download_csv()
    servers  = parse_servers(raw)
    print(f"Total de servidores en la lista: {len(servers)}\n")

    result   = save_ovpn_files(servers)

    total_saved = sum(len(v) for v in result.values())
    print(f"Archivos .ovpn guardados en: {OUTPUT_DIR}")
    print(f"Total guardados: {total_saved}\n")

    summary = build_summary(result)
    print(summary)

    with open(RESUMEN_FILE, "w", encoding="utf-8") as f:
        f.write(summary + "\n")
    print(f"\nResumen guardado en: {RESUMEN_FILE}")


if __name__ == "__main__":
    main()
