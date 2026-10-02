"""Trading service: wires market data → decision engine → risk → execution → journal,
and publishes everything to connected app clients.
"""

from __future__ import annotations

import asyncio
import itertools
import logging
from dataclasses import asdict, dataclass, field
from datetime import datetime, time
from typing import Any

from .config import Settings
from .data.sources import MarketSource
from .engines.decision import DecisionEngine
from .execution.live import LiveBroker
from .execution.paper import PaperBroker
from .execution.positions import Position, exit_reason, mark_value
from .models import Signal, TradeProposal, _jsonable
from .storage.journal import Journal

log = logging.getLogger(__name__)
_pending_ids = itertools.count(1)


@dataclass
class PendingApproval:
    proposal: TradeProposal
    signal_ts: datetime
    decision_id: int | None
    id: int = field(default_factory=lambda: next(_pending_ids))
    expires_after_s: int = 120


def position_dict(p: Position) -> dict[str, Any]:
    return _jsonable({
        "id": p.journal_id or p.id, "mode": p.mode, "strategy": p.proposal.strategy, "lots": p.lots,
        "lot_size": p.proposal.lot_size, "opened_at": p.opened_at, "closed_at": p.closed_at,
        "entry_value": p.entry_value, "exit_value": p.exit_value, "mark_value": p.last_value,
        "target": p.proposal.target, "stop": p.proposal.stop, "exit_reason": p.exit_reason,
        "unrealized_pnl": p.gross_pnl(p.last_value) if p.is_open and p.last_value is not None else None,
        "net_pnl": None if p.is_open else p.net_pnl, "charges": p.charges,
        "legs": [asdict(l) for l in p.proposal.legs],
    })


class TradingService:
    def __init__(self, settings: Settings, source: MarketSource, engine: DecisionEngine, journal: Journal,
                 live_broker: LiveBroker | None = None):
        self.settings = settings
        self.source = source
        self.engine = engine
        self.journal = journal
        self.paper = PaperBroker()
        self.live = live_broker
        self.latest: Signal | None = None
        self.pending: dict[int, PendingApproval] = {}
        self.subscribers: set[asyncio.Queue] = set()
        self._task: asyncio.Task | None = None
        self._monitor_task: asyncio.Task | None = None
        h, m = settings.risk.square_off_at.split(":")
        self.square_off = time(int(h), int(m))

    # ── lifecycle ──────────────────────────────────────────────────────
    async def start(self) -> None:
        await self.source.start()
        self._task = asyncio.create_task(self._loop())
        self._monitor_task = asyncio.create_task(self._monitor_loop())

    async def stop(self) -> None:
        for t in (self._task, self._monitor_task):
            if t:
                t.cancel()
        await self.source.stop()

    @property
    def positions(self) -> list[Position]:
        return self.paper.positions + (self.live.positions if self.live else [])

    async def _loop(self) -> None:
        while True:
            await self.source.wait_minute()
            try:
                await self.evaluate_once()
            except Exception:
                log.exception("evaluation failed")

    async def _monitor_loop(self, interval: float = 1.0) -> None:
        while True:
            await asyncio.sleep(interval)
            try:
                await self.monitor_once()
            except Exception:
                log.exception("position monitor failed")

    # ── one cycle ──────────────────────────────────────────────────────
    async def evaluate_once(self) -> Signal | None:
        snap = self.source.snapshot()
        if snap is None:
            return None
        sig = self.engine.evaluate(snap)
        self.latest = sig
        decision_id = self.journal.record_signal(sig)
        self._expire_pending(snap.now)
        if sig.action == "TRADE" and sig.proposal is not None:
            await self._route(sig, decision_id)
        await self.publish({"type": "signal", "data": sig.to_dict()})
        return sig

    async def _route(self, sig: Signal, decision_id: int) -> None:
        live = self.settings.live_allowed and self.live is not None
        if live and self.settings.approval_mode != "auto":
            pa = PendingApproval(sig.proposal, sig.timestamp, decision_id)
            self.pending[pa.id] = pa
            await self.publish({"type": "approval_required", "data": {"id": pa.id, "proposal": _jsonable(asdict(pa.proposal))}})
            return
        await self._open(sig.proposal, decision_id, live)

    async def _open(self, proposal: TradeProposal, decision_id: int | None, live: bool) -> Position:
        chain = self.source.chain()
        now = self.source.now()
        if live:
            pos = await self.live.open(proposal, chain)
        else:
            pos = self.paper.open(proposal, chain, now)
        self.engine.risk.on_open(now)
        self.journal.record_open(pos, decision_id)
        await self.publish({"type": "position_opened", "data": position_dict(pos)})
        return pos

    async def monitor_once(self) -> list[Position]:
        chain = self.source.chain()
        if chain is None:
            return []
        now = self.source.now()
        closed = []
        for pos in [p for p in self.positions if p.is_open]:
            value = mark_value(pos.proposal, chain)
            if value is None:
                continue
            pos.last_value = value
            reason = exit_reason(pos, value, now, self.square_off)
            if reason:
                closed.append(await self.close_position(pos, reason))
        return closed

    async def close_position(self, pos: Position, reason: str) -> Position:
        chain = self.source.chain()
        now = self.source.now()
        if pos.mode == "live" and self.live is not None:
            await self.live.close(pos, chain, reason)
        else:
            self.paper.close(pos, chain, now, reason)
        self.engine.risk.on_close(now, pos.net_pnl)
        self.journal.record_close(pos)
        await self.publish({"type": "position_closed", "data": position_dict(pos)})
        return pos

    # ── approvals ──────────────────────────────────────────────────────
    def _expire_pending(self, now: datetime) -> None:
        for pid, pa in list(self.pending.items()):
            if (now - pa.signal_ts).total_seconds() > pa.expires_after_s:
                del self.pending[pid]

    async def approve(self, pending_id: int) -> Position:
        pa = self.pending.pop(pending_id, None)
        if pa is None:
            raise KeyError("unknown or expired approval")
        # Re-run the risk gates at approval time: the market and session state may have moved.
        blocks = self.engine.risk.session_blocks(self.source.now())
        if blocks:
            raise PermissionError("; ".join(blocks))
        return await self._open(pa.proposal, pa.decision_id, live=self.settings.live_allowed and self.live is not None)

    def reject(self, pending_id: int) -> None:
        self.pending.pop(pending_id, None)

    async def kill_switch(self, on: bool, flatten: bool = False) -> None:
        self.engine.risk.set_kill_switch(on, "app kill switch" if on else "")
        self.pending.clear()
        if on and flatten:
            for pos in [p for p in self.positions if p.is_open]:
                await self.close_position(pos, "KILL_SWITCH")
        await self.publish({"type": "risk", "data": self.risk_state()})

    def risk_state(self) -> dict[str, Any]:
        s = self.engine.risk.session(self.source.now().date())
        return _jsonable({**asdict(s), "limits": asdict(self.settings.risk), "capital": self.settings.capital,
                          "execution_mode": self.settings.execution_mode, "live_allowed": self.settings.live_allowed,
                          "approval_mode": self.settings.approval_mode})

    # ── pub/sub ────────────────────────────────────────────────────────
    async def publish(self, message: dict[str, Any]) -> None:
        for q in list(self.subscribers):
            if q.full():
                q.get_nowait()   # drop the oldest message for slow clients
            q.put_nowait(message)
