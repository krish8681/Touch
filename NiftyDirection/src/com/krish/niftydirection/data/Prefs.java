package com.krish.niftydirection.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/** All settings, kept only on this phone. */
public class Prefs {
    private final SharedPreferences p;
    public Prefs(Context c) { p = c.getSharedPreferences("niftydirection", Context.MODE_PRIVATE); }

    public String str(String k, String d) { return p.getString(k, d); }
    public void put(String k, String v) { p.edit().putString(k, v).apply(); }
    public boolean bool(String k, boolean d) { return p.getBoolean(k, d); }
    public void put(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }
    public double num(String k, double d) {
        try { String s = p.getString(k, ""); return s.trim().isEmpty() ? d : Double.parseDouble(s.trim().replace(",", "")); } catch (Exception e) { return d; }
    }

    public String apiKey() { return str("api_key", "").trim(); }
    public String apiSecret() { return str("api_secret", "").trim(); }
    public String token() { return str("access_token", ""); }
    public String userName() { return str("user_name", ""); }
    public int refreshMin() { return (int) Math.max(1, Math.min(60, num("refresh_min", 5))); }

    public void saveSession(String token, String user) {
        p.edit().putString("access_token", token).putString("user_name", user).putLong("login_at", System.currentTimeMillis()).apply();
    }
    public void clearSession() { p.edit().remove("access_token").remove("login_at").apply(); }

    /** Kite tokens die around 6 AM IST the next day. */
    public boolean hasValidSession() {
        if (token().isEmpty()) return false;
        long at = p.getLong("login_at", 0);
        Calendar six = Calendar.getInstance(Collector.IST);
        six.set(Calendar.HOUR_OF_DAY, 6); six.set(Calendar.MINUTE, 0); six.set(Calendar.SECOND, 0); six.set(Calendar.MILLISECOND, 0);
        if (six.getTimeInMillis() > System.currentTimeMillis()) six.add(Calendar.DAY_OF_MONTH, -1);
        return at > six.getTimeInMillis();
    }

    /** Trade gate and simulation assumptions for the pre-live replay and paper trades. */
    public com.krish.niftydirection.intel.Validator.Config simConfig() {
        com.krish.niftydirection.intel.Validator.Config c = new com.krish.niftydirection.intel.Validator.Config();
        c.threshold = Math.max(0.5, Math.min(0.95, num("trade_threshold", 62) / 100.0));
        c.lot = (int) Math.max(1, num("sim_lot", 65));
        c.slippagePts = Math.max(0, num("sim_slip", 1));
        c.stopMult = Math.max(0, num("sim_stop", 1));
        c.guard.on = bool("guard_on", true);
        c.guard.maxLossRupees = Math.max(0, num("guard_loss", 3000));
        c.guard.maxTrades = (int) Math.max(1, num("guard_trades", 3));
        c.guard.coolMin = (int) Math.max(0, num("guard_cool", 30));
        c.guard.noFirstMin = (int) Math.max(0, num("guard_first", 15));
        c.guard.noLastMin = (int) Math.max(0, num("guard_last", 30));
        return c;
    }

    public Collector.Config config() {
        if (!bool("mig13", false)) {   // v1.3: chain width default 10 → 15 (outer flow layer)
            if (str("strikes", "").trim().equals("10")) put("strikes", "15");
            put("mig13", true);
        }
        Collector.Config c = new Collector.Config();
        c.apiKey = apiKey();
        c.token = hasValidSession() ? token() : "";
        c.globalOn = bool("global_on", true);
        c.nseOn = bool("nse_on", true);
        c.gift = num("gift", Double.NaN);
        c.giftDate = str("gift_date", "");
        c.fiiManual = num("fii_manual", Double.NaN);
        c.strikesEachSide = (int) Math.max(6, Math.min(20, num("strikes", 15)));
        c.oiStrikes = (int) Math.max(2, Math.min(c.strikesEachSide, num("oi_strikes", 6)));
        c.newsOn = bool("news_on", true);
        c.geminiKey = str("gemini_key", "").trim();
        c.geminiModel = str("gemini_model", "auto").trim();
        c.userEvents = str("user_events", "");
        return c;
    }
}
