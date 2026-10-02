"""DhanHQ client, downloader and store, against a fake Dhan API built from the synthetic market."""

import argparse
import json
import math
from datetime import date, datetime, time, timedelta

import httpx
import numpy as np
import pytest

from nifty_engine.analytics.greeks import black76_price, forward_from_spot
from nifty_engine.data.chain_builder import time_to_expiry
from nifty_engine.data.dhan import DhanClient, DhanDownloader, DhanError, DownloadPlan, date_chunks
from nifty_engine.data.dhan_store import DhanChainProvider, DhanStore, coverage_report
from nifty_engine.data.kite import IST
from nifty_engine.models import OptionType

R, Q = 0.065, 0.012


class FakeDhan:
    """Serves /v2/charts/intraday and /v2/charts/rollingoption from a deterministic random walk."""

    def __init__(self, expiry_shift_weeks: int = 0, fail_first: int = 0, drop_offsets: set[int] = frozenset()):
        self.shift = expiry_shift_weeks          # 0: data is the near expiry; 1: the next one
        self.fail_first = fail_first
        self.drop = drop_offsets
        self.calls = []
        self._paths: dict[date, np.ndarray] = {}

    def path(self, d: date) -> np.ndarray:
        if d not in self._paths:
            rng = np.random.default_rng(d.toordinal())
            self._paths[d] = 25_000 * np.exp(np.cumsum(rng.normal(0, 0.0004, 375)))
        return self._paths[d]

    @staticmethod
    def days(a: date, b: date):
        d = a
        while d < b:
            if d.weekday() < 5:
                yield d
            d += timedelta(days=1)

    def expiry(self, d: date) -> date:
        wd = 3 if d < date(2025, 9, 1) else 1
        e = d + timedelta(days=(wd - d.weekday()) % 7)
        return e + timedelta(days=7 * self.shift)

    def handler(self, req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        self.calls.append((req.url.path, body, dict(req.headers)))
        if self.fail_first:
            self.fail_first -= 1
            return httpx.Response(429, json={"errorCode": "805", "errorMessage": "Too many requests"})
        if req.url.path == "/v2/charts/intraday":
            a = datetime.strptime(body["fromDate"], "%Y-%m-%d %H:%M:%S").date()
            b = datetime.strptime(body["toDate"], "%Y-%m-%d %H:%M:%S").date() + timedelta(days=1)
            out = {k: [] for k in ("open", "high", "low", "close", "volume", "timestamp")}
            for d in self.days(a, b):
                p = self.path(d)
                for m, c in enumerate(p):
                    ts = datetime.combine(d, time(9, 15), IST) + timedelta(minutes=m)
                    o = p[m - 1] if m else c
                    for k, v in (("open", o), ("high", max(o, c) + 1), ("low", min(o, c) - 1), ("close", c), ("volume", 0),
                                 ("timestamp", int(ts.timestamp()))):
                        out[k].append(v)
            return httpx.Response(200, json=out)
        if req.url.path == "/v2/charts/rollingoption":
            off = 0 if body["strike"] == "ATM" else int(body["strike"][3:])
            ot = OptionType.CE if body["drvOptionType"] == "CALL" else OptionType.PE
            a, b = date.fromisoformat(body["fromDate"]), date.fromisoformat(body["toDate"])
            arr = {k: [] for k in ("open", "high", "low", "close", "volume", "oi", "iv", "spot", "strike", "timestamp")}
            if off not in self.drop:
                for d in self.days(a, b):
                    e = self.expiry(d)
                    for m, s in enumerate(self.path(d)):
                        ts = datetime.combine(d, time(9, 15), IST) + timedelta(minutes=m)
                        k = round(s / 50) * 50 + off * 50
                        t = time_to_expiry(ts + timedelta(minutes=1), e)
                        iv = 0.12 + 0.02 * abs(off) / 10
                        px = round(black76_price(forward_from_spot(s, t, R, Q), k, t, R, iv, ot), 2)
                        for key, v in (("open", px), ("high", px), ("low", px), ("close", max(px, 0.05)), ("volume", 1500),
                                       ("oi", 500_000 + m * 100), ("iv", iv * 100), ("spot", s), ("strike", k),
                                       ("timestamp", int(ts.timestamp()))):
                            arr[key].append(v)
            data = {"ce": arr if ot == OptionType.CE else None, "pe": arr if ot == OptionType.PE else None}
            return httpx.Response(200, json={"data": data})
        return httpx.Response(404, json={})


def _client(fake, **kw):
    return DhanClient("tok", "cid", per_second=1000, transport=httpx.MockTransport(fake.handler), sleep=lambda s: None, **kw)


def test_date_chunks_are_half_open_and_cover_range():
    ch = date_chunks(date(2025, 1, 1), date(2025, 3, 5), 30)
    assert ch[0] == (date(2025, 1, 1), date(2025, 1, 31))
    assert ch[-1][1] == date(2025, 3, 5)
    assert all(a < b for a, b in ch) and all(ch[i][1] == ch[i + 1][0] for i in range(len(ch) - 1))


def test_client_request_shape_and_retry():
    fake = FakeDhan(fail_first=2)
    c = _client(fake)
    raw = c.rolling_option(offset=-3, option_type="PE", from_date=date(2025, 3, 3), to_date=date(2025, 3, 4))
    assert len(raw["timestamp"]) == 375
    path, body, headers = fake.calls[-1]
    assert path == "/v2/charts/rollingoption"
    assert body["strike"] == "ATM-3" and body["drvOptionType"] == "PUT" and body["securityId"] == 13
    assert body["exchangeSegment"] == "NSE_FNO" and body["instrument"] == "OPTIDX" and "iv" in body["requiredData"]
    assert headers["access-token"] == "tok" and headers["client-id"] == "cid"
    assert c.calls == 3


def test_client_raises_non_retryable():
    def handler(req):
        return httpx.Response(400, json={"errorCode": "DH-905", "errorMessage": "bad input"})

    c = DhanClient("tok", transport=httpx.MockTransport(handler), sleep=lambda s: None)
    with pytest.raises(DhanError) as e:
        c.rolling_option(offset=0, option_type="CE", from_date=date(2025, 3, 3), to_date=date(2025, 3, 4))
    assert e.value.code == "DH-905" and c.calls == 1


@pytest.fixture
def downloaded(tmp_path):
    fake = FakeDhan(drop_offsets={4})
    plan = DownloadPlan(date(2025, 8, 25), date(2025, 9, 6), strikes=5, option_days=7, index_days=10)
    rep = DhanDownloader(_client(fake), tmp_path).run(plan)
    return tmp_path, fake, plan, rep


def test_download_writes_chunks_and_resumes(downloaded):
    root, fake, plan, rep = downloaded
    assert rep.option_chunks_done == 2 and rep.index_chunks_done == 2
    assert len(rep.empty_series) == 2 * 2           # offset +4, CE and PE, in both chunks
    manifest = json.loads((root / "NIFTY" / "manifest.json").read_text())
    assert len(manifest["options"]["WEEK1_1m"]) == 2
    calls_before = len(fake.calls)
    rep2 = DhanDownloader(_client(fake), root).run(plan)
    assert rep2.option_chunks_skipped == 2 and rep2.index_chunks_skipped == 2
    assert len(fake.calls) == calls_before


def test_store_index_and_chain(downloaded):
    root, fake, plan, _ = downloaded
    store = DhanStore(root)
    candles = store.index_candles()
    assert len(candles) == 10 * 375
    assert candles[0].start == datetime(2025, 8, 25, 9, 15, tzinfo=IST)
    assert store.trading_days[0] == date(2025, 8, 25)

    prov = DhanChainProvider(store, spread_bps=50)
    c = candles[100]
    chain = prov(c.start + timedelta(minutes=1), c.close)
    assert chain is not None and chain.expiry == date(2025, 8, 28)       # Thursday expiry before Sep 2025
    atm = chain.atm_strike()
    q = chain.get(atm, OptionType.CE)
    assert q is not None and q.bid < q.ltp < q.ask
    assert q.iv == pytest.approx(0.12, abs=0.01)
    assert q.volume >= 1500
    assert chain.get(atm + 200, OptionType.CE) is None or chain.get(atm + 200, OptionType.CE).strike == atm + 200
    later = candles[300]
    chain2 = prov(later.start + timedelta(minutes=1), later.close)
    q2 = chain2.get(chain2.atm_strike(), OptionType.PE)
    assert q2.oi_change >= 0


def test_expiry_resolution_switches_to_tuesday_and_detects_next_expiry(tmp_path):
    fake = FakeDhan(expiry_shift_weeks=1)            # data belongs to the *next* weekly expiry
    plan = DownloadPlan(date(2025, 9, 1), date(2025, 9, 6), strikes=2, option_days=7, index_days=10)
    DhanDownloader(_client(fake), tmp_path).run(plan)
    store = DhanStore(tmp_path)
    store.index_candles()
    res = store.resolve_expiry(date(2025, 9, 3))
    assert res.method == "iv-match"
    assert res.expiry == date(2025, 9, 16)           # Wed 3 Sep: near Tuesday is 9 Sep, next is 16 Sep
    assert store.iv_scale == 0.01                    # Dhan IV reported in percent
    rows = coverage_report(store)
    assert len(rows) == 5 and all(r["coverage"] > 0 for r in rows)


def test_holiday_moves_expiry_to_previous_trading_day(tmp_path):
    store = DhanStore(tmp_path)
    store._trading_days = [date(2025, 3, 10), date(2025, 3, 11), date(2025, 3, 12), date(2025, 3, 14), date(2025, 3, 17)]
    # Thursday 13 Mar 2025 was a holiday in this calendar → expiry Wednesday 12 Mar
    assert store.expiry_candidates(date(2025, 3, 10))[0] == date(2025, 3, 12)


def test_train_and_backtest_from_dhan_data(downloaded):
    from nifty_engine.__main__ import _market
    from nifty_engine.backtest.replay import ReplayEngine, build_training_set
    from nifty_engine.config import Settings
    from nifty_engine.engines.decision import DecisionEngine

    root, *_ = downloaded
    args = argparse.Namespace(source="dhan", data=str(root), series="WEEK1_1m", start=None, end=None, spread_bps=50.0)
    spot, fut, provider, label = _market(args)
    assert fut is None and label.startswith("dhan:")
    X, labels, stamps = build_training_set(spot, None, provider, step=30)
    assert X.shape[0] == len(stamps) > 0
    assert np.isfinite(X).all()
    settings = Settings()
    spot, fut, provider, _ = _market(args)
    res = ReplayEngine(settings, DecisionEngine(settings), provider, eval_every=15).run(spot, fut)
    assert res.signals and all(not p.is_open for p in res.positions)
