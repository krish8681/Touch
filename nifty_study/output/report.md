# Nifty reverse-engineering study

Data: 2009-01-02 → 2026-10-01 (4355 days). Train: before 2020-01-01. Test (unseen): from 2020-01-01.

**Large move** = within the next **5 trading days** Nifty reaches **±3.0%** from today's close *and* the day-5 close is in the same direction. All indicator values are taken at today's close, so nothing looks into the future.


## LARGE RISE

Base rate — how often a large rise follows *any* random day: train **16.6%**, test **12.5%**. A useful signal has to beat this number.

### Step 1 – What indicators looked like the day BEFORE the move (train years)

effect_size: how different the pre-move days are from normal days (|0.2| small, |0.5| medium, |0.8| large).

| feature | before_big_move_median | normal_day_median | effect_size |
|---|---|---|---|
| atr_pct | 1.65 | 1.20 | 0.84 |
| vix | 22.74 | 16.82 | 0.84 |
| bb_width | 8.50 | 5.97 | 0.59 |
| vix_pctile | 56.63 | 40.56 | 0.37 |
| dist_ema200 | 1.54 | 5.04 | -0.37 |
| rsi14 | 49.90 | 55.78 | -0.36 |
| di_diff | -3.38 | 3.48 | -0.29 |
| bb_width_pctile | 50.40 | 42.17 | 0.18 |
| dist_ema20 | -0.11 | 0.77 | -0.16 |
| ret5 | -0.39 | 0.48 | -0.16 |

### Step 2 & 3 – Rules found in train years, then tested on unseen years

`test_lift` = test hit rate ÷ test base rate. 1.0 = no better than random. `test_episodes` counts separate occasions (back-to-back signal days count once). `holds_up` = lift ≥ 1.25 with ≥ 8 episodes.

| rule | train_signals | train_hit_rate% | train_lift | test_signals | test_episodes | test_hit_rate% | test_lift | holds_up |
|---|---|---|---|---|---|---|---|---|
| vix >= 24 | 538 | 39.00 | 2.35 | 156 | 10 | 47.40 | 3.80 | True |
| dist_ema200 <= -4.33 | 269 | 40.50 | 2.44 | 127 | 12 | 41.70 | 3.34 | True |
| dist_ema20 <= -2.51 | 269 | 33.80 | 2.04 | 113 | 29 | 37.20 | 2.98 | True |
| atr_pct >= 1.8 | 537 | 36.50 | 2.20 | 197 | 9 | 36.00 | 2.89 | True |
| bb_width >= 12.5 | 269 | 38.30 | 2.30 | 100 | 12 | 32.00 | 2.56 | True |
| vix_pctile >= 91.2 | 274 | 29.20 | 1.76 | 150 | 13 | 31.30 | 2.51 | True |
| macd_hist_pct <= -0.457 | 269 | 29.00 | 1.74 | 93 | 19 | 31.20 | 2.50 | True |
| ret5 <= -2.83 | 269 | 28.30 | 1.70 | 113 | 35 | 28.30 | 2.27 | True |
| bb_width >= 10 | 537 | 30.70 | 1.85 | 220 | 17 | 27.30 | 2.18 | True |
| ret1 >= 1.29 | 269 | 28.60 | 1.72 | 119 | 66 | 26.90 | 2.15 | True |
| dist_ema200 <= -1.18 | 537 | 31.30 | 1.88 | 299 | 11 | 26.80 | 2.14 | True |
| ret1 <= -1.19 | 269 | 26.00 | 1.57 | 135 | 63 | 26.70 | 2.14 | True |
| bb_width_pctile >= 90 | 273 | 24.90 | 1.50 | 200 | 16 | 25.50 | 2.04 | True |
| dist_ema20 <= -1.34 | 537 | 28.10 | 1.69 | 268 | 41 | 24.60 | 1.97 | True |
| vix_pctile >= 78.5 | 537 | 24.60 | 1.48 | 332 | 20 | 24.40 | 1.95 | True |

**Year by year for the best rule** (`vix >= 24`) — check it isn't one crash year doing all the work:

