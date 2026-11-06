
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Union

from sqlalchemy.engine import URL

ROOT = Path(__file__).resolve().parent.parent


class ConfigError(Exception):
    pass


@dataclass
class Settings:
    database_url: Union[str, URL]
    panel_password_hash: str
    upload_token_sha256: str
    session_ttl_s: int
    max_ingest_bytes: int
    secure_cookie: bool
    trust_proxy: bool
    static_dir: Path
    db_wait_s: float
    rate_per_minute: int
    rate_burst_map: int


def database_url(env: Mapping[str, str]) -> Union[str, URL]:


    url = env.get("WARDRIVER_DATABASE_URL", "").strip()
    if url:
        return url
    host, user, password = env.get("WARDRIVER_DB_HOST", ""), env.get("WARDRIVER_DB_USER", ""), env.get("WARDRIVER_DB_PASSWORD", "")
    if not (host and user and password):
        raise ConfigError("banco nao configurado: defina WARDRIVER_DB_HOST, WARDRIVER_DB_USER e WARDRIVER_DB_PASSWORD "
                          "(ou WARDRIVER_DATABASE_URL completa)")
    return URL.create("mysql+pymysql", username=user, password=password, host=host,
                      port=int(env.get("WARDRIVER_DB_PORT", "3306")), database=env.get("WARDRIVER_DB_NAME", "wardriver"),
                      query={"charset": "utf8mb4"})


def panel_password_hash(env: Mapping[str, str]) -> str:
    value = env.get("WARDRIVER_WEB_PANEL_PASSWORD_HASH", "").strip()
    if not value:
        raise ConfigError("painel sem senha: defina WARDRIVER_WEB_PANEL_PASSWORD_HASH "
                          "(gere com `python -m app.hashpw --password`)")
    return value


def load_settings(env: Mapping[str, str] = os.environ) -> Settings:
    return Settings(
        database_url=database_url(env),
        panel_password_hash=panel_password_hash(env),
        upload_token_sha256=env.get("WARDRIVER_WEB_UPLOAD_TOKEN_SHA256", "").strip().lower(),
        session_ttl_s=int(env.get("WARDRIVER_WEB_SESSION_HOURS", "12")) * 3600,
        max_ingest_bytes=int(env.get("WARDRIVER_WEB_MAX_INGEST_MB", "8")) * 1024 * 1024,

        secure_cookie=env.get("WARDRIVER_WEB_INSECURE_COOKIE", "0") != "1",
        trust_proxy=env.get("WARDRIVER_WEB_TRUST_PROXY", "0") == "1",
        static_dir=Path(env.get("WARDRIVER_WEB_STATIC", str(ROOT / "frontend_dist"))),
        db_wait_s=float(env.get("WARDRIVER_DB_WAIT_SECONDS", "60")),
        rate_per_minute=int(env.get("WARDRIVER_WEB_RATE_PER_MINUTE", "240")),
        rate_burst_map=int(env.get("WARDRIVER_WEB_RATE_MAP_PER_MINUTE", "90")),
    )
