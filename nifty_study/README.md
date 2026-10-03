# Nifty reverse-engineering study

Question: *before Nifty makes a large move, do volume, volatility or indicators
show something first? And if they show the same values again, does a large move
follow again?*

## How it works

1. **Label big moves.** For every day, check whether Nifty reaches ±`move`% within the
   next `horizon` days *and* the day-`horizon` close is in the same direction.
2. **Reverse-engineer.** Compare indicator values at the close of the day *before* each
   big move with normal days. The indicators are volume ratio, ATR %, ATR expansion,
   Bollinger width/squeeze, NR7, India VIX level/percentile/change, RSI, ADX/DI, MACD
   histogram, distance from EMA20/EMA200, recent returns, gap, and close-in-range.
3. **Build rules** such as `vix >= 24 AND atr_pct >= 1.8`, with thresholds taken
   **only from the training years** (default: 2009–2019).
4. **Test the same rules** on unseen years (2020 to today) and compare the hit rate
   with the base rate for those years. Results are also shown year by year, so you can
   see whether one crash year accounts for most of the hits.

## Run

```bash
pip install pandas numpy yfinance
python nifty_reverse_engineer.py                       # 5 days, ±3%
python nifty_reverse_engineer.py --fresh               # only moves that start from a calm market
python nifty_reverse_engineer.py --horizon 3 --move 2  # other definitions of "large"
python nifty_reverse_engineer.py --split 2017-01-01    # other train/test split
python nifty_reverse_engineer.py --refresh             # re-download data
```

The report is written to `output/report.md`. Rule tables and the full feature table are
saved as CSV files.

## Main findings (data to 2026-10-01, 5 days, ±3%)

* **Volatility comes before big moves. Volume mostly does not.** ATR %, India VIX and
  Bollinger width show the largest differences before big moves (effect size about
  0.8). Volume ratio is a weak signal on index data.
* **The rules held up on unseen years.** `vix >= 24` was followed by a large move 69% of
  the time in 2020–2026, against a 26% base rate. It has worked in most years, not only
  in 2020.
* **They predict size, not direction.** The same conditions come before both large rises
  and large falls. After a large move, a rule's hit rate for "rise" and its hit rate for
  "fall" are both high. A volatility signal tells you *a big move is likely*; it does
  not tell you which way.
* **Few independent occasions.** `test_episodes` is often only 10–20. Treat each rule as
  a hint, not a proven edge.
* **Large falls from a calm market (`--fresh`) are the hardest to see in advance.** Only
  8 of 47 fall rules survive the test, and the best one (`dist_ema200 >= 13.3`, meaning
  the market is stretched far above its 200-day EMA) is inconsistent from year to year.

Not financial advice. This is a research tool, and past behaviour does not guarantee
future moves.
