package com.w8bdcr.bloodbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * W8BDCR Blood Box V2 — blood donor finder for Bangladesh.
 * WebView shell + W8Native bridge around a bundled Bangla HTML UI.
 * Donor registry is ONLINE and SHARED via Firebase Realtime Database
 * (https://w8bdcr-gate-default-rtdb.firebaseio.com/donors) — a donor
 * registered on one phone appears on every phone. No secret, no auth:
 * plain REST on the public /donors node. Passwords stay on the phone
 * (localStorage only) and are never sent to Firebase.
 * Network calls go through this Java bridge (HttpURLConnection) because
 * fetch() from a file:// page is unreliable on this device.
 */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // WebChromeClient MUST be set: JS confirm()/alert() silently fail without it.
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new NativeBridge(), "W8Native");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class NativeBridge {

        /** Open the dialer with the number pre-filled (no CALL permission needed). */
        @JavascriptInterface
        public void dial(String number) {
            try {
                String n = number == null ? "" : number.replaceAll("[^0-9+]", "");
                if (n.length() < 11) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "সঠিক নম্বর পাওয়া যায়নি", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + n));
                startActivity(i);
            } catch (Exception e) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, "ডায়াল খোলা যায়নি", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            final String m = msg == null ? "" : msg;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
                }
            });
        }

        /**
         * Generic HTTP request on a background thread (fetch() from a
         * file:// page is unreliable on this device). Result is delivered
         * back to JS as window.__apiDone(cbId, '{"ok":..,"status":..,"body":".."}').
         * Used for the Firebase donor registry (GET/POST/DELETE .../donors.json).
         */
        @JavascriptInterface
        public void http(final String method, final String urlStr, final String body, final int cbId) {
            new Thread(new Runnable() {
                @Override public void run() {
                    String out;
                    try {
                        URL url = new URL(urlStr);
                        HttpURLConnection c = (HttpURLConnection) url.openConnection();
                        c.setRequestMethod(method);
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(15000);
                        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        c.setRequestProperty("Accept", "application/json");
                        if (body != null && body.length() > 0
                                && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                            c.setDoOutput(true);
                            byte[] b = body.getBytes(StandardCharsets.UTF_8);
                            c.setFixedLengthStreamingMode(b.length);
                            OutputStream os = c.getOutputStream();
                            os.write(b);
                            os.flush();
                            os.close();
                        }
                        int status = c.getResponseCode();
                        InputStream in = (status >= 200 && status < 300) ? c.getInputStream() : c.getErrorStream();
                        StringBuilder sb = new StringBuilder();
                        if (in != null) {
                            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line);
                            r.close();
                        }
                        c.disconnect();
                        JSONObject j = new JSONObject();
                        j.put("ok", status >= 200 && status < 300);
                        j.put("status", status);
                        j.put("body", sb.toString());
                        out = j.toString();
                    } catch (Exception e) {
                        try {
                            JSONObject j = new JSONObject();
                            j.put("ok", false);
                            j.put("status", 0);
                            j.put("body", "");
                            j.put("error", "network");
                            out = j.toString();
                        } catch (Exception e2) {
                            out = "{\"ok\":false,\"status\":0,\"body\":\"\"}";
                        }
                    }
                    final String js = "window.__apiDone(" + cbId + "," + JSONObject.quote(out) + ");";
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (web != null) web.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
        }

        /** Share a blood-request text via Android's share sheet. */
        @JavascriptInterface
        public void share(final String text) {
            final String t = text == null ? "" : text;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, t);
                        startActivity(Intent.createChooser(i, "শেয়ার করুন"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "শেয়ার করা যায়নি", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }
}
