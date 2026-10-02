"""Tick → multi-timeframe candle aggregation and candle series storage."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from datetime import datetime, timedelta

import numpy as np

from ..models import Candle, Tick

TIMEFRAMES = (1, 3, 5, 15)

# NSE session start; candles align to 09:15 rather than the top of the hour.
SESSION_OPEN = (9, 15)


def bucket_start(ts: datetime, minutes: int) -> datetime:
    open_ = ts.replace(hour=SESSION_OPEN[0], minute=SESSION_OPEN[1], second=0, microsecond=0)
    if ts < open_:
        return ts.replace(second=0, microsecond=0)
    elapsed = int((ts - open_).total_seconds() // 60)
    return open_ + timedelta(minutes=(elapsed // minutes) * minutes)


@dataclass
class CandleArrays:
    time: np.ndarray
    open: np.ndarray
    high: np.ndarray
    low: np.ndarray
    close: np.ndarray
    volume: np.ndarray
    oi: np.ndarray
    session: np.ndarray        # integer day id, for session VWAP / opening range

    def __len__(self) -> int:
        return len(self.close)


class CandleSeries:
    """Bounded series of completed candles plus the one currently forming."""

    def __init__(self, minutes: int, maxlen: int = 2000):
        self.minutes = minutes
        self.completed: deque[Candle] = deque(maxlen=maxlen)
        self.current: Candle | None = None
        self._last_cum_volume: int | None = None

    def add_candle(self, candle: Candle) -> None:
        """Append an already-built candle (historical backfill or replay)."""
        if self.current is not None:
            self.completed.append(self.current)
            self.current = None
        self.completed.append(candle)

    def on_tick(self, tick: Tick) -> Candle | None:
        """Update with a tick. Returns the candle that just closed, if any.

        Kite reports cumulative day volume, so per-candle volume is the delta.
        """
        start = bucket_start(tick.timestamp, self.minutes)
        vol_delta = 0
        if tick.volume:
            if self._last_cum_volume is not None and tick.volume >= self._last_cum_volume:
                vol_delta = tick.volume - self._last_cum_volume
            self._last_cum_volume = tick.volume
        closed = None
        if self.current is not None and start > self.current.start:
            closed = self.current
            self.completed.append(closed)
            self.current = None
        if self.current is None:
            self.current = Candle(start, tick.last_price, tick.last_price, tick.last_price, tick.last_price, vol_delta, tick.oi)
        else:
            c = self.current
            c.high = max(c.high, tick.last_price)
            c.low = min(c.low, tick.last_price)
            c.close = tick.last_price
            c.volume += vol_delta
            if tick.oi:
                c.oi = tick.oi
        return closed

    def candles(self, include_current: bool = False) -> list[Candle]:
        out = list(self.completed)
        if include_current and self.current is not None:
            out.append(self.current)
        return out

    def arrays(self, include_current: bool = False) -> CandleArrays:
        cs = self.candles(include_current)
        day0 = cs[0].start.date() if cs else None
        return CandleArrays(
            time=np.array([c.start for c in cs], dtype=object),
            open=np.array([c.open for c in cs], dtype=float),
            high=np.array([c.high for c in cs], dtype=float),
            low=np.array([c.low for c in cs], dtype=float),
            close=np.array([c.close for c in cs], dtype=float),
            volume=np.array([c.volume for c in cs], dtype=float),
            oi=np.array([c.oi for c in cs], dtype=float),
            session=np.array([(c.start.date() - day0).days for c in cs], dtype=int) if cs else np.array([], dtype=int),
        )


class MultiTimeframe:
    def __init__(self, timeframes: tuple[int, ...] = TIMEFRAMES, maxlen: int = 2000):
        self.series = {tf: CandleSeries(tf, maxlen) for tf in timeframes}

    def on_tick(self, tick: Tick) -> dict[int, Candle]:
        closed = {}
        for tf, s in self.series.items():
            c = s.on_tick(tick)
            if c is not None:
                closed[tf] = c
        return closed

    def add_one_minute(self, candle: Candle) -> None:
        """Feed a completed 1-minute candle and roll it up into higher timeframes."""
        for tf, s in self.series.items():
            if tf == 1:
                s.add_candle(candle)
                continue
            start = bucket_start(candle.start, tf)
            if s.current is not None and s.current.start != start:
                s.completed.append(s.current)
                s.current = None
            if s.current is None:
                s.current = Candle(start, candle.open, candle.high, candle.low, candle.close, candle.volume, candle.oi)
            else:
                c = s.current
                c.high = max(c.high, candle.high)
                c.low = min(c.low, candle.low)
                c.close = candle.close
                c.volume += candle.volume
                c.oi = candle.oi or c.oi

    def __getitem__(self, tf: int) -> CandleSeries:
        return self.series[tf]
