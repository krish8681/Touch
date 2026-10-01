package com.krish.niftydirection.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Tiny HTTP helper on HttpURLConnection. No libraries. */
public final class Http {
    private Http() {}

    public static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36";

    public static class HttpError extends IOException {
        public final int code; public final String body;
        HttpError(int code, String body, String url) { super("HTTP " + code + " from " + host(url)); this.code = code; this.body = body; }
    }

    public static String get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        return send("GET", url, headers, null, timeoutMs);
    }

    public static String postForm(String url, Map<String, String> headers, Map<String, String> form, int timeoutMs) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return send("POST", url, headers, b.toString().getBytes(StandardCharsets.UTF_8), timeoutMs);
    }

    static String send(String method, String url, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                if (headers == null || !headers.containsKey("Content-Type")) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in, "gzip".equalsIgnoreCase(c.getContentEncoding()));
            if (code >= 400) throw new HttpError(code, text, url);
            return text;
        } finally {
            c.disconnect();
        }
    }

    static String read(InputStream in, boolean gz) throws IOException {
        if (gz) in = new GZIPInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try { while ((n = in.read(buf)) > 0) out.write(buf, 0, n); } finally { in.close(); }
        return out.toString("UTF-8");
    }

    static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }

    public static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
