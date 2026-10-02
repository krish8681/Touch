"""Option-chain intelligence: OI build-up, support/resistance, PCR, max pain, IV structure.

OI is never used on its own to call direction; these outputs are features
that the direction model weighs together with price structure.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from ..models import OptionChain, OptionQuote


def classify_buildup(price_change: float, oi_change: float, eps: float = 1e-9) -> str:
    """Classic price/OI quadrant for a futures or option contract."""
    if abs(oi_change) <= eps or abs(price_change) <= eps:
        return "NEUTRAL"
    if price_change > 0 and oi_change > 0:
        return "LONG_BUILDUP"
    if price_change < 0 and oi_change > 0:
        return "SHORT_BUILDUP"
    if price_change > 0 and oi_change < 0:
        return "SHORT_COVERING"
    return "LONG_UNWINDING"


# Directional reading of the underlying implied by a build-up in a FUTURES contract.
BUILDUP_BIAS = {
    "LONG_BUILDUP": 1.0,
    "SHORT_COVERING": 0.5,
    "NEUTRAL": 0.0,
    "LONG_UNWINDING": -0.5,
    "SHORT_BUILDUP": -1.0,
}


@dataclass
class ChainLevel:
    strike: float
    oi: int
    oi_change: int
    volume: int
    score: float


@dataclass
class ChainAnalysis:
    pcr_oi: float
    pcr_oi_change: float
    pcr_volume: float
    max_pain: float
    resistance: list[ChainLevel] = field(default_factory=list)   # call writing above spot
    support: list[ChainLevel] = field(default_factory=list)      # put writing below spot
    atm_iv: float | None = None
    iv_skew: float | None = None        # 25-delta-ish put IV minus call IV (vol points)
    flow_bias: float = 0.0              # -1..+1, positive = puts being written harder than calls near ATM
    distance_to_resistance: float | None = None
    distance_to_support: float | None = None


def _level_score(q: OptionQuote, max_oi: int, max_doi: int, max_vol: int) -> float:
    s = 0.5 * (q.oi / max_oi if max_oi else 0)
    s += 0.3 * (max(q.oi_change, 0) / max_doi if max_doi else 0)
    s += 0.2 * (q.volume / max_vol if max_vol else 0)
    return s


def max_pain(chain: OptionChain) -> float:
    strikes = chain.strikes
    if not strikes:
        return chain.spot
    best, best_pain = strikes[0], float("inf")
    for settle in strikes:
        pain = 0.0
        for k, c in chain.calls.items():
            pain += max(settle - k, 0) * c.oi
        for k, p in chain.puts.items():
            pain += max(k - settle, 0) * p.oi
        if pain < best_pain:
            best, best_pain = settle, pain
    return best


def analyze_chain(chain: OptionChain, step: int = 50, top_n: int = 3, flow_band: int = 4) -> ChainAnalysis:
    calls, puts = chain.calls, chain.puts
    call_oi = sum(q.oi for q in calls.values())
    put_oi = sum(q.oi for q in puts.values())
    call_doi = sum(q.oi_change for q in calls.values())
    put_doi = sum(q.oi_change for q in puts.values())
    call_vol = sum(q.volume for q in calls.values())
    put_vol = sum(q.volume for q in puts.values())

    def ratio(a: float, b: float) -> float:
        return a / b if b else 0.0

    all_q = list(calls.values()) + list(puts.values())
    max_oi = max((q.oi for q in all_q), default=0)
    max_doi = max((max(q.oi_change, 0) for q in all_q), default=0)
    max_vol = max((q.volume for q in all_q), default=0)

    spot = chain.spot
    res = [
        ChainLevel(k, q.oi, q.oi_change, q.volume, _level_score(q, max_oi, max_doi, max_vol))
        for k, q in calls.items() if k >= spot
    ]
    sup = [
        ChainLevel(k, q.oi, q.oi_change, q.volume, _level_score(q, max_oi, max_doi, max_vol))
        for k, q in puts.items() if k <= spot
    ]
    res.sort(key=lambda lv: lv.score, reverse=True)
    sup.sort(key=lambda lv: lv.score, reverse=True)

    atm = chain.atm_strike(step)
    atm_ivs = [q.iv for q in (calls.get(atm), puts.get(atm)) if q is not None and q.iv]
    atm_iv = sum(atm_ivs) / len(atm_ivs) if atm_ivs else None

    # Skew: OTM put ~4 strikes below vs OTM call ~4 strikes above.
    otm_put = puts.get(atm - flow_band * step)
    otm_call = calls.get(atm + flow_band * step)
    iv_skew = None
    if otm_put and otm_call and otm_put.iv and otm_call.iv:
        iv_skew = (otm_put.iv - otm_call.iv) * 100

    # Near-ATM writing pressure: put OI added = support being built (bullish), call OI added = cap (bearish).
    near = [atm + i * step for i in range(-flow_band, flow_band + 1)]
    near_put_add = sum(max(puts[k].oi_change, 0) for k in near if k in puts)
    near_call_add = sum(max(calls[k].oi_change, 0) for k in near if k in calls)
    total_add = near_put_add + near_call_add
    flow_bias = (near_put_add - near_call_add) / total_add if total_add else 0.0

    top_res, top_sup = res[:top_n], sup[:top_n]
    return ChainAnalysis(
        pcr_oi=ratio(put_oi, call_oi),
        pcr_oi_change=ratio(put_doi, call_doi) if call_doi > 0 else 0.0,
        pcr_volume=ratio(put_vol, call_vol),
        max_pain=max_pain(chain),
        resistance=top_res,
        support=top_sup,
        atm_iv=atm_iv,
        iv_skew=iv_skew,
        flow_bias=flow_bias,
        distance_to_resistance=(top_res[0].strike - spot) if top_res else None,
        distance_to_support=(spot - top_sup[0].strike) if top_sup else None,
    )


def iv_percentile(current_iv: float, history: list[float]) -> float | None:
    """Share of historical observations below the current IV (0..100)."""
    if not history:
        return None
    below = sum(1 for v in history if v < current_iv)
    return 100.0 * below / len(history)
