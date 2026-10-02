"""Market data sources for the live service.

`SyntheticSource` replays a generated market at an accelerated clock so the
whole stack (engine → API → Android app) can be exercised without Kite.

`KiteSource` builds the same snapshot from the Kite WebSocket: NIFTY index,
near-month future, India VIX and the current-expiry option strikes around ATM,
re-centring the subscribed strikes when NIFTY moves.
"""

from __future__ import annotations

import asyncio
import logging
from abc import ABC, abstractmethod
from datetime import date, datetime, timedelta
from typing import Any

from ..config import Settings
from ..engines.decision import MarketSnapshot
from ..models import OptionChain
from .candles import MultiTimeframe
from .chain_builder import RawQuote, build_chain
from .instruments import INDIA_VIX_TOKEN, NIFTY_INDEX_TOKEN, NiftyUniverse, parse_instruments_csv
from .kite import IST, KiteClient, KiteTicker, MODE_FULL, tick_from_dict
from .synthetic import SyntheticMarket

log = logging.getLogger(__name__)


class MarketSource(ABC):
    @abstractmethod
    async def start(self) -> None: ...

    @abstractmethod
    async def stop(self) -> None: ...

    @abstractmethod
    def now(self) -> datetime: ...

    @abstractmethod
    def chain(self) -> OptionChain | None: ...

    @abstractmethod
    def snapshot(self) -> MarketSnapshot | None: ...

    async def wait_minute(self) -> None:
        """Block until the next 1-minute candle closes."""
        await self._minute_event.wait()
        self._minute_event.clear()

    _minute_event: asyncio.Event


class SyntheticSource(MarketSource):
    def __init__(self, settings: Settings, seconds_per_minute: float = 2.0, history_days: int = 3, seed: int = 11):
        self.settings = settings
        self.market = SyntheticMarket(seed=seed, strike_step=settings.strike_step, lot_size=settings.lot_size)
        self.seconds_per_minute = seconds_per_minute
        start = date.today() - timedelta(days=history_days + 3)
        self._spot, self._fut = self.market.generate(start, history_days + 30)
        self._warm = history_days * 375
        self.mtf, self.fmtf = MultiTimeframe((1, 5, 15)), MultiTimeframe((1,))
        for c, f in zip(self._spot[: self._warm], self._fut[: self._warm]):
            self.mtf.add_one_minute(c)
            self.fmtf.add_one_minute(f)
        self._i = self._warm
        self._minute_event = asyncio.Event()
        self._task: asyncio.Task | None = None
        self._chain: OptionChain | None = None
        self._iv_hist: list[float] = []
        from ..backtest.replay import synthetic_chain_provider
        self._provider = synthetic_chain_provider(self.market, settings.strikes_each_side)

    async def start(self) -> None:
        self._refresh_chain()
        self._task = asyncio.create_task(self._run())

    async def _run(self) -> None:
        while self._i < len(self._spot):
            await asyncio.sleep(self.seconds_per_minute)
            self.mtf.add_one_minute(self._spot[self._i])
            self.fmtf.add_one_minute(self._fut[self._i])
            self._i += 1
            self._refresh_chain()
            self._minute_event.set()

    def _refresh_chain(self) -> None:
        last = self._spot[self._i - 1]
        self._chain = self._provider(self.now(), last.close)
        atm = self._chain.calls.get(self._chain.atm_strike(self.settings.strike_step))
        if atm and atm.iv:
            self._iv_hist.append(atm.iv)

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()

    def now(self) -> datetime:
        return self._spot[self._i - 1].start + timedelta(minutes=1)

    def chain(self) -> OptionChain | None:
        return self._chain

    def snapshot(self) -> MarketSnapshot | None:
        if self._chain is None:
            return None
        return MarketSnapshot(self.now(), self.mtf[1].arrays(), self.mtf[5].arrays(), self._chain, self.fmtf[1].arrays(),
                              iv_history=self._iv_hist[-2000:])


