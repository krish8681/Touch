"""Approximate Zerodha F&O options transaction costs (per order leg).

Rates change; keep them in one place and review against the broker's
published charges before relying on P&L numbers.
"""

from __future__ import annotations

from dataclasses import dataclass

from ..models import Side


@dataclass
class ChargeRates:
    brokerage_per_order: float = 20.0
    stt_sell_pct: float = 0.1            # on option premium, sell side
    exchange_txn_pct: float = 0.03503    # NSE options, on premium
    sebi_per_crore: float = 10.0
    stamp_buy_pct: float = 0.003
    gst_pct: float = 18.0                # on brokerage + exchange + SEBI fees


def leg_charges(side: Side, price: float, quantity: int, rates: ChargeRates = ChargeRates()) -> float:
    turnover = price * quantity
    brokerage = rates.brokerage_per_order if turnover > 0 else 0.0   # flat per executed option order
    stt = turnover * rates.stt_sell_pct / 100 if side == Side.SELL else 0.0
    exch = turnover * rates.exchange_txn_pct / 100
    sebi = turnover * rates.sebi_per_crore / 1e7
    stamp = turnover * rates.stamp_buy_pct / 100 if side == Side.BUY else 0.0
    gst = (brokerage + exch + sebi) * rates.gst_pct / 100
    return brokerage + stt + exch + sebi + stamp + gst
