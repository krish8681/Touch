# NIFTY Three-Horizon Engine v5.0 — Android

A NIFTY **options-trading decision-support bot** for Android, rebuilt around one architecture: three prediction
horizons that end at the **next weekly and monthly expiries**, a separate options valuation → strategy → risk chain,
and calibrated probabilities. It answers four questions:

1. Where is NIFTY likely to be in **30 min / 1 h / 3 h / at today's close**? (H1)
2. Where is NIFTY likely to be at the **next weekly expiry**? (H2)
3. Where is NIFTY likely to be at the **next monthly expiry**? (H3)
4. Which option / **defined-risk** option structure has the best risk-reward given the predicted direction, range,
   probability, IV and expiry — or is the answer **no trade**?

> Decision support only — not investment advice. Factor weights are the spec's starting values and κ (score → drift)
> is an engineering constant: until enough outcomes are logged, every probability is labelled **model score** and the
> bot can at most **PAPER TRADE**.

**Install:** `release/NiftyThreeHorizonEngine-v5.0.0.apk` (Android 8.0+, sideload / "install unknown apps"). It opens in
**Simulator** mode (synthetic data, works offline/after hours). Switch to live data in **Setup**.

## Architecture

```
LIVE DATA (market · options · macro · expiry)
   → DATA VALIDATION      freshness per input, reliability per source, circuit breaker
   → EXPECTATION ENGINE   expected → actual → surprise → interpretation → persistence
   → REGIME ENGINE        Risk-on · Risk-off · Domestic bullish · Earnings expansion · Event shock · Range/compression
   → THREE-HORIZON ENGINE H1 30m/1h/3h/close · H2 next weekly expiry · H3 next monthly expiry
   → EXPIRY ENGINE        OI / ΔOI / IV / skew / PCR / VIX · support / resistance · pin / breakout · expected move
   → PROBABILITY ENGINE   distribution → direction (bull / neutral / bear) · range · buckets
   → CONFIDENCE ENGINE    H1/H2/H3 alignment · FII / global / heavyweights / options agreement · event risk
   → OPTIONS VALUATION    which option is priced attractively?
   → STRATEGY ENGINE      which defined-risk structure fits the prediction?
   → RISK ENGINE          should we actually trade?  → TRADE / PAPER TRADE / WAIT / NO TRADE / DATA ERROR
```

Prediction ≠ trade: the four last layers are separate, so a high direction probability never selects a structure on
its own, and **naked option selling is never generated** (a structure with an uncovered short leg is rejected).

### Core formula (§27)

