#!/usr/bin/env python3
"""Local mock of the Kite Connect endpoints the app uses. Usage: mock_kite.py PORT TODAY(yyyy-mm-dd)"""
import sys, json, math, datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]); TODAY = sys.argv[2]
GENERIC = len(sys.argv) > 3 and sys.argv[3] == "replay"   # long, generated history for the replay test
REQS = [0]
d = datetime.date.fromisoformat(TODAY)
def prev_bday(x, n=1):
    while n:
        x -= datetime.timedelta(days=1)
        if x.weekday() < 5: n -= 1
    return x
YDAY = prev_bday(d).isoformat(); Y2 = prev_bday(d, 2).isoformat()
SPOT = 25120.0
GIFT_PX = 25310.0
CALLS = [0]   # counts /quote calls so volume and OI grow between snapshots
FUT1, FUT2 = "NIFTY26OCTFUT", "NIFTY26NOVFUT"
OPT_EXP = [(TODAY, "NIFTY26929"), ((d + datetime.timedelta(days=7)).isoformat(), "NIFTY26O06")]
STOCKS = ["RELIANCE", "HDFCBANK", "ICICIBANK", "INFY", "TCS", "SBIN", "ITC", "LT", "AXISBANK", "BHARTIARTL"] + ["STK%d" % i for i in range(40)]

inst = []   # (token, symbol, expiry, strike, type)
tok = 10000
inst.append((9001, FUT1, "2026-10-27", 0, "FUT")); inst.append((9002, FUT2, "2026-11-24", 0, "FUT"))
inst.append((9003, "NIFTY26SEPFUT", "2026-09-29", 0, "FUT"))
for exp, pre in OPT_EXP:
    for k in range(24000, 26300, 50):
        for t in ("CE", "PE"):
            tok += 1; inst.append((tok, "%s%d%s" % (pre, k, t), exp, k, t))
BYSYM = {i[1]: i for i in inst}; BYTOK = {i[0]: i for i in inst}

def opt_quote(i):
    _, sym, exp, k, t = i
    dist = (k - SPOT) / 50.0
    base = 4e6 / (1 + abs(dist) * 0.35)
    oi = base * (1.5 if (t == "CE" and dist > 0) or (t == "PE" and dist < 0) else 0.6)
    if t == "PE" and 0 < -dist < 3: oi *= 1.3       # fresh put writing just below
    intrinsic = max(0, SPOT - k) if t == "CE" else max(0, k - SPOT)
    ltp = round(intrinsic + 60 * math.exp(-abs(dist) / 6), 2)
    prev = ltp * (1.15 if t == "PE" else 0.9)
    return {"instrument_token": i[0], "last_price": ltp, "oi": round(oi) + CALLS[0] * 3000, "volume": 1e6 + CALLS[0] * 2e5, "ohlc": {"open": prev, "high": ltp * 1.1, "low": ltp * .9, "close": round(prev, 2)},
            "timestamp": TODAY + " 11:00:00", "last_trade_time": TODAY + " 10:59:58"}

def quote(key):
    ex, name = key.split(":", 1)
    ts = {"timestamp": TODAY + " 11:00:00", "last_trade_time": None}
    if key == "NSE:NIFTY 50": return dict(instrument_token=256265, last_price=SPOT, ohlc=dict(open=25010, high=25140, low=24990, close=24980), **ts)
    if key == "NSEIX:GIFT NIFTY": return dict(instrument_token=291849, last_price=GIFT_PX, ohlc=dict(open=25150, high=25330, low=25120, close=25160), timestamp=TODAY + " 21:40:00", last_trade_time=TODAY + " 21:39:58")
    if key == "NSE:NIFTY BANK": return dict(instrument_token=260105, last_price=55500, ohlc=dict(open=55000, high=55600, low=54950, close=55000), **ts)
    if key == "NSE:INDIA VIX": return dict(instrument_token=264969, last_price=12.4, ohlc=dict(open=13, high=13, low=12.3, close=13.1), **ts)
    if name.startswith("NIFTY "): return dict(instrument_token=1, last_price=1010, ohlc=dict(open=1000, high=1012, low=999, close=1000), **ts)
    if ex == "NSE" and name in STOCKS:
        i = STOCKS.index(name); p = 100 * (1 + (0.6 if i < 36 else -0.5) / 100)
        return dict(instrument_token=500 + i, last_price=p, volume=1e6, average_price=100.2, ohlc=dict(open=100, high=p, low=99, close=100), **ts)
    if ex == "NFO" and name in BYSYM:
        i = BYSYM[name]
        if i[4] == "FUT":
            off = 110 if name == FUT1 else 260
            return dict(instrument_token=i[0], last_price=SPOT + off, oi=1.2e7 if name == FUT1 else 2e6, ohlc=dict(open=0, high=0, low=0, close=24980 + off - 20), **ts)
        return opt_quote(i)
    return None

def bdays(a, b):
    x = datetime.date.fromisoformat(a); e = datetime.date.fromisoformat(b); out = []
    while x <= e:
        if x.weekday() < 5: out.append(x.isoformat())
        x += datetime.timedelta(days=1)
    return out

