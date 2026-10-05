# NIFTY Direction Engine v5.1.3 — Android

A **NIFTY market-intelligence, probability and trade-selection engine** for Android. v5 replaces
"indicators → score → direction → option" with a decision pipeline that asks, in order:

1. What state is the market in, relative to its own normal?
2. Which regime is it — and do the information blocks even agree?
3. What is the market **expecting** for the near future, and is that expectation **changing**?
4. What just **changed** (information shock: expected vs actual)?
5. Which **scenarios** follow (breakout / continuation / range / reversal / sharp decline), with what calibrated probability?
6. Is there enough **trade quality** — and which **option**, which **strategy**, how much **risk**?

> Decision support only — not investment advice. Weights and thresholds are starting engineering values, not validated
> optima. The app never places real orders: approved decisions are executed in **shadow mode** (virtual trades) so the
> whole pipeline can be measured before any money is risked.

**Install:** `release/NiftyDirectionEngine-v5.1.3.apk` (Android 8.0+, sideload / "install unknown apps"). Installs over v4.x / v5.x
(same signing key). It opens in **Simulator** mode (synthetic data, works offline/after hours). Switch to live data in **Setup**.

## v5.1.3 — fixes from the first full live session (5 Oct 2026)

| Seen on the device | Cause | Fix |
|---|---|---|
| One "event" with **143 articles from 65 sources**, stage flipping Expected↔Confirmed ~25 times, each flip re-scored as fresh 100 %-unpriced news (News driver +0.38) | New articles were matched against the union of every title the event had absorbed (up to 80 tokens) — a snowball; the rule stage came from the newest article only | Articles match the event's **founding headline**; the rule stage comes from the whole cluster — the newest article that *states* a stage decides, and an outcome once known is never undone by a later preview |
| "Fed's Hammack says…", "Former RBI Governor…" read as confirmed central-bank decisions with 60 % surprise | No outcome wording ⇒ default CONFIRMED | Commentary on a scheduled event (RBI/Fed/inflation/GDP/US data) with no outcome stated is EXPECTED at half severity |
| "…new CEO" (chief **execut**ive) read as a **rate cut** (+0.70) | Direction rule matched the substring `cut` | Whole-word matching (cut/cuts, hike/hiked, hold…) |
| 30-min outcomes missing ("…") for 12:29–12:56 after the app was down 12:57–13:26 | Outcomes priced only from the in-memory path, lost on restart | Gaps are filled from the session's completed 1-minute NIFTY bars (point-in-time: close known at bar start + 60 s) |
| ~20 log rows in 28 min (WAIT↔NO TRADE flips) | Every decision change was logged | One row per 5 min, plus one when an actionable decision (TRADE / PAPER TRADE) starts or ends |
| Gemini **503 high demand**: 101 of 200 daily calls used, 1 event AI-read | Retried every minute | Server errors back off 2→4→8…30 min |

## v5.1.2 — fixes from the first live (Kite) run

| Issue seen on the device | Fix |
|---|---|
| After hours: **DATA ERROR** "Futures stale 34m / Options stale 17m", model health NO SIGNAL — Kite re-stamps quotes after 15:30 at different times (NIFTY 17:35, futures 17:01, chain 17:18) and each was aged against the newest one | With the market closed, ages are measured against the **close of the last session** (found from the newest critical stamp, so holidays work); a stamp after the close is that session's final value. A feed that froze during the session is still STALE; future stamps are still FUTURE; in-session rules are unchanged. |
| **Gemini 404** — `gemini-2.5-flash` "no longer available to new users" — every event fell back to rules | A retired/unknown model is detected; the app switches to the replacement the API names (else the newest `gemini-x-flash` the key lists via ListModels), saves it in Setup and retries; with no replacement it backs off 1 h instead of spending the daily budget. Default model is now `gemini-3.8-flash`. |
| Rules fallback read previews as **confirmed RBI decisions** (severity 0.90, 100 % unpriced) — "Week Ahead… RBI Policy", "Market outlook…", "MPC Meeting Begins Monday; Hike Possible", "Rate hike? …", "Quote on RBI MPC Expectation…" — and "Kotak… new CEO after RBI approval" as RBI policy | Preview / outlook / opinion / question titles without an outcome verb are **EXPECTED** (scheduled, already known — near-zero surprise, still counted as event risk ahead); an unmatched title no longer silently becomes a policy decision through a bare "RBI" mention — RBI_POLICY needs policy wording (repo, MPC, policy rate, governor, rate cut/hike…). |

