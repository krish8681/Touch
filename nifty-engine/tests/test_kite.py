import asyncio
import hashlib
import struct
from datetime import datetime

import httpx
import pytest

from nifty_engine.data.kite import KiteClient, KiteError, parse_binary, raw_quote_from_kite, tick_from_dict


def _frame(*packets: bytes) -> bytes:
    out = struct.pack(">H", len(packets))
    for p in packets:
        out += struct.pack(">H", len(p)) + p
    return out


def test_parse_ltp_packet():
    token = 12345 << 8 | 2          # NFO segment
    ticks = parse_binary(_frame(struct.pack(">Ii", token, 10_550)))
    assert ticks == [{"instrument_token": token, "last_price": 105.5, "tradable": True, "mode": "ltp"}]


def test_parse_index_full_packet():
    token = 256265
    ts = int(datetime(2025, 3, 3, 10, 0).timestamp())
    pkt = struct.pack(">Iiiiiiii", token, 2_500_050, 2_510_000, 2_490_000, 2_495_000, 2_480_000, 2_005_000, ts)
    (t,) = parse_binary(_frame(pkt))
    assert t["last_price"] == 25_000.5 and t["tradable"] is False
    assert t["ohlc"]["high"] == 25_100.0 and t["mode"] == "full"


def test_parse_full_fo_packet_with_depth():
    token = 99 << 8 | 2
    head = struct.pack(">iiiiiiiiiii", token, 12_000, 75, 11_900, 150_000, 1000, 2000, 11_000, 13_000, 10_500, 11_500)
    tail = struct.pack(">iiiii", 1_740_000_000, 9_000_000, 9_100_000, 8_000_000, 1_740_000_001)
    depth = b""
    for i in range(10):
        price = 11_995 - i * 5 if i < 5 else 12_005 + (i - 5) * 5
        depth += struct.pack(">iiHH", 75 * (i + 1), price, i + 1, 0)
    pkt = head + tail + depth
    assert len(pkt) == 184
    t, ltp_only = parse_binary(_frame(pkt, struct.pack(">Ii", 7 << 8 | 2, 500)))
    assert ltp_only["last_price"] == 5.0
    assert t["oi"] == 9_000_000 and t["volume_traded"] == 150_000
    assert t["depth"]["buy"][0]["price"] == 119.95 and t["depth"]["sell"][0]["price"] == 120.05
    tick = tick_from_dict(t)
    assert tick.bid == 119.95 and tick.ask == 120.05 and tick.oi == 9_000_000


def test_heartbeat_ignored():
    assert parse_binary(b"\x00") == []


def _client(handler):
    return KiteClient("key", "secret", "tok", transport=httpx.MockTransport(handler))


def test_session_checksum_and_auth_header():
    seen = {}

    def handler(req: httpx.Request):
        if req.url.path == "/session/token":
            seen["form"] = dict(x.split("=") for x in req.content.decode().split("&"))
            return httpx.Response(200, json={"status": "success", "data": {"access_token": "AT", "user_id": "AB1234"}})
        seen["auth"] = req.headers["Authorization"]
        return httpx.Response(200, json={"status": "success", "data": {"order_id": "1001"}})

    async def run():
        k = _client(handler)
        await k.generate_session("RT")
        oid = await k.place_order(tradingsymbol="NIFTY25MAR25000CE", exchange="NFO", transaction_type="BUY",
                                  quantity=75, price=101.25)
        await k.close()
        return oid

    assert asyncio.run(run()) == "1001"
    assert seen["form"]["checksum"] == hashlib.sha256(b"keyRTsecret").hexdigest()
    assert seen["auth"] == "token key:AT"


def test_error_raises():
    def handler(req):
        return httpx.Response(403, json={"status": "error", "message": "Invalid token", "error_type": "TokenException"})

    async def run():
        k = _client(handler)
        try:
            await k.orders()
        finally:
            await k.close()

    with pytest.raises(KiteError) as e:
        asyncio.run(run())
    assert e.value.error_type == "TokenException"


def test_raw_quote_from_kite():
    q = raw_quote_from_kite({"instrument_token": 5, "last_price": 10, "volume": 7, "oi": 9,
                             "depth": {"buy": [{"price": 9.9}], "sell": [{"price": 10.1}]}})
    assert (q.bid, q.ask, q.volume, q.oi) == (9.9, 10.1, 7, 9)
