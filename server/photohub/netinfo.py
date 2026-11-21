"""The NAS's own addresses, so nobody has to type them.

With `network_mode: host` (docker-compose.yml) the container sees the NAS's interfaces:
  LAN       first private IPv4 on a normal interface (eth0, ovs_eth0, bond0, ...)
  Tailscale the 100.64.0.0/10 address on tailscale0 (Synology's Tailscale package)
PHOTOHUB_LAN_URL / PHOTOHUB_REMOTE_URL override the detected values.
"""
from __future__ import annotations

import fcntl
import ipaddress
import socket
import struct

from .config import Settings

SIOCGIFADDR = 0x8915
_SKIP = ("lo", "docker", "br-", "veth", "virbr", "cni", "flannel")
_CGNAT = ipaddress.ip_network("100.64.0.0/10")


def interfaces() -> dict[str, str]:
    out = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        for _, name in socket.if_nameindex():
            try:
                res = fcntl.ioctl(s.fileno(), SIOCGIFADDR, struct.pack("256s", name[:15].encode()))
                out[name] = socket.inet_ntoa(res[20:24])
            except OSError:
                continue
    finally:
        s.close()
    return out


def lan_ip(ifs: dict[str, str] | None = None) -> str | None:
    ifs = interfaces() if ifs is None else ifs
    for name, ip in sorted(ifs.items()):
        if name.startswith(_SKIP) or name.startswith("tailscale"):
            continue
        a = ipaddress.ip_address(ip)
        if a.is_private and a not in _CGNAT:
            return ip
    return None


def tailscale_ip(ifs: dict[str, str] | None = None) -> str | None:
    ifs = interfaces() if ifs is None else ifs
    for name, ip in sorted(ifs.items()):
        if name.startswith("tailscale") or ipaddress.ip_address(ip) in _CGNAT:
            return ip
    return None


def lan_url(settings: Settings) -> str:
    if settings.lan_url:
        return settings.lan_url
    ip = lan_ip()
    return f"http://{ip}:{settings.api_port}" if ip else ""


def remote_url(settings: Settings) -> str:
    if settings.remote_url:
        return settings.remote_url
    ip = tailscale_ip()
    return f"http://{ip}:{settings.api_port}" if ip else ""


def dsm_url(settings: Settings) -> str:
    """DSM itself, for checking passwords. Host networking: the NAS is localhost."""
    return settings.dsm_url or "https://127.0.0.1:5001"


class Advertiser:
    """Announces _photohub._tcp on the home network (mDNS) so the app finds the NAS by itself."""

    def __init__(self, settings: Settings):
        self.s = settings
        self.zc = None

    def start(self) -> bool:
        ip = lan_ip()
        if not ip:
            return False
        try:
            from zeroconf import ServiceInfo, Zeroconf
        except ImportError:
            return False
        info = ServiceInfo("_photohub._tcp.local.", f"PhotoHub {socket.gethostname()}._photohub._tcp.local.",
                           addresses=[socket.inet_aton(ip)], port=self.s.api_port,
                           properties={"v": "1", "path": "/"}, server=f"{socket.gethostname()}.local.")
        self.zc = Zeroconf()
        self.zc.register_service(info)
        return True

    def stop(self) -> None:
        if self.zc is not None:
            self.zc.unregister_all_services()
            self.zc.close()
