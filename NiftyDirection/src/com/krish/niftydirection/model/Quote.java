package com.krish.niftydirection.model;

/** One instrument's price snapshot from Kite /quote. */
public class Quote {
    public String symbol = "";
    public long token;
    public double last, open, high, low, prevClose, volume, oi;
    public double buyQty, sellQty;   // total pending buy / sell quantity in the order book (Kite depth totals)
    public double avgPrice;    // day VWAP from Kite (average_price)
    public String time = "";   // newest exchange time "yyyy-MM-dd HH:mm:ss"

    public Quote() {}
    public Quote(String symbol, double last, double open, double high, double low, double prevClose) {
        this.symbol = symbol; this.last = last; this.open = open; this.high = high; this.low = low; this.prevClose = prevClose;
    }

    /** % change against the previous close (0 if unknown). */
    public double pct() { return prevClose > 0 ? (last - prevClose) / prevClose * 100.0 : 0; }
    public double change() { return prevClose > 0 ? last - prevClose : 0; }
    public boolean ok() { return last > 0; }
}
