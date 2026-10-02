"""Positions, mark-to-market and exit rules (shared by paper and live execution)."""

from __future__ import annotations

import itertools
from dataclasses import dataclass, field
from datetime import datetime, time, timedelta

from ..models import Leg, OptionChain, Side, TradeProposal
from .charges import leg_charges

_ids = itertools.count(1)


@dataclass
class Fill:
    leg: Leg
    price: float
    quantity: int
    at: datetime
    order_id: str = ""


@dataclass
class Position:
    proposal: TradeProposal
    lots: int
    opened_at: datetime
    entry_fills: list[Fill]
    mode: str = "paper"
    id: int = field(default_factory=lambda: next(_ids))
    exit_fills: list[Fill] = field(default_factory=list)
    closed_at: datetime | None = None
    exit_reason: str = ""
    last_value: float | None = None
    charges: float = 0.0
    journal_id: int | None = None

    @property
    def quantity(self) -> int:
        return self.lots * self.proposal.lot_size

    @property
    def is_open(self) -> bool:
        return self.closed_at is None

    @property
    def entry_value(self) -> float:
        """Net premium paid per unit (credit is negative)."""
        return sum((1 if f.leg.side == Side.BUY else -1) * f.price for f in self.entry_fills)

    @property
    def exit_value(self) -> float | None:
        if not self.exit_fills:
            return None
        # Closing a BUY leg is a sale and vice versa; value received per unit.
        return sum((1 if f.leg.side == Side.BUY else -1) * f.price for f in self.exit_fills)

    def gross_pnl(self, value: float | None = None) -> float:
        v = self.exit_value if value is None else value
        if v is None:
            return 0.0
        return (v - self.entry_value) * self.quantity

    @property
    def net_pnl(self) -> float:
        return self.gross_pnl() - self.charges

    def deadline(self) -> datetime:
        """Maximum holding time: twice the forecast horizon (EOD structures hold to square-off)."""
        h = self.proposal.horizon_minutes
        return self.opened_at + timedelta(minutes=2 * h) if h else datetime.max.replace(tzinfo=self.opened_at.tzinfo)


def mark_value(proposal: TradeProposal, chain: OptionChain) -> float | None:
    """Per-unit value if the position were closed now (long legs at bid, short legs at ask)."""
    total = 0.0
    for leg in proposal.legs:
        q = chain.get(leg.strike, leg.option_type)
        if q is None:
            return None
        if leg.side == Side.BUY:
            px = q.bid if q.bid > 0 else q.ltp
            total += px
        else:
            px = q.ask if q.ask > 0 else q.ltp
            total -= px
    return total


def exit_reason(pos: Position, value: float, now: datetime, square_off: time) -> str | None:
    p = pos.proposal
    if value >= p.target:
        return "TARGET"
    if value <= p.stop:
        return "STOP"
    if now.time() >= square_off:
        return "SQUARE_OFF"
    if now >= pos.deadline():
        return "TIME"
    return None


def entry_charges(fills: list[Fill]) -> float:
    return sum(leg_charges(f.leg.side, f.price, f.quantity) for f in fills)


def exit_charges(fills: list[Fill]) -> float:
    # The closing order is on the opposite side of the opening leg.
    return sum(leg_charges(Side.SELL if f.leg.side == Side.BUY else Side.BUY, f.price, f.quantity) for f in fills)
