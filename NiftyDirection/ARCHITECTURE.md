# Nifty Direction Pure 2.1 — Prediction Engine Architecture

This is the architecture as built (code in `src/com/krish/niftydirection/intel`, wired in by `data/IntelRunner` and `ui/IntelPage`).
It runs entirely on the phone: Kite Connect + NSE + Yahoo + public news feeds in, calibrated probabilities out. **Signals only — the app never places orders.**

## Principles

1. **Probabilistic, not deterministic.** Every output is P(Nifty higher at the target time), plus an expected range. Nothing claims to know where Nifty *will* go.
2. **Forecast ≠ trading signal.** A separate trade gate decides whether a forecast is strong enough to act on. Most of the time the answer is NO TRADE.
3. **Learnt, not hard-coded.** Group weights, regime-specific weights, calibration and live-overlay weights all come from data. Fixed numbers are priors or caps, and are listed below.
4. **Tested before trusted.** Each horizon is walk-forward tested on sessions it never saw. "Proven edge" needs statistical evidence, not just a lucky test window.
5. **Every forecast is scored.** Live forecasts are logged, resolved against what happened, and feed back into the overlay weights.
6. **Only what was known at that moment.** Every input uses finished bars and daily series dated before the session. This is tested at random moments and at the open.

## Pipeline

```text
 Kite (Nifty/Bank/VIX/10 sectors 5-min, futures, option chain, 50 stocks)   NSE (FII/DII, participant OI, weights)
 Yahoo (29 daily world series)   RSS news (Google News, ET, Moneycontrol, Mint, RBI, SEBI) + Gemini/word reader
                                   │
                    DATA NORMALISATION — collector caches, IST session dates, staleness checks
                                   │
       ┌───────────────────────────┼─────────────────────────────┐
       ▼                           ▼                             ▼
 FEATURE ENGINE (70 inputs,  EVENT INTELLIGENCE              CONSTITUENT ENGINE
 9 groups)                   news → event → impact            free-float weights, movers,
 FeatureEngine               EventImpact, EventCalendarRisk   company-news exposure (Constituents)
       │                           │                             │
       ▼                           │                             │
 MARKET REGIME ENGINE (Regime): trend · volatility · risk · expiry · live flags
       │
       ▼
 MULTI-HORIZON ENGINE: 15m · 30m · 1h · 3h · 6h · 1D · 1W   (HorizonModel × 7, one per horizon)
   per horizon: 9 group models ──► meta model (+ regime interactions) ──► calibration
       │
       ▼
 LIVE OVERLAYS: option/futures evidence score (Today tab) + news score, bounded ±0.4 logit, weights re-learnt (Feedback)
       │
       ▼
 CONFIDENCE (separate from direction) · EXPECTED RANGE · TRADE GATE · WHY? · WHAT CHANGED? · EVENT MEMORY
       │
       ▼
 NIFTY AI screen (IntelPage) ──► prediction log ──► outcomes ──► scorecard + overlay re-learning (feedback loop)
```

## The 12 engines → implementation

