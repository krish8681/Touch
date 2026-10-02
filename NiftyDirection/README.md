# Nifty Direction Pure 2.2 (Android)

A **Nifty 50 direction engine** on Zerodha Kite. It does not guess "up or down". It **classifies** the market the way desks do:
which side the evidence favours right now, how strongly, and how much of the evidence agrees.
**Signals only — the app never places orders.**

## Setup (one time)
1. Use the same Kite Connect app as Nifty50 Signals. Its Redirect URL must be `https://127.0.0.1/kite`.
2. Install the APK (allow "install unknown apps"). It is a separate app (`com.krish.niftydirection.pure`), so it does not replace older Nifty Direction builds (`com.krish.niftydirection`) or Nifty50 Signals.
3. Open the app → ⚙ Settings → paste API key + API secret → Save.
4. Tap **Log in** in the top bar each morning (Kite logins end at about 6 AM). It turns into a green **● Live** pill.
5. **GIFT Nifty** is read from Kite (`NSEIX:GIFT NIFTY`). A typed value in Settings is only a backup. After 9:15 the real opening gap is used.

## What you see
| Page | What it shows |
|---|---|
| ↗ AI | NIFTY AI: regime, current signal, 7 horizons (direction, probability, confidence, expected range, trade gate, Why?, What changed?), top factors, event risk, event memory, constituent movers, data quality, track record (walk-forward + live), prediction matrix, impact graph |
| ◉ Today | Direction Score gauge, regime (BULLISH / BEARISH / RANGE / NO EDGE / CONFLICT), transition ("Bullish → weakening"), confidence, event-risk and volatility-regime pills, what to do next, support + resistance, live option and futures flow, momentum state, the three scores (structure / live / events), conflict breakdown, score line with hysteresis bands, coming events, index tiles incl. GIFT Nifty |
| ≡ Evidence | Data-quality table (age, LIVE / DELAYED / DAILY / STALE, source trust, update time) and every factor with its reading, weight, source, freshness and learnt multiplier |
| ⊞ Options | Chain ATM ±10 with IV per strike, "Today" (vs yesterday) and "Live" (last ~15 min, read from IV change) activity per side, walls, PCR, ATM IV change, max pain (location only) |
| ◍ Market | What is moving Nifty (sector + stock contributions from NSE free-float weights), global markets, GIFT Nifty, FII / DII (background), sector indices, breadth |
| ✎ News | Event risk, news tone, scheduled events (RBI, Fed, CPI, expiries, yours, and ones Gemini finds), every headline with impact / severity / reason |
| ✓ Record | 9:45 call and morning idea track record, plus "what the record teaches" per factor (walk-forward) |

## The engine (v1.2)
**Structure (100):** global 15 · GIFT / gap 15 · FII background 15 · futures since yesterday 20 · options since yesterday 20 · previous day 15
**Live (100):** opening range 10 · VWAP 14 · breadth (A/D, up-volume, % above VWAP, % above 20-day avg) 14 · Bank Nifty 10 · momentum (5/15/30-min ATR moves, range + volume expansion) 12 · sector contribution 10 · live option flow 16 · live futures flow (OI speed, acceleration, basis trend) 14
**Events:** news tone (Gemini or word list) adds at most ±15; calendar + big news set event risk, which lowers confidence.
**VIX:** volatility regime only (orderly bullish / unstable / risk-off / orderly weakness); lowers confidence when high or rising, never votes.

Blend (smooth by minute): before the open structure 85% / live 15%, then live 30% at 9:30, 40% at 9:45, 55% at 10:15, 65% at 11:00, 75% from 13:00.
Each factor's weight = base × source trust (Kite / NSE 1.0, Yahoo 0.8, Gemini 0.6, typed 0.5, word list 0.35) × freshness × learnt multiplier (needs 100 readings: about 70 sessions with the app open at 9:45 and 11:30; ±15% until 400 readings, ±40% after).
Regime: enter a side at ±25, keep it until ±15 (hysteresis). CONFLICT when structure and live are both ≥30 and opposite (confidence halved).
NO EDGE when evidence is weak or mixed; RANGE only with 2+ real range signs.

