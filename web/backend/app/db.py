
import logging
import hashlib
import time
from typing import Dict, Iterator, List, Optional, Tuple, Union
from sqlalchemy import (BigInteger, Boolean, Column, Double, Index, Integer, MetaData, String, Table, bindparam,
                        create_engine, delete, func, inspect, insert, select, text, update, case, and_)
from sqlalchemy.engine import URL, Engine, make_url
from sqlalchemy.exc import DBAPIError, IntegrityError, OperationalError
from sqlalchemy.pool import StaticPool

from . import enrich
from .merge import Network, has_name, merge_observations
log = logging.getLogger("wardriver.db")
MYSQL_OPTS = {"mysql_charset": "utf8mb4", "mysql_engine": "InnoDB"}

metadata = MetaData()
networks = Table(
    "networks", metadata,
    Column("bssid", String(17), primary_key=True),
    Column("ssid", String(64), nullable=False),
    Column("ssid_hidden", Boolean, nullable=False),
    Column("channel", Integer, nullable=False),
    Column("rssi", Integer, nullable=False),
    Column("encryption", String(16), nullable=False),
    Column("times_seen", BigInteger, nullable=False),
    Column("wps_active", Boolean, nullable=False),
    Column("lat", Double, nullable=True),
    Column("lon", Double, nullable=True),
    Column("last_session_id", String(64), nullable=False),
    Column("last_seen_at_ms", BigInteger, nullable=False),
    Column("updated_at_ms", BigInteger, nullable=False),

    Column("city", String(64), nullable=True),
    Column("vendor", String(64), nullable=True),
    Column("local_admin", Boolean, nullable=True),
    Column("default_name", Boolean, nullable=True),
    Index("ix_networks_updated_at_ms", "updated_at_ms"),
    Index("ix_networks_city", "city"),
    Index("ix_networks_vendor", "vendor"),
    Index("ix_networks_pos", "lat", "lon"),
    **MYSQL_OPTS,
)
imports = Table(
    "imports", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("at_ms", BigInteger, nullable=False),
    Column("source", String(16), nullable=False),
    Column("layout", String(64), nullable=False),
    Column("rows_read", Integer, nullable=False),
    Column("rows_skipped", Integer, nullable=False),
    Column("networks", Integer, nullable=False),
    Column("added", Integer, nullable=False),
    Column("updated", Integer, nullable=False),
    Column("names_filled", Integer, nullable=False),
    Column("with_position", Integer, nullable=False),
    **MYSQL_OPTS,
)
sessions = Table(
    "sessions", metadata,
    Column("token_hash", String(64), primary_key=True),
    Column("expires_at_ms", BigInteger, nullable=False),
    Column("term_version", String(16), nullable=True),
    Column("accepted_at_ms", BigInteger, nullable=True),
    **MYSQL_OPTS,
)
_NET_COLS = [networks.c.bssid, networks.c.ssid, networks.c.ssid_hidden, networks.c.channel, networks.c.rssi,
             networks.c.encryption, networks.c.times_seen, networks.c.wps_active, networks.c.lat, networks.c.lon,
             networks.c.last_session_id, networks.c.last_seen_at_ms]
_IN_CHUNK = 500
MYSQL_DEADLOCK, MYSQL_LOCK_TIMEOUT = 1213, 1205

def _now_ms() -> int:
    return int(time.time() * 1000)
def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()
def _row_to_network(r) -> Network:
    return Network(bssid=r.bssid, ssid=r.ssid, ssid_hidden=bool(r.ssid_hidden), channel=r.channel, rssi=r.rssi,
                   encryption=r.encryption, times_seen=int(r.times_seen), wps_active=bool(r.wps_active),
                   lat=r.lat, lon=r.lon, last_session_id=r.last_session_id, last_seen_at_ms=int(r.last_seen_at_ms))


