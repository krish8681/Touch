package com.krish.niftydirection.engine;

/** One piece of evidence. value is -1 (fully bearish) .. +1 (fully bullish). */
public class Factor {
    public static final String STRUCT = "S", LIVE = "L", MODIFIER = "M", EVENT = "E";

    public final String key, name, group;
    public final boolean live;       // true = needs today's running session
    public final double maxWeight;   // base weight inside its group (each group adds to 100)
    public double weight;            // after freshness, source reliability and learning
    public double value;             // -1 .. +1
    public boolean available;
    public String reading = "";      // short: "Long buildup"
    public String detail = "";       // one or two plain sentences
    public String source = "Kite";
    public double reliability = 1.0; // how much we trust the source (Kite/NSE 1.0 … typed 0.5)
    public double freshness = 1.0;   // 1 = fresh, lower = old data
    public double learnt = 1.0;      // weight multiplier from the record (walk-forward), 1 = none
    public double maturity = 1.0;    // less than 1 while the measurement window is still short (e.g. 15-min futures flow)
    public double dayMod = 1.0;      // expiry-day adjustment
    public double ageMin = Double.NaN, expectedMin = Double.NaN;   // data age vs how fresh this kind of data should be
    public String quality = "LIVE";  // LIVE / DELAYED / DAILY / STALE / TYPED / AI

    public Factor(String key, String name, String group, boolean live, double maxWeight) {
        this.key = key; this.name = name; this.group = group; this.live = live; this.maxWeight = maxWeight;
    }

    /** Signed points on the base scale. */
    public double points() { return available ? value * maxWeight : 0; }

    Factor set(double v, String reading, String detail) {
        this.value = Double.isNaN(v) ? 0 : Math.max(-1, Math.min(1, v));
        this.reading = reading; this.detail = detail; this.available = true;
        return this;
    }
    Factor missing(String why) { this.available = false; this.value = 0; this.reading = "No data"; this.detail = why; return this; }
    Factor src(String source, double reliability, String quality) { this.source = source; this.reliability = reliability; this.quality = quality; return this; }
}
