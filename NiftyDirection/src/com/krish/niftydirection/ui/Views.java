package com.krish.niftydirection.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.List;

/** Custom drawings: the score gauge, the day's score line, and the evidence bars. */
public final class Views {
    private Views() {}

    /** Half-circle gauge 0..100. Red on the left, amber middle, green right. */
    public static class Gauge extends View {
        private int score = 50; private String label = "", sub = "";
        private int color = Ui.AMBER;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Gauge(Context c) { super(c); }
        public void set(int score, String label, String sub, int color) { this.score = Math.max(0, Math.min(100, score)); this.label = label; this.sub = sub; this.color = color; invalidate(); }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, (int) (width * 0.62f));
        }

        /** Arc with five soft zones, the score filled in colour and a marker dot; text sits inside the arc, labels under its ends. */
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float label = w * 0.045f;                                   // room under the arc for "Bearish" / "Bullish"
            float stroke = w * 0.055f, r = Math.min(w / 2f - stroke, h - stroke - label * 1.8f), cx = w / 2f, cy = stroke / 2 + r + stroke * 0.2f;
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
            int[] cols = {Ui.RED, 0xFFE3794B, Ui.AMBER, 0xFF9BBF4A, Ui.GREEN};
            float[] cuts = {0, 25, 40, 60, 75, 100};
            p.setStrokeCap(Paint.Cap.BUTT);
            for (int i = 0; i < 5; i++) {
                p.setColor((cols[i] & 0x00FFFFFF) | 0x33000000);
                c.drawArc(oval, 180 + cuts[i] * 1.8f, (cuts[i + 1] - cuts[i]) * 1.8f - 1.2f, false, p);
            }
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(color);
            if (score > 0) c.drawArc(oval, 180, Math.max(1, score * 1.8f), false, p);
            // marker on the arc instead of a needle (keeps the centre free for the number)
            double a = Math.toRadians(180 + score * 1.8);
            float mx = cx + (float) Math.cos(a) * r, my = cy + (float) Math.sin(a) * r;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.BG);
            c.drawCircle(mx, my, stroke * 0.78f, p);
            p.setColor(Ui.TEXT);
            c.drawCircle(mx, my, stroke * 0.5f, p);
            p.setColor(color);
            c.drawCircle(mx, my, stroke * 0.3f, p);
            // number + caption inside the arc
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Ui.TEXT);
            p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
            p.setTextSize(r * 0.42f);
            c.drawText(String.valueOf(score), cx, cy - r * 0.18f, p);
            p.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            p.setTextSize(r * 0.1f);
            p.setColor(Ui.DIM);
            c.drawText(sub, cx, cy + r * 0.0f, p);
            // end labels under the arc ends
            p.setTextSize(label);
            p.setColor(Ui.alpha(Ui.RED, 0xCC));
            // anchored to the outer edge of the arc so the words never run off the view
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("Bearish", Math.max(0, cx - r - stroke / 2), cy + stroke / 2 + label * 1.4f, p);
            p.setColor(Ui.alpha(Ui.GREEN, 0xCC));
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("Bullish", Math.min(w, cx + r + stroke / 2), cy + stroke / 2 + label * 1.4f, p);
            p.setTextAlign(Paint.Align.CENTER);
        }
    }

    /** The day's score line (-100..+100) with a zero line and time marks. points: {minute, score, conf, regime(1 bull, 2 bear, 0 range), spot}. */
    public static class Timeline extends View {
        private List<double[]> pts;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Timeline(Context c) { super(c); }
        public void set(List<double[]> pts) { this.pts = pts; invalidate(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 150)); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), padL = Ui.dp(getContext(), 30), padB = Ui.dp(getContext(), 16), top = Ui.dp(getContext(), 6);
            float ph = h - padB - top, pw = w - padL - Ui.dp(getContext(), 4);
            float t0 = 9 * 60 + 15, t1 = 15 * 60 + 30;
            p.setTextSize(Ui.dp(getContext(), 9));
            p.setStrokeWidth(1);
            for (int lv = -100; lv <= 100; lv += 50) {
                float y = top + ph * (1 - (lv + 100) / 200f);
                p.setColor(lv == 0 ? Ui.GREY : Ui.LINE);
                c.drawLine(padL, y, w, y, p);
                p.setColor(Ui.DIM);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText((lv > 0 ? "+" : "") + lv, padL - 4, y + 3, p);
            }
            // bands at ±25 (regime line)
            p.setColor(0x143FB950);
            c.drawRect(padL, top, w, top + ph * (1 - 125 / 200f), p);
            p.setColor(0x14F85149);
            c.drawRect(padL, top + ph * (1 - 75 / 200f), w, top + ph, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Ui.DIM);
            for (int hr = 10; hr <= 15; hr++) {
                float x = padL + pw * ((hr * 60 - t0) / (t1 - t0));
                c.drawText(String.valueOf(hr), x, h - 3, p);
            }
            if (pts == null || pts.isEmpty()) {
                c.drawText("The line fills in as the day goes on", padL + pw / 2, top + ph / 2 - 8, p);
                return;
            }
            Path path = new Path();
            boolean first = true;
            float lx = 0, ly = 0;
            for (double[] q : pts) {
                double m = Math.max(t0 - 60, Math.min(t1, q[0]));
                if (m < t0) m = t0;
                float x = padL + pw * (float) ((m - t0) / (t1 - t0));
                float y = top + ph * (float) (1 - (Math.max(-100, Math.min(100, q[1])) + 100) / 200);
                if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
                lx = x; ly = y;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 2));
            p.setColor(Ui.CYAN);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            double last = pts.get(pts.size() - 1)[1];
            p.setColor(last >= 25 ? Ui.GREEN : last <= -25 ? Ui.RED : Ui.AMBER);
            c.drawCircle(lx, ly, Ui.dp(getContext(), 4), p);
        }
    }

    /** A centred bar: value -1..+1, grows right in green or left in red. */
    public static class Bar extends View {
        private double v; private boolean on = true;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Bar(Context c) { super(c); }
        public void set(double v, boolean available) { this.v = Math.max(-1, Math.min(1, v)); this.on = available; invalidate(); }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 10)); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setColor(Ui.LINE);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            p.setColor(Ui.GREY);
            c.drawRect(w / 2 - 1, 0, w / 2 + 1, h, p);
            if (!on) return;
            p.setColor(v >= 0 ? Ui.GREEN : Ui.RED);
            float len = (float) (Math.abs(v) * w / 2);
            if (v >= 0) c.drawRoundRect(new RectF(w / 2, 0, w / 2 + len, h), r, r, p);
            else c.drawRoundRect(new RectF(w / 2 - len, 0, w / 2, h), r, r, p);
        }
    }

    /** Plain horizontal progress bar 0..100. */
    public static class Meter extends View {
        private int val; private int color = Ui.CYAN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Meter(Context c) { super(c); }
        public void set(int val, int color) { this.val = Math.max(0, Math.min(100, val)); this.color = color; invalidate(); }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 8)); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setColor(Ui.LINE);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            p.setColor(color);
            c.drawRoundRect(new RectF(0, 0, Math.max(h, w * val / 100f), h), r, r, p);
        }
    }
}
