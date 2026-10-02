"""Black-76 pricing, implied volatility and Greeks for NIFTY index options.

NIFTY options are European and cash settled, so pricing off the forward
(the NIFTY future of the same expiry, or spot carried forward) with Black-76
avoids having to estimate the index dividend yield separately.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from ..models import OptionType

_SQRT_2PI = math.sqrt(2 * math.pi)


def norm_cdf(x: float) -> float:
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def norm_pdf(x: float) -> float:
    return math.exp(-0.5 * x * x) / _SQRT_2PI


def forward_from_spot(spot: float, t: float, r: float, q: float) -> float:
    return spot * math.exp((r - q) * t)


def black76_price(forward: float, strike: float, t: float, r: float, vol: float, option_type: OptionType) -> float:
    disc = math.exp(-r * t)
    if t <= 0 or vol <= 0:
        intrinsic = max(forward - strike, 0.0) if option_type == OptionType.CE else max(strike - forward, 0.0)
        return disc * intrinsic
    sd = vol * math.sqrt(t)
    d1 = (math.log(forward / strike) + 0.5 * sd * sd) / sd
    d2 = d1 - sd
    if option_type == OptionType.CE:
        return disc * (forward * norm_cdf(d1) - strike * norm_cdf(d2))
    return disc * (strike * norm_cdf(-d2) - forward * norm_cdf(-d1))


@dataclass
class Greeks:
    price: float
    delta: float     # w.r.t. the forward, ~ spot delta for short-dated index options
    gamma: float
    theta: float     # premium change per calendar day
    vega: float      # premium change per 1 vol point


def black76_greeks(forward: float, strike: float, t: float, r: float, vol: float, option_type: OptionType) -> Greeks:
    price = black76_price(forward, strike, t, r, vol, option_type)
    if t <= 0 or vol <= 0:
        itm = (forward > strike) if option_type == OptionType.CE else (forward < strike)
        delta = (1.0 if itm else 0.0) * (1 if option_type == OptionType.CE else -1)
        return Greeks(price, delta, 0.0, 0.0, 0.0)
    disc = math.exp(-r * t)
    sqrt_t = math.sqrt(t)
    sd = vol * sqrt_t
    d1 = (math.log(forward / strike) + 0.5 * sd * sd) / sd
    pdf = norm_pdf(d1)
    delta = disc * norm_cdf(d1) if option_type == OptionType.CE else -disc * norm_cdf(-d1)
    # Black-76 theta has the same form for calls and puts.
    theta_year = -disc * forward * pdf * vol / (2 * sqrt_t) + r * price
    gamma = disc * pdf / (forward * sd)
    vega = disc * forward * pdf * sqrt_t / 100.0
    return Greeks(price=price, delta=delta, gamma=gamma, theta=theta_year / 365.0, vega=vega)


def implied_vol(
    price: float,
    forward: float,
    strike: float,
    t: float,
    r: float,
    option_type: OptionType,
    lo: float = 0.005,
    hi: float = 3.0,
    tol: float = 1e-6,
    max_iter: int = 100,
) -> float | None:
    """Solve for Black-76 volatility. Returns None when the price is outside no-arbitrage bounds."""
    if price <= 0 or t <= 0:
        return None
    disc = math.exp(-r * t)
    intrinsic = disc * (max(forward - strike, 0.0) if option_type == OptionType.CE else max(strike - forward, 0.0))
    upper = disc * (forward if option_type == OptionType.CE else strike)
    if price < intrinsic - 1e-9 or price >= upper:
        return None
    if price <= intrinsic + 1e-9:
        return lo

    vol = 0.2
    # Newton first (fast near the money), bisection fallback (robust in the wings).
    for _ in range(20):
        g = black76_greeks(forward, strike, t, r, vol, option_type)
        diff = g.price - price
        if abs(diff) < tol:
            return vol
        vega = g.vega * 100.0
        if vega < 1e-8:
            break
        step = diff / vega
        new_vol = vol - step
        if not (lo < new_vol < hi):
            break
        vol = new_vol

    a, b = lo, hi
    fa = black76_price(forward, strike, t, r, a, option_type) - price
    fb = black76_price(forward, strike, t, r, b, option_type) - price
    if fa * fb > 0:
        return None
    for _ in range(max_iter):
        m = 0.5 * (a + b)
        fm = black76_price(forward, strike, t, r, m, option_type) - price
        if abs(fm) < tol or (b - a) < tol:
            return m
        if fa * fm < 0:
            b, fb = m, fm
        else:
            a, fa = m, fm
    return 0.5 * (a + b)


def prob_itm_at_expiry(forward: float, strike: float, t: float, vol: float, option_type: OptionType, drift: float = 0.0) -> float:
    """Probability of finishing in the money under a lognormal model with optional annual drift."""
    if t <= 0 or vol <= 0:
        return 1.0 if (forward > strike) == (option_type == OptionType.CE) else 0.0
    sd = vol * math.sqrt(t)
    d2 = (math.log(forward / strike) + (drift - 0.5 * vol * vol) * t) / sd
    return norm_cdf(d2) if option_type == OptionType.CE else norm_cdf(-d2)


def prob_touch(spot: float, level: float, t: float, vol: float) -> float:
    """Driftless probability that the underlying touches `level` before t (reflection principle)."""
    if t <= 0 or vol <= 0:
        return 1.0 if math.isclose(spot, level) else 0.0
    sd = vol * math.sqrt(t)
    z = abs(math.log(level / spot)) / sd
    return min(1.0, 2.0 * (1.0 - norm_cdf(z)))


def norm_cdf_vec(x: np.ndarray) -> np.ndarray:
    """Vectorised standard normal CDF (Abramowitz–Stegun 7.1.26 erf, |error| < 1.5e-7)."""
    z = np.abs(x) / math.sqrt(2.0)
    t = 1.0 / (1.0 + 0.3275911 * z)
    poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    erf = 1.0 - poly * np.exp(-z * z)
    return 0.5 * (1.0 + np.sign(x) * erf)


def black76_price_vec(forward: np.ndarray, strike: float, t: float, r: float, vol: float, option_type: OptionType) -> np.ndarray:
    """Black-76 price for an array of forwards (used to revalue positions across a price distribution)."""
    forward = np.asarray(forward, dtype=float)
    disc = math.exp(-r * t)
    if t <= 0 or vol <= 0:
        intr = np.maximum(forward - strike, 0.0) if option_type == OptionType.CE else np.maximum(strike - forward, 0.0)
        return disc * intr
    sd = vol * math.sqrt(t)
    d1 = (np.log(forward / strike) + 0.5 * sd * sd) / sd
    d2 = d1 - sd
    if option_type == OptionType.CE:
        return disc * (forward * norm_cdf_vec(d1) - strike * norm_cdf_vec(d2))
    return disc * (strike * norm_cdf_vec(-d2) - forward * norm_cdf_vec(-d1))
