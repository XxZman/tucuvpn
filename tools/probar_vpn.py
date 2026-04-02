#!/usr/bin/env python3
"""
probar_vpn.py
Lee los archivos .ovpn de ovpn_descargados/, prueba conectividad TCP por el
puerto indicado en cada config, copia los funcionales a ovpn_funcionales/ y
genera un resumen ordenado por velocidad + ping.
"""

import socket
import os
import shutil
import sys
import time
import re

# ── UTF-8 en consola Windows ──────────────────────────────────────────────────
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ── Rutas ─────────────────────────────────────────────────────────────────────
BASE_DIR        = os.path.dirname(os.path.abspath(__file__))
OVPN_DIR        = os.path.join(BASE_DIR, "ovpn_descargados")
FUNCIONALES_DIR = os.path.join(BASE_DIR, "ovpn_funcionales")
RESUMEN_TXT     = os.path.join(BASE_DIR, "resumen_vpn.txt")
OUTPUT_TXT      = os.path.join(BASE_DIR, "funcionales.txt")

TCP_TIMEOUT = 3   # segundos

BANDERAS = {
    "BR": ("Brasil",  "🇧🇷"),
    "JP": ("Japón",   "🇯🇵"),
    "CA": ("Canadá",  "🇨🇦"),
    "US": ("EEUU",    "🇺🇸"),
}

ORDEN_PAISES = ["JP", "CA", "US", "BR"]

# ── Parseo del .ovpn ──────────────────────────────────────────────────────────

def parse_remote(ovpn_path: str) -> tuple[str, int]:
    """
    Extrae (ip, puerto) de la linea  'remote <host> <port>'  del config.
    Devuelve ('', 443) si no se encuentra.
    """
    ip, port = "", 443
    try:
        with open(ovpn_path, encoding="utf-8", errors="replace") as f:
            for line in f:
                stripped = line.strip()
                if stripped.startswith("remote "):
                    parts = stripped.split()
                    if len(parts) >= 2:
                        ip = parts[1]
                    if len(parts) >= 3:
                        try:
                            port = int(parts[2])
                        except ValueError:
                            pass
                    break
    except OSError:
        pass
    return ip, port


# ── Parseo de resumen_vpn.txt para recuperar speed / ping ─────────────────────

