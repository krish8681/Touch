# NIFTY AI Options Engine — v1.0 Technical Specification

Status: Stages 1–3 implemented, plus Stage 4 paper trading. Live execution is implemented but turned off by default.
The Android app is specified here (§10) and is the next deliverable.

```
Kite WS/REST ─► Market Data ─► Features ─► Regime ─► Direction P(UP/SIDE/DOWN) ─► Expected move
                                                    ▲                               │
                                       News engine ─┘ (capped log-odds shift)       ▼
                    Journal ◄── Execution (paper | live) ◄── Risk engine ◄── Strategy + strike valuation
                                       │
                                       ▼
                         FastAPI (REST + WebSocket) ─► Android app
```

## 1. Package layout

| Module | Responsibility |
| --- | --- |
| `config.py` | Settings from env/.env, `RiskLimits` |
| `models.py` | Domain types: `OptionQuote`, `OptionChain`, `DirectionForecast`, `TradeProposal`, `Signal`, … |
| `data/kite.py` | Kite Connect v3 REST (session, instruments, quote, historical, orders) + binary WebSocket parser and reconnecting ticker |
| `data/instruments.py` | Instrument dump parsing; NIFTY expiries, strikes, near future |
| `data/candles.py` | Tick → 1/3/5/15-min candles aligned to 09:15; uses deltas of the cumulative volume |
| `data/chain_builder.py` | Builds the option chain from per-strike quotes; IV and Greeks per strike |
| `data/sources.py` | `KiteSource` (live), `SyntheticSource` (demo) |
| `data/synthetic.py` | Market simulator with regime switching, a volatility smile, spreads and OI |
| `analytics/greeks.py` | Black-76 price/Greeks, IV solver, P(ITM), P(touch) |
| `analytics/indicators.py` | EMA, RSI, ATR, ADX/DI, MACD, Bollinger, VWAP, realised vol, swing structure |
| `analytics/option_flow.py` | Price/OI build-up, PCR, max pain, OI support/resistance, IV skew, near-ATM writing bias |
| `engines/features.py` | Feature vector (30 features, see §4) |
| `engines/regime.py` | Rule-based regime with an auditable trend score |
| `engines/direction.py` | One softmax regression per horizon, temperature scaling, calibration reports |
| `engines/expected_move.py` | Blended volatility, expected move, distribution consistent with the forecast |
| `engines/strategy.py` | Candidate structures, model valuation, edge, R/R, liquidity, ranking |
| `engines/risk.py` | Session limits, per-trade checks, position sizing |
| `engines/news.py` | Scored news items, event windows, capped fusion |
| `engines/decision.py` | Orchestrates one decision → `Signal` |
| `execution/` | Paper broker, live broker, positions/exit rules, charges |
| `backtest/` | Historical replay, training-set builder, performance metrics |
| `storage/journal.py` | Decisions and trades in SQLite or PostgreSQL |
| `service.py` | Live loop: evaluation each minute, a position monitor every second, approvals, pub/sub |
| `api/app.py` | FastAPI endpoints for the app |

## 2. Market data

**Subscriptions (WebSocket, `full` mode):** NIFTY 50 (256265), INDIA VIX (264969), NIFTY near-month future, and CE+PE of the
nearest weekly expiry for ATM ± (`strikes_each_side` + 2) strikes. The set is re-centred when ATM moves by 2 or more strikes.

**Binary packet layouts parsed** (`data/kite.py::parse_binary`): LTP (8 bytes), index quote/full (28/32 bytes),
quote (44 bytes), full (184 bytes, including OI, timestamps and 5-level depth). Prices are divided by 100 for NSE/NFO.

**Tick schema (normalised):**
`instrument_token, last_price, timestamp, volume (cumulative day), oi, bid, ask, bid_qty, ask_qty`.

**Startup:**
1. Download the instrument dump for NFO; it changes every session.
2. Backfill 7 days of 1-minute index and futures candles (`oi=1` for futures).
3. Fetch daily VIX candles to get the previous close.
4. Subscribe.

**OI change:** Kite quotes carry no previous-day OI. The first OI seen in the session is the baseline. A better baseline is the
previous day's closing OI from historical candles with `oi=1`, and that is a planned v1.1 improvement.

**Kite constraints handled:**
- Rate limits: quote 1/s, historical 3/s, orders 10/s.
- Up to 500 instruments per `/quote` call.
- Order placement is not a fill. Every leg waits for `COMPLETE` through WebSocket order updates, with `order_history`
  polling as a fallback.
- The access token expires daily, so the user logs in once a day through `/auth/kite/login-url`.
- Kite redirects to `/auth/kite/callback` after login.

## 3. Option chain construction

