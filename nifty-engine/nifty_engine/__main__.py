"""Command line entry point.

    python -m nifty_engine serve      [--host 0.0.0.0 --port 8000]
    python -m nifty_engine train      [--days 120 --seed 1 --out models/direction_model.json]
    python -m nifty_engine backtest   [--days 20 --seed 2 --model models/direction_model.json]
    python -m nifty_engine signal     one decision on synthetic data, printed as JSON
    python -m nifty_engine dhan-download --start 2021-10-01 --end 2026-10-01
    python -m nifty_engine dhan-check
    python -m nifty_engine train    --source dhan --start 2021-10-01 --end 2025-10-01
    python -m nifty_engine backtest --source dhan --start 2025-10-01 --end 2026-10-01 --model models/direction_model.json

`train` and `backtest` use the synthetic market by default (plumbing checks
only) or downloaded DhanHQ history with `--source dhan`. Always backtest on a
period that comes after the training period.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import date

from .config import Settings


def _synthetic(days: int, seed: int):
    from .data.synthetic import SyntheticMarket

    market = SyntheticMarket(seed=seed)
    spot, fut = market.generate(date(2025, 1, 6), days)
    return market, spot, fut


def _market(args: argparse.Namespace, with_chain: bool = True):
    """(spot candles, futures candles or None, chain provider or None, label) for --source synthetic|dhan."""
    from .backtest.replay import synthetic_chain_provider

    if args.source == "dhan":
        from .data.dhan_store import DhanChainProvider, DhanStore

        store = DhanStore(args.data, series=args.series)
        start = date.fromisoformat(args.start) if args.start else None
        end = date.fromisoformat(args.end) if args.end else None
        spot = store.index_candles(start, end)
        if not spot:
            raise SystemExit(f"no Dhan index candles under {args.data} for {args.start}..{args.end}; run dhan-download first")
        provider = DhanChainProvider(store, spread_bps=args.spread_bps) if with_chain else None
        return spot, None, provider, f"dhan:{args.series}:{spot[0].start.date()}..{spot[-1].start.date()}"
    market, spot, fut = _synthetic(args.days, args.seed)
    return spot, fut, synthetic_chain_provider(market) if with_chain else None, f"synthetic:seed{args.seed}:{args.days}d"


def cmd_train(args: argparse.Namespace) -> int:
    from .backtest.replay import build_training_set
    from .engines.direction import DirectionEngine

    spot, fut, provider, label = _market(args, with_chain=args.with_chain or args.source == "dhan")
    print(f"building features from {label} ({len(spot):,} minutes)…")
    X, labels, _ = build_training_set(spot, fut, provider, step=args.step)
    eng = DirectionEngine(uses_futures=fut is not None, trained_on=label)
    reports = eng.train(X, labels)
    for h, r in reports.items():
        print(f"horizon {h or 'EOD':>3}: n={r.n_train}/{r.n_valid} T={r.temperature:.2f} brier={r.brier:.4f} "
              f"(baseline {r.brier_baseline:.4f}, {'beats' if r.beats_baseline else 'does NOT beat'} baseline) "
              f"ECE={r.ece:.3f}")
    eng.save(args.out)
    print(f"saved {args.out}")
    return 0


def cmd_backtest(args: argparse.Namespace) -> int:
    from .backtest.replay import ReplayEngine
    from .engines.decision import DecisionEngine
    from .engines.direction import DirectionEngine

    settings = Settings()
    if args.lot_size:
        settings.lot_size = args.lot_size
    spot, fut, provider, label = _market(args)
    direction = DirectionEngine.load(args.model) if args.model else DirectionEngine()
    if direction.trained_on:
        print(f"model trained on {direction.trained_on}; replaying {label} — make sure the periods don't overlap")
    engine = DecisionEngine(settings, direction)
    result = ReplayEngine(settings, engine, provider, eval_every=args.every).run(spot, fut)
    trades = sum(1 for s in result.signals if s.action == "TRADE")
    print(f"decisions {len(result.signals)}, trade signals {trades}")
    print(result.report.summary())
    for name, st in result.report.by_strategy.items():
        print(f"  {name:<18} trades {st['trades']:>3}  net ₹{st['net_pnl']:>10,.0f}  win {st['win_rate']:.0%}")
    print("  exits:", result.report.by_exit_reason)
    return 0


def cmd_signal(args: argparse.Namespace) -> int:
    from .backtest.replay import synthetic_chain_provider
    from .data.candles import MultiTimeframe
    from .engines.decision import DecisionEngine, MarketSnapshot
    from .engines.direction import DirectionEngine
    from datetime import timedelta

    settings = Settings()
    market, spot, fut = _synthetic(3, args.seed)
    mtf, fmtf = MultiTimeframe((1, 5)), MultiTimeframe((1,))
    upto = 375 * 2 + args.minute
    for c, f in zip(spot[:upto], fut[:upto]):
        mtf.add_one_minute(c)
        fmtf.add_one_minute(f)
    last = spot[upto - 1]
    now = last.start + timedelta(minutes=1)
    chain = synthetic_chain_provider(market)(now, last.close)
    engine = DecisionEngine(settings, DirectionEngine.load(args.model) if args.model else DirectionEngine())
    sig = engine.evaluate(MarketSnapshot(now, mtf[1].arrays(), mtf[5].arrays(), chain, fmtf[1].arrays()))
    out = sig.to_dict()
    out["context"].pop("features", None)
    print(json.dumps(out, indent=2))
    return 0


def cmd_dhan_download(args: argparse.Namespace) -> int:
    from .data.dhan import DhanClient, DhanDownloader, DhanError, DownloadPlan

    settings = Settings.from_env()
    if not settings.dhan_access_token:
        raise SystemExit("set DHAN_ACCESS_TOKEN (and DHAN_CLIENT_ID) in .env — generate the token on web.dhan.co → DhanHQ Trading APIs")
    client = DhanClient(settings.dhan_access_token, settings.dhan_client_id, per_second=args.rate)
    plan = DownloadPlan(date.fromisoformat(args.start), date.fromisoformat(args.end), strikes=args.strikes,
                        expiry_flag=args.expiry_flag, expiry_code=args.expiry_code, interval=args.interval)
    n_chunks = -(-((plan.end - plan.start).days) // plan.option_days)
    print(f"downloading {plan.start}..{plan.end} (exclusive) into {args.data}: about {n_chunks * (2 * plan.strikes + 1) * 2:,} "
          f"option requests at {args.rate}/s; safe to interrupt and re-run")
    try:
        rep = DhanDownloader(client, args.data).run(plan, progress=print)
    except DhanError as exc:
        raise SystemExit(f"Dhan API error: {exc}\n(806/DH-902 = no Data API subscription; 807-810/DH-901 = token invalid or "
                         "expired). Completed chunks are saved; re-run to resume.") from exc
    finally:
        client.close()
    print(f"done: option chunks {rep.option_chunks_done} new / {rep.option_chunks_skipped} already present, "
          f"index chunks {rep.index_chunks_done} new / {rep.index_chunks_skipped} present, {rep.rows:,} rows, "
          f"{len(rep.empty_series)} empty series, {client.calls} API calls")
    return 0


def cmd_dhan_check(args: argparse.Namespace) -> int:
    from collections import Counter

    from .data.dhan_store import DhanStore, coverage_report

    store = DhanStore(args.data, series=args.series)
    rows = coverage_report(store, date.fromisoformat(args.start) if args.start else None,
                           date.fromisoformat(args.end) if args.end else None)
    if not rows:
        print("no data found")
        return 1
    low = [r for r in rows if r["coverage"] < args.min_coverage]
    methods = Counter(r.get("expiry_method") for r in rows)
    errs = sorted(r["iv_error"] for r in rows if r.get("iv_error") is not None)
    print(f"{len(rows)} trading days, {rows[0]['day']} … {rows[-1]['day']}")
    print(f"mean coverage {sum(r['coverage'] for r in rows) / len(rows):.1%}; {len(low)} days below {args.min_coverage:.0%}")
    print(f"expiry resolution: {dict(methods)}; IV scale {store.iv_scale}; "
          f"median IV error {errs[len(errs) // 2]:.4f}" if errs else f"expiry resolution: {dict(methods)}")
    for r in low[:args.show]:
        print(f"  low coverage {r['day']}: {r['coverage']:.1%} ({r['minutes']} minutes, {r.get('series', 0)} series)")
    if args.csv:
        import csv

        with open(args.csv, "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=sorted({k for r in rows for k in r}))
            w.writeheader()
            w.writerows(rows)
        print(f"wrote {args.csv}")
    return 0


def cmd_serve(args: argparse.Namespace) -> int:
    import uvicorn

    from .api.app import create_app

    uvicorn.run(create_app(), host=args.host, port=args.port)
    return 0


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    p = argparse.ArgumentParser(prog="nifty_engine")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8000)
    s.set_defaults(fn=cmd_serve)

    def source_args(sp: argparse.ArgumentParser, days: int, seed: int) -> None:
        sp.add_argument("--source", choices=("synthetic", "dhan"), default="synthetic")
        sp.add_argument("--data", default="data/dhan", help="Dhan download directory (--source dhan)")
        sp.add_argument("--series", default="WEEK1_1m", help="Dhan option series folder (--source dhan)")
        sp.add_argument("--start", help="first day, YYYY-MM-DD (--source dhan)")
        sp.add_argument("--end", help="end day, exclusive, YYYY-MM-DD (--source dhan)")
        sp.add_argument("--spread-bps", type=float, default=50.0, help="assumed bid/ask spread for Dhan bars")
        sp.add_argument("--days", type=int, default=days, help="synthetic days")
        sp.add_argument("--seed", type=int, default=seed, help="synthetic seed")

    t = sub.add_parser("train")
    source_args(t, 120, 1)
    t.add_argument("--step", type=int, default=5)
    t.add_argument("--with-chain", action="store_true", help="include option-chain features (slower)")
    t.add_argument("--out", default="models/direction_model.json")
    t.set_defaults(fn=cmd_train)

    b = sub.add_parser("backtest")
    source_args(b, 20, 2)
    b.add_argument("--lot-size", type=int, default=0, help="override lot size (NIFTY lot size changed over the years)")
    b.add_argument("--every", type=int, default=5)
    b.add_argument("--model", default=None)
    b.set_defaults(fn=cmd_backtest)

    d = sub.add_parser("dhan-download", help="download expired NIFTY options + index minute data from DhanHQ")
    d.add_argument("--start", required=True)
    d.add_argument("--end", required=True, help="exclusive")
    d.add_argument("--data", default="data/dhan")
    d.add_argument("--strikes", type=int, default=10, help="ATM-N … ATM+N (max 10 for index options)")
    d.add_argument("--expiry-flag", choices=("WEEK", "MONTH"), default="WEEK")
    d.add_argument("--expiry-code", type=int, default=1)
    d.add_argument("--interval", type=int, choices=(1, 5, 15, 25, 60), default=1)
    d.add_argument("--rate", type=float, default=4.0, help="requests per second")
    d.set_defaults(fn=cmd_dhan_download)

    c = sub.add_parser("dhan-check", help="coverage and expiry-resolution report for downloaded Dhan data")
    c.add_argument("--data", default="data/dhan")
    c.add_argument("--series", default="WEEK1_1m")
    c.add_argument("--start")
    c.add_argument("--end")
    c.add_argument("--min-coverage", type=float, default=0.8)
    c.add_argument("--show", type=int, default=20)
    c.add_argument("--csv", help="write the per-day report to this CSV")
    c.set_defaults(fn=cmd_dhan_check)

    g = sub.add_parser("signal")
    g.add_argument("--seed", type=int, default=3)
    g.add_argument("--minute", type=int, default=120, help="minute of the 3rd session to evaluate at")
    g.add_argument("--model", default=None)
    g.set_defaults(fn=cmd_signal)

    args = p.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
