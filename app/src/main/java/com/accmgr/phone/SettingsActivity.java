package com.accmgr.phone;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SettingsActivity extends android.app.Activity {
    private static final int REQ_IMPORT = 41;
    private SharedPreferences sp;
    private TextView cacheSub, updSub;
    private LinearLayout usageBox;

    private int dp(int v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));
        sv.addView(root);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = Ui.iconButton(this, R.drawable.ic_back, 44);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        head.addView(Ui.tv(this, "Settings", 24, Ui.FG, true), Ui.lp(this, -2, -2, 0f, 14, 0, 0, 0));
        root.addView(head, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 8));

        // ----- Data usage -----
        root.addView(section("DATA USAGE"));
        usageBox = card();
        usageBox.setPadding(dp(16), dp(14), dp(16), dp(14));
        usageBox.addView(Ui.tv(this, "Measuring\u2026", 14, Ui.MUTED, false));
        root.addView(usageBox);

        // ----- Storage -----
        root.addView(section("STORAGE"));
        LinearLayout st = card();
        View cacheRow = row("Clear cache", "Calculating\u2026", v -> clearCache());
        cacheSub = (TextView) cacheRow.getTag();
        st.addView(cacheRow);
        st.addView(divider());
        st.addView(row("Clear all browsing history", "Removes the history log of every account", v ->
                Ui.confirm(this, "Clear all history?", "The history of every account will be deleted. Logins stay saved.",
                        "Clear", true, () -> {
                            Store.clearAllHistory(this);
                            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
                        })));
        root.addView(st);

        // ----- Browsing -----
        root.addView(section("PRIVACY"));
        LinearLayout pr = card();
        pr.addView(switchRow("Save browsing history", "Keep a per-account log of visited pages",
                sp.getBoolean("save_history", true), on -> sp.edit().putBoolean("save_history", on).apply()));
        root.addView(pr);

        // ----- Backup & sharing -----
        root.addView(section("BACKUP & SHARING"));
        LinearLayout bk = card();
        bk.addView(row("Share backup", "Send all accounts and saved logins to another phone", v -> shareBackup()));
        bk.addView(divider());
        bk.addView(row("Import backup", "Restore accounts from a backup file", v -> pickBackup()));
        bk.addView(divider());
        bk.addView(row("Share this app", "Send the app file (APK) to a friend", v -> shareApp()));
        root.addView(bk);
        TextView note = Ui.tv(this, "A backup contains saved logins. Always protect it with a password and share it only with people you trust.",
                12, Ui.MUTED, false);
        note.setLineSpacing(0, 1.2f);
        root.addView(note, Ui.lp(this, -1, -2, 0f, 6, 8, 6, 0));

        // ----- Updates -----
        root.addView(section("APP UPDATES"));
        LinearLayout up = card();
        View updRow = row("Check for updates",
                Updater.enabled() ? "Version " + versionName() : "Updates are not set up in this build",
                v -> checkUpdate());
        updSub = (TextView) updRow.getTag();
        up.addView(updRow);
        root.addView(up);

        // ----- Danger -----
        root.addView(section("DANGER ZONE"));
        LinearLayout dz = card();
        View eraseRow = row("Erase all data", "Delete every account, login and history from this phone", v -> eraseAll());
        ((TextView) ((LinearLayout) eraseRow).getChildAt(0)).setTextColor(Ui.DANGER);
        dz.addView(eraseRow);
        root.addView(dz);

        // ----- About -----
        LinearLayout about = new LinearLayout(this);
        about.setOrientation(LinearLayout.VERTICAL);
        about.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView lg = new ImageView(this);
        lg.setImageResource(R.drawable.logo);
        about.addView(lg, new LinearLayout.LayoutParams(dp(56), dp(56)));
        about.addView(Ui.tv(this, "Accounts", 16, Ui.FG, true), Ui.lp(this, -2, -2, 0f, 0, 10, 0, 0));
        TextView ver = Ui.tv(this, "Version " + versionName() + "\nEverything stays on this phone.", 12, Ui.MUTED, false);
        ver.setGravity(Gravity.CENTER);
        about.addView(ver, Ui.lp(this, -2, -2, 0f, 0, 4, 0, 0));
        root.addView(about, Ui.lp(this, -1, -2, 0f, 0, 28, 0, 0));

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSizes();
    }

    private void checkUpdate() {
        if (!Updater.enabled()) return;
        updSub.setText("Checking\u2026");
        Updater.check(this, true, (tag, url, msg) -> runOnUiThread(() -> {
            updSub.setText(tag != null ? "Update " + tag + " is available" : msg);
            if (tag != null) Updater.prompt(this, tag, url);
        }));
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- small UI builders ----------
    private TextView section(String t) {
        TextView s = Ui.tv(this, t, 11, Ui.MUTED, true);
        s.setLetterSpacing(0.12f);
        s.setPadding(dp(6), dp(22), 0, dp(8));
        return s;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.card(this, Ui.PANEL, 18));
        return l;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.BORDER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        p.setMargins(dp(16), 0, 0, 0);
        v.setLayoutParams(p);
        return v;
    }

    /** Row with a title and subtitle. The subtitle TextView is returned through getTag(). */
    private View row(String title, String subtitle, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.addView(Ui.tv(this, title, 15, Ui.FG, true));
        TextView s = Ui.tv(this, subtitle, 12, Ui.MUTED, false);
        r.addView(s, Ui.lp(this, -2, -2, 0f, 0, 2, 0, 0));
        r.setTag(s);
        if (click != null) {
            r.setBackground(Ui.ripple(this, Ui.rounded(this, 0x00000000, 18), 18));
            r.setOnClickListener(click);
        }
        return r;
    }

    private interface OnToggle { void on(boolean v); }

    private View switchRow(String title, String subtitle, boolean checked, final OnToggle t) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.tv(this, title, 15, Ui.FG, true));
        col.addView(Ui.tv(this, subtitle, 12, Ui.MUTED, false), Ui.lp(this, -2, -2, 0f, 0, 2, 0, 0));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        sw.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Ui.ACCENT, 0xFF3A3A50}));
        sw.setOnCheckedChangeListener((v, on) -> t.on(on));
        r.addView(sw);
        return r;
    }

    // ---------- storage ----------
    private void refreshSizes() {
        final boolean web = !Store.browserRunning(this) && sp.getString("pending_id", null) == null;
        new Thread(() -> {
            final long c = Store.cacheSize(this, web);
            final Store.Usage u = Store.usage(this);
            runOnUiThread(() -> {
                cacheSub.setText(Ui.size(c) + " of temporary files");
                showUsage(u);
            });
        }).start();
    }

    private void legend(int color, String name, long size) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(Ui.rounded(this, color, 5));
        r.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView n = Ui.tv(this, name, 13, Ui.FG, false);
        r.addView(n, Ui.lp(this, 0, -2, 1f, 10, 0, 8, 0));
        r.addView(Ui.tv(this, Ui.size(size), 13, Ui.MUTED, true));
        usageBox.addView(r, Ui.lp(this, -1, -2, 0f, 0, 5, 0, 5));
    }

    private void showUsage(Store.Usage u) {
        usageBox.removeAllViews();
        usageBox.addView(Ui.tv(this, "Total data on this phone", 12, Ui.MUTED, false));
        usageBox.addView(Ui.tv(this, Ui.size(u.total), 28, Ui.FG, true));
        long[] v = {u.sessions, u.history, u.ext, u.cache, u.other};
        int[] col = {Ui.ACCENT, Ui.ACCENT_B, Ui.GREEN, Ui.WARN, 0xFF5A5A72};
        String[] nm = {"Accounts & saved logins", "Browsing history", "Extensions",
                "Cache (safe to clear)", "Other (browser engine, system)"};
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(Ui.rounded(this, Ui.BORDER, 4));
        bar.setClipToOutline(true);
        for (int i = 0; i < v.length; i++) {
            if (v[i] <= 0) continue;
            View seg = new View(this);
            seg.setBackgroundColor(col[i]);
            bar.addView(seg, new LinearLayout.LayoutParams(0, dp(8), (float) v[i]));
        }
        usageBox.addView(bar, Ui.lp(this, -1, dp(8), 0f, 0, 10, 0, 8));
        for (int i = 0; i < v.length; i++) legend(col[i], nm[i], v[i]);
        View line = new View(this);
        line.setBackgroundColor(Ui.BORDER);
        usageBox.addView(line, Ui.lp(this, -1, 1, 0f, 0, 8, 0, 8));
        usageBox.addView(Ui.tv(this, "App file (APK): " + Ui.size(u.apk), 12, Ui.MUTED, false));
        if (!u.names.isEmpty()) {
            TextView h = Ui.tv(this, "PER ACCOUNT", 11, Ui.MUTED, true);
            h.setLetterSpacing(0.12f);
            usageBox.addView(h, Ui.lp(this, -2, -2, 0f, 0, 14, 0, 4));
            for (int i = 0; i < u.names.size(); i++) {
                LinearLayout r = new LinearLayout(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                TextView n = Ui.tv(this, u.names.get(i), 13, Ui.FG, false);
                n.setSingleLine(true);
                n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
                r.addView(Ui.tv(this, Ui.size(u.sizes.get(i)), 13, Ui.MUTED, true));
                usageBox.addView(r, Ui.lp(this, -1, -2, 0f, 0, 4, 0, 4));
            }
        }
        TextView note = Ui.tv(this, "Saved logins are stored compressed. While a browser is open, its live data shows under \"Other\". Phone App info also counts the app file.",
                11, Ui.MUTED, false);
        note.setLineSpacing(0, 1.15f);
        usageBox.addView(note, Ui.lp(this, -1, -2, 0f, 0, 12, 0, 0));
    }

    private void clearCache() {
        final boolean web = !Store.browserRunning(this) && sp.getString("pending_id", null) == null;
        new Thread(() -> {
            final long freed = Store.clearCache(this, web);
            runOnUiThread(() -> {
                Toast.makeText(this, "Cleared " + Ui.size(freed), Toast.LENGTH_SHORT).show();
                refreshSizes();
            });
        }).start();
    }

    private void eraseAll() {
        if (Store.browserRunning(this)) {
            Toast.makeText(this, "Close the browser first", Toast.LENGTH_SHORT).show();
            return;
        }
        Ui.confirm(this, "Erase everything?",
                "All accounts, saved logins and history will be deleted from this phone. This can't be undone.",
                "Erase all", true, () -> {
                    Store.eraseAll(this);
                    Toast.makeText(this, "All data erased", Toast.LENGTH_SHORT).show();
                    refreshSizes();
                });
    }

    // ---------- backup & sharing ----------
    private boolean ready() {
        if (Store.browserRunning(this)) {
            Toast.makeText(this, "Close the browser first", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void fail(Dialog loading, Exception e) {
        runOnUiThread(() -> {
            if (loading != null) loading.dismiss();
            Ui.message(this, "Something went wrong", e.getMessage() == null ? e.toString() : e.getMessage());
        });
    }

    private void shareBackup() {
        if (!ready()) return;
        if (Store.accounts(this).isEmpty()) {
            Toast.makeText(this, "No accounts to back up yet", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText p1 = Ui.field(this, "Password");
        final EditText p2 = Ui.field(this, "Repeat password");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p1, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 10));
        box.addView(p2);
        Ui.dialog(this, "Protect your backup",
                "Anyone who has this file can use your saved logins. Choose a password and remember it \u2014 it can't be recovered.",
                box, "Cancel", "Create backup", false, () -> {
                    String a = p1.getText().toString(), c = p2.getText().toString();
                    if (a.length() < 4) {
                        p1.setError("Use at least 4 characters");
                        return false;
                    }
                    if (!a.equals(c)) {
                        p2.setError("Passwords don't match");
                        return false;
                    }
                    doExport(a);
                    return true;
                });
    }

    private void doExport(final String pw) {
        final Dialog ld = Ui.loading(this, "Preparing backup\u2026");
        new Thread(() -> {
            try {
                Store.finishPending(this);
                Store.deleteRec(ShareProvider.dir(this));
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
                File out = new File(ShareProvider.dir(this), "Accounts-backup-" + stamp + ".accbak");
                OutputStream o = new FileOutputStream(out);
                Store.exportBackup(this, o, pw);
                runOnUiThread(() -> {
                    ld.dismiss();
                    share(out, "application/octet-stream", "Share backup");
                });
            } catch (Exception e) {
                fail(ld, e);
            }
        }).start();
    }

    private void shareApp() {
        Ui.dialog(this, "Share this app",
                "This sends the app file (APK). The other person must allow \"Install unknown apps\" for the app they open it with. Send a backup too if you want them to get your accounts.",
                null, "Cancel", "Share", false, () -> {
                    final Dialog ld = Ui.loading(this, "Preparing app file\u2026");
                    new Thread(() -> {
                        try {
                            Store.deleteRec(ShareProvider.dir(this));
                            File out = new File(ShareProvider.dir(this), "Accounts.apk");
                            InputStream in = new FileInputStream(getApplicationInfo().sourceDir);
                            OutputStream o = new FileOutputStream(out);
                            try {
                                Store.copy(in, o);
                            } finally {
                                in.close();
                                o.close();
                            }
                            runOnUiThread(() -> {
                                ld.dismiss();
                                share(out, "application/vnd.android.package-archive", "Share app");
                            });
                        } catch (Exception e) {
                            fail(ld, e);
                        }
                    }).start();
                    return true;
                });
    }

    private void share(File f, String mime, String chooserTitle) {
        Uri u = ShareProvider.uri(this, f.getName());
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType(mime);
        s.putExtra(Intent.EXTRA_STREAM, u);
        s.setClipData(ClipData.newRawUri("file", u));
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, chooserTitle));
    }

    private void pickBackup() {
        if (!ready()) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            boolean enc = false;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                enc = Store.isEncrypted(in);
            } catch (Exception e) {
                fail(null, e);
                return;
            }
            final boolean fe = enc;
            runOnUiThread(() -> {
                if (fe) askPassword(uri);
                else runImport(uri, null);
            });
        }).start();
    }

    private void askPassword(final Uri uri) {
        final EditText p = Ui.field(this, "Password");
        p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Ui.dialog(this, "Enter backup password", null, p, "Cancel", "Restore", false, () -> {
            String pw = p.getText().toString();
            if (pw.isEmpty()) {
                p.setError("Enter the password");
                return false;
            }
            runImport(uri, pw);
            return true;
        });
    }

    private void runImport(final Uri uri, final String pw) {
        final Dialog ld = Ui.loading(this, "Restoring\u2026");
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Store.finishPending(this);
                final int n = Store.importBackup(this, in, pw);
                runOnUiThread(() -> {
                    ld.dismiss();
                    Ui.message(this, "Backup restored",
                            n + (n == 1 ? " account was" : " accounts were") + " added to this phone.");
                    refreshSizes();
                });
            } catch (Exception e) {
                fail(ld, e);
            }
        }).start();
    }
}