For each contract: `mid = (bid+ask)/2`, falling back to the LTP.

**Forward:** use the future of the same expiry when available. Otherwise use `spot·e^{(r−q)T}`. A put-call-parity implied
forward is also available.

**Time to expiry:** calendar time until 15:30 IST on expiry day.

**IV:** Black-76 inversion (Newton, falling back to bisection). Greeks are Black-76: Δ, Γ, θ per calendar day, and vega per
vol point.

**Chain analytics:**
- PCR by OI, by OI change and by volume.
- Max pain.
- Top-3 resistance (calls at or above spot) and support (puts at or below spot), scored as
  `0.5·OI/maxOI + 0.3·ΔOI⁺/maxΔOI + 0.2·vol/maxVol`.
- ATM IV, and the 4-strike skew (put IV − call IV).
- Near-ATM writing bias: `(ΣΔOI⁺ puts − ΣΔOI⁺ calls) / total` over ATM ± 4 strikes.

## 4. Features (model inputs)

Distances are in ATR units, so the scale does not depend on the NIFTY level. "5m" means computed on 5-minute candles.

| Group | Features |
| --- | --- |
| Momentum | `ret_5, ret_15, ret_30, ret_60` — return over k minutes / (ATR₁ₘ·√k) |
| Location | `dist_vwap` (futures-volume VWAP), `dist_ema20`, `ema20_50`, `ema50_200` (5m) |
| Trend strength | `adx`, `di_diff`, `rsi`, `macd_hist`, `bb_pos`, `structure` (HH/HL = +1, LH/LL = −1) |
| Session | `or_break` (15-min opening range), `dist_pdh`, `dist_pdl`, `session_frac` |
| Volatility | `rv_ratio` (15-bar / 120-bar realised vol), `atr_pct` |
| Options | `pcr_oi`, `flow_bias`, `iv_skew`, `atm_iv`, `dist_res`, `dist_sup` |
| Futures | `fut_buildup` (price/OI quadrant over 15 min), `basis_z` |
| External | `vix_change`, `news_impact` |

## 5. Regime engine

There are eight trend components, each in −1..+1:
- VWAP
- EMA stack
- momentum
- structure
- DI
- opening range
- option flow
- futures build-up

Their weighted sum is the trend score. The classification is checked in this order:

| Regime | Condition |
| --- | --- |
| EVENT | A scheduled event window is active |
| HIGH_VOLATILITY | IV percentile ≥ 85, or realised vol spike ×2.2 |
| STRONG_BULLISH / STRONG_BEARISH | \|trend\| ≥ 0.45, ADX ≥ 22, and ≥ 5 of 8 groups aligned |
| MODERATE_BULLISH / MODERATE_BEARISH | \|trend\| ≥ 0.25, and ≥ 4 groups aligned |
| RANGE | \|trend\| < 0.18, ADX < 20, and \|dist_vwap\| < 0.8 ATR |
| UNCERTAIN | Anything else. No trade. |

## 6. Direction probability engine

**Horizons:** 15, 30 and 60 minutes, plus end of day. Intraday horizons are capped at the session close.

**Labels:** UP if the move is greater than `0.5·ATR₁ₘ·√h`. DOWN if it is less than the negative of that. Otherwise SIDEWAYS.

**Model:** one multinomial logistic regression per horizon, with standardised features and L2 regularisation, trained with Adam.

**Calibration:**
- The split is time-ordered: the first 75% is for training and the last 25% is for validation. There is no shuffling.
- A single temperature is fitted on the validation slice by minimising log loss.
- The training report records Brier score against a base-rate baseline, log loss, ECE and a reliability table for UP.

**Safety rules:**
- If a horizon's model does **not beat the base-rate Brier score**, it outputs base rates for that horizon. Base rates have no
  directional edge, so they never pass the probability gate.
- With no trained model, a conservative heuristic prior is used (capped at about 62%) and flagged `calibrated=false`.
- The risk engine refuses **live** trading on uncalibrated forecasts.

**News fusion:**
- `impact = tanh(Σ direction·severity·relevance·reliability·0.5^{age/45min})`
- This shifts the UP-vs-DOWN log-odds by at most ±0.35·2 and leaves SIDEWAYS unchanged.
- A single headline cannot flip the market-structure view.

## 7. Expected move, valuation and strike scoring

**Volatility:**
- `σ_ann = 0.4·ATM IV + 0.4·realised(15) + 0.2·ATR-implied`
- `σ_points(h) = spot·σ_ann·√(h / (252·375))`

**Expected move:**
- `up = σ + 0.8σ(p_up − p_down)`
- `down = σ − 0.8σ(p_up − p_down)`

