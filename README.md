# NIFTY Direction Engine v3 — Android

A **NIFTY market-intelligence and probability engine** for Android. It answers four questions in order:

1. What is driving NIFTY?
2. Which regime are we in?
3. What is the probability distribution (Bull / Bear / Range) and the expected magnitude of the next move?
4. Given that, which option (if any) has the best risk/reward — or is the answer **no trade**?

> Decision support only — not investment advice. Weights are starting engineering values, not validated
> optima; use the prediction log and replay tools to measure before risking capital.

**Install:** `release/NiftyDirectionEngine-v3.1.0.apk` (Android 8.0+, sideload / "install unknown apps").
It opens in **Simulator** mode (synthetic data, works offline/after hours). Switch to live data in **Setup**.

## Project layout

```
engine/   pure Kotlin/JVM — all 18 modules, no Android deps (can be moved to a backend unchanged)
app/      Android (Jetpack Compose): live data feeds, storage, UI, notifications
release/  prebuilt APK
```

### Engine modules (`engine/src/main/kotlin/com/niftyengine/engine/engines`)

| # | Module | What it does |
|---|--------|--------------|
| 01 | `DataCollector` | `SnapshotProvider` interface + data-coverage health |
| 02 | `DataNormalizer` | raw levels → 1m/5m/15m/30m/1h/1D changes, acceleration, percentile, z-score; merges feed bars with the engine's own ticks |
| 02b | `MarketStructureEngine` | price pressure, VWAP, EMA20/50, opening range, prev-day levels, RSI/ADX/ATR as *confirmation* |
| 03 | `NiftyWeightEngine` | stock return × free-float weight (live from NSE `ffmc`), top-5/top-10 contribution, **fake-breadth** detection |
| 04 | `SectorEngine` | sector return, momentum, breadth, relative strength, contribution, `SECTOR_ALIGNMENT` |
| 05 | `BreadthEngine` | A/D, equal-weight vs index, % above open, short-term momentum breadth |
| 06 | `FuturesPositionEngine` | long buildup / short buildup / short covering / long unwinding (day + last 30 min), basis |
| 07 | `OptionsPositionEngine` | call/put walls, ΔOI writing near ATM, OI migration, IV skew, PCR (one feature only), max pain |
| 08 | `VIXEngine` | falling/stable/rising/spiking; mainly a volatility-regime input |
| 09 | `GlobalRiskEngine` | one `GLOBAL_RISK_SCORE` from US futures, Asia, Europe, US VIX, US10Y, DXY, gold |
| 10 | `IndiaMacroEngine` + `FlowEngine` | fast (USDINR, crude, yields, liquidity) vs capped slow macro; FPI/DII with DII as counterweight |
| 11 | `NewsEventEngine` | dedup/cluster across outlets, event type, expected/actual/surprise, sectors, duration, exponential decay, **market-reaction & divergence** |
| 12 | `MarketRegimeEngine` | R1–R10 incl. event shock, divergence, transition (uses regime history) |
| 13 | `DirectionProbabilityEngine` | regime-adaptive weights (§20/§21), driver persistence, conflict damping, softmax → P(bull/bear/range); confidence separate from probability |
| 14 | `ExpectedMoveEngine` | blend of VIX, ATM IV, realised & historical vol × event multiplier → σ, central move, range |
| 15 | `OptionSelectionEngine` | ITM→OTM candidates, Black-Scholes repricing under bull/bear/range scenarios, P(profit), P(touch), EV, liquidity/IV/theta/execution factors, hard filters |
| 16 | `TradeDecisionEngine` | TRADE / WAIT / NO TRADE with every check shown |
| 17 | `PredictionLogger` | logs each prediction, attaches 15/30/60-min outcomes (incl. option price), accuracy, Brier score, calibration buckets, per-driver hit rates |
| 18 | `HistoricalReplayEngine` | Mode A market-only / Mode B full information, strict point-in-time (no future bars/news) |

