"""Runtime configuration.

All secrets live on the backend only. Values are read from environment
variables (optionally loaded from a `.env` file) so nothing sensitive is
compiled into the Android app.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def _env_bool(name: str, default: bool) -> bool:
    return _env(name, str(default)).strip().lower() in {"1", "true", "yes", "on"}


@dataclass
class RiskLimits:
    max_risk_per_trade_pct: float = 1.0      # % of capital that a single trade may lose
    max_daily_loss_pct: float = 2.0          # stop trading for the day after this loss
    max_trades_per_day: int = 5
    max_consecutive_losses: int = 3
    max_open_positions: int = 2
    max_spread_pct: float = 3.0              # bid/ask spread as % of mid
    min_volume: int = 1000                   # contracts traded today
    min_open_interest: int = 10000
    min_direction_probability: float = 0.55
    min_edge_pct: float = 2.0                # model value vs premium, % of premium
    min_reward_risk: float = 1.2
    no_new_trades_after: str = "15:00"       # IST
    no_trades_before: str = "09:20"          # skip the opening auction noise
    square_off_at: str = "15:15"


@dataclass
class Settings:
    kite_api_key: str = ""
    kite_api_secret: str = ""
    dhan_access_token: str = ""
    dhan_client_id: str = ""
    app_api_token: str = ""
    database_url: str = "sqlite:///./nifty_engine.db"
    execution_mode: str = "paper"            # paper | live
    live_trading_enabled: bool = False
    approval_mode: str = "manual"            # manual | auto
    data_source: str = "synthetic"           # synthetic | kite
    capital: float = 500_000.0
    lot_size: int = 65                       # NIFTY lot size; refreshed from the Kite instrument dump when live
    strike_step: int = 50
    risk_free_rate: float = 0.065
    dividend_yield: float = 0.012
    strikes_each_side: int = 10              # option chain width around ATM
    model_path: str = "models/direction_model.json"
    risk: RiskLimits = field(default_factory=RiskLimits)

    @property
    def live_allowed(self) -> bool:
        return self.execution_mode == "live" and self.live_trading_enabled

    @classmethod
    def from_env(cls, dotenv: Path | None = Path(".env")) -> "Settings":
        if dotenv is not None:
            _load_dotenv(dotenv)
        return cls(
            kite_api_key=_env("KITE_API_KEY", ""),
            kite_api_secret=_env("KITE_API_SECRET", ""),
            dhan_access_token=_env("DHAN_ACCESS_TOKEN", ""),
            dhan_client_id=_env("DHAN_CLIENT_ID", ""),
            app_api_token=_env("APP_API_TOKEN", ""),
            database_url=_env("DATABASE_URL", "sqlite:///./nifty_engine.db"),
            execution_mode=_env("EXECUTION_MODE", "paper").lower(),
            live_trading_enabled=_env_bool("LIVE_TRADING_ENABLED", False),
            approval_mode=_env("APPROVAL_MODE", "manual").lower(),
            data_source=_env("DATA_SOURCE", "synthetic").lower(),
            capital=float(_env("CAPITAL", "500000")),
            model_path=_env("MODEL_PATH", "models/direction_model.json"),
        )
