"""Market regime classification.

The regime decides which strategy family is even allowed; direction
probabilities then decide within that family. Regime is rule-based and
transparent on purpose so it can be audited from the app.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from ..models import Regime
from .features import FeatureSet


@dataclass
class RegimeResult:
    regime: Regime
    confidence: float
    trend_score: float          # -1..+1
    components: dict[str, float] = field(default_factory=dict)
    reasons: list[str] = field(default_factory=list)


# Weighted votes of independent groups; each component is roughly in -1..+1.
TREND_WEIGHTS = {
    "vwap": 0.15,
    "ema_stack": 0.20,
    "momentum": 0.15,
    "structure": 0.10,
    "di": 0.10,
    "opening_range": 0.10,
    "option_flow": 0.10,
    "futures": 0.10,
}


def _sq(x: float, scale: float = 1.0) -> float:
    """Soft clip into -1..1."""
    return max(-1.0, min(1.0, x / scale))


def trend_components(f: FeatureSet) -> dict[str, float]:
    v = f.values
    return {
        "vwap": _sq(v["dist_vwap"], 1.5),
        "ema_stack": _sq(0.5 * v["dist_ema20"] + v["ema20_50"] + 0.5 * v["ema50_200"], 2.0),
        "momentum": _sq(0.5 * v["ret_15"] + 0.3 * v["ret_30"] + 0.5 * v["macd_hist"] + v["rsi"], 2.0),
        "structure": v["structure"],
        "di": _sq(v["di_diff"], 0.4),
        "opening_range": _sq(v["or_break"], 1.0),
        "option_flow": _sq(v.get("flow_bias", 0.0) + 0.5 * v.get("pcr_oi", 0.0), 1.0),
        "futures": _sq(v["fut_buildup"], 1.0),
    }


def classify_regime(
    f: FeatureSet,
    iv_percentile: float | None = None,
    event_active: bool = False,
    high_iv_pct: float = 85.0,
) -> RegimeResult:
    comps = trend_components(f)
    trend = sum(TREND_WEIGHTS[k] * comps[k] for k in TREND_WEIGHTS)
    adx = f.extras.get("adx_raw", 0.0)
    rv_ratio = f.values["rv_ratio"]
    reasons: list[str] = []

    if event_active:
        return RegimeResult(Regime.EVENT, 1.0, trend, comps, ["scheduled event window active"])

    if (iv_percentile is not None and iv_percentile >= high_iv_pct) or rv_ratio > 2.2:
        why = f"IV percentile {iv_percentile:.0f}" if iv_percentile is not None and iv_percentile >= high_iv_pct else f"realized vol spike x{rv_ratio:.1f}"
        return RegimeResult(Regime.HIGH_VOLATILITY, min(1.0, 0.6 + 0.2 * (rv_ratio - 1)), trend, comps, [why])

    agree = sum(1 for k in comps if comps[k] * trend > 0.15)
    strength = abs(trend)
    if strength >= 0.45 and adx >= 22 and agree >= 5:
        regime = Regime.STRONG_BULLISH if trend > 0 else Regime.STRONG_BEARISH
        reasons.append(f"trend score {trend:+.2f}, ADX {adx:.0f}, {agree}/8 groups aligned")
    elif strength >= 0.25 and agree >= 4:
        regime = Regime.MODERATE_BULLISH if trend > 0 else Regime.MODERATE_BEARISH
        reasons.append(f"trend score {trend:+.2f}, {agree}/8 groups aligned")
    elif strength < 0.18 and adx < 20 and abs(f.values["dist_vwap"]) < 0.8:
        regime = Regime.RANGE
        reasons.append(f"low ADX {adx:.0f}, price near VWAP, trend score {trend:+.2f}")
    else:
        regime = Regime.UNCERTAIN
        reasons.append(f"mixed signals: trend score {trend:+.2f}, ADX {adx:.0f}, {agree}/8 aligned")

    if regime == Regime.RANGE:
        confidence = 1.0 - strength / 0.18 * 0.5
    elif regime == Regime.UNCERTAIN:
        confidence = 0.3
    else:
        confidence = min(1.0, 0.4 + strength * 0.6 + agree / 8 * 0.3)
    return RegimeResult(regime, round(confidence, 3), trend, comps, reasons)
