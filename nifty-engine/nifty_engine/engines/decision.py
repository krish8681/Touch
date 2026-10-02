"""Decision engine: market snapshot → regime → probabilities → expected move →
option valuation → strike/strategy → risk → TRADE / NO TRADE.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

from ..analytics.option_flow import ChainAnalysis, analyze_chain, iv_percentile
from ..config import Settings
from ..data.candles import CandleArrays
from ..models import OptionChain, Regime, Signal
from .direction import DirectionEngine
from .expected_move import blended_vol, expected_move
from .features import compute_features
from .news import NewsEngine, fuse_news
from .regime import classify_regime
from .risk import RiskEngine
from .strategy import IRON_CONDOR, StrategyEngine


@dataclass
class MarketSnapshot:
    now: datetime
    m1: CandleArrays                    # NIFTY spot, 1-minute
    m5: CandleArrays                    # NIFTY spot, 5-minute
    chain: OptionChain
    fut_m1: CandleArrays | None = None  # NIFTY near-month futures, 1-minute (volume, OI, basis)
    vix: float | None = None
    vix_prev_close: float | None = None
    iv_history: list[float] = field(default_factory=list)   # ATM IV samples for IV percentile


class DecisionEngine:
    def __init__(self, settings: Settings, direction: DirectionEngine | None = None, news: NewsEngine | None = None,
                 risk: RiskEngine | None = None):
        self.settings = settings
        self.direction = direction or DirectionEngine()
        self.news = news or NewsEngine()
        self.risk = risk or RiskEngine(settings.risk, settings.capital, require_calibrated=settings.live_allowed)
        self.strategy = StrategyEngine(settings.risk, settings.risk_free_rate, settings.strike_step, settings.lot_size)

    def evaluate(self, snap: MarketSnapshot) -> Signal:
        now, chain = snap.now, snap.chain
        ca: ChainAnalysis = analyze_chain(chain, self.settings.strike_step)
        impact = self.news.impact(now)
        events = self.news.active_events(now)
        fut = snap.fut_m1 if self.direction.uses_futures else None
        f = compute_features(snap.m1, snap.m5, fut, ca, snap.vix, snap.vix_prev_close, impact, now)
        ivp = iv_percentile(ca.atm_iv, snap.iv_history) if ca.atm_iv and len(snap.iv_history) >= 20 else None
        reg = classify_regime(f, ivp, event_active=bool(events))

        forecasts = [fuse_news(fc, impact) for fc in self.direction.predict(f, reg.trend_score)]
        vol = blended_vol(ca.atm_iv, f.extras.get("rv_short"), f.atr_1m, chain.spot)
        moves = [expected_move(chain.spot, vol, fc, now) for fc in forecasts]

        reasons = list(reg.reasons)
        blocks = self.risk.session_blocks(now, bool(events))
        proposal = None
        action = "NO_TRADE"
        cands = []
        if reg.regime in (Regime.EVENT, Regime.UNCERTAIN):
            blocks.append(f"regime {reg.regime.value}: no structure allowed")
        elif not blocks:
            cands = self.strategy.candidates(reg.regime, forecasts, moves, chain, vol, f.atr_1m, now)
            if not cands:
                blocks.append("no candidate structure with positive model value")
            by_h = {fc.horizon_minutes: fc for fc in forecasts}
            first_blocks: list[str] | None = None
            for cand in cands[:15]:
                p = cand.proposal
                fc = by_h[0] if p.strategy == IRON_CONDOR else by_h[p.horizon_minutes]
                decision = self.risk.check_trade(p, fc, chain, now, bool(events))
                if decision.approved:
                    p.lots = decision.lots
                    p.notes = self.strategy.note_for(cand) + decision.warnings
                    proposal, action = p, "TRADE"
                    reasons.append(f"selected {p.strategy} ({len(cands)} candidates evaluated)")
                    break
                if first_blocks is None:
                    first_blocks = [f"best candidate {p.strategy}: {b}" for b in decision.blocks]
            if proposal is None and first_blocks:
                blocks.extend(first_blocks)

        return Signal(
            timestamp=now,
            spot=chain.spot,
            regime=reg.regime,
            regime_confidence=reg.confidence,
            forecasts=forecasts,
            expected_moves=moves,
            action=action,
            proposal=proposal,
            reasons=reasons,
            blocks=blocks,
            context={
                "vwap": round(f.vwap, 2),
                "atr_1m": round(f.atr_1m, 2),
                "atr_5m": round(f.atr_5m, 2),
                "trend_score": round(reg.trend_score, 3),
                "trend_components": {k: round(v, 3) for k, v in reg.components.items()},
                "vol_blended": round(vol, 4),
                "atm_iv": ca.atm_iv,
                "iv_percentile": ivp,
                "pcr_oi": round(ca.pcr_oi, 3),
                "max_pain": ca.max_pain,
                "resistance": [lv.strike for lv in ca.resistance],
                "support": [lv.strike for lv in ca.support],
                "flow_bias": round(ca.flow_bias, 3),
                "news_impact": round(impact, 3),
                "events": [e.name for e in events],
                "model_calibrated": self.direction.trained,
                "candidates_evaluated": len(cands),
                "features": {k: round(v, 4) for k, v in f.values.items()},
            },
        )