| # | Engine | Where | Status |
|---|---|---|---|
| 1 | Market data | `data/Collector`, `data/Kite`, `data/Nse`, `data/ForecastRunner.history` | Nifty, Bank Nifty, India VIX, 10 sector indices (5-min, ~3 years), futures + basis + OI, option chain ±15 strikes with IV/OI/volume/PCR, breadth, FII/DII, participant OI. Tick data and L2/L3 are not available from Kite REST. |
| 2 | Global markets | `intel/Markets`, `FeatureEngine` | S&P 500, Nasdaq, Dow, Russell 2000, US VIX, US futures, DAX, FTSE, CAC, Nikkei, Hang Seng, Kospi, Shanghai, ASX 200, Taiwan, GIFT Nifty (live, Kite), plus a composite **global risk score**. |
| 3 | Commodities | `FeatureEngine` | Brent, WTI, gold, silver, copper, Brent 5-day, **crude shock score** (Brent 1d/5d, made worse by a weaker rupee). The Indian crude basket, OPEC, inventories and shipping come in only through the news engine. |
| 4 | FX | `FeatureEngine` | USD/INR (1d and 5d), DXY, EUR/USD, USD/JPY, USD/CNY. |
| 5 | Bonds / money market | `FeatureEngine` | US 13-week, 5Y, 10Y and 30Y yield changes, curve slope (10Y − 13W) and its 5-day change, 10Y 5-day change. India G-Sec, MIBOR, T-bills and RBI liquidity have no free machine-readable daily feed: RBI enters through the event calendar, official RSS and the surprise engine. |
| 6 | Macro | `EventCalendarRisk` | Scheduled releases (RBI, Fed, India CPI, Budget, user events with times). The **Surprise engine** turns actual − expected into a primary-source event. Actual/expected numbers are typed in Settings, because no free consensus feed exists. |
| 7 | Constituents | `Constituents`, collector weights | NSE free-float weights, weighted contribution of each stock, share of index weight trading up, company-name matching so company news is weighted by its Nifty weight. |
| 8 | News intelligence | `EventImpact` (+ `News`, `Gemini`) | Entity (constituent / sector / macro), event, direction, magnitude, probability (rumour = ½), credibility (4 source tiers × confirmation), novelty, exposure, time decay by horizon. Duplicates are grouped (50 copies = 1 event, confirmed by many sources). |
| 9 | Regime | `Regime` | Trend (TREND_BULL / TREND_BEAR / RANGE / MIXED), volatility (VIX 1-year rank), risk (RISK_ON / RISK_OFF / GLOBAL_SHOCK), expiry, PANIC. Live flags: EVENT_DRIVEN, NEWS_DOMINATED, INDIA_SPECIFIC. |
| 10 | Prediction | `Trainer`, `HorizonModel` | 7 horizons × (9 group logistic models + meta model). |
| 11 | Calibration | `Calibrator` | Isotonic regression (≥1,500 out-of-sample forecasts) or Platt scaling, fitted on out-of-sample predictions. Reliability table shown in the app. |
| 12 | Feedback | `Feedback`, `IntelRunner` | Prediction log, outcome resolution, live scorecard by horizon and regime, controlled re-learning of the overlay weights. |

