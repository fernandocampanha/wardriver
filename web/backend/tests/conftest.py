import pytest
from fastapi.testclient import TestClient

from app import auth, main
from app.config import Settings
from app.db import Store, make_engine

TOKEN = auth.new_token()
PASSWORD = "test-password"
CSRF = {"X-Requested-With": "wardriver-web"}
BEARER = {"Authorization": f"Bearer {TOKEN}"}


def make_settings(tmp_path, **over) -> Settings:
    base = dict(database_url="sqlite://", panel_password_hash=auth.hash_password(PASSWORD),
                upload_token_sha256=auth.token_sha256(TOKEN), session_ttl_s=3600,
                max_ingest_bytes=64 * 1024, secure_cookie=False, trust_proxy=False, static_dir=tmp_path / "dist",
                db_wait_s=0.0, rate_per_minute=10_000, rate_burst_map=10_000)
    base.update(over)
    return Settings(**base)


@pytest.fixture
def static_dir(tmp_path):
    d = tmp_path / "dist"
    (d / "assets").mkdir(parents=True)
    (d / "index.html").write_text("<!doctype html><html><body><div id=root></div></body></html>")
    (d / "assets" / "app-abc123.js").write_text("console.log(1)")
    return d


@pytest.fixture
def make_client(tmp_path, static_dir):
    created = []

    def factory(**over):
        store = Store(make_engine("sqlite://"))
        app = main.create_app(make_settings(tmp_path, **over), store)
        client = TestClient(app, base_url="http://testserver")
        created.append(client)
        return client

    yield factory
    for c in created:
        c.close()


@pytest.fixture
def client(make_client):
    return make_client()


@pytest.fixture
def accepted(client):
    r = client.post("/api/term/accept", json={"version": main.TERM_VERSION, "password": PASSWORD}, headers=CSRF)
    assert r.status_code == 200, r.text
    return client