Every factor produces, per horizon: **direction** (−100…+100), **strength** (0…1, coherence of its own evidence),
**freshness** (0…1, from the input's timestamp and the horizon) and **reliability** (0…1, by source).

```
Effective factor score = direction × strength × freshness × reliability × horizon weight
Horizon score          = Σ effective factor scores                        (−100 … +100)
Drift μ                = κ × score/100 × σ                                 (κ ≈ 0.9–1.2 per horizon, uncalibrated)
Distribution           = log-normal(μ, σ) [+ pin component at the OI concentration strike on expiry horizons]
Bullish / Bearish      = P(S_T > S₀ + 0.25σ) / P(S_T < S₀ − 0.25σ),  Neutral = the rest
```

The score is never mapped directly to a probability: an isotonic calibrator per horizon and class, fitted on logged
out-of-sample outcomes, replaces the model scores once a horizon has enough outcomes (default 150).

### Factor weights (base, %)

| H1-A 30 min | H1-B 1 hour | H1-C 3 hours | H1-D day close¹ | H2 weekly expiry | H3 monthly expiry |
|---|---|---|---|---|---|
| Price structure 20 | Price structure 17 | Price/trend 15 | Trend 16 | FII/FPI positioning 18 | Earnings/EPS 20 |
| Heavyweights 15 | Heavyweights 15 | Heavyweights 15 | Heavyweights 14 | Weekly options 18 | FII/FPI allocation 15 |
| Options OI/ΔOI 15 | Options 15 | FII 15 | FII 12 | Global risk regime 12 | RBI/liquidity/rates 15 |
| Futures 10 | FII/futures 12 | Options 13 | Options 12 | Earnings revisions 12 | Indian growth 12 |
| VIX/IV 10 | Global 12 | Global 12 | Global 11 | RBI/liquidity/rates 10 | Global regime 10 |
| Global 10 | VIX/IV 10 | News 10 | Breadth 10 | Sector leadership 8 | Valuation 10 |
| FII 8 | News 8 | VIX/IV 8 | News 8 | USD/INR 6 | USD/INR + crude 7 |
| News 8 | USD/INR 6 | USD/INR 6 | VIX/IV 7 | Crude 6 | Inflation 6 |
| USD/INR + crude 4 | Crude 5 | Crude 6 | USD/INR 5 · Crude 5 | Indian macro 5 · News 5 | Fiscal/government 5 |

¹ §6 lists the close inputs without weights; these are the starting values. "Expected closing volatility" sets the
close σ rather than a direction. H3 also has a **monthly options-structure overlay capped at ±8 score points**, so monthly
OI shapes the range but can never dominate the fundamental model (§11).

**Adaptive weighting (§28):** the regime multiplies weights (e.g. FII 18 % → ~22 % risk-off, ~15 % range, ~12 % earnings
expansion), clamped to 0.6×–1.5× of base, then renormalised.

**Missing data (§31):** a factor without usable data is removed, the remaining weights renormalised, and the lost share
reported as reduced coverage (which lowers confidence). Missing data never silently becomes "neutral".

**Freshness (§32):** ticks fade within minutes for the 30-minute view but stay fresh for the monthly one; daily data
(FII, valuation) is aged in trading days; monthly macro releases fade over weeks. Inputs too stale count as missing.

**Excluded from the core model (§29):** RSI, MACD, Bollinger, stochastics, CCI, candlestick patterns. Price structure
uses returns, VWAP, opening range, previous-day range, swing structure and trend persistence only.

### Engines (`engine/src/main/kotlin/com/niftyengine/engine/engines`)

| Module | Spec | What it does |
|---|---|---|
| `E01b_DataQualityEngine` | §30–32 | per-feed status/age (LIVE / DEGRADED / STALE / INVALID / MISSING / MANUAL), quality score, circuit breaker |
| `E02b_PriceStructureEngine` | H1 | multi-window pressure, VWAP, opening range, previous-day range, swing structure, persistence; realised/typical ranges |
| `E03_NiftyWeightEngine` | §15 | Σ weight × return, 30/60-min contribution, leadership (banking, IT, energy, auto, pharma, FMCG, telecom, capital goods), narrow/fake breadth |
| `E04_SectorEngine` | §8 | sector rows incl. 5-/20-day returns; H2 sector-leadership factor |
| `E06_FuturesPositionEngine` | H1 | long/short buildup, short covering, long unwinding (day + last 30 min), basis |
| `E07_ExpiryOptionsEngine` | §9 §11 §17 | weekly **and** monthly chain: OI, ΔOI, call/put writing & unwinding, ATM/OTM IV, skew, IV change, PCR, support/resistance (OI + additions − unwinding + price reaction + futures), pin zone (OI + gamma, time, IV), max pain, expected move (IV and straddle) |
| `E08_VIXEngine` | H1 | VIX state and changes |
| `E09_GlobalRiskEngine` | H1–H3 | intraday (with GIFT Nifty around the open), 5-session and 20-session global risk composites |
| `E10_MacroEngine` | H1–H3 | USD/INR and crude per horizon; RBI/liquidity/rates, growth, inflation, Indian macro, fiscal |
| `E10b_FiiEngine` | §16 | FPI cash (latest / 5-day / 20-day), index-futures long/short and long-share change, index calls/puts net exposure → FII regime |
| `E10c_EarningsValuationEngine` | H2 H3 | EPS revisions, quarterly surprise, beat ratio, earnings news; P/E vs fair band, earnings-yield gap |
| `E11/E20_EventIntelligence` | §12 | news clustering, lifecycle, expectation state, pricing-in, reaction check, multi-horizon impact (Gemini optional) |
| `E20b_ExpectationEngine` | §12 | expected → actual → surprise → interpretation → persistence for news and manual consensus (RBI, CPI, GDP, EPS); channel impacts feed their factors |
| `E21_GiftNiftyEngine` | Tier 1 | implied opening gap, gap behaviour in the first hour |
| `E12_RegimeEngine` | §13 §14 | six market regimes with evidence + hysteresis; expiry regime (range/pin, bullish/bearish expansion, volatility expansion) + breakout probability |
| `E13_HorizonEngine` | §3–§11 §27 §28 §31 | weight tables, bounded regime adaptation, missing-data renormalisation, horizon score |
| `E14_ProbabilityEngine` | §18–§20 | σ per horizon (intraday vol blend; IV / straddle / realised to expiry), drift, distribution, direction probabilities, ranges, buckets, day-by-day path to expiry |
| `E23_EventRiskEngine` | §23 | 30-day calendar (expiries, 2026 FOMC, estimated India CPI/GDP, user calendar, pending news) → LOW/MEDIUM/HIGH/EXTREME per horizon |
| `E24_ConfidenceEngine` | §21 §22 | per-horizon confidence, H1/H2/H3 alignment (3/3 · 2/3 · 1/3), master prediction; a horizon opposing the master with ≥ 60 % forces LOW |
| `E15_OptionsValuationEngine` | §18 §26 | fair value under the horizon distribution vs ask → attractive / reasonably priced / too expensive / too far OTM / excessive theta; ATM IV vs forecast vol |
| `E15b_StrategyEngine` | §25 | long ITM/ATM CE/PE, bull call / bear put spreads, bull put / bear call credit spreads, iron condor / butterfly, long straddle; EV, P(profit), max loss/profit, breakevens, greeks — net of costs |
| `E16_RiskEngine` | §26 | probability, confidence, alignment, event risk, P(profit), EV/risk, reward:risk, risk budget & lot sizing, defined risk, liquidity, theta, data quality, session, persistence, calibration |
| `E17/E18/E19/E22` | §27 | prediction log (six horizons + strategy outcome), replay (no look-ahead), isotonic calibration, Kite historical backtest |

`NiftyDirectionEngine` orchestrates one cycle; `sim/SimulatedMarket` generates a consistent synthetic market (now with a
monthly chain, FII participant OI, flow history, valuation, earnings, global and sector daily history).

## Data (spec §30 tiers)

| Tier | Input | Kite mode | Public mode |
|---|---|---|---|
| 1 | NIFTY, Bank Nifty, VIX, sectors, 50 stocks | Kite quote (one call) | NSE `allIndices`, `getIndicesData` |
| 1 | NIFTY futures (+ OI history) | Kite quote + historical `oi=1` | NSE derivatives |
| 1 | **Weekly + monthly option chains** | Kite quote (monthly = near-month futures expiry), ΔOI from NSE | NSE `option-chain-v3`; monthly = last listed expiry of the nearest month (holiday-shifted dates handled) |
| 1 | **FII positioning** | NSE `fiidiiTradeReact` (cash, kept daily on the phone for 5/20-day sums) + **NSE participant-wise OI** (`fao_participant_oi_DDMMYYYY.csv`, last 20 sessions cached) | same |
| 1 | GIFT Nifty, global indices, USD/INR, crude | NSE `getGiftNifty`, Yahoo (+ 1-year daily history) | same |
| 1 | News | RSS → rules / Gemini | same |
| 2 | Sector daily history | NSE `getIndexChart` | same |
| 2 | **NIFTY P/E, P/B, dividend yield** | NSE `allIndices` | same |
| 2 | RBI / economic calendar, earnings, consensus | **Setup** (manual) | same |
| 3 | Gold, US yields, DXY, DII, other macro | Yahoo / Setup | same |

A missing Tier-2/3 input never stops the engine — its factor is removed and the others renormalised.

## App tabs

**Home** — the §24 summary: current NIFTY, market regime, expiry regime, H1 30 min / 1 h / 3 h / day close with
direction and probability, expected close range and P(positive close), H2 and H3 blocks (direction, range, support,
resistance, days to expiry), alignment, confidence, event risk, and the final signal with every risk check.
**Horizons** — per horizon: bull/neutral/bear bar, σ, drift, ranges, distribution buckets, path to expiry, and the
factor table (base → regime → used weight, direction, strength · freshness · reliability, effective score, missing
factors). **Expiry** — weekly and monthly intelligence: expected move, IV/skew, PCR, writing/unwinding,
support/resistance, pin zone, breakout odds, expiry regime, OI by strike. **Strategy** — prediction → category,
valuation tables, ranked structures with legs, EV, P(profit), max loss, breakevens. **Market** — regime evidence, FII
engine, heavyweights and leadership, sectors (day/5d/20d), engine panels. **Events** — event-risk calendar, expectation
engine records, news intelligence. **Log** — per-horizon accuracy, Brier, in-range rate, reliability tables, strategy
win rate, calibration, replay, Kite backtest. **Setup** — data source, Kite, risk budget, thresholds, costs, event
calendar, consensus/macro, earnings/valuation, Gemini.

### What to enter in Setup

* **Event calendar** — RBI MPC dates, Budget, major NIFTY results (`yyyy-MM-dd | RBI | RBI MPC decision | 3`).
  Expiries, the 2026 FOMC schedule (verify) and estimated India CPI (≈12th) / GDP dates are built in.
* **Expectations** — repo, last policy change *and what was expected*, CPI/GDP *and consensus*, PMI, IIP, credit,
  liquidity, fiscal stance, with release dates. Blank = factor missing (weight redistributed), never neutral.
* **Earnings** — NIFTY forward EPS now / a month ago, quarterly EPS growth expected vs actual, beat ratio.
* **Risk** — capital, max loss per trade %, max lots, min alignment, min probabilities, min EV/risk, reward:risk.

## Validation

```bash
./gradlew :engine:test                   # three-horizon engine: spec weights, bounded adaptation, missing-data renormalisation,
                                         # freshness, expectation surprises, conflicting-horizon confidence, distributions,
                                         # defined-risk structures, circuit breaker, calibration, no-look-ahead replay, backtest
./gradlew :app:testDebugUnitTest         # UI crash test on degraded data, NSE participant-OI parsing, Kite client vs mock server …
./gradlew :app:assembleDebug             # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest -DliveNetwork=true --tests '*LiveFeedTest*'   # hits real NSE endpoints
```

The Kite historical backtest (Log tab, or `KiteBacktestLiveRun` with `-DkiteKey … -DkiteToken …`) replays real minute
data through every horizon with walk-forward calibration and compares each with climatology and momentum baselines.
Options, FII data, news and macro inputs don't exist historically, so it tests the market-only core.

## Known limits

* Probabilities are **model scores** until calibrated per horizon; expiry horizons need weeks of outcomes.
* No free API for EPS revisions, consensus or the RBI calendar — these are manual inputs in Setup.
* Built-in FOMC dates are the published 2026 schedule (verify); India CPI/GDP dates are estimates.
* NSE/Yahoo are public website endpoints and may be delayed, rate-limited or changed without notice.
* Valuation is a weak one-month predictor and carries low reliability by design.

## Kept from v4.x

Background operation (foreground service, wake lock, battery banner), Kite login hardening and crash reporting,
GIFT Nifty opening factor, Gemini/rules event intelligence with point-in-time replays, transaction costs (brokerage,
STT, exchange, SEBI, GST, stamp, slippage), session recording and walk-forward replay, Kite historical backtest.

## Build

Requires JDK 17+ and the Android SDK (platform 35). Project layout: `engine/` pure Kotlin/JVM (no Android deps — can
run on a backend unchanged), `app/` Android (Jetpack Compose), `release/` prebuilt APK.
