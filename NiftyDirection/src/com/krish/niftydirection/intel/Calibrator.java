package com.krish.niftydirection.intel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * Calibration Engine: raw model probability → realistic probability.
 * With enough out-of-sample predictions it uses isotonic regression (pool-adjacent-violators: monotone, no shape assumed);
 * with fewer it falls back to Platt scaling (a 2-parameter logistic fit on the logit). Output is kept inside [0.03, 0.97].
 */
public final class Calibrator {
    public static final int ISOTONIC_MIN = 1500;
    static final double LO = 0.03, HI = 0.97;

    public String kind = "none";   // none / platt / isotonic
    public double a = 1, b = 0;    // platt: p = sigmoid(a·logit(raw) + b)
    public double[] x = new double[0], y = new double[0];   // isotonic knots (raw → calibrated)

    public static Calibrator fit(double[] p, int[] yy, int n) {
        Calibrator c = new Calibrator();
        if (n < 50) return c;
        if (n >= ISOTONIC_MIN) { c.isotonic(p, yy, n); return c; }
        c.platt(p, yy, n);
        return c;
    }

    public double apply(double raw) {
        if (Double.isNaN(raw)) return raw;
        double v;
        switch (kind) {
            case "platt": v = sig(a * logit(raw) + b); break;
            case "isotonic": v = interp(raw); break;
            default: v = raw;
        }
        return Math.max(LO, Math.min(HI, v));
    }

    void platt(double[] p, int[] yy, int n) {
        double A = 1, B = 0;
        for (int it = 0; it < 30; it++) {   // Newton on 2 parameters, tiny ridge for stability
            double g0 = 0, g1 = 0, h00 = 1e-3, h01 = 0, h11 = 1e-3;
            for (int i = 0; i < n; i++) {
                double z = logit(p[i]), q = sig(A * z + B), e = yy[i] - q, s = q * (1 - q);
                g0 += e * z; g1 += e; h00 += s * z * z; h01 += s * z; h11 += s;
            }
            double det = h00 * h11 - h01 * h01;
            if (Math.abs(det) < 1e-12) break;
            double dA = (h11 * g0 - h01 * g1) / det, dB = (h00 * g1 - h01 * g0) / det;
            A += dA; B += dB;
            if (Math.abs(dA) + Math.abs(dB) < 1e-8) break;
        }
        a = Math.max(0, A); b = B; kind = "platt";
    }

    void isotonic(double[] p, int[] yy, int n) {
        Integer[] o = new Integer[n];
        for (int i = 0; i < n; i++) o[i] = i;
        Arrays.sort(o, (i, j) -> Double.compare(p[i], p[j]));
        double[] sx = new double[n], sy = new double[n], w = new double[n];
        int m = 0;
        for (int r = 0; r < n; r++) {
            int i = o[r];
            sx[m] = p[i]; sy[m] = yy[i]; w[m] = 1; m++;
            while (m > 1 && sy[m - 2] / w[m - 2] > sy[m - 1] / w[m - 1]) {   // pool adjacent violators
                sx[m - 2] += sx[m - 1]; sy[m - 2] += sy[m - 1]; w[m - 2] += w[m - 1]; m--;
            }
        }
        // light smoothing: merge blocks smaller than 1% of the data with a neighbour (avoids spiky steps)
        int minW = Math.max(20, n / 100);
        double[] bx = new double[m], by = new double[m], bw = new double[m];
        int k = 0;
        for (int i = 0; i < m; i++) {
            bx[k] = sx[i]; by[k] = sy[i]; bw[k] = w[i]; k++;
            if (k > 1 && bw[k - 2] < minW) { bx[k - 2] += bx[k - 1]; by[k - 2] += by[k - 1]; bw[k - 2] += bw[k - 1]; k--; }
        }
        if (k > 1 && bw[k - 1] < minW) { bx[k - 2] += bx[k - 1]; by[k - 2] += by[k - 1]; bw[k - 2] += bw[k - 1]; k--; }
        x = new double[k]; y = new double[k];
        for (int i = 0; i < k; i++) { x[i] = bx[i] / bw[i]; y[i] = by[i] / bw[i]; }
        for (int i = 1; i < k; i++) y[i] = Math.max(y[i], y[i - 1]);   // stays monotone after merging
        kind = "isotonic";
    }

    double interp(double v) {
        if (x.length == 0) return v;
        if (v <= x[0]) return y[0];
        if (v >= x[x.length - 1]) return y[y.length - 1];
        int i = Arrays.binarySearch(x, v);
        if (i >= 0) return y[i];
        i = -i - 1;
        double t = (v - x[i - 1]) / (x[i] - x[i - 1]);
        return y[i - 1] + t * (y[i] - y[i - 1]);
    }

    static double sig(double v) { return v > 30 ? 1 : v < -30 ? 0 : 1 / (1 + Math.exp(-v)); }
    static double logit(double p) { p = Math.max(1e-4, Math.min(1 - 1e-4, p)); return Math.log(p / (1 - p)); }

    JSONObject toJson() throws Exception {
        JSONObject j = new JSONObject().put("kind", kind).put("a", a).put("b", b);
        JSONArray ax = new JSONArray(), ay = new JSONArray();
        for (double v : x) ax.put(v);
        for (double v : y) ay.put(v);
        return j.put("x", ax).put("y", ay);
    }

    static Calibrator fromJson(JSONObject j) throws Exception {
        Calibrator c = new Calibrator();
        if (j == null) return c;
        c.kind = j.optString("kind", "none"); c.a = j.optDouble("a", 1); c.b = j.optDouble("b", 0);
        JSONArray ax = j.optJSONArray("x"), ay = j.optJSONArray("y");
        if (ax != null && ay != null && ax.length() == ay.length()) {
            c.x = new double[ax.length()]; c.y = new double[ay.length()];
            for (int i = 0; i < c.x.length; i++) { c.x[i] = ax.getDouble(i); c.y[i] = ay.getDouble(i); }
        }
        return c;
    }
}
