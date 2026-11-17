package com.accmgr.phone;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Everything is stored privately on this phone: accounts, saved logins (web data), history. */
final class Store {
    private Store() {}

    private static final Object LOCK = new Object();
    private static final byte[] MAGIC = "ACCENC01".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    private static final Pattern NUM = Pattern.compile("\\d{1,15}");
    private static final int MAX_HISTORY = 500;

    // ---------- paths ----------
    static File sessions(Context c) {
        File f = new File(c.getFilesDir(), "sessions");
        f.mkdirs();
        return f;
    }

    static File histDir(Context c) {
        File f = new File(c.getFilesDir(), "history");
        f.mkdirs();
        return f;
    }

    static File sessionFile(Context c, String id) { return new File(sessions(c), id + ".zip"); }

    static File histFile(Context c, String id) { return new File(histDir(c), id + ".json"); }

    static File webDir(Context c) { return new File(c.getApplicationInfo().dataDir, "app_webview"); }

    static boolean validId(String s) { return s != null && ID.matcher(s).matches(); }

    // ---------- accounts ----------
    private static int cmpName(String a, String b) {
        boolean na = NUM.matcher(a.trim()).matches(), nb = NUM.matcher(b.trim()).matches();
        if (na && nb) return Long.compare(Long.parseLong(a.trim()), Long.parseLong(b.trim()));
        if (na) return -1;
        if (nb) return 1;
        return a.compareToIgnoreCase(b);
    }

