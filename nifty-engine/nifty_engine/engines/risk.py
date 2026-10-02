"""Risk engine — independent of the prediction model and always has the last word.

Two layers:
  * account/session limits (daily loss, trade count, losing streak, open positions,
    time-of-day, event windows, kill switch);
  * per-trade checks (direction probability, edge, reward/risk, liquidity of every
    leg, calibrated model for live trading) and position sizing.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, datetime, time

from ..config import RiskLimits
from ..models import DirectionForecast, OptionChain, TradeProposal
from .strategy import IRON_CONDOR


def _t(hhmm: str) -> time:
    h, m = hhmm.split(":")
    return time(int(h), int(m))


@dataclass
class SessionState:
    day: date
    trades_taken: int = 0
    realized_pnl: float = 0.0
    consecutive_losses: int = 0
    open_positions: int = 0
    kill_switch: bool = False
    halted_reason: str = ""

    def record_close(self, pnl: float) -> None:
        self.realized_pnl += pnl
        self.consecutive_losses = self.consecutive_losses + 1 if pnl < 0 else 0


@dataclass
class RiskDecision:
    approved: bool
    lots: int
    blocks: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


class RiskEngine:
    def __init__(self, limits: RiskLimits, capital: float, require_calibrated: bool = False):
        self.limits = limits
        self.capital = capital
        self.require_calibrated = require_calibrated
        self.state: SessionState | None = None

    def session(self, today: date) -> SessionState:
        if self.state is None or self.state.day != today:
            kill = self.state.kill_switch if self.state else False   # kill switch survives the day roll
            self.state = SessionState(day=today, kill_switch=kill)
        return self.state

    def set_kill_switch(self, on: bool, reason: str = "manual") -> None:
        s = self.session(self.state.day if self.state else date.today())
        s.kill_switch = on
        s.halted_reason = reason if on else ""

    # ── session gates ─────────────────────────────────────────────────
    def session_blocks(self, now: datetime, event_active: bool = False) -> list[str]:
        s = self.session(now.date())
        L = self.limits
        blocks = []
        if s.kill_switch:
            blocks.append(f"kill switch on ({s.halted_reason or 'manual'})")
        t = now.time()
        if t < _t(L.no_trades_before):
            blocks.append(f"before {L.no_trades_before}")
        if t >= _t(L.no_new_trades_after):
            blocks.append(f"no new trades after {L.no_new_trades_after}")
        if s.trades_taken >= L.max_trades_per_day:
            blocks.append(f"max trades/day reached ({L.max_trades_per_day})")
        if s.realized_pnl <= -self.capital * L.max_daily_loss_pct / 100:
            blocks.append(f"daily loss limit hit ({s.realized_pnl:,.0f})")
        if s.consecutive_losses >= L.max_consecutive_losses:
            blocks.append(f"{s.consecutive_losses} consecutive losses — stopped for the day")
        if s.open_positions >= L.max_open_positions:
            blocks.append(f"max open positions ({L.max_open_positions})")
        if event_active:
            blocks.append("event window — new trades blocked")
        return blocks

    # ── per-trade checks + sizing ─────────────────────────────────────
    def check_trade(self, proposal: TradeProposal, forecast: DirectionForecast, chain: OptionChain,
                    now: datetime, event_active: bool = False) -> RiskDecision:
        L = self.limits
        blocks = self.session_blocks(now, event_active)
        warnings: list[str] = []

        if self.require_calibrated and not forecast.calibrated:
            blocks.append("direction model not trained/calibrated — live trading refused")
        elif not forecast.calibrated:
            warnings.append("using heuristic prior (model not trained) — paper trading only")

        if proposal.strategy == IRON_CONDOR:
            if forecast.sideways < 0.40:
                blocks.append(f"SIDEWAYS probability {forecast.sideways:.0%} < 40% for range structure")
        else:
            p_dir = max(forecast.up, forecast.down)
            if p_dir < L.min_direction_probability:
                blocks.append(f"direction probability {p_dir:.0%} < {L.min_direction_probability:.0%}")
        if proposal.edge_pct < L.min_edge_pct:
            blocks.append(f"edge {proposal.edge_pct:.1f}% < {L.min_edge_pct:.1f}%")
        if proposal.reward_risk < L.min_reward_risk:
            blocks.append(f"reward/risk {proposal.reward_risk:.2f} < {L.min_reward_risk:.2f}")

        for leg in proposal.legs:
            q = chain.get(leg.strike, leg.option_type)
            if q is None:
                blocks.append(f"{leg.tradingsymbol}: no quote")
                continue
            if q.spread_pct > L.max_spread_pct:
                blocks.append(f"{leg.tradingsymbol}: spread {q.spread_pct:.1f}% > {L.max_spread_pct}%")
            if q.volume < L.min_volume:
                blocks.append(f"{leg.tradingsymbol}: volume {q.volume} < {L.min_volume}")
            if q.oi < L.min_open_interest:
                blocks.append(f"{leg.tradingsymbol}: OI {q.oi} < {L.min_open_interest}")

        lots = self.size(proposal)
        if lots < 1:
            blocks.append("risk budget smaller than one lot")
        return RiskDecision(approved=not blocks, lots=max(lots, 0), blocks=blocks, warnings=warnings)

    def size(self, proposal: TradeProposal) -> int:
        """Lots such that the loss at the stop (padded for gaps/slippage), capped at max loss, fits the budget."""
        s = self.state
        budget = self.capital * self.limits.max_risk_per_trade_pct / 100
        if s is not None:
            remaining_day = self.capital * self.limits.max_daily_loss_pct / 100 + min(s.realized_pnl, 0)
            budget = min(budget, max(remaining_day, 0))
        stop_loss = proposal.net_premium - proposal.stop
        per_unit = min(proposal.max_loss, 1.5 * stop_loss) if stop_loss > 0 else proposal.max_loss
        if per_unit <= 0:
            return 0
        return int(math.floor(budget / (per_unit * proposal.lot_size)))

    def on_open(self, now: datetime) -> None:
        s = self.session(now.date())
        s.trades_taken += 1
        s.open_positions += 1

    def on_close(self, now: datetime, pnl: float) -> None:
        s = self.session(now.date())
        s.open_positions = max(0, s.open_positions - 1)
        s.record_close(pnl)
