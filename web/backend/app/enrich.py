
import math
from functools import lru_cache
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from .analysis import DEFAULT_NAME_RE
DATA = Path(__file__).resolve().parent / "data"
MAX_KM = 20.0

@lru_cache(maxsize=1)
def _oui() -> Dict[str, str]:
    out: Dict[str, str] = {}
    for line in (DATA / "oui.tsv").read_text(encoding="utf-8").splitlines():
        key, _, name = line.partition("\t")
        if name:
            out[key] = name
    return out
@lru_cache(maxsize=1)
def _cities() -> List[Tuple[str, float, float]]:
    out = []
    for line in (DATA / "cities.tsv").read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) == 4:
            out.append((f"{parts[0]} - {parts[1]}", float(parts[2]), float(parts[3])))
    return out
def vendor_of(bssid: str) -> str:

    key = bssid.replace(":", "").upper()[:6]
    if len(key) < 6:
        return ""
    name = _oui().get(key)
    if name is None:
        cleared = "%02X" % (int(key[:2], 16) & 0xFD) + key[2:]
        name = _oui().get(cleared, "")
    return name
def is_local_admin(bssid: str) -> bool:
    try:
        return bool(int(bssid[:2], 16) & 0x02)
    except ValueError:
        return False
def is_default_name(ssid: str) -> bool:
    return bool(ssid) and bool(DEFAULT_NAME_RE.search(ssid))

def _km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p = math.pi / 180
    a = math.sin((lat2 - lat1) * p / 2) ** 2 + math.cos(lat1 * p) * math.cos(lat2 * p) * math.sin((lon2 - lon1) * p / 2) ** 2
    return 12742 * math.asin(math.sqrt(a))
def city_of(lat: Optional[float], lon: Optional[float]) -> str:
    if lat is None or lon is None:
        return ""
    best, best_km = "", MAX_KM
    for name, clat, clon in _cities():
        if abs(clat - lat) > 0.25 or abs(clon - lon) > 0.3:
            continue
        d = _km(lat, lon, clat, clon)
        if d < best_km:
            best, best_km = name, d
    return best
