package com.krish.niftydirection.model;

/** One OHLC bar. t = epoch millis of the bar start. oi = open interest (0 if not asked). */
public class Candle {
    public long t;
    public String date = "";   // yyyy-MM-dd (IST)
    public int minute;         // minutes since midnight IST
    public double o, h, l, c, v, oi;

    public Candle() {}
    public Candle(long t, double o, double h, double l, double c, double v) { this.t = t; this.o = o; this.h = h; this.l = l; this.c = c; this.v = v; }
}
