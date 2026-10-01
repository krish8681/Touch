package org.json;
import java.util.*;
/** Test-only minimal JSON parser. */
class JSONTokener {
    final String s; int i;
    JSONTokener(String s) { this.s = s; }
    void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    Object value() {
        ws();
        if (i >= s.length()) throw new JSONException("end");
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return str();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return JSONObject.NULL; }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw new JSONException("bad char " + c + " at " + i);
        String n = s.substring(st, i);
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
        long l = Long.parseLong(n);
        return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (Object) (int) l : (Object) l;
    }
    JSONObject obj() {
        JSONObject o = new JSONObject(); i++; ws();
        if (s.charAt(i) == '}') { i++; return o; }
        while (true) {
            ws(); String k = str(); ws();
            if (s.charAt(i++) != ':') throw new JSONException("colon");
            o.map.put(k, value()); ws();
            char c = s.charAt(i++);
            if (c == '}') return o;
            if (c != ',') throw new JSONException("comma");
        }
    }
    JSONArray arr() {
        JSONArray a = new JSONArray(); i++; ws();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            a.list.add(value()); ws();
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw new JSONException("comma");
        }
    }
    String str() {
        if (s.charAt(i) != '"') throw new JSONException("quote at " + i);
        i++; StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
    }
    static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 32) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    static String write(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof String) return quote((String) v);
        if (v instanceof Double) { double d = (Double) v; return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d); }
        return v.toString();
    }
}
