"""Historical replay: walk minute by minute through recorded/historical data, generate decisions,
open paper trades and manage exits exactly as the live loop does.

Also builds labelled training sets for the direction model from the same replay,
so features at training time and at decision time come from identical code.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timedelta

import numpy as np

from ..analytics.option_flow import analyze_chain
from ..config import Settings
from ..data.candles import MultiTimeframe
from ..data.chain_builder import RawQuote, build_chain
from ..data.synthetic import SyntheticMarket
from ..engines.decision import DecisionEngine, MarketSnapshot
from ..engines.direction import HORIZONS, make_label
from ..engines.features import compute_features
from ..execution.paper import PaperBroker
from ..execution.positions import Position
from ..models import Candle, Instrument, OptionChain, Signal
from .metrics import PerformanceReport, performance

ChainProvider = Callable[[datetime, float], OptionChain | None]


def synthetic_chain_provider(market: SyntheticMarket, each_side: int = 12) -> ChainProvider:
    """Chains from the synthetic market. Instruments are cached per (expiry, ATM) so strikes keep stable tokens."""
    cache: dict[tuple, list[Instrument]] = {}

    def provide(now: datetime, spot: float) -> OptionChain:
        expiry = market.weekly_expiry(now.date())
        atm = round(spot / market.strike_step) * market.strike_step
        key = (expiry, atm // 500)
        if key not in cache:
            cache[key] = market.instruments(expiry, round(spot / 500) * 500, each_side + 10)
        insts = [i for i in cache[key] if abs(i.strike - atm) <= each_side * market.strike_step]
        quotes = market.quotes(insts, spot, now)
        return build_chain("NIFTY", spot, None, expiry, now, insts, quotes, r=market.r, q_div=market.q)

    return provide


def candle_chain_provider(instruments: list[Instrument], candles: dict[int, dict[datetime, Candle]],
                          spread_bps: float = 60.0, r: float = 0.065) -> ChainProvider:
    """Chains from per-contract 1-minute candles (e.g. recorded snapshots or Kite historical data).

    Historical candles have no bid/ask, so a symmetric spread of `spread_bps` around the close is assumed —
    keep it pessimistic.
    """
    by_expiry: dict = {}
    for i in instruments:
        by_expiry.setdefault(i.expiry, []).append(i)

    def provide(now: datetime, spot: float) -> OptionChain | None:
        live = sorted(e for e in by_expiry if e and e >= now.date())
        if not live:
            return None
        expiry = live[0]
        quotes = {}
        key = now.replace(second=0, microsecond=0) - timedelta(minutes=1)   # last *completed* minute
        for inst in by_expiry[expiry]:
            c = candles.get(inst.instrument_token, {}).get(key)
            if c is None:
                continue
            half = max(0.05, c.close * spread_bps / 2 / 10_000)
            quotes[inst.instrument_token] = RawQuote(inst.instrument_token, c.close, max(0.05, c.close - half),
                                                     c.close + half, int(c.volume), int(c.oi))
        if not quotes:
            return None
        return build_chain("NIFTY", spot, None, expiry, now, by_expiry[expiry], quotes, r=r)

    return provide


@dataclass
class ReplayResult:
    signals: list[Signal]
    positions: list[Position]
    report: PerformanceReport
    equity_curve: list[tuple[datetime, float]] = field(default_factory=list)


class ReplayEngine:
    def __init__(self, settings: Settings, engine: DecisionEngine, chain_provider: ChainProvider,
                 eval_every: int = 5, warmup_minutes: int = 375):
        self.settings = settings
        self.engine = engine
        self.chains = chain_provider
        self.eval_every = eval_every
        self.warmup = warmup_minutes
        self.broker = PaperBroker()

    def run(self, spot: list[Candle], fut: list[Candle] | None = None) -> ReplayResult:
        mtf, fmtf = MultiTimeframe((1, 5)), MultiTimeframe((1,))
        risk = self.engine.risk
        signals: list[Signal] = []
        equity = [(spot[0].start, self.settings.capital)]
        realized = 0.0
        iv_hist: list[float] = []
        for i, c in enumerate(spot):
            mtf.add_one_minute(c)
            if fut:
                fmtf.add_one_minute(fut[i])
            if i < self.warmup:
                continue
            now = c.start + timedelta(minutes=1)        # decision made once the minute has closed
            need_eval = i % self.eval_every == 0
            if not (need_eval or self.broker.open_positions):
                continue
            chain = self.chains(now, c.close)
            if chain is None:
                continue
            for pos in self.broker.monitor(chain, now):
                risk.on_close(now, pos.net_pnl)
                realized += pos.net_pnl
                equity.append((now, self.settings.capital + realized))
            if not need_eval:
                continue
            snap = MarketSnapshot(now, mtf[1].arrays(), mtf[5].arrays(), chain, fmtf[1].arrays() if fut else None, iv_history=iv_hist[-2000:])
            sig = self.engine.evaluate(snap)
            if sig.context.get("atm_iv"):
                iv_hist.append(sig.context["atm_iv"])
            signals.append(sig)
            if sig.action == "TRADE" and sig.proposal is not None:
                self.broker.open(sig.proposal, chain, now)
                risk.on_open(now)
        # Close anything left open at the last available price.
        last = spot[-1]
        end = last.start + timedelta(minutes=1)
        chain = self.chains(end, last.close)
        if chain is not None:
            for pos in list(self.broker.open_positions):
                self.broker.close(pos, chain, end, "END_OF_DATA")
                risk.on_close(end, pos.net_pnl)
                realized += pos.net_pnl
                equity.append((end, self.settings.capital + realized))
        return ReplayResult(signals, self.broker.positions, performance(self.broker.positions, self.settings.capital), equity)


def build_training_set(spot: list[Candle], fut: list[Candle] | None = None, chain_provider: ChainProvider | None = None,
                       step: int = 5, warmup_minutes: int = 375) -> tuple[np.ndarray, dict[int, np.ndarray], list[datetime]]:
    """Features every `step` minutes with UP/SIDEWAYS/DOWN labels for each horizon.

    Intraday horizons that would cross the session close are left unlabelled (-1);
    the end-of-day horizon is labelled with the move to the session's last close.
    """
    mtf, fmtf = MultiTimeframe((1, 5)), MultiTimeframe((1,))
    closes = np.array([c.close for c in spot])
    days = np.array([c.start.date().toordinal() for c in spot])
    last_idx_of_day: dict[int, int] = {}
    for idx, d in enumerate(days):
        last_idx_of_day[d] = idx
    rows, labels, stamps = [], {h: [] for h in HORIZONS}, []
    for i, c in enumerate(spot):
        mtf.add_one_minute(c)
        if fut:
            fmtf.add_one_minute(fut[i])
        if i < warmup_minutes or i % step:
            continue
        now = c.start + timedelta(minutes=1)
        chain = chain_provider(now, c.close) if chain_provider else None
        ca = analyze_chain(chain) if chain is not None else None
        f = compute_features(mtf[1].arrays(), mtf[5].arrays(), fmtf[1].arrays() if fut else None, ca, now=now)
        rows.append(f.vector())
        stamps.append(now)
        end_i = last_idx_of_day[days[i]]
        for h in HORIZONS:
            j = end_i if h == 0 else i + h
            if j > end_i or (h == 0 and end_i - i < 15):
                labels[h].append(-1)
                continue
            horizon = h if h else end_i - i
            labels[h].append(make_label(closes[j] - closes[i], f.atr_1m, horizon))
    return np.array(rows), {h: np.array(v, dtype=int) for h, v in labels.items()}, stamps
