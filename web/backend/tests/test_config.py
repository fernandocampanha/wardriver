import pytest

from app.config import ConfigError, load_settings


def test_refuses_without_database():
    with pytest.raises(ConfigError):
        load_settings({})
    with pytest.raises(ConfigError):
        load_settings({"WARDRIVER_DB_HOST": "db"})


def test_refuses_without_panel_password():
    env = {"WARDRIVER_DATABASE_URL": "sqlite://"}
    with pytest.raises(ConfigError):
        load_settings(env)
    with pytest.raises(ConfigError):
        load_settings({**env, "WARDRIVER_WEB_PANEL_PASSWORD_HASH": "   "})


def test_database_by_parts_escapes_special_characters_in_password():
    env = {"WARDRIVER_DB_HOST": "db", "WARDRIVER_DB_USER": "wardriver", "WARDRIVER_DB_PASSWORD": "p@ss:w/rd#?%",
           "WARDRIVER_WEB_PANEL_PASSWORD_HASH": "x"}
    url = load_settings(env).database_url
    assert url.host == "db" and url.database == "wardriver" and url.password == "p@ss:w/rd#?%"
    assert url.query["charset"] == "utf8mb4" and url.drivername == "mysql+pymysql"
    assert "p@ss:w/rd#?%" not in url.render_as_string(hide_password=False).split("@db")[0]


def test_full_url_wins():
    env = {"WARDRIVER_DATABASE_URL": "sqlite://", "WARDRIVER_WEB_PANEL_PASSWORD_HASH": "x"}
    assert load_settings(env).database_url == "sqlite://"
