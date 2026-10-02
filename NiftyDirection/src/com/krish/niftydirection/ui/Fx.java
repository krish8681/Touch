package com.krish.niftydirection.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;

/** Modern drawn components for the AI screen: probability ring, three-way bar, confidence dots, value bar, heat cell. */
final class Fx {
    private Fx() {}

    /** A ring that fills to `value` (0..1) with a soft gradient, a big number in the middle and a caption under it. */
    static final class Ring extends View {
        private float value = 0.5f;
        private String big = "—", small = "";
        private int color = Ui.CYAN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ring(Context c) { super(c); }

        void set(double value, String big, String small, int color) {
            this.value = (float) Math.max(0, Math.min(1, Double.isNaN(value) ? 0 : value));
            this.big = big; this.small = small; this.color = color;
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            int s = Ui.dp(getContext(), 132);
            setMeasuredDimension(s, s);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), stroke = w * 0.085f, r = w / 2f - stroke;
            float cx = w / 2f, cy = getHeight() / 2f;
            RectF o = new RectF(cx - r, cy - r, cx + r, cy + r);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setShader(null);
            p.setColor(0x22FFFFFF);
            c.drawArc(o, 0, 360, false, p);
            SweepGradient g = new SweepGradient(cx, cy, new int[]{Ui.alpha(color, 0x66), color, color}, new float[]{0, Math.max(0.01f, value), 1});
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setRotate(-90, cx, cy);
            g.setLocalMatrix(m);
            p.setShader(g);
            c.drawArc(o, -90, 360 * value, false, p);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Ui.TEXT);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            p.setTextSize(r * 0.52f);
            c.drawText(big, cx, cy + r * 0.12f, p);
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextSize(r * 0.2f);
            p.setColor(Ui.DIM);
            c.drawText(small, cx, cy + r * 0.45f, p);
        }
    }

    /** Up / flat / down as one rounded bar. NaN parts fall back to a plain up/down split. */
    static final class Split extends View {
        private float up = 0.5f, flat = 0, down = 0.5f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Split(Context c) { super(c); }

        void set(double pUp, double pFlat, double pDown, double pFinal) {
            if (Double.isNaN(pFlat)) { up = (float) pFinal; flat = 0; down = 1 - up; }
            else { up = (float) pUp; flat = (float) pFlat; down = (float) pDown; }
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 8)); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f, gap = Ui.dp(getContext(), 2);
            float tot = Math.max(1e-6f, up + flat + down);
            float a = w * up / tot, b = w * flat / tot;
            p.setColor(0x1FFFFFFF);
            c.drawRoundRect(new RectF(0, 0, w, h), rad, rad, p);
            p.setColor(Ui.GREEN);
            c.drawRoundRect(new RectF(0, 0, Math.max(h, a - gap / 2), h), rad, rad, p);
            if (b > 1) { p.setColor(0xFF4B5563); c.drawRoundRect(new RectF(a + gap / 2, 0, a + b - gap / 2, h), rad, rad, p); }
            p.setColor(Ui.RED);
            c.drawRoundRect(new RectF(Math.min(w - h, a + b + gap / 2), 0, w, h), rad, rad, p);
        }
    }

    /** Five dots for confidence 0..100. */
    static final class Dots extends View {
        private int filled;
        private int color = Ui.CYAN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dots(Context c) { super(c); }

        void set(int confidence, int color) { filled = Math.max(0, Math.min(5, (int) Math.round(confidence / 20.0))); this.color = color; invalidate(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(getContext(), 52), Ui.dp(getContext(), 10)); }

        @Override protected void onDraw(Canvas c) {
            float r = getHeight() / 2.6f, step = getWidth() / 5f;
            for (int i = 0; i < 5; i++) {
                p.setColor(i < filled ? color : 0x2BFFFFFF);
                c.drawCircle(step * i + step / 2, getHeight() / 2f, r, p);
            }
        }
    }

    /** A centred bar for a signed value: grows right (green) or left (red) from the middle; |value| ≤ max fills half. */
    static final class Push extends View {
        private float v, max = 10;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Push(Context c) { super(c); }

        void set(double v, double max) { this.v = (float) v; this.max = (float) Math.max(1e-6, max); invalidate(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 6)); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), mid = w / 2f, len = Math.min(1, Math.abs(v) / max) * mid;
            p.setColor(0x14FFFFFF);
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            p.setColor(v >= 0 ? Ui.GREEN : Ui.RED);
            RectF r = v >= 0 ? new RectF(mid, 0, mid + Math.max(h, len), h) : new RectF(mid - Math.max(h, len), 0, mid, h);
            c.drawRoundRect(r, h / 2, h / 2, p);
            p.setColor(0x55FFFFFF);
            c.drawRect(mid - 1, 0, mid + 1, h, p);
        }
    }

    /** A 0..1 progress bar with a gradient fill. */
    static final class Progress extends View {
        private float v;
        private int color = Ui.CYAN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Progress(Context c) { super(c); }

        void set(double v, int color) { this.v = (float) Math.max(0, Math.min(1, v)); this.color = color; invalidate(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 6)); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setShader(null);
            p.setColor(0x1AFFFFFF);
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            if (v <= 0) return;
            p.setShader(new LinearGradient(0, 0, w * v, 0, Ui.alpha(color, 0x99), color, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(0, 0, Math.max(h, w * v), h), h / 2, h / 2, p);
            p.setShader(null);
        }
    }
}
