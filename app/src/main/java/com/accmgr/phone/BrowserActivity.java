package com.accmgr.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.Switch;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BrowserActivity extends Activity {
    private static final int MAX_TABS = 8;

    private static class Tab {
        WebView web;
        String title = "New tab";
    }

    private final List<Tab> tabs = new ArrayList<>();
    private Tab cur;
    private FrameLayout content;
    private LinearLayout tabRow;
    private EditText url;
    private ProgressBar progress;
    private boolean closing = false;
    private volatile String lastUrl = "";
    private volatile List<JSONObject> exts = new ArrayList<>();
    private TextView addBar;
    private boolean saveHistory = true;
    private String accId, accName;

    private int dp(int v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        accId = getIntent().getStringExtra("id");
        accName = getIntent().getStringExtra("name");
        String email = getIntent().getStringExtra("email");
        saveHistory = getIntent().getBooleanExtra("history", true) && Store.validId(accId);
        if (Store.validId(accId)) exts = Extensions.list(this, accId);

        getWindow().setStatusBarColor(Ui.PANEL);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(Ui.PANEL);
        top.setPadding(dp(14), dp(8), dp(14), dp(10));

        // ----- account row -----
        LinearLayout acc = new LinearLayout(this);
        acc.setGravity(Gravity.CENTER_VERTICAL);
        acc.addView(Ui.avatar(this, accName, accId, 34));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = Ui.tv(this, accName == null ? "" : accName, 16, Ui.FG, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm);
        if (email != null && !email.isEmpty()) {
            TextView em = Ui.tv(this, email, 11, Ui.MUTED, false);
            em.setSingleLine(true);
            em.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(em);
        }
        acc.addView(col, Ui.lp(this, 0, -2, 1f, 10, 0, 8, 0));
        ImageView hist = Ui.iconButton(this, R.drawable.ic_history, 40);
        acc.addView(hist, Ui.lp(this, dp(40) , dp(40), 0f, 0, 0, 8, 0));
        Button close = Ui.button(this, "Save & close", true, false);
        close.setTextSize(13);
        close.setPadding(dp(14), dp(9), dp(14), dp(9));
        acc.addView(close);
        top.addView(acc, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 10));

        // ----- tabs -----
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(this);
        hs.addView(tabRow);
        tabBar.addView(hs, Ui.lp(this, 0, -2, 1f, 0, 0, 8, 0));
        ImageView extBtn = Ui.iconButton(this, R.drawable.ic_ext, 36);
        tabBar.addView(extBtn, Ui.lp(this, dp(36), dp(36), 0f, 0, 0, 8, 0));
        ImageView plus = Ui.iconButton(this, R.drawable.ic_add, 36);
        tabBar.addView(plus);
        top.addView(tabBar, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 10));

        // ----- address bar -----
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        url = Ui.field(this, "Search or type a URL");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setSelectAllOnFocus(true);
        url.setTextSize(14);
        bar.addView(url, Ui.lp(this, 0, -2, 1f, 0, 0, 8, 0));
        ImageView go = Ui.iconButton(this, R.drawable.ic_forward, 42);
        bar.addView(go);
        top.addView(bar);
        root.addView(top);

        addBar = Ui.tv(this, "Add this extension to " + accName, 14, 0xFFFFFFFF, true);
        addBar.setGravity(Gravity.CENTER);
        addBar.setBackground(Ui.ripple(this, Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_B, 0), 0));
        addBar.setPadding(dp(14), dp(12), dp(14), dp(12));
        addBar.setVisibility(View.GONE);
        addBar.setOnClickListener(v -> {
            String id = Extensions.storeId(lastUrl);
            String ttl = cur == null ? "" : cur.web.getTitle();
            if (id != null) confirmAdd(id, ttl == null ? "" : ttl.replace(" - Chrome Web Store", "").trim());
        });
        root.addView(addBar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.PANEL));
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        CookieManager.getInstance().setAcceptCookie(true);

        go.setOnClickListener(v -> goUrl());
        close.setOnClickListener(v -> closeAndSave());
        hist.setOnClickListener(v -> {
            if (!saveHistory) {
                Ui.message(this, "History is off", "Turn on \"Save browsing history\" in Settings to keep a log.");
                return;
            }
            Ui.history(this, accId, accName, u -> {
                if (cur != null) cur.web.loadUrl(u);
            });
        });
        extBtn.setOnClickListener(v -> showExtensions());
        plus.setOnClickListener(v -> {
            if (tabs.size() >= MAX_TABS) {
                Toast.makeText(this, "Up to " + MAX_TABS + " tabs", Toast.LENGTH_SHORT).show();
                return;
            }
            newTab("https://www.google.com", true);
        });
        url.setOnEditorActionListener((v, id, ev) -> {
            goUrl();
            return true;
        });

        String start = getIntent().getStringExtra("url");
        if (start == null || !(start.startsWith("http://") || start.startsWith("https://"))) {
            start = "https://mail.google.com";
        }
        newTab(start, true);
    }

    // ---------- tabs ----------
    private Tab newTab(String firstUrl, boolean select) {
        final Tab t = new Tab();
        final WebView w = new WebView(this);
        t.web = w;

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(true);
        // Remove the WebView marker so sign-in pages (e.g. Google) behave like normal Chrome
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new Bridge(), "AccExt");

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap f) {
                if (t == cur && !u.equals("about:blank")) {
                    url.setText(u);
                    updateAddBar(u);
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String u, boolean reload) {
                if (t == cur && u != null) updateAddBar(u);
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                if (!closing) {
                    Extensions.run(v, u, exts);
                    if (Extensions.isStoreHost(u)) v.evaluateJavascript(Extensions.STORE_HOOK, null);
                }
                if (saveHistory && !closing && u != null
                        && (u.startsWith("http://") || u.startsWith("https://"))) {
                    Store.addHistory(BrowserActivity.this, accId, v.getTitle(), u);
                }
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView v, String ttl) {
                if (ttl != null && !ttl.isEmpty() && !ttl.equals("about:blank")) {
                    t.title = ttl;
                    rebuildTabs();
                }
            }

            @Override
            public void onProgressChanged(WebView v, int p) {
                if (t != cur) return;
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (tabs.size() >= MAX_TABS) {
                    Toast.makeText(BrowserActivity.this, "Up to " + MAX_TABS + " tabs", Toast.LENGTH_SHORT).show();
                    return false;
                }
                Tab nt = newTab(null, true);
                WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                tr.setWebView(nt.web);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView v) {
                for (final Tab x : tabs) {
                    if (x.web == v) {
                        new Handler(Looper.getMainLooper()).post(() -> closeTab(x));
                        break;
                    }
                }
            }
        });

        // press and hold on a link or image for options
        w.setOnLongClickListener(v -> {
            WebView.HitTestResult r = w.getHitTestResult();
            int type = r.getType();
            final String extra = r.getExtra();
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                    || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                Message msg = new Handler(Looper.getMainLooper()) {
                    @Override
                    public void handleMessage(Message m) {
                        String u = m.getData().getString("url");
                        if (u == null || u.isEmpty()) u = extra;
                        if (u != null) linkMenu(w, u, false);
                    }
                }.obtainMessage();
                w.requestFocusNodeHref(msg);
                return true;
            }
            if (type == WebView.HitTestResult.IMAGE_TYPE && extra != null) {
                linkMenu(w, extra, true);
                return true;
            }
            return false;
        });

        tabs.add(t);
        if (select) selectTab(t);
        else rebuildTabs();
        if (firstUrl != null) w.loadUrl(firstUrl);
        return t;
    }

    private void selectTab(Tab t) {
        cur = t;
        content.removeAllViews();
        if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web);
        content.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
        String u = t.web.getUrl();
        url.setText(u == null || u.equals("about:blank") ? "" : u);
        updateAddBar(u == null ? "" : u);
        progress.setVisibility(View.INVISIBLE);
        rebuildTabs();
    }

    private void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        tabs.remove(t);
        if (t.web.getParent() != null) ((ViewGroup) t.web.getParent()).removeView(t.web);
        t.web.destroy();
        if (tabs.isEmpty()) {
            newTab("https://www.google.com", true);
            return;
        }
        if (t == cur) selectTab(tabs.get(Math.min(i, tabs.size() - 1)));
        else rebuildTabs();
    }

    private void rebuildTabs() {
        tabRow.removeAllViews();
        for (final Tab t : tabs) {
            boolean sel = t == cur;
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(sel
                    ? Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_B, 18)
                    : Ui.card(this, Ui.PANEL2, 18));
            chip.setPadding(dp(14), dp(7), dp(4), dp(7));
            TextView lbl = Ui.tv(this, shorten(t.title, 14), 13, sel ? 0xFFFFFFFF : Ui.FG, false);
            lbl.setSingleLine(true);
            TextView x = Ui.tv(this, "\u00D7", 18, sel ? 0xFFFFFFFF : Ui.MUTED, false);
            x.setPadding(dp(10), 0, dp(10), 0);
            chip.addView(lbl);
            chip.addView(x);
            chip.setOnClickListener(v -> selectTab(t));
            x.setOnClickListener(v -> closeTab(t));
            tabRow.addView(chip, Ui.lp(this, -2, -2, 0f, 0, 0, 6, 0));
        }
    }

    private String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n - 1) + "\u2026" : s;
    }

    // ---------- link / image menu ----------
    private void linkMenu(final WebView from, final String link, final boolean image) {
        if (!(link.startsWith("http://") || link.startsWith("https://"))) return;
        String[] items = image
                ? new String[]{"Open image in new tab", "Copy image link"}
                : new String[]{"Open in new tab", "Open in background tab", "Open here", "Copy link"};
        Ui.menu(this, shorten(link, 50), items, -1, which -> {
            if (image) {
                if (which == 0) openInNewTab(link, true);
                else copyLink(link);
            } else {
                if (which == 0) openInNewTab(link, true);
                else if (which == 1) openInNewTab(link, false);
                else if (which == 2) from.loadUrl(link);
                else copyLink(link);
            }
        });
    }

    private void openInNewTab(String link, boolean select) {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Up to " + MAX_TABS + " tabs", Toast.LENGTH_SHORT).show();
            return;
        }
        newTab(link, select);
        if (!select) Toast.makeText(this, "Opening in a background tab", Toast.LENGTH_SHORT).show();
    }

    private void copyLink(String link) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("link", link));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    // ---------- extensions (per account) ----------
    private class Bridge {
        @android.webkit.JavascriptInterface
        public void addExtension(String id, String title) {
            final String u = lastUrl;
            if (id == null || !id.matches("[a-p]{32}") || !id.equals(Extensions.storeId(u))) return;
            final String name = title == null ? "" : title.replace(" - Chrome Web Store", "").trim();
            final String fid = id;
            runOnUiThread(() -> confirmAdd(fid, name));
        }
    }

    private void updateAddBar(String u) {
        lastUrl = u;
        String id = Extensions.storeId(u);
        addBar.setVisibility(id != null && Store.validId(accId) ? View.VISIBLE : View.GONE);
    }

    private void confirmAdd(final String id, final String name) {
        if (!Store.validId(accId)) return;
        String n = name.isEmpty() ? "this extension" : "\"" + name + "\"";
        Ui.dialog(this, "Add extension?",
                "Add " + n + " to " + accName + "? It works only in this account. Only the part that changes web pages runs; toolbar buttons and background features don't work on Android.",
                null, "Cancel", "Add", false, () -> {
                    doAdd(id, name);
                    return true;
                });
    }

    private void doAdd(final String id, final String name) {
        final Dialog ld = Ui.loading(this, "Adding extension\u2026");
        new Thread(() -> {
            String title, msg;
            try {
                msg = Extensions.install(this, accId, id, name);
                title = "Extension added";
                exts = Extensions.list(this, accId);
            } catch (Exception ex) {
                title = "Can't add this extension";
                msg = String.valueOf(ex.getMessage());
            }
            final String t = title, m = msg;
            runOnUiThread(() -> {
                ld.dismiss();
                Ui.message(this, t, m);
            });
        }).start();
    }

    private void showExtensions() {
        if (!Store.validId(accId)) return;
        exts = Extensions.list(this, accId);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        if (exts.isEmpty()) {
            body.addView(Ui.tv(this, "No extensions in this account yet.", 14, Ui.MUTED, false));
        }
        final Dialog[] ref = new Dialog[1];
        for (final JSONObject e : exts) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(Ui.card(this, Ui.PANEL2, 12));
            r.setPadding(dp(12), dp(8), dp(8), dp(8));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView nm = Ui.tv(this, e.optString("name"), 14, Ui.FG, true);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            String ver = e.optString("version");
            col.addView(nm);
            if (!ver.isEmpty()) col.addView(Ui.tv(this, "Version " + ver, 11, Ui.MUTED, false));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            Switch sw = new Switch(this);
            sw.setChecked(e.optBoolean("on", true));
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{Ui.ACCENT, 0xFF3A3A50}));
            final String eid = e.optString("id");
            sw.setOnCheckedChangeListener((v, on) -> {
                try {
                    Extensions.setOn(this, accId, eid, on);
                    exts = Extensions.list(this, accId);
                } catch (Exception ex) {
                    Toast.makeText(this, "Error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
            r.addView(sw);
            TextView del = Ui.tv(this, "\u00D7", 22, Ui.DANGER, false);
            del.setPadding(dp(12), 0, dp(8), 0);
            del.setOnClickListener(v -> {
                try {
                    Extensions.remove(this, accId, eid);
                    exts = Extensions.list(this, accId);
                } catch (Exception ex) {
                    Toast.makeText(this, "Error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                }
                if (ref[0] != null) ref[0].dismiss();
                showExtensions();
            });
            r.addView(del);
            body.addView(r, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 8));
        }
        ref[0] = Ui.dialog(this, "Extensions \u00B7 " + accName,
                "Open an extension on the Chrome Web Store and tap its Add button. It is added only to this account. VPN tools and toolbar-only extensions can't run on Android.",
                body, "Close", "Open Web Store", false, () -> {
                    if (cur != null) cur.web.loadUrl("https://chromewebstore.google.com");
                    return true;
                });
    }

    // ---------- navigation / close ----------
    private void goUrl() {
        String u = url.getText().toString().trim();
        if (u.isEmpty() || cur == null) return;
        if (!u.contains("://")) {
            u = u.contains(" ") || !u.contains(".")
                    ? "https://www.google.com/search?q=" + android.net.Uri.encode(u)
                    : "https://" + u;
        }
        cur.web.loadUrl(u);
    }

    private void closeAndSave() {
        if (closing) return;
        closing = true;
        Toast.makeText(this, "Saving\u2026", Toast.LENGTH_SHORT).show();
        for (Tab t : tabs) {
            t.web.stopLoading();
            t.web.loadUrl("about:blank");
        }
        CookieManager.getInstance().flush();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            CookieManager.getInstance().flush();
            finish();
        }, 1500);
    }

    @Override
    public void onBackPressed() {
        if (cur != null && cur.web.canGoBack()) {
            cur.web.goBack();
        } else if (tabs.size() > 1) {
            closeTab(cur);
        } else {
            Toast.makeText(this, "Use \"Save & close\" to exit", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
        // Kill this process so every web file is released; the main app then saves the session
        Process.killProcess(Process.myPid());
    }
}