| year | signals | hit_rate | base_rate |
|---|---|---|---|
| 2009 | 238 | 43.7 | 43.2 |
| 2010 | 52 | 30.8 | 18.4 |
| 2011 | 107 | 35.5 | 23.1 |
| 2012 | 49 | 26.5 | 18.2 |
| 2013 | 37 | 51.4 | 20.6 |
| 2014 | 25 | 36.0 | 17.1 |
| 2015 | 14 | 28.6 | 14.3 |
| 2016 | 2 | 100.0 | 11.5 |
| 2019 | 14 | 35.7 | 10.4 |
| 2020 | 105 | 43.8 | 26.8 |
| 2021 | 13 | 53.8 | 16.5 |
| 2022 | 24 | 50.0 | 15.3 |
| 2024 | 5 | 60.0 | 5.7 |
| 2026 | 9 | 66.7 | 10.0 |

**Active on 2026-10-01:** dist_ema200 <= -4.33; dist_ema20 <= -2.51; dist_ema200 <= -1.18; dist_ema20 <= -1.34; rsi14 <= 37.1; ret5 <= -1.68; rsi14 <= 42.8; di_diff <= -11.6; macd_hist_pct <= -0.294; di_diff <= -17.6


## LARGE FALL

Base rate — how often a large fall follows *any* random day: train **16.2%**, test **13.1%**. A useful signal has to beat this number.

### Step 1 – What indicators looked like the day BEFORE the move (train years)

effect_size: how different the pre-move days are from normal days (|0.2| small, |0.5| medium, |0.8| large).

| feature | before_big_move_median | normal_day_median | effect_size |
|---|---|---|---|
| atr_pct | 1.44 | 1.23 | 0.38 |
| vix | 19.36 | 17.00 | 0.37 |
| vix_pctile | 55.42 | 41.37 | 0.30 |
| rsi14 | 50.15 | 55.60 | -0.28 |
| di_diff | -2.05 | 3.37 | -0.25 |
| macd_hist_pct | -0.09 | 0.02 | -0.24 |
| vol_trend | 1.02 | 0.98 | 0.21 |
| bb_width | 6.92 | 6.14 | 0.20 |
| dist_ema20 | -0.01 | 0.74 | -0.14 |
| dist_ema200 | 3.23 | 4.83 | -0.13 |

### Step 2 & 3 – Rules found in train years, then tested on unseen years

`test_lift` = test hit rate ÷ test base rate. 1.0 = no better than random. `test_episodes` counts separate occasions (back-to-back signal days count once). `holds_up` = lift ≥ 1.25 with ≥ 8 episodes.

| rule | train_signals | train_hit_rate% | train_lift | test_signals | test_episodes | test_hit_rate% | test_lift | holds_up |
|---|---|---|---|---|---|---|---|---|
| vix_pctile >= 91.2  AND  gap_pct >= 0.558 | 51 | 33.30 | 2.06 | 30 | 9 | 40.00 | 3.04 | True |
| vix >= 24  AND  macd_hist_pct <= -0.294 | 186 | 29.60 | 1.82 | 44 | 9 | 38.60 | 2.94 | True |
| macd_hist_pct <= -0.457  AND  gap_pct >= 0.558 | 41 | 39.00 | 2.41 | 26 | 15 | 34.60 | 2.63 | True |
| dist_ema20 <= -2.51 | 269 | 21.60 | 1.33 | 113 | 29 | 31.90 | 2.42 | True |
| macd_hist_pct <= -0.457 | 269 | 26.00 | 1.61 | 93 | 19 | 30.10 | 2.29 | True |
| bb_width >= 12.5 | 269 | 26.40 | 1.63 | 100 | 12 | 29.00 | 2.21 | True |
| vix_pctile >= 91.2  AND  bb_width_pctile >= 90 | 109 | 32.10 | 1.98 | 83 | 8 | 28.90 | 2.20 | True |
| vix >= 24  AND  gap_pct >= 0.558 | 60 | 30.00 | 1.85 | 53 | 8 | 28.30 | 2.15 | True |
| ret5 <= -2.83 | 269 | 23.00 | 1.42 | 113 | 35 | 27.40 | 2.09 | True |
| dist_ema200 <= -4.33 | 269 | 25.70 | 1.58 | 127 | 12 | 25.20 | 1.92 | True |
| dist_ema20 <= -1.34  AND  gap_pct >= 0.558 | 68 | 29.40 | 1.81 | 44 | 27 | 25.00 | 1.90 | True |
| vix_pctile >= 91.2 | 274 | 22.30 | 1.37 | 150 | 13 | 24.70 | 1.88 | True |
| atr_pct >= 1.8  AND  gap_pct >= 0.558 | 84 | 31.00 | 1.91 | 66 | 13 | 24.20 | 1.84 | True |
| macd_hist_pct <= -0.294  AND  gap_pct >= 0.558 | 77 | 31.20 | 1.92 | 54 | 29 | 24.10 | 1.83 | True |
| bb_width >= 10  AND  gap_pct >= 0.558 | 69 | 29.00 | 1.79 | 54 | 17 | 24.10 | 1.83 | True |

