from datetime import date, datetime, timedelta

import pytest

from nifty_engine.backtest.replay import synthetic_chain_provider
from nifty_engine.data.candles import MultiTimeframe
from nifty_engine.data.synthetic import SyntheticMarket


@pytest.fixture(scope="session")
def market_data():
    market = SyntheticMarket(seed=5)
    spot, fut = market.generate(date(2025, 3, 3), 4)
    return market, spot, fut


@pytest.fixture
def snapshot_parts(market_data):
    """Candles up to mid-session of day 3 plus a chain at that moment."""
    market, spot, fut = market_data
    mtf, fmtf = MultiTimeframe((1, 5)), MultiTimeframe((1,))
    upto = 375 * 2 + 150
    for c, f in zip(spot[:upto], fut[:upto]):
        mtf.add_one_minute(c)
        fmtf.add_one_minute(f)
    last = spot[upto - 1]
    now = last.start + timedelta(minutes=1)
    chain = synthetic_chain_provider(market)(now, last.close)
    return now, mtf, fmtf, chain
