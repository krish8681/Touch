package com.krish.niftydirection.data;

import com.krish.niftydirection.model.NewsItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini news reader. It rates each headline's likely effect on the market and on Nifty.
 * It is told never to say buy or sell; the app's own engine decides how much the rating counts.
 */
public final class Gemini {
    private Gemini() {}

    /** Not final so tests can point it at a mock. */
    public static String ROOT = "https://generativelanguage.googleapis.com/v1beta/";
    private static String chosen = "";
    public static final String PROMPT_VERSION = "p3";
    /** File that pins the model "auto" picked, so results stay comparable from day to day. Set by the collector. */
    public static java.io.File pinFile;
    public static String lastSwitchNote = "";

    static boolean isAuto(String preferred) { return preferred == null || preferred.trim().isEmpty() || preferred.trim().equalsIgnoreCase("auto"); }

    /**
     * The model to use: the one typed in Settings; or for "auto", the model auto picked before (pinned on the phone),
     * or if none yet, the newest general Flash model this key can use — which is then pinned.
     */
    public static String model(String key, String preferred) throws Exception {
        if (!isAuto(preferred)) return preferred.trim();
        if (!chosen.isEmpty()) return chosen;
        if (pinFile != null && pinFile.exists()) {
            try {
                String p = new String(java.nio.file.Files.readAllBytes(pinFile.toPath()), "UTF-8").trim();
                if (!p.isEmpty()) { chosen = p; return p; }
            } catch (Exception ignored) {}
        }
        chosen = newest(key);
        pin(chosen);
        return chosen;
    }

