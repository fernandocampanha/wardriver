
from dataclasses import dataclass, replace
from typing import Optional
UNKNOWN_ENCRYPTION = "UNKNOWN"
DEFAULT_RSSI = -100
@dataclass
class Network:
    bssid: str
    ssid: str = ""
    ssid_hidden: bool = True
    channel: int = 0
    rssi: int = DEFAULT_RSSI
    encryption: str = UNKNOWN_ENCRYPTION
    times_seen: int = 1
    wps_active: bool = False
    lat: Optional[float] = None
    lon: Optional[float] = None
    last_session_id: str = ""
    last_seen_at_ms: int = 0
def has_name(n: Network) -> bool:
    return bool(n.ssid) and not n.ssid_hidden
def has_location(n: Network) -> bool:
    return n.lat is not None and n.lon is not None

def is_unnamed(n: Network) -> bool:
    return n.ssid_hidden or not n.ssid

def merge_observations(existing: Optional[Network], incoming: Network) -> Network:
    if existing is None:
        return replace(incoming)

    if has_name(existing) and has_name(incoming):
        name = incoming.ssid if incoming.last_seen_at_ms > existing.last_seen_at_ms else existing.ssid
    elif has_name(existing):
        name = existing.ssid
    elif has_name(incoming):
        name = incoming.ssid
    else:
        name = ""

    if not has_location(incoming):
        use_incoming_fix = False
    elif not has_location(existing):
        use_incoming_fix = True
    else:
        use_incoming_fix = incoming.rssi > existing.rssi
    lat = incoming.lat if use_incoming_fix else existing.lat
    lon = incoming.lon if use_incoming_fix else existing.lon

    if has_location(existing) or has_location(incoming):
        rssi = incoming.rssi if use_incoming_fix else existing.rssi
    else:
        rssi = max(existing.rssi, incoming.rssi)
    newer = incoming if incoming.last_seen_at_ms > existing.last_seen_at_ms else existing
    return Network(
        bssid=existing.bssid,
        ssid=name,
        ssid_hidden=not name,
        channel=existing.channel if existing.channel > 0 else incoming.channel,
        rssi=rssi,
        encryption=existing.encryption if existing.encryption != UNKNOWN_ENCRYPTION else incoming.encryption,
        times_seen=max(existing.times_seen, incoming.times_seen),
        wps_active=existing.wps_active or incoming.wps_active,
        lat=lat,
        lon=lon,
        last_session_id=newer.last_session_id,
        last_seen_at_ms=newer.last_seen_at_ms,
    )
