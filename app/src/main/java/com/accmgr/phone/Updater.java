package com.accmgr.phone;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

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

/** Optional in-app updates from GitHub Releases (repo + token set in Settings). */
final class Updater {
    private Updater() {}

    interface Cb { void result(String tag, String assetUrl, String message); }
    interface Msg { void say(String message); }

    private static volatile boolean busy = false;

    static long installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Store.copy(in, b);
        in.close();
        return b.toByteArray();
    }

    /** Runs on a background thread; the callback is invoked from that thread. */
    static void check(Context c, final boolean manual, final Cb cb) {
        final SharedPreferences sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        final String repo = sp.getString("gh_repo", "").trim();
        final String tk = sp.getString("gh_token", "").trim();
        final long installed = installedCode(c);
        if (repo.isEmpty() || tk.isEmpty()) {
            if (manual) cb.result(null, null, "Add your GitHub repo and token first.");
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(20000);
                h.setRequestProperty("Authorization", "Bearer " + tk);
                h.setRequestProperty("Accept", "application/vnd.github+json");
                h.setRequestProperty("User-Agent", "Accounts");
                int code = h.getResponseCode();
                if (code == 404 || code == 401 || code == 403) {
                    if (manual) cb.result(null, null, "Update check failed (" + code + "). Check repo and token.");
                    return;
                }
                if (code != 200) throw new IOException("GitHub " + code);
                JSONObject j = new JSONObject(new String(readAll(h.getInputStream()), StandardCharsets.UTF_8));
                String tag = j.optString("tag_name");
                int tagNum = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
                String assetUrl = null;
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) {
                    for (int k = 0; k < assets.length(); k++) {
                        JSONObject a = assets.getJSONObject(k);
                        if (a.optString("name").endsWith(".apk")) {
                            assetUrl = a.optString("url");
                            break;
                        }
                    }
                }
                if (assetUrl != null && tagNum > installed) {
                    cb.result(tag, assetUrl, "Update " + tag + " is ready.");
                } else if (manual) {
                    cb.result(null, null, "You have the latest version.");
                }
            } catch (Exception e) {
                if (manual) cb.result(null, null, "Update check error: " + e.getMessage());
            }
        }).start();
    }

    private static byte[] download(String assetUrl, String tk) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(assetUrl).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + tk);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setRequestProperty("User-Agent", "Accounts");
        int code = c.getResponseCode();
        if (code >= 300 && code < 400) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            HttpURLConnection d = (HttpURLConnection) new URL(loc).openConnection();
            d.setConnectTimeout(15000);
            d.setReadTimeout(120000);
            if (d.getResponseCode() != 200) throw new IOException("Download " + d.getResponseCode());
            return readAll(d.getInputStream());
        }
        if (code != 200) throw new IOException("Download " + code);
        return readAll(c.getInputStream());
    }

    static void install(final Activity a, final String assetUrl, final Msg msg) {
        if (assetUrl == null || busy) return;
        final SharedPreferences sp = a.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (sp.getString("pending_id", null) != null) {
            msg.say("Let the last session finish saving first, then update.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            msg.say("Allow \"Install unknown apps\" for Accounts, then tap again.");
            a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + a.getPackageName())));
            return;
        }
        final String tk = sp.getString("gh_token", "").trim();
        busy = true;
        msg.say("Downloading update\u2026");
        new Thread(() -> {
            try {
                byte[] apk = download(assetUrl, tk);
                File dir = new File(a.getFilesDir(), "update");
                dir.mkdirs();
                File f = new File(dir, "update.apk");
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(apk);
                fo.close();
                installApk(a, f);
                msg.say("Tap \"Update\" in the system dialog to finish.");
            } catch (Exception ex) {
                msg.say("Update error: " + ex.getMessage());
            }
            busy = false;
        }).start();
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
            it.setAction("com.accmgr.phone.INSTALL_RESULT");
            PendingIntent pend = PendingIntent.getBroadcast(c, id, it,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } finally {
            s.close();
        }
    }
}
