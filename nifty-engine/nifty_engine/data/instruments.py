"""Kite instrument dump parsing and NIFTY contract selection.

The dump changes every session (new weeklies, expired contracts), so it must
be downloaded once per trading day before subscribing.
"""

from __future__ import annotations

import csv
import io
from datetime import date, datetime

from ..models import Instrument, OptionType

NIFTY_INDEX_TOKEN = 256265      # NSE:NIFTY 50
INDIA_VIX_TOKEN = 264969        # NSE:INDIA VIX
BANKNIFTY_INDEX_TOKEN = 260105  # NSE:NIFTY BANK


def parse_instruments_csv(text: str) -> list[Instrument]:
    out = []
    for row in csv.DictReader(io.StringIO(text)):
        expiry = None
        if row.get("expiry"):
            expiry = datetime.strptime(row["expiry"], "%Y-%m-%d").date()
        out.append(
            Instrument(
                instrument_token=int(row["instrument_token"]),
                tradingsymbol=row["tradingsymbol"],
                name=row.get("name", "").strip('"'),
                exchange=row["exchange"],
                segment=row["segment"],
                instrument_type=row["instrument_type"],
                expiry=expiry,
                strike=float(row.get("strike") or 0),
                lot_size=int(float(row.get("lot_size") or 1)),
                tick_size=float(row.get("tick_size") or 0.05),
            )
        )
    return out


class NiftyUniverse:
    """Index of NIFTY futures and options from the instrument dump."""

    def __init__(self, instruments: list[Instrument], underlying: str = "NIFTY"):
        self.underlying = underlying
        self.options = [
            i for i in instruments
            if i.name == underlying and i.segment == "NFO-OPT" and i.instrument_type in ("CE", "PE")
        ]
        self.futures = sorted(
            (i for i in instruments if i.name == underlying and i.segment == "NFO-FUT"),
            key=lambda i: i.expiry or date.max,
        )
        self._by_token = {i.instrument_token: i for i in self.options + self.futures}

    def by_token(self, token: int) -> Instrument | None:
        return self._by_token.get(token)

    def expiries(self, today: date) -> list[date]:
        return sorted({i.expiry for i in self.options if i.expiry and i.expiry >= today})

    def nearest_expiry(self, today: date, skip_expiry_day: bool = False) -> date:
        exps = self.expiries(today)
        if not exps:
            raise ValueError("no live NIFTY option expiries in the instrument dump")
        if skip_expiry_day and exps[0] == today and len(exps) > 1:
            return exps[1]
        return exps[0]

    def near_future(self, today: date) -> Instrument | None:
        for f in self.futures:
            if f.expiry and f.expiry >= today:
                return f
        return None

    @property
    def lot_size(self) -> int:
        return self.options[0].lot_size if self.options else 75

    def chain_instruments(self, expiry: date, atm: float, step: int, each_side: int) -> list[Instrument]:
        lo, hi = atm - each_side * step, atm + each_side * step
        return sorted(
            (i for i in self.options if i.expiry == expiry and lo <= i.strike <= hi),
            key=lambda i: (i.strike, i.instrument_type),
        )

    def option(self, expiry: date, strike: float, option_type: OptionType) -> Instrument | None:
        for i in self.options:
            if i.expiry == expiry and i.strike == strike and i.instrument_type == option_type.value:
                return i
        return None
