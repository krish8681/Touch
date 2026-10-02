"""DhanHQ v2 data client and bulk downloader for historical NIFTY options.

Two Dhan endpoints are used:

* ``POST /v2/charts/rollingoption`` — expired option contracts, minute bars for
  ATM-10 … ATM+10 strikes (labels are relative to spot and roll with it; every bar
  carries the actual ``strike``), with OHLC, volume, OI, IV and spot. Up to 30 days
  per call, about 5 years of history.
* ``POST /v2/charts/intraday`` — NIFTY 50 index minute candles (IDX_I / 13),
  up to 90 days per call.

Requires a Dhan account with Data API access (free with 25+ trades in the last
30 days, otherwise a paid monthly subscription). The access token and client id
come from ``DHAN_ACCESS_TOKEN`` / ``DHAN_CLIENT_ID`` and never leave the backend.

Downloads are written as gzipped CSV chunks plus a manifest, so an interrupted
download resumes where it stopped.
"""

from __future__ import annotations

import csv
import gzip
import json
import logging
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx

log = logging.getLogger(__name__)

API_ROOT = "https://api.dhan.co"
NIFTY_SECURITY_ID = 13          # NIFTY 50 underlying id for both IDX_I and NSE_FNO option lookups
OPTION_FIELDS = ["open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot"]
OPTION_COLUMNS = ["ts", "type", "offset", "strike", "open", "high", "low", "close", "volume", "oi", "iv", "spot"]
INDEX_COLUMNS = ["ts", "open", "high", "low", "close"]
RETRYABLE_CODES = {"805", "DH-904", "800", "DH-908", "DH-909"}


class DhanError(RuntimeError):
    def __init__(self, message: str, code: str = "", status: int = 0):
        super().__init__(message)
        self.code = code
        self.status = status

    @property
    def retryable(self) -> bool:
        return self.status in (429, 500, 502, 503, 504) or self.code in RETRYABLE_CODES


def strike_label(offset: int) -> str:
    return "ATM" if offset == 0 else f"ATM{offset:+d}"


class DhanClient:
    def __init__(self, access_token: str, client_id: str = "", per_second: float = 4.0, timeout: float = 30.0,
                 max_retries: int = 5, transport: httpx.BaseTransport | None = None, sleep=time.sleep):
        if not access_token:
            raise DhanError("DHAN_ACCESS_TOKEN is not set")
        headers = {"Content-Type": "application/json", "Accept": "application/json", "access-token": access_token}
        if client_id:
            headers["client-id"] = client_id
        self._http = httpx.Client(base_url=API_ROOT, headers=headers, timeout=timeout, transport=transport)
        self._interval = 1.0 / per_second
        self._next = 0.0
        self._sleep = sleep
        self.max_retries = max_retries
        self.calls = 0

    def close(self) -> None:
        self._http.close()

    def _throttle(self) -> None:
        now = time.monotonic()
        if now < self._next:
            self._sleep(self._next - now)
        self._next = max(now, self._next) + self._interval

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        delay = 2.0
        for attempt in range(self.max_retries + 1):
            self._throttle()
            self.calls += 1
            try:
                resp = self._http.post(path, json=body)
            except httpx.TransportError as exc:
                err = DhanError(f"network error: {exc}", status=503)
            else:
                try:
                    payload = resp.json()
                except ValueError:
                    payload = {}
                if resp.status_code == 200 and payload.get("status") not in ("failure", "error"):
                    return payload
                code = str(payload.get("errorCode") or payload.get("error_code") or "")
                msg = payload.get("errorMessage") or payload.get("remarks") or resp.text[:300]
                err = DhanError(f"{path} → HTTP {resp.status_code} {code}: {msg}", code, resp.status_code)
            if not err.retryable or attempt == self.max_retries:
                raise err
            log.warning("%s; retrying in %.0fs", err, delay)
            self._sleep(delay)
            delay = min(delay * 2, 60)
        raise AssertionError("unreachable")

    # ── endpoints ──────────────────────────────────────────────────────
    def rolling_option(self, *, offset: int, option_type: str, from_date: date, to_date: date,
                       expiry_flag: str = "WEEK", expiry_code: int = 1, interval: int = 1,
                       security_id: int = NIFTY_SECURITY_ID) -> dict[str, list]:
        """Bars for one relative strike and side. `to_date` is exclusive. Returns parallel arrays (may be empty)."""
        payload = self._post("/v2/charts/rollingoption", {
            "exchangeSegment": "NSE_FNO",
            "interval": str(interval),
            "securityId": security_id,
            "instrument": "OPTIDX",
            "expiryFlag": expiry_flag,
            "expiryCode": expiry_code,
            "strike": strike_label(offset),
            "drvOptionType": "CALL" if option_type == "CE" else "PUT",
            "requiredData": OPTION_FIELDS,
            "fromDate": from_date.isoformat(),
            "toDate": to_date.isoformat(),
        })
        data = payload.get("data") or {}
        return (data.get("ce") if option_type == "CE" else data.get("pe")) or {}

    def index_intraday(self, *, start: datetime, end: datetime, interval: int = 1,
                       security_id: int = NIFTY_SECURITY_ID) -> dict[str, list]:
        payload = self._post("/v2/charts/intraday", {
            "securityId": str(security_id),
            "exchangeSegment": "IDX_I",
            "instrument": "INDEX",
            "interval": str(interval),
            "oi": False,
            "fromDate": start.strftime("%Y-%m-%d %H:%M:%S"),
            "toDate": end.strftime("%Y-%m-%d %H:%M:%S"),
        })
        data = payload.get("data")
        return data if isinstance(data, dict) else payload


