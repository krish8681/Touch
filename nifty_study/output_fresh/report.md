# Nifty reverse-engineering study

Data: 2009-01-02 → 2026-10-01 (4355 days). Train: before 2020-01-01. Test (unseen): from 2020-01-01.

**Large move** = within the next **5 trading days** Nifty reaches **±3.0%** from today's close *and* the day-5 close is in the same direction. All indicator values are taken at today's close, so nothing looks into the future.

**Fresh mode:** days where Nifty had already moved ≥ 1.5% over the previous 5 days are excluded, so only moves starting from a calm market count.


## LARGE RISE

Base rate — how often a large rise follows *any* random day: train **13.0%**, test **10.8%**. A useful signal has to beat this number.

### Step 1 – What indicators looked like the day BEFORE the move (train years)

effect_size: how different the pre-move days are from normal days (|0.2| small, |0.5| medium, |0.8| large).

| feature | before_big_move_median | normal_day_median | effect_size |
|---|---|---|---|
| atr_pct | 1.58 | 1.07 | 0.96 |
| vix | 20.88 | 15.91 | 0.92 |
| bb_width | 8.05 | 5.39 | 0.66 |
| rsi14 | 50.75 | 56.50 | -0.45 |
| vix_pctile | 51.00 | 32.73 | 0.45 |
| dist_ema200 | 2.45 | 5.87 | -0.37 |
| di_diff | -3.23 | 4.41 | -0.36 |
| ret5 | -0.08 | 0.20 | -0.29 |
| bb_width_pctile | 49.40 | 37.75 | 0.24 |
| dist_ema20 | 0.05 | 0.74 | -0.23 |

### Step 2 & 3 – Rules found in train years, then tested on unseen years

`test_lift` = test hit rate ÷ test base rate. 1.0 = no better than random. `test_episodes` counts separate occasions (back-to-back signal days count once). `holds_up` = lift ≥ 1.25 with ≥ 8 episodes.

| rule | train_signals | train_hit_rate% | train_lift | test_signals | test_episodes | test_hit_rate% | test_lift | holds_up |
|---|---|---|---|---|---|---|---|---|
| vix >= 24  AND  vix_pctile >= 78.5 | 71 | 43.70 | 3.35 | 29 | 8 | 55.20 | 5.10 | True |
| vix >= 24  AND  atr_pct >= 1.8 | 123 | 45.50 | 3.49 | 33 | 8 | 54.50 | 5.04 | True |
| vix >= 24  AND  bb_width >= 10 | 79 | 40.50 | 3.11 | 22 | 9 | 54.50 | 5.04 | True |
| dist_ema200 <= -4.33  AND  rsi14 <= 42.8 | 44 | 40.90 | 3.14 | 21 | 8 | 52.40 | 4.84 | True |
| vix >= 24 | 174 | 40.20 | 3.08 | 49 | 14 | 49.00 | 4.53 | True |
| atr_pct >= 1.8  AND  vix_pctile >= 78.5 | 66 | 42.40 | 3.25 | 37 | 9 | 48.60 | 4.50 | True |
| dist_ema200 <= -4.33 | 67 | 40.30 | 3.09 | 42 | 10 | 45.20 | 4.18 | True |
| atr_pct >= 1.8  AND  bb_width_pctile >= 79.9 | 61 | 41.00 | 3.14 | 41 | 11 | 41.50 | 3.83 | True |
| atr_pct >= 1.8  AND  ret1 <= -0.726 | 50 | 42.00 | 3.22 | 17 | 13 | 41.20 | 3.81 | True |
| vix >= 24  AND  ret1 <= -0.726 | 48 | 43.80 | 3.35 | 16 | 13 | 37.50 | 3.47 | True |
| atr_pct >= 1.8  AND  ret1 >= 1.29 | 42 | 42.90 | 3.29 | 11 | 11 | 36.40 | 3.36 | True |
| atr_pct >= 1.8 | 178 | 36.00 | 2.76 | 73 | 15 | 34.20 | 3.17 | True |
| macd_hist_pct <= -0.457 | 62 | 29.00 | 2.23 | 15 | 13 | 33.30 | 3.08 | True |
| vix_pctile >= 91.2 | 98 | 26.50 | 2.03 | 53 | 15 | 30.20 | 2.79 | True |
| dist_ema200 <= -1.18  AND  vix_pctile >= 78.5 | 61 | 41.00 | 3.14 | 57 | 10 | 28.10 | 2.60 | True |

**Year by year for the best rule** (`vix >= 24  AND  vix_pctile >= 78.5`) — check it isn't one crash year doing all the work:

| year | signals | hit_rate | base_rate |
|---|---|---|---|
| 2009 | 7 | 71.4 | 41.2 |
| 2011 | 23 | 47.8 | 24.7 |
| 2012 | 2 | 0.0 | 13.8 |
| 2013 | 15 | 53.3 | 20.0 |
| 2014 | 14 | 28.6 | 14.3 |
| 2015 | 5 | 20.0 | 11.8 |
| 2019 | 5 | 40.0 | 6.9 |
| 2020 | 16 | 43.8 | 19.8 |
| 2022 | 7 | 71.4 | 17.6 |
| 2024 | 1 | 0.0 | 4.1 |
| 2026 | 5 | 80.0 | 12.1 |

