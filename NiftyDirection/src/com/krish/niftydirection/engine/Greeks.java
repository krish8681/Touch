package com.krish.niftydirection.engine;

/** Black-Scholes price and implied volatility. Kite does not send IV, so it is worked out from each option's price. */
public final class Greeks {
    private Greeks() {}

    public static final double RATE = 0.065;   // Indian risk-free rate, roughly

    static double ncdf(double x) {
        // Abramowitz-Stegun 7.1.26, good to ~1e-7
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double d = 0.3989422804014327 * Math.exp(-x * x / 2);
        double p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return x >= 0 ? 1 - p : p;
    }

    /** Option price. s spot, k strike, t years, v volatility (0.15 = 15%). */
    public static double price(boolean call, double s, double k, double t, double v) {
        if (t <= 0 || v <= 0) return Math.max(0, call ? s - k : k - s);
        double sq = v * Math.sqrt(t);
        double d1 = (Math.log(s / k) + (RATE + v * v / 2) * t) / sq, d2 = d1 - sq;
        double disc = Math.exp(-RATE * t);
        return call ? s * ncdf(d1) - k * disc * ncdf(d2) : k * disc * ncdf(-d2) - s * ncdf(-d1);
    }

    /** Delta (per 1 point of spot). */
    public static double delta(boolean call, double s, double k, double t, double v) {
        if (t <= 0 || v <= 0) return call ? (s > k ? 1 : 0) : (s < k ? -1 : 0);
        double d1 = (Math.log(s / k) + (RATE + v * v / 2) * t) / (v * Math.sqrt(t));
        return call ? ncdf(d1) : ncdf(d1) - 1;
    }

    /** Implied volatility in % (e.g. 13.4), or NaN when the price is below intrinsic value or too small to read. */
    public static double iv(boolean call, double s, double k, double t, double premium) {
        if (s <= 0 || k <= 0 || t <= 0 || premium <= 0.05) return Double.NaN;
        double intrinsic = Math.max(0, call ? s - k * Math.exp(-RATE * t) : k * Math.exp(-RATE * t) - s);
        if (premium <= intrinsic + 0.01) return Double.NaN;
        double lo = 0.005, hi = 3.0;
        if (price(call, s, k, t, hi) < premium) return Double.NaN;
        for (int i = 0; i < 70; i++) {
            double mid = (lo + hi) / 2;
            if (price(call, s, k, t, mid) > premium) hi = mid; else lo = mid;
        }
        return (lo + hi) / 2 * 100;
    }

    /** Years from now to 15:30 IST on the expiry day. At least 30 minutes, so expiry day still gives a number. */
    public static double yearsToExpiry(String today, int minuteNow, String expiry, int daysBetween) {
        double minutes = daysBetween * 1440.0 + (15 * 60 + 30 - minuteNow);
        return Math.max(30, minutes) / (365.0 * 1440.0);
    }
}
