from datetime import datetime, timedelta

import numpy as np
import pytest

from nifty_engine.config import RiskLimits, Settings
from nifty_engine.data.kite import IST
from nifty_engine.engines.decision import DecisionEngine, MarketSnapshot
from nifty_engine.engines.direction import HORIZONS, DirectionEngine, make_label
from nifty_engine.engines.expected_move import forecast_distribution
from nifty_engine.engines.features import FEATURE_NAMES, FeatureSet, compute_features
from nifty_engine.engines.news import EventWindow, NewsEngine, NewsItem, fuse_news
from nifty_engine.engines.regime import classify_regime
from nifty_engine.engines.risk import RiskEngine
from nifty_engine.engines.strategy import ALLOWED, IRON_CONDOR, StrategyEngine
from nifty_engine.models import DirectionForecast, Leg, OptionType, Regime, Side, TradeProposal


def test_labels():
    assert make_label(10, 2.0, 16) == 0      # threshold 0.5*2*4 = 4
    assert make_label(-10, 2.0, 16) == 2
    assert make_label(3, 2.0, 16) == 1


def test_heuristic_probabilities_are_valid_and_capped():
    for score in (-1, -0.3, 0, 0.4, 1):
        for h in HORIZONS:
            f = DirectionEngine.heuristic(h, score)
            assert f.up + f.sideways + f.down == pytest.approx(1)
            assert max(f.up, f.down) < 0.65
            assert not f.calibrated


def test_model_learns_and_calibrates(tmp_path):
    rng = np.random.default_rng(1)
    n = 4000
    X = rng.normal(size=(n, len(FEATURE_NAMES)))
    logits = np.stack([1.5 * X[:, 0], np.zeros(n), -1.5 * X[:, 0]], axis=1)
    p = np.exp(logits) / np.exp(logits).sum(axis=1, keepdims=True)
    y = np.array([rng.choice(3, p=row) for row in p])
    eng = DirectionEngine()
    reports = eng.train(X, {h: y for h in HORIZONS})
    for r in reports.values():
        assert r.beats_baseline
        assert r.ece < 0.05
    path = tmp_path / "m.json"
    eng.save(path)
    loaded = DirectionEngine.load(path)
    fs = FeatureSet(values={FEATURE_NAMES[0]: 2.0}, atr_1m=1, atr_5m=2, vwap=0)
    f15 = loaded.predict(fs)[0]
    assert f15.calibrated and f15.up > 0.7


def test_model_without_skill_reports_base_rates():
    rng = np.random.default_rng(2)
    X = rng.normal(size=(2000, len(FEATURE_NAMES)))
    y = rng.choice(3, size=2000, p=[0.3, 0.4, 0.3])
    eng = DirectionEngine()
    eng.train(X, {h: y for h in HORIZONS})
    fs = FeatureSet(values={n: 3.0 for n in FEATURE_NAMES}, atr_1m=1, atr_5m=2, vwap=0)
    for f, h in zip(eng.predict(fs), HORIZONS):
        if not eng.reports[h].beats_baseline:
            assert max(f.up, f.down) < 0.4


def test_news_fusion_is_bounded():
    f = DirectionForecast(30, 0.4, 0.3, 0.3, True)
    g = fuse_news(f, 1.0)
    assert g.sideways == pytest.approx(0.3)
    assert g.up + g.down == pytest.approx(0.7)
    assert g.up > f.up and g.up < 0.6

    now = datetime(2025, 3, 3, 11, tzinfo=IST)
    ne = NewsEngine(half_life_min=30)
    ne.add(NewsItem("x", "s", now - timedelta(minutes=30), 1, 1, 1, 1))
    assert ne.impact(now) == pytest.approx(np.tanh(0.5))
    ne.add_event(EventWindow("RBI policy", now + timedelta(minutes=20)))
    assert ne.active_events(now)


def test_distribution_matches_forecast():
    now = datetime(2025, 3, 3, 11, tzinfo=IST)
    f = DirectionForecast(60, 0.6, 0.25, 0.15, True)
    d = forecast_distribution(25_000, 0.13, f, 4.0, now)
    thr = 0.5 * 4.0 * np.sqrt(60)
    assert d.prob(d.prices > 25_000 + thr) == pytest.approx(0.6, abs=1e-6)
    assert d.prob(d.prices < 25_000 - thr) == pytest.approx(0.15, abs=1e-6)
    assert d.weights.sum() == pytest.approx(1)


def test_features_and_regime(snapshot_parts):
    now, mtf, fmtf, chain = snapshot_parts
    from nifty_engine.analytics.option_flow import analyze_chain

    f = compute_features(mtf[1].arrays(), mtf[5].arrays(), fmtf[1].arrays(), analyze_chain(chain), now=now)
    v = f.vector()
    assert v.shape == (len(FEATURE_NAMES),) and np.isfinite(v).all()
    r = classify_regime(f)
    assert -1 <= r.trend_score <= 1
    assert classify_regime(f, event_active=True).regime == Regime.EVENT


