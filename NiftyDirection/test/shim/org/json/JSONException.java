package org.json;
/** Test-only shim of org.json (Android ships the real one). */
public class JSONException extends RuntimeException { public JSONException(String m) { super(m); } }
