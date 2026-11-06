import json

import pytest

from app import main
from tests.conftest import BEARER, CSRF, PASSWORD

VERSION = main.TERM_VERSION


def net(**kw):
    base = {"bssid": "70:aa:bb:10:00:01", "ssid": "Loja", "hidden": False, "channel": 6, "rssi": -60, "enc": "WPA2",
            "seen": 3, "wps": False, "lat": -21.1378, "lon": -48.9728, "session": "s-1", "last": 1760000000000}
    base.update(kw)
    return base


def ingest(client, networks, headers=BEARER):
    return client.post("/api/v1/ingest", content=json.dumps({"device": "tel-1", "networks": networks}), headers=headers)


def seed(client):
    ingest(client, [
        net(),
        net(bssid="D8:00:4D:00:00:02", ssid="VIVO-1234", enc="OPEN", channel=1, rssi=-80, lat=-23.55, lon=-46.63, last=1770000000000),
        net(bssid="72:AA:BB:10:00:03", ssid="", hidden=True, enc="WPA2", channel=11, rssi=-70, wps=True, lat=None, lon=None, seen=1),
        net(bssid="70:AA:BB:10:00:04", ssid="Café", enc="WEP", channel=6, rssi=-40, lat=-21.14, lon=-48.97, seen=9),
    ])




def test_health_and_me_public_without_data(client):
    assert client.get("/api/health").json() == {"ok": True}
    assert client.get("/api/me").json() == {"accepted": False, "term_version": VERSION}


@pytest.mark.parametrize("path", ["/api/networks", "/api/map", "/api/stats", "/api/facets"])
def test_data_requires_term(client, path):
    seed(client)
    assert client.get(path).status_code == 403


@pytest.mark.parametrize("path", ["/api/login", "/api/logout", "/api/import", "/api/export.csv", "/api/imports"])
def test_removed_routes_are_gone(client, path):
    assert client.get(path).status_code in (404, 405)
    assert client.post(path, headers=CSRF).status_code in (404, 405)


def test_upload_token_cannot_read(client):
    assert client.get("/api/networks", headers=BEARER).status_code == 403


def test_accept_sets_session_cookie_and_unlocks(client):
    r = client.post("/api/term/accept", json={"version": VERSION, "password": PASSWORD}, headers=CSRF)
    assert r.status_code == 200
    cookie = r.headers["set-cookie"].lower()
    assert "httponly" in cookie and "samesite=strict" in cookie and "max-age" not in cookie
    assert client.get("/api/me").json()["accepted"] is True
    assert client.get("/api/networks").status_code == 200


def test_secure_flag_when_configured(make_client):
    r = make_client(secure_cookie=True).post("/api/term/accept", json={"version": VERSION, "password": PASSWORD}, headers=CSRF)
    assert "secure" in r.headers["set-cookie"].lower()


def test_wrong_or_missing_password_rejected(client):
    assert client.post("/api/term/accept", json={"version": VERSION, "password": "chuta"}, headers=CSRF).status_code == 401
    assert client.post("/api/term/accept", json={"version": VERSION, "password": ""}, headers=CSRF).status_code == 401
    assert client.post("/api/term/accept", json={"version": VERSION}, headers=CSRF).status_code == 400
    assert client.get("/api/networks").status_code == 403


def test_accept_needs_csrf_same_origin_and_current_version(client):
    assert client.post("/api/term/accept", json={"version": VERSION, "password": PASSWORD}).status_code == 403
    assert client.post("/api/term/accept", json={"version": VERSION, "password": PASSWORD},
                       headers={**CSRF, "Origin": "https://evil.example"}).status_code == 403
    assert client.post("/api/term/accept", json={"version": "antigo", "password": PASSWORD}, headers=CSRF).status_code == 409
    assert client.post("/api/term/accept", content=b"lixo", headers=CSRF).status_code == 400
    assert client.post("/api/term/accept", content=b"x" * 5000, headers=CSRF).status_code == 413
    assert client.get("/api/networks").status_code == 403


def test_revoke_locks_again(accepted):
    assert accepted.post("/api/term/revoke").status_code == 403
    assert accepted.post("/api/term/revoke", headers=CSRF).status_code == 200
    assert accepted.get("/api/networks").status_code == 403


def test_forged_cookie_rejected(client):
    client.cookies.set("wd_term", "abc")
    assert client.get("/api/networks").status_code == 403


def test_new_term_version_invalidates_old_acceptance(accepted, monkeypatch):
    monkeypatch.setattr(main, "TERM_VERSION", "novo")
    assert accepted.get("/api/networks").status_code == 403