    private static List<JSONObject> load(File f) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = new JSONArray(readText(f));
        for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        return out;
    }

    static List<JSONObject> accounts(Context c) {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            File f = new File(c.getFilesDir(), "accounts.json");
            File bak = new File(c.getFilesDir(), "accounts.bak");
            try {
                if (f.exists()) out = load(f);
                else if (bak.exists()) out = load(bak);
            } catch (Exception e) {
                try {
                    if (bak.exists()) out = load(bak);
                } catch (Exception ignored) {
                }
            }
            Collections.sort(out, (a, b) -> cmpName(a.optString("name"), b.optString("name")));
            return out;
        }
    }

    private static void saveAccounts(Context c, List<JSONObject> list) throws IOException {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        File f = new File(c.getFilesDir(), "accounts.json");
        File tmp = new File(c.getFilesDir(), "accounts.tmp");
        File bak = new File(c.getFilesDir(), "accounts.bak");
        writeText(tmp, arr.toString());
        if (f.exists()) {
            bak.delete();
            f.renameTo(bak);
        }
        if (!tmp.renameTo(f)) throw new IOException("Could not save accounts");
    }

    static JSONObject addAccount(Context c, String name, String email, String tag) throws Exception {
        synchronized (LOCK) {
            List<JSONObject> l = accounts(c);
            JSONObject o = new JSONObject();
            byte[] r = new byte[4];
            new SecureRandom().nextBytes(r);
            StringBuilder sb = new StringBuilder("a" + Long.toHexString(System.currentTimeMillis()));
            for (byte x : r) sb.append(String.format("%02x", x));
            o.put("id", sb.toString());
            o.put("name", name);
            o.put("email", email);
            o.put("tag", tag);
            o.put("created", System.currentTimeMillis());
            o.put("last_used", 0L);
            l.add(o);
            saveAccounts(c, l);
            return o;
        }
    }

    static void updateAccount(Context c, String id, String name, String email, String tag) throws Exception {
        synchronized (LOCK) {
            List<JSONObject> l = accounts(c);
            for (JSONObject o : l) {
                if (id.equals(o.optString("id"))) {
                    o.put("name", name);
                    o.put("email", email);
                    o.put("tag", tag);
                }
            }
            saveAccounts(c, l);
        }
    }

    static void touch(Context c, String id) {
        synchronized (LOCK) {
            try {
                List<JSONObject> l = accounts(c);
                for (JSONObject o : l) {
                    if (id.equals(o.optString("id"))) o.put("last_used", System.currentTimeMillis());
                }
                saveAccounts(c, l);
            } catch (Exception ignored) {
            }
        }
    }

    static void deleteAccount(Context c, String id) throws Exception {
        synchronized (LOCK) {
            List<JSONObject> l = accounts(c);
            List<JSONObject> keep = new ArrayList<>();
            for (JSONObject o : l) if (!id.equals(o.optString("id"))) keep.add(o);
            saveAccounts(c, keep);
            sessionFile(c, id).delete();
            histFile(c, id).delete();
        }
    }

    static boolean hasSession(Context c, String id) { return sessionFile(c, id).exists(); }

    static void eraseAll(Context c) {
        synchronized (LOCK) {
            new File(c.getFilesDir(), "accounts.json").delete();
            new File(c.getFilesDir(), "accounts.bak").delete();
            deleteRec(sessions(c));
            deleteRec(histDir(c));
            wipeWeb(c);
            c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                    .remove("pending_id").remove("pending_name").commit();
        }
    }

    // ---------- sessions (saved logins) ----------
    static boolean browserRunning(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> l = am.getRunningAppProcesses();
        if (l != null) {
            for (ActivityManager.RunningAppProcessInfo p : l) {
                if (p.processName != null && p.processName.endsWith(":browser")) return true;
            }
        }
        return false;
    }

    /** Puts this account's saved web data into the browser, replacing whatever was there. */
    static void prepareSession(Context c, String id) throws IOException {
        wipeWeb(c);
        File z = sessionFile(c, id);
        if (z.exists()) {
            InputStream in = new BufferedInputStream(new FileInputStream(z));
            try {
                unzipWeb(c, in);
            } finally {
                in.close();
            }
        }
    }

    /** Saves the browser's web data back into this account, then clears the browser. */
    static void saveSession(Context c, String id) throws IOException {
        File dir = webDir(c);
        if (dir.exists()) {
            File tmp = new File(sessions(c), id + ".tmp");
            ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            try {
                addFiles(z, dir, "app_webview");
            } finally {
                z.close();
            }
            File dst = sessionFile(c, id);
            dst.delete();
            if (!tmp.renameTo(dst)) throw new IOException("Could not save session");
        }
        wipeWeb(c);
    }

    /** If a browser session was left unsaved, save it now. Returns the saved account id or null. */
    static String finishPending(Context c) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        String id = sp.getString("pending_id", null);
        if (id == null) return null;
        for (int k = 0; k < 30 && browserRunning(c); k++) Thread.sleep(200);
        if (browserRunning(c)) throw new IOException("The browser is still open. Use its Close button first.");
        saveSession(c, id);
        sp.edit().remove("pending_id").remove("pending_name").commit();
        return id;
    }

    private static void addFiles(ZipOutputStream z, File f, String name) throws IOException {
        if (f.isDirectory()) {
            if (name.toLowerCase().contains("cache")) return; // never store cache
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) addFiles(z, k, name + "/" + k.getName());
        } else {
            if (name.contains("Singleton") || name.endsWith("/lockfile")) return;
            z.putNextEntry(new ZipEntry(name));
            FileInputStream in = new FileInputStream(f);
            try {
                copy(in, z);
            } finally {
                in.close();
            }
            z.closeEntry();
        }
    }

    private static void unzipWeb(Context c, InputStream raw) throws IOException {
        File base = new File(c.getApplicationInfo().dataDir);
        String allowed = new File(base, "app_webview").getCanonicalPath();
        ZipInputStream z = new ZipInputStream(raw);
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            File out = new File(base, e.getName());
            String p = out.getCanonicalPath();
            if (!p.equals(allowed) && !p.startsWith(allowed + File.separator)) {
                throw new IOException("Bad zip entry");
            }
            if (e.isDirectory()) {
                out.mkdirs();
            } else {
                out.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(out);
                try {
                    copy(z, fo);
                } finally {
                    fo.close();
                }
            }
        }
    }

    static void wipeWeb(Context c) {
        deleteRec(webDir(c));
        File[] cs = c.getCacheDir().listFiles();
        if (cs != null) for (File f : cs) deleteRec(f);
    }

    // ---------- cache ----------
    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long t = 0;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) t += sizeOf(x);
        return t;
    }

    private static List<File> webCaches(File dir) {
        List<File> out = new ArrayList<>();
        File[] k = dir.listFiles();
        if (k == null) return out;
        for (File f : k) {
            if (f.isDirectory()) {
                if (f.getName().toLowerCase().contains("cache")) out.add(f);
                else out.addAll(webCaches(f));
            }
        }
        return out;
    }

    static long cacheSize(Context c, boolean includeWeb) {
        long t = sizeOf(c.getCacheDir()) + sizeOf(new File(c.getFilesDir(), "update"));
        if (includeWeb) for (File f : webCaches(webDir(c))) t += sizeOf(f);
        return t;
    }

    static long clearCache(Context c, boolean includeWeb) {
        long before = cacheSize(c, includeWeb);
        File[] cs = c.getCacheDir().listFiles();
        if (cs != null) for (File f : cs) deleteRec(f);
        deleteRec(new File(c.getFilesDir(), "update"));
        if (includeWeb) for (File f : webCaches(webDir(c))) deleteRec(f);
        return Math.max(0, before - cacheSize(c, includeWeb));
    }

    static long totalDataSize(Context c) {
        return sizeOf(sessions(c)) + sizeOf(histDir(c));
    }

    // ---------- browsing history ----------
    static List<JSONObject> history(Context c, String id) {
        List<JSONObject> out = new ArrayList<>();
        try {
            File f = histFile(c, id);
            if (f.exists()) out = load(f);
        } catch (Exception ignored) {
        }
        return out;
    }

    static synchronized void addHistory(Context c, String id, String title, String url) {
        try {
            List<JSONObject> l = history(c, id);
            long now = System.currentTimeMillis();
            if (!l.isEmpty() && url.equals(l.get(0).optString("u"))) {
                JSONObject o = l.get(0);
                o.put("t", title == null ? "" : title);
                o.put("ts", now);
            } else {
                JSONObject o = new JSONObject();
                o.put("t", title == null ? "" : title);
                o.put("u", url);
                o.put("ts", now);
                l.add(0, o);
            }
            while (l.size() > MAX_HISTORY) l.remove(l.size() - 1);
            JSONArray arr = new JSONArray();
            for (JSONObject o : l) arr.put(o);
            writeText(histFile(c, id), arr.toString());
        } catch (Exception ignored) {
        }
    }

    static void clearHistory(Context c, String id) { histFile(c, id).delete(); }

    static void clearAllHistory(Context c) { deleteRec(histDir(c)); }

    // ---------- backup / restore ----------
    private static SecretKeySpec key(String pw, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 120000, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    static void exportBackup(Context c, OutputStream raw, String password) throws Exception {
        OutputStream out = new BufferedOutputStream(raw, 65536);
        if (password != null && !password.isEmpty()) {
            SecureRandom r = new SecureRandom();
            byte[] salt = new byte[16], iv = new byte[12];
            r.nextBytes(salt);
            r.nextBytes(iv);
            out.write(MAGIC);
            out.write(salt);
            out.write(iv);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
            out = new CipherOutputStream(out, ci);
        }
        ZipOutputStream z = new ZipOutputStream(out);
        try {
            JSONObject meta = new JSONObject();
            meta.put("app", "Accounts");
            meta.put("created", System.currentTimeMillis());
            putText(z, "meta.json", meta.toString());
            List<JSONObject> accs = accounts(c);
            JSONArray arr = new JSONArray();
            for (JSONObject a : accs) arr.put(a);
            putText(z, "accounts.json", arr.toString());
            for (JSONObject a : accs) {
                String id = a.optString("id");
                if (!validId(id)) continue;
                putFile(z, "sessions/" + id + ".zip", sessionFile(c, id));
                putFile(z, "history/" + id + ".json", histFile(c, id));
            }
        } finally {
            z.close();
        }
    }

    static boolean isEncrypted(InputStream in) throws IOException {
        byte[] b = new byte[8];
        int n = 0;
        while (n < 8) {
            int r = in.read(b, n, 8 - n);
            if (r < 0) break;
            n += r;
        }
        return n == 8 && Arrays.equals(b, MAGIC);
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new IOException("Damaged backup file");
            got += r;
        }
        return b;
    }

    private static boolean allowedEntry(String n) {
        return n.equals("accounts.json") || n.equals("meta.json")
                || n.matches("sessions/[A-Za-z0-9_\\-]{1,64}\\.zip")
                || n.matches("history/[A-Za-z0-9_\\-]{1,64}\\.json");
    }

    /** Returns how many accounts were restored. */
    static int importBackup(Context c, InputStream raw, String password) throws Exception {
        BufferedInputStream in = new BufferedInputStream(raw, 65536);
        in.mark(16);
        boolean enc = isEncrypted(in);
        InputStream src = in;
        if (enc) {
            if (password == null || password.isEmpty()) throw new IOException("This backup needs a password");
            byte[] salt = readN(in, 16), iv = readN(in, 12);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
            src = new CipherInputStream(in, ci);
        } else {
            in.reset();
        }
        File tmp = new File(c.getCacheDir(), "imp_" + System.currentTimeMillis());
        tmp.mkdirs();
        try {
            String base = tmp.getCanonicalPath();
            try {
                ZipInputStream z = new ZipInputStream(src);
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String name = e.getName();
                    if (e.isDirectory() || !allowedEntry(name)) continue;
                    File out = new File(tmp, name);
                    if (!out.getCanonicalPath().startsWith(base + File.separator)) continue;
                    out.getParentFile().mkdirs();
                    FileOutputStream fo = new FileOutputStream(out);
                    try {
                        copy(z, fo);
                    } finally {
                        fo.close();
                    }
                }
            } catch (IOException | RuntimeException ex) {
                if (enc) throw new IOException("Wrong password or damaged backup file");
                throw ex;
            }
            File af = new File(tmp, "accounts.json");
            if (!af.exists()) {
                throw new IOException(enc ? "Wrong password or damaged backup file"
                        : "This is not an Accounts backup");
            }
            JSONArray arr = new JSONArray(readText(af));
            int count = 0;
            synchronized (LOCK) {
                List<JSONObject> cur = accounts(c);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.optString("id");
                    String name = o.optString("name");
                    if (!validId(id) || name.isEmpty()) continue;
                    move(new File(tmp, "sessions/" + id + ".zip"), sessionFile(c, id));
                    move(new File(tmp, "history/" + id + ".json"), histFile(c, id));
                    JSONObject n = new JSONObject();
                    n.put("id", id);
                    n.put("name", name);
                    n.put("email", o.optString("email"));
                    n.put("tag", o.optString("tag"));
                    n.put("created", o.optLong("created", System.currentTimeMillis()));
                    n.put("last_used", o.optLong("last_used", 0));
                    for (int k = cur.size() - 1; k >= 0; k--) {
                        if (id.equals(cur.get(k).optString("id"))) cur.remove(k);
                    }
                    cur.add(n);
                    count++;
                }
                saveAccounts(c, cur);
            }
            return count;
        } finally {
            deleteRec(tmp);
        }
    }

    // ---------- file helpers ----------
    private static void putText(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void putFile(ZipOutputStream z, String name, File f) throws IOException {
        if (!f.exists()) return;
        z.putNextEntry(new ZipEntry(name));
        FileInputStream in = new FileInputStream(f);
        try {
            copy(in, z);
        } finally {
            in.close();
        }
        z.closeEntry();
    }

    private static void move(File src, File dst) throws IOException {
        if (!src.exists()) return;
        dst.getParentFile().mkdirs();
        dst.delete();
        if (src.renameTo(dst)) return;
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            copy(in, out);
        } finally {
            in.close();
            out.close();
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    static String readText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    static void writeText(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            o.close();
        }
    }

    static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
