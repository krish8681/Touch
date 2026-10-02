"""Expected NIFTY move per horizon, and the forecast-consistent price distribution.

Volatility is a blend of implied (ATM IV), realized (recent 1-minute
returns) and ATR-based estimates, scaled to the horizon in trading minutes.

The terminal distribution reweights a driftless normal so that the mass in
the UP / SIDEWAYS / DOWN regions equals the model's probabilities, using the
same band that defined the training labels. This keeps option valuation
consistent with the direction model instead of inventing a separate drift.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, time

import numpy as np

from ..models import DirectionForecast, ExpectedMove
from .direction import label_threshold

TRADING_MINUTES_PER_YEAR = 252 * 375
MINUTES_PER_YEAR_CAL = 365 * 24 * 60   # option time-to-expiry convention
SESSION_CLOSE = time(15, 30)


def minutes_to_close(now: datetime) -> int:
    close = datetime.combine(now.date(), SESSION_CLOSE, tzinfo=now.tzinfo)
    return max(1, int((close - now).total_seconds() // 60))


def effective_horizon(horizon_minutes: int, now: datetime) -> int:
    """Horizon 0 means end of day; any horizon is capped at the session close."""
    mtc = minutes_to_close(now)
    return mtc if horizon_minutes == 0 else min(horizon_minutes, mtc)


def blended_vol(atm_iv: float | None, realized_vol: float | None, atr_1m: float, spot: float,
                w_iv: float = 0.4, w_rv: float = 0.4, w_atr: float = 0.2) -> float:
    """Annualised volatility (trading-minute convention)."""
    atr_vol = atr_1m / spot * math.sqrt(TRADING_MINUTES_PER_YEAR) * 0.8  # ATR ≈ 1.25 σ for normal bars
    parts = [(w_atr, atr_vol)]
    if atm_iv and atm_iv > 0:
        parts.append((w_iv, atm_iv))
    if realized_vol and realized_vol > 0 and math.isfinite(realized_vol):
        parts.append((w_rv, realized_vol))
    wsum = sum(w for w, _ in parts)
    return sum(w * v for w, v in parts) / wsum


def sigma_points(spot: float, vol: float, horizon_minutes: int) -> float:
    return spot * vol * math.sqrt(horizon_minutes / TRADING_MINUTES_PER_YEAR)


def expected_move(spot: float, vol: float, forecast: DirectionForecast, now: datetime) -> ExpectedMove:
    h = effective_horizon(forecast.horizon_minutes, now)
    sd = sigma_points(spot, vol, h)
    tilt = (forecast.up - forecast.down) * 0.8 * sd     # 0.8 ≈ E|Z| for a unit normal
    return ExpectedMove(forecast.horizon_minutes, sd, up_points=max(0.0, sd + tilt), down_points=max(0.0, sd - tilt))


@dataclass
class PriceDistribution:
    """Discrete distribution of NIFTY at the horizon (points and probabilities)."""
    prices: np.ndarray
    weights: np.ndarray
    horizon_minutes: int
    sigma: float

    def expect(self, values: np.ndarray) -> float:
        return float(np.dot(self.weights, values))

    def prob(self, mask: np.ndarray) -> float:
        return float(self.weights[mask].sum())


def forecast_distribution(spot: float, vol: float, forecast: DirectionForecast, atr_1m: float, now: datetime,
                          n: int = 241, width: float = 5.0) -> PriceDistribution:
    h = effective_horizon(forecast.horizon_minutes, now)
    sd = max(sigma_points(spot, vol, h), 1e-6)
    z = np.linspace(-width, width, n)
    moves = z * sd
    base = np.exp(-0.5 * z * z)
    base /= base.sum()
    thr = label_threshold(atr_1m, h)
    up_mask, down_mask = moves > thr, moves < -thr
    side_mask = ~(up_mask | down_mask)
    w = np.zeros(n)
    for mask, p in ((up_mask, forecast.up), (side_mask, forecast.sideways), (down_mask, forecast.down)):
        mass = base[mask].sum()
        if mass > 0:
            w[mask] = base[mask] * p / mass
    w /= w.sum()
    return PriceDistribution(spot + moves, w, h, sd)
