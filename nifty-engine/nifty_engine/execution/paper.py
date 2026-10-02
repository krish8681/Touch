"""Paper broker: virtual fills at the touch plus slippage, full position lifecycle."""

from __future__ import annotations

from datetime import datetime, time

from ..models import OptionChain, Side, TradeProposal
from .positions import Fill, Position, entry_charges, exit_charges, exit_reason, mark_value


class PaperBroker:
    def __init__(self, slippage_ticks: int = 1, tick: float = 0.05, square_off: time = time(15, 15)):
        self.slippage = slippage_ticks * tick
        self.square_off = square_off
        self.positions: list[Position] = []

    def _fill_price(self, side: Side, bid: float, ask: float, ltp: float) -> float:
        if side == Side.BUY:
            return (ask if ask > 0 else ltp) + self.slippage
        return max(0.05, (bid if bid > 0 else ltp) - self.slippage)

    def open(self, proposal: TradeProposal, chain: OptionChain, now: datetime) -> Position:
        if proposal.lots < 1:
            raise ValueError("proposal has no lots sized")
        qty = proposal.lots * proposal.lot_size
        fills = []
        for leg in proposal.legs:
            q = chain.get(leg.strike, leg.option_type)
            if q is None:
                raise ValueError(f"no quote for {leg.tradingsymbol}")
            fills.append(Fill(leg, self._fill_price(leg.side, q.bid, q.ask, q.ltp), qty, now, order_id=f"PAPER-{leg.tradingsymbol}"))
        pos = Position(proposal, proposal.lots, now, fills, mode="paper")
        pos.charges = entry_charges(fills)
        self.positions.append(pos)
        return pos

    def close(self, pos: Position, chain: OptionChain, now: datetime, reason: str) -> Position:
        fills = []
        for f in pos.entry_fills:
            q = chain.get(f.leg.strike, f.leg.option_type)
            if q is None:
                raise ValueError(f"no quote for {f.leg.tradingsymbol}")
            closing_side = Side.SELL if f.leg.side == Side.BUY else Side.BUY
            fills.append(Fill(f.leg, self._fill_price(closing_side, q.bid, q.ask, q.ltp), f.quantity, now))
        pos.exit_fills = fills
        pos.closed_at = now
        pos.exit_reason = reason
        pos.charges += exit_charges(fills)
        return pos

    def monitor(self, chain: OptionChain, now: datetime) -> list[Position]:
        """Mark open positions and close the ones that hit an exit rule. Returns closed positions."""
        closed = []
        for pos in self.open_positions:
            value = mark_value(pos.proposal, chain)
            if value is None:
                continue
            pos.last_value = value
            reason = exit_reason(pos, value, now, self.square_off)
            if reason:
                closed.append(self.close(pos, chain, now, reason))
        return closed

    @property
    def open_positions(self) -> list[Position]:
        return [p for p in self.positions if p.is_open]
