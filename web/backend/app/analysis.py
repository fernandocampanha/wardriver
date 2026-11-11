import re
from collections import Counter
from typing import Dict, Iterable, List, Optional
from .merge import Network, has_location, is_unnamed

DEFAULT_NAME_RE = re.compile(
    r"^(TP-LINK|VIVO|CLARO|OI-|OI_|SKY|NET_|NET-|TENDA|D-LINK|DLINK|HUAWEI|MULTILASER|"
    r"INTELBRAS|ARRIS|WIFI[0-9]|GVT-|ZTE)[ _-]?[0-9A-F]*$",
    re.IGNORECASE,
)
ENCRYPTION_ORDER = ["OPEN", "WEP", "WPA", "WPA2", "WPA3", "UNKNOWN"]
MAX_CHANNEL = 14
TOP_SSIDS = 10
def _octets(bssid: str) -> Optional[List[int]]:
    parts = bssid.split(":")
    if len(parts) != 6:
        return None
    try:
        return [int(p, 16) for p in parts]
    except ValueError:
        return None
def _sibling_keys(o: List[int]) -> List[str]:

    oui = "%02X%02X%02X" % (o[0] & 0xFD, o[1], o[2])
    a, b, c = "%02X" % o[3], "%02X" % o[4], "%02X" % o[5]
    return [f"{oui}|{a}|{b}|*", f"{oui}|{a}|*|{c}", f"{oui}|*|{b}|{c}"]
def hidden_origin(networks: Iterable[Network]) -> Dict[str, int]:

    nets = list(networks)
    named_keys = set()
    for n in nets:
        if is_unnamed(n):
            continue
        o = _octets(n.bssid)
        if o:
            named_keys.update(_sibling_keys(o))
    unnamed = with_sibling = local_admin = 0
    for n in nets:
        if not is_unnamed(n):
            continue
        unnamed += 1
        o = _octets(n.bssid)
        if not o:
            continue
        if o[0] & 0x02:
            local_admin += 1
        if any(k in named_keys for k in _sibling_keys(o)):
            with_sibling += 1
    return {"unnamed": unnamed, "with_visible_sibling": with_sibling, "locally_administered": local_admin}
SIGNAL_BANDS = [("Forte (acima de -50 dBm)", -50, 1), ("Bom (-50 a -60)", -60, -50), ("Médio (-60 a -70)", -70, -60),
                ("Fraco (-70 a -80)", -80, -70), ("Muito fraco (abaixo de -80)", -128, -80)]

def _signal_bands(networks: List[Network]) -> List[dict]:
    return [{"name": name, "count": sum(1 for n in networks if lo <= n.rssi < hi)} for name, lo, hi in SIGNAL_BANDS]

def compute_stats(networks: List[Network]) -> dict:
    total = len(networks)
    by_enc = Counter(n.encryption for n in networks)
    channels = Counter(n.channel for n in networks)
    ssids = Counter(n.ssid for n in networks if n.ssid)
    seen_times = [n.last_seen_at_ms for n in networks if n.last_seen_at_ms > 0]
    encryption = [{"name": name, "count": by_enc[name]} for name in ENCRYPTION_ORDER if by_enc.get(name)]
    encryption += [{"name": k, "count": v} for k, v in by_enc.items() if k not in ENCRYPTION_ORDER]

    return {
        "total": total,
        "with_position": sum(1 for n in networks if has_location(n)),
        "hidden": sum(1 for n in networks if is_unnamed(n)),
        "wps": sum(1 for n in networks if n.wps_active),
        "weak": by_enc.get("OPEN", 0) + by_enc.get("WEP", 0),
        "default_names": sum(1 for n in networks if n.ssid and DEFAULT_NAME_RE.search(n.ssid)),
        "hidden_origin": hidden_origin(networks),
        "encryption": encryption,

        "channels": [{"channel": c, "count": channels.get(c, 0)} for c in range(1, MAX_CHANNEL + 1)],
        "channels_other": sum(v for k, v in channels.items() if not 1 <= k <= MAX_CHANNEL),
        "top_ssids": [{"ssid": s, "count": c} for s, c in sorted(ssids.items(), key=lambda kv: (-kv[1], kv[0]))[:TOP_SSIDS]],
        "signal": _signal_bands(networks),
        "first_seen_ms": min(seen_times) if seen_times else None,
        "last_seen_ms": max(seen_times) if seen_times else None,
    }
