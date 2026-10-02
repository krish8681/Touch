"""Feature extraction: one fixed-order numeric vector per decision point.

Price-distance features are expressed in ATR units so the model sees the
same scale whether NIFTY is at 18,000 or 26,000.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import datetime

import numpy as np

from ..analytics import indicators as ind
from ..analytics.option_flow import BUILDUP_BIAS, ChainAnalysis, classify_buildup
from ..data.candles import CandleArrays

FEATURE_NAMES: tuple[str, ...] = (
    "ret_5", "ret_15", "ret_30", "ret_60",
    "dist_vwap", "dist_ema20", "ema20_50", "ema50_200",
    "adx", "di_diff", "rsi", "macd_hist", "bb_pos", "structure",
    "or_break", "dist_pdh", "dist_pdl",
    "rv_ratio", "atr_pct", "session_frac",
    "pcr_oi", "flow_bias", "iv_skew", "atm_iv", "dist_res", "dist_sup",
    "fut_buildup", "basis_z", "vix_change", "news_impact",
)

SESSION_MINUTES = 375.0


@dataclass
class FeatureSet:
    values: dict[str, float]
    atr_1m: float
    atr_5m: float
    vwap: float
    timestamp: datetime | None = None
    extras: dict[str, float] = field(default_factory=dict)

    def vector(self) -> np.ndarray:
        return np.array([self.values.get(n, 0.0) for n in FEATURE_NAMES], dtype=float)


def _last(a: np.ndarray, default: float = 0.0) -> float:
    if len(a) == 0:
        return default
    v = a[-1]
    return float(v) if np.isfinite(v) else default


def _safe(x: float, default: float = 0.0) -> float:
    return float(x) if x is not None and math.isfinite(x) else default


def _clip(x: float, lim: float = 5.0) -> float:
    return max(-lim, min(lim, x))


def compute_features(
    m1: CandleArrays,
    m5: CandleArrays,
    fut_m1: CandleArrays | None = None,
    chain: ChainAnalysis | None = None,
    vix: float | None = None,
    vix_prev_close: float | None = None,
    news_impact: float = 0.0,
    now: datetime | None = None,
) -> FeatureSet:
    """Requires at least ~60 one-minute candles; returns zeros for unavailable pieces."""
    c = m1.close
    n = len(c)
    if n < 2:
        raise ValueError("need at least two 1-minute candles")
    price = c[-1]

    atr1 = _last(ind.atr(m1.high, m1.low, c, 14), default=max(price * 0.0004, 1.0))
    atr1 = max(atr1, price * 0.00005)

    def ret(k: int) -> float:
        if n <= k:
            return 0.0
        return _clip((price - c[-1 - k]) / (atr1 * math.sqrt(k)))

    # VWAP from futures volume (spot index has none), mapped back onto spot by the basis.
    if fut_m1 is not None and len(fut_m1) > 0 and fut_m1.volume.sum() > 0:
        fvwap = ind.vwap(fut_m1.high, fut_m1.low, fut_m1.close, fut_m1.volume, fut_m1.session)
        vwap = _last(fvwap) - (fut_m1.close[-1] - price)
    else:
        vwap = _last(ind.vwap(m1.high, m1.low, c, m1.volume, m1.session))

    # Trend/momentum block on 5-minute candles.
    c5 = m5.close if len(m5) else c
    h5 = m5.high if len(m5) else m1.high
    l5 = m5.low if len(m5) else m1.low
    atr5 = max(_last(ind.atr(h5, l5, c5, 14), default=atr1 * math.sqrt(5)), atr1)
    e20 = _last(ind.ema(c5, 20), default=price)
    e50 = _last(ind.ema(c5, 50), default=e20)
    e200 = _last(ind.ema(c5, 200), default=e50)
    adx_, pdi, mdi = ind.adx(h5, l5, c5, 14)
    rsi_ = _last(ind.rsi(c5, 14), default=50.0)
    _, _, hist = ind.macd(c5)
    mid, up, _lo = ind.bollinger(c5, 20, 2.0)
    bb_half = _last(up) - _last(mid)
    bb_pos = (price - _last(mid)) / bb_half if bb_half > 0 else 0.0

    # Session context: opening range (first 15 min), previous-day high/low.
    sess = m1.session
    today = sess[-1]
    idx_today = np.flatnonzero(sess == today)
    minutes_in = len(idx_today)
    or_break = 0.0
    if minutes_in > 15:
        orh = m1.high[idx_today[:15]].max()
        orl = m1.low[idx_today[:15]].min()
        if price > orh:
            or_break = (price - orh) / atr5
        elif price < orl:
            or_break = (price - orl) / atr5
    prev = np.flatnonzero(sess == today - 1)
    if len(prev) == 0:
        earlier = sess[sess < today]
        prev = np.flatnonzero(sess == earlier.max()) if len(earlier) else prev
    dist_pdh = (price - m1.high[prev].max()) / atr5 if len(prev) else 0.0
    dist_pdl = (price - m1.low[prev].min()) / atr5 if len(prev) else 0.0

    rv_s = _last(ind.realized_vol(c, 15, 252 * SESSION_MINUTES))
    rv_l = _last(ind.realized_vol(c, 120, 252 * SESSION_MINUTES)) if n > 121 else rv_s
    rv_ratio = rv_s / rv_l if rv_l > 0 else 1.0

    fut_buildup = 0.0
    basis_z = 0.0
    if fut_m1 is not None and len(fut_m1) > 15:
        dp = fut_m1.close[-1] - fut_m1.close[-16]
        doi = fut_m1.oi[-1] - fut_m1.oi[-16]
        fut_buildup = BUILDUP_BIAS[classify_buildup(dp, doi)]
        k = min(60, n, len(fut_m1))
        basis = fut_m1.close[-k:] - c[-k:]
        if len(basis) > 5 and basis.std() > 0:
            basis_z = (basis[-1] - basis.mean()) / basis.std()

    v: dict[str, float] = {
        "ret_5": ret(5), "ret_15": ret(15), "ret_30": ret(30), "ret_60": ret(60),
        "dist_vwap": _clip((price - vwap) / atr5),
        "dist_ema20": _clip((price - e20) / atr5),
        "ema20_50": _clip((e20 - e50) / atr5),
        "ema50_200": _clip((e50 - e200) / atr5),
        "adx": _safe(_last(adx_), 15.0) / 50.0,
        "di_diff": _safe(_last(pdi) - _last(mdi)) / 50.0,
        "rsi": (rsi_ - 50.0) / 50.0,
        "macd_hist": _clip(_safe(_last(hist)) / atr5),
        "bb_pos": _clip(bb_pos, 3),
        "structure": float(ind.swing_structure(h5, l5, 5)),
        "or_break": _clip(or_break),
        "dist_pdh": _clip(dist_pdh, 10),
        "dist_pdl": _clip(dist_pdl, 10),
        "rv_ratio": _clip(rv_ratio, 4),
        "atr_pct": atr5 / price * 100,
        "session_frac": minutes_in / SESSION_MINUTES,
        "fut_buildup": fut_buildup,
        "basis_z": _clip(basis_z, 4),
        "vix_change": (vix / vix_prev_close - 1) * 10 if vix and vix_prev_close else 0.0,
        "news_impact": news_impact,
    }
    if chain is not None:
        v.update(
            pcr_oi=_clip(chain.pcr_oi - 1.0, 2),
            flow_bias=chain.flow_bias,
            iv_skew=_safe(chain.iv_skew) / 5.0,
            atm_iv=_safe(chain.atm_iv) * 10,
            dist_res=_clip(_safe(chain.distance_to_resistance, 10 * atr5) / atr5, 10),
            dist_sup=_clip(_safe(chain.distance_to_support, 10 * atr5) / atr5, 10),
        )
    return FeatureSet(values=v, atr_1m=atr1, atr_5m=atr5, vwap=vwap, timestamp=now,
                      extras={"ema20": e20, "ema50": e50, "ema200": e200, "rsi_raw": rsi_, "adx_raw": _safe(_last(adx_), 0.0),
                              "rv_short": rv_s, "rv_long": rv_l})
