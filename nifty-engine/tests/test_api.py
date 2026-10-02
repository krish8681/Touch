import pytest
from fastapi.testclient import TestClient

from nifty_engine.api.app import create_app
from nifty_engine.config import Settings
from nifty_engine.data.sources import SyntheticSource

TOKEN = "test-token"
H = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture
def client(tmp_path):
    settings = Settings(app_api_token=TOKEN, database_url=f"sqlite:///{tmp_path}/t.db", model_path=str(tmp_path / "none.json"))
    app = create_app(settings, source=SyntheticSource(settings, seconds_per_minute=3600, history_days=2))
    with TestClient(app) as c:
        yield c


def test_health_is_public(client):
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["engine_running"]


def test_auth_required(client):
    assert client.get("/signal/latest").status_code == 401
    assert client.get("/signal/latest", headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_evaluate_and_read_back(client):
    r = client.post("/signal/evaluate", headers=H)
    assert r.status_code == 200
    sig = r.json()
    assert sig["action"] in ("TRADE", "NO_TRADE")
    assert len(sig["forecasts"]) == 4
    assert client.get("/signal/latest", headers=H).json()["timestamp"] == sig["timestamp"]
    assert len(client.get("/signals", headers=H).json()) == 1
    chain = client.get("/option-chain", headers=H).json()
    assert chain["rows"] and chain["atm"]
    assert isinstance(client.get("/positions", headers=H).json(), list)
    assert client.get("/model", headers=H).json()["trained"] is False


def test_kill_switch_blocks_trading(client):
    r = client.post("/risk/kill-switch", headers=H, json={"on": True})
    assert r.json()["kill_switch"] is True
    sig = client.post("/signal/evaluate", headers=H).json()
    assert sig["action"] == "NO_TRADE"
    assert any("kill switch" in b for b in sig["blocks"])


def test_news_endpoint(client):
    r = client.post("/news", headers=H, json={"headline": "Strong GDP print", "direction": 0.8, "severity": 0.6})
    assert r.status_code == 200 and r.json()["news_impact"] > 0


def test_websocket_requires_token_and_streams(client):
    client.post("/signal/evaluate", headers=H)
    with client.websocket_connect(f"/ws?token={TOKEN}") as ws:
        msg = ws.receive_json()
        assert msg["type"] == "signal"