def test_rate_limit_per_ip(make_client):
    c = make_client(rate_per_minute=5)
    c.post("/api/term/accept", json={"version": VERSION, "password": PASSWORD}, headers=CSRF)
    codes = [c.get("/api/networks").status_code for _ in range(8)]
    assert codes[:5] == [200] * 5 and codes[5:] == [429] * 3
    assert c.get("/api/networks").headers["retry-after"]


def test_rate_limit_uses_forwarded_ip_only_when_trusted(make_client):
    c = make_client(trust_proxy=True, rate_per_minute=2)
    c.post("/api/term/accept", json={"version": VERSION, "password": PASSWORD}, headers=CSRF)
    h = {"X-Forwarded-For": "9.9.9.9"}
    assert [c.get("/api/networks", headers=h).status_code for _ in range(3)] == [200, 200, 429]
    assert c.get("/api/networks", headers={"X-Forwarded-For": "8.8.8.8"}).status_code == 200

    assert c.get("/api/networks", headers={"X-Forwarded-For": "1.1.1.1, 9.9.9.9"}).status_code == 429


def test_openapi_docs_not_exposed(client):
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404




def macs(r):
    return {n["bssid"] for n in r.json()["networks"]}


def test_networks_page_sort_and_totals(accepted):
    seed(accepted)
    data = accepted.get("/api/networks").json()
    assert data["total"] == 4 and data["networks"][0]["bssid"] == "70:AA:BB:10:00:04"
    page = accepted.get("/api/networks?limit=2&offset=2&sort=bssid&order=asc").json()
    assert page["total"] == 4 and len(page["networks"]) == 2
    assert accepted.get("/api/networks?sort=hack").status_code == 400
    assert accepted.get("/api/networks?limit=100000").status_code == 422


@pytest.mark.parametrize("qs,expected", [
    ("enc=OPEN", {"D8:00:4D:00:00:02"}),
    ("enc=OPEN,WEP", {"D8:00:4D:00:00:02", "70:AA:BB:10:00:04"}),
    ("channel=11", {"72:AA:BB:10:00:03"}),
    ("rssi_min=-65", {"70:AA:BB:10:00:01", "70:AA:BB:10:00:04"}),
    ("rssi_max=-75", {"D8:00:4D:00:00:02"}),
    ("wps=true", {"72:AA:BB:10:00:03"}),
    ("hidden=only", {"72:AA:BB:10:00:03"}),
    ("hidden=no", {"70:AA:BB:10:00:01", "D8:00:4D:00:00:02", "70:AA:BB:10:00:04"}),
    ("has_pos=true", {"70:AA:BB:10:00:01", "D8:00:4D:00:00:02", "70:AA:BB:10:00:04"}),
    ("seen_min=5", {"70:AA:BB:10:00:04"}),
    ("since=1765000000000", {"D8:00:4D:00:00:02"}),
    ("until=1765000000000", {"70:AA:BB:10:00:01", "72:AA:BB:10:00:03", "70:AA:BB:10:00:04"}),
    ("city=Catanduva - SP", {"70:AA:BB:10:00:01", "70:AA:BB:10:00:04"}),
    ("city=Catanduva - SP|São Paulo - SP", {"70:AA:BB:10:00:01", "70:AA:BB:10:00:04", "D8:00:4D:00:00:02"}),
    ("local_admin=true", {"72:AA:BB:10:00:03"}),
    ("default_name=true", {"D8:00:4D:00:00:02"}),
    ("q=loja", {"70:AA:BB:10:00:01"}),
    ("q=vivo 1234", {"D8:00:4D:00:00:02"}),
    ("q=oculta", {"72:AA:BB:10:00:03"}),
    ("q=70aabb100004", {"70:AA:BB:10:00:04"}),
    ("bbox=-21.2,-49.1,-21.0,-48.8", {"70:AA:BB:10:00:01", "70:AA:BB:10:00:04"}),
])
def test_filters(accepted, qs, expected):
    seed(accepted)
    r = accepted.get(f"/api/networks?{qs}")
    assert r.status_code == 200, r.text
    assert macs(r) == expected


def test_vendor_filter_and_facets(accepted):
    seed(accepted)
    facets = accepted.get("/api/facets").json()
    assert {"name": "Catanduva - SP", "count": 2} in facets["cities"]
    apple = [v["name"] for v in facets["vendors"] if "Apple" in v["name"]]
    assert apple and macs(accepted.get(f"/api/networks?vendor={apple[0]}")) == {"D8:00:4D:00:00:02"}


