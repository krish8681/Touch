"""Command line entry point.

    python -m nifty_engine serve      [--host 0.0.0.0 --port 8000]
    python -m nifty_engine train      [--days 120 --seed 1 --out models/direction_model.json]
    python -m nifty_engine backtest   [--days 20 --seed 2 --model models/direction_model.json]
    python -m nifty_engine signal     one decision on synthetic data, printed as JSON

`train` and `backtest` run on the synthetic market unless real data loaders are
plugged in (see docs/SPEC.md §8). Synthetic results validate the plumbing, not
the profitability of any strategy.
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


def cmd_train(args: argparse.Namespace) -> int:
    from .backtest.replay import build_training_set, synthetic_chain_provider
    from .engines.direction import DirectionEngine

    market, spot, fut = _synthetic(args.days, args.seed)
    X, labels, _ = build_training_set(spot, fut, synthetic_chain_provider(market) if args.with_chain else None, step=args.step)
    eng = DirectionEngine()
    reports = eng.train(X, labels)
    for h, r in reports.items():
        print(f"horizon {h or 'EOD':>3}: n={r.n_train}/{r.n_valid} T={r.temperature:.2f} brier={r.brier:.4f} "
              f"(baseline {r.brier_baseline:.4f}, {'beats' if r.beats_baseline else 'does NOT beat'} baseline) "
              f"ECE={r.ece:.3f}")
    eng.save(args.out)
    print(f"saved {args.out}")
    return 0


def cmd_backtest(args: argparse.Namespace) -> int:
    from .backtest.replay import ReplayEngine, synthetic_chain_provider
    from .engines.decision import DecisionEngine
    from .engines.direction import DirectionEngine

    settings = Settings()
    market, spot, fut = _synthetic(args.days, args.seed)
    direction = DirectionEngine.load(args.model) if args.model else DirectionEngine()
    engine = DecisionEngine(settings, direction)
    result = ReplayEngine(settings, engine, synthetic_chain_provider(market), eval_every=args.every).run(spot, fut)
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

    t = sub.add_parser("train")
    t.add_argument("--days", type=int, default=120)
    t.add_argument("--seed", type=int, default=1)
    t.add_argument("--step", type=int, default=5)
    t.add_argument("--with-chain", action="store_true", help="include option-chain features (slower)")
    t.add_argument("--out", default="models/direction_model.json")
    t.set_defaults(fn=cmd_train)

    b = sub.add_parser("backtest")
    b.add_argument("--days", type=int, default=20)
    b.add_argument("--seed", type=int, default=2)
    b.add_argument("--every", type=int, default=5)
    b.add_argument("--model", default=None)
    b.set_defaults(fn=cmd_backtest)

    g = sub.add_parser("signal")
    g.add_argument("--seed", type=int, default=3)
    g.add_argument("--minute", type=int, default=120, help="minute of the 3rd session to evaluate at")
    g.add_argument("--model", default=None)
    g.set_defaults(fn=cmd_signal)

    args = p.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