**Year by year for the best rule** (`vix_pctile >= 91.2  AND  gap_pct >= 0.558`) — check it isn't one crash year doing all the work:

| year | signals | hit_rate | base_rate |
|---|---|---|---|
| 2011 | 14 | 57.1 | 31.0 |
| 2013 | 12 | 33.3 | 21.1 |
| 2014 | 1 | 0.0 | 7.9 |
| 2015 | 9 | 33.3 | 20.5 |
| 2016 | 3 | 0.0 | 11.1 |
| 2018 | 10 | 20.0 | 15.1 |
| 2019 | 2 | 0.0 | 10.0 |
| 2020 | 14 | 42.9 | 18.8 |
| 2022 | 6 | 33.3 | 21.0 |
| 2024 | 3 | 33.3 | 10.2 |
| 2025 | 3 | 0.0 | 4.4 |
| 2026 | 4 | 75.0 | 16.7 |

**Active on 2026-10-01:** dist_ema200 <= -4.33; macd_hist_pct <= -0.294; dist_ema20 <= -1.34; dist_ema200 <= -1.18; dist_ema20 <= -2.51


## LARGE MOVE (either way)

Base rate — how often a large move (either way) follows *any* random day: train **32.8%**, test **25.6%**. A useful signal has to beat this number.

### Step 1 – What indicators looked like the day BEFORE the move (train years)

effect_size: how different the pre-move days are from normal days (|0.2| small, |0.5| medium, |0.8| large).

| feature | before_big_move_median | normal_day_median | effect_size |
|---|---|---|---|
| atr_pct | 1.55 | 1.16 | 0.81 |
| vix | 20.91 | 16.33 | 0.80 |
| bb_width | 7.79 | 5.82 | 0.53 |
| vix_pctile | 56.22 | 36.95 | 0.42 |
| rsi14 | 50.10 | 56.70 | -0.40 |
| di_diff | -2.77 | 4.65 | -0.34 |
| dist_ema200 | 2.57 | 5.33 | -0.33 |
| dist_ema20 | -0.02 | 0.86 | -0.20 |
| macd_hist_pct | -0.07 | 0.03 | -0.19 |
| ret5 | -0.08 | 0.52 | -0.15 |

### Step 2 & 3 – Rules found in train years, then tested on unseen years

`test_lift` = test hit rate ÷ test base rate. 1.0 = no better than random. `test_episodes` counts separate occasions (back-to-back signal days count once). `holds_up` = lift ≥ 1.25 with ≥ 8 episodes.

| rule | train_signals | train_hit_rate% | train_lift | test_signals | test_episodes | test_hit_rate% | test_lift | holds_up |
|---|---|---|---|---|---|---|---|---|
| vix >= 24 | 538 | 63.80 | 1.94 | 156 | 10 | 69.20 | 2.70 | True |
| dist_ema20 <= -2.51 | 269 | 55.40 | 1.69 | 113 | 29 | 69.00 | 2.69 | True |
| dist_ema200 <= -4.33 | 269 | 66.20 | 2.02 | 127 | 12 | 66.90 | 2.61 | True |
| macd_hist_pct <= -0.457 | 269 | 55.00 | 1.68 | 93 | 19 | 61.30 | 2.39 | True |
| bb_width >= 12.5 | 269 | 64.70 | 1.97 | 100 | 12 | 61.00 | 2.38 | True |
| atr_pct >= 1.8 | 537 | 62.00 | 1.89 | 197 | 9 | 56.90 | 2.22 | True |
| vix_pctile >= 91.2 | 274 | 51.50 | 1.57 | 150 | 13 | 56.00 | 2.18 | True |
| ret5 <= -2.83 | 269 | 51.30 | 1.56 | 113 | 35 | 55.80 | 2.18 | True |
| ret1 <= -1.19 | 269 | 49.40 | 1.51 | 135 | 63 | 48.10 | 1.88 | True |
| rsi14 <= 37.1 | 269 | 44.20 | 1.35 | 154 | 27 | 48.10 | 1.87 | True |
| dist_ema200 <= -1.18 | 537 | 53.60 | 1.63 | 299 | 11 | 46.80 | 1.83 | True |
| bb_width >= 10 | 537 | 52.30 | 1.59 | 220 | 17 | 46.80 | 1.83 | True |
| dist_ema20 <= -1.34 | 537 | 50.80 | 1.55 | 268 | 41 | 44.80 | 1.75 | True |
| bb_width_pctile >= 90 | 273 | 46.20 | 1.41 | 200 | 16 | 44.00 | 1.72 | True |
| ret1 >= 1.29 | 269 | 48.00 | 1.46 | 119 | 66 | 42.90 | 1.67 | True |

