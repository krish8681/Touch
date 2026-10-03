#!/usr/bin/env python3
"""
Nifty "reverse engineering" study.

Idea
----
1. Find every day after which Nifty made a LARGE move (up or down) over the
   next H trading days.
2. Look at what the indicators (volume, volatility, VIX, RSI, ADX, MACD,
   Bollinger squeeze, ...) were showing at the CLOSE of that day, i.e. BEFORE
   the move started.
3. Turn those observations into simple rules ("VIX percentile > 80 AND
   ATR5/ATR20 > 1.2") using ONLY the training years.
4. Apply the exact same rules and thresholds to later, unseen years and check
   whether a large move really followed more often than normal.

Step 4 is what separates a real edge from a pattern that only looks good in
hindsight. A rule is only interesting if its hit rate in the TEST years is
clearly above the TEST base rate.

Usage
-----
    pip install pandas numpy yfinance
    python nifty_reverse_engineer.py                    # defaults
    python nifty_reverse_engineer.py --horizon 3 --move 2.5
    python nifty_reverse_engineer.py --split 2018-01-01

Outputs go to nifty_study/output/ (CSV files + report.md).
"""
import argparse
import itertools
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))


# ─────────────────────────── data ───────────────────────────
def load_data(cache_dir, refresh=False):
    """Daily Nifty OHLCV + India VIX close. Cached to CSV."""
    path = os.path.join(cache_dir, "nifty_vix_daily.csv")
    if os.path.exists(path) and not refresh:
        return pd.read_csv(path, index_col=0, parse_dates=True)

    import yfinance as yf

    def dl(sym):
        d = yf.download(sym, period="max", interval="1d", progress=False, auto_adjust=False)
        if isinstance(d.columns, pd.MultiIndex):
            d.columns = d.columns.get_level_values(0)
        return d

    n = dl("^NSEI")[["Open", "High", "Low", "Close", "Volume"]]
    v = dl("^INDIAVIX")[["Close"]].rename(columns={"Close": "VIX"})
    df = n.join(v, how="left")
    df["VIX"] = df["VIX"].ffill()
    df = df[df["Close"] > 0].dropna(subset=["Open", "High", "Low", "Close"])
    df["Volume"] = df["Volume"].replace(0, np.nan)  # index volume missing before 2013
    os.makedirs(cache_dir, exist_ok=True)
    df.to_csv(path)
    return df


# ───────────────────────── indicators ─────────────────────────
def rsi(close, n=14):
    d = close.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    return 100 - 100 / (1 + up / dn)


def true_range(df):
    pc = df["Close"].shift()
    return pd.concat([df["High"] - df["Low"], (df["High"] - pc).abs(), (df["Low"] - pc).abs()], axis=1).max(axis=1)


def adx(df, n=14):
    up = df["High"].diff()
    dn = -df["Low"].diff()
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    ndm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = true_range(df).ewm(alpha=1 / n, adjust=False).mean()
    pdi = 100 * pd.Series(pdm, df.index).ewm(alpha=1 / n, adjust=False).mean() / tr
    ndi = 100 * pd.Series(ndm, df.index).ewm(alpha=1 / n, adjust=False).mean() / tr
    dx = 100 * (pdi - ndi).abs() / (pdi + ndi)
    return dx.ewm(alpha=1 / n, adjust=False).mean(), pdi - ndi


