"""Minimal Kite Connect v3 client: auth, instruments, quotes, historical data, orders, and the
binary WebSocket ticker.

Implemented directly on httpx/websockets so the backend has no hidden
dependencies; endpoints and the binary packet layout follow
https://kite.trade/docs/connect/v3/.

The API secret and access token never leave the backend.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import struct
from collections.abc import Awaitable, Callable, Iterable
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx

from ..models import Candle, Tick
from .chain_builder import RawQuote

log = logging.getLogger(__name__)

IST = timezone(timedelta(hours=5, minutes=30))
API_ROOT = "https://api.kite.trade"
LOGIN_URL = "https://kite.zerodha.com/connect/login"
WS_ROOT = "wss://ws.kite.trade"

# Kite's per-endpoint rate limits (requests/second).
QUOTE_LIMIT_PER_SEC = 1
HISTORICAL_LIMIT_PER_SEC = 3
ORDER_LIMIT_PER_SEC = 10
QUOTE_MAX_INSTRUMENTS = 500


class KiteError(RuntimeError):
    def __init__(self, message: str, error_type: str = "", status: int = 0):
        super().__init__(message)
        self.error_type = error_type
        self.status = status


class _RateLimiter:
    def __init__(self, per_second: float):
        self.interval = 1.0 / per_second
        self._next = 0.0
        self._lock = asyncio.Lock()

    async def wait(self) -> None:
        async with self._lock:
            loop = asyncio.get_running_loop()
            now = loop.time()
            if now < self._next:
                await asyncio.sleep(self._next - now)
                now = loop.time()
            self._next = now + self.interval


class KiteClient:
    def __init__(self, api_key: str, api_secret: str = "", access_token: str = "", timeout: float = 10.0,
                 transport: httpx.AsyncBaseTransport | None = None):
        self.api_key = api_key
        self.api_secret = api_secret
        self.access_token = access_token
        self._http = httpx.AsyncClient(base_url=API_ROOT, timeout=timeout, transport=transport)
        self._quote_rl = _RateLimiter(QUOTE_LIMIT_PER_SEC)
        self._hist_rl = _RateLimiter(HISTORICAL_LIMIT_PER_SEC)
        self._order_rl = _RateLimiter(ORDER_LIMIT_PER_SEC)

    async def close(self) -> None:
        await self._http.aclose()

    # ── auth ────────────────────────────────────────────────────────────
    def login_url(self) -> str:
        return f"{LOGIN_URL}?v=3&api_key={self.api_key}"

    @staticmethod
    def checksum(api_key: str, request_token: str, api_secret: str) -> str:
        return hashlib.sha256(f"{api_key}{request_token}{api_secret}".encode()).hexdigest()

    async def generate_session(self, request_token: str) -> dict[str, Any]:
        data = await self._request(
            "POST", "/session/token", auth=False,
            data={
                "api_key": self.api_key,
                "request_token": request_token,
                "checksum": self.checksum(self.api_key, request_token, self.api_secret),
            },
        )
        self.access_token = data["access_token"]
        return data

    async def invalidate_session(self) -> None:
        await self._request("DELETE", "/session/token", params={"api_key": self.api_key, "access_token": self.access_token})
        self.access_token = ""

    # ── plumbing ────────────────────────────────────────────────────────
    def _headers(self, auth: bool) -> dict[str, str]:
        h = {"X-Kite-Version": "3"}
        if auth:
            h["Authorization"] = f"token {self.api_key}:{self.access_token}"
        return h

    async def _request(self, method: str, path: str, auth: bool = True, raw: bool = False, **kw: Any) -> Any:
        resp = await self._http.request(method, path, headers=self._headers(auth), **kw)
        if raw:
            if resp.status_code >= 400:
                raise KiteError(resp.text, status=resp.status_code)
            return resp.text
        try:
            body = resp.json()
        except ValueError as exc:
            raise KiteError(f"non-JSON response ({resp.status_code})", status=resp.status_code) from exc
        if resp.status_code >= 400 or body.get("status") == "error":
            raise KiteError(body.get("message", "Kite error"), body.get("error_type", ""), resp.status_code)
        return body.get("data")

    # ── market data ─────────────────────────────────────────────────────
    async def instruments(self, exchange: str = "NFO") -> str:
        """Raw CSV instrument dump for an exchange."""
        return await self._request("GET", f"/instruments/{exchange}", raw=True)

    async def quote(self, instruments: Iterable[str]) -> dict[str, Any]:
        """Full quotes keyed by 'EXCHANGE:TRADINGSYMBOL' (batched to 500 per call)."""
        keys = list(instruments)
        out: dict[str, Any] = {}
        for i in range(0, len(keys), QUOTE_MAX_INSTRUMENTS):
            await self._quote_rl.wait()
            out.update(await self._request("GET", "/quote", params=[("i", k) for k in keys[i : i + QUOTE_MAX_INSTRUMENTS]]) or {})
        return out

    async def historical(self, token: int, interval: str, start: datetime, end: datetime, oi: bool = False,
                         continuous: bool = False) -> list[Candle]:
        """interval: minute, 3minute, 5minute, 15minute, 60minute, day."""
        await self._hist_rl.wait()
        data = await self._request(
            "GET", f"/instruments/historical/{token}/{interval}",
            params={
                "from": start.strftime("%Y-%m-%d %H:%M:%S"),
                "to": end.strftime("%Y-%m-%d %H:%M:%S"),
                "oi": int(oi),
                "continuous": int(continuous),
            },
        )
        out = []
        for row in data.get("candles", []):
            ts = datetime.fromisoformat(row[0]).astimezone(IST)
            out.append(Candle(ts, row[1], row[2], row[3], row[4], row[5] if len(row) > 5 else 0, row[6] if len(row) > 6 else 0))
        return out

    # ── orders ──────────────────────────────────────────────────────────
    async def place_order(self, *, tradingsymbol: str, exchange: str, transaction_type: str, quantity: int,
                          order_type: str = "LIMIT", product: str = "NRML", price: float | None = None,
                          trigger_price: float | None = None, validity: str = "DAY", tag: str = "",
                          variety: str = "regular") -> str:
        """Registers an order and returns its order_id. Registration is NOT a fill — track status."""
        form: dict[str, Any] = {
            "tradingsymbol": tradingsymbol,
            "exchange": exchange,
            "transaction_type": transaction_type,
            "quantity": quantity,
            "order_type": order_type,
            "product": product,
            "validity": validity,
        }
        if price is not None:
            form["price"] = f"{price:.2f}"
        if trigger_price is not None:
            form["trigger_price"] = f"{trigger_price:.2f}"
        if tag:
            form["tag"] = tag[:20]
        await self._order_rl.wait()
        data = await self._request("POST", f"/orders/{variety}", data=form)
        return data["order_id"]

    async def modify_order(self, order_id: str, variety: str = "regular", **fields: Any) -> str:
        await self._order_rl.wait()
        data = await self._request("PUT", f"/orders/{variety}/{order_id}", data=fields)
        return data["order_id"]

    async def cancel_order(self, order_id: str, variety: str = "regular") -> str:
        await self._order_rl.wait()
        data = await self._request("DELETE", f"/orders/{variety}/{order_id}")
        return data["order_id"]

    async def orders(self) -> list[dict[str, Any]]:
        return await self._request("GET", "/orders")

    async def order_history(self, order_id: str) -> list[dict[str, Any]]:
        return await self._request("GET", f"/orders/{order_id}")

    async def positions(self) -> dict[str, Any]:
        return await self._request("GET", "/portfolio/positions")

    async def margins(self) -> dict[str, Any]:
        return await self._request("GET", "/user/margins")


def raw_quote_from_kite(q: dict[str, Any]) -> RawQuote:
    depth = q.get("depth") or {}
    buy = depth.get("buy") or [{}]
    sell = depth.get("sell") or [{}]
    return RawQuote(
        instrument_token=int(q["instrument_token"]),
        last_price=float(q.get("last_price") or 0),
        bid=float(buy[0].get("price") or 0),
        ask=float(sell[0].get("price") or 0),
        volume=int(q.get("volume") or 0),
        oi=int(q.get("oi") or 0),
    )


# ── WebSocket binary protocol ─────────────────────────────────────────────

MODE_LTP, MODE_QUOTE, MODE_FULL = "ltp", "quote", "full"
_SEG_CDS, _SEG_BCD = 3, 6


def _divisor(token: int) -> float:
    seg = token & 0xFF
    if seg == _SEG_CDS:
        return 10_000_000.0
    if seg == _SEG_BCD:
        return 10_000.0
    return 100.0


def parse_binary(message: bytes) -> list[dict[str, Any]]:
    """Parse one Kite ticker binary frame into tick dicts. A 1-byte frame is a heartbeat."""
    if len(message) < 2:
        return []
    count = struct.unpack(">H", message[:2])[0]
    offset, ticks = 2, []
    for _ in range(count):
        size = struct.unpack(">H", message[offset : offset + 2])[0]
        packet = message[offset + 2 : offset + 2 + size]
        offset += 2 + size
        tick = _parse_packet(packet)
        if tick:
            ticks.append(tick)
    return ticks


def _parse_packet(p: bytes) -> dict[str, Any] | None:
    n = len(p)
    if n < 8:
        return None
    token = struct.unpack(">I", p[0:4])[0]
    div = _divisor(token)
    seg = token & 0xFF
    is_index = seg == 9  # NSE indices segment
    ltp = struct.unpack(">i", p[4:8])[0] / div
    tick: dict[str, Any] = {"instrument_token": token, "last_price": ltp, "tradable": not is_index}
    if n == 8:
        tick["mode"] = MODE_LTP
        return tick
    if n in (28, 32):  # index quote / full
        high, low, open_, close, change = (v / div for v in struct.unpack(">iiiii", p[8:28]))
        tick.update(mode=MODE_QUOTE if n == 28 else MODE_FULL, ohlc={"high": high, "low": low, "open": open_, "close": close},
                    change=change)
        if n == 32:
            tick["exchange_timestamp"] = datetime.fromtimestamp(struct.unpack(">i", p[28:32])[0], IST)
        return tick
    if n >= 44:
        f = struct.unpack(">iiiiiiiiiii", p[0:44])
        tick.update(
            mode=MODE_QUOTE,
            last_traded_quantity=f[2],
            average_traded_price=f[3] / div,
            volume_traded=f[4],
            total_buy_quantity=f[5],
            total_sell_quantity=f[6],
            ohlc={"open": f[7] / div, "high": f[8] / div, "low": f[9] / div, "close": f[10] / div},
        )
    if n >= 184:
        last_trade_time, oi, oi_high, oi_low, exch_ts = struct.unpack(">iiiii", p[44:64])
        tick.update(
            mode=MODE_FULL,
            last_trade_time=datetime.fromtimestamp(last_trade_time, IST) if last_trade_time else None,
            oi=oi, oi_day_high=oi_high, oi_day_low=oi_low,
            exchange_timestamp=datetime.fromtimestamp(exch_ts, IST) if exch_ts else None,
        )
        depth: dict[str, list[dict[str, Any]]] = {"buy": [], "sell": []}
        for i in range(10):
            qty, price, orders = struct.unpack(">iiH", p[64 + i * 12 : 64 + i * 12 + 10])
            depth["buy" if i < 5 else "sell"].append({"quantity": qty, "price": price / div, "orders": orders})
        tick["depth"] = depth
    return tick


def tick_from_dict(d: dict[str, Any], fallback_ts: datetime | None = None) -> Tick:
    depth = d.get("depth") or {}
    buy = (depth.get("buy") or [{}])[0]
    sell = (depth.get("sell") or [{}])[0]
    return Tick(
        instrument_token=d["instrument_token"],
        last_price=d["last_price"],
        timestamp=d.get("exchange_timestamp") or fallback_ts or datetime.now(IST),
        volume=d.get("volume_traded", 0),
        oi=d.get("oi", 0),
        bid=buy.get("price", 0.0),
        ask=sell.get("price", 0.0),
        bid_qty=buy.get("quantity", 0),
        ask_qty=sell.get("quantity", 0),
    )


TickHandler = Callable[[list[dict[str, Any]]], Awaitable[None]]
OrderHandler = Callable[[dict[str, Any]], Awaitable[None]]


class KiteTicker:
    """Async WebSocket ticker with automatic reconnect and resubscription."""

    def __init__(self, api_key: str, access_token: str, on_ticks: TickHandler, on_order: OrderHandler | None = None,
                 max_backoff: float = 60.0):
        self.url = f"{WS_ROOT}?api_key={api_key}&access_token={access_token}"
        self.on_ticks = on_ticks
        self.on_order = on_order
        self.max_backoff = max_backoff
        self.modes: dict[int, str] = {}
        self._ws: Any = None
        self._stop = asyncio.Event()

    async def subscribe(self, tokens: Iterable[int], mode: str = MODE_FULL) -> None:
        tokens = list(tokens)
        for t in tokens:
            self.modes[t] = mode
        if self._ws is not None:
            await self._send_subscriptions(tokens, mode)

    async def unsubscribe(self, tokens: Iterable[int]) -> None:
        tokens = list(tokens)
        for t in tokens:
            self.modes.pop(t, None)
        if self._ws is not None:
            await self._ws.send(json.dumps({"a": "unsubscribe", "v": tokens}))

    async def _send_subscriptions(self, tokens: list[int], mode: str) -> None:
        await self._ws.send(json.dumps({"a": "subscribe", "v": tokens}))
        await self._ws.send(json.dumps({"a": "mode", "v": [mode, tokens]}))

    async def run(self) -> None:
        import websockets  # imported lazily so unit tests don't need a network stack

        backoff = 1.0
        while not self._stop.is_set():
            try:
                async with websockets.connect(self.url, ping_interval=None, max_size=2**22) as ws:
                    self._ws = ws
                    backoff = 1.0
                    by_mode: dict[str, list[int]] = {}
                    for t, m in self.modes.items():
                        by_mode.setdefault(m, []).append(t)
                    for m, toks in by_mode.items():
                        await self._send_subscriptions(toks, m)
                    async for msg in ws:
                        if isinstance(msg, bytes):
                            ticks = parse_binary(msg)
                            if ticks:
                                await self.on_ticks(ticks)
                        else:
                            await self._on_text(msg)
            except Exception as exc:  # network errors, token expiry, etc.
                log.warning("ticker disconnected: %s; reconnecting in %.0fs", exc, backoff)
            finally:
                self._ws = None
            if self._stop.is_set():
                break
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, self.max_backoff)

    async def _on_text(self, msg: str) -> None:
        try:
            payload = json.loads(msg)
        except ValueError:
            return
        if payload.get("type") == "order" and self.on_order:
            await self.on_order(payload.get("data") or {})
        elif payload.get("type") == "error":
            log.error("ticker error: %s", payload.get("data"))

    async def stop(self) -> None:
        self._stop.set()
        if self._ws is not None:
            await self._ws.close()