def generic(token, interval, frm, to, oi):
    import random
    f, t = frm[0][:10], to[0][:10]
    if interval == "5minute" and (datetime.date.fromisoformat(t) - datetime.date.fromisoformat(f)).days > 100:
        return None
    base = 25000 if token in (256265, 9001, 9002, 9003) else 55000 if token == 260105 else 13 if token == 264969 else 1000 if token < 500 else 100
    out = []
    days = [d for d in bdays(f, t) if d < TODAY or interval == "day"]
    for d in days:
        r = random.Random(hash((token, d)) & 0xffffffff)
        rd = random.Random(int(d.replace("-", "")))          # market-wide direction for the day
        drift = rd.choice([-1, 0, 1]) * 0.0004
        if interval == "day":
            c = base * (1 + (int(d.replace("-", "")) % 97 - 48) / 2000.0)
            row = [d + "T00:00:00+0530", c * 0.998, c * 1.004, c * 0.995, c, 1e6]
            if oi: row.append(1.2e7 + (int(d.replace("-", "")) % 13) * 1e5)
            out.append(row)
        else:
            p = base * (1 + (int(d.replace("-", "")) % 97 - 48) / 2000.0)
            for n in range(75):
                m = 9 * 60 + 15 + 5 * n
                c = p * (1 + drift + r.gauss(0, 0.0006))
                out.append(["%sT%02d:%02d:00+0530" % (d, m // 60, m % 60), p, max(p, c) * 1.0003, min(p, c) * 0.9997, c, 10000])
                p = c
    return out

def candles(token, interval, frm, to, oi):
    out = []
    if interval == "day" and 500 <= token < 600:   # stocks: 30 business days for the 20-day average
        x = d; rows = []
        for n in range(30):
            rows.append([x.isoformat() + "T00:00:00+0530", 100, 101, 99, 99.5 + n * 0.01, 1e6]); x = prev_bday(x)
        return list(reversed(rows))
    if interval == "day":
        for day, c in ((Y2, 24900), (YDAY, 24980), (TODAY, SPOT)):
            row = [day + "T00:00:00+0530", c - 30, c + 60, c - 70, c, 0]
            if oi:
                i = BYTOK.get(token)
                if i and i[4] == "FUT": row.append(1.1e7 if i[1] == FUT1 else 1.9e6)
                elif i: row.append(round(opt_quote(i)["oi"] * (0.8 if i[4] == "PE" else 1.05)))
                else: row.append(0)
            out.append(row)
        return out
    # 5-minute bars 09:15 .. 10:55 rising
    base = 25010 + (110 if token == 9001 else 0)
    for n in range(21):
        m = 9 * 60 + 15 + 5 * n
        c = base + n * 5.5
        row = ["%sT%02d:%02d:00+0530" % (TODAY, m // 60, m % 60), c - 3, c + 6, c - 5, c, 10000 if token == 9001 else 0]
        if oi: row.append(1.0e7 * (1 + 0.003 * n) if token == 9001 else 2e6)
        out.append(row)
    return out

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body, ctype="application/json"):
        b = body.encode(); self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path.startswith("/v1beta/models"):
            return self.send(200, json.dumps({"models": [
                {"name": "models/gemini-3.5-flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.8-flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.8-flash-lite", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.9-flash-image", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/text-embedding-9", "supportedGenerationMethods": ["embedContent"]}]}))
        if self.headers.get("Authorization") != "token KEY:TOKEN":
            return self.send(403, json.dumps({"status": "error", "error_type": "TokenException", "message": "Incorrect api_key or access_token."}))
        if u.path == "/quote":
            CALLS[0] += 1
            data = {k: v for k in q.get("i", []) for v in [quote(k)] if v}
            return self.send(200, json.dumps({"status": "success", "data": data}))
        if u.path == "/instruments/NFO":
            rows = ["instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange"]
            for t, s, e, k, ty in inst:
                rows.append('%d,%d,%s,"NIFTY",0,%s,%s,0.05,75,%s,%s,NFO' % (t, t // 256, s, e, k if ty != "FUT" else 0, ty, "NFO-FUT" if ty == "FUT" else "NFO-OPT"))
            rows.append('111,1,BANKNIFTY26OCTFUT,"BANKNIFTY",0,2026-10-27,0,0.05,35,FUT,NFO-FUT,NFO')
            return self.send(200, "\n".join(rows) + "\n", "text/csv")
        if u.path.startswith("/instruments/historical/"):
            _, _, _, t, interval = u.path.split("/")
            REQS[0] += 1
            if GENERIC:
                g = generic(int(t), interval, q.get("from"), q.get("to"), q.get("oi") == ["1"])
                if g is None: return self.send(400, json.dumps({"status": "error", "error_type": "InputException", "message": "interval exceeds max limit: 100 days"}))
                return self.send(200, json.dumps({"status": "success", "data": {"candles": g}}))
            return self.send(200, json.dumps({"status": "success", "data": {"candles": candles(int(t), interval, q.get("from"), q.get("to"), q.get("oi") == ["1"])}}))
        if u.path == "/_reqs": return self.send(200, str(REQS[0]))
        self.send(404, "{}")

def do_POST(self):
    u = urlparse(self.path)
    n = int(self.headers.get("Content-Length", 0)); body = json.loads(self.rfile.read(n))
    if ":generateContent" not in u.path or self.headers.get("x-goog-api-key") != "GKEY" or "key=" in u.query or self.headers.get("Content-Type") != "application/json":
        return self.send(400, json.dumps({"error": {"message": "bad request " + u.path}}))
    text = body["contents"][0]["parts"][0]["text"]
    import re
    ids = re.findall(r'"id":"([^"]+)"', text)
    out = [{"id": i, "marketImpact": -0.7, "niftyImpact": -0.6, "sector": "Broad market", "severity": "HIGH", "horizon": "intraday",
            "scheduled": True, "eventDate": TODAY, "eventName": "RBI policy", "reason": "rate hike surprise",
            "topic": "RBI policy surprise", "speculative": True} for i in ids]
    self.send(200, json.dumps({"candidates": [{"content": {"parts": [{"text": "```json\n" + json.dumps(out) + "\n```"}]}}]}))
H.do_POST = do_POST

HTTPServer(("127.0.0.1", PORT), H).serve_forever()
