"""Vectorised technical indicators on numpy arrays.

Every function returns an array aligned with its input; positions without
enough history are NaN.
"""

from __future__ import annotations

import numpy as np


def ema(values: np.ndarray, period: int) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    out = np.full_like(values, np.nan)
    if len(values) < period:
        return out
    alpha = 2.0 / (period + 1)
    out[period - 1] = values[:period].mean()
    for i in range(period, len(values)):
        out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
    return out


def wilder(values: np.ndarray, period: int) -> np.ndarray:
    """Wilder's smoothing (RMA), used by RSI/ATR/ADX."""
    values = np.asarray(values, dtype=float)
    out = np.full_like(values, np.nan)
    valid = ~np.isnan(values)
    idx = np.flatnonzero(valid)
    if len(idx) < period:
        return out
    start = idx[0]
    first = start + period - 1
    out[first] = values[start : first + 1].mean()
    for i in range(first + 1, len(values)):
        out[i] = (out[i - 1] * (period - 1) + values[i]) / period
    return out


def rsi(close: np.ndarray, period: int = 14) -> np.ndarray:
    close = np.asarray(close, dtype=float)
    delta = np.diff(close, prepend=np.nan)
    gain = np.where(delta > 0, delta, 0.0)
    loss = np.where(delta < 0, -delta, 0.0)
    gain[0] = loss[0] = np.nan
    avg_gain = wilder(gain, period)
    avg_loss = wilder(loss, period)
    with np.errstate(divide="ignore", invalid="ignore"):
        rs = avg_gain / avg_loss
        out = 100 - 100 / (1 + rs)
    out = np.where((avg_loss == 0) & ~np.isnan(avg_gain), 100.0, out)
    return out


def true_range(high: np.ndarray, low: np.ndarray, close: np.ndarray) -> np.ndarray:
    high, low, close = (np.asarray(a, dtype=float) for a in (high, low, close))
    prev_close = np.roll(close, 1)
    tr = np.maximum(high - low, np.maximum(np.abs(high - prev_close), np.abs(low - prev_close)))
    tr[0] = high[0] - low[0]
    return tr


def atr(high: np.ndarray, low: np.ndarray, close: np.ndarray, period: int = 14) -> np.ndarray:
    return wilder(true_range(high, low, close), period)


def adx(high: np.ndarray, low: np.ndarray, close: np.ndarray, period: int = 14) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Returns (adx, +DI, -DI)."""
    high, low, close = (np.asarray(a, dtype=float) for a in (high, low, close))
    up = np.diff(high, prepend=np.nan)
    down = -np.diff(low, prepend=np.nan)
    plus_dm = np.where((up > down) & (up > 0), up, 0.0)
    minus_dm = np.where((down > up) & (down > 0), down, 0.0)
    plus_dm[0] = minus_dm[0] = np.nan
    tr = true_range(high, low, close)
    tr[0] = np.nan
    atr_ = wilder(tr, period)
    with np.errstate(divide="ignore", invalid="ignore"):
        plus_di = 100 * wilder(plus_dm, period) / atr_
        minus_di = 100 * wilder(minus_dm, period) / atr_
        dx = 100 * np.abs(plus_di - minus_di) / (plus_di + minus_di)
    dx = np.where(np.isfinite(dx), dx, np.nan)
    return wilder(dx, period), plus_di, minus_di


def macd(close: np.ndarray, fast: int = 12, slow: int = 26, signal: int = 9) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Returns (macd line, signal line, histogram)."""
    line = ema(close, fast) - ema(close, slow)
    sig = np.full_like(line, np.nan)
    valid = np.flatnonzero(~np.isnan(line))
    if len(valid) >= signal:
        sig[valid[0]:] = ema(line[valid[0]:], signal)
    return line, sig, line - sig


def _rolling_mean_std(x: np.ndarray, window: int, ddof: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """Rolling mean/std via cumulative sums (NaN-free input), aligned to the window's last bar."""
    x = np.asarray(x, dtype=float)
    mean = np.full_like(x, np.nan)
    std = np.full_like(x, np.nan)
    if len(x) < window:
        return mean, std
    c1 = np.concatenate([[0.0], np.cumsum(x)])
    c2 = np.concatenate([[0.0], np.cumsum(x * x)])
    s1 = c1[window:] - c1[:-window]
    s2 = c2[window:] - c2[:-window]
    m = s1 / window
    var = np.maximum((s2 - window * m * m) / (window - ddof), 0.0)
    mean[window - 1 :] = m
    std[window - 1 :] = np.sqrt(var)
    return mean, std


def bollinger(close: np.ndarray, period: int = 20, width: float = 2.0) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Returns (middle, upper, lower)."""
    mid, sd = _rolling_mean_std(close, period)
    return mid, mid + width * sd, mid - width * sd


def vwap(high: np.ndarray, low: np.ndarray, close: np.ndarray, volume: np.ndarray, session_ids: np.ndarray | None = None) -> np.ndarray:
    """Session VWAP. NIFTY spot has no volume, so callers should pass futures volume.

    When volume is all zero the typical-price running mean is returned instead.
    """
    typical = (np.asarray(high, float) + np.asarray(low, float) + np.asarray(close, float)) / 3
    volume = np.asarray(volume, dtype=float)
    if session_ids is None:
        session_ids = np.zeros(len(typical), dtype=int)
    out = np.empty_like(typical)
    pv = vol = cnt = tp_sum = 0.0
    prev = None
    for i, sid in enumerate(session_ids):
        if sid != prev:
            pv = vol = cnt = tp_sum = 0.0
            prev = sid
        pv += typical[i] * volume[i]
        vol += volume[i]
        cnt += 1
        tp_sum += typical[i]
        out[i] = pv / vol if vol > 0 else tp_sum / cnt
    return out


def realized_vol(close: np.ndarray, window: int, bars_per_year: float) -> np.ndarray:
    """Annualised close-to-close volatility over a rolling window of bars."""
    close = np.asarray(close, dtype=float)
    out = np.full_like(close, np.nan)
    if len(close) <= window:
        return out
    rets = np.diff(np.log(close))
    _, sd = _rolling_mean_std(rets, window, ddof=1)
    out[1:] = sd * np.sqrt(bars_per_year)
    return out


def swing_structure(high: np.ndarray, low: np.ndarray, lookback: int = 5) -> int:
    """+1 for higher-high & higher-low, -1 for lower-high & lower-low, 0 otherwise.

    Compares the last `lookback` bars with the `lookback` bars before them.
    """
    high = np.asarray(high, dtype=float)
    low = np.asarray(low, dtype=float)
    if len(high) < 2 * lookback:
        return 0
    recent_h, prior_h = high[-lookback:].max(), high[-2 * lookback : -lookback].max()
    recent_l, prior_l = low[-lookback:].min(), low[-2 * lookback : -lookback].min()
    if recent_h > prior_h and recent_l > prior_l:
        return 1
    if recent_h < prior_h and recent_l < prior_l:
        return -1
    return 0