**Year by year for the best rule** (`vix >= 24`) — check it isn't one crash year doing all the work:

| year | signals | hit_rate | base_rate |
|---|---|---|---|
| 2009 | 238 | 70.2 | 69.3 |
| 2010 | 52 | 59.6 | 38.0 |
| 2011 | 107 | 69.2 | 54.1 |
| 2012 | 49 | 46.9 | 31.4 |
| 2013 | 37 | 62.2 | 41.7 |
| 2014 | 25 | 36.0 | 25.0 |
| 2015 | 14 | 50.0 | 34.8 |
| 2016 | 2 | 100.0 | 22.5 |
| 2019 | 14 | 50.0 | 20.3 |
| 2020 | 105 | 67.6 | 45.6 |
| 2021 | 13 | 53.8 | 31.9 |
| 2022 | 24 | 70.8 | 36.3 |
| 2024 | 5 | 100.0 | 15.9 |
| 2026 | 9 | 88.9 | 26.7 |

When this rule fired and a large move followed, it was UP 63% of the time and DOWN 37% — i.e. it tells you size more than direction.


**Active on 2026-10-01:** dist_ema200 <= -4.33; dist_ema20 <= -2.51; dist_ema200 <= -1.18; dist_ema20 <= -1.34; macd_hist_pct <= -0.294; ret5 <= -1.68; rsi14 <= 42.8; rsi14 <= 37.1; di_diff <= -11.6; vix_chg5 >= 13.2; ret1 <= -0.726


## Summary

| target | base_test% | rules_tested | rules_holding_up | best_rule | best_test_hit% |
|---|---|---|---|---|---|
| LARGE RISE | 12.50 | 50 | 22 | vix >= 24 | 47.40 |
| LARGE FALL | 13.10 | 43 | 25 | vix_pctile >= 91.2  AND  gap_pct >= 0.558 | 40.00 |
| LARGE MOVE (either way) | 25.60 | 50 | 22 | vix >= 24 | 69.20 |

## Latest indicator readings

| feature | value |
|---|---|
| vol_ratio | 1.56 |
| vol_trend | 1.31 |
| atr_pct | 1.09 |
| atr_expansion | 1.24 |
| range_vs_atr | 1.81 |
| bb_width | 6.91 |
| bb_width_pctile | 84.34 |
| nr7 | 0.00 |
| vix | 14.46 |
| vix_pctile | 72.29 |
| vix_chg5 | 13.95 |
| vix_chg1 | 7.19 |
| rsi14 | 22.57 |
| adx14 | 38.73 |
| di_diff | -35.03 |
| macd_hist_pct | -0.32 |
| dist_ema20 | -3.44 |
| dist_ema200 | -7.18 |
| ret1 | -0.88 |
| ret5 | -2.78 |
| gap_pct | -0.34 |
| close_in_range | 0.52 |

## How to read this honestly

- Train-period numbers are always flattering — they were picked *because* they looked good. Only the test columns tell you if the pattern is real.
- Most indicators that 'react before' big moves are **volatility** measures (VIX, ATR expansion, BB width). They tend to predict that a *big move* is likely, not *which direction*.
- Big moves cluster: once volatility is high, more big days follow. That is a real effect, but it means the signal often fires *during* a move rather than before its first day.
- Index volume from Yahoo only exists from 2013 and is not true traded volume; Nifty futures volume/OI would be a better input if you have it.
