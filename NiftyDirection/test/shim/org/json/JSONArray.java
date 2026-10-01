package org.json;
import java.util.*;
/** Test-only shim of org.json.JSONArray. */
public class JSONArray {
    final ArrayList<Object> list = new ArrayList<>();
    public JSONArray() {}
    public JSONArray(String s) { JSONTokener t = new JSONTokener(s); t.ws(); list.addAll(t.arr().list); }
    public int length() { return list.size(); }
    public JSONArray put(Object v) { list.add(v); return this; }
    public JSONArray put(double v) { list.add(v); return this; }
    public JSONArray put(int v) { list.add(v); return this; }
    public JSONArray put(long v) { list.add(v); return this; }
    public JSONArray put(int i, Object v) { while (list.size() <= i) list.add(JSONObject.NULL); list.set(i, v); return this; }
    public JSONArray optJSONArray(int i) { Object v = i < list.size() ? list.get(i) : null; return v instanceof JSONArray ? (JSONArray) v : null; }
    public JSONObject optJSONObject(int i) { Object v = i < list.size() ? list.get(i) : null; return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONObject getJSONObject(int i) { Object v = list.get(i); if (!(v instanceof JSONObject)) throw new JSONException("not object"); return (JSONObject) v; }
    public JSONArray getJSONArray(int i) { Object v = list.get(i); if (!(v instanceof JSONArray)) throw new JSONException("not array"); return (JSONArray) v; }
    public String optString(int i) { return optString(i, ""); }
    public int optInt(int i) { return (int) num(i < list.size() ? list.get(i) : null, 0); }
    public Object get(int i) { return list.get(i); }
    public long getLong(int i) { return (long) num(list.get(i), 0); }
    public double getDouble(int i) { return num(list.get(i), 0); }
    public int getInt(int i) { return (int) num(list.get(i), 0); }
    public String optString(int i, String d) { Object v = i < list.size() ? list.get(i) : null; return v == null || v == JSONObject.NULL ? d : v.toString(); }
    public double optDouble(int i) { return optDouble(i, Double.NaN); }
    public double optDouble(int i, double d) { return num(i < list.size() ? list.get(i) : null, d); }
    static double num(Object v, double d) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) try { return Double.parseDouble((String) v); } catch (Exception e) { return d; }
        return d;
    }
    @Override public String toString() {
        StringBuilder b = new StringBuilder("["); for (int i = 0; i < list.size(); i++) { if (i > 0) b.append(','); b.append(JSONTokener.write(list.get(i))); } return b.append(']').toString();
    }
}
