package com.accmgr.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
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

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap f) {
                if (t == cur && !u.equals("about:blank")) url.setText(u);
            }

            @Override
            public void onPageFinished(WebView v, String u) {
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