def load_metadata_from_resumen() -> dict[str, dict]:
    """
    Lee resumen_vpn.txt y construye un dict  ip -> {speed, ping_ms}
    basado en las lineas de la forma:
      #N   | <ip>  | ⚡ <speed> Mbps | 📶 <calidad> (<ping>ms) | ...
    """
    meta: dict[str, dict] = {}
    if not os.path.isfile(RESUMEN_TXT):
        return meta

    # Patron: cualquier cosa | ip | ... Mbps | ... (Nms) | ...
    pat = re.compile(
        r"\|\s*([\d\.]+)\s*\|"          # IP
        r".*?(\d+(?:\.\d+)?)\s*Mbps"    # velocidad
        r".*?\((\d+)ms\)"               # ping
    )
    try:
        with open(RESUMEN_TXT, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = pat.search(line)
                if m:
                    ip      = m.group(1)
                    speed   = float(m.group(2))
                    ping_ms = int(m.group(3))
                    meta[ip] = {"speed": speed, "ping_ms": ping_ms}
    except OSError:
        pass
    return meta


# ── Test TCP ──────────────────────────────────────────────────────────────────

def tcp_ping(ip: str, port: int, timeout: float = TCP_TIMEOUT) -> tuple[bool, float]:
    """
    Intenta abrir una conexion TCP a (ip, port).
    Devuelve (True, rtt_ms) si tiene exito, (False, 0.0) si falla.
    """
    if not ip:
        return False, 0.0
    try:
        t0 = time.perf_counter()
        with socket.create_connection((ip, port), timeout=timeout):
            rtt = (time.perf_counter() - t0) * 1000
        return True, round(rtt, 1)
    except OSError:
        return False, 0.0


# ── Listado y clasificacion de archivos .ovpn ─────────────────────────────────

def collect_ovpn_files() -> dict[str, list[str]]:
    """
    Devuelve {cc: [filename, ...]} con los archivos encontrados en OVPN_DIR,
    ordenados numericamente.
    """
    grouped: dict[str, list[str]] = {cc: [] for cc in BANDERAS}

    if not os.path.isdir(OVPN_DIR):
        print(f"ERROR: No se encontro la carpeta {OVPN_DIR}")
        sys.exit(1)

    for fname in sorted(os.listdir(OVPN_DIR)):
        if not fname.endswith(".ovpn"):
            continue
        cc = fname.split("_")[0].upper()
        if cc in grouped:
            grouped[cc].append(fname)

    # Ordenar numericamente (BR_1, BR_2, ... BR_10, ...)
    def numeric_key(name: str) -> int:
        m = re.search(r"_(\d+)\.ovpn$", name)
        return int(m.group(1)) if m else 0

    for cc in grouped:
        grouped[cc].sort(key=numeric_key)

    return grouped


# ── Pruebas y copia ───────────────────────────────────────────────────────────

def test_and_copy(grouped: dict[str, list[str]],
                  meta: dict[str, dict]) -> dict[str, list[dict]]:
    """
    Prueba cada servidor y copia los funcionales a FUNCIONALES_DIR.
    Devuelve {cc: [{info...}, ...]} solo con los que funcionan.
    """
    os.makedirs(FUNCIONALES_DIR, exist_ok=True)

    # Limpiar archivos anteriores en destino
    for fname in os.listdir(FUNCIONALES_DIR):
        if fname.endswith(".ovpn"):
            os.remove(os.path.join(FUNCIONALES_DIR, fname))

    total = sum(len(v) for v in grouped.values())
    tested = 0
    results: dict[str, list[dict]] = {cc: [] for cc in BANDERAS}
    counters: dict[str, int] = {cc: 0 for cc in BANDERAS}

    print(f"Probando {total} servidor(es) — timeout {TCP_TIMEOUT}s por servidor\n")
    print("-" * 70)

    for cc in ORDEN_PAISES:
        files = grouped.get(cc, [])
        if not files:
            continue
        nombre, bandera = BANDERAS[cc]
        print(f"\n{bandera}  {nombre} ({len(files)} servidor(es))")

        for fname in files:
            tested += 1
            src_path = os.path.join(OVPN_DIR, fname)
            ip, port = parse_remote(src_path)

            # Indicador de progreso en tiempo real (sin newline)
            label = f"  🔍 Probando {fname:<16} ({ip:<16}:{port}) ..."
            print(label, end=" ", flush=True)

            ok, rtt = tcp_ping(ip, port)

            if ok:
                counters[cc] += 1
                new_name = f"{cc}_{counters[cc]}.ovpn"
                dst_path = os.path.join(FUNCIONALES_DIR, new_name)
                shutil.copy2(src_path, dst_path)

                # Metadatos de velocidad / ping del resumen previo
                m = meta.get(ip, {})
                speed   = m.get("speed",   0.0)
                ping_ms = m.get("ping_ms", int(rtt))  # fallback: RTT medido

                results[cc].append({
                    "new_name": new_name,
                    "ip":       ip,
                    "port":     port,
                    "speed":    speed,
                    "ping_ms":  ping_ms,
                    "rtt":      rtt,
                })
                print(f"✅ FUNCIONA  ({rtt:.0f}ms RTT)")
            else:
                print("❌ NO RESPONDE")

    print("\n" + "-" * 70)
    return results


# ── Resumen final ─────────────────────────────────────────────────────────────

def build_summary(results: dict[str, list[dict]]) -> str:
    lines: list[str] = []

    lines.append("\n" + "=" * 70)
    lines.append("  SERVIDORES FUNCIONALES")
    lines.append("=" * 70)
    lines.append("\n✅ Servidores funcionales:")

    for cc in ORDEN_PAISES:
        nombre, bandera = BANDERAS[cc]
        count = len(results.get(cc, []))
        lines.append(f"  {bandera}  {nombre}: {count} funcionando")

    lines.append(f"\n  Archivos guardados en: {FUNCIONALES_DIR}")

    lines.append("\n  Los mejores por pais (mayor velocidad + ping bajo):")

    for cc in ORDEN_PAISES:
        servers = results.get(cc, [])
        nombre, bandera = BANDERAS[cc]

        if not servers:
            lines.append(f"  🥇 {nombre}: (ninguno disponible)")
            continue

        # Ordenar: primero por velocidad desc, luego por ping asc
        best = sorted(servers,
                      key=lambda s: (-s["speed"], s["ping_ms"]))[0]

        speed_str = (f"{best['speed']:,.0f} Mbps"
                     if best["speed"] > 0 else f"RTT {best['rtt']:.0f}ms")
        lines.append(
            f"  🥇 {nombre}: {best['new_name']:<12} — {speed_str}, "
            f"{best['ping_ms']}ms ping"
        )

    lines.append("")
    lines.append("=" * 70)

    # Tabla detallada por pais
    for cc in ORDEN_PAISES:
        servers = results.get(cc, [])
        nombre, bandera = BANDERAS[cc]
        lines.append(f"\n{bandera}  {nombre} — {len(servers)} funcionales")
        lines.append("-" * 70)

        if not servers:
            lines.append("  (ninguno)")
            continue

        ranked = sorted(servers, key=lambda s: (-s["speed"], s["ping_ms"]))
        for i, s in enumerate(ranked, 1):
            speed_str = (f"{s['speed']:>10,.2f} Mbps"
                         if s["speed"] > 0 else f"{'N/D':>14}")
            lines.append(
                f"  #{i:<3} {s['new_name']:<12} | {s['ip']:<16} :{s['port']:<5} | "
                f"⚡{speed_str} | 📶 {s['ping_ms']:>4}ms | RTT {s['rtt']:.0f}ms"
            )

    lines.append("\n" + "=" * 70)
    return "\n".join(lines)


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    print("=" * 70)
    print("  PROBADOR DE SERVIDORES VPN GATE")
    print("=" * 70)

    grouped = collect_ovpn_files()
    meta    = load_metadata_from_resumen()

    total = sum(len(v) for v in grouped.values())
    if total == 0:
        print(f"No se encontraron archivos .ovpn en {OVPN_DIR}")
        print("Ejecuta primero descargar_vpn.py")
        sys.exit(0)

    results = test_and_copy(grouped, meta)

    summary = build_summary(results)
    print(summary)

    with open(OUTPUT_TXT, "w", encoding="utf-8") as f:
        f.write(summary + "\n")
    print(f"\nResumen guardado en: {OUTPUT_TXT}\n")


if __name__ == "__main__":
    main()
