import pytest

from datetime import date, datetime, timedelta

from nifty_engine.analytics.option_flow import analyze_chain, classify_buildup, max_pain
from nifty_engine.data.candles import CandleSeries, MultiTimeframe, bucket_start
from nifty_engine.data.chain_builder import RawQuote, build_chain
from nifty_engine.data.instruments import NiftyUniverse, parse_instruments_csv
from nifty_engine.data.kite import IST
from nifty_engine.models import Candle, Instrument, OptionType, Tick

CSV = """instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
111,1,NIFTY25MAR25000CE,"NIFTY",0,2025-03-06,25000,0.05,75,CE,NFO-OPT,NFO
112,2,NIFTY25MAR25000PE,"NIFTY",0,2025-03-06,25000,0.05,75,PE,NFO-OPT,NFO
113,3,NIFTY25MAR25050CE,"NIFTY",0,2025-03-06,25050,0.05,75,CE,NFO-OPT,NFO
114,4,NIFTY25MAR2525000CE,"NIFTY",0,2025-03-13,25000,0.05,75,CE,NFO-OPT,NFO
200,5,NIFTY25MARFUT,"NIFTY",0,2025-03-27,0,0.1,75,FUT,NFO-FUT,NFO
300,6,BANKNIFTY25MAR50000CE,"BANKNIFTY",0,2025-03-06,50000,0.05,30,CE,NFO-OPT,NFO
"""


def test_universe_selection():
    u = NiftyUniverse(parse_instruments_csv(CSV))
    assert len(u.options) == 4
    assert u.nearest_expiry(date(2025, 3, 3)) == date(2025, 3, 6)
    assert u.nearest_expiry(date(2025, 3, 6), skip_expiry_day=True) == date(2025, 3, 13)
    assert u.near_future(date(2025, 3, 3)).instrument_token == 200
    chain = u.chain_instruments(date(2025, 3, 6), 25_000, 50, 1)
    assert [i.instrument_token for i in chain] == [111, 112, 113]
    assert u.option(date(2025, 3, 6), 25_050, OptionType.CE).tradingsymbol == "NIFTY25MAR25050CE"
    assert u.lot_size == 75


def test_bucket_alignment_to_session_open():
    t = datetime(2025, 3, 3, 9, 22, 30, tzinfo=IST)
    assert bucket_start(t, 5) == datetime(2025, 3, 3, 9, 20, tzinfo=IST)
    assert bucket_start(t, 15) == datetime(2025, 3, 3, 9, 15, tzinfo=IST)


def test_tick_aggregation_uses_volume_deltas():
    s = CandleSeries(1)
    t0 = datetime(2025, 3, 3, 9, 15, 5, tzinfo=IST)
    s.on_tick(Tick(1, 100, t0, volume=1000))
    s.on_tick(Tick(1, 103, t0 + timedelta(seconds=20), volume=1600))
    s.on_tick(Tick(1, 99, t0 + timedelta(seconds=40), volume=2000))
    closed = s.on_tick(Tick(1, 101, t0 + timedelta(seconds=60), volume=2100))
    assert (closed.open, closed.high, closed.low, closed.close, closed.volume) == (100, 103, 99, 99, 1000)
    assert s.current.volume == 100


def test_rollup_to_higher_timeframes():
    m = MultiTimeframe((1, 5))
    t0 = datetime(2025, 3, 3, 9, 15, tzinfo=IST)
    for i in range(10):
        m.add_one_minute(Candle(t0 + timedelta(minutes=i), 100 + i, 101 + i, 99 + i, 100.5 + i, 10))
    five = m[5].candles(include_current=True)
    assert len(five) == 2
    assert five[0].open == 100 and five[0].high == 105 and five[0].volume == 50


def test_buildup_quadrants():
    assert classify_buildup(1, 1) == "LONG_BUILDUP"
    assert classify_buildup(-1, 1) == "SHORT_BUILDUP"
    assert classify_buildup(1, -1) == "SHORT_COVERING"
    assert classify_buildup(-1, -1) == "LONG_UNWINDING"


def _chain():
    exp = date(2025, 3, 6)
    now = datetime(2025, 3, 3, 11, 0, tzinfo=IST)
    insts, quotes = [], {}
    tok = 1
    for k in (24_900, 25_000, 25_100):
        for ot in OptionType:
            insts.append(Instrument(tok, f"N{k}{ot.value}", "NIFTY", "NFO", "NFO-OPT", ot.value, exp, k, 75))
            oi = {(25_100, "CE"): 900_000, (24_900, "PE"): 800_000}.get((k, ot.value), 100_000)
            price = 120.0 if k == 25_000 else (60.0 if (k > 25_000) == (ot == OptionType.CE) else 170.0)
            quotes[tok] = RawQuote(tok, price, price - 0.5, price + 0.5, 10_000, oi)
            tok += 1
    return build_chain("NIFTY", 25_000, None, exp, now, insts, quotes, oi_baseline={1: 50_000})


def test_chain_build_and_analysis():
    ch = _chain()
    assert set(ch.strikes) == {24_900, 25_000, 25_100}
    q = ch.get(25_000, OptionType.CE)
    assert q.iv and 0.02 < q.iv < 1.0 and 0.3 < q.delta < 0.7
    assert ch.get(24_900, OptionType.CE).oi_change == 50_000
    a = analyze_chain(ch, 50, flow_band=1)
    assert a.resistance[0].strike == 25_100
    assert a.support[0].strike == 24_900
    assert a.pcr_oi == pytest.approx(1_000_000 / 1_100_000)
    assert max_pain(ch) == 25_000