    static void pin(String m) {
        if (pinFile == null) return;
        try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(pinFile), "UTF-8")) { w.write(m); } catch (Exception ignored) {}
    }

    static String newest(String key) throws Exception {
        String body = Http.get(ROOT + "models?pageSize=200&key=" + Http.enc(key), null, 15000);
        JSONArray a = new JSONObject(body).optJSONArray("models");
        String best = "", bestLite = "";
        double bv = -1, bl = -1;
        Pattern ver = Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject m = a.getJSONObject(i);
            String name = m.optString("name", "").replace("models/", "");
            String methods = String.valueOf(m.optJSONArray("supportedGenerationMethods"));
            if (!name.contains("flash") || !methods.contains("generateContent")) continue;
            if (name.matches(".*(image|tts|live|audio|embedding|exp|preview|thinking|transcribe|computer).*")) continue;
            Matcher vm = ver.matcher(name);
            double v = vm.find() ? Double.parseDouble(vm.group(1)) : 0;
            if (name.contains("lite")) { if (v > bl) { bl = v; bestLite = name; } }
            else if (v > bv) { bv = v; best = name; }
        }
        return !best.isEmpty() ? best : !bestLite.isEmpty() ? bestLite : "gemini-flash-latest";
    }

    /** Rate up to 40 headlines in one call. Fills the fields of each item it rated; returns the model used. */
    public static String rate(List<NewsItem> items, String key, String preferred) throws Exception {
        if (items.isEmpty()) return "";
        String model = model(key, preferred);
        StringBuilder list = new StringBuilder();
        for (NewsItem n : items) list.append("{\"id\":\"").append(n.id).append("\",\"source\":\"").append(esc(n.source))
                .append("\",\"headline\":\"").append(esc(n.title)).append("\"}\n");
        String prompt = "You are a careful markets news analyst for India's NIFTY 50 index. For each headline below, judge its likely effect. "
                + "Never recommend buying or selling anything.\n"
                + "Return ONLY a JSON array with one object per headline, keys:\n"
                + "id (copy it), marketImpact (number -1..1, effect on Indian shares overall), niftyImpact (number -1..1, effect on the NIFTY 50 level), "
                + "sector (one of: Financials, IT, Energy, Auto, FMCG, Healthcare, Metals, Infra, Power, Telecom, Consumer, Broad market, None), "
                + "severity (LOW, MEDIUM or HIGH), horizon (intraday, days or weeks), "
                + "scheduled (true if it announces or previews a scheduled event such as a policy meeting, data release, budget, election), "
                + "eventDate (yyyy-mm-dd of that event, or empty), eventName (short, or empty), reason (at most 15 words), "
                + "topic (2-5 lowercase words naming the underlying story, the same for headlines about the same story), "
                + "speculative (true if the headline reports a rumour, a possibility, an expectation or unnamed sources rather than a fact).\n"
                + "Rules: be conservative, 0 means no clear effect; single-company news is LOW unless the company is a top-10 Nifty weight; "
                + "opinion pieces, stale or already-known news get impact near 0; HIGH only for news that can move the whole index today.\n\n"
                + "Headlines:\n" + list;
        JSONObject req = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject part = new JSONObject().put("text", prompt);
        contents.put(new JSONObject().put("parts", new JSONArray().put(part)));
        req.put("contents", contents);
        req.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1));
        String body;
        try {
            body = postJson(ROOT + "models/" + model + ":generateContent?key=" + Http.enc(key), req.toString());
        } catch (Http.HttpError e) {
            if (e.code == 404 && isAuto(preferred)) {
                String old = model;
                chosen = newest(key);
                pin(chosen);
                lastSwitchNote = "Gemini model " + old + " is no longer available; switched to " + chosen + ".";
                model = chosen;
                body = postJson(ROOT + "models/" + model + ":generateContent?key=" + Http.enc(key), req.toString());
            } else throw new Exception(geminiError(e));
        }
        JSONObject resp = new JSONObject(body);
        JSONArray cands = resp.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new Exception("Gemini gave no answer");
        JSONArray parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text", ""));
        String t = text.toString().trim();
        if (t.startsWith("```")) t = t.replaceAll("^```[a-zA-Z]*", "").replaceAll("```\\s*$", "").trim();
        int s0 = t.indexOf('[');
        if (s0 > 0) t = t.substring(s0);
        JSONArray out = new JSONArray(t);
        Map<String, NewsItem> byId = new HashMap<>();
        for (NewsItem n : items) byId.put(n.id, n);
        for (int i = 0; i < out.length(); i++) {
            JSONObject o = out.getJSONObject(i);
            NewsItem n = byId.get(o.optString("id", ""));
            if (n == null) continue;
            n.marketImpact = clamp(o.optDouble("marketImpact", 0));
            n.niftyImpact = clamp(o.optDouble("niftyImpact", n.marketImpact));
            n.sector = o.optString("sector", "");
            String sev = o.optString("severity", "LOW").toUpperCase(Locale.US);
            n.severity = sev.startsWith("H") ? "HIGH" : sev.startsWith("M") ? "MEDIUM" : "LOW";
            n.horizon = o.optString("horizon", "intraday");
            n.scheduled = o.optBoolean("scheduled");
            n.eventDate = o.optString("eventDate", "");
            n.eventName = o.optString("eventName", "");
            n.reason = o.optString("reason", "");
            n.topic = o.optString("topic", "").toLowerCase(Locale.US).trim();
            n.speculative = o.optBoolean("speculative");
            n.model = model;
            n.promptVersion = PROMPT_VERSION;
            n.ratedAt = System.currentTimeMillis();
            n.by = "Gemini";
            n.read = true;
        }
        return model;
    }

    static String geminiError(Http.HttpError e) {
        try { return "Gemini: " + new JSONObject(e.body).getJSONObject("error").optString("message", e.getMessage()); } catch (Exception x) { return "Gemini: " + e.getMessage(); }
    }

    static String postJson(String url, String json) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        return Http.send("POST", url, h, json.getBytes("UTF-8"), 45000);
    }

    static double clamp(double v) { return Double.isNaN(v) ? 0 : Math.max(-1, Math.min(1, v)); }
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