def rolling_pct_rank(s, window):
    """Percentile (0-100) of today's value within the trailing window. No look-ahead."""
    return s.rolling(window, min_periods=window // 2).apply(lambda a: (a[:-1] < a[-1]).mean() * 100, raw=True)


def build_features(df):
    f = pd.DataFrame(index=df.index)
    c, h, l, o, v = df["Close"], df["High"], df["Low"], df["Open"], df["Volume"]
    tr = true_range(df)
    atr5, atr14, atr20 = tr.rolling(5).mean(), tr.rolling(14).mean(), tr.rolling(20).mean()

    # ── volume ──
    f["vol_ratio"] = v / v.rolling(20).mean()              # today's volume vs 20d avg
    f["vol_trend"] = v.rolling(5).mean() / v.rolling(20).mean()

    # ── volatility ──
    f["atr_pct"] = atr14 / c * 100                          # ATR as % of price
    f["atr_expansion"] = atr5 / atr20                       # >1 = volatility rising
    f["range_vs_atr"] = (h - l) / atr20                     # today's range vs normal
    ma20, sd20 = c.rolling(20).mean(), c.rolling(20).std()
    f["bb_width"] = 4 * sd20 / ma20 * 100
    f["bb_width_pctile"] = rolling_pct_rank(f["bb_width"], 250)  # low = squeeze
    rng = h - l
    f["nr7"] = (rng == rng.rolling(7).min()).astype(float)  # narrowest range in 7 days
    f["vix"] = df["VIX"]
    f["vix_pctile"] = rolling_pct_rank(df["VIX"], 250)
    f["vix_chg5"] = df["VIX"].pct_change(5) * 100
    f["vix_chg1"] = df["VIX"].pct_change(1) * 100

    # ── momentum / trend ──
    f["rsi14"] = rsi(c, 14)
    f["adx14"], f["di_diff"] = adx(df, 14)
    ema12, ema26 = c.ewm(span=12, adjust=False).mean(), c.ewm(span=26, adjust=False).mean()
    macd = ema12 - ema26
    f["macd_hist_pct"] = (macd - macd.ewm(span=9, adjust=False).mean()) / c * 100
    f["dist_ema20"] = (c / c.ewm(span=20, adjust=False).mean() - 1) * 100
    f["dist_ema200"] = (c / c.ewm(span=200, adjust=False).mean() - 1) * 100
    f["ret1"] = c.pct_change(1) * 100
    f["ret5"] = c.pct_change(5) * 100
    f["gap_pct"] = (o / c.shift() - 1) * 100
    f["close_in_range"] = (c - l) / (h - l).replace(0, np.nan)   # 0 = closed at low, 1 = at high
    return f


def build_labels(df, horizon, move_pct):
    """Forward move over the next `horizon` days, measured from today's close.

    big_up   : highest high in next H days >= +move%  AND close after H days is up
    big_down : lowest low  in next H days <= -move%  AND close after H days is down
    Using the H-day close confirms the move "stuck" instead of being one spike.
    """
    c = df["Close"]
    fwd_ret = (c.shift(-horizon) / c - 1) * 100
    fwd_high = df["High"][::-1].rolling(horizon).max()[::-1].shift(-1)
    fwd_low = df["Low"][::-1].rolling(horizon).min()[::-1].shift(-1)
    mfe_up = (fwd_high / c - 1) * 100
    mfe_dn = (fwd_low / c - 1) * 100
    lab = pd.DataFrame(index=df.index)
    lab["fwd_ret"] = fwd_ret
    lab["fwd_max_up"] = mfe_up
    lab["fwd_max_dn"] = mfe_dn
    lab["big_up"] = ((mfe_up >= move_pct) & (fwd_ret > 0)).astype(float)
    lab["big_down"] = ((mfe_dn <= -move_pct) & (fwd_ret < 0)).astype(float)
    lab["big_any"] = ((lab["big_up"] + lab["big_down"]) > 0).astype(float)
    lab.loc[fwd_ret.isna(), ["big_up", "big_down", "big_any"]] = np.nan
    return lab


# ─────────────────────── reverse engineering ───────────────────────
def profile_before_moves(feat, lab, target):
    """Average indicator values on the day BEFORE big moves vs. all other days."""
    rows = []
    hit = lab[target] == 1
    rest = lab[target] == 0
    for col in feat.columns:
        a, b = feat.loc[hit, col].dropna(), feat.loc[rest, col].dropna()
        if len(a) < 10 or len(b) < 10:
            continue
        pooled = np.sqrt((a.var() + b.var()) / 2) or np.nan
        rows.append({
            "feature": col,
            "before_big_move_median": a.median(),
            "normal_day_median": b.median(),
            "effect_size": (a.mean() - b.mean()) / pooled,   # >0.3 or < -0.3 is notable
        })
    return pd.DataFrame(rows).sort_values("effect_size", key=abs, ascending=False)


def candidate_conditions(feat_train, quantiles=(0.1, 0.2, 0.8, 0.9)):
    """Single conditions like 'vix_pctile >= <train 80th pct>'. Thresholds come from TRAIN only."""
    conds = []
    for col in feat_train.columns:
        s = feat_train[col].dropna()
        if s.nunique() <= 2:  # binary flag
            conds.append((col, "==", 1.0))
            continue
        for q in quantiles:
            thr = float(s.quantile(q))
            conds.append((col, "<=" if q < 0.5 else ">=", thr))
    return conds


def mask_for(feat, cond):
    col, op, thr = cond
    s = feat[col]
    if op == "<=":
        return s <= thr
    if op == ">=":
        return s >= thr
    return s == thr


def cond_str(cond):
    col, op, thr = cond
    return f"{col} {op} {thr:.3g}"


def episodes(mask, gap):
    """Number of separate signal clusters (signals closer than `gap` days count once)."""
    idx = np.flatnonzero(mask.values)
    if len(idx) == 0:
        return 0
    return int(1 + (np.diff(idx) > gap).sum())


def evaluate(mask, y):
    m = mask & y.notna()
    n = int(m.sum())
    if n == 0:
        return n, np.nan
    return n, float(y[m].mean())


def search_rules(feat_tr, y_tr, max_pairs_from=40, min_n=40, min_lift=1.3):
    base = y_tr.mean()
    singles = []
    for cnd in candidate_conditions(feat_tr):
        n, hr = evaluate(mask_for(feat_tr, cnd), y_tr)
        if n >= min_n and hr / base >= min_lift:
            singles.append(((cnd,), n, hr))
    singles.sort(key=lambda r: r[2], reverse=True)

    # combine the strongest single conditions into pairs (different features only)
    pairs = []
    top = [s[0][0] for s in singles[:max_pairs_from]]
    for a, b in itertools.combinations(top, 2):
        if a[0] == b[0]:
            continue
        n, hr = evaluate(mask_for(feat_tr, a) & mask_for(feat_tr, b), y_tr)
        if n >= min_n and hr / base >= min_lift:
            pairs.append(((a, b), n, hr))
    pairs.sort(key=lambda r: r[2], reverse=True)
    return singles, pairs


def walk_forward(rules, feat_tr, y_tr, feat_te, y_te, horizon):
    base_tr, base_te = y_tr.mean(), y_te.mean()
    out = []
    for conds, n_tr, hr_tr in rules:
        m_te = pd.Series(True, index=feat_te.index)
        for c in conds:
            m_te &= mask_for(feat_te, c)
        n_te, hr_te = evaluate(m_te, y_te)
        out.append({
            "rule": "  AND  ".join(cond_str(c) for c in conds),
            "train_signals": n_tr,
            "train_hit_rate%": round(hr_tr * 100, 1),
            "train_lift": round(hr_tr / base_tr, 2),
            "test_signals": n_te,
            "test_episodes": episodes(m_te & y_te.notna(), horizon),
            "test_hit_rate%": round(hr_te * 100, 1) if n_te else np.nan,
            "test_lift": round(hr_te / base_te, 2) if n_te else np.nan,
        })
    df = pd.DataFrame(out)
    # A rule "holds up" when it still beats the test base rate with a usable sample.
    df["holds_up"] = (df["test_lift"] >= 1.25) & (df["test_episodes"] >= 8)
    return df.sort_values(["holds_up", "test_lift"], ascending=False)


# ─────────────────────────── report ───────────────────────────
def df_to_md(df, floatfmt=".2f"):
    cols = list(df.columns)
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for r in df.itertuples(index=False):  # itertuples keeps int columns as ints
        cells = []
        for v in r:
            cells.append(f"{v:{floatfmt}}" if isinstance(v, (float, np.floating)) and not pd.isna(v) else str(v))
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--horizon", type=int, default=5, help="days ahead to look for the move (default 5)")
    ap.add_argument("--move", type=float, default=3.0, help="what counts as a LARGE move, in %% (default 3.0)")
    ap.add_argument("--split", default="2020-01-01", help="train before / test from this date")
    ap.add_argument("--start", default="2009-01-01", help="ignore data before this date")
    ap.add_argument("--fresh", action="store_true",
                    help="only score days where the market had NOT already moved >= move/2 %% in the last H days "
                         "(tests 'before the move starts' rather than 'during a move')")
    ap.add_argument("--refresh", action="store_true", help="re-download data")
    ap.add_argument("--out", default=os.path.join(HERE, "output"))
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    raw = load_data(args.out, args.refresh)
    feat = build_features(raw)
    lab = build_labels(raw, args.horizon, args.move)
    if args.fresh:
        moved = (raw["Close"].pct_change(args.horizon).abs() * 100) >= args.move / 2
        lab.loc[moved, ["big_up", "big_down", "big_any"]] = np.nan
    keep = feat.index >= args.start
    feat, lab, raw = feat[keep], lab[keep], raw[keep]
    tr, te = feat.index < args.split, feat.index >= args.split

    rep = []
    P = rep.append
    P(f"# Nifty reverse-engineering study\n")
    P(f"Data: {feat.index[0].date()} → {feat.index[-1].date()} ({len(feat)} days). "
      f"Train: before {args.split}. Test (unseen): from {args.split}.\n")
    P(f"**Large move** = within the next **{args.horizon} trading days** Nifty reaches "
      f"**±{args.move}%** from today's close *and* the day-{args.horizon} close is in the same direction. "
      f"All indicator values are taken at today's close, so nothing looks into the future.\n")
    if args.fresh:
        P(f"**Fresh mode:** days where Nifty had already moved ≥ {args.move/2}% over the previous "
          f"{args.horizon} days are excluded, so only moves starting from a calm market count.\n")

    # Latest reading (what do the indicators say right now?)
    latest = feat.iloc[-1]

    summary_rows = []
    for target, name in [("big_up", "LARGE RISE"), ("big_down", "LARGE FALL"), ("big_any", "LARGE MOVE (either way)")]:
        y = lab[target]
        y_tr, y_te = y[tr], y[te]
        P(f"\n## {name}\n")
        P(f"Base rate — how often a {name.lower()} follows *any* random day: "
          f"train **{y_tr.mean()*100:.1f}%**, test **{y_te.mean()*100:.1f}%**. "
          f"A useful signal has to beat this number.\n")

        prof = profile_before_moves(feat[tr], lab[tr], target)
        prof.to_csv(os.path.join(args.out, f"profile_{target}.csv"), index=False)
        P("### Step 1 – What indicators looked like the day BEFORE the move (train years)\n")
        P("effect_size: how different the pre-move days are from normal days "
          "(|0.2| small, |0.5| medium, |0.8| large).\n")
        P(df_to_md(prof.head(10)))

        singles, pairs = search_rules(feat[tr], y_tr)
        rules = singles[:25] + pairs[:25]
        if not rules:
            P("\nNo rule passed the training filter.\n")
            continue
        wf = walk_forward(rules, feat[tr], y_tr, feat[te], y_te, args.horizon)
        wf.to_csv(os.path.join(args.out, f"rules_{target}.csv"), index=False)
        P("\n### Step 2 & 3 – Rules found in train years, then tested on unseen years\n")
        P("`test_lift` = test hit rate ÷ test base rate. 1.0 = no better than random. "
          "`test_episodes` counts separate occasions (back-to-back signal days count once). "
          "`holds_up` = lift ≥ 1.25 with ≥ 8 episodes.\n")
        P(df_to_md(wf.head(15)))

        good = wf[wf["holds_up"]]
        if len(good):
            best = next(r for r in rules if "  AND  ".join(cond_str(c) for c in r[0]) == good.iloc[0]["rule"])
            m = pd.Series(True, index=feat.index)
            for c in best[0]:
                m &= mask_for(feat, c)
            m &= y.notna()
            yr = pd.DataFrame({"signal": m, "hit": y.where(m), "base": y})
            by = yr.groupby(yr.index.year).agg(signals=("signal", "sum"), hit_rate=("hit", "mean"), base_rate=("base", "mean"))
            by["hit_rate"] = (by["hit_rate"] * 100).round(1)
            by["base_rate"] = (by["base_rate"] * 100).round(1)
            by = by[by["signals"] > 0].astype({"signals": int})
            by.index.name = "year"
            by = by.reset_index()
            P(f"\n**Year by year for the best rule** (`{good.iloc[0]['rule']}`) — check it isn't one crash year doing all the work:\n")
            P(df_to_md(by, ".1f"))
            if target == "big_any":
                fr = lab.loc[m & (lab["big_any"] == 1), "fwd_ret"]
                P(f"\nWhen this rule fired and a large move followed, it was UP {(fr > 0).mean()*100:.0f}% "
                  f"of the time and DOWN {(fr < 0).mean()*100:.0f}% — i.e. it tells you size more than direction.\n")
        summary_rows.append({"target": name, "base_test%": round(y_te.mean() * 100, 1),
                             "rules_tested": len(wf), "rules_holding_up": len(good),
                             "best_rule": good.iloc[0]["rule"] if len(good) else "-",
                             "best_test_hit%": good.iloc[0]["test_hit_rate%"] if len(good) else np.nan})

        # Which of the surviving rules are ON today?
        on_now = []
        for conds, _, _ in rules:
            r = "  AND  ".join(cond_str(c) for c in conds)
            if r in set(good["rule"]) and all(bool(mask_for(feat.iloc[[-1]], c).iloc[0]) for c in conds):
                on_now.append(r)
        P(f"\n**Active on {feat.index[-1].date()}:** " + ("; ".join(on_now) if on_now else "none of the surviving rules") + "\n")

    P("\n## Summary\n")
    P(df_to_md(pd.DataFrame(summary_rows)))
    P("\n## Latest indicator readings\n")
    P(df_to_md(latest.round(3).to_frame("value").reset_index().rename(columns={"index": "feature"})))
    P("\n## How to read this honestly\n")
    P("- Train-period numbers are always flattering — they were picked *because* they looked good. "
      "Only the test columns tell you if the pattern is real.\n"
      "- Most indicators that 'react before' big moves are **volatility** measures (VIX, ATR expansion, "
      "BB width). They tend to predict that a *big move* is likely, not *which direction*.\n"
      "- Big moves cluster: once volatility is high, more big days follow. That is a real effect, "
      "but it means the signal often fires *during* a move rather than before its first day.\n"
      "- Index volume from Yahoo only exists from 2013 and is not true traded volume; "
      "Nifty futures volume/OI would be a better input if you have it.\n")

    text = "\n".join(rep)
    with open(os.path.join(args.out, "report.md"), "w") as fh:
        fh.write(text)
    feat.join(lab).to_csv(os.path.join(args.out, "features_and_labels.csv"))
    print(text)
    print(f"\nSaved to {args.out}/", file=sys.stderr)


if __name__ == "__main__":
    main()