Also built: **Impact graph** (`ImpactGraph`, learnt driver → Nifty and driver → driver links), **Event memory** (today's sequence), **What changed?**, **Why?**, **Data quality** (`IntelEngine.quality`) and the **trade gate**.

## 2.1 additions

| Area | What was added | Where |
|---|---|---|
| Data universe | Fed, ECB, BoE, BoJ, US BEA, PIB official feeds (tier 1); NSE filings for constituents; Google News searches for the 15 heaviest constituents | `data/News` |
| Company → sector → Nifty | exposure = stock weight ÷ 6% + spillover × peers' weight ÷ 6% (spillover 0.3 for lasting news, 0.1 otherwise); the path is stored as `Event.chain` | `EventImpact.exposure` |
| Persistence | half-life × 4 FUNDAMENTAL (results, policy, macro data, M&A), × 2 ORDERS, × 1 OPINION, × 0.5 FLOW (block deals, rumours) | `EventImpact.persistence` |
| Cross-market event graph | chains Brent → USD/INR → Nifty; US 10Y → DXY → USD/INR → Nifty; US 10Y → Nasdaq → Nifty; consensus of 8 direct drivers. Betas come from regressions over the 250 sessions before each date (no look-ahead), cached per date. Implied Nifty move = root move × Π betas. They form model group 10 and a live card. | `CrossMarket` |
| Three-way outlook | P(flat) = share of past moves under 0.25 × horizon vol (per volatility bucket); P(up) = p·(1 − P(flat)), P(down) = (1 − p)·(1 − P(flat)); expected return = (2p − 1) × mean |move|; typical high/low = median excursions before the target, tilted toward the forecast side | `HorizonModel.outlook`, `Trainer.excursion` |
| Data quality + conflicts | per-source freshness vs expected age (live sources are not marked stale while the market is closed), news-reader quality. Conflicts: GIFT vs overnight world, Today-tab evidence vs today's price move, Bank Nifty vs Nifty divergence, stale FII. Each conflict × 0.9 on intraday confidence (floor 0.7). Score = 0.6 inputs + 0.25 sources + 0.15 evidence coverage. | `IntelEngine.quality`, `conflicts` |
| Signal quality | HIGH (tradeable, confidence ≥ 65, no conflicts), MEDIUM (tradeable), LOW (gate failed), each with its reason | `IntelEngine.forecast` |

Model version 2 (10 groups). Older models retrain automatically outside market hours.

## Inputs (74, in 10 groups)

| Group | Inputs |
|---|---|
| Technical | 15/60-min move, move since open, gap, place in day's range, distance from VWAP-like average, 5-min RSI(14), MACD histogram, Bollinger %b, stochastic %K, 30-min ROC, 1-hour efficiency ratio, bar range vs normal |
| Trend | yesterday's move and close-in-range, 5-day move, distance from 20/50/200-day averages, daily RSI(14), 20-day efficiency ratio |
| Sector & banks | Bank Nifty and Financials vs Nifty (today and yesterday), share of sectors up (today and yesterday), sector dispersion |
| Volatility | today's swings vs VIX, India VIX change, VIX 1-year rank, VIX 5-day change, US VIX, Nifty realised vol vs VIX |
| Global markets | the 14 index series above (last finished session), global risk score |
| Currencies | USD/INR, DXY, EUR/USD, USD/JPY, USD/CNY, rupee 5-day change |
| Bonds & rates | US 10Y, 13W, 5Y, 30Y changes, curve slope and its change, 10Y 5-day change |
| Commodities | Brent, WTI, gold, silver, copper, Brent 5-day change, crude shock score |
| Calendar | time of day, weekly expiry, day of week |
| Cross-market chains | oil chain, rates & dollar chain, US rates → tech chain, consensus implied move (all in Nifty volatility units) |

Moves are divided by Nifty's recent daily volatility. Missing inputs are NaN (the model uses its training average). Inputs 0–28 are the original forecaster's (`forecast.Features`), so old and new share one code path.

## Horizons

| Horizon | Target | Band | News half-life |
|---|---|---|---|
| 15m, 30m, 1h, 3h, 6h | Nifty after 3/6/12/36/72 trading 5-min bars (nights and weekends skipped) | SHORT (15m, 30m), INTRADAY (1h–6h) | 1h, 2h, 3h, 6h, 12h |
| 1D | next session's close (today's close when made at the open) | SWING | 24h |
| 1W | close 5 sessions later | SWING | 72h |

Each horizon has its own models. How much it leans on technical vs global vs macro inputs is learnt (the **prediction matrix** on the AI tab), not set by hand.

## Training and validation (`Trainer`)

1. **Samples:** every 15 minutes of every finished session, plus the open (k = 0). That is about 25 samples per session and about 19,000 for 3 years. Features are computed once and shared by all horizons.
2. **Out-of-sample group probabilities:** the newest 60% of sessions are cut into 5 consecutive blocks. Each block is predicted by group models learnt only from earlier sessions **whose targets also ended earlier** (purged).
3. **Meta model:** logistic regression on the 9 group logits, plus the same × range-market flag and × high-volatility flag. This is how weights change by regime.
4. **Calibration:** isotonic or Platt on the meta output.
5. **Walk-forward test:** the newest 120 sessions are scored by a meta model and calibration learnt only from earlier sessions. Reported: hit rate vs always-the-usual-side, Brier, log loss, Brier skill, reliability table, hit rate by regime.
6. **Proven edge** = the 5% point of the Brier skill under a **circular block bootstrap over test sessions** (block = horizon length in sessions, 400 resamples) is above 0, **and** the hit rate beats the base rate. Measured on pure-noise markets: 1 false "edge" in 70 horizon models (~1.4%). A planted signal is proven with its whole band above zero.
7. **Production models** are refitted on all data, versioned (`HorizonModel.VERSION`) and saved with their test results. They retrain weekly outside market hours, never on every prediction.

## Live pipeline (`IntelEngine.forecast`)