def _network_to_row(n: Network, now: int) -> dict:
    return {"bssid": n.bssid, "ssid": n.ssid, "ssid_hidden": n.ssid_hidden, "channel": n.channel, "rssi": n.rssi,
            "encryption": n.encryption, "times_seen": n.times_seen, "wps_active": n.wps_active, "lat": n.lat,
            "lon": n.lon, "last_session_id": n.last_session_id, "last_seen_at_ms": n.last_seen_at_ms,
            "updated_at_ms": now, **_derived(n)}

def _derived(n: Network) -> dict:
    return {"city": enrich.city_of(n.lat, n.lon), "vendor": enrich.vendor_of(n.bssid)[:64],
            "local_admin": enrich.is_local_admin(n.bssid), "default_name": enrich.is_default_name(n.ssid)}

def _chunks(items: List[str], n: int) -> Iterator[List[str]]:
    for i in range(0, len(items), n):
        yield items[i:i + n]

def make_engine(url: Union[str, URL]) -> Engine:
    parsed = make_url(url)
    if parsed.get_backend_name() == "sqlite":
        kwargs = {"connect_args": {"check_same_thread": False}}
        if parsed.database in (None, "", ":memory:"):
            kwargs["poolclass"] = StaticPool
        return create_engine(url, **kwargs)
    return create_engine(url, pool_pre_ping=True, pool_recycle=1800, pool_size=5, max_overflow=5)
def _retryable(exc: Exception) -> bool:

    if isinstance(exc, IntegrityError):
        return True
    if isinstance(exc, OperationalError):
        code = exc.orig.args[0] if getattr(exc.orig, "args", None) else None
        return code in (MYSQL_DEADLOCK, MYSQL_LOCK_TIMEOUT)
    return False
