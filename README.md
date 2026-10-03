# NIFTY Direction Engine v4.1 — Android

A **NIFTY market-intelligence and probability engine** for Android. It answers four questions in order:

1. What is driving NIFTY?
2. Which regime are we in?
3. What is the probability distribution (Bull / Bear / Range) and the expected magnitude of the next move?
4. Given that, which option (if any) has the best risk/reward — or is the answer **no trade**?

> Decision support only — not investment advice. Weights are starting engineering values, not validated
> optima; use the prediction log and replay tools to measure before risking capital.

**Install:** `release/NiftyDirectionEngine-v4.1.0.apk` (Android 8.0+, sideload / "install unknown apps").
It opens in **Simulator** mode (synthetic data, works offline/after hours). Switch to live data in **Setup**.

## v4.1 — GIFT Nifty opening factor (`E21_GiftNiftyEngine`)

GIFT Nifty (NSE IX near-month NIFTY futures, trading ~06:30–02:45 IST) is used as an **opening** factor, not an all-day predictor:

| Phase | Role |
|-------|------|
| Before 09:15 | **Implied gap** = GIFT vs the NSE near-month futures close (same contract ⇒ no basis error; falls back to GIFT's own day change on an expiry mismatch). Implied open = NIFTY's latest close × (1 + gap). Driver `GIFT_NIFTY` (weight 8–10, confidence 0.8 when the quote is ≤ 60 min old). |
| 09:15 → 10:15 | The pre-open implied gap is **frozen** (GIFT keeps trading, so a live recompute would show the day's move). The factor tracks gap behaviour — extending / holding / fading / filled — and its confidence decays to 0 over the first hour. A warning is raised if NIFTY opens > 0.75 % away from the implied open. |
| After 10:15 | Spent (confidence 0); intraday direction comes from the other engines. |
| Pricing-in | Before the open, GIFT's implied open is the market's reaction to overnight news (NIFTY isn't trading), so overnight events are judged priced/unpriced correctly. |
| Data quality | GIFT feed status/age shown (non-critical; quiet 02:45–06:30 is normal). |

Reference closes are chosen from each quote's timestamp (before 09:15 NSE's `previousClose` can still be the day-before's close).
Source: NSE `getGiftNifty` (works in both Kite and public modes).

## v4.0 — event intelligence (what was expected → what was priced → what changed → how the market repriced)

| # | Component | What it does |
|---|-----------|--------------|
| 1 | **Gemini event intelligence** (`app/data/GeminiEventAnalyst`) | Reads important news and returns a strict JSON description per event: type, stage, severity, direction, affected sectors/stocks, channels, expected outcome + probability, actual outcome, surprise, duration, persistence, escalation risk, confidence, horizon relevance. **No trade fields exist in the schema**, the prompt forbids advice, and extra fields are dropped. Budgeted (calls/day, min interval, 429 back-off). Without a key the rule analyst produces the same schema. |
| 2 | **Expectation state** (`E20_EventIntelligenceEngine`) | Per event: expected outcome and probability over time, actual outcome, expectation change. Surprise = the share of information not already expected (e.g. 25 bps cut at 80 % expected → surprise ≈ 0.2; a consensus event first seen carries ~no new information). |
| 3 | **Pricing-in / unpriced** | Measures how much of the new information the market has already absorbed since it arrived, on NIFTY, Bank Nifty, affected sectors, affected heavyweights, breadth, futures, options PCR, India VIX and USDINR, plus pre-information drift. **Effective impact = event impact × unpriced × surprise × confidence.** |
| 4 | **Lifecycle + clustering** | One `EVENT_ID` per real-world event across outlets, rewrites and days (token + shared-phrase similarity, plus Gemini `mergeWith`). Stages RUMOUR → POSSIBLE → LIKELY → EXPECTED → CONFIRMED → DEVELOPING/ESCALATING → RESOLVING → RESOLVED with history. Event memory persists across app restarts. |
| 5 | **Reaction confirmation** | Expected vs actual reaction per channel; if the market contradicts the reading, news confidence is cut (flag `MARKET_DISAGREES`) — the market is never forced to agree with the AI. |
| 6 | **Multi-horizon impact** | Separate impact for 5–15 min, 30–120 min, EOD, 1–3 days, 1–2 weeks (duration, persistence, stage, AI hint, per-horizon decay). The news driver uses the bucket that matches the prediction horizon. |
| 7 | **Kept from v3.2** | Probability calibration, walk-forward validation, point-in-time news filtering (AI readings are timestamped and recorded in session snapshots so replays only see what existed then; Mode A strips them), stale/missing-data detection, circuit breaker, transaction costs, prediction audit log (now with per-event audit). |

Nothing else was added: no new indicators, scoring rules or prediction models.

## v3.2 — data integrity + calibration

| Area | What it does |
|------|--------------|
| **Probability calibration** (`E19_ProbabilityCalibrator`) | Isotonic regression per horizon (5/15/30/60 min) and class, fitted on logged outcomes. Until a horizon has enough outcomes (default 150) probabilities are labelled **model score**, not probability. Walk-forward hold-out Brier (raw vs calibrated) is shown in the Log tab, with reliability buckets <50, 50–55 … 75–80, 80%+. |
| **Data freshness** (`E01b_DataQualityEngine`) | Every input carries source, timestamp, age and status LIVE / DEGRADED / STALE / INVALID / MISSING / MANUAL (macro values with release dates). Stale inputs stop being drivers, degraded ones are down-weighted, and the weighted quality score caps confidence. |
| **Circuit breaker** | NIFTY, futures or option chain missing/stale/invalid, a >2.5% jump between cycles, implausible basis, chain/spot mismatch, ATM IV ≤1% or ≥150%, ATM spread >10% ⇒ **DATA ERROR — NO TRADE**. |
| **Direction ≠ option probability** | The option outcome model reports its own P(profit) and EV (calibrated separately against realised option P&L), and the trade filter checks it separately. |
| **Transaction costs** (`TransactionCosts`) | Brokerage, STT, exchange, SEBI, GST, stamp duty and slippage. EV, breakeven and P(profit) are all net of these. Configurable in Setup. |
| **Audit trail** | Each logged prediction stores every driver (score, weight, data confidence, persistence, contribution), every engine signal, regime reasons, raw + calibrated probabilities, feed statuses, the option economics, failed checks and the config. Tap a log row to inspect it. |
| **Point-in-time validation** | Walk-forward replay across all recorded sessions: each session uses only a calibration fitted on earlier sessions. |
| Also | Multi-horizon probability table, move distribution P(±50…±200), event regime raises the threshold (+8 pts), hysteresis (N consecutive cycles), stale option quotes rejected (Kite last-trade time), heavyweight-concentration confidence penalty, **PAPER TRADE** state when everything passes except calibration. |

Deferred to v3.3+: AI-assisted news/event extraction, correlated-factor (latent factor) model, automatic macro-data updates,
gamma/dealer regime, composite breadth, opening-session model, ML weight optimisation.

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
| 18 | `HistoricalReplayEngine` | Mode A market-only / Mode B full information, strict point-in-time (no future bars/news); walk-forward over many sessions |
| 01b | `DataQualityEngine` | per-feed freshness/validity, quality score, circuit breaker |
| 20 | `EventIntelligenceEngine` | event lifecycle/clustering, expectation state, pricing-in, reaction confirmation, multi-horizon news impact |
| 21 | `GiftNiftyEngine` | GIFT Nifty implied opening gap, gap behaviour in the first hour, pre-open pricing of overnight news |
| 19 | `ProbabilityCalibrator` | isotonic calibration per horizon/class + option-outcome calibration, walk-forward hold-out check |

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
./gradlew :app:testDebugUnitTest --tests '*GeminiAnalystTest*'   # Gemini client vs mock server (schema, no trade fields, 429)
./gradlew :app:testDebugUnitTest --tests '*KiteDataTest*'   # Kite client vs mock server + real NFO instrument dump
./gradlew :app:testDebugUnitTest -DliveNetwork=true --tests '*LiveFeedTest*'   # hits real NSE endpoints
```

## Roadmap (spec stages 2–3)

Use the logged predictions to tune weights/thresholds and calibrate probabilities; then add a tree-based model
(XGBoost/LightGBM) as an ensemble member next to the rule engine. The engine module is plain Kotlin, so it can
also run on a backend with the app as a thin client.
