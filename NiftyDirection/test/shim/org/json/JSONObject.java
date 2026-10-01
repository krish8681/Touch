package org.json;
import java.util.*;
/** Test-only shim of org.json.JSONObject. */
public class JSONObject {
    public static final Object NULL = new Object() { public String toString() { return "null"; } };
    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String s) { JSONTokener t = new JSONTokener(s); t.ws(); JSONObject o = t.obj(); map.putAll(o.map); }
    public Iterator<String> keys() { return new ArrayList<>(map.keySet()).iterator(); }
    public boolean has(String k) { return map.containsKey(k); }
    public boolean isNull(String k) { Object v = map.get(k); return v == null || v == NULL; }
    public int length() { return map.size(); }
    public Object remove(String k) { return map.remove(k); }
    public JSONObject put(String k, Object v) { map.put(k, v instanceof Float ? (Object) ((Float) v).doubleValue() : v); return this; }
    public JSONObject put(String k, double v) { map.put(k, v); return this; }
    public JSONObject put(String k, int v) { map.put(k, v); return this; }
    public JSONObject put(String k, long v) { map.put(k, v); return this; }
    public JSONObject put(String k, boolean v) { map.put(k, v); return this; }
    public JSONObject optJSONObject(String k) { Object v = map.get(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k) { Object v = map.get(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public JSONObject getJSONObject(String k) { JSONObject o = optJSONObject(k); if (o == null) throw new JSONException("no object " + k); return o; }
    public JSONArray getJSONArray(String k) { JSONArray o = optJSONArray(k); if (o == null) throw new JSONException("no array " + k); return o; }
    public String getString(String k) { Object v = map.get(k); if (v == null) throw new JSONException("no " + k); return v.toString(); }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String d) { Object v = map.get(k); return v == null || v == NULL ? d : v.toString(); }
    public double optDouble(String k) { return optDouble(k, Double.NaN); }
    public double optDouble(String k, double d) { return JSONArray.num(map.get(k), d); }
    public long optLong(String k) { return (long) JSONArray.num(map.get(k), 0); }
    public long optLong(String k, long d) { return (long) JSONArray.num(map.get(k), d); }
    public int optInt(String k) { return optInt(k, 0); }
    public int optInt(String k, int d) { return (int) JSONArray.num(map.get(k), d); }
    public boolean optBoolean(String k, boolean d) { return has(k) ? optBoolean(k) : d; }
    public boolean optBoolean(String k) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : "true".equals(String.valueOf(v)); }
    @Override public String toString() {
        StringBuilder b = new StringBuilder("{"); boolean f = true;
        for (Map.Entry<String, Object> e : map.entrySet()) { if (!f) b.append(','); f = false; b.append(JSONTokener.quote(e.getKey())).append(':').append(JSONTokener.write(e.getValue())); }
        return b.append('}').toString();
    }
}
