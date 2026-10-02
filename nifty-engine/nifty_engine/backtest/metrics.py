"""Performance metrics — expectancy and drawdown first, win rate is just one number among many."""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass, field

import numpy as np

from ..execution.positions import Position


@dataclass
class PerformanceReport:
    trades: int
    win_rate: float
    avg_win: float
    avg_loss: float
    profit_factor: float
    expectancy: float               # net ₹ per trade
    net_pnl: float
    gross_pnl: float
    charges: float
    max_drawdown: float             # ₹, peak-to-trough on the net equity curve
    max_drawdown_pct: float
    sharpe: float                   # annualised, on daily net P&L / capital
    sortino: float
    avg_holding_minutes: float
    by_strategy: dict[str, dict[str, float]] = field(default_factory=dict)
    by_exit_reason: dict[str, int] = field(default_factory=dict)

    def summary(self) -> str:
        return (f"trades {self.trades} | win {self.win_rate:.1%} | avg win ₹{self.avg_win:,.0f} | avg loss ₹{self.avg_loss:,.0f} | "
                f"PF {self.profit_factor:.2f} | expectancy ₹{self.expectancy:,.0f}/trade | net ₹{self.net_pnl:,.0f} "
                f"(charges ₹{self.charges:,.0f}) | maxDD ₹{self.max_drawdown:,.0f} ({self.max_drawdown_pct:.1%}) | "
                f"Sharpe {self.sharpe:.2f} | Sortino {self.sortino:.2f} | hold {self.avg_holding_minutes:.0f}m")


def performance(positions: list[Position], capital: float) -> PerformanceReport:
    closed = sorted((p for p in positions if not p.is_open), key=lambda p: p.closed_at)
    n = len(closed)
    if n == 0:
        return PerformanceReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    pnl = np.array([p.net_pnl for p in closed])
    wins, losses = pnl[pnl > 0], pnl[pnl <= 0]
    gross_win, gross_loss = wins.sum(), -losses.sum()
    equity = capital + np.cumsum(pnl)
    peak = np.maximum.accumulate(np.concatenate([[capital], equity]))[1:]
    dd = peak - equity
    i = int(dd.argmax())

    daily: dict = defaultdict(float)
    for p in closed:
        daily[p.closed_at.date()] += p.net_pnl
    d = np.array(list(daily.values())) / capital
    sharpe = float(d.mean() / d.std(ddof=1) * math.sqrt(252)) if len(d) > 1 and d.std(ddof=1) > 0 else 0.0
    downside = d[d < 0]
    sortino = float(d.mean() / downside.std(ddof=1) * math.sqrt(252)) if len(downside) > 1 and downside.std(ddof=1) > 0 else 0.0

    by_strat: dict[str, dict[str, float]] = {}
    for name in {p.proposal.strategy for p in closed}:
        sp = np.array([p.net_pnl for p in closed if p.proposal.strategy == name])
        by_strat[name] = {"trades": len(sp), "net_pnl": float(sp.sum()), "win_rate": float((sp > 0).mean()),
                          "expectancy": float(sp.mean())}
    reasons: dict[str, int] = defaultdict(int)
    for p in closed:
        reasons[p.exit_reason] += 1

    return PerformanceReport(
        trades=n,
        win_rate=float(len(wins) / n),
        avg_win=float(wins.mean()) if len(wins) else 0.0,
        avg_loss=float(losses.mean()) if len(losses) else 0.0,
        profit_factor=float(gross_win / gross_loss) if gross_loss > 0 else float("inf"),
        expectancy=float(pnl.mean()),
        net_pnl=float(pnl.sum()),
        gross_pnl=float(sum(p.gross_pnl() for p in closed)),
        charges=float(sum(p.charges for p in closed)),
        max_drawdown=float(dd.max()),
        max_drawdown_pct=float(dd[i] / peak[i]) if peak[i] else 0.0,
        sharpe=sharpe,
        sortino=sortino,
        avg_holding_minutes=float(np.mean([(p.closed_at - p.opened_at).total_seconds() / 60 for p in closed])),
        by_strategy=by_strat,
        by_exit_reason=dict(reasons),
    )