**Active on 2026-10-01:** dist_ema200 <= -4.33; dist_ema20 <= -1.34; dist_ema200 <= -1.18; macd_hist_pct <= -0.294; bb_width_pctile >= 79.9; rsi14 <= 42.8; di_diff <= -11.6; ret1 <= -0.726; dist_ema200 <= -4.33  AND  rsi14 <= 42.8


## LARGE FALL

Base rate — how often a large fall follows *any* random day: train **14.3%**, test **12.6%**. A useful signal has to beat this number.

### Step 1 – What indicators looked like the day BEFORE the move (train years)

effect_size: how different the pre-move days are from normal days (|0.2| small, |0.5| medium, |0.8| large).

| feature | before_big_move_median | normal_day_median | effect_size |
|---|---|---|---|
| vol_trend | 1.01 | 0.96 | 0.35 |
| vix | 17.99 | 16.07 | 0.32 |
| rsi14 | 53.43 | 56.17 | -0.31 |
| atr_pct | 1.26 | 1.10 | 0.29 |
| vol_ratio | 0.99 | 0.91 | 0.25 |
| di_diff | 0.65 | 4.00 | -0.25 |
| gap_pct | 0.01 | 0.10 | -0.21 |
| macd_hist_pct | -0.07 | -0.01 | -0.20 |
| adx14 | 21.79 | 24.64 | -0.20 |
| bb_width | 5.72 | 5.61 | 0.19 |

### Step 2 & 3 – Rules found in train years, then tested on unseen years

`test_lift` = test hit rate ÷ test base rate. 1.0 = no better than random. `test_episodes` counts separate occasions (back-to-back signal days count once). `holds_up` = lift ≥ 1.25 with ≥ 8 episodes.

| rule | train_signals | train_hit_rate% | train_lift | test_signals | test_episodes | test_hit_rate% | test_lift | holds_up |
|---|---|---|---|---|---|---|---|---|
| dist_ema200 >= 13.3 | 118 | 19.50 | 1.37 | 71 | 8 | 26.80 | 2.12 | True |
| bb_width >= 12.5 | 76 | 28.90 | 2.03 | 37 | 12 | 24.30 | 1.93 | True |
| atr_pct >= 1.8 | 178 | 20.20 | 1.42 | 73 | 15 | 21.90 | 1.74 | True |
| gap_pct <= -0.361 | 115 | 21.70 | 1.52 | 111 | 72 | 21.60 | 1.71 | True |
| dist_ema200 <= -4.33 | 67 | 19.40 | 1.36 | 42 | 10 | 21.40 | 1.70 | True |
| atr_pct >= 1.8  AND  vix >= 24 | 123 | 20.30 | 1.42 | 33 | 8 | 21.20 | 1.68 | True |
| vix >= 24 | 174 | 19.00 | 1.33 | 49 | 14 | 20.40 | 1.62 | True |
| ret1 <= -1.19 | 93 | 20.40 | 1.43 | 40 | 33 | 17.50 | 1.39 | True |
| bb_width >= 10  AND  dist_ema200 >= 13.3 | 45 | 26.70 | 1.87 | 16 | 3 | 37.50 | 2.97 | False |
| vix >= 28.8 | 70 | 20.00 | 1.40 | 19 | 4 | 36.80 | 2.92 | False |
| atr_pct >= 1.8  AND  vix >= 28.8 | 54 | 25.90 | 1.82 | 19 | 4 | 36.80 | 2.92 | False |
| atr_pct >= 2.23  AND  vix >= 28.8 | 50 | 28.00 | 1.96 | 15 | 3 | 33.30 | 2.64 | False |
| macd_hist_pct <= -0.294  AND  bb_width >= 10 | 49 | 32.70 | 2.29 | 10 | 5 | 30.00 | 2.38 | False |
| atr_pct >= 2.23 | 72 | 23.60 | 1.66 | 19 | 5 | 26.30 | 2.09 | False |
| atr_pct >= 2.23  AND  vix >= 24 | 71 | 23.90 | 1.68 | 19 | 5 | 26.30 | 2.09 | False |

**Year by year for the best rule** (`dist_ema200 >= 13.3`) — check it isn't one crash year doing all the work:

| year | signals | hit_rate | base_rate |
|---|---|---|---|
| 2009 | 47 | 31.9 | 26.5 |
| 2010 | 20 | 25.0 | 18.0 |
| 2014 | 51 | 5.9 | 6.8 |
| 2020 | 13 | 15.4 | 17.9 |
| 2021 | 58 | 29.3 | 19.0 |

**Active on 2026-10-01:** dist_ema200 <= -4.33


## LARGE MOVE (either way)

Base rate — how often a large move (either way) follows *any* random day: train **27.3%**, test **23.4%**. A useful signal has to beat this number.

### Step 1 – What indicators looked like the day BEFORE the move (train years)

effect_size: how different the pre-move days are from normal days (|0.2| small, |0.5| medium, |0.8| large).