`NiftyDirectionEngine` orchestrates one cycle; `sim/SimulatedMarket` generates a consistent synthetic market for demo/tests.

## Data sources (app)

**Kite Connect mode (recommended)** — with a Kite Connect subscription:

| Data | From Kite | Gap-filled by |
|------|-----------|---------------|
| NIFTY, Bank Nifty, India VIX, sector indices, all 50 stocks | one `/quote` call per cycle | — |
| Near-month futures price, OI, volume | `/quote` | — |
| Futures OI history (1-min, today) + previous-day OI | historical API with `oi=1` | — |
| Option chain (nearest expiry, ATM ±20 strikes): LTP, best bid/ask, OI, volume | `/quote` + NFO instrument dump | IV implied from prices (Black-Scholes); ΔOI from NSE's chain, else change since first fetch today |
| 1-min NIFTY & VIX bars, 1-year daily history | historical API | — |
| Free-float index weights | — | NSE (refreshed every 6 h) |
| FII/DII flows | — | NSE |
| Global markets, news | — | Yahoo, RSS |

Setup: in developers.kite.trade set any redirect URL for your app (e.g. `https://127.0.0.1/kite`; the app
intercepts it), enter the API key and secret in **Setup**, tap **Login to Kite**. Access tokens expire daily
(~06:00 IST); the app shows a banner when today's login is needed and falls back to NSE/Yahoo meanwhile. The
API secret stays in the app's private storage on the phone.
Rate limits are respected: one quote request per cycle (Kite allows 1/s), historical requests once a minute,
sequentially (limit 3/s), instrument dump once a day (cached).

**Public mode** (no Kite):

| Feed | Source | Notes |
|------|--------|-------|
| NIFTY, Bank Nifty, VIX, sector indices | NSE `allIndices` | |
| 50 constituents + free-float mcap | NSE `getIndicesData` (legacy endpoint fallback) | live index weights |
| Option chain | NSE `option-chain-v3` (legacy fallback) | OI, ΔOI, volume, IV, LTP, bid/ask |
| Futures price/OI/ΔOI | NSE `getSymbolDerivativesData` | |
| FII/DII | NSE `fiidiiTradeReact` | previous session |
| Intraday bars / daily history | Yahoo chart API, NSE `getIndexChart` fallback | |
| Global markets | Yahoo (ES=F, NQ=F, Nikkei, HSI, DXY, US10Y, Brent, gold, USDINR…) | |
| News | RSS: ET Markets, Moneycontrol, Livemint, Business Standard, Google News | |
| Slow macro (CPI, GDP, PMI, repo…) | entered in Setup | capped bias only |

NSE/Yahoo are public website endpoints: they can be delayed, rate-limited or changed without notice. Each feed's
status is shown on the Home tab.

## App tabs

Home (probabilities, confidence, regime, expected move, decision + checks, option candidate) · Drivers (weighted
driver bars, agreement/conflict, every engine signal) · Options (ranked strikes) · Market (heavyweights, sectors,
futures, VIX, global, macro, flows) · News (clustered events with decay/reaction) · Log (live performance,
calibration, replay, export) · Setup.

The engine refreshes while the app is open (default 30 s live / 2 s per simulated minute). Live sessions are
recorded for replay; predictions are logged every 5 minutes during market hours.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew :engine:test            # engine unit tests (simulated session, replay no-look-ahead, news dedup, BS parity…)
./gradlew :app:assembleDebug      # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest --tests '*KiteDataTest*'   # Kite client vs mock server + real NFO instrument dump
./gradlew :app:testDebugUnitTest -DliveNetwork=true --tests '*LiveFeedTest*'   # hits real NSE endpoints
```

## Roadmap (spec stages 2–3)

Use the logged predictions to tune weights/thresholds and calibrate probabilities; then add a tree-based model
(XGBoost/LightGBM) as an ensemble member next to the rule engine. The engine module is plain Kotlin, so it can
also run on a backend with the app as a thin client.
