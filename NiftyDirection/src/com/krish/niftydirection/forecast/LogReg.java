package com.krish.niftydirection.forecast;

/**
 * Plain logistic regression with an L2 penalty (ridge), fitted by Newton's method.
 * Inputs are standardised first (mean 0, sd 1, missing → 0). The intercept is not penalised.
 */
public final class LogReg {
    public double[] w;        // w[0] = intercept, w[1..p] = weights of the standardised inputs
    public double[] mean, sd;

    public static LogReg fit(double[][] X, int[] y, int[] rows, int nRows, double lambda, int iters) { return fit(X, y, rows, nRows, lambda, iters, null); }

    /**
     * wt (indexed like X, or null = all 1): sample weights. Overlapping samples that share one outcome, or inputs that repeat
     * all day, should count as the few independent observations they are — weights below 1 make the same ridge penalty
     * shrink harder, so the model cannot become sure of itself from repeated noise.
     */
    public static LogReg fit(double[][] X, int[] y, int[] rows, int nRows, double lambda, int iters, double[] wt) {
        int p = X[0].length;
        LogReg m = new LogReg();
        m.mean = new double[p]; m.sd = new double[p];
        for (int j = 0; j < p; j++) {
            double s = 0, s2 = 0; int n = 0;
            for (int r = 0; r < nRows; r++) { double v = X[rows[r]][j]; if (!Double.isNaN(v)) { s += v; s2 += v * v; n++; } }
            m.mean[j] = n > 0 ? s / n : 0;
            double var = n > 1 ? (s2 - n * m.mean[j] * m.mean[j]) / (n - 1) : 0;
            m.sd[j] = var > 1e-12 ? Math.sqrt(var) : 0;   // sd 0 → input ignored
        }
        int q = p + 1;
        double[] w = new double[q];
        double ups = 0, tw = 0;
        for (int r = 0; r < nRows; r++) { double wr = wt == null ? 1 : wt[rows[r]]; ups += wr * y[rows[r]]; tw += wr; }
        double base = Math.min(0.99, Math.max(0.01, (ups + 0.5) / (tw + 1.0)));
        w[0] = Math.log(base / (1 - base));
        double[] z = new double[q];
        for (int it = 0; it < iters; it++) {
            double[] g = new double[q];
            double[][] H = new double[q][q];
            for (int r = 0; r < nRows; r++) {
                m.row(X[rows[r]], z);
                double pr = sigmoid(dot(w, z));
                double wr = wt == null ? 1 : wt[rows[r]];
                double e = wr * (y[rows[r]] - pr), s = wr * pr * (1 - pr);
                for (int a = 0; a < q; a++) {
                    if (z[a] == 0) continue;
                    g[a] += e * z[a];
                    double sa = s * z[a];
                    for (int b = a; b < q; b++) H[a][b] += sa * z[b];
                }
            }
            for (int a = 0; a < q; a++) for (int b = 0; b < a; b++) H[a][b] = H[b][a];
            for (int a = 1; a < q; a++) { g[a] -= lambda * w[a]; H[a][a] += lambda; }
            H[0][0] += 1e-6;
            double[] step = solve(H, g);
            if (step == null) break;
            double mx = 0;
            for (int a = 0; a < q; a++) { w[a] += step[a]; mx = Math.max(mx, Math.abs(step[a])); }
            if (mx < 1e-6) break;
        }
        m.w = w;
        return m;
    }

    /** Standardised row with a leading 1 (missing inputs → 0, i.e. the training average). */
    public void row(double[] x, double[] z) {
        z[0] = 1;
        for (int j = 0; j < x.length; j++) z[j + 1] = sd[j] > 0 && !Double.isNaN(x[j]) ? Math.max(-5, Math.min(5, (x[j] - mean[j]) / sd[j])) : 0;
    }

    public double predict(double[] x) {
        double[] z = new double[x.length + 1];
        row(x, z);
        return sigmoid(dot(w, z));
    }

    static double sigmoid(double v) { return v > 30 ? 1 : v < -30 ? 0 : 1 / (1 + Math.exp(-v)); }
    static double dot(double[] a, double[] b) { double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }

    /** Solves A x = b (A symmetric positive definite) by Gaussian elimination with partial pivoting. */
    static double[] solve(double[][] A, double[] b) {
        int n = b.length;
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) { System.arraycopy(A[i], 0, M[i], 0, n); M[i][n] = b[i]; }
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[piv][c])) piv = r;
            if (Math.abs(M[piv][c]) < 1e-12) return null;
            double[] t = M[c]; M[c] = M[piv]; M[piv] = t;
            for (int r = c + 1; r < n; r++) {
                double f = M[r][c] / M[c][c];
                if (f == 0) continue;
                for (int k = c; k <= n; k++) M[r][k] -= f * M[c][k];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = M[i][n];
            for (int k = i + 1; k < n; k++) s -= M[i][k] * x[k];
            x[i] = s / M[i][i];
        }
        return x;
    }
}
