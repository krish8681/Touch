"""FastAPI backend for the Android app.

Every endpoint except /health and the Kite login redirect requires
`Authorization: Bearer <APP_API_TOKEN>` (WebSocket: `?token=`). Kite
credentials stay on this server.
"""

from __future__ import annotations

import asyncio
import hmac
import logging
from contextlib import asynccontextmanager
from dataclasses import asdict
from datetime import datetime
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field

from ..config import Settings
from ..data.kite import IST, KiteClient, KiteError
from ..data.sources import KiteSource, MarketSource, SyntheticSource
from ..engines.decision import DecisionEngine
from ..engines.direction import DirectionEngine
from ..engines.news import EventWindow, NewsItem
from ..execution.live import LiveBroker
from ..models import _jsonable
from ..service import TradingService, position_dict
from ..storage.journal import Journal

log = logging.getLogger(__name__)


class KillSwitchBody(BaseModel):
    on: bool
    flatten: bool = False


class NewsBody(BaseModel):
    headline: str
    source: str = "manual"
    published_at: datetime | None = None
    direction: float = Field(ge=-1, le=1)
    severity: float = Field(ge=0, le=1)
    relevance: float = Field(1.0, ge=0, le=1)
    reliability: float = Field(0.7, ge=0, le=1)


class EventBody(BaseModel):
    name: str
    at: datetime
    block_before_min: int = 30
    block_after_min: int = 30


