"""Construct an option chain (with IV and Greeks) from raw per-contract quotes.

Kite does not publish an option chain; it has to be assembled from the
instrument dump plus quotes/ticks for each strike.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, datetime, time

from ..analytics.greeks import black76_greeks, forward_from_spot, implied_vol
from ..models import Instrument, OptionChain, OptionQuote, OptionType

EXPIRY_TIME = time(15, 30)
MINUTES_PER_YEAR = 365.0 * 24 * 60


def time_to_expiry(now: datetime, expiry: date) -> float:
    """Calendar-time years until 15:30 on expiry day (floored at ~1 minute)."""
    expiry_dt = datetime.combine(expiry, EXPIRY_TIME, tzinfo=now.tzinfo)
    minutes = (expiry_dt - now).total_seconds() / 60.0
    return max(minutes, 1.0) / MINUTES_PER_YEAR


@dataclass
class RawQuote:
    """Normalised quote for one contract, from either a Kite tick or a /quote response."""
    instrument_token: int
    last_price: float
    bid: float
    ask: float
    volume: int
    oi: int


def enrich_quote(q: OptionQuote, forward: float, t: float, r: float) -> OptionQuote:
    price = q.mid if q.mid > 0 else q.ltp
    iv = implied_vol(price, forward, q.strike, t, r, q.option_type) if price > 0 else None
    q.iv = iv
    if iv:
        g = black76_greeks(forward, q.strike, t, r, iv, q.option_type)
        q.delta, q.gamma, q.theta, q.vega = g.delta, g.gamma, g.theta, g.vega
    return q


def build_chain(
    underlying: str,
    spot: float,
    future: float | None,
    expiry: date,
    now: datetime,
    instruments: list[Instrument],
    quotes: dict[int, RawQuote],
    oi_baseline: dict[int, int] | None = None,
    r: float = 0.065,
    q_div: float = 0.012,
) -> OptionChain:
    t = time_to_expiry(now, expiry)
    # The future of a *different* expiry is not the right forward; only use it when expiries match.
    forward = future if future else forward_from_spot(spot, t, r, q_div)
    chain = OptionChain(underlying, spot, future or forward, expiry, now, t)
    oi_baseline = oi_baseline or {}
    for inst in instruments:
        raw = quotes.get(inst.instrument_token)
        if raw is None or raw.last_price <= 0:
            continue
        ot = OptionType(inst.instrument_type)
        base = oi_baseline.get(inst.instrument_token)
        oq = OptionQuote(
            strike=inst.strike,
            option_type=ot,
            tradingsymbol=inst.tradingsymbol,
            instrument_token=inst.instrument_token,
            ltp=raw.last_price,
            bid=raw.bid,
            ask=raw.ask,
            volume=raw.volume,
            oi=raw.oi,
            oi_change=(raw.oi - base) if base is not None else 0,
        )
        enrich_quote(oq, forward, t, r)
        (chain.calls if ot == OptionType.CE else chain.puts)[inst.strike] = oq
    return chain


def implied_forward(chain: OptionChain, r: float) -> float | None:
    """Put-call parity forward from the ATM pair: F = K + (C - P) * e^{rT}."""
    atm = min(chain.strikes, key=lambda k: abs(k - chain.spot), default=None)
    if atm is None:
        return None
    c, p = chain.calls.get(atm), chain.puts.get(atm)
    if not c or not p:
        return None
    return atm + (c.mid - p.mid) * math.exp(r * chain.time_to_expiry_years)
