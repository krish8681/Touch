"""Decision and trade journal (SQLAlchemy Core: SQLite by default, PostgreSQL via DATABASE_URL).

Every evaluation is stored with its features and probabilities so that
predictions can later be joined with what NIFTY actually did — the closed
loop used for re-training and calibration checks.
"""

from __future__ import annotations

import json
from dataclasses import asdict
from datetime import datetime
from typing import Any

from sqlalchemy import (
    JSON,
    Column,
    DateTime,
    Float,
    Integer,
    MetaData,
    String,
    Table,
    create_engine,
    insert,
    select,
    update,
)

from ..models import Signal, _jsonable
from ..execution.positions import Position

metadata = MetaData()

decisions = Table(
    "decisions", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("ts", DateTime(timezone=True), index=True, nullable=False),
    Column("spot", Float, nullable=False),
    Column("regime", String(32), nullable=False),
    Column("action", String(16), nullable=False),
    Column("strategy", String(32)),
    Column("model_calibrated", Integer, nullable=False),
    Column("features", JSON, nullable=False),
    Column("forecasts", JSON, nullable=False),
    Column("signal", JSON, nullable=False),
)

trades = Table(
    "trades", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("mode", String(8), nullable=False),
    Column("decision_id", Integer),
    Column("strategy", String(32), nullable=False),
    Column("lots", Integer, nullable=False),
    Column("lot_size", Integer, nullable=False),
    Column("opened_at", DateTime(timezone=True), nullable=False),
    Column("closed_at", DateTime(timezone=True)),
    Column("entry_value", Float, nullable=False),
    Column("exit_value", Float),
    Column("gross_pnl", Float),
    Column("charges", Float),
    Column("net_pnl", Float),
    Column("exit_reason", String(16)),
    Column("proposal", JSON, nullable=False),
    Column("fills", JSON, nullable=False),
)


class Journal:
    def __init__(self, url: str = "sqlite:///./nifty_engine.db"):
        self.engine = create_engine(url, future=True)
        metadata.create_all(self.engine)

    def record_signal(self, sig: Signal) -> int:
        d = sig.to_dict()
        with self.engine.begin() as conn:
            res = conn.execute(insert(decisions).values(
                ts=sig.timestamp, spot=sig.spot, regime=sig.regime.value, action=sig.action,
                strategy=sig.proposal.strategy if sig.proposal else None,
                model_calibrated=int(bool(sig.context.get("model_calibrated"))),
                features=sig.context.get("features", {}), forecasts=d["forecasts"], signal=d,
            ))
            return int(res.inserted_primary_key[0])

    def _fills(self, pos: Position) -> list[dict[str, Any]]:
        return _jsonable([
            {"phase": phase, "symbol": f.leg.tradingsymbol, "side": f.leg.side, "price": f.price, "qty": f.quantity,
             "at": f.at, "order_id": f.order_id}
            for phase, fl in (("entry", pos.entry_fills), ("exit", pos.exit_fills)) for f in fl
        ])

    def record_open(self, pos: Position, decision_id: int | None = None) -> int:
        with self.engine.begin() as conn:
            res = conn.execute(insert(trades).values(
                mode=pos.mode, decision_id=decision_id, strategy=pos.proposal.strategy, lots=pos.lots,
                lot_size=pos.proposal.lot_size, opened_at=pos.opened_at, entry_value=pos.entry_value,
                charges=pos.charges, proposal=_jsonable(asdict(pos.proposal)), fills=self._fills(pos),
            ))
            pos.journal_id = int(res.inserted_primary_key[0])
            return pos.journal_id

    def record_close(self, pos: Position) -> None:
        with self.engine.begin() as conn:
            conn.execute(update(trades).where(trades.c.id == pos.journal_id).values(
                closed_at=pos.closed_at, exit_value=pos.exit_value, gross_pnl=pos.gross_pnl(), charges=pos.charges,
                net_pnl=pos.net_pnl, exit_reason=pos.exit_reason, fills=self._fills(pos),
            ))

    def recent_signals(self, limit: int = 50) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(select(decisions.c.signal).order_by(decisions.c.id.desc()).limit(limit)).all()
        return [r[0] if isinstance(r[0], dict) else json.loads(r[0]) for r in rows]

    def trades_between(self, start: datetime | None = None, end: datetime | None = None) -> list[dict[str, Any]]:
        q = select(trades).order_by(trades.c.opened_at)
        if start:
            q = q.where(trades.c.opened_at >= start)
        if end:
            q = q.where(trades.c.opened_at < end)
        with self.engine.connect() as conn:
            return [dict(r._mapping) for r in conn.execute(q).all()]