def create_app(settings: Settings | None = None, source: MarketSource | None = None, start_service: bool = True) -> FastAPI:
    settings = settings or Settings.from_env()
    state: dict[str, Any] = {"kite": None, "service": None}

    def build_service(src: MarketSource, kite: KiteClient | None) -> TradingService:
        direction = DirectionEngine.load(settings.model_path)
        engine = DecisionEngine(settings, direction)
        live = LiveBroker(settings, kite) if settings.live_allowed and kite is not None else None
        svc = TradingService(settings, src, engine, Journal(settings.database_url), live)
        if live is not None and isinstance(src, KiteSource):
            src.order_handlers.append(live.on_order_update)
        return svc

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if settings.kite_api_key:
            state["kite"] = KiteClient(settings.kite_api_key, settings.kite_api_secret)
        if start_service:
            src = source
            if src is None and settings.data_source == "synthetic":
                src = SyntheticSource(settings)
            if src is not None:
                state["service"] = build_service(src, state["kite"])
                await state["service"].start()
        yield
        if state["service"]:
            await state["service"].stop()
        if state["kite"]:
            await state["kite"].close()

    app = FastAPI(title="NIFTY AI Options Engine", version="1.0.0", lifespan=lifespan)
    app.state.engine_state = state

    def check_token(token: str | None) -> None:
        if not settings.app_api_token:
            raise HTTPException(503, "APP_API_TOKEN is not configured on the server")
        if not token or not hmac.compare_digest(token, settings.app_api_token):
            raise HTTPException(401, "invalid token")

    def auth(authorization: str | None = Header(default=None)) -> None:
        token = authorization.removeprefix("Bearer ").strip() if authorization else None
        check_token(token)

    def svc() -> TradingService:
        s = state["service"]
        if s is None:
            raise HTTPException(503, "engine not running (log in to Kite first, or use DATA_SOURCE=synthetic)")
        return s

    # ── public ─────────────────────────────────────────────────────────
    @app.get("/health")
    async def health() -> dict[str, Any]:
        s = state["service"]
        return {"status": "ok", "engine_running": s is not None, "data_source": settings.data_source,
                "execution_mode": settings.execution_mode, "live_allowed": settings.live_allowed,
                "time": datetime.now(IST).isoformat()}

    @app.get("/auth/kite/callback")
    async def kite_callback(request_token: str, status: str = "success") -> dict[str, Any]:
        """Kite redirects here after login. Exchanges the one-time request token for an access token."""
        kite: KiteClient | None = state["kite"]
        if kite is None:
            raise HTTPException(503, "KITE_API_KEY not configured")
        if status != "success":
            raise HTTPException(400, f"Kite login status: {status}")
        try:
            session = await kite.generate_session(request_token)
        except KiteError as exc:
            raise HTTPException(400, str(exc)) from exc
        if settings.data_source == "kite":
            if state["service"]:
                await state["service"].stop()
            state["service"] = build_service(KiteSource(settings, kite), kite)
            await state["service"].start()
        return {"status": "logged_in", "user_id": session.get("user_id"), "engine_running": state["service"] is not None}

    # ── authenticated ──────────────────────────────────────────────────
    @app.get("/auth/kite/login-url", dependencies=[Depends(auth)])
    async def kite_login_url() -> dict[str, str]:
        kite = state["kite"]
        if kite is None:
            raise HTTPException(503, "KITE_API_KEY not configured")
        return {"url": kite.login_url()}

    @app.get("/signal/latest", dependencies=[Depends(auth)])
    async def latest_signal() -> dict[str, Any]:
        s = svc()
        return s.latest.to_dict() if s.latest else {}

    @app.post("/signal/evaluate", dependencies=[Depends(auth)])
    async def evaluate_now() -> dict[str, Any]:
        sig = await svc().evaluate_once()
        if sig is None:
            raise HTTPException(409, "not enough market data yet")
        return sig.to_dict()

    @app.get("/signals", dependencies=[Depends(auth)])
    async def signals(limit: int = Query(50, ge=1, le=500)) -> list[dict[str, Any]]:
        return svc().journal.recent_signals(limit)

    @app.get("/option-chain", dependencies=[Depends(auth)])
    async def option_chain() -> dict[str, Any]:
        ch = svc().source.chain()
        if ch is None:
            raise HTTPException(409, "no chain yet")
        rows = []
        for k in ch.strikes:
            c, p = ch.calls.get(k), ch.puts.get(k)
            rows.append({"strike": k, "ce": asdict(c) if c else None, "pe": asdict(p) if p else None})
        return _jsonable({"spot": ch.spot, "future": ch.future, "expiry": ch.expiry, "timestamp": ch.timestamp,
                          "atm": ch.atm_strike(settings.strike_step), "rows": rows})

    @app.get("/positions", dependencies=[Depends(auth)])
    async def positions(open_only: bool = False) -> list[dict[str, Any]]:
        return [position_dict(p) for p in svc().positions if p.is_open or not open_only]

    @app.post("/positions/{pos_id}/close", dependencies=[Depends(auth)])
    async def close_position(pos_id: int) -> dict[str, Any]:
        s = svc()
        for p in s.positions:
            if (p.journal_id or p.id) == pos_id and p.is_open:
                return position_dict(await s.close_position(p, "MANUAL"))
        raise HTTPException(404, "no open position with that id")

    @app.get("/trades", dependencies=[Depends(auth)])
    async def trades() -> list[dict[str, Any]]:
        return _jsonable(svc().journal.trades_between())

    @app.get("/approvals", dependencies=[Depends(auth)])
    async def approvals() -> list[dict[str, Any]]:
        return [_jsonable({"id": pa.id, "signal_ts": pa.signal_ts, "proposal": asdict(pa.proposal)}) for pa in svc().pending.values()]

    @app.post("/approvals/{pid}/approve", dependencies=[Depends(auth)])
    async def approve(pid: int) -> dict[str, Any]:
        try:
            return position_dict(await svc().approve(pid))
        except KeyError as exc:
            raise HTTPException(404, str(exc)) from exc
        except PermissionError as exc:
            raise HTTPException(409, str(exc)) from exc

    @app.post("/approvals/{pid}/reject", dependencies=[Depends(auth)])
    async def reject(pid: int) -> dict[str, str]:
        svc().reject(pid)
        return {"status": "rejected"}

    @app.get("/risk", dependencies=[Depends(auth)])
    async def risk() -> dict[str, Any]:
        return svc().risk_state()

    @app.post("/risk/kill-switch", dependencies=[Depends(auth)])
    async def kill_switch(body: KillSwitchBody) -> dict[str, Any]:
        s = svc()
        await s.kill_switch(body.on, body.flatten)
        return s.risk_state()

    @app.post("/news", dependencies=[Depends(auth)])
    async def add_news(body: NewsBody) -> dict[str, Any]:
        s = svc()
        s.engine.news.add(NewsItem(body.headline, body.source, body.published_at or s.source.now(), body.direction,
                                   body.severity, body.relevance, body.reliability))
        return {"news_impact": s.engine.news.impact(s.source.now())}

    @app.post("/events", dependencies=[Depends(auth)])
    async def add_event(body: EventBody) -> dict[str, Any]:
        svc().engine.news.add_event(EventWindow(body.name, body.at, body.block_before_min, body.block_after_min))
        return {"status": "added"}

    @app.get("/model", dependencies=[Depends(auth)])
    async def model_report() -> dict[str, Any]:
        d = svc().engine.direction
        return _jsonable({"trained": d.trained, "reports": {h: asdict(r) for h, r in d.reports.items()}})

    @app.websocket("/ws")
    async def stream(ws: WebSocket, token: str | None = None) -> None:
        try:
            check_token(token)
        except HTTPException:
            await ws.close(code=4401)
            return
        s = state["service"]
        if s is None:
            await ws.close(code=4503)
            return
        await ws.accept()
        q: asyncio.Queue = asyncio.Queue(maxsize=100)
        s.subscribers.add(q)
        try:
            if s.latest:
                await ws.send_json({"type": "signal", "data": s.latest.to_dict()})
            while True:
                await ws.send_json(await q.get())
        except WebSocketDisconnect:
            pass
        finally:
            s.subscribers.discard(q)

    return app
