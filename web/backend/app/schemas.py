import re
from typing import List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .merge import Network

BSSID_RE = re.compile(r"^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
NUL = chr(0)
MAX_BATCH = 5000

class IngestNetwork(BaseModel):
    model_config = ConfigDict(extra="ignore")

    bssid: str
    ssid: str = Field(default="", max_length=64)
    hidden: bool = False
    channel: int = Field(default=0, ge=0, le=196)
    rssi: int = Field(default=-100, ge=-127, le=0)
    enc: Literal["OPEN", "WEP", "WPA", "WPA2", "WPA3", "UNKNOWN"] = "UNKNOWN"
    seen: int = Field(default=1, ge=1, le=2_000_000_000)
    wps: bool = False
    lat: Optional[float] = Field(default=None, ge=-90, le=90)
    lon: Optional[float] = Field(default=None, ge=-180, le=180)
    session: str = Field(default="", max_length=64)
    last: int = Field(default=0, ge=0, le=32_503_680_000_000)

    @field_validator("bssid")
    @classmethod
    def _bssid(cls, v: str) -> str:
        if not BSSID_RE.match(v):
            raise ValueError("BSSID invalido")
        return v.upper()

    @field_validator("ssid", "session")
    @classmethod
    def _text(cls, v: str) -> str:
        v = v.replace(NUL, "")
        try:
            v.encode("utf-8")
        except UnicodeEncodeError:
            raise ValueError("texto nao e UTF-8 valido")
        return v

    @model_validator(mode="after")
    def _position(self):

        if (self.lat is None) != (self.lon is None) or (self.lat == 0.0 and self.lon == 0.0):
            self.lat = self.lon = None
        return self

    def to_network(self) -> Network:
        name = "" if self.hidden else self.ssid
        return Network(bssid=self.bssid, ssid=name, ssid_hidden=not name, channel=self.channel, rssi=self.rssi,
                       encryption=self.enc, times_seen=self.seen, wps_active=self.wps, lat=self.lat, lon=self.lon,
                       last_session_id=self.session or "app", last_seen_at_ms=self.last)

class IngestRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    device: str = Field(default="", max_length=64)
    networks: List[IngestNetwork] = Field(min_length=1, max_length=MAX_BATCH)
