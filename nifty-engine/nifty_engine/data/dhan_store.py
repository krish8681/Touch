"""Read downloaded Dhan data and serve it to the replay/training code.

* ``DhanStore.index_candles`` — NIFTY 1-minute spot candles (IST).
* ``DhanChainProvider`` — rebuilds the option chain for any minute from the
  rolling-strike bars, in the same ``OptionChain`` form the live engine uses.

Dhan's rolling data has no contract expiry date and no bid/ask:

* Expiry is resolved per trading day. Candidates come from the weekly expiry
  weekday (Thursday until 31 Aug 2025, Tuesday from 1 Sep 2025), moved to the
  previous trading day when the exchange was closed. If Dhan supplied IV, the
  candidate whose Black-76 IV best matches Dhan's IV for the ATM options that
  day is chosen. This also settles whether ``expiryCode`` meant the near or the
  next expiry.
* Bid/ask is modelled as a symmetric spread around the bar close (``spread_bps``),
  floored at one tick. Keep it pessimistic.

Volume is accumulated through the day per contract (Kite ticks report cumulative
day volume, and the risk engine's liquidity filter expects that), and OI change is
measured from the first OI seen in the session, exactly like the live source.
"""

from __future__ import annotations

import bisect
import logging
import math
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from pathlib import Path
from statistics import median

from ..analytics.greeks import forward_from_spot, implied_vol
from ..models import Candle, Instrument, OptionChain, OptionType
from .chain_builder import RawQuote, build_chain, time_to_expiry
from .dhan import parse_index_rows, read_csv_gz
from .kite import IST

log = logging.getLogger(__name__)

# (first day, weekday) of NIFTY weekly expiry regimes; Monday=0 … Thursday=3.
WEEKLY_EXPIRY_SCHEDULE: list[tuple[date, int]] = [(date(2000, 1, 1), 3), (date(2025, 9, 1), 1)]
SESSION_OPEN, SESSION_CLOSE = time(9, 15), time(15, 30)
STALE_MINUTES = 5


def expiry_weekday(d: date) -> int:
    wd = WEEKLY_EXPIRY_SCHEDULE[0][1]
    for start, w in WEEKLY_EXPIRY_SCHEDULE:
        if d >= start:
            wd = w
    return wd


def _parse_range(name: str) -> tuple[date, date]:
    a, b = name.removesuffix(".csv.gz").split("_")
    return date.fromisoformat(a), date.fromisoformat(b)


def _f(x: str | None) -> float | None:
    if x in (None, "", "None"):
        return None
    try:
        v = float(x)
    except ValueError:
        return None
    return v if math.isfinite(v) else None


@dataclass
class OptBar:
    ts: datetime
    option_type: OptionType
    offset: int
    strike: float
    close: float
    volume: float
    oi: float
    iv: float | None
    spot: float | None


@dataclass
class DayData:
    day: date
    bars: dict[datetime, list[OptBar]] = field(default_factory=dict)
    minutes: list[datetime] = field(default_factory=list)


@dataclass
class ExpiryResolution:
    day: date
    expiry: date
    method: str                 # "iv-match" | "calendar"
    iv_error: float | None = None
    iv_scale: float | None = None


