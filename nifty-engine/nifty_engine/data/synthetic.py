"""Synthetic NIFTY market for demos, tests and engine development without Kite access.

Generates regime-switching 1-minute index candles, a futures leg with basis
and volume, and option chains priced off a volatility smile with realistic
bid/ask spreads and OI. It is NOT a substitute for real historical option
data when judging strategy profitability.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta

import numpy as np

from ..analytics.greeks import black76_price, forward_from_spot
from ..models import Candle, Instrument, OptionType
from .chain_builder import RawQuote, time_to_expiry
from .kite import IST

SESSION_MINUTES = 375  # 09:15 → 15:30


@dataclass
class SyntheticRegime:
    drift_per_min: float      # fractional drift per minute
    vol_annual: float
    name: str


REGIMES = [
    SyntheticRegime(0.00006, 0.11, "trend_up"),
    SyntheticRegime(-0.00006, 0.13, "trend_down"),
    SyntheticRegime(0.0, 0.09, "range"),
    SyntheticRegime(0.0, 0.22, "volatile"),
]


@dataclass
class SyntheticMarket:
    seed: int = 7
    start_price: float = 25_000.0
    base_iv: float = 0.13
    strike_step: int = 50
    lot_size: int = 65
    r: float = 0.065
    q: float = 0.012
    rng: np.random.Generator = field(init=False)
    regime_log: list[tuple[datetime, str]] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.rng = np.random.default_rng(self.seed)

    # ── underlying ──────────────────────────────────────────────────────
    def trading_days(self, start: date, n: int) -> list[date]:
        days, d = [], start
        while len(days) < n:
            if d.weekday() < 5:
                days.append(d)
            d += timedelta(days=1)
        return days

    def session(self, day: date, open_price: float) -> tuple[list[Candle], list[Candle]]:
        """Returns (spot 1m candles, futures 1m candles) for one session."""
        spot_c, fut_c = [], []
        price = open_price
        regime = REGIMES[self.rng.integers(len(REGIMES))]
        t0 = datetime.combine(day, time(9, 15), tzinfo=IST)
        self.regime_log.append((t0, regime.name))
        expiry = self.weekly_expiry(day)
        for m in range(SESSION_MINUTES):
            if m and self.rng.random() < 1 / 90:  # regimes persist ~90 minutes on average
                regime = REGIMES[self.rng.integers(len(REGIMES))]
                self.regime_log.append((t0 + timedelta(minutes=m), regime.name))
            sigma = regime.vol_annual / math.sqrt(252 * SESSION_MINUTES)
            # U-shaped intraday volatility
            u = 1.0 + 0.8 * math.exp(-m / 25) + 0.4 * math.exp(-(SESSION_MINUTES - m) / 30)
            path = [price]
            for _ in range(4):
                path.append(path[-1] * math.exp(regime.drift_per_min / 4 + sigma * u * self.rng.standard_normal() / 2))
            o, c = path[0], path[-1]
            h, lo = max(path), min(path)
            ts = t0 + timedelta(minutes=m)
            spot_c.append(Candle(ts, o, h, lo, c, 0, 0))
            t = time_to_expiry(ts, expiry)
            basis = math.exp((self.r - self.q) * max(t, 7 / 365))
            vol = float(self.rng.gamma(4, 2500) * u * (1 + 3 * abs(c / o - 1) * 100))
            fut_c.append(Candle(ts, o * basis, h * basis, lo * basis, c * basis, vol, 1.2e7))
            price = c
        return spot_c, fut_c

    def generate(self, start: date, days: int) -> tuple[list[Candle], list[Candle]]:
        spot, fut = [], []
        price = self.start_price
        for d in self.trading_days(start, days):
            gap = price * self.rng.normal(0, 0.003)
            s, f = self.session(d, price + gap)
            spot += s
            fut += f
            price = s[-1].close
        return spot, fut

    # ── options ─────────────────────────────────────────────────────────
    @staticmethod
    def weekly_expiry(day: date) -> date:
        """Next Tuesday on or after `day` (NIFTY weeklies expire on Tuesday)."""
        return day + timedelta(days=(1 - day.weekday()) % 7)

    def smile_iv(self, spot: float, strike: float, t: float, atm_iv: float) -> float:
        m = math.log(strike / spot) / max(math.sqrt(t), 1e-3)
        return max(0.04, atm_iv * (1 - 0.35 * m + 0.9 * m * m))

    def instruments(self, expiry: date, atm: float, each_side: int = 15) -> list[Instrument]:
        out = []
        base = 10_000_000 + int(expiry.strftime("%y%m%d")) * 1000
        for i in range(-each_side, each_side + 1):
            k = atm + i * self.strike_step
            for j, ot in enumerate((OptionType.CE, OptionType.PE)):
                token = base + (i + each_side) * 2 + j
                sym = f"NIFTY{expiry.strftime('%y%b').upper()}{int(k)}{ot.value}"
                out.append(Instrument(token, sym, "NIFTY", "NFO", "NFO-OPT", ot.value, expiry, k, self.lot_size, 0.05))
        return out

    def quotes(self, instruments: list[Instrument], spot: float, now: datetime, atm_iv: float | None = None,
               minutes_into_session: int = 200) -> dict[int, RawQuote]:
        atm_iv = atm_iv or self.base_iv
        out = {}
        for inst in instruments:
            t = time_to_expiry(now, inst.expiry)
            fwd = forward_from_spot(spot, t, self.r, self.q)
            iv = self.smile_iv(spot, inst.strike, t, atm_iv)
            ot = OptionType(inst.instrument_type)
            fair = black76_price(fwd, inst.strike, t, self.r, iv, ot)
            fair = max(fair, 0.05)
            moneyness = abs(inst.strike - spot) / self.strike_step
            half_spread = max(0.05, fair * (0.004 + 0.002 * moneyness))
            bid = max(0.05, round((fair - half_spread) * 20) / 20)
            ask = round((fair + half_spread) * 20) / 20
            ltp = round((fair + self.rng.normal(0, half_spread / 2)) * 20) / 20
            oi_scale = math.exp(-0.5 * (moneyness / 6) ** 2)
            # Round-number strikes get extra writing interest.
            if inst.strike % 500 == 0:
                oi_scale *= 1.6
            oi = int(self.rng.uniform(0.7, 1.3) * 9e6 * oi_scale) // self.lot_size * self.lot_size
            volume = int(oi * self.rng.uniform(0.5, 3.0) * min(1.0, minutes_into_session / 120))
            out[inst.instrument_token] = RawQuote(inst.instrument_token, max(ltp, 0.05), bid, ask, volume, oi)
        return out
