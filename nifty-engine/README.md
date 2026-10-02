# NIFTY AI Options Engine v1.0

This is the backend decision and trading engine for NIFTY options, using Kite Connect for market data and execution. It produces:

> market regime → P(UP / SIDEWAYS / DOWN) for 15m / 30m / 60m / EOD → expected move → model value of each strike and structure →
> strategy → entry / target / stop / lots → risk gate → **TRADE / NO TRADE**

It also includes a paper broker, an optional live broker (off by default), historical replay, model training with calibration
reports, a decision and trade journal, and a FastAPI REST + WebSocket API for the Android app.

The full design is in **[docs/SPEC.md](docs/SPEC.md)**: data schema, features, formulas, risk rules, API and Android screens.

> ⚠️ This is decision-support software, not investment advice. Options trading can lose money quickly. Live trading is disabled
> unless you explicitly enable it. Every safety switch is described below.

## Quick start (no Kite account needed)

```bash
cd nifty-engine
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
pytest -q                                   # 67 tests

python -m nifty_engine signal               # one full decision on synthetic data, printed as JSON
python -m nifty_engine backtest --days 20   # replay the synthetic market with paper trades + metrics
python -m nifty_engine train --days 120     # train + calibrate the direction model, save models/direction_model.json

cp .env.example .env                        # set APP_API_TOKEN
python -m nifty_engine serve                # http://127.0.0.1:8000/docs
```

With `DATA_SOURCE=synthetic`, the server runs on a simulated market that advances one minute every 2 seconds. This lets you build
and test the Android app before connecting Kite.

Synthetic backtests only prove that the pipeline works. They say nothing about real-market profitability.

## Connecting Kite

1. Create a Kite Connect app. Set its redirect URL to `https://<your-server>/auth/kite/callback`.
2. In `.env`, set `KITE_API_KEY`, `KITE_API_SECRET`, `DATA_SOURCE=kite`.
3. Log in every trading day; Kite access tokens expire daily:
   1. Call `GET /auth/kite/login-url` from the app.
   2. Open the URL and log in.
   3. Kite redirects to the callback. The server then exchanges the token, downloads the day's instrument dump, backfills
      candles and starts streaming.

The API secret and access token live only on the server. The Android app holds just the server URL and `APP_API_TOKEN`.

## Safety switches

| Setting | Default | Effect |
| --- | --- | --- |
| `EXECUTION_MODE` | `paper` | `live` is required for real orders… |
| `LIVE_TRADING_ENABLED` | `false` | …and so is this |
| `APPROVAL_MODE` | `manual` | Live signals wait for Approve in the app. They expire after 120 s, and risk is re-checked on approval |
| Model calibration | — | Live trading refuses forecasts from an untrained or uncalibrated model |
| Kill switch | off | `POST /risk/kill-switch {"on": true, "flatten": true}` blocks new trades and closes everything |
| Risk limits | `config.RiskLimits` | 1% risk/trade, 2% daily loss, 5 trades/day, stop after 3 losses, spread/volume/OI filters, 09:20–15:00 entries, 15:15 square-off |

Only defined-risk structures are generated: long CE/PE, debit spreads and iron condors. Naked option selling is never proposed.

## Layout

```
nifty_engine/
  data/        Kite REST + WebSocket, instruments, candles, chain builder, synthetic market, live sources
  analytics/   Black-76 Greeks/IV, indicators, option-chain flow (OI, PCR, max pain, S/R)
  engines/     features, regime, direction (calibrated), expected move, strategy/strike, risk, news, decision
  execution/   paper broker, live broker, positions & exits, charges
  backtest/    replay engine, training-set builder, metrics
  storage/     decision & trade journal (SQLite/PostgreSQL)
  api/         FastAPI app
  service.py   live loop: evaluate every minute, monitor positions every second
docs/SPEC.md   v1.0 technical specification
```

## Roadmap

- **Stage 1–3** (market intelligence, prediction, option selection): done.
- **Stage 4**:
  - Paper trading and gated live execution: done.
  - Real-data training: needs recorded chain history (SPEC §13–14).
- **Android app** (Kotlin + Compose): next. Its screens and API contract are in SPEC §10–11.
- **v1.1**:
  - Chain-snapshot recorder.
  - Previous-day OI baseline from historical data.
  - Gradient-boosted model option.
  - Automated news scoring feed.
