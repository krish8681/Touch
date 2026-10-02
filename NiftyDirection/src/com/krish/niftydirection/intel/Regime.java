package com.krish.niftydirection.intel;

import java.util.ArrayList;
import java.util.List;

/**
 * Market Regime Engine: what kind of market is this, before asking which way it goes.
 *
 * Four axes are read from the same feature vector the models use (so the regime is known for every past sample too):
 *   trend      TREND_BULL / TREND_BEAR / RANGE / MIXED   (20/50-day averages, 20-day efficiency, last 5 days)
 *   volatility HIGH_VOL / NORMAL_VOL / LOW_VOL           (India VIX 1-year rank)
 *   risk       RISK_ON / RISK_OFF / GLOBAL_SHOCK / NEUTRAL (overnight global risk score)
 *   expiry     weekly expiry day or not
 * Live-only flags are added by the runner: EVENT_DRIVEN (scheduled event window), NEWS_DOMINATED (verified high-impact news),
 * INDIA_SPECIFIC (Nifty moving hard while the world is calm).
 * The trend and volatility axes also enter each horizon's meta model, so model weights can differ by regime.
 */
public final class Regime {
    public String trend = "MIXED", vol = "NORMAL_VOL", risk = "NEUTRAL";
    public boolean expiry, panic;
    public final List<String> flags = new ArrayList<>();   // live-only: EVENT_DRIVEN, NEWS_DOMINATED, INDIA_SPECIFIC

    public static Regime of(double[] f) {
        Regime r = new Regime();
        double d20 = f[11], d50 = f[12], eff = f[37], d5 = f[10];
        int up = 0, dn = 0, seen = 0;
        for (double v : new double[]{d20, d50, d5}) { if (Double.isNaN(v)) continue; seen++; if (v > 0.3) up++; else if (v < -0.3) dn++; }
        boolean efficient = !Double.isNaN(eff) && Math.abs(eff) >= 0.25;
        if (seen >= 2 && up >= 2 && (efficient ? eff > 0 : up == seen)) r.trend = "TREND_BULL";
        else if (seen >= 2 && dn >= 2 && (efficient ? eff < 0 : dn == seen)) r.trend = "TREND_BEAR";
        else if (!Double.isNaN(eff) && Math.abs(eff) < 0.15 && (Double.isNaN(d20) || Math.abs(d20) < 0.6)) r.trend = "RANGE";

        double vr = f[16];   // VIX 1-year rank − 0.5
        if (!Double.isNaN(vr)) r.vol = vr > 0.3 ? "HIGH_VOL" : vr < -0.3 ? "LOW_VOL" : "NORMAL_VOL";

        double g = f[52];
        if (!Double.isNaN(g)) r.risk = g <= -2.0 ? "GLOBAL_SHOCK" : g <= -0.6 ? "RISK_OFF" : g >= 0.6 ? "RISK_ON" : "NEUTRAL";
        r.expiry = f[26] == 1;
        r.panic = r.vol.equals("HIGH_VOL") && (r.risk.equals("RISK_OFF") || r.risk.equals("GLOBAL_SHOCK")) && !r.trend.equals("TREND_BULL");
        return r;
    }

    /** One headline label: the axis that matters most right now. */
    public String label() {
        if (risk.equals("GLOBAL_SHOCK")) return "GLOBAL SHOCK";
        if (flags.contains("EVENT_DRIVEN")) return "EVENT DRIVEN";
        if (panic) return "PANIC / RISK-OFF";
        if (flags.contains("NEWS_DOMINATED")) return "NEWS DOMINATED";
        String t = trend.equals("TREND_BULL") ? "TRENDING BULL" : trend.equals("TREND_BEAR") ? "TRENDING BEAR" : trend.equals("RANGE") ? "RANGE" : "MIXED";
        return t;
    }

    /** Secondary labels shown next to the headline. */
    public String detail() {
        List<String> p = new ArrayList<>();
        p.add(vol.replace("_VOL", " VOLATILITY").replace("NORMAL VOLATILITY", "NORMAL VOL"));
        if (!risk.equals("NEUTRAL")) p.add(risk.replace('_', '-'));
        if (expiry) p.add("EXPIRY DAY");
        for (String fl : flags) if (!fl.replace('_', ' ').equals(label())) p.add(fl.replace('_', ' '));
        return String.join(" · ", p);
    }

    /** Regime inputs of the meta model: lets each group's weight change in range-bound and high-volatility markets. */
    public double rangeFlag() { return trend.equals("RANGE") || trend.equals("MIXED") ? 1 : 0; }
    public double highVolFlag() { return vol.equals("HIGH_VOL") ? 1 : 0; }

    /** Volatility bucket for the expected-range tables: 0 low, 1 normal, 2 high. */
    public int volBucket() { return vol.equals("LOW_VOL") ? 0 : vol.equals("HIGH_VOL") ? 2 : 1; }

    public String key() { return label(); }
}
