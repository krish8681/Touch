"""Strike selection + strategy selection.

For every allowed structure and strike combination the engine prices the
position under the forecast-consistent NIFTY distribution at the horizon
(Black-76 with remaining time, sticky-strike IV), subtracts exit spread
costs, and compares that model value with what the position costs to open
at current bid/ask. Only defined-risk structures are generated: long CE/PE,
debit verticals and iron condors. Naked short options are never proposed.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import datetime

import numpy as np

from ..analytics.greeks import black76_price_vec
from ..config import RiskLimits
from ..models import (
    DirectionForecast,
    ExpectedMove,
    Leg,
    OptionChain,
    OptionQuote,
    OptionType,
    Regime,
    Side,
    TradeProposal,
)
from .expected_move import MINUTES_PER_YEAR_CAL, PriceDistribution, forecast_distribution

LONG_CALL, LONG_PUT = "LONG_CE", "LONG_PE"
BULL_CALL_SPREAD, BEAR_PUT_SPREAD = "BULL_CALL_SPREAD", "BEAR_PUT_SPREAD"
IRON_CONDOR = "IRON_CONDOR"

ALLOWED: dict[Regime, tuple[str, ...]] = {
    Regime.STRONG_BULLISH: (LONG_CALL, BULL_CALL_SPREAD),
    Regime.MODERATE_BULLISH: (BULL_CALL_SPREAD,),
    Regime.STRONG_BEARISH: (LONG_PUT, BEAR_PUT_SPREAD),
    Regime.MODERATE_BEARISH: (BEAR_PUT_SPREAD,),
    Regime.RANGE: (IRON_CONDOR,),
    Regime.HIGH_VOLATILITY: (BULL_CALL_SPREAD, BEAR_PUT_SPREAD, IRON_CONDOR),
    Regime.EVENT: (),
    Regime.UNCERTAIN: (),
}


@dataclass
class _LegSpec:
    side: Side
    quote: OptionQuote

    @property
    def sign(self) -> int:
        return 1 if self.side == Side.BUY else -1

    @property
    def open_price(self) -> float:
        q = self.quote
        if self.side == Side.BUY:
            return q.ask if q.ask > 0 else q.ltp
        return q.bid if q.bid > 0 else q.ltp

    @property
    def half_spread(self) -> float:
        q = self.quote
        return (q.ask - q.bid) / 2 if q.ask > 0 and q.bid > 0 else q.ltp * 0.01


@dataclass
class Candidate:
    proposal: TradeProposal
    score: float
    p_target: float
    expected_pnl: float
    diagnostics: dict[str, float] = field(default_factory=dict)


def liquidity_score(quotes: list[OptionQuote], limits: RiskLimits) -> float:
    scores = []
    for q in quotes:
        s = 0.5 * max(0.0, 1 - q.spread_pct / limits.max_spread_pct)
        s += 0.25 * min(1.0, q.volume / (10 * limits.min_volume))
        s += 0.25 * min(1.0, q.oi / (10 * limits.min_open_interest))
        scores.append(s)
    return min(scores) if scores else 0.0


class StrategyEngine:
    def __init__(self, limits: RiskLimits, r: float = 0.065, step: int = 50, lot_size: int = 75):
        self.limits = limits
        self.r = r
        self.step = step
        self.lot_size = lot_size

    # ── pricing helpers ────────────────────────────────────────────────
    def _value(self, legs: list[_LegSpec], prices: np.ndarray, chain: OptionChain, t_after: float) -> np.ndarray:
        """Position value (sum of signed leg values) for each terminal price, net of exit half-spreads."""
        basis = chain.future / chain.spot if chain.spot else 1.0
        total = np.zeros_like(prices)
        for leg in legs:
            q = leg.quote
            iv = q.iv or 0.15
            vals = black76_price_vec(prices * basis, q.strike, t_after, self.r, iv, q.option_type)
            # Longs exit at the bid (value - half spread), shorts are bought back at the ask (value + half spread).
            total += leg.sign * vals - leg.half_spread
        return total

    @staticmethod
    def _expiry_payoff(legs: list[_LegSpec], prices: np.ndarray) -> np.ndarray:
        total = np.zeros_like(prices)
        for leg in legs:
            k = leg.quote.strike
            intrinsic = np.maximum(prices - k, 0) if leg.quote.option_type == OptionType.CE else np.maximum(k - prices, 0)
            total += leg.sign * intrinsic
        return total

    def evaluate(self, name: str, legs: list[_LegSpec], chain: OptionChain, dist: PriceDistribution,
                 move: ExpectedMove, bullish: bool | None) -> Candidate | None:
        if any(l.open_price <= 0 for l in legs):
            return None
        entry = sum(l.sign * l.open_price for l in legs)        # >0 debit, <0 credit
        t_after = max(chain.time_to_expiry_years - dist.horizon_minutes / MINUTES_PER_YEAR_CAL, 1e-6)
        values = self._value(legs, dist.prices, chain, t_after)
        pnl = values - entry

        grid = np.linspace(chain.spot * 0.8, chain.spot * 1.2, 801)
        payoff = self._expiry_payoff(legs, grid) - entry
        max_loss = float(-payoff.min())
        if name == LONG_CALL:
            max_profit = None
        elif name == LONG_PUT:
            max_profit = legs[0].quote.strike - entry
        else:
            max_profit = float(payoff.max())
        if max_loss <= 0:
            return None

        spot = chain.spot
        if bullish is None:   # range structure: take profit at half the credit, stop at a loss equal to the credit
            credit = -entry
            if credit <= 0:
                return None
            target = entry + 0.5 * credit
            stop = entry - min(credit, max_loss)
        else:
            fav = spot + move.up_points if bullish else spot - move.down_points
            adv = spot - 0.75 * move.down_points if bullish else spot + 0.75 * move.up_points
            target = float(self._value(legs, np.array([fav]), chain, t_after)[0])
            stop_val = float(self._value(legs, np.array([adv]), chain, t_after)[0])
            loss = min(max(entry - stop_val, 0.2 * max_loss), 0.6 * max_loss)
            stop = entry - loss
            if max_profit is not None:
                target = min(target, entry + 0.9 * max_profit)
        reward, risk = target - entry, entry - stop
        if reward <= 0 or risk <= 0:
            return None

        exp_pnl = dist.expect(pnl)
        p_profit = dist.prob(pnl > 0)
        p_target = dist.prob(pnl >= reward)
        quotes = [l.quote for l in legs]
        liq = liquidity_score(quotes, self.limits)
        edge_pct = exp_pnl / max_loss * 100
        rr = reward / risk
        model_value = entry + exp_pnl
        prop = TradeProposal(
            strategy=name,
            legs=[Leg(l.side, l.quote.option_type, l.quote.strike, l.quote.tradingsymbol, l.quote.instrument_token,
                      round(l.open_price, 2)) for l in legs],
            net_premium=round(entry, 2), max_profit=None if max_profit is None else round(max_profit, 2),
            max_loss=round(max_loss, 2), target=round(target, 2), stop=round(stop, 2),
            probability=round(p_profit, 4), model_value=round(model_value, 2), edge_pct=round(edge_pct, 2),
            reward_risk=round(rr, 2), liquidity_score=round(liq, 3), lot_size=self.lot_size,
            horizon_minutes=move.horizon_minutes,
        )
        # Rank by risk-adjusted expectancy, discounted by liquidity; reward/risk breaks ties.
        score = edge_pct * (0.5 + 0.5 * liq) + 2.0 * min(rr, 3.0)
        return Candidate(prop, score, p_target, exp_pnl, {"t_after": t_after})

    # ── candidate generation ───────────────────────────────────────────
    def _strikes_around(self, chain: OptionChain, lo: int, hi: int) -> list[float]:
        atm = chain.atm_strike(self.step)
        return [atm + i * self.step for i in range(lo, hi + 1)]

    def candidates(self, regime: Regime, forecasts: list[DirectionForecast], moves: list[ExpectedMove],
                   chain: OptionChain, vol: float, atr_1m: float, now: datetime) -> list[Candidate]:
        allowed = ALLOWED.get(regime, ())
        if not allowed:
            return []
        fc = {f.horizon_minutes: f for f in forecasts}
        mv = {m.horizon_minutes: m for m in moves}
        out: list[Candidate] = []

        for h in (30, 60):
            if h not in fc:
                continue
            f, m = fc[h], mv[h]
            dist = forecast_distribution(chain.spot, vol, f, atr_1m, now)
            if f.up > f.down:
                if LONG_CALL in allowed:
                    for k in self._strikes_around(chain, -2, 4):
                        q = chain.get(k, OptionType.CE)
                        if q:
                            c = self.evaluate(LONG_CALL, [_LegSpec(Side.BUY, q)], chain, dist, m, True)
                            if c:
                                out.append(c)
                if BULL_CALL_SPREAD in allowed:
                    for k in self._strikes_around(chain, -2, 3):
                        for width in (1, 2, 3, 4):
                            lq, sq = chain.get(k, OptionType.CE), chain.get(k + width * self.step, OptionType.CE)
                            if lq and sq:
                                c = self.evaluate(BULL_CALL_SPREAD, [_LegSpec(Side.BUY, lq), _LegSpec(Side.SELL, sq)],
                                                  chain, dist, m, True)
                                if c:
                                    out.append(c)
            elif f.down > f.up:
                if LONG_PUT in allowed:
                    for k in self._strikes_around(chain, -4, 2):
                        q = chain.get(k, OptionType.PE)
                        if q:
                            c = self.evaluate(LONG_PUT, [_LegSpec(Side.BUY, q)], chain, dist, m, False)
                            if c:
                                out.append(c)
                if BEAR_PUT_SPREAD in allowed:
                    for k in self._strikes_around(chain, -3, 2):
                        for width in (1, 2, 3, 4):
                            lq, sq = chain.get(k, OptionType.PE), chain.get(k - width * self.step, OptionType.PE)
                            if lq and sq:
                                c = self.evaluate(BEAR_PUT_SPREAD, [_LegSpec(Side.BUY, lq), _LegSpec(Side.SELL, sq)],
                                                  chain, dist, m, False)
                                if c:
                                    out.append(c)

        if IRON_CONDOR in allowed and 0 in fc:
            f, m = fc[0], mv[0]
            dist = forecast_distribution(chain.spot, vol, f, atr_1m, now)
            sd_strikes = max(1, round(m.sigma_points / self.step))
            atm = chain.atm_strike(self.step)
            for short_off in {sd_strikes, sd_strikes + 1, sd_strikes + 2}:
                for wing in (2, 3, 4):
                    ks_c, ks_p = atm + short_off * self.step, atm - short_off * self.step
                    legs_q = (chain.get(ks_c, OptionType.CE), chain.get(ks_c + wing * self.step, OptionType.CE),
                              chain.get(ks_p, OptionType.PE), chain.get(ks_p - wing * self.step, OptionType.PE))
                    if not all(legs_q):
                        continue
                    sc, lc, sp, lp = legs_q
                    legs = [_LegSpec(Side.BUY, lc), _LegSpec(Side.BUY, lp), _LegSpec(Side.SELL, sc), _LegSpec(Side.SELL, sp)]
                    c = self.evaluate(IRON_CONDOR, legs, chain, dist, m, None)
                    if c:
                        out.append(c)

        out.sort(key=lambda c: c.score, reverse=True)
        return out

    @staticmethod
    def note_for(c: Candidate) -> list[str]:
        p = c.proposal
        return [
            f"{p.strategy}: model value {p.model_value:.2f} vs cost {p.net_premium:.2f} "
            f"(edge {p.edge_pct:+.1f}% of max loss)",
            f"P(profit @ {p.horizon_minutes or 'EOD'}m) {p.probability:.0%}, P(target) {c.p_target:.0%}, R/R {p.reward_risk:.2f}",
        ]


def round_tick(x: float, tick: float = 0.05) -> float:
    return round(math.floor(x / tick + 0.5) * tick, 2)