| feature | before_big_move_median | normal_day_median | effect_size |
|---|---|---|---|
| vix | 19.24 | 15.56 | 0.77 |
| atr_pct | 1.39 | 1.05 | 0.77 |
| bb_width | 7.05 | 5.28 | 0.53 |
| rsi14 | 51.89 | 56.74 | -0.45 |
| vix_pctile | 46.99 | 31.73 | 0.37 |
| di_diff | -1.41 | 5.16 | -0.37 |
| dist_ema200 | 3.52 | 6.12 | -0.24 |
| dist_ema20 | 0.26 | 0.78 | -0.24 |
| gap_pct | 0.01 | 0.11 | -0.21 |
| macd_hist_pct | -0.06 | -0.01 | -0.18 |

### Step 2 & 3 – Rules found in train years, then tested on unseen years

`test_lift` = test hit rate ÷ test base rate. 1.0 = no better than random. `test_episodes` counts separate occasions (back-to-back signal days count once). `holds_up` = lift ≥ 1.25 with ≥ 8 episodes.

| rule | train_signals | train_hit_rate% | train_lift | test_signals | test_episodes | test_hit_rate% | test_lift | holds_up |
|---|---|---|---|---|---|---|---|---|
| vix >= 24  AND  atr_pct >= 1.8 | 123 | 65.90 | 2.41 | 33 | 8 | 75.80 | 3.23 | True |
| vix >= 24 | 174 | 59.20 | 2.17 | 49 | 14 | 69.40 | 2.96 | True |
| dist_ema200 <= -4.33 | 67 | 59.70 | 2.19 | 42 | 10 | 66.70 | 2.84 | True |
| atr_pct >= 1.8  AND  ret1 <= -0.726 | 50 | 66.00 | 2.42 | 17 | 13 | 64.70 | 2.76 | True |
| atr_pct >= 1.8  AND  bb_width >= 10 | 94 | 61.70 | 2.26 | 25 | 8 | 64.00 | 2.73 | True |
| vix >= 24  AND  bb_width >= 10 | 79 | 63.30 | 2.32 | 22 | 9 | 63.60 | 2.72 | True |
| atr_pct >= 1.8 | 178 | 56.20 | 2.06 | 73 | 15 | 56.20 | 2.40 | True |
| vix >= 24  AND  ret1 <= -0.726 | 48 | 66.70 | 2.44 | 16 | 13 | 56.20 | 2.40 | True |
| atr_pct >= 1.8  AND  bb_width_pctile >= 79.9 | 61 | 60.70 | 2.22 | 41 | 11 | 51.20 | 2.19 | True |
| bb_width >= 12.5 | 76 | 71.10 | 2.60 | 37 | 12 | 48.60 | 2.08 | True |
| dist_ema200 >= 13.3 | 118 | 39.00 | 1.43 | 71 | 8 | 46.50 | 1.98 | True |
| bb_width >= 10 | 181 | 48.60 | 1.78 | 82 | 19 | 42.70 | 1.82 | True |
| vix_pctile >= 91.2 | 98 | 42.90 | 1.57 | 53 | 15 | 41.50 | 1.77 | True |
| macd_hist_pct >= 0.473 | 53 | 35.80 | 1.31 | 29 | 13 | 41.40 | 1.77 | True |
| bb_width >= 12.5  AND  bb_width_pctile >= 79.9 | 50 | 68.00 | 2.49 | 29 | 10 | 41.40 | 1.77 | True |

**Year by year for the best rule** (`vix >= 24  AND  atr_pct >= 1.8`) — check it isn't one crash year doing all the work:

| year | signals | hit_rate | base_rate |
|---|---|---|---|
| 2009 | 48 | 79.2 | 67.6 |
| 2010 | 14 | 78.6 | 35.9 |
| 2011 | 29 | 65.5 | 52.9 |
| 2012 | 12 | 16.7 | 26.6 |
| 2013 | 15 | 60.0 | 42.9 |
| 2015 | 5 | 40.0 | 36.3 |
| 2020 | 21 | 76.2 | 37.7 |
| 2021 | 2 | 0.0 | 35.9 |
| 2022 | 5 | 100.0 | 37.0 |
| 2026 | 5 | 80.0 | 25.0 |

When this rule fired and a large move followed, it was UP 70% of the time and DOWN 30% — i.e. it tells you size more than direction.


**Active on 2026-10-01:** dist_ema200 <= -4.33; dist_ema20 <= -1.34; rsi14 <= 37.1; dist_ema200 <= -1.18; rsi14 <= 42.8; bb_width_pctile >= 79.9; vix_chg5 >= 13.2


## Summary

| target | base_test% | rules_tested | rules_holding_up | best_rule | best_test_hit% |
|---|---|---|---|---|---|
| LARGE RISE | 10.80 | 50 | 33 | vix >= 24  AND  vix_pctile >= 78.5 | 55.20 |
| LARGE FALL | 12.60 | 47 | 8 | dist_ema200 >= 13.3 | 26.80 |
| LARGE MOVE (either way) | 23.40 | 50 | 28 | vix >= 24  AND  atr_pct >= 1.8 | 75.80 |

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
