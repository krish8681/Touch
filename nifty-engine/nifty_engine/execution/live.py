"""Live execution through Kite Connect.

Safety properties:
  * disabled unless EXECUTION_MODE=live AND LIVE_TRADING_ENABLED=true;
  * LIMIT orders only, priced a few ticks through the touch (no market orders on options);
  * multi-leg structures buy their long legs first so a short leg is never naked;
  * an order ID only means the order was *registered* — each leg waits for COMPLETE,
    and if a later leg fails, already-filled legs are unwound;
  * exits buy back short legs before selling long legs.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime

from ..config import Settings
from ..data.kite import KiteClient
from ..models import Leg, OptionChain, Side, TradeProposal
from .positions import Fill, Position, entry_charges, exit_charges

log = logging.getLogger(__name__)

TERMINAL = {"COMPLETE", "REJECTED", "CANCELLED"}


class LiveExecutionError(RuntimeError):
    pass


def _round_tick(x: float, tick: float = 0.05) -> float:
    return round(round(x / tick) * tick, 2)


class LiveBroker:
    def __init__(self, settings: Settings, kite: KiteClient, price_buffer_ticks: int = 2, fill_timeout: float = 20.0,
                 product: str = "NRML", tag: str = "niftyengine"):
        if not settings.live_allowed:
            raise LiveExecutionError("live trading disabled (set EXECUTION_MODE=live and LIVE_TRADING_ENABLED=true)")
        self.kite = kite
        self.buffer = price_buffer_ticks * 0.05
        self.fill_timeout = fill_timeout
        self.product = product
        self.tag = tag
        self.positions: list[Position] = []
        self._order_events: dict[str, asyncio.Queue] = {}

    async def on_order_update(self, data: dict) -> None:
        """Feed WebSocket order postbacks here (KiteTicker on_order)."""
        q = self._order_events.get(data.get("order_id", ""))
        if q is not None:
            await q.put(data)

    async def _await_fill(self, order_id: str) -> dict:
        q = self._order_events.setdefault(order_id, asyncio.Queue())
        loop = asyncio.get_running_loop()
        deadline = loop.time() + self.fill_timeout
        try:
            while loop.time() < deadline:
                try:
                    upd = await asyncio.wait_for(q.get(), timeout=1.0)
                    if upd.get("status") in TERMINAL:
                        return upd
                except asyncio.TimeoutError:
                    hist = await self.kite.order_history(order_id)
                    if hist and hist[-1].get("status") in TERMINAL:
                        return hist[-1]
            return {"order_id": order_id, "status": "TIMEOUT"}
        finally:
            self._order_events.pop(order_id, None)

    async def _execute_leg(self, leg: Leg, side: Side, qty: int, limit: float) -> Fill:
        order_id = await self.kite.place_order(
            tradingsymbol=leg.tradingsymbol, exchange="NFO", transaction_type=side.value, quantity=qty,
            order_type="LIMIT", product=self.product, price=_round_tick(limit), tag=self.tag,
        )
        result = await self._await_fill(order_id)
        status = result.get("status")
        if status != "COMPLETE":
            if status == "TIMEOUT":
                try:
                    await self.kite.cancel_order(order_id)
                except Exception as exc:  # already filled/cancelled in the meantime
                    log.warning("cancel %s failed: %s", order_id, exc)
            raise LiveExecutionError(f"{leg.tradingsymbol} {side.value} {status}: {result.get('status_message', '')}")
        price = float(result.get("average_price") or limit)
        return Fill(leg, price, qty, datetime.now().astimezone(), order_id)

    def _limit(self, side: Side, chain: OptionChain, leg: Leg) -> float:
        q = chain.get(leg.strike, leg.option_type)
        if q is None:
            raise LiveExecutionError(f"no quote for {leg.tradingsymbol}")
        if side == Side.BUY:
            return (q.ask if q.ask > 0 else q.ltp) + self.buffer
        return max(0.05, (q.bid if q.bid > 0 else q.ltp) - self.buffer)

    async def open(self, proposal: TradeProposal, chain: OptionChain) -> Position:
        qty = proposal.lots * proposal.lot_size
        ordered = sorted(proposal.legs, key=lambda l: 0 if l.side == Side.BUY else 1)
        fills: list[Fill] = []
        try:
            for leg in ordered:
                fills.append(await self._execute_leg(leg, leg.side, qty, self._limit(leg.side, chain, leg)))
        except LiveExecutionError:
            await self._unwind(fills, chain)
            raise
        pos = Position(proposal, proposal.lots, fills[0].at, fills, mode="live")
        pos.charges = entry_charges(fills)
        self.positions.append(pos)
        return pos

    async def _unwind(self, fills: list[Fill], chain: OptionChain) -> None:
        for f in sorted(fills, key=lambda f: 0 if f.leg.side == Side.SELL else 1):
            side = Side.SELL if f.leg.side == Side.BUY else Side.BUY
            try:
                await self._execute_leg(f.leg, side, f.quantity, self._limit(side, chain, f.leg))
            except LiveExecutionError as exc:
                log.critical("UNWIND FAILED for %s — manual intervention required: %s", f.leg.tradingsymbol, exc)

    async def close(self, pos: Position, chain: OptionChain, reason: str) -> Position:
        fills = []
        for f in sorted(pos.entry_fills, key=lambda f: 0 if f.leg.side == Side.SELL else 1):
            side = Side.SELL if f.leg.side == Side.BUY else Side.BUY
            fills.append(await self._execute_leg(f.leg, side, f.quantity, self._limit(side, chain, f.leg)))
        pos.exit_fills = fills
        pos.closed_at = fills[-1].at
        pos.exit_reason = reason
        pos.charges += exit_charges(fills)
        return pos