## v5.1.1 — point-in-time integrity (five essential fixes, nothing else added)

No new indicators, models, news logic, calibration complexity or strategies — only guarantees that every decision is made
from one consistent, past-only snapshot and that every prediction can be audited against its outcome.

| # | Fix | What changed |
|---|-----|--------------|
| 1 | **Point-in-time validator** (`E00_PointInTimeValidator`) | Runs **before the entire pipeline**. Every input must satisfy `dataTimestamp ≤ decisionTimestamp`: NIFTY / Bank Nifty / VIX / constituents / sectors / global (`InstrumentData.asOf`), `FuturesData.asOf`, `OptionChain.asOf`, `GiftNiftyData.asOf`, `FlowData.asOf`, macro release dates, news, event analyses, every intraday/daily candle and futures OI bar. A future-dated non-critical input is **removed**; a future-dated **critical** input (NIFTY, futures, option chain) is flagged and the cycle becomes **DATA ERROR — NO TRADE**. Series items stamped later are dropped. Everything rejected is listed in the output (`pointInTime`) and on the Decision card. |
| 2 | **Future timestamps are FUTURE, not fresh** | The old `max(0, now − asOf)` age made a future quote look "0 s old — perfectly fresh". Ages are now signed; `asOf > decisionTime + tolerance` ⇒ new feed status **FUTURE** (red, shown as `+5m`) ⇒ critical ⇒ NO TRADE. A small clock-skew allowance (default **10 s**, Setup) is accepted and reported as "clock skew" — beyond it the input is rejected. |
| 3 | **One frozen decision snapshot** | The decision time is the snapshot's own timestamp; the live provider now stamps a snapshot when it is **completed**, not when collection started; Gemini analyses are stamped when they **arrive** and only those that existed at snapshot time enter it (later ones wait for the next cycle); the calibration is captured **once per cycle**, and UI-triggered refits run on the engine thread, so one decision never mixes two calibrations. During the session NIFTY, futures and the option chain must be stamped within **180 s** of each other (Setup) — otherwise "Inconsistent snapshot … inputs are from different moments" ⇒ NO TRADE. Each decision carries a `DecisionSnapshot` (id, decision time, analysed time, every input's timestamp, critical skew, calibration fit time). |
| 4 | **Hard critical-data gate — missing ≠ neutral** | Missing, stale, invalid or future-dated NIFTY / futures / option chain, inputs from different moments, or no volatility input at all ⇒ **DATA ERROR — NO TRADE**: no strategy is selected, nothing is sized, no instrument is named, the shadow trader opens nothing. No neutral value is substituted — the strategy selector's "typical 13 % IV" fallback is gone (a leg whose IV cannot be measured is not priced). |
| 5 | **Prediction / outcome audit trail** | Every logged prediction stores the decision timestamp, analysed time, snapshot id, **every input timestamp**, point-in-time rejections, raw / used / calibrated probabilities with the calibration level and fit time, regime, scenarios, selected option and strategy, model health, critical-data status and decision — and is **sealed with a hash** of that content. Outcomes carry their provenance (window start/end, price timestamp, points, threshold, **attached-at**) and are attached **only after the horizon has fully expired**. `PredictionAudit.verify` re-checks hash, input timestamps, outcome window, attachment time and class; calibration and statistics use only verified (or pre-v5.1.1 legacy) records and never DATA ERROR cycles. Log → tap a row shows the audit verdict. |

Next step per plan: run v5.1.1 in **shadow / live-data testing** and let the audited log accumulate.

## v5.1 — correctness, validation and data integrity (no new architecture)

