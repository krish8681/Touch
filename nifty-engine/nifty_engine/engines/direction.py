"""Direction probability engine.

Each horizon (15/30/60 min, end of day) has its own multinomial logistic
regression over the feature vector, followed by temperature scaling fitted
on a held-out, *later* slice of data (time-ordered split, no shuffling, so
there is no look-ahead). Probabilities are therefore empirical frequencies,
not hand-tuned scores.

Until a model has been trained, a conservative heuristic prior is used and
every forecast is flagged `calibrated=False`; the risk engine refuses live
trading on uncalibrated forecasts.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from ..models import Direction, DirectionForecast
from .features import FEATURE_NAMES, FeatureSet

HORIZONS = (15, 30, 60, 0)   # 0 = end of day
CLASSES = (Direction.UP, Direction.SIDEWAYS, Direction.DOWN)
LABEL_BAND = 0.5             # sideways band = LABEL_BAND × ATR(1m) × sqrt(horizon minutes)


def label_threshold(atr_1m: float, horizon_minutes: int) -> float:
    return LABEL_BAND * atr_1m * math.sqrt(max(horizon_minutes, 1))


def make_label(move_points: float, atr_1m: float, horizon_minutes: int) -> int:
    """0 = UP, 1 = SIDEWAYS, 2 = DOWN (index into CLASSES)."""
    thr = label_threshold(atr_1m, horizon_minutes)
    if move_points > thr:
        return 0
    if move_points < -thr:
        return 2
    return 1


def _softmax(z: np.ndarray) -> np.ndarray:
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


@dataclass
class SoftmaxRegression:
    l2: float = 1e-2
    lr: float = 0.05
    epochs: int = 600
    mean: np.ndarray | None = None
    std: np.ndarray | None = None
    W: np.ndarray | None = None
    b: np.ndarray | None = None
    temperature: float = 1.0

    def _x(self, X: np.ndarray) -> np.ndarray:
        return (X - self.mean) / self.std

    def fit(self, X: np.ndarray, y: np.ndarray, sample_weight: np.ndarray | None = None) -> "SoftmaxRegression":
        n, d = X.shape
        k = len(CLASSES)
        self.mean = X.mean(axis=0)
        self.std = X.std(axis=0)
        self.std[self.std < 1e-9] = 1.0
        Xs = self._x(X)
        Y = np.eye(k)[y]
        w = np.ones(n) if sample_weight is None else sample_weight / sample_weight.mean()
        W = np.zeros((d, k))
        b = np.log(np.clip(Y.mean(axis=0), 1e-3, None))
        # Adam
        mW = np.zeros_like(W); vW = np.zeros_like(W); mb = np.zeros_like(b); vb = np.zeros_like(b)
        b1, b2, eps = 0.9, 0.999, 1e-8
        for t in range(1, self.epochs + 1):
            P = _softmax(Xs @ W + b)
            G = (P - Y) * w[:, None] / n
            gW = Xs.T @ G + self.l2 * W
            gb = G.sum(axis=0)
            mW = b1 * mW + (1 - b1) * gW; vW = b2 * vW + (1 - b2) * gW * gW
            mb = b1 * mb + (1 - b1) * gb; vb = b2 * vb + (1 - b2) * gb * gb
            W -= self.lr * (mW / (1 - b1**t)) / (np.sqrt(vW / (1 - b2**t)) + eps)
            b -= self.lr * (mb / (1 - b1**t)) / (np.sqrt(vb / (1 - b2**t)) + eps)
        self.W, self.b = W, b
        return self

    def logits(self, X: np.ndarray) -> np.ndarray:
        return self._x(np.atleast_2d(X)) @ self.W + self.b

    def predict_proba(self, X: np.ndarray) -> np.ndarray:
        return _softmax(self.logits(X) / self.temperature)

    def calibrate_temperature(self, X: np.ndarray, y: np.ndarray) -> float:
        """Fit a single temperature on held-out data by minimising log loss (grid + refine)."""
        L = self.logits(X)
        def nll(T: float) -> float:
            P = _softmax(L / T)
            return -np.mean(np.log(np.clip(P[np.arange(len(y)), y], 1e-12, None)))
        grid = np.exp(np.linspace(np.log(0.3), np.log(5.0), 60))
        best = min(grid, key=nll)
        lo, hi = best / 1.1, best * 1.1
        for _ in range(30):
            m1, m2 = lo + (hi - lo) / 3, hi - (hi - lo) / 3
            if nll(m1) < nll(m2):
                hi = m2
            else:
                lo = m1
        self.temperature = float((lo + hi) / 2)
        return self.temperature

    def to_dict(self) -> dict:
        return {"l2": self.l2, "mean": self.mean.tolist(), "std": self.std.tolist(), "W": self.W.tolist(),
                "b": self.b.tolist(), "temperature": self.temperature}

    @classmethod
    def from_dict(cls, d: dict) -> "SoftmaxRegression":
        m = cls(l2=d["l2"])
        m.mean, m.std = np.array(d["mean"]), np.array(d["std"])
        m.W, m.b = np.array(d["W"]), np.array(d["b"])
        m.temperature = d["temperature"]
        return m


# ── calibration diagnostics ────────────────────────────────────────────────

def brier_score(P: np.ndarray, y: np.ndarray) -> float:
    Y = np.eye(P.shape[1])[y]
    return float(np.mean(np.sum((P - Y) ** 2, axis=1)))


def log_loss(P: np.ndarray, y: np.ndarray) -> float:
    return float(-np.mean(np.log(np.clip(P[np.arange(len(y)), y], 1e-12, None))))


def reliability(P: np.ndarray, y: np.ndarray, cls: int = 0, bins: int = 10) -> list[dict]:
    """Predicted vs observed frequency for one class, per probability bin."""
    p = P[:, cls]
    hit = (y == cls).astype(float)
    out = []
    edges = np.linspace(0, 1, bins + 1)
    for lo, hi in zip(edges[:-1], edges[1:]):
        mask = (p >= lo) & (p < hi) if hi < 1 else (p >= lo) & (p <= hi)
        if mask.sum():
            out.append({"bin": f"{lo:.1f}-{hi:.1f}", "n": int(mask.sum()), "predicted": float(p[mask].mean()),
                        "observed": float(hit[mask].mean())})
    return out


def expected_calibration_error(P: np.ndarray, y: np.ndarray, bins: int = 10) -> float:
    conf = P.max(axis=1)
    correct = (P.argmax(axis=1) == y).astype(float)
    ece = 0.0
    edges = np.linspace(0, 1, bins + 1)
    for lo, hi in zip(edges[:-1], edges[1:]):
        mask = (conf > lo) & (conf <= hi)
        if mask.any():
            ece += mask.mean() * abs(conf[mask].mean() - correct[mask].mean())
    return float(ece)


# ── engine ─────────────────────────────────────────────────────────────────

@dataclass
class TrainReport:
    horizon: int
    n_train: int
    n_valid: int
    class_balance: list[float]
    temperature: float
    brier: float
    brier_baseline: float      # predicting training class frequencies
    log_loss: float
    ece: float
    reliability_up: list[dict] = field(default_factory=list)

    @property
    def beats_baseline(self) -> bool:
        return self.brier < self.brier_baseline


class DirectionEngine:
    def __init__(self, models: dict[int, SoftmaxRegression] | None = None, reports: dict[int, TrainReport] | None = None):
        self.models = models or {}
        self.reports = reports or {}

    @property
    def trained(self) -> bool:
        return all(h in self.models for h in HORIZONS)

    def train(self, X: np.ndarray, labels: dict[int, np.ndarray], valid_frac: float = 0.25,
              l2: float = 1e-2) -> dict[int, TrainReport]:
        """X rows must be in time order. labels[h] uses -1 for rows without a label (end of data)."""
        for h in HORIZONS:
            y_all = labels[h]
            mask = y_all >= 0
            Xh, yh = X[mask], y_all[mask]
            split = int(len(yh) * (1 - valid_frac))
            if split < 50 or len(yh) - split < 20:
                raise ValueError(f"not enough labelled rows for horizon {h}: {len(yh)}")
            Xt, yt, Xv, yv = Xh[:split], yh[:split], Xh[split:], yh[split:]
            counts = np.bincount(yt, minlength=3).astype(float)
            prior = counts / counts.sum()
            m = SoftmaxRegression(l2=l2).fit(Xt, yt)
            m.calibrate_temperature(Xv, yv)
            Pv = m.predict_proba(Xv)
            base = np.tile(prior, (len(yv), 1))
            self.models[h] = m
            self.reports[h] = TrainReport(
                horizon=h, n_train=len(yt), n_valid=len(yv), class_balance=prior.round(4).tolist(),
                temperature=m.temperature, brier=brier_score(Pv, yv), brier_baseline=brier_score(base, yv),
                log_loss=log_loss(Pv, yv), ece=expected_calibration_error(Pv, yv), reliability_up=reliability(Pv, yv, 0),
            )
        return self.reports

    def predict(self, features: FeatureSet, trend_score: float | None = None) -> list[DirectionForecast]:
        x = features.vector()
        out = []
        for h in HORIZONS:
            m = self.models.get(h)
            rep = self.reports.get(h)
            if m is not None and rep is not None and not rep.beats_baseline:
                # No demonstrated skill on held-out data: report base rates, which carry no directional edge.
                up, side, down = rep.class_balance
                out.append(DirectionForecast(h, up, side, down, calibrated=True))
            elif m is not None:
                p = m.predict_proba(x)[0]
                out.append(DirectionForecast(h, float(p[0]), float(p[1]), float(p[2]), calibrated=True))
            else:
                out.append(self.heuristic(h, trend_score if trend_score is not None else 0.0))
        return out

    @staticmethod
    def heuristic(horizon: int, trend_score: float) -> DirectionForecast:
        """Conservative prior: trend score tilts UP/DOWN, edge decays with horizon. Never above ~62%."""
        decay = {15: 1.0, 30: 0.9, 60: 0.75, 0: 0.6}.get(horizon, 0.6)
        tilt = math.tanh(1.6 * trend_score) * decay
        side = 0.34 - 0.10 * abs(tilt)
        directional = 1 - side
        up = directional * (0.5 + 0.35 * tilt)
        return DirectionForecast(horizon, up, side, directional - up, calibrated=False)

    def save(self, path: str | Path) -> None:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "features": list(FEATURE_NAMES),
            "label_band": LABEL_BAND,
            "models": {str(h): m.to_dict() for h, m in self.models.items()},
            "reports": {str(h): r.__dict__ for h, r in self.reports.items()},
        }
        path.write_text(json.dumps(payload))

    @classmethod
    def load(cls, path: str | Path) -> "DirectionEngine":
        path = Path(path)
        if not path.exists():
            return cls()
        payload = json.loads(path.read_text())
        if payload.get("features") != list(FEATURE_NAMES):
            raise ValueError("saved model was trained on a different feature set; retrain it")
        models = {int(h): SoftmaxRegression.from_dict(d) for h, d in payload["models"].items()}
        reports = {int(h): TrainReport(**r) for h, r in payload.get("reports", {}).items()}
        return cls(models, reports)