# ── bulk download ──────────────────────────────────────────────────────────

def _write_csv_gz(path: Path, columns: list[str], rows: list[list[Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    with gzip.open(tmp, "wt", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(columns)
        w.writerows(rows)
    tmp.replace(path)


def read_csv_gz(path: Path) -> list[dict[str, str]]:
    with gzip.open(path, "rt", newline="") as fh:
        return list(csv.DictReader(fh))


def date_chunks(start: date, end: date, days: int) -> list[tuple[date, date]]:
    """Half-open [from, to) windows of at most `days` days covering [start, end)."""
    out, cur = [], start
    while cur < end:
        nxt = min(cur + timedelta(days=days), end)
        out.append((cur, nxt))
        cur = nxt
    return out


def _arr(raw: dict[str, list], key: str, n: int) -> list:
    v = raw.get(key) or []
    return list(v) + [None] * (n - len(v))


def option_rows(raw: dict[str, list], option_type: str, offset: int) -> list[list[Any]]:
    ts = raw.get("timestamp") or []
    n = len(ts)
    cols = {k: _arr(raw, k, n) for k in ("strike", "open", "high", "low", "close", "volume", "oi", "iv", "spot")}
    return [[int(ts[i]), option_type, offset, cols["strike"][i], cols["open"][i], cols["high"][i], cols["low"][i],
             cols["close"][i], cols["volume"][i], cols["oi"][i], cols["iv"][i], cols["spot"][i]] for i in range(n)]


@dataclass
class DownloadPlan:
    start: date
    end: date                         # exclusive
    strikes: int = 10                 # ATM-N … ATM+N
    expiry_flag: str = "WEEK"
    expiry_code: int = 1
    interval: int = 1
    option_days: int = 30
    index_days: int = 85
    underlying: str = "NIFTY"
    security_id: int = NIFTY_SECURITY_ID

    @property
    def series_key(self) -> str:
        return f"{self.expiry_flag}{self.expiry_code}_{self.interval}m"


@dataclass
class DownloadReport:
    option_chunks_done: int = 0
    option_chunks_skipped: int = 0
    index_chunks_done: int = 0
    index_chunks_skipped: int = 0
    rows: int = 0
    empty_series: list[str] = field(default_factory=list)


class DhanDownloader:
    """Downloads into ``root/<underlying>/{index,options/<series>}/<from>_<to>.csv.gz`` with a manifest."""

    def __init__(self, client: DhanClient, root: str | Path):
        self.client = client
        self.root = Path(root)

    def _manifest_path(self, plan: DownloadPlan) -> Path:
        return self.root / plan.underlying / "manifest.json"

    def _load_manifest(self, plan: DownloadPlan) -> dict[str, Any]:
        p = self._manifest_path(plan)
        return json.loads(p.read_text()) if p.exists() else {"index": {}, "options": {}}

    def _save_manifest(self, plan: DownloadPlan, m: dict[str, Any]) -> None:
        p = self._manifest_path(plan)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(m, indent=1, sort_keys=True))

    def run(self, plan: DownloadPlan, progress=None) -> DownloadReport:
        rep = DownloadReport()
        m = self._load_manifest(plan)
        base = self.root / plan.underlying
        # Index candles
        for a, b in date_chunks(plan.start, plan.end, plan.index_days):
            key = f"{a}_{b}"
            if key in m["index"]:
                rep.index_chunks_skipped += 1
                continue
            raw = self.client.index_intraday(start=datetime.combine(a, datetime.min.time()).replace(hour=9, minute=15),
                                             end=datetime.combine(b - timedelta(days=1), datetime.min.time()).replace(hour=15, minute=30),
                                             interval=plan.interval, security_id=plan.security_id)
            ts = raw.get("timestamp") or []
            rows = [[int(ts[i]), *(_arr(raw, k, len(ts))[i] for k in ("open", "high", "low", "close"))] for i in range(len(ts))]
            _write_csv_gz(base / "index" / f"{key}.csv.gz", INDEX_COLUMNS, rows)
            m["index"][key] = {"rows": len(rows)}
            self._save_manifest(plan, m)
            rep.index_chunks_done += 1
            rep.rows += len(rows)
        # Option chunks: every relative strike × side for each window
        series = m["options"].setdefault(plan.series_key, {})
        offsets = range(-plan.strikes, plan.strikes + 1)
        for a, b in date_chunks(plan.start, plan.end, plan.option_days):
            key = f"{a}_{b}"
            if key in series and series[key].get("strikes") == plan.strikes:
                rep.option_chunks_skipped += 1
                continue
            rows: list[list[Any]] = []
            empty = 0
            for off in offsets:
                for ot in ("CE", "PE"):
                    raw = self.client.rolling_option(offset=off, option_type=ot, from_date=a, to_date=b,
                                                     expiry_flag=plan.expiry_flag, expiry_code=plan.expiry_code,
                                                     interval=plan.interval, security_id=plan.security_id)
                    r = option_rows(raw, ot, off)
                    if not r:
                        empty += 1
                        rep.empty_series.append(f"{key} {strike_label(off)} {ot}")
                    rows.extend(r)
            rows.sort(key=lambda r: (r[0], r[1], r[2]))
            _write_csv_gz(base / "options" / plan.series_key / f"{key}.csv.gz", OPTION_COLUMNS, rows)
            series[key] = {"rows": len(rows), "strikes": plan.strikes, "empty_series": empty}
            self._save_manifest(plan, m)
            rep.option_chunks_done += 1
            rep.rows += len(rows)
            if progress:
                progress(f"options {key}: {len(rows):,} rows ({empty} empty series)")
        return rep


def parse_index_rows(rows: list[dict[str, str]]) -> list[tuple[int, float, float, float, float]]:
    out = []
    for r in rows:
        try:
            out.append((int(r["ts"]), float(r["open"]), float(r["high"]), float(r["low"]), float(r["close"])))
        except (TypeError, ValueError):
            continue
    return out