def test_filter_is_parameterized_not_injectable(accepted):
    seed(accepted)
    for evil in ["' OR '1'='1", "%", "_", "\\", "'; DROP TABLE networks; --"]:
        r = accepted.get("/api/networks", params={"q": evil})
        assert r.status_code == 200 and r.json()["total"] == 0
    assert accepted.get("/api/networks").json()["total"] == 4


@pytest.mark.parametrize("qs", ["enc=WPA9", "channel=abc", "channel=999", "bbox=1,2", "bbox=50,0,10,0", "hidden=maybe",
                                "rssi_min=5", "since=-1", "q=" + "x" * 101])
def test_bad_filters_rejected(accepted, qs):
    assert accepted.get(f"/api/networks?{qs}").status_code in (400, 422)


def test_map_points_then_cells(accepted, monkeypatch):
    seed(accepted)
    d = accepted.get("/api/map?zoom=12").json()
    assert d["mode"] == "points" and d["total"] == 3 and not d["truncated"]
    assert all(p["lat"] is not None for p in d["points"])
    assert accepted.get("/api/map?zoom=12&enc=OPEN").json()["total"] == 1
    monkeypatch.setattr(main, "MAP_POINT_LIMIT", 1)
    cells = accepted.get("/api/map?zoom=6").json()
    assert cells["mode"] == "cells" and sum(c["n"] for c in cells["cells"]) == 3 and cells["total"] == 3
    assert sum(c["weak"] for c in cells["cells"]) == 2
    assert accepted.get("/api/map?zoom=17").json()["mode"] == "points"


def test_stats_respect_filters_and_include_facets(accepted):
    seed(accepted)
    full = accepted.get("/api/stats").json()
    assert (full["total"], full["weak"], full["hidden"], full["with_position"]) == (4, 2, 1, 3)
    assert full["cities"][0] == {"name": "Catanduva - SP", "count": 2} and len(full["signal"]) == 5
    sub = accepted.get("/api/stats?city=Catanduva - SP").json()
    assert (sub["total"], sub["weak"]) == (2, 1)
    assert accepted.get("/api/stats").headers["cache-control"] == "no-store"




def test_ping_needs_token(client):
    assert client.get("/api/v1/ping").status_code == 401
    r = client.get("/api/v1/ping", headers=BEARER)
    assert r.status_code == 200 and r.json()["ok"] is True


def test_ingest_requires_bearer_term_cookie_is_not_enough(accepted):
    assert ingest(accepted, [net()], headers=CSRF).status_code == 401


def test_ingest_stores_derived_fields_and_is_idempotent(accepted):
    r = ingest(accepted, [net(), net(bssid="70:AA:BB:10:00:02", hidden=True, ssid="", enc="OPEN", lat=None, lon=None)])
    assert r.status_code == 200, r.text
    assert (r.json()["added"], r.json()["total"]) == (2, 2)
    assert ingest(accepted, [net(), net(bssid="70:AA:BB:10:00:02", hidden=True, ssid="", enc="OPEN", lat=None, lon=None)]).json()["updated"] == 0
    by = {n["bssid"]: n for n in accepted.get("/api/networks").json()["networks"]}
    assert by["70:AA:BB:10:00:01"]["ssid"] == "Loja" and by["70:AA:BB:10:00:01"]["city"] == "Catanduva - SP"
    assert by["70:AA:BB:10:00:02"]["hidden"] is True and by["70:AA:BB:10:00:02"]["city"] == ""


def test_ingest_never_loses_name_or_position_and_renames_by_date(accepted):
    ingest(accepted, [net(ssid="Casa", rssi=-50, lat=-10.2, lon=-50.3, last=1000)])
    ingest(accepted, [net(ssid="", hidden=True, lat=None, lon=None, rssi=-90, last=2000)])
    n = accepted.get("/api/networks").json()["networks"][0]
    assert (n["ssid"], n["lat"], n["rssi"]) == ("Casa", -10.2, -50)
    ingest(accepted, [net(ssid="Casa Nova", last=3000, rssi=-90, lat=None, lon=None)])
    ingest(accepted, [net(ssid="Velha", last=500, rssi=-90, lat=None, lon=None)])
    assert accepted.get("/api/networks").json()["networks"][0]["ssid"] == "Casa Nova"


def test_ingest_merges_duplicate_bssid_in_same_batch(accepted):
    r = ingest(accepted, [net(rssi=-80, lat=-1.0, lon=-2.0), net(rssi=-40, lat=-3.0, lon=-4.0)])
    assert r.json()["networks"] == 1
    n = accepted.get("/api/networks").json()["networks"][0]
    assert (n["rssi"], n["lat"], n["lon"]) == (-40, -3.0, -4.0)