class Store:
    def __init__(self, engine: Engine):
        self.engine = engine
    def create_schema(self, wait_s: float = 0.0) -> None:

        deadline = time.monotonic() + wait_s
        while True:
            try:
                metadata.create_all(self.engine)
                self._ensure_columns()
                self.backfill_derived()
                return
            except OperationalError as e:
                if time.monotonic() >= deadline:
                    raise
                log.warning("banco indisponivel (%s); tentando de novo em 2 s", str(e.orig)[:120])
                time.sleep(2)
    def _ensure_columns(self) -> None:

        insp = inspect(self.engine)
        for table in (networks, sessions):
            have = {c["name"] for c in insp.get_columns(table.name)}
            for col in table.columns:
                if col.name not in have:
                    ddl = col.type.compile(self.engine.dialect)
                    with self.engine.begin() as c:
                        c.execute(text(f"ALTER TABLE {table.name} ADD COLUMN {col.name} {ddl} NULL"))
    def backfill_derived(self, batch: int = 2000) -> int:

        done = 0
        while True:
            with self.engine.begin() as c:
                rows = c.execute(select(*_NET_COLS).where(networks.c.city.is_(None)).limit(batch)).all()
                if not rows:
                    return done
                params = [{"b_bssid": r.bssid, **_derived(_row_to_network(r))} for r in rows]
                c.execute(update(networks).where(networks.c.bssid == bindparam("b_bssid")).values(
                    city=bindparam("city"), vendor=bindparam("vendor"), local_admin=bindparam("local_admin"),
                    default_name=bindparam("default_name")), params)
                done += len(rows)
    def ping(self) -> bool:
        try:
            with self.engine.connect() as c:
                c.execute(text("SELECT 1"))
            return True
        except DBAPIError:
            return False

    def all_networks(self, clauses: Optional[list] = None) -> List[Network]:
        stmt = select(*_NET_COLS).order_by(networks.c.rssi.desc(), networks.c.bssid)
        if clauses:
            stmt = stmt.where(and_(*clauses))
        with self.engine.connect() as c:
            return [_row_to_network(r) for r in c.execute(stmt)]
    def page(self, clauses: list, sort_col, desc: bool, limit: int, offset: int) -> Tuple[int, List[dict]]:

        cols = [*_NET_COLS, networks.c.city, networks.c.vendor]
        order = [sort_col.desc() if desc else sort_col.asc(), networks.c.bssid]
        with self.engine.connect() as c:
            total = c.execute(select(func.count()).select_from(networks).where(and_(*clauses))).scalar_one()
            rows = c.execute(select(*cols).where(and_(*clauses)).order_by(*order).limit(limit).offset(offset))
            return total, [dict(r._mapping) for r in rows]
    def map_points(self, clauses: list, limit: int) -> Tuple[int, List[dict]]:

        has = and_(networks.c.lat.is_not(None), networks.c.lon.is_not(None), *clauses)
        cols = [networks.c.bssid, networks.c.ssid, networks.c.ssid_hidden, networks.c.channel, networks.c.rssi,
                networks.c.encryption, networks.c.times_seen, networks.c.wps_active, networks.c.lat, networks.c.lon,
                networks.c.last_seen_at_ms, networks.c.city, networks.c.vendor]
        with self.engine.connect() as c:
            total = c.execute(select(func.count()).select_from(networks).where(has)).scalar_one()
            rows = c.execute(select(*cols).where(has).order_by(networks.c.bssid).limit(limit)) if limit else []
            return total, [dict(r._mapping) for r in rows]
    def bounds(self, clauses: list) -> Optional[List[float]]:

        where = and_(networks.c.lat.is_not(None), networks.c.lon.is_not(None), *clauses)
        with self.engine.connect() as c:
            r = c.execute(select(func.min(networks.c.lat), func.min(networks.c.lon), func.max(networks.c.lat),
                                 func.max(networks.c.lon)).where(where)).one()
        return None if r[0] is None else [float(v) for v in r]

    def map_cells(self, clauses: list, cell_deg: float) -> List[dict]:

        gx = func.floor(networks.c.lon / cell_deg)
        gy = func.floor(networks.c.lat / cell_deg)
        weak = func.sum(case((networks.c.encryption.in_(["OPEN", "WEP"]), 1), else_=0))
        where = and_(networks.c.lat.is_not(None), networks.c.lon.is_not(None), *clauses)
        stmt = (select(func.avg(networks.c.lat).label("lat"), func.avg(networks.c.lon).label("lon"),
                       func.count().label("n"), weak.label("weak")).where(where).group_by(gx, gy))
        with self.engine.connect() as c:
            return [{"lat": float(r.lat), "lon": float(r.lon), "n": int(r.n), "weak": int(r.weak or 0)}
                    for r in c.execute(stmt)]
    def facet(self, column, clauses: list, limit: int = 300) -> List[dict]:
        where = and_(column.is_not(None), column != "", *clauses)
        stmt = (select(column.label("name"), func.count().label("n")).where(where).group_by(column)
                .order_by(func.count().desc(), column).limit(limit))
        with self.engine.connect() as c:
            return [{"name": r.name, "count": int(r.n)} for r in c.execute(stmt)]
    def count(self) -> int:
        with self.engine.connect() as c:
            return c.execute(select(func.count()).select_from(networks)).scalar_one()
    def version(self) -> str:

        with self.engine.connect() as c:
            n, last = c.execute(select(func.count(), func.coalesce(func.max(networks.c.updated_at_ms), 0))
                                .select_from(networks)).one()
        return f"{n}-{last}"
    def apply_import(self, records: List[Network], source: str, layout: str, rows_read: int, rows_skipped: int) -> Dict:

        for attempt in range(3):
            try:
                return self._apply_import_once(records, source, layout, rows_read, rows_skipped)
            except (IntegrityError, OperationalError) as e:
                if attempt == 2 or not _retryable(e):
                    raise
                log.warning("importacao repetida apos conflito de banco: %s", type(e).__name__)
                time.sleep(0.1 * (attempt + 1))
        raise AssertionError("inalcancavel")
    def _apply_import_once(self, records, source, layout, rows_read, rows_skipped) -> Dict:
        added = updated = names_filled = 0
        now = _now_ms()
        with self.engine.begin() as c:
            existing: Dict[str, Network] = {}
            for chunk in _chunks([r.bssid for r in records], _IN_CHUNK):

                for row in c.execute(select(*_NET_COLS).where(networks.c.bssid.in_(chunk)).with_for_update()):
                    existing[row.bssid] = _row_to_network(row)
            new_rows, changed_rows = [], []
            for rec in records:
                old = existing.get(rec.bssid)
                merged = merge_observations(old, rec)
                if old is None:
                    added += 1
                    new_rows.append(_network_to_row(merged, now))
                elif merged != old:
                    updated += 1
                    if not has_name(old) and has_name(merged):
                        names_filled += 1
                    changed_rows.append(_network_to_row(merged, now))
            if new_rows:
                c.execute(insert(networks), new_rows)
            if changed_rows:
                params = [{**{("b_" + k if k == "bssid" else k): v for k, v in row.items()}} for row in changed_rows]
                c.execute(update(networks).where(networks.c.bssid == bindparam("b_bssid")).values(
                    ssid=bindparam("ssid"), ssid_hidden=bindparam("ssid_hidden"), channel=bindparam("channel"),
                    rssi=bindparam("rssi"), encryption=bindparam("encryption"), times_seen=bindparam("times_seen"),
                    wps_active=bindparam("wps_active"), lat=bindparam("lat"), lon=bindparam("lon"),
                    last_session_id=bindparam("last_session_id"), last_seen_at_ms=bindparam("last_seen_at_ms"),
                    updated_at_ms=bindparam("updated_at_ms"), city=bindparam("city"), vendor=bindparam("vendor"),
                    local_admin=bindparam("local_admin"), default_name=bindparam("default_name")), params)
            with_position = sum(1 for r in records if r.lat is not None and r.lon is not None)
            c.execute(insert(imports).values(at_ms=now, source=source, layout=layout[:64], rows_read=rows_read,
                                             rows_skipped=rows_skipped, networks=len(records), added=added,
                                             updated=updated, names_filled=names_filled, with_position=with_position))
            total = c.execute(select(func.count()).select_from(networks)).scalar_one()
        return {"layout": layout, "rows_read": rows_read, "rows_skipped": rows_skipped, "networks": len(records),
                "added": added, "updated": updated, "names_filled": names_filled,
                "with_position": with_position, "total": total}
    def last_import(self) -> Optional[Dict]:
        rows = self.recent_imports(1)
        return rows[0] if rows else None
    def recent_imports(self, limit: int = 10) -> List[Dict]:
        with self.engine.connect() as c:
            rows = c.execute(select(imports).order_by(imports.c.id.desc()).limit(limit))
            return [dict(r._mapping) for r in rows]

    def create_term_session(self, token: str, term_version: str, ttl_s: int) -> None:
        now = _now_ms()
        with self.engine.begin() as c:
            c.execute(delete(sessions).where(sessions.c.expires_at_ms < now))
            c.execute(insert(sessions).values(token_hash=_hash_token(token), expires_at_ms=now + ttl_s * 1000,
                                              term_version=term_version, accepted_at_ms=now))
    def term_valid(self, token: str, term_version: str) -> bool:
        with self.engine.connect() as c:
            row = c.execute(select(sessions.c.expires_at_ms, sessions.c.term_version)
                            .where(sessions.c.token_hash == _hash_token(token))).first()
        return row is not None and row.expires_at_ms > _now_ms() and row.term_version == term_version

    def delete_session(self, token: str) -> None:
        with self.engine.begin() as c:
            c.execute(delete(sessions).where(sessions.c.token_hash == _hash_token(token)))
