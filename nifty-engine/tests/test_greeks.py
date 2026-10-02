import math

import pytest

from nifty_engine.analytics.greeks import (
    black76_greeks,
    black76_price,
    black76_price_vec,
    implied_vol,
    norm_cdf,
    norm_cdf_vec,
    prob_touch,
)
from nifty_engine.models import OptionType

F, K, T, R = 25_000.0, 25_100.0, 5 / 365, 0.065


def test_put_call_parity():
    c = black76_price(F, K, T, R, 0.14, OptionType.CE)
    p = black76_price(F, K, T, R, 0.14, OptionType.PE)
    assert c - p == pytest.approx(math.exp(-R * T) * (F - K), abs=1e-8)


@pytest.mark.parametrize("ot", [OptionType.CE, OptionType.PE])
@pytest.mark.parametrize("k", [24_500, 25_000, 25_600])
@pytest.mark.parametrize("vol", [0.08, 0.15, 0.45])
def test_implied_vol_roundtrip(ot, k, vol):
    price = black76_price(F, k, T, R, vol, ot)
    assert implied_vol(price, F, k, T, R, ot) == pytest.approx(vol, abs=1e-4)


def test_implied_vol_rejects_arbitrage():
    assert implied_vol(0.0, F, K, T, R, OptionType.CE) is None
    assert implied_vol(F * 2, F, K, T, R, OptionType.CE) is None


def test_greeks_match_finite_differences():
    g = black76_greeks(F, K, T, R, 0.15, OptionType.CE)
    h = 1.0
    up = black76_price(F + h, K, T, R, 0.15, OptionType.CE)
    dn = black76_price(F - h, K, T, R, 0.15, OptionType.CE)
    assert g.delta == pytest.approx((up - dn) / (2 * h), rel=1e-4)
    assert g.gamma == pytest.approx((up - 2 * g.price + dn) / h**2, rel=1e-3)
    v_up = black76_price(F, K, T, R, 0.16, OptionType.CE)
    assert g.vega == pytest.approx(v_up - g.price, rel=2e-2)
    dt = 0.01 / 365
    later = black76_price(F, K, T - dt, R, 0.15, OptionType.CE)
    assert g.theta == pytest.approx((later - g.price) * 100, rel=1e-2)
    assert g.theta < 0


def test_vectorised_matches_scalar():
    import numpy as np

    fs = np.array([24_000.0, 25_000.0, 26_000.0])
    for ot in OptionType:
        vec = black76_price_vec(fs, K, T, R, 0.15, ot)
        for f, v in zip(fs, vec):
            assert v == pytest.approx(black76_price(f, K, T, R, 0.15, ot), abs=0.02)
    xs = np.linspace(-4, 4, 41)
    assert np.allclose(norm_cdf_vec(xs), [norm_cdf(x) for x in xs], atol=2e-7)


def test_prob_touch_is_roughly_twice_prob_itm():
    p = prob_touch(25_000, 25_300, T, 0.15)
    assert 0 < p < 1
    assert prob_touch(25_000, 25_000.0001, T, 0.15) == pytest.approx(1.0, abs=1e-3)