| Fix | What changed |
|-----|--------------|
| **Walk-forward calibration acceptance** | Direction (per horizon), scenario and option-outcome calibrations are each fitted on the oldest 70 % of outcomes and scored on the newest 30 % they never saw. A calibration is **applied only if it lowers the hold-out Brier score without worsening log loss**; otherwise it is rejected, the raw score is kept and labelled as such (Log → calibration shows raw→calibrated Brier and log loss and ACCEPTED / REJECTED). Previously the direction hold-out was only reported and scenario calibration had no hold-out. |
| **Future-data leakage guard** | The engine strips everything stamped after the decision instant from every snapshot — intraday bars of all instruments, daily candles (all instruments, by date), futures OI bars, news published later, analyses made later — so a misbehaving feed cannot leak the future. Test: injecting future bars/candles/news/analyses into 60 consecutive snapshots leaves every output byte-identical (and the test fails without the guard). |
| **Prediction vs outcome time** | Already separated (outcomes attach only after T + horizon, from prices in (T, T + horizon]); now covered by an explicit test. |
| **Stale-data gate** | Critical feeds (NIFTY, futures, option chain) go STALE ⇒ DATA ERROR — NO TRADE after 5–7 min instead of 10–15 min (realistic for 30 s polling and NSE's minute-stamped snapshots). |
| **Model health gate** | One 0–100 score: data freshness 25 · completeness 15 · regime stability 15 · calibration quality 20 (rejected calibration scores low) · news reliability 10 · options quality 15. **≥ 75 eligible · 60–74 shadow only (PAPER TRADE at best) · < 60 no signal (NO TRADE)**; thresholds in Setup. |
| **Log loss** | Reported next to Brier for every horizon, the calibration hold-out and per regime. |
| **Regime-specific performance → selectivity** | Directional calls are tracked per v5 regime (predicted vs realised, Brier, log loss). A regime that keeps over-stating its probability more than the model overall (n ≥ 30, shrunk by sample size) needs up to +10 pts more probability before a trade; the shadow book's R-multiple per regime also scales trade quality. |
| **Shadow metrics + benchmark** | Expectancy, profit factor, max drawdown, average win/loss and payoff, average R and daily Sharpe first — win rate second. A **benchmark book** opens a naive momentum ATM option at the same moments with the same risk budget and exit rules, so the Shadow tab shows whether v5's strategy and strike choice beat a simple trade after costs. |

Already in v5.0 and unchanged: separate direction / scenario / option-profit probabilities, risk-adjusted option selection with
liquidity, spread, stale-price and slippage filters, EV net of all charges, news de-duplication (EVENT_ID) with
event-specific decay, Gemini never overriding data/risk/liquidity/execution, and the failed-check list behind every WAIT /
NO TRADE ("why not trade").

## v5.0 — State → Regime → Expectation → Shock → Scenario → Probability → Quality → Option → Strategy → Risk → Shadow

```
DATA ─→ NORMALIZED STATE ─→ MARKET STATE ENGINES ─→ FUTURE EXPECTATION ─→ INFORMATION SHOCK ─→ REGIME (v5)
     ─→ DIRECTION PROBABILITY (regime weights) ─→ CALIBRATION ─→ SCENARIOS ─→ OPTION SELECTOR ─→ STRATEGY SELECTOR
     ─→ TRADE QUALITY ─→ RISK ENGINE ─→ TRADE / WAIT ─→ SHADOW EXECUTION ─→ OUTCOME LOG ─→ CALIBRATION
```

| # | Module | What it does |
|---|--------|--------------|
| 02c | `RelativeBaselines` | Every positioning/volatility/global input **relative to its own normal**: PCR vs its 20-session normal ("1.12 vs 0.94 → +19 %"), IV skew, ATM IV (India VIX as the prior), futures premium vs **fair carry**, realised vol, NIFTY 15-min move and global daily moves as **multiples of normal** ("Nasdaq +1.0 % = 4.0× normal"). Rolling day aggregates persist across restarts; global normals come from ~2 months of daily candles. The global-risk engine now uses these normals. |
| 23 | `FutureExpectationEngine` | Keeps **current** (price/breadth/sectors), **expected** (expectation changes of unresolved events, lasting news impact, options vs normal, futures premium vs fair, live US/Asia futures & GIFT pre-open, Gemini's expectation shift) and **change** (30 min). Price rising while the expected future deteriorates ⇒ **early reversal warning** — before RSI/MACD turn. New `EXPECTATION` driver. |
| 24 | `InformationShockEngine` | What the market expected vs what happened: event surprises (expected 25 bp cut at 78 % → no cut), global moves, India VIX / ATM-IV jumps, NIFTY 15-min moves and opening gaps in units of normal, sudden expectation repricing. The shock widens the expected move, fattens/skews scenario tails and pushes the regime to EVENT_DRIVEN / VOLATILITY_EXPANSION immediately. |
| 12b | `RegimeEngineV5` | One primary regime: **TREND_UP / TREND_DOWN / RANGE / VOLATILITY_EXPANSION / EVENT_DRIVEN / REVERSAL_RISK / CONFLICT** plus a regime-quality score. When NIFTY, global, options, news, breadth and expectation blocks split, the regime is **CONFLICT ⇒ WAIT** — never forced into UP or DOWN. Each regime family has its own driver-weight table (event regimes lean on news + expectation, trends on structure). |
| 14b | `ScenarioEngine` | Splits P(up)/P(down) into **Bull breakout · Bull continuation (or reversal) · Range · Bear reversal (or continuation) · Sharp decline** by move size (range band = the outcome logger's threshold; breakout = 1.25 normal σ). Breakout shares are tilted by momentum/ADX, opening-range and previous-day breaks, futures short covering/buildup, the shock, expectation change and scheduled-event risk. Produces the move distribution used to reprice options. |
| 19 | `ProbabilityCalibrator` (extended) | **Progressive calibration**: from 30 outcomes per horizon the raw score is blended with the isotonic fit by sample share (full calibration at the configured minimum). **Scenario calibration** against realised buckets. Decision object shows raw *and* calibrated probability. |
| 15 | `OptionSelectionEngine` (extended) | Strikes repriced over the five-scenario distribution, with a **variance-consistent trading-time clock** (calendar-time repricing under-charged intraday theta ≈4–5×). |
| 26 | `StrategySelector` | Strategy is a consequence of regime + scenario shape + IV: strong directional → **BUY CALL / BUY PUT**; moderate or rich IV → **BULL CALL / BEAR PUT debit spread**; range + IV above normal + no event/shock → **IRON CONDOR** (defined-risk option selling, valued and held to the time exit); conflict / reversal against / no positive-EV structure → **NO TRADE**. Legs filled at ask/bid, priced over the scenario distribution, net of all-leg costs; strikes and widths searched for the best return on risk. |
| 25 | `TradeQualityEngine` | **Probability edge × confidence × regime quality × liquidity × R:R** (geometric mean, every component above its floor). 74 % with 86 % confidence, 81 % regime, 94 % liquidity, R:R 1.8 ⇒ HIGH; the same 74 % with 52 % confidence and a 43 % regime ⇒ NO TRADE. Shadow track record per strategy nudges the score once enough trades exist. |
| 27 | `RiskEngine` | Deterministic — **AI can recommend, code decides**: size from the stop (capital × max risk per trade), max capital per trade, max daily loss, max open positions, trades/day, leg spread, slippage share, max IV (long premium), max event/shock risk, entry cut-off; stop, target, time exit and emergency exits. |
| 28 | `ShadowTrader` | **Shadow mode** execution: virtual fills at quoted ask/bid, marked to the chain every cycle (long legs at bid, short at ask), closed on STOP / TARGET / TIME / EMERGENCY (data breaker, shock/regime/expectation against the position) / session end, P&L net of charges. Learning tables by strategy, regime, quality tier, probability bucket, expectation state and exit reason. Persisted per data family (simulator vs live). |
| 16 | `TradeDecisionEngine` (v5) | Data → regime → probability → strategy → liquidity → P(profit) → EV → quality → risk → event risk → session → persistence → calibration. Uncalibrated but otherwise passing ⇒ PAPER TRADE (shadow-executed). |

**Final decision object** (Home → Decision state; Shadow tab → share JSON):

```json
{
  "market": "NIFTY", "regime": "TREND_UP", "regime_quality": 0.81, "direction": "UP",
  "raw_probability": 0.78, "calibrated_probability": 0.72, "calibration": "PARTIAL", "confidence": 0.86,
  "scenarios": {"bull_breakout": 0.18, "bull_continuation": 0.51, "range": 0.15, "bear_reversal": 0.11, "sharp_decline": 0.05},
  "future_expectation": 0.34, "expectation_change": -0.05, "expectation_state": "CONFIRMING",
  "information_shock": 0.12, "trade_quality": 0.81, "quality_tier": "HIGH",
  "instrument": "NIFTY 24500 CE", "strategy": "BUY_CALL", "lots": 1, "risk_at_stop": 2340,
  "stop": "premium −35% (₹65.0)", "target": "premium +60% (₹160.0)",
  "model_health": 86, "health_tier": "ELIGIBLE", "action": "TRADE",
  "snapshot_id": "3FA91C07", "critical_data": "OK"
}
```

**Gemini** remains the news/macro reader only. v5 asks it the expectation question explicitly ("what was expected, at what
probability, what is new, has the probability changed?") and adds an `expectationShift` field that feeds the Future
Expectation Engine. It still has no trade fields; probabilities, option/strategy choice, risk and execution are code.

**App:** new Home cards (decision state, scenarios, v5 regime + information blocks, future expectation, information shock,
strategy & risk plan, trade quality), Market → relative state, Options → strategy candidates, a new **Shadow** tab
(open positions, P&L, recent trades, learning tables, decision JSON), Log → scenario reliability, Setup → strategy/risk/shadow
settings (capital, risk %, daily loss %, positions, trades/day, IV cap, stop/target %, min quality, strategy toggles).

Also fixed in v5: the isotonic calibrator pooled tied scores incorrectly (duplicate blocks made `predict` return the first
one); a data-mode switch could save the old engine's state under the new mode's file.

## v4.3.1 — Kite login crash fixes

- Kite login screen: non-web URLs (`about:blank`, `intent:`, `data:`) made `Uri.getQueryParameter` throw
  inside the WebView callback, which killed the app. Token parsing is now string-based and never throws, app deep
  links go to Android, and a dead WebView renderer is handled. If the in-app browser can't run, login opens in the
  phone's browser with a box to paste the redirected URL.
- Engine work (cycles, settings changes, Kite login) runs on one dedicated thread, so a settings change can't race a cycle.
  Background errors (including out-of-memory) are reported in the top bar instead of crashing the app.
- The NFO/NSE instrument dumps stream to disk and are parsed line by line (much lower peak memory); `largeHeap` is on.
- Crash reports: any crash is saved, and the next launch shows a red banner to share it.

## v4.3 — keeps running in the background

- The engine loop moved out of the screen (`MainViewModel` → application-scoped `ui/EngineController`), so
  minimising the app, switching apps or turning the screen off no longer stops it.
- `EngineService`: a foreground service with an ongoing "Engine running" notification (live spot, Bull/Bear %,
  last update, **Stop engine** action) and a partial wake lock. If Android kills the process, the service is restarted
  (`START_STICKY`) and the loop resumes. Pressing ❚❚ in the app or "Stop engine" ends both.
- Battery: live modes poll at the configured rate from 08:45 to 15:45 IST on weekdays, and every 5 minutes outside
  that window. A banner asks for "unrestricted battery" (battery-optimisation exemption) while it is missing.
  On Xiaomi/Oppo/Vivo/Realme/OnePlus also enable **Autostart** and set the app's battery to **No restrictions**.
- Setup → App → "Run in background" (on by default).

## v4.2 — Kite historical backtest (market-only)

Log tab → **Kite historical backtest** → 1M / 3M / 6M / 12M (needs today's Kite login).

* Downloads NIFTY 50, India VIX, Bank Nifty (1-minute), sector indices and the 50 constituents (5-minute), continuous
  NIFTY futures with OI (if Kite serves it) and daily history; ≤ 3 requests/s, ≤ 60 days per minute request, cached on disk.
* Replays every 5 minutes with completed bars only (no look-ahead); walk-forward calibration refitted every 5 days from
  earlier days only.
* Compares the model with **climatology** (always predict past base rates; Brier skill score) and **30-min momentum**,
  plus a signal-quality table (hit rate and average points when bull/bear ≥ 50…70 %) and accuracy by regime.
* Exports every prediction (CSV) and the report (JSON) for sharing.
* **Scope:** options, news, GIFT Nifty, global markets and FII/DII don't exist historically, so this tests the
  market-only core. Constituents/weights are today's (survivorship bias for older periods).

Workstation alternative: `./gradlew :app:testDebugUnitTest --tests '*KiteBacktestLiveRun*' -DkiteKey=… -DkiteToken=… -DbacktestMonths=6`.

Also fixed: Kite daily candles are stamped 00:00 IST, so "previous-day" history could include today's partial bar in
live Kite mode — daily history is now filtered by date.

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
engine/   pure Kotlin/JVM — all modules, no Android deps (can be moved to a backend unchanged)
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
| 19 | `ProbabilityCalibrator` | isotonic calibration per horizon/class (progressive from 30 outcomes) + scenario and option-outcome calibration, walk-forward hold-out check |
| 02c | `RelativeBaselines` | inputs relative to their own normal (PCR, IV, skew, basis vs fair carry, global moves × normal) |
| 12b | `RegimeEngineV5` | primary regime (trend/range/volatility/event/reversal/conflict) + regime quality, weight-table selection |
| 14b | `ScenarioEngine` | five scenarios + move distribution for option/strategy repricing |
| 23 | `FutureExpectationEngine` | current vs expected vs change; early reversal warnings; EXPECTATION driver |
| 24 | `InformationShockEngine` | expected vs actual on events and market data, in units of normal |
| 25 | `TradeQualityEngine` | probability edge × confidence × regime quality × liquidity × R:R with floors |
| 26 | `StrategySelector` | buy call/put, debit spreads, iron condor or no trade — from regime, scenario shape and IV |
| 27 | `RiskEngine` | deterministic sizing, limits and exit plan |
| 28 | `ShadowTrader` | virtual execution, exits, net P&L, learning statistics, benchmark book |
| 29 | `ModelHealthEngine` | 0–100 health score; eligible / shadow only / no signal |

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

Home (decision state, probabilities, scenarios, regime, expectation, shock, strategy & risk plan, quality, checks) ·
Drivers (weighted driver bars incl. future expectation, agreement/conflict, every engine signal) · Options (strategy
candidates, ranked strikes) · Market (relative state vs normal, heavyweights, sectors, futures, VIX, global, macro, flows) ·
News (clustered events with decay/reaction) · Shadow (virtual positions, P&L, learning, decision JSON) · Log (live
performance, calibration, scenario reliability, replay, export) · Setup.

The engine refreshes while the app is open (default 30 s live / 2 s per simulated minute). Live sessions are
recorded for replay; predictions are logged every 5 minutes during market hours.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew :engine:test            # engine unit tests (v5 pipeline, simulated sessions, replay no-look-ahead, news dedup, BS parity…)
./gradlew :app:assembleDebug      # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest --tests '*GeminiAnalystTest*'   # Gemini client vs mock server (schema, no trade fields, 429)
./gradlew :app:testDebugUnitTest --tests '*KiteDataTest*'   # Kite client vs mock server + real NFO instrument dump
./gradlew :app:testDebugUnitTest -DliveNetwork=true --tests '*LiveFeedTest*'   # hits real NSE endpoints
```

## Roadmap

Run shadow mode until the calibration and the shadow book have enough outcomes (Log + Shadow tabs), then tune weights,
thresholds and strategy rules from what the learning tables show. Live order placement is intentionally not implemented:
it should only be added once shadow results justify it, behind the same deterministic risk engine. A tree-based model
(XGBoost/LightGBM) can later join the rule engine as an ensemble member. The engine module is plain Kotlin, so it can
also run on a backend with the app as a thin client.
