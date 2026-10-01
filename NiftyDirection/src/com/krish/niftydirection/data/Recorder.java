package com.krish.niftydirection.data;

import com.krish.niftydirection.engine.Factor;
import com.krish.niftydirection.engine.Result;
import com.krish.niftydirection.model.NewsItem;
import com.krish.niftydirection.model.Quote;
import com.krish.niftydirection.model.Snapshot;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Saves every live input the historical data can NOT give back later (option chain summary, futures order book,
 * FII, news tone, the engine's factor readings) — one JSON line per refresh in files/recorder/rec_<date>.jsonl.
 * After a few months this becomes the training data for models that use these inputs.
 */
public final class Recorder {
    private Recorder() {}

    public static void record(File dir, Snapshot s, Result r) {
        if (!s.live || s.nifty == null || !s.nifty.ok()) return;
        try {
            File d = new File(dir, "recorder");
            d.mkdirs();
            JSONObject j = new JSONObject();
            j.put("t", s.time).put("date", s.today).put("min", s.minute);
            q(j, "nifty", s.nifty); q(j, "bank", s.bank); q(j, "vix", s.vix); q(j, "fut", s.fut); q(j, "futNext", s.futNext);
            if (!Double.isNaN(s.giftNifty)) j.put("gift", s.giftNifty);
            if (!Double.isNaN(s.fiiCash)) j.put("fiiCash", s.fiiCash);
            if (!Double.isNaN(s.fiiIdxLong)) j.put("fiiIdxLong", s.fiiIdxLong);
            double ceOi = 0, peOi = 0, ceChg = 0, peChg = 0;
            for (com.krish.niftydirection.model.OptionRow o : s.chain) {
                ceOi += o.ceOi; peOi += o.peOi; ceChg += o.ceDoi(); peChg += o.peDoi();
            }
            if (!s.chain.isEmpty()) j.put("ceOi", ceOi).put("peOi", peOi).put("ceOiChg", ceChg).put("peOiChg", peChg).put("expiry", s.expiry);
            double tone = 0; int n = 0;
            for (NewsItem ni : s.news) if (ni.read) { tone += ni.niftyImpact; n++; }
            if (n > 0) j.put("newsTone", tone / n).put("newsN", n);
            j.put("score", r.score).put("regime", r.regime).put("conf", r.confidence)
                    .put("struct", nz(r.structScore)).put("live", nz(r.liveScore)).put("event", nz(r.eventScore)).put("eventRisk", r.eventRisk);
            if (r.optFlow != null && !Double.isNaN(r.optFlow.intensity)) j.put("optFlow", r.optFlow.intensity).put("optNet", r.optFlow.net);
            JSONObject f = new JSONObject();
            for (Factor x : r.factors) if (x.available) f.put(x.key, Math.round(x.value * 1000) / 1000.0);
            j.put("factors", f);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(d, "rec_" + s.today + ".jsonl"), true), StandardCharsets.UTF_8)) {
                w.write(j.toString());
                w.write('\n');
            }
            File[] old = d.listFiles((x, name) -> name.startsWith("rec_") && name.compareTo("rec_" + Collector.daysAgo(s.today, 800)) < 0);
            if (old != null) for (File o : old) o.delete();
        } catch (Exception ignored) { }
    }

    static void q(JSONObject j, String k, Quote q) throws Exception {
        if (q == null || !q.ok()) return;
        JSONObject o = new JSONObject();
        o.put("last", q.last).put("open", q.open).put("high", q.high).put("low", q.low).put("prev", q.prevClose);
        if (q.oi > 0) o.put("oi", q.oi);
        if (q.volume > 0) o.put("vol", q.volume);
        if (q.buyQty > 0 || q.sellQty > 0) o.put("buyQty", q.buyQty).put("sellQty", q.sellQty);
        j.put(k, o);
    }

    static double nz(double v) { return Double.isNaN(v) ? 0 : v; }

    /** Number of recorded days (for the Forecast page). */
    public static int days(File dir) {
        File[] fs = new File(dir, "recorder").listFiles((x, n) -> n.startsWith("rec_"));
        return fs == null ? 0 : fs.length;
    }
}