class KiteSource(MarketSource):
    def __init__(self, settings: Settings, kite: KiteClient, recenter_strikes: int = 2):
        self.settings = settings
        self.kite = kite
        self.recenter = recenter_strikes
        self.mtf, self.fmtf = MultiTimeframe((1, 5, 15)), MultiTimeframe((1,))
        self.universe: NiftyUniverse | None = None
        self.expiry: date | None = None
        self.future_token: int | None = None
        self.option_quotes: dict[int, RawQuote] = {}
        self.oi_baseline: dict[int, int] = {}
        self.spot = 0.0
        self.future = 0.0
        self.vix: float | None = None
        self.vix_prev_close: float | None = None
        self.center: float | None = None
        self._chain_tokens: set[int] = set()
        self._iv_hist: list[float] = []
        self._minute_event = asyncio.Event()
        self._ticker: KiteTicker | None = None
        self._task: asyncio.Task | None = None
        self.order_handlers: list[Any] = []

    async def start(self) -> None:
        today = datetime.now(IST).date()
        self.universe = NiftyUniverse(parse_instruments_csv(await self.kite.instruments("NFO")))
        self.settings.lot_size = self.universe.lot_size
        self.expiry = self.universe.nearest_expiry(today)
        fut = self.universe.near_future(today)
        self.future_token = fut.instrument_token if fut else None
        await self._backfill()
        self._ticker = KiteTicker(self.kite.api_key, self.kite.access_token, self._on_ticks, self._on_order)
        base = [NIFTY_INDEX_TOKEN, INDIA_VIX_TOKEN] + ([self.future_token] if self.future_token else [])
        await self._ticker.subscribe(base, MODE_FULL)
        await self._recenter(self.spot)
        self._task = asyncio.create_task(self._ticker.run())

    async def _backfill(self) -> None:
        end = datetime.now(IST)
        start = end - timedelta(days=7)
        for c in await self.kite.historical(NIFTY_INDEX_TOKEN, "minute", start, end):
            self.mtf.add_one_minute(c)
        if self.future_token:
            for c in await self.kite.historical(self.future_token, "minute", start, end, oi=True):
                self.fmtf.add_one_minute(c)
        days = await self.kite.historical(INDIA_VIX_TOKEN, "day", end - timedelta(days=10), end)
        if len(days) >= 2:
            self.vix_prev_close = days[-2].close if days[-1].start.date() == end.date() else days[-1].close
        if len(self.mtf[1].completed):
            self.spot = self.mtf[1].completed[-1].close

    async def _recenter(self, spot: float) -> None:
        if not spot or self.universe is None or self.expiry is None:
            return
        step = self.settings.strike_step
        atm = round(spot / step) * step
        if self.center is not None and abs(atm - self.center) < self.recenter * step:
            return
        self.center = atm
        wanted = {i.instrument_token for i in
                  self.universe.chain_instruments(self.expiry, atm, step, self.settings.strikes_each_side + self.recenter)}
        new, old = wanted - self._chain_tokens, self._chain_tokens - wanted
        if old:
            await self._ticker.unsubscribe(old)
        if new:
            await self._ticker.subscribe(new, MODE_FULL)
        self._chain_tokens = wanted
        log.info("option chain centred at %s (%d contracts)", atm, len(wanted))

    async def _on_ticks(self, ticks: list[dict[str, Any]]) -> None:
        closed_minute = False
        for d in ticks:
            tok = d["instrument_token"]
            t = tick_from_dict(d)
            if tok == NIFTY_INDEX_TOKEN:
                self.spot = t.last_price
                closed_minute |= 1 in self.mtf.on_tick(t)
            elif tok == self.future_token:
                self.future = t.last_price
                self.fmtf.on_tick(t)
            elif tok == INDIA_VIX_TOKEN:
                self.vix = t.last_price
            elif tok in self._chain_tokens:
                self.option_quotes[tok] = RawQuote(tok, t.last_price, t.bid, t.ask, t.volume, t.oi)
                # First OI seen today is the baseline for OI change (Kite quotes carry no previous-day OI).
                self.oi_baseline.setdefault(tok, t.oi)
        if self.spot:
            await self._recenter(self.spot)
        if closed_minute:
            self._minute_event.set()

    async def _on_order(self, data: dict[str, Any]) -> None:
        for h in self.order_handlers:
            await h(data)

    async def stop(self) -> None:
        if self._ticker:
            await self._ticker.stop()
        if self._task:
            self._task.cancel()

    def now(self) -> datetime:
        return datetime.now(IST)

    def chain(self) -> OptionChain | None:
        if not self.spot or self.universe is None or self.expiry is None:
            return None
        insts = [i for i in self.universe.chain_instruments(self.expiry, self.center or self.spot,
                                                            self.settings.strike_step, self.settings.strikes_each_side + self.recenter)]
        fut_inst = self.universe.by_token(self.future_token) if self.future_token else None
        same_expiry_future = self.future if fut_inst and fut_inst.expiry == self.expiry else None
        ch = build_chain("NIFTY", self.spot, same_expiry_future, self.expiry, self.now(), insts, self.option_quotes,
                         self.oi_baseline, self.settings.risk_free_rate, self.settings.dividend_yield)
        atm = ch.calls.get(ch.atm_strike(self.settings.strike_step))
        if atm and atm.iv:
            self._iv_hist.append(atm.iv)
        return ch

    def snapshot(self) -> MarketSnapshot | None:
        ch = self.chain()
        if ch is None or len(self.mtf[1].completed) < 60:
            return None
        return MarketSnapshot(self.now(), self.mtf[1].arrays(), self.mtf[5].arrays(), ch, self.fmtf[1].arrays(),
                              self.vix, self.vix_prev_close, self._iv_hist[-5000:])