**Forecast-consistent distribution:** take a driftless normal grid (241 points, ±5σ). Reweight its UP, SIDEWAYS and DOWN regions,
using the same thresholds as the labels, so that each region's mass equals the model's probability.

**Structures** are all defined-risk. A naked short is never generated.

| Regime | Allowed |
| --- | --- |
| Strong bull | Long CE (ATM−2…ATM+4), bull call spread (width 50–200) |
| Moderate bull | Bull call spread |
| Strong bear | Long PE, bear put spread |
| Moderate bear | Bear put spread |
| Range | Iron condor: shorts about 1–3σ out, wings 100–200 |
| High volatility | Spreads or an iron condor only |
| Event / uncertain | Nothing |

**Valuation of each candidate** at horizon h:
- Reprice every leg with Black-76 at `T − h` on each grid price, keeping IV per strike (sticky strike).
- Long legs exit at value − ½ spread. Short legs exit at value + ½ spread.
- `entry` is ask for buys and bid for sells.

**Formulas:**
- `PnL(S) = value(S) − entry`
- `model_value = entry + E[PnL]`
- `edge% = E[PnL] / max_loss × 100`
- `P(profit) = P(PnL > 0)`
- `liquidity = min over legs of [0.5·(1 − spread%/max) + 0.25·min(1, vol/10·minVol) + 0.25·min(1, OI/10·minOI)]`
- `score = edge%·(0.5 + 0.5·liquidity) + 2·min(R/R, 3)`

**Target and stop for directional structures:**
- Target is the value after the favourable 1σ expected move, capped at 90% of max profit.
- Stop is the value after an adverse 0.75σ move.
- The stop's loss is clamped to between 20% and 60% of max loss.

**Target and stop for condors:** take profit at 50% of the credit. Stop at a loss equal to the credit.

## 8. Risk engine

Session gates:
- Kill switch, which persists across the day roll.
- No trades before 09:20 or after 15:00. Square-off at 15:15.
- At most 5 trades per day.
- Daily loss limit of 2% of capital.
- Stop for the day after 3 consecutive losses.
- At most 2 open positions.
- Event windows block new trades.

Per-trade checks:
- Direction probability ≥ 55%. Condors instead need SIDEWAYS ≥ 40%.
- Edge ≥ 2%.
- R/R ≥ 1.2.
- Every leg: spread ≤ 3%, volume ≥ 1,000, OI ≥ 10,000.
- Live trading requires a calibrated model.

**Sizing:**
- `lots = ⌊budget / (risk_per_unit × lot_size)⌋`
- `budget = min(1% of capital, remaining daily loss allowance)`
- `risk_per_unit = min(max_loss, 1.5 × stop distance)`

## 9. Execution

**Paper:**
- Fills at the touch ± 1 tick of slippage.
- Exits on target, stop, time (2× horizon), square-off (15:15), manual close or the kill switch.
- Charges cover brokerage, STT, exchange fees, SEBI fees, stamp duty and GST (`execution/charges.py`; review the rates
  periodically).

**Live** (`EXECUTION_MODE=live` **and** `LIVE_TRADING_ENABLED=true`):
- LIMIT orders only, priced 2 ticks through the touch.
- Long legs are bought first so a short leg is never naked.
- If a later leg fails, the filled legs are unwound.
- Exits buy back shorts first.
- With `APPROVAL_MODE=manual`, every signal becomes a pending approval that expires after 120 seconds.
- Risk gates are re-checked when the approval arrives.

## 10. Android app (next deliverable)

Kotlin + Jetpack Compose, using Retrofit/OkHttp for REST and an OkHttp WebSocket for `/ws`. The app holds only the server URL and
the app token, stored in EncryptedSharedPreferences. **No Kite key or secret ever goes in the APK.**

| Screen | Content | Endpoints |
| --- | --- | --- |
| Dashboard | Spot, VWAP, regime and confidence, direction matrix (4 horizons), expected move, TRADE/NO TRADE with reasons and blocks | `/ws` (`signal`), `/signal/latest` |
| Trade proposal | Legs, net premium, max profit/loss, target/stop, P(profit), edge, R/R, liquidity, lots. **Approve / Reject** | `/ws` (`approval_required`), `/approvals/*` |
| Option chain | Strike ladder: CE/PE LTP, IV, Δ, OI, ΔOI, volume, support/resistance markers | `/option-chain` |
| Positions | Open positions with live mark and unrealised P&L. Close button | `/positions`, `/positions/{id}/close`, `/ws` |
| History | Trades, net P&L, expectancy, win rate, drawdown | `/trades`, `/signals` |
| Risk | Limits and today's state. **Kill switch** (with "flatten all") | `/risk`, `/risk/kill-switch` |
| Settings / Login | Server URL, token, daily Kite login (opens the login URL in a Custom Tab) | `/auth/kite/login-url` |

