package com.krish.niftydirection.ui;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.krish.niftydirection.data.Kite;
import com.krish.niftydirection.data.Prefs;

/** Kite login in a WebView. Catches the redirect to https://127.0.0.1/kite and swaps the request token for an access token. */
public class LoginActivity extends Activity {
    private Prefs prefs;
    private TextView msg;
    private boolean done;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        LinearLayout shell = Ui.col(this);
        shell.setBackgroundColor(Ui.BG);
        msg = Ui.text(this, "Log in to Zerodha Kite", 14, Ui.TEXT, true);
        msg.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 10));
        shell.addView(msg, Ui.matchW());
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { return catchRedirect(req.getUrl().toString()); }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return catchRedirect(url); }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) { if (catchRedirect(url)) v.stopLoading(); }
            @Override public void onReceivedError(WebView v, android.webkit.WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req != null && req.isForMainFrame()) catchRedirect(req.getUrl().toString());
            }
        });
        shell.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(shell);
        web.loadUrl(Kite.loginUrl(prefs.apiKey()));
    }

    /**
     * Any redirect Kite sends back after login: our own Redirect URL (https://127.0.0.1/kite), a plain https://127.0.0.1/,
     * localhost, or any page carrying a request_token. The token is read from the URL, so nothing needs to load there.
     */
    static boolean isRedirect(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(java.util.Locale.US);
        if (u.startsWith("https://kite.zerodha.com") || u.startsWith("https://kite.trade")) return false;
        return u.startsWith("https://127.0.0.1") || u.startsWith("http://127.0.0.1") || u.startsWith("https://localhost") || u.startsWith("http://localhost")
                || u.contains("request_token=");
    }

    private boolean catchRedirect(String url) {
        if (!isRedirect(url)) return false;
        if (done) return true;
        Uri u = Uri.parse(url);
        String rt = u.getQueryParameter("request_token");
        String st = u.getQueryParameter("status");
        if (rt == null || (st != null && !st.equals("success"))) { msg.setText("Login was cancelled or failed. Go back and try again."); msg.setTextColor(Ui.RED); return true; }
        done = true;
        msg.setText("Logging in…");
        new Thread(() -> {
            try {
                String[] s = Kite.createSession(prefs.apiKey(), prefs.apiSecret(), rt);
                prefs.saveSession(s[0], s[1]);
                runOnUiThread(() -> { Toast.makeText(this, "Logged in" + (s[1].isEmpty() ? "" : " as " + s[1]), Toast.LENGTH_SHORT).show(); finish(); });
            } catch (Exception e) {
                done = false;
                runOnUiThread(() -> { msg.setText("Login failed: " + e.getMessage() + "\nCheck the API secret in Settings."); msg.setTextColor(Ui.RED); });
            }
        }).start();
        return true;
    }
}
