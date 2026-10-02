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

/** Small view helpers and the app's look (deep navy, glass cards, soft accents). The whole UI is built in code (no XML layouts). */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF0A0E1A, PANEL = 0xFF0F1524, CARD = 0xFF151C2E, CARD2 = 0xFF111727, LINE = 0xFF232C42,
            TEXT = 0xFFEEF2FF, DIM = 0xFF8A94B0, GREEN = 0xFF34D399, RED = 0xFFF87171, AMBER = 0xFFFBBF24, CYAN = 0xFF60A5FA,
            GREY = 0xFF6B7280, PURPLE = 0xFFA78BFA, ACCENT2 = 0xFF8B5CF6;

    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL),
            MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            BOLD = Typeface.create("sans-serif-medium", Typeface.BOLD);

    public static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    /** Same colour with a new alpha (0..255). */
    public static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(bold ? BOLD : REGULAR);
        t.setLineSpacing(0, 1.15f);
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

    /** Rounded gradient (top-left → bottom-right) with a hairline border. */
    public static GradientDrawable gradient(float radiusPx, int stroke, int... colors) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(radiusPx);
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }

    /** A glass card: soft vertical gradient, hairline border, rounded 20dp, a little shadow. */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{CARD, CARD2});
        g.setCornerRadius(dp(c, 20));
        g.setStroke(1, 0x1AFFFFFF);
        l.setBackground(g);
        l.setElevation(dp(c, 2));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** A hero card tinted with `color` (direction colour). */
    public static LinearLayout hero(Context c, int color) {
        LinearLayout l = col(c);
        l.setBackground(gradient(dp(c, 24), alpha(color, 0x55), alpha(color, 0x40), 0xFF151C2E, 0xFF0F1524));
        l.setElevation(dp(c, 4));
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout.LayoutParams cardLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(c, 12));
        return lp;
    }

    public static LinearLayout.LayoutParams matchW() { return new LinearLayout.LayoutParams(-1, -2); }
    public static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    public static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, -2, w); }
    public static LinearLayout.LayoutParams top(Context c, float dpTop) { LinearLayout.LayoutParams lp = matchW(); lp.topMargin = dp(c, dpTop); return lp; }
    public static LinearLayout.LayoutParams gapLeft(Context c, float dpLeft) { LinearLayout.LayoutParams lp = wrap(); lp.leftMargin = dp(c, dpLeft); return lp; }

    public static Button button(Context c, String s, int color) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTypeface(MEDIUM);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setStateListAnimator(null);
        b.setBackground(round(alpha(color, 0x14), dp(c, 14), alpha(color, 0x80), dp(c, 1)));
        b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        return b;
    }

    /** Main action: blue → violet gradient, white text. */
    public static Button primary(Context c, String s) {
        Button b = button(c, s, Color.WHITE);
        b.setTypeface(BOLD);
        b.setBackground(gradient(dp(c, 14), 0, CYAN, ACCENT2));
        return b;
    }

    public static TextView pill(Context c, String s, int color) {
        TextView t = text(c, s, 12, color, true);
        t.setBackground(round(alpha(color, 0x26), dp(c, 20), alpha(color, 0x66), dp(c, 1)));
        t.setPadding(dp(c, 11), dp(c, 4), dp(c, 11), dp(c, 4));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** A quiet chip: filled, no border. */
    public static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 11.5f, color, true);
        t.setBackground(round(alpha(color, 0x1F), dp(c, 10), 0, 0));
        t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    public static TextView header(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11, DIM, true);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(c, 2), 0, dp(c, 10));
        return t;
    }

    /** A card title row: bold title and an optional small caption on the right. */
    public static LinearLayout title(Context c, String title, String caption) {
        LinearLayout r = row(c);
        r.setPadding(0, 0, 0, dp(c, 10));
        r.addView(text(c, title, 15, TEXT, true), weight(1));
        if (caption != null && !caption.isEmpty()) r.addView(text(c, caption, 11, DIM, false), wrap());
        return r;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins(0, dp(c, 10), 0, dp(c, 10));
        v.setLayoutParams(lp);
        return v;
    }

    /** "Label ........ value" row. */
    public static LinearLayout kv(Context c, String k, String v, int vColor) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 4), 0, dp(c, 4));
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
