

import logging
import time
from pathlib import Path
from typing import Optional
from urllib.parse import urlsplit

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ValidationError
from sqlalchemy.exc import DataError
from starlette.middleware.gzip import GZipMiddleware
from . import auth
from .analysis import compute_stats
from .config import Settings, load_settings
from .db import Store, make_engine, networks
from .query import SORTS, Filters, filters_from_query
from .merge import merge_observations
from .middleware import SecurityHeadersMiddleware
from .schemas import IngestRequest
log = logging.getLogger("wardriver")
COOKIE = "wd_term"
CSRF_HEADER = "x-requested-with"
CSRF_VALUE = "wardriver-web"
TERM_VERSION = "2026-10-10"
BODY_SMALL = 2048
MAP_POINT_LIMIT = 8000
PAGE_MAX = 200
def network_to_json(n) -> dict:
    return {"bssid": n.bssid, "ssid": n.ssid, "hidden": n.ssid_hidden or not n.ssid, "channel": n.channel,
            "rssi": n.rssi, "enc": n.encryption, "seen": n.times_seen, "wps": n.wps_active,
            "lat": n.lat, "lon": n.lon, "last": n.last_seen_at_ms}


def row_to_json(r: dict) -> dict:
    return {"bssid": r["bssid"], "ssid": r["ssid"], "hidden": bool(r["ssid_hidden"]) or not r["ssid"],
            "channel": r["channel"], "rssi": r["rssi"], "enc": r["encryption"], "seen": int(r["times_seen"]),
            "wps": bool(r["wps_active"]), "lat": r["lat"], "lon": r["lon"], "last": int(r["last_seen_at_ms"]),
            "city": r.get("city") or "", "vendor": r.get("vendor") or ""}

async def read_limited_body(request: Request, limit: int) -> bytes:

    declared = request.headers.get("content-length")
    if declared is not None and declared.isdigit() and int(declared) > limit:
        raise HTTPException(413, f"corpo maior que o limite de {limit} bytes")
    chunks, total = [], 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > limit:
            raise HTTPException(413, f"corpo maior que o limite de {limit} bytes")
        chunks.append(chunk)
    return b"".join(chunks)
class AcceptBody(BaseModel):
    version: str
    password: str

class Ctx:

    def __init__(self, settings: Settings, store: Store):
        self.settings, self.store, self.limiter = settings, store, auth.RateLimiter()

def ctx_of(request: Request) -> Ctx:
    return request.app.state.ctx
def client_key(request: Request) -> str:
    ctx = ctx_of(request)
    if ctx.settings.trust_proxy:
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            return forwarded.split(",")[-1].strip()
    return request.client.host if request.client else "?"
def term_token(request: Request) -> Optional[str]:
    return request.cookies.get(COOKIE)
def has_accepted(request: Request) -> bool:
    token = term_token(request)
    return bool(token) and ctx_of(request).store.term_valid(token, TERM_VERSION)

def require_term(request: Request) -> None:

    if not has_accepted(request):
        raise HTTPException(403, "nao autenticado")


def rate_limit(request: Request) -> None:
    ctx = ctx_of(request)
    key = client_key(request)
    wait = ctx.limiter.retry_after(f"{key}|all", ctx.settings.rate_per_minute)
    if not wait and request.url.path == "/api/map":
        wait = ctx.limiter.retry_after(f"{key}|map", ctx.settings.rate_burst_map)
    if wait:
        raise HTTPException(429, "muitas requisicoes; aguarde", headers={"Retry-After": str(wait)})
def check_csrf(request: Request) -> None:

    if request.headers.get(CSRF_HEADER) != CSRF_VALUE:
        raise HTTPException(403, f"cabecalho {CSRF_HEADER} ausente")
    origin = request.headers.get("origin")
    if origin and urlsplit(origin).netloc != request.headers.get("host", ""):
        raise HTTPException(403, "origem nao permitida")

