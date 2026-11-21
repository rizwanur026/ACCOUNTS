package com.accmgr.phone;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Automatic updates from the public GitHub Releases of this project. No repo or token to type. */
final class Updater {
    private Updater() {}

    interface Cb { void result(String tag, String assetUrl, String message); }

    private static volatile boolean busy = false;

    static boolean enabled() { return !BuildConfig.GH_REPO.isEmpty(); }

    static long installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Checks GitHub in the background. The callback runs on that background thread. */
    static void check(Context c, final boolean manual, final Cb cb) {
        final long installed = installedCode(c);
        if (!enabled()) {
            if (manual) cb.result(null, null, "Updates are not set up in this build.");
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/" + BuildConfig.GH_REPO + "/releases/latest").openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(20000);
                h.setRequestProperty("Accept", "application/vnd.github+json");
                h.setRequestProperty("User-Agent", "Accounts");
                int code = h.getResponseCode();
                if (code == 404) {
                    if (manual) cb.result(null, null, "No update found. The GitHub repo must be public.");
                    return;
                }
                if (code != 200) throw new IOException("GitHub " + code);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                Store.copy(h.getInputStream(), b);
                JSONObject j = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
                String tag = j.optString("tag_name");
                int tagNum = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
                String assetUrl = null;
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) {
                    for (int k = 0; k < assets.length(); k++) {
                        JSONObject a = assets.getJSONObject(k);
                        if (a.optString("name").endsWith(".apk")) {
                            assetUrl = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
                if (assetUrl != null && tagNum > installed) cb.result(tag, assetUrl, "Update " + tag + " is ready.");
                else if (manual) cb.result(null, null, "You have the latest version.");
            } catch (Exception e) {
                if (manual) cb.result(null, null, "Couldn't check for updates: " + e.getMessage());
            }
        }).start();
    }

    /** Pop-up: "Update available" with Update now / Later. */
    static void prompt(final Activity a, String tag, final String assetUrl) {
        Ui.dialog(a, "Update available",
                "Version " + tag + " is ready. Accounts will download it and ask you to confirm the install. Your accounts and logins stay as they are.",
                null, "Later", "Update now", false, () -> {
                    install(a, assetUrl);
                    return true;
                });
    }

    static void install(final Activity a, final String assetUrl) {
        if (assetUrl == null || busy) return;
        SharedPreferences sp = a.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (sp.getString("pending_id", null) != null || Store.browserRunning(a)) {
            Ui.message(a, "Close the browser first", "Use \"Save & close\" in the open account, then update.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Ui.dialog(a, "Allow installing updates",
                    "Android needs your permission once. Tap Allow, switch on \"Allow from this source\", then come back and tap Update again.",
                    null, "Cancel", "Allow", false, () -> {
                        a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + a.getPackageName())));
                        return true;
                    });
            return;
        }
        busy = true;
        final Dialog ld = Ui.loading(a, "Downloading update\u2026 0%");
        final TextView label = ld.findViewById(android.R.id.text1);
        new Thread(() -> {
            try {
                File dir = new File(a.getFilesDir(), "update");
                dir.mkdirs();
                File f = new File(dir, "update.apk");
                download(assetUrl, f, pct -> a.runOnUiThread(() -> label.setText("Downloading update\u2026 " + pct + "%")));
                a.runOnUiThread(() -> label.setText("Installing\u2026"));
                installApk(a, f);
                a.runOnUiThread(ld::dismiss);
            } catch (final Exception ex) {
                a.runOnUiThread(() -> {
                    ld.dismiss();
                    Ui.message(a, "Update failed", String.valueOf(ex.getMessage()));
                });
            }
            busy = false;
        }).start();
    }

    private interface Progress { void pct(int p); }

    private static void download(String url, File out, Progress pr) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Accounts");
        if (c.getResponseCode() != 200) throw new IOException("Download " + c.getResponseCode());
        long total = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        FileOutputStream fo = new FileOutputStream(out);
        try {
            byte[] buf = new byte[32768];
            long got = 0;
            int n, last = -1;
            while ((n = in.read(buf)) != -1) {
                fo.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    int p = (int) (got * 100 / total);
                    if (p != last) {
                        last = p;
                        pr.pct(p);
                    }
                }
            }
        } finally {
            fo.close();
            in.close();
        }
    }

    private static void installApk(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream out = s.openWrite("app", 0, apk.length());
            FileInputStream in = new FileInputStream(apk);
            Store.copy(in, out);
            in.close();
            s.fsync(out);
            out.close();
            Intent it = new Intent(c, UpdateReceiver.class);
            it.setAction("com.accounts.vault.INSTALL_RESULT");
            PendingIntent pend = PendingIntent.getBroadcast(c, id, it,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } finally {
            s.close();
        }
    }
}
