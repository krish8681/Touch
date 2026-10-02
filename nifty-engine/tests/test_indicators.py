import numpy as np
import pytest

from nifty_engine.analytics import indicators as ind


@pytest.fixture
def ohlc():
    rng = np.random.default_rng(0)
    close = 100 + np.cumsum(rng.normal(0, 1, 300))
    high = close + rng.uniform(0, 1, 300)
    low = close - rng.uniform(0, 1, 300)
    return high, low, close


def test_ema_constant_series():
    out = ind.ema(np.full(50, 7.0), 10)
    assert np.isnan(out[:9]).all()
    assert np.allclose(out[9:], 7.0)


def test_rsi_bounds_and_extremes(ohlc):
    _, _, close = ohlc
    r = ind.rsi(close)
    valid = r[~np.isnan(r)]
    assert ((valid >= 0) & (valid <= 100)).all()
    assert ind.rsi(np.arange(1.0, 60.0))[-1] == pytest.approx(100.0)


def test_bollinger_matches_naive(ohlc):
    _, _, close = ohlc
    mid, up, lo = ind.bollinger(close, 20, 2)
    i = 150
    w = close[i - 19 : i + 1]
    assert mid[i] == pytest.approx(w.mean())
    assert up[i] == pytest.approx(w.mean() + 2 * w.std())


def test_realized_vol_matches_naive(ohlc):
    _, _, close = ohlc
    rv = ind.realized_vol(close, 30, 252)
    rets = np.diff(np.log(close))
    i = 200
    assert rv[i] == pytest.approx(rets[i - 30 : i].std(ddof=1) * np.sqrt(252), rel=1e-6)


def test_adx_and_atr_finite(ohlc):
    h, l, c = ohlc
    a, pdi, mdi = ind.adx(h, l, c)
    assert np.isfinite(a[-1]) and 0 <= a[-1] <= 100
    assert np.isfinite(ind.atr(h, l, c)[-1])


def test_vwap_resets_per_session():
    h = l = c = np.array([10.0, 20.0, 100.0, 200.0])
    v = np.array([1.0, 1.0, 1.0, 3.0])
    out = ind.vwap(h, l, c, v, np.array([0, 0, 1, 1]))
    assert out[1] == pytest.approx(15.0)
    assert out[3] == pytest.approx((100 + 600) / 4)


def test_swing_structure():
    up_h = np.array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], float)
    assert ind.swing_structure(up_h, up_h - 1, 5) == 1
    assert ind.swing_structure(up_h[::-1], up_h[::-1] - 1, 5) == -1