def _bull_forecasts():
    return [DirectionForecast(h, 0.62, 0.22, 0.16, True) for h in HORIZONS]


def test_strategy_generates_only_defined_risk(snapshot_parts):
    now, mtf, fmtf, chain = snapshot_parts
    from nifty_engine.engines.expected_move import expected_move

    se = StrategyEngine(RiskLimits())
    fcs = _bull_forecasts()
    moves = [expected_move(chain.spot, 0.13, f, now) for f in fcs]
    cands = se.candidates(Regime.STRONG_BULLISH, fcs, moves, chain, 0.13, 3.0, now)
    assert cands
    for c in cands:
        p = c.proposal
        assert p.strategy in ALLOWED[Regime.STRONG_BULLISH]
        sells = [l for l in p.legs if l.side == Side.SELL]
        buys = [l for l in p.legs if l.side == Side.BUY]
        assert len(buys) >= len(sells)                      # never naked short
        assert p.max_loss > 0
        if p.strategy == "BULL_CALL_SPREAD":
            assert p.max_loss == pytest.approx(p.net_premium, abs=0.05)
            assert p.max_profit == pytest.approx(sells[0].strike - buys[0].strike - p.net_premium, abs=0.5)
        assert p.stop < p.net_premium < p.target
    assert se.candidates(Regime.UNCERTAIN, fcs, moves, chain, 0.13, 3.0, now) == []


def test_iron_condor_for_range(snapshot_parts):
    now, _, _, chain = snapshot_parts
    from nifty_engine.engines.expected_move import expected_move

    se = StrategyEngine(RiskLimits())
    fcs = [DirectionForecast(h, 0.2, 0.6, 0.2, True) for h in HORIZONS]
    moves = [expected_move(chain.spot, 0.13, f, now) for f in fcs]
    cands = se.candidates(Regime.RANGE, fcs, moves, chain, 0.13, 3.0, now)
    assert cands and all(c.proposal.strategy == IRON_CONDOR for c in cands)
    p = cands[0].proposal
    assert p.net_premium < 0 and len(p.legs) == 4


def _proposal(**kw):
    base = dict(strategy="BULL_CALL_SPREAD", legs=[], net_premium=60.0, max_profit=40.0, max_loss=60.0, target=85.0,
                stop=40.0, probability=0.6, model_value=66.0, edge_pct=10.0, reward_risk=1.25, liquidity_score=0.8)
    base.update(kw)
    return TradeProposal(**base)


def test_risk_sizing_and_session_limits(snapshot_parts):
    now, _, _, chain = snapshot_parts
    L = RiskLimits()
    risk = RiskEngine(L, capital=500_000)
    risk.session(now.date())
    p = _proposal()
    # budget 5,000; per-unit risk min(60, 1.5*20)=30 → 5000/(30*75) = 2 lots
    assert risk.size(p) == 2

    now_mid = now.replace(hour=11, minute=0)
    assert risk.session_blocks(now_mid) == []
    assert risk.session_blocks(now.replace(hour=9, minute=16))
    assert risk.session_blocks(now.replace(hour=15, minute=5))
    for _ in range(3):
        risk.on_open(now_mid)
        risk.on_close(now_mid, -100)
    assert any("consecutive" in b for b in risk.session_blocks(now_mid))
    risk.on_open(now_mid)
    risk.on_close(now_mid, 1000)
    risk.state.realized_pnl = -10_001
    assert any("daily loss" in b for b in risk.session_blocks(now_mid))
    risk.set_kill_switch(True)
    assert any("kill switch" in b for b in risk.session_blocks(now_mid))


def test_risk_trade_checks(snapshot_parts):
    now, _, _, chain = snapshot_parts
    now = now.replace(hour=11, minute=0)
    risk = RiskEngine(RiskLimits(), 500_000, require_calibrated=True)
    k = chain.atm_strike()
    q = chain.get(k, OptionType.CE)
    p = _proposal(legs=[Leg(Side.BUY, OptionType.CE, k, q.tradingsymbol, q.instrument_token, q.ask)], edge_pct=0.5,
                  reward_risk=0.9)
    d = risk.check_trade(p, DirectionForecast(30, 0.5, 0.3, 0.2, calibrated=False), chain, now)
    assert not d.approved
    text = " ".join(d.blocks)
    for needle in ("not trained", "direction probability", "edge", "reward/risk"):
        assert needle in text


def test_decision_engine_end_to_end(snapshot_parts):
    now, mtf, fmtf, chain = snapshot_parts
    eng = DecisionEngine(Settings())
    sig = eng.evaluate(MarketSnapshot(now, mtf[1].arrays(), mtf[5].arrays(), chain, fmtf[1].arrays()))
    assert sig.action in ("TRADE", "NO_TRADE")
    assert len(sig.forecasts) == 4 and len(sig.expected_moves) == 4
    for f in sig.forecasts:
        assert f.up + f.sideways + f.down == pytest.approx(1)
    if sig.action == "TRADE":
        assert sig.proposal.lots >= 1
    else:
        assert sig.blocks
    d = sig.to_dict()
    assert d["regime"] in {r.value for r in Regime}