def bearer_ok(request: Request) -> bool:
    header = request.headers.get("authorization", "")
    if not header.lower().startswith("bearer "):
        return False
    return auth.verify_token(header[7:].strip(), ctx_of(request).settings.upload_token_sha256)

def require_bearer(request: Request) -> None:
    if not bearer_ok(request):
        raise HTTPException(401, "token de upload ausente ou invalido")

def create_app(settings: Optional[Settings] = None, store: Optional[Store] = None) -> FastAPI:
    settings = settings or load_settings()
    if store is None:
        store = Store(make_engine(settings.database_url))
        store.create_schema(wait_s=settings.db_wait_s)
    else:
        store.create_schema()
    app = FastAPI(title="Wardriver Web", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.ctx = Ctx(settings, store)
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    app.add_middleware(SecurityHeadersMiddleware, trust_proxy=settings.trust_proxy)
    def json_error(status: int, message: str, headers: Optional[dict] = None) -> JSONResponse:
        return JSONResponse({"error": message}, status_code=status, headers=headers)
    @app.exception_handler(HTTPException)
    async def http_exc(request: Request, exc: HTTPException):
        headers = dict(exc.headers or {})
        if exc.status_code == 413:
            headers["Connection"] = "close"
        return json_error(exc.status_code, str(exc.detail), headers)
    @app.exception_handler(Exception)
    async def unhandled(request: Request, exc: Exception):
        log.exception("erro interno em %s %s", request.method, request.url.path)
        return json_error(500, "erro interno")

    @app.get("/api/health")
    def health(request: Request):
        if not ctx_of(request).store.ping():
            return json_error(503, "banco indisponivel")
        return {"ok": True}

    @app.get("/api/me")
    def me(request: Request):
        return {"accepted": has_accepted(request), "term_version": TERM_VERSION}
    @app.post("/api/term/accept")
    async def accept_term(request: Request):
        ctx = ctx_of(request)

        wait = ctx.limiter.retry_after(f"{client_key(request)}|accept", 8)
        if wait:
            raise HTTPException(429, "muitas requisicoes; aguarde", headers={"Retry-After": str(wait)})
        body = await read_limited_body(request, BODY_SMALL)
        check_csrf(request)
        try:
            form = AcceptBody.model_validate_json(body or b"{}")
        except ValidationError:
            raise HTTPException(400, "JSON invalido")
        if not auth.verify_password(form.password, ctx.settings.panel_password_hash):
            raise HTTPException(401, "senha incorreta")
        if form.version != TERM_VERSION:
            raise HTTPException(409, "o termo foi atualizado; recarregue a pagina")
        token = auth.new_token()
        ctx.store.create_term_session(token, TERM_VERSION, ctx.settings.session_ttl_s)
        response = JSONResponse({"accepted": True, "term_version": TERM_VERSION})

        response.set_cookie(COOKIE, token, httponly=True, samesite="strict", secure=ctx.settings.secure_cookie, path="/")
        return response
    @app.post("/api/term/revoke")
    def revoke_term(request: Request):
        check_csrf(request)
        token = term_token(request)
        if token:
            ctx_of(request).store.delete_session(token)
        response = JSONResponse({"accepted": False})
        response.delete_cookie(COOKIE, path="/", httponly=True, samesite="strict", secure=ctx_of(request).settings.secure_cookie)
        return response

    gate = [Depends(rate_limit), Depends(require_term)]
    NOSTORE = {"Cache-Control": "no-store"}
    @app.get("/api/networks", dependencies=gate)
    def list_networks(request: Request, f: Filters = Depends(filters_from_query), sort: str = "rssi", order: str = "desc",
                      limit: int = Query(50, ge=1, le=PAGE_MAX), offset: int = Query(0, ge=0, le=10_000_000)):
        if sort not in SORTS or order not in ("asc", "desc"):
            raise HTTPException(400, "ordenacao invalida")
        total, rows = ctx_of(request).store.page(f.clauses(), SORTS[sort], order == "desc", limit, offset)
        return JSONResponse({"total": total, "limit": limit, "offset": offset, "networks": [row_to_json(r) for r in rows]},
                            headers=NOSTORE)
    @app.get("/api/map", dependencies=gate)
    def map_view(request: Request, f: Filters = Depends(filters_from_query), zoom: int = Query(10, ge=0, le=20),
                 mode: str = Query("auto", pattern="^(auto|cells|points)$")):

        store = ctx_of(request).store
        clauses = f.clauses()
        total, points = store.map_points(clauses, MAP_POINT_LIMIT if mode != "cells" else 0)

        if mode == "points" or (mode == "auto" and (total <= MAP_POINT_LIMIT or zoom >= 16)):
            return JSONResponse({"mode": "points", "total": total, "truncated": total > len(points),
                                 "bounds": store.bounds(clauses), "points": [row_to_json(r) for r in points]}, headers=NOSTORE)
        cell = 44 * 360 / (256 * 2 ** zoom)
        return JSONResponse({"mode": "cells", "total": total, "bounds": store.bounds(clauses),
                             "cells": store.map_cells(clauses, cell)}, headers=NOSTORE)
    @app.get("/api/stats", dependencies=gate)
    def stats(request: Request, f: Filters = Depends(filters_from_query)):
        store = ctx_of(request).store
        clauses = f.clauses()
        out = compute_stats(store.all_networks(clauses))
        out["cities"] = store.facet(networks.c.city, clauses, 10)
        out["vendors"] = store.facet(networks.c.vendor, clauses, 10)
        out["last_import"] = store.last_import()
        return JSONResponse(out, headers=NOSTORE)
    @app.get("/api/facets", dependencies=gate)
    def facets(request: Request):

        store = ctx_of(request).store
        return JSONResponse({"cities": store.facet(networks.c.city, [], 300), "vendors": store.facet(networks.c.vendor, [], 300),
                             "term_version": TERM_VERSION}, headers={"Cache-Control": "private, max-age=60"})

    @app.get("/api/sync-history", dependencies=gate)
    def sync_history(request: Request, limit: int = Query(50, ge=1, le=PAGE_MAX), offset: int = Query(0, ge=0, le=10_000_000)):
        total, rows = ctx_of(request).store.sync_history(limit, offset)
        return JSONResponse({"total": total, "limit": limit, "offset": offset, "events": rows}, headers=NOSTORE)


    @app.get("/api/v1/ping", dependencies=[Depends(require_bearer)])
    def ping():
        return {"ok": True, "server_time_ms": int(time.time() * 1000)}

    @app.post("/api/v1/ingest", dependencies=[Depends(require_bearer)])
    async def ingest(request: Request):
        ctx = ctx_of(request)
        body = await read_limited_body(request, ctx.settings.max_ingest_bytes)
        try:
            payload = IngestRequest.model_validate_json(body)
        except ValidationError as e:
            first = e.errors()[0]
            where = ".".join(str(p) for p in first["loc"])
            raise HTTPException(422, f"payload invalido em {where}: {first['msg']}")
        records = {}
        for item in payload.networks:
            rec = item.to_network()
            records[rec.bssid] = merge_observations(records.get(rec.bssid), rec)
        try:
            summary = ctx.store.apply_import(list(records.values()), "app", "app", len(payload.networks), 0)
        except DataError as e:
            log.warning("ingest recusado pelo banco: %s", str(e.orig)[:200])
            raise HTTPException(422, "payload com valores fora dos limites aceitos pelo banco")
        log.info("ingest: %d rede(s) (%d novas, %d atualizadas)", len(records), summary["added"], summary["updated"])
        return summary

    static_dir = Path(settings.static_dir)
    if static_dir.is_dir():
        app.mount("/", _Static(directory=static_dir, html=True), name="frontend")
    return app


class _Static(StaticFiles):

    async def get_response(self, path, scope):
        response = await super().get_response(path, scope)
        if path.startswith("assets/"):
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        else:
            response.headers["Cache-Control"] = "no-cache"
        return response