def test_ingest_zero_zero_is_no_fix_and_lone_coordinate_dropped(accepted):
    ingest(accepted, [net(bssid="70:AA:BB:10:00:03", lat=0.0, lon=0.0), net(bssid="70:AA:BB:10:00:04", lat=-10.0, lon=None)])
    by = {n["bssid"]: n for n in accepted.get("/api/networks").json()["networks"]}
    assert by["70:AA:BB:10:00:03"]["lat"] is None and by["70:AA:BB:10:00:04"]["lat"] is None


@pytest.mark.parametrize("bad", [
    {"bssid": "nao-e-mac"}, {"bssid": "70:AA:BB:10:00"}, {"rssi": 5}, {"rssi": -200}, {"lat": 91.0, "lon": 0.5},
    {"lon": 181.0, "lat": 1.0}, {"channel": 500}, {"enc": "WPA9"}, {"seen": 0}, {"last": -1}, {"ssid": "x" * 65},
    {"ssid": "\ud800"},
])
def test_ingest_rejects_invalid_fields(client, bad):
    r = client.post("/api/v1/ingest", content=json.dumps({"networks": [net(**bad)]}, ensure_ascii=True), headers=BEARER)
    assert r.status_code == 422, r.text
    assert "invalido" in r.json()["error"]


def test_ingest_batch_limits(make_client):
    client = make_client(max_ingest_bytes=8 * 1024 * 1024)
    assert ingest(client, []).status_code == 422
    assert ingest(client, [net(bssid="70:AA:BB:%02X:%02X:%02X" % (i >> 16 & 255, i >> 8 & 255, i & 255))
                           for i in range(5001)]).status_code == 422
    assert client.post("/api/v1/ingest", content=b"{}", headers=BEARER).status_code == 422
    assert client.post("/api/v1/ingest", content=b"lixo", headers=BEARER).status_code == 422


def test_ingest_size_limit(make_client):
    c = make_client(max_ingest_bytes=1000)
    assert ingest(c, [net(bssid="70:AA:BB:10:%02X:%02X" % (i >> 8, i & 255)) for i in range(50)]).status_code == 413

    def chunks():
        for _ in range(10):
            yield b"x" * 1000

    assert c.post("/api/v1/ingest", content=chunks(), headers=BEARER).status_code == 413


def test_ingest_strips_nul_and_keeps_utf8_names(accepted):
    ingest(accepted, [net(ssid="Café ☕️" + chr(0) + "X")])
    assert accepted.get("/api/networks").json()["networks"][0]["ssid"] == "Café ☕️X"




def test_static_and_security_headers(client):
    r = client.get("/")
    assert r.status_code == 200 and "<div id=root>" in r.text
    assert "script-src 'self'" in r.headers["content-security-policy"]
    assert r.headers["x-content-type-options"] == "nosniff" and r.headers["x-frame-options"] == "DENY"
    assert r.headers["cache-control"] == "no-cache"
    assert "immutable" in client.get("/assets/app-abc123.js").headers["cache-control"]


def test_api_responses_also_carry_security_headers(client):
    r = client.get("/api/networks")
    assert r.status_code == 403 and r.headers["x-content-type-options"] == "nosniff"


def test_hsts_only_behind_trusted_https_proxy(make_client):
    c = make_client(trust_proxy=True)
    assert "strict-transport-security" in c.get("/api/health", headers={"X-Forwarded-Proto": "https"}).headers
    assert "strict-transport-security" not in make_client().get("/api/health", headers={"X-Forwarded-Proto": "https"}).headers


@pytest.mark.parametrize("path", ["/../app/main.py", "/..%2fapp/main.py", "/%2e%2e/app/main.py", "/assets/../../app/auth.py"])
def test_path_traversal_blocked(client, path):
    r = client.get(path)
    assert r.status_code in (400, 404) or "def new_token" not in r.text


def test_no_cors_headers(client):
    r = client.get("/api/health", headers={"Origin": "https://evil.example"})
    assert "access-control-allow-origin" not in r.headers
    assert client.options("/api/networks", headers={"Origin": "https://evil.example",
                                                    "Access-Control-Request-Method": "GET"}).status_code in (400, 404, 405)


def test_unhandled_error_does_not_leak_details(accepted, monkeypatch):
    from fastapi.testclient import TestClient
    quiet = TestClient(accepted.app, raise_server_exceptions=False, cookies=accepted.cookies)
    monkeypatch.setattr(accepted.app.state.ctx.store, "page", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("segredo interno")))
    r = quiet.get("/api/networks")
    assert r.status_code == 500 and "segredo" not in r.text