class DhanStore:
    def __init__(self, root: str | Path, underlying: str = "NIFTY", series: str = "WEEK1_1m"):
        self.base = Path(root) / underlying
        self.series_dir = self.base / "options" / series
        self._files = sorted(self.series_dir.glob("*.csv.gz")) if self.series_dir.exists() else []
        self._ranges = [_parse_range(p.name) for p in self._files]
        self._loaded_file: Path | None = None
        self._days: dict[date, DayData] = {}
        self._trading_days: list[date] | None = None
        self._day_set: set[date] | None = None
        self.resolutions: dict[date, ExpiryResolution] = {}
        self.iv_scale: float | None = None

    # ── index ──────────────────────────────────────────────────────────
    def index_candles(self, start: date | None = None, end: date | None = None) -> list[Candle]:
        out: list[Candle] = []
        seen: set[int] = set()
        for p in sorted((self.base / "index").glob("*.csv.gz")):
            a, b = _parse_range(p.name)
            if (end and a >= end) or (start and b <= start):
                continue
            for ts, o, h, l, c in parse_index_rows(read_csv_gz(p)):
                if ts in seen:
                    continue
                t = datetime.fromtimestamp(ts, IST).replace(second=0, microsecond=0)
                if not (SESSION_OPEN <= t.time() < SESSION_CLOSE):
                    continue
                if (start and t.date() < start) or (end and t.date() >= end):
                    continue
                seen.add(ts)
                out.append(Candle(t, o, h, l, c, 0, 0))
        out.sort(key=lambda c: c.start)
        self._trading_days = sorted({c.start.date() for c in out})
        self._day_set = None
        return out

    @property
    def trading_days(self) -> list[date]:
        if self._trading_days is None:
            self.index_candles()
        return self._trading_days or []

    # ── options ────────────────────────────────────────────────────────
    def _file_for(self, d: date) -> Path | None:
        for p, (a, b) in zip(self._files, self._ranges):
            if a <= d < b:
                return p
        return None

    def day(self, d: date) -> DayData | None:
        if d in self._days:
            return self._days[d]
        p = self._file_for(d)
        if p is None:
            return None
        if p != self._loaded_file:
            self._load_file(p)
        return self._days.get(d)

    def _load_file(self, p: Path) -> None:
        days: dict[date, DayData] = {}
        for r in read_csv_gz(p):
            close, strike = _f(r["close"]), _f(r["strike"])
            if not close or not strike or close <= 0:
                continue
            t = datetime.fromtimestamp(int(r["ts"]), IST).replace(second=0, microsecond=0)
            dd = days.setdefault(t.date(), DayData(t.date()))
            dd.bars.setdefault(t, []).append(OptBar(
                t, OptionType(r["type"]), int(r["offset"]), strike, close, _f(r["volume"]) or 0.0, _f(r["oi"]) or 0.0,
                _f(r["iv"]), _f(r["spot"])))
        for dd in days.values():
            dd.minutes = sorted(dd.bars)
        self._days = days
        self._loaded_file = p

    # ── expiry ─────────────────────────────────────────────────────────
    def _shift_for_holiday(self, nominal: date, not_before: date) -> date:
        """Move an expiry that falls on an exchange holiday to the previous trading day."""
        days = self.trading_days
        if not days or nominal > days[-1]:
            return nominal          # beyond the data we hold: holidays unknown
        if self._day_set is None:
            self._day_set = set(days)
        d = nominal
        while d >= not_before and d not in self._day_set:
            d -= timedelta(days=1)
        return d if d >= not_before else nominal

    def expiry_candidates(self, d: date, n: int = 3) -> list[date]:
        """The next `n` weekly expiries on or after `d`, honouring the weekday schedule and holidays."""
        out: list[date] = []
        x = d
        while len(out) < n:
            if x.weekday() == expiry_weekday(x):
                e = self._shift_for_holiday(x, d)
                if e not in out:
                    out.append(e)
            x += timedelta(days=1)
        return out

    def resolve_expiry(self, d: date, r: float = 0.065, q: float = 0.012) -> ExpiryResolution:
        if d in self.resolutions:
            return self.resolutions[d]
        cands = self.expiry_candidates(d)
        dd = self.day(d)
        samples: list[tuple[datetime, float, float, OptionType, float, float]] = []   # ts, spot, strike, type, price, dhan iv
        if dd:
            for t in dd.minutes[30::15]:                      # every 15 minutes after the open
                for b in dd.bars[t]:
                    if b.offset == 0 and b.iv and b.spot and b.close > 0.5:
                        samples.append((t, b.spot, b.strike, b.option_type, b.close, b.iv))
        if not samples:
            res = ExpiryResolution(d, cands[0], "calendar")
            self.resolutions[d] = res
            return res
        best: tuple[float, date, float] | None = None
        for e in cands:
            for scale in ((self.iv_scale,) if self.iv_scale else (1.0, 0.01)):
                errs = []
                for t, spot, k, ot, px, div in samples:
                    tt = time_to_expiry(t + timedelta(minutes=1), e)
                    iv = implied_vol(px, forward_from_spot(spot, tt, r, q), k, tt, r, ot)
                    if iv:
                        errs.append(abs(iv - div * scale))
                if len(errs) >= max(2, len(samples) // 3):
                    err = median(errs)
                    if best is None or err < best[0]:
                        best = (err, e, scale)
        if best is None:
            res = ExpiryResolution(d, cands[0], "calendar")
        else:
            res = ExpiryResolution(d, best[1], "iv-match", best[0], best[2])
            if self.iv_scale is None:
                self.iv_scale = best[2]
        self.resolutions[d] = res
        return res


class DhanChainProvider:
    """ChainProvider (``(now, spot) -> OptionChain | None``) backed by a DhanStore."""

    def __init__(self, store: DhanStore, spread_bps: float = 50.0, r: float = 0.065, q: float = 0.012):
        self.store = store
        self.spread_bps = spread_bps
        self.r, self.q = r, q
        self._day: date | None = None
        self._cum_vol: dict[tuple, float] = {}
        self._oi_base: dict[int, int] = {}
        self._last_minute_seen: dict[tuple, datetime] = {}
        self._latest: dict[tuple, OptBar] = {}
        self._consumed_upto: datetime | None = None
        self._instruments: dict[tuple, Instrument] = {}

    def _instrument(self, expiry: date, strike: float, ot: OptionType) -> Instrument:
        key = (expiry, strike, ot)
        inst = self._instruments.get(key)
        if inst is None:
            token = int(expiry.strftime("%y%m%d")) * 1_000_000 + int(strike) * 10 + (0 if ot == OptionType.CE else 1)
            sym = f"NIFTY{expiry.strftime('%d%b%y').upper()}{int(strike)}{ot.value}"
            inst = Instrument(token, sym, "NIFTY", "NFO", "NFO-OPT", ot.value, expiry, strike, 1, 0.05)
            self._instruments[key] = inst
        return inst

    def _reset_day(self, d: date) -> None:
        self._day = d
        self._cum_vol.clear()
        self._oi_base.clear()
        self._latest.clear()
        self._last_minute_seen.clear()
        self._consumed_upto = None

    def __call__(self, now: datetime, spot: float) -> OptionChain | None:
        d = now.date()
        dd = self.store.day(d)
        if dd is None or not dd.minutes:
            return None
        if d != self._day:
            self._reset_day(d)
        bar_time = now.replace(second=0, microsecond=0) - timedelta(minutes=1)   # last completed bar
        # Consume every bar up to bar_time (in order) so cumulative volume and staleness are correct.
        i = 0 if self._consumed_upto is None else bisect.bisect_right(dd.minutes, self._consumed_upto)
        j = bisect.bisect_right(dd.minutes, bar_time)
        for t in dd.minutes[i:j]:
            for b in dd.bars[t]:
                key = (b.strike, b.option_type)
                self._cum_vol[key] = self._cum_vol.get(key, 0.0) + b.volume
                self._latest[key] = b
        if j > i:
            self._consumed_upto = dd.minutes[j - 1]
        if not self._latest:
            return None

        expiry = self.store.resolve_expiry(d, self.r, self.q).expiry
        instruments, quotes = [], {}
        for (strike, ot), b in self._latest.items():
            if (bar_time - b.ts) > timedelta(minutes=STALE_MINUTES):
                continue
            inst = self._instrument(expiry, strike, ot)
            half = max(0.05, b.close * self.spread_bps / 2 / 10_000)
            oi = int(b.oi)
            self._oi_base.setdefault(inst.instrument_token, oi)
            instruments.append(inst)
            quotes[inst.instrument_token] = RawQuote(inst.instrument_token, b.close, max(0.05, round(b.close - half, 2)),
                                                     round(b.close + half, 2), int(self._cum_vol[(strike, ot)]), oi)
        if not quotes:
            return None
        return build_chain("NIFTY", spot, None, expiry, now, instruments, quotes, self._oi_base, self.r, self.q)


def coverage_report(store: DhanStore, start: date | None = None, end: date | None = None,
                    expected_series: int = 42) -> list[dict]:
    """Per-day completeness: share of (minute × series) cells present vs a full 375-minute session."""
    out = []
    for d in store.trading_days:
        if (start and d < start) or (end and d >= end):
            continue
        dd = store.day(d)
        if dd is None:
            out.append({"day": d.isoformat(), "coverage": 0.0, "minutes": 0})
            continue
        cells = sum(len(v) for v in dd.bars.values())
        per_series = defaultdict(int)
        for v in dd.bars.values():
            for b in v:
                per_series[(b.offset, b.option_type)] += 1
        res = store.resolve_expiry(d)
        out.append({"day": d.isoformat(), "minutes": len(dd.minutes), "coverage": round(cells / (375 * expected_series), 4),
                    "series": len(per_series), "expiry": res.expiry.isoformat(), "expiry_method": res.method,
                    "iv_error": None if res.iv_error is None else round(res.iv_error, 4)})
    return out