## Pure 2.2 — new look
- **New theme across the app:** deep-navy background, glass cards with soft gradients, blue→violet accents, rounded 20dp corners, modern type. Bottom tabs highlight the selected tab with a pill.
- **AI tab redesigned around one question — where is Nifty likely going, and can I act on it?**
  - **Hero card:** price, day change and regime, a **probability ring**, the current signal with **confidence dots** and signal quality, a one-sentence plain-English summary, and a green "strong enough" / amber "no trade" banner.
  - **Forecasts:** one row per horizon with a big arrow and %, confidence dots, a range chip and an **up / flat / down bar**. Tap a row to open Why? (bars showing each factor's push), What changed?, the outlook numbers and the test result.
  - **Three sections** instead of one long scroll: **Overview** (hero, forecasts, drivers, event risk), **Insights** (what changed, news events with tier and persistence chips, cross-market chains, today's timeline, who moved Nifty, learnt links) and **Health** (data-quality bars, source conflicts, track record with calibration bars, the weight heat map, the training report).
  - A friendly three-step start screen before the first training.

## Pure 2.1 — wider data, event chains, three-way outlook
1. **More sources:** official feeds from the Fed, ECB, Bank of England, Bank of Japan, US BEA and PIB, NSE filings (kept only for Nifty 50 companies), and Google News searches for the 15 heaviest constituents (3 requests). All go through the same de-duplication and source tiers. BLS and the US Treasury block automated readers, so they are not included.
2. **Company → sector → Nifty:** company news reaches the index through the stock's own weight plus a spillover to its sector peers (30% for lasting news, 10% otherwise). The path is shown with each event. **Persistence by story type:** results, policy and macro data decay 4× slower; orders and contracts 2×; broker opinions 1×; block deals, flows and rumours twice as fast.
3. **Cross-market chains:**
   - Oil shock: Brent → rupee → Nifty
   - Rates & dollar: US 10Y → dollar → rupee → Nifty
   - US rates → tech: US 10Y → Nasdaq → Nifty
   - plus a consensus implied move from 8 drivers.

   Each link's beta is learnt from the 250 sessions before the date. The chains are a 10th model group and are shown live with their numbers.
4. **Three-way outlook:** up / flat / down probabilities per horizon (flat = a move under ¼ of the horizon's volatility), expected return, and typical high/low.
5. **Data quality + conflicts:**
   - freshness of every live source and the news reader;
   - checks where sources disagree (GIFT vs overnight world, option/futures evidence vs price, Bank Nifty vs Nifty, stale FII);
   - each conflict lowers intraday confidence;
   - every forecast carries a **signal quality** (HIGH / MEDIUM / LOW) with its reason, separate from the forecast.

Models are version 2 and retrain by themselves outside market hours.

## Pure 2.0 — NIFTY AI prediction engine
The first tab is now **AI**: a multi-layer prediction engine (full design in [ARCHITECTURE.md](ARCHITECTURE.md)).
- **7 horizons:** 15 min, 30 min, 1 hour, 3 hours, 6 hours, 1 day, 1 week. Each has its own models.
- **70 inputs in 9 groups:** technical (incl. RSI, MACD, Bollinger, stochastic), trend, sector & banks, volatility, global markets (14 indices + global risk score), currencies, bonds & rates (US curve), commodities (Brent, WTI, gold, silver, copper, crude shock), calendar. 29 world series come from Yahoo.
- **Per horizon:** 9 group models → a meta model that learns how much to trust each group, separately in range-bound and high-volatility markets → calibrated probability (isotonic / Platt).
- **Market regime first:** trending bull/bear, range, high/low volatility, risk-on/off, global shock, panic, expiry, event-driven, news-dominated, India-specific.
- **Proven edge** needs the Brier skill's 90% block-bootstrap band above zero on 120 unseen sessions. On pure noise this falsely passes ~1.4% of the time.
- **Each row:** direction, probability, **confidence (separate)**, expected range (50/68/90%), **trade gate** (NO TRADE unless strong enough; threshold in Settings).
- **Tap a row for Why?** (each group's push in probability points and the inputs behind it) and **What changed?**
- **News → events:** de-duplicated stories, 4 source tiers, rumours half weight, exposure by Nifty weight of the named stocks or sector, time decay by horizon. A **surprise engine** handles actual vs expected releases you type in.
- **Event risk mode** around scheduled releases, **event memory** of the day, **impact graph** of learnt driver links, **data quality**, constituent movers.
- **Feedback loop:** every forecast is logged and scored once its time passes. The live option/futures and news overlays start from conservative weights and are re-learnt from that record only when the re-learnt weights test better.
- `build.sh` takes tool paths from the environment (`ANDROID_JAR`, `AAPT2`, `D8_JAR`, `APKSIGNER_JAR`, `LAMBDA_STUBS`, `KEYSTORE`). It falls back to `javac` when ecj is not present.

## Pure 1.2.1 (fixes)
- **Forecasts are tested before they are trusted:** each model is learnt again on all but the last 120 sessions and scored on those unseen sessions (targets that reach into the test period are left out of learning). The card shows the hit rate vs "always guess the usual side" and a skill score. A model that does not beat the base rate shows **No proven edge**, gets no Strong/Clear/Mild label and is left out of the final line. Models retrain once by themselves (model version 4).
- **Final line** counts only what the models see beyond Nifty's usual up-share, so a model saying "UP 54%" on a market that rises 54% of the time no longer reads as UP.
- **Login kept on permission errors:** only a Kite `TokenException` logs you out. A 403 `PermissionException` (e.g. no market-data add-on) is now shown as the error it is.
- **Live watch keeps running with the screen off:** the CPU stays awake during the 8:30–15:35 window (Doze stopped the timer before), and an alarm wakes the watch when the window opens.
- **Learnt weights can reach their full range:** the track record keeps 260 days (was 120, which capped every factor at ±15% for ever).
- **No half-day global closes:** before 6:00 IST the still-trading US / Brent / USD-INR bars are not used or cached.
- One Kite rate limit for the whole app (screen, forecast and watch no longer pace separately).
- Pre-open "Today's close" card renamed **Today's close vs the open** (that is what it predicts).
- Security: no Android cloud/device backup of settings (API secret, token, Gemini key); Gemini key sent in a header, not the URL; `build.sh` reads the keystore password from `KS_PASS`.

## Pure 1.2
- **Pre-open fix:** overnight Kite resets each quote's previous close, so before 9:15 every change read 0.00% (Bank Nifty "flat", futures "no build-up", breadth 0:0). The app now keeps the last session's numbers (saved in the evening, or fetched once from day candles) so the morning idea uses yesterday's real moves.
- **Pre-open forecast:** new models learnt from ~3 years of openings. Before 9:15 the Forecast tab predicts from the open GIFT Nifty points to: first 1 / 3 / 6 hours after the open and today's close.
- **2 new inputs:** Bank Nifty vs Nifty yesterday, share of sectors up yesterday (29 inputs).
- **Final line** ignores the evidence score while it is still loading (low confidence or coverage) and shows its time.
- Models from older versions retrain by themselves outside market hours.

## Pure 1.1
- **Final direction** line on the Forecast tab: combines the forecast models and the evidence score (Today tab). Same side → FINAL UP/DOWN; one side undecided → LEANS; opposite → NO CLEAR SIDE.
- **Reasons by group** (today's price action, recent days and trend, banks, VIX, sectors, global, expiry): the net push of each group, so inputs that move together no longer show opposite reasons.
- After market hours, old prices show as **CLOSED** (grey) instead of STALE (red).

## Pure 1.0 (was 2.1) — Pure direction forecast
The Forecast tab (first tab) answers one question: **which way is Nifty moving now, and which way is it more likely to move next?**
- **Four horizons:** the next 1, 3 and 6 hours of trading time (nights and weekends are skipped, so a 1-hour forecast made at 15:30 covers 9:15–10:15 the next session), and the next session's close.
- **Learns from:** about 3 years of 5-minute bars from Kite (Nifty, Bank Nifty, India VIX, 10 sector indices), daily Nifty and VIX, and Yahoo daily closes (S&P 500, Nasdaq, Nikkei, Hang Seng, Brent, USD/INR, US 10Y, dollar index). History is downloaded once, then only new days are added.
- **27 inputs, all known at that moment:** recent moves (15 min, 60 min, since open), gap, place in today's range, distance from today's average price, today's swings vs VIX, time of day, yesterday, the last 5 days, 20/50-day averages, Bank Nifty and Financials vs Nifty, VIX change and level, sector breadth and spread, global closes, expiry day. Moves are scaled by recent volatility.
- **Model:** one logistic regression per horizon, learnt from a sample every 15 minutes of every past session.
- **Each card:** UP x% / DOWN y%, strength (Strong 65%+, Clear 58–65%, Mild 53–58%, Toss-up), target time, typical move in points, and the three biggest reasons.
- **One code path:** the live forecast uses the same history object and feature code as learning (only finished 5-minute bars).
- **Updates:** every 5 minutes in market hours. The models relearn by themselves weekly, outside market hours; **Retrain** does it now.
- **Recorder:** every refresh saves live-only inputs (option chain summary, futures order-book totals, FII, news tone, engine factors) to `files/recorder/`, for later models.
- **Removed in Pure 1.0:** historical replay, backtest, the daily model, walk-forward pass/fail gating and ablation.

## v1.3.1 fixes
- **Option flow reference must be fresh:** the comparison snapshot must be 6–25 minutes older than the current one (the one nearest 15 min is used). If none qualifies, live option flow is shown as unavailable instead of a misleading "15-minute" reading.
- **News confirmation counts independent reports, not websites:** official feed = VERIFIED (1.2); 2+ independent write-ups = CONFIRMED (1.0); several sites carrying the same agency (PTI, Reuters, IANS, ANI, Bloomberg, AFP, AP, UNI) or copied story = CORROBORATED (0.7); one source = PROVISIONAL (0.5). Only CONFIRMED / VERIFIED news can raise event risk.

## v1.3 changes (interpretation, data quality, safety)
- **Option flow is probabilistic:** each strike gets "probable writing / covering / buying / unwinding" with a confidence. Pressure = the strike's IV change minus the whole curve's move, checked against the premium move beyond what spot and time explain; much volume with little OI change (churn) lowers confidence.
- **Flow intensity** = confidence-weighted OI change per contract traded, sized against the median of past days (starting guess until 30 readings).
- **Two layers:** ATM ±5 for direction, up to ±15 for walls being built (shown as levels).
- **Futures flow windows grow:** 15 min from ~9:35 (counts 60%), 30 min from ~9:50 (85%), 60-min acceleration from ~10:20 (100%).
- **VIX regime** from its 1-year percentile (low / normal / elevated / extreme) + 20-day Nifty-VIX correlation; range signs are weighed smoothly, no hard 12.5 cut-off.
- **Per-source freshness** (spot 1 min, options 2, candles 7, breadth 2, global 30, FII daily, news 10) and a **DATA DEGRADED** state when key live inputs are missing or old.
- **News verification:** same story grouped; 1 publisher = PROVISIONAL (half weight, can't raise event risk), 2+ = CONFIRMED, official RBI / SEBI feed = VERIFIED; rumours half weight; duplicates counted once.
- **Already priced:** each story is checked against Nifty's move since it came out (fresh shock / absorbing / re-accelerating / already priced / faded).
- **Gemini model pinned:** "auto" picks once and keeps it; every rating logs model, prompt version and time.
- **Protected learning:** weekly refit from past days only, last 20 days held back to test, 100+ readings per factor, ±15% until 400, used only if it passes the test.
- **High internal disagreement** flag (35%+ of the evidence against the score's side), **smooth structure/live blend** by minute, **expiry-day mode** (weekly / monthly / event + expiry; max-pain magnet late on expiry), **gap regime** (normal / moderate / large / extreme · hold / fill / gap-and-go / reversal), **attribution coverage** check, **world risk vs India macro** split, **2-of-3 confirmation** before a bullish ↔ bearish flip (overridden by a ±60 score or confirmed high-risk news).

Not done (Tier 3, later): historical factor analytics beyond the Record page, option-surface modelling, cross-asset correlation regimes, automatic parameter search. True order-flow (who hit the bid/ask) is not available from Kite REST.

## Build
`./build.sh` → `build/NiftyDirection_v<version>_<date>_<time>.apk` (ecj → d8 → aapt2 → align → apksigner, no Gradle).
Tests: `./test/run_tests.sh` (engine scenarios, forecaster, prediction engine, end-to-end collector and runners against a local mock Kite + Yahoo server).

## Code layout
`intel` (prediction engine: Horizon, Markets, FeatureEngine, Regime, Trainer, HorizonModel, Calibrator, EventImpact, EventCalendarRisk, Constituents, ImpactGraph, Feedback, IntelEngine — pure Java; data/IntelRunner feeds it) ·
`forecast` (History, Features, LogReg, Forecaster — pure Java; data/HistoryLoader + ForecastRunner feed it) · `model` (Quote, Candle, OptionRow, Snapshot) · `engine` (Engine, Chain, Factor, Result — pure Java) ·
`data` (Kite, Nse, Global, Http, Collector, Store, Prefs, Brain) · `ui` (MainActivity, IntelPage, Pages, Views, Ui, SettingsActivity, LoginActivity) · `service` (WatchService)
