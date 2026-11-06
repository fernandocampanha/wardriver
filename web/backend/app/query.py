

from dataclasses import dataclass, field
from typing import List, Optional, Tuple

from fastapi import HTTPException, Query
from sqlalchemy import and_, false, func, or_

from .db import networks

ENCRYPTIONS = {"OPEN", "WEP", "WPA", "WPA2", "WPA3", "UNKNOWN"}
SORTS = {"rssi": networks.c.rssi, "ssid": networks.c.ssid, "last": networks.c.last_seen_at_ms,
         "seen": networks.c.times_seen, "channel": networks.c.channel, "bssid": networks.c.bssid,
         "city": networks.c.city, "vendor": networks.c.vendor}
MAX_TOKENS = 8
MAX_LIST = 50


def _like_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


@dataclass
class Filters:
    q: str = ""
    enc: List[str] = field(default_factory=list)
    channel: List[int] = field(default_factory=list)
    rssi_min: Optional[int] = None
    rssi_max: Optional[int] = None
    wps: Optional[bool] = None
    hidden: str = ""
    default_name: bool = False
    local_admin: bool = False
    has_pos: bool = False
    seen_min: Optional[int] = None
    since_ms: Optional[int] = None
    until_ms: Optional[int] = None
    city: List[str] = field(default_factory=list)
    vendor: List[str] = field(default_factory=list)
    bbox: Optional[Tuple[float, float, float, float]] = None

    def clauses(self) -> list:
        c = networks.c
        out = []
        tokens = self.q.lower().split()[:MAX_TOKENS]
        for t in tokens:
            like = f"%{_like_escape(t)}%"
            parts = [func.lower(c.ssid).like(like, escape="\\"),
                     func.lower(c.bssid).like(like, escape="\\"),
                     func.replace(func.lower(c.bssid), ":", "").like(like, escape="\\")]
            if t in ("oculta", "oculto", "hidden"):
                parts.append(c.ssid_hidden.is_(True))
            out.append(or_(*parts))
        if self.enc:
            out.append(c.encryption.in_(self.enc))
        if self.channel:
            out.append(c.channel.in_(self.channel))
        if self.rssi_min is not None:
            out.append(c.rssi >= self.rssi_min)
        if self.rssi_max is not None:
            out.append(c.rssi <= self.rssi_max)
        if self.wps is not None:
            out.append(c.wps_active.is_(self.wps))
        if self.hidden == "only":
            out.append(or_(c.ssid_hidden.is_(True), c.ssid == ""))
        elif self.hidden == "no":
            out.append(and_(c.ssid_hidden.is_(False), c.ssid != ""))
        if self.default_name:
            out.append(c.default_name.is_(True))
        if self.local_admin:
            out.append(and_(c.local_admin.is_(True), or_(c.ssid_hidden.is_(True), c.ssid == "")))
        if self.has_pos:
            out.append(and_(c.lat.is_not(None), c.lon.is_not(None)))
        if self.seen_min is not None:
            out.append(c.times_seen >= self.seen_min)
        if self.since_ms is not None:
            out.append(c.last_seen_at_ms >= self.since_ms)
        if self.until_ms is not None:
            out.append(c.last_seen_at_ms <= self.until_ms)
        if self.city:
            out.append(c.city.in_(self.city))
        if self.vendor:
            out.append(c.vendor.in_(self.vendor))
        if self.bbox:
            s, w, n, e = self.bbox
            out.append(and_(c.lat >= s, c.lat <= n, c.lon >= w, c.lon <= e))
        return out


def _ints(raw: str, lo: int, hi: int, name: str) -> List[int]:
    try:
        vals = [int(x) for x in raw.split(",") if x.strip()]
    except ValueError:
        raise HTTPException(400, f"{name} invalido")
    if len(vals) > MAX_LIST or any(not lo <= v <= hi for v in vals):
        raise HTTPException(400, f"{name} fora dos limites")
    return vals


def _strs(raw: str, name: str) -> List[str]:
    vals = [x.strip() for x in raw.split("|") if x.strip()]
    if len(vals) > MAX_LIST or any(len(v) > 80 for v in vals):
        raise HTTPException(400, f"{name} fora dos limites")
    return vals


def filters_from_query(
    q: str = Query("", max_length=100), enc: str = Query("", max_length=60), channel: str = Query("", max_length=120),
    rssi_min: Optional[int] = Query(None, ge=-127, le=0), rssi_max: Optional[int] = Query(None, ge=-127, le=0),
    wps: Optional[bool] = None, hidden: str = Query("", pattern="^(only|no)?$"), default_name: bool = False,
    local_admin: bool = False, has_pos: bool = False, seen_min: Optional[int] = Query(None, ge=1, le=2_000_000_000),
    since: Optional[int] = Query(None, ge=0, le=32_503_680_000_000), until: Optional[int] = Query(None, ge=0, le=32_503_680_000_000),
    city: str = Query("", max_length=2000), vendor: str = Query("", max_length=2000),
    bbox: str = Query("", max_length=100),
) -> Filters:
    encs = [e for e in enc.split(",") if e]
    if any(e not in ENCRYPTIONS for e in encs):
        raise HTTPException(400, "criptografia invalida")
    box = None
    if bbox:
        try:
            s, w, n, e = (float(x) for x in bbox.split(","))
        except ValueError:
            raise HTTPException(400, "bbox invalido")
        if not (-90 <= s <= n <= 90 and -180 <= w <= 180 and -180 <= e <= 180):
            raise HTTPException(400, "bbox fora dos limites")
        box = (s, w, n, e)
    return Filters(q=q, enc=encs, channel=_ints(channel, 0, 196, "canal"), rssi_min=rssi_min, rssi_max=rssi_max, wps=wps,
                   hidden=hidden, default_name=default_name, local_admin=local_admin, has_pos=has_pos,
                   seen_min=seen_min, since_ms=since, until_ms=until, city=_strs(city, "cidade"),
                   vendor=_strs(vendor, "fabricante"), bbox=box)
