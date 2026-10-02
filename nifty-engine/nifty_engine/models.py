"""Core domain objects shared by every engine."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from enum import Enum
from typing import Any


class Direction(str, Enum):
    UP = "UP"
    SIDEWAYS = "SIDEWAYS"
    DOWN = "DOWN"


class Regime(str, Enum):
    STRONG_BULLISH = "STRONG_BULLISH"
    MODERATE_BULLISH = "MODERATE_BULLISH"
    STRONG_BEARISH = "STRONG_BEARISH"
    MODERATE_BEARISH = "MODERATE_BEARISH"
    RANGE = "RANGE"
    HIGH_VOLATILITY = "HIGH_VOLATILITY"
    EVENT = "EVENT"
    UNCERTAIN = "UNCERTAIN"


class OptionType(str, Enum):
    CE = "CE"
    PE = "PE"


class Side(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


@dataclass
class Tick:
    instrument_token: int
    last_price: float
    timestamp: datetime
    volume: int = 0
    oi: int = 0
    bid: float = 0.0
    ask: float = 0.0
    bid_qty: int = 0
    ask_qty: int = 0


@dataclass
class Candle:
    start: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0
    oi: float = 0.0


@dataclass
class Instrument:
    instrument_token: int
    tradingsymbol: str
    name: str
    exchange: str
    segment: str
    instrument_type: str          # EQ / FUT / CE / PE / INDEX
    expiry: date | None = None
    strike: float = 0.0
    lot_size: int = 1
    tick_size: float = 0.05


@dataclass
class OptionQuote:
    strike: float
    option_type: OptionType
    tradingsymbol: str
    instrument_token: int
    ltp: float
    bid: float
    ask: float
    volume: int = 0
    oi: int = 0
    oi_change: int = 0
    iv: float | None = None
    delta: float | None = None
    gamma: float | None = None
    theta: float | None = None      # per calendar day, in premium points
    vega: float | None = None       # per 1 vol point (0.01)

    @property
    def mid(self) -> float:
        if self.bid > 0 and self.ask > 0:
            return (self.bid + self.ask) / 2
        return self.ltp

    @property
    def spread_pct(self) -> float:
        mid = self.mid
        if mid <= 0 or self.bid <= 0 or self.ask <= 0:
            return 100.0
        return (self.ask - self.bid) / mid * 100


@dataclass
class OptionChain:
    underlying: str
    spot: float
    future: float
    expiry: date
    timestamp: datetime
    time_to_expiry_years: float
    calls: dict[float, OptionQuote] = field(default_factory=dict)
    puts: dict[float, OptionQuote] = field(default_factory=dict)

    @property
    def strikes(self) -> list[float]:
        return sorted(set(self.calls) | set(self.puts))

    def atm_strike(self, step: int = 50) -> float:
        return round(self.spot / step) * step

    def get(self, strike: float, option_type: OptionType) -> OptionQuote | None:
        book = self.calls if option_type == OptionType.CE else self.puts
        return book.get(strike)


@dataclass
class DirectionForecast:
    horizon_minutes: int        # 0 means end of day
    up: float
    sideways: float
    down: float
    calibrated: bool            # False when the heuristic prior is used (no trained model yet)

    @property
    def label(self) -> Direction:
        best = max((self.up, Direction.UP), (self.sideways, Direction.SIDEWAYS), (self.down, Direction.DOWN))
        return best[1]


@dataclass
class ExpectedMove:
    horizon_minutes: int
    sigma_points: float         # one standard deviation of the underlying move
    up_points: float            # expected favourable excursion
    down_points: float


@dataclass
class Leg:
    side: Side
    option_type: OptionType
    strike: float
    tradingsymbol: str
    instrument_token: int
    price: float                # price we expect to transact at (ask for buys, bid for sells)
    lots: int = 1


@dataclass
class TradeProposal:
    strategy: str
    legs: list[Leg]
    net_premium: float          # per unit, positive = debit paid
    max_profit: float | None    # per unit; None = uncapped
    max_loss: float             # per unit
    target: float               # exit value of the position, per unit
    stop: float
    probability: float          # model probability the structure reaches target before stop
    model_value: float          # model expected value of the position at horizon, per unit
    edge_pct: float             # (model_value - net_premium) / net_premium * 100
    reward_risk: float
    liquidity_score: float      # 0..1
    lots: int = 0
    lot_size: int = 65
    horizon_minutes: int = 60
    notes: list[str] = field(default_factory=list)

    @property
    def capital_at_risk(self) -> float:
        return self.max_loss * self.lots * self.lot_size


@dataclass
class Signal:
    timestamp: datetime
    spot: float
    regime: Regime
    regime_confidence: float
    forecasts: list[DirectionForecast]
    expected_moves: list[ExpectedMove]
    action: str                         # TRADE | NO_TRADE
    proposal: TradeProposal | None
    reasons: list[str]
    blocks: list[str]
    context: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return _jsonable(asdict(self))


def _jsonable(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {str(k): _jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_jsonable(v) for v in obj]
    if isinstance(obj, Enum):
        return obj.value
    if isinstance(obj, (datetime, date)):
        return obj.isoformat()
    if isinstance(obj, float):
        return round(obj, 6)
    return obj