- **Overlays:** p = σ(logit(p_model) + clip(w_e·E + w_n·N, ±0.4)).
  - E = Today-tab score/100 × confidence/100 (options, futures, FII, breadth: no history exists to train on).
  - N = tanh(Σ event impacts) at that horizon.
  - Priors (w_e, w_n): SHORT 0.6/0.3, INTRADAY 0.45/0.35, SWING 0.2/0.4.
  - The priors are replaced by weights learnt from the phone's own resolved forecasts once there are ≥200 of them **and** the learnt weights beat the priors on the newest 30%.
- **Pre-open:** the open is estimated from GIFT Nifty (Nifty × GIFT ÷ near future), so the model logit is shrunk × 0.7.
- **Event impact** = direction × magnitude × probability × credibility × novelty × exposure × time decay.
  - Credibility: tier 1 (RBI/SEBI/NSE/BSE/ministries/Fed/Treasury) 1.0, tier 2 (Reuters, Bloomberg, ET, Mint, Business Standard…) 0.9, tier 3 0.7, tier 4 (social) 0.4; × confirmation 0.85–1.1.
  - Exposure: named constituents → Σ weight ÷ 6%; sector → weight ÷ 25%; macro words → 1.
- **Event risk:** PRE-EVENT (≤30 min before a scheduled release: short-horizon confidence ×0.5, no trade), POST-EVENT (60 min after), EVENT DAY. A horizon whose window contains an event gets ×0.6 (high importance) or ×0.8.
- **Confidence (0–100)** = 100 × (0.35 strength + 0.25 proven edge + 0.2 agreement of groups + 0.2 data quality) × event-risk multiplier × 0.85 if panic.
  - Capped at 30 without a proven edge, and at 45 if untested.
  - High ≥ 65, Medium ≥ 40.
- **Expected range:** empirical 50/68/90% quantiles of |move| ÷ horizon volatility, per volatility bucket, × today's volatility. On fresh synthetic data the 68% band covered 68% of moves.
- **Trade gate** (all must pass): side probability ≥ threshold (Settings, default 62%), proven edge, confidence ≥ 40, no PRE-EVENT for intraday, data quality ≥ 0.6, 68% range ≥ 0.1%, a side.
- **Why?** Each group's push on the meta logit in probability points, with the 2 inputs inside it that push hardest, plus the two overlays. **What changed?** The change in each group's points since the last forecast, and the input inside the group whose change moved it most.

## Storage (`files/intel/`)

| File | Content |
|---|---|
| `model_<h>.json` | group models, meta, calibration, test results, reliability, by-regime, range table, learnt weights |
| `log_<yyyy-MM>.jsonl` | one line per horizon at most every 30 min: `{t, date, k, h, price, pm, pf, ev, nw, regime, ahead, slot, done, up, move, target}` (kept 12 months) |
| `feedback.json` | daily scorecard + learnt overlays |
| `state.json` | last forecast (for What changed?) |
| `memory.json` | today's event memory |
| `graph.json` | impact graph |
| `report.txt` | training report |

## Not done, and why

- **Tick-by-tick, L2/L3 order book, true order flow:** Kite REST gives quotes and 5-minute candles only. The microstructure group uses 5-minute bars, and the Today tab uses order-book totals and the option chain.
- **Server stack (Kafka/Redpanda, TimescaleDB, Redis, FastAPI, XGBoost/LightGBM, PyTorch):** this is a single-user phone app with no server. The same layers (connectors → normalisation → feature store → model services → calibration → UI) run in-process in plain Java. Ridge-logistic group models were chosen because they train in seconds on a phone and stay explainable.
- **FinBERT / transformer NLP:** replaced by the existing Gemini reader (or the word list) for per-headline direction, severity, sector and topic. Event structuring, tiers, dedup and impact are done in code.
- **India G-Sec curve, MIBOR, T-bills, RBI liquidity, consensus forecasts:** no free machine-readable source. RBI comes in via the calendar and official RSS; macro surprises are typed in.
- **Social media:** tier 4 is supported in the credibility model, but no social feed is connected.
- **Options, futures, FII and news history for the learnt models:** not downloadable historically. They act as live overlays whose weights are learnt from the phone's own record, and the Recorder keeps saving them for future models.