## 11. REST / WebSocket API

All endpoints except `/health` and `/auth/kite/callback` require `Authorization: Bearer <APP_API_TOKEN>`. The WebSocket uses
`/ws?token=…`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/health` | Liveness, mode, data source |
| GET | `/auth/kite/login-url` | URL for the daily Kite login |
| GET | `/auth/kite/callback` | Kite redirect target; exchanges the request token and starts the live data source |
| GET | `/signal/latest` | Most recent `Signal` |
| POST | `/signal/evaluate` | Force a decision now |
| GET | `/signals?limit=` | Recent decisions from the journal |
| GET | `/option-chain` | Current chain with IV and Greeks |
| GET | `/positions?open_only=` | Paper and live positions |
| POST | `/positions/{id}/close` | Manual exit |
| GET | `/trades` | Closed and open trades from the journal |
| GET | `/approvals` | Pending approvals (live, manual mode) |
| POST | `/approvals/{id}/approve` | Approve a pending trade |
| POST | `/approvals/{id}/reject` | Reject a pending trade |
| GET | `/risk` | Limits and session state |
| POST | `/risk/kill-switch` | Body `{on, flatten}` |
| POST | `/news` | Add a scored news item |
| POST | `/events` | Add an event window (RBI, Fed, CPI, Budget…) |
| GET | `/model` | Training and calibration reports |
| WS | `/ws` | Pushes `signal`, `approval_required`, `position_opened`, `position_closed` and `risk` messages |

## 12. Database

**`decisions`** — one row per evaluation:
- `ts`, `spot`, `regime`, `action`, `strategy`, `model_calibrated`
- `features` (JSON), `forecasts` (JSON), `signal` (JSON)

This table feeds the closed loop. Join each prediction with the realised move, then re-train and re-check calibration.

**`trades`** — one row per position:
- `mode`, `decision_id`, `strategy`, `lots`, `lot_size`
- `opened_at`, `closed_at`, `entry_value`, `exit_value`
- `gross_pnl`, `charges`, `net_pnl`, `exit_reason`
- `proposal` (JSON), `fills` (JSON, including order IDs)

## 13. Historical replay and training

`ReplayEngine` walks 1-minute data and drives exactly the same `DecisionEngine`, `PaperBroker` and `RiskEngine` code as the live
service. `build_training_set` produces features and labels from the same feature code, so training and inference cannot drift
apart.

**Historical data: DhanHQ** (`data/dhan.py`, `data/dhan_store.py`). Kite generally does not serve expired option contracts,
so history comes from Dhan.

Endpoints:
- `POST /v2/charts/rollingoption`: NSE_FNO, OPTIDX, securityId 13, WEEK, expiryCode 1. It covers strikes ATM−10…ATM+10 for
  CALL and PUT, at most 30 days per call, about 5 years back.
- `POST /v2/charts/intraday`: IDX_I/INDEX 13 for the spot candles.

Downloads:
- Saved as gzipped CSV chunks with a manifest, and they resume after an interruption.
- Request rate is throttled to 4/s.
- Retries on 805 / DH-904 / 5xx.

`DhanChainProvider` rebuilds each minute's `OptionChain` from the last completed bar of every strike:
- Quotes older than 5 minutes are dropped.
- Volume is accumulated through the day, and OI change is measured from the session's first OI, matching the live source.
- The spread is assumed: ±`spread_bps`/2 around the close, at least one tick.

**Expiry resolution:** the API returns no expiry date. For each day, the resolver builds candidates:
- The next 3 weekly expiries: Thursday until 31 Aug 2025, Tuesday from 1 Sep 2025.
- Each moved to the previous trading day if the exchange was closed.

It then picks the candidate whose Black-76 IV of the ATM bars matches Dhan's `iv` field best. This also detects whether the IV
is in percent, and whether `expiryCode` meant the near or the next expiry. `dhan-check` reports coverage, the method used and
the IV error per day.

Models trained on Dhan data record `uses_futures=false`. The decision engine then drops futures-based features live as well,
so training and inference match.

The synthetic market only validates plumbing. Its P&L numbers say nothing about real-market edge.

## 14. Promotion path to live

1. Download Dhan history (`dhan-download`, `dhan-check`). Train on the older years and backtest on the most recent year only.
2. Every horizon must beat its base rate on out-of-sample data, with ECE < 0.05.
3. Paper trade for at least 4 weeks. Compare paper expectancy (after charges) with the backtest. Investigate slippage.
4. Live with `APPROVAL_MODE=manual` and 1 lot.
5. Only then consider `APPROVAL_MODE=auto`.
