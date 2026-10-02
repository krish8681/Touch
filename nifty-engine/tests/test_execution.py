import asyncio
from datetime import time, timedelta

import pytest

from nifty_engine.backtest.metrics import performance
from nifty_engine.backtest.replay import ReplayEngine, build_training_set, synthetic_chain_provider
from nifty_engine.config import Settings
from nifty_engine.engines.decision import DecisionEngine
from nifty_engine.execution.charges import leg_charges
from nifty_engine.execution.live import LiveBroker, LiveExecutionError
from nifty_engine.execution.paper import PaperBroker
from nifty_engine.execution.positions import exit_reason, mark_value
from nifty_engine.models import Leg, OptionType, Side, TradeProposal


def _spread(chain):
    k = chain.atm_strike()
    b, s = chain.get(k, OptionType.CE), chain.get(k + 100, OptionType.CE)
    legs = [Leg(Side.BUY, OptionType.CE, k, b.tradingsymbol, b.instrument_token, b.ask),
            Leg(Side.SELL, OptionType.CE, k + 100, s.tradingsymbol, s.instrument_token, s.bid)]
    debit = b.ask - s.bid
    return TradeProposal("BULL_CALL_SPREAD", legs, debit, 100 - debit, debit, debit * 1.5, debit * 0.6, 0.6, debit, 5, 1.5,
                         0.9, lots=2, horizon_minutes=30)


def test_charges_sell_side_pays_stt():
    assert leg_charges(Side.SELL, 100, 75) > leg_charges(Side.BUY, 100, 75)


def test_paper_round_trip(snapshot_parts):
    now, _, _, chain = snapshot_parts
    broker = PaperBroker(slippage_ticks=0)
    p = _spread(chain)
    pos = broker.open(p, chain, now)
    assert pos.entry_value == pytest.approx(p.net_premium)
    assert pos.quantity == 150
    broker.close(pos, chain, now + timedelta(minutes=1), "MANUAL")
    # Immediate round trip loses the spread plus charges.
    assert pos.gross_pnl() < 0 and pos.net_pnl < pos.gross_pnl()
    assert mark_value(p, chain) == pytest.approx(pos.exit_value)


def test_exit_rules(snapshot_parts):
    now, _, _, chain = snapshot_parts
    pos = PaperBroker().open(_spread(chain), chain, now)
    p = pos.proposal
    sq = time(15, 15)
    assert exit_reason(pos, p.target + 1, now, sq) == "TARGET"
    assert exit_reason(pos, p.stop - 1, now, sq) == "STOP"
    assert exit_reason(pos, p.net_premium, now + timedelta(minutes=61), sq) == "TIME"
    assert exit_reason(pos, p.net_premium, now.replace(hour=15, minute=16), sq) == "SQUARE_OFF"
    assert exit_reason(pos, p.net_premium, now + timedelta(minutes=5), sq) is None


def test_live_broker_refuses_when_disabled():
    with pytest.raises(LiveExecutionError):
        LiveBroker(Settings(execution_mode="live", live_trading_enabled=False), kite=None)


class FakeKite:
    def __init__(self, fail_symbol=None):
        self.orders, self.fail_symbol, self._n = [], fail_symbol, 0

    async def place_order(self, **kw):
        self._n += 1
        self.orders.append(kw)
        return str(self._n)

    async def order_history(self, order_id):
        kw = self.orders[int(order_id) - 1]
        if kw["tradingsymbol"] == self.fail_symbol and len(self.orders) == int(order_id) and kw["transaction_type"] == "SELL":
            return [{"order_id": order_id, "status": "REJECTED", "status_message": "margin"}]
        return [{"order_id": order_id, "status": "COMPLETE", "average_price": kw["price"]}]

    async def cancel_order(self, order_id):
        return order_id


def test_live_broker_buys_first_and_unwinds_on_failure(snapshot_parts):
    _, _, _, chain = snapshot_parts
    settings = Settings(execution_mode="live", live_trading_enabled=True)
    p = _spread(chain)

    kite = FakeKite()
    lb = LiveBroker(settings, kite, fill_timeout=0.5)
    pos = asyncio.run(lb.open(p, chain))
    assert [o["transaction_type"] for o in kite.orders] == ["BUY", "SELL"]
    assert all(o["order_type"] == "LIMIT" for o in kite.orders)
    asyncio.run(lb.close(pos, chain, "MANUAL"))
    assert [o["transaction_type"] for o in kite.orders[2:]] == ["BUY", "SELL"]   # short leg bought back first

    failing = FakeKite(fail_symbol=p.legs[1].tradingsymbol)
    lb2 = LiveBroker(settings, failing, fill_timeout=0.5)
    with pytest.raises(LiveExecutionError):
        asyncio.run(lb2.open(p, chain))
    # long leg bought, short leg rejected, long leg sold back
    assert [(o["tradingsymbol"], o["transaction_type"]) for o in failing.orders] == [
        (p.legs[0].tradingsymbol, "BUY"), (p.legs[1].tradingsymbol, "SELL"), (p.legs[0].tradingsymbol, "SELL")]


def test_replay_and_training_set(market_data):
    market, spot, fut = market_data
    settings = Settings()
    res = ReplayEngine(settings, DecisionEngine(settings), synthetic_chain_provider(market), eval_every=10).run(spot, fut)
    assert res.signals
    assert all(not p.is_open for p in res.positions)
    rep = performance(res.positions, settings.capital)
    assert rep.trades == len(res.positions)

    X, labels, stamps = build_training_set(spot[:900], fut[:900], step=15)
    assert X.shape[0] == len(stamps) == len(labels[15])
    assert set(labels[60]) <= {-1, 0, 1, 2}
