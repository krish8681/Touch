package com.krish.niftydirection.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view helpers. The whole UI is built in code (no XML layouts). */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF0D1117, PANEL = 0xFF151B23, CARD = 0xFF1B222C, LINE = 0xFF2A3340,
            TEXT = 0xFFE6EDF3, DIM = 0xFF8B949E, GREEN = 0xFF3FB950, RED = 0xFFF85149, AMBER = 0xFFD29922, CYAN = 0xFF58A6FF, GREY = 0xFF6E7681, PURPLE = 0xFFBC8CFF;

    public static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    public static TextView mono(Context c, String s, float sp, int color, boolean bold) {
        TextView t = text(c, s, sp, color, bold);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    public static LinearLayout col(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    public static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }

    public static GradientDrawable round(int color, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** A rounded card with padding. */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(round(CARD, dp(c, 14), LINE, 1));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout.LayoutParams cardLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(c, 10));
        return lp;
    }

    public static LinearLayout.LayoutParams matchW() { return new LinearLayout.LayoutParams(-1, -2); }
    public static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    public static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, -2, w); }
    public static LinearLayout.LayoutParams top(Context c, float dpTop) { LinearLayout.LayoutParams lp = matchW(); lp.topMargin = dp(c, dpTop); return lp; }

    public static Button button(Context c, String s, int color) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(round(0x00000000, dp(c, 10), color, dp(c, 1)));
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        return b;
    }

    public static Button primary(Context c, String s) {
        Button b = button(c, s, Color.BLACK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(CYAN, dp(c, 10), CYAN, 0));
        return b;
    }

    public static TextView pill(Context c, String s, int color) {
        TextView t = text(c, s, 12, color, true);
        t.setBackground(round((color & 0x00FFFFFF) | 0x26000000, dp(c, 20), color, dp(c, 1)));
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    public static TextView header(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11, DIM, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(c, 6), 0, dp(c, 8));
        return t;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins(0, dp(c, 8), 0, dp(c, 8));
        v.setLayoutParams(lp);
        return v;
    }

    /** "Label ........ value" row. */
    public static LinearLayout kv(Context c, String k, String v, int vColor) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 3), 0, dp(c, 3));
        TextView a = text(c, k, 13, DIM, false);
        TextView b = text(c, v, 13, vColor, true);
        b.setGravity(Gravity.END);
        r.addView(a, weight(1));
        r.addView(b, weight(1.6f));
        return r;
    }

    public static int regimeColor(String regime) {
        if ("BULLISH".equals(regime)) return GREEN;
        if ("BEARISH".equals(regime)) return RED;
        if ("CONFLICT".equals(regime)) return PURPLE;
        if ("RANGE".equals(regime)) return AMBER;
        return GREY;   // NO EDGE
    }

    public static int signColor(double v) { return v > 0.0001 ? GREEN : v < -0.0001 ? RED : DIM; }
}
