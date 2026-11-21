package com.accmgr.phone;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Per-account Chrome extensions. Android's browser view can't run full extensions, so this loads the
 * "content scripts" (the part that changes web pages) of an extension from the Chrome Web Store
 * and injects them only into the account they were added to.
 */
final class Extensions {
    private Extensions() {}

    private static final Pattern STORE = Pattern.compile(
            "^https://(?:chromewebstore\\.google\\.com|chrome\\.google\\.com)/(?:webstore/)?detail/(?:[^/?#]+/)?([a-p]{32})");
    private static final int MAX_DOWNLOAD = 40 * 1024 * 1024;
    private static final int MAX_CODE = 6 * 1000 * 1000;

    static String storeId(String url) {
        if (url == null) return null;
        Matcher m = STORE.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    static boolean isStoreHost(String url) {
        return url != null && (url.startsWith("https://chromewebstore.google.com/")
                || url.startsWith("https://chrome.google.com/webstore"));
    }

    // ---------- saved extensions ----------
    static List<JSONObject> list(Context c, String acc) {
        List<JSONObject> out = new ArrayList<>();
        try {
            File f = Store.extFile(c, acc);
            if (f.exists()) {
                JSONArray a = new JSONArray(Store.readText(f));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    if (o.has("scripts")) out.add(o);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, String acc, List<JSONObject> l) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        Store.writeText(Store.extFile(c, acc), a.toString());
    }

    static void remove(Context c, String acc, String id) throws Exception {
        List<JSONObject> keep = new ArrayList<>();
        for (JSONObject o : list(c, acc)) if (!id.equals(o.optString("id"))) keep.add(o);
        save(c, acc, keep);
    }

    static void setOn(Context c, String acc, String id, boolean on) throws Exception {
        List<JSONObject> l = list(c, acc);
        for (JSONObject o : l) if (id.equals(o.optString("id"))) o.put("on", on);
        save(c, acc, l);
    }

    // ---------- install from the Chrome Web Store ----------
    /** Downloads and adds the extension. Throws an Exception with a readable message if it can't work here. */
    static String install(Context c, String acc, String id, String fallbackName) throws Exception {
        Map<String, byte[]> files = unpack(download(id));
        byte[] mb = files.get("manifest.json");
        if (mb == null) throw new Exception("This download doesn't look like an extension.");
        String mt = new String(mb, StandardCharsets.UTF_8);
        if (mt.startsWith("\uFEFF")) mt = mt.substring(1);
        JSONObject mf = new JSONObject(mt);

        JSONArray perms = mf.optJSONArray("permissions");
        if (perms != null) {
            for (int i = 0; i < perms.length(); i++) {
                if ("proxy".equals(perms.optString(i))) {
                    throw new Exception("This extension is a VPN / proxy tool. Android browsers don't allow that, so it can't work here. A VPN app on the phone covers every account instead.");
                }
            }
        }

        JSONArray cs = mf.optJSONArray("content_scripts");
        JSONArray scripts = new JSONArray();
        long size = 0;
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject s = cs.getJSONObject(i);
                StringBuilder js = new StringBuilder(), css = new StringBuilder();
                JSONArray jl = s.optJSONArray("js"), cl = s.optJSONArray("css");
                if (jl != null) for (int k = 0; k < jl.length(); k++) js.append(file(files, jl.getString(k))).append("\n;\n");
                if (cl != null) for (int k = 0; k < cl.length(); k++) css.append(file(files, cl.getString(k))).append("\n");
                if (js.length() == 0 && css.length() == 0) continue;
                size += js.length() + css.length();
                JSONObject o = new JSONObject();
                o.put("m", s.optJSONArray("matches") == null ? new JSONArray() : s.optJSONArray("matches"));
                o.put("x", s.optJSONArray("exclude_matches") == null ? new JSONArray() : s.optJSONArray("exclude_matches"));
                o.put("js", js.toString());
                o.put("css", css.toString());
                scripts.put(o);
            }
        }
        if (scripts.length() == 0) {
            throw new Exception("This extension has no page scripts, so it can't do anything here. Extensions that only use a toolbar button or background features can't run on Android.");
        }
        if (size > MAX_CODE) throw new Exception("This extension is too large to add.");

        String name = resolve(mf.optString("name"), files, mf).trim();
        if (name.isEmpty()) name = fallbackName == null || fallbackName.isEmpty() ? "Extension" : fallbackName;
        JSONObject ext = new JSONObject();
        ext.put("id", id);
        ext.put("name", name);
        ext.put("version", mf.optString("version"));
        ext.put("on", true);
        ext.put("scripts", scripts);

        List<JSONObject> l = list(c, acc);
        for (int i = l.size() - 1; i >= 0; i--) if (id.equals(l.get(i).optString("id"))) l.remove(i);
        l.add(ext);
        save(c, acc, l);
        return "\"" + name + "\" was added to this account. It runs on the sites it supports.";
    }

    private static String file(Map<String, byte[]> files, String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        byte[] b = files.get(p);
        if (b == null) return "";
        String t = new String(b, StandardCharsets.UTF_8);
        return t.startsWith("\uFEFF") ? t.substring(1) : t;
    }

    private static String resolve(String v, Map<String, byte[]> files, JSONObject mf) {
        if (v == null) return "";
        if (!(v.startsWith("__MSG_") && v.endsWith("__"))) return v;
        String key = v.substring(6, v.length() - 2).toLowerCase();
        String[] locales = {mf.optString("default_locale", "en"), "en", "en_US"};
        for (String l : locales) {
            byte[] b = files.get("_locales/" + l + "/messages.json");
            if (b == null) continue;
            try {
                String t = new String(b, StandardCharsets.UTF_8);
                if (t.startsWith("\uFEFF")) t = t.substring(1);
                JSONObject j = new JSONObject(t);
                Iterator<String> it = j.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.toLowerCase().equals(key)) return j.getJSONObject(k).optString("message");
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static byte[] download(String id) throws Exception {
        String u = "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=130.0.0.0"
                + "&acceptformat=crx2,crx3&x=id%3D" + id + "%26installsource%3Dondemand%26uc";
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(15000);
        h.setReadTimeout(60000);
        h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36");
        int code = h.getResponseCode();
        if (code == 204 || code == 404) throw new Exception("This extension isn't available to download.");
        if (code != 200) throw new Exception("Download failed (HTTP " + code + ").");
        InputStream in = h.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            b.write(buf, 0, n);
            if (b.size() > MAX_DOWNLOAD) throw new Exception("This extension is too large to add.");
        }
        in.close();
        return b.toByteArray();
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    private static Map<String, byte[]> unpack(byte[] crx) throws Exception {
        int off = 0;
        if (crx.length > 16 && crx[0] == 'C' && crx[1] == 'r' && crx[2] == '2' && crx[3] == '4') {
            int ver = le32(crx, 4);
            if (ver == 3) off = 12 + le32(crx, 8);
            else if (ver == 2) off = 16 + le32(crx, 8) + le32(crx, 12);
            else throw new Exception("Unsupported extension format.");
        }
        if (off < 0 || off >= crx.length) throw new Exception("Damaged extension file.");
        Map<String, byte[]> out = new HashMap<>();
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(crx, off, crx.length - off));
        ZipEntry e;
        long total = 0;
        while ((e = z.getNextEntry()) != null) {
            String n = e.getName();
            if (e.isDirectory() || !(n.endsWith(".js") || n.endsWith(".css") || n.endsWith(".json"))) continue;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = z.read(buf)) != -1) {
                b.write(buf, 0, r);
                total += r;
                if (total > 30L * 1024 * 1024) throw new Exception("This extension is too large to add.");
            }
            out.put(n, b.toByteArray());
        }
        return out;
    }

    // ---------- matching (Chrome match patterns) ----------
    private static boolean glob(String g, String s) {
        StringBuilder re = new StringBuilder();
        for (char ch : g.toCharArray()) {
            if (ch == '*') re.append(".*");
            else re.append(Pattern.quote(String.valueOf(ch)));
        }
        try {
            return Pattern.compile(re.toString(), Pattern.DOTALL).matcher(s).matches();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean matchPattern(String url, String p) {
        if (p.equals("<all_urls>")) return url.startsWith("http://") || url.startsWith("https://");
        Matcher m = Pattern.compile("^(\\*|https?)://([^/]*)(/.*)$").matcher(p);
        if (!m.find()) return false;
        Uri u = Uri.parse(url);
        String us = u.getScheme();
        if (us == null) return false;
        if (m.group(1).equals("*")) {
            if (!(us.equals("http") || us.equals("https"))) return false;
        } else if (!m.group(1).equals(us)) {
            return false;
        }
        String uh = u.getHost() == null ? "" : u.getHost().toLowerCase();
        String host = m.group(2).toLowerCase();
        if (!host.equals("*")) {
            if (host.startsWith("*.")) {
                String base = host.substring(2);
                if (!(uh.equals(base) || uh.endsWith("." + base))) return false;
            } else if (!host.equals(uh)) {
                return false;
            }
        }
        String path = (u.getEncodedPath() == null ? "/" : u.getEncodedPath())
                + (u.getEncodedQuery() == null ? "" : "?" + u.getEncodedQuery());
        return glob(m.group(3), path);
    }

    private static boolean matchAny(String url, JSONArray pats) {
        if (pats == null) return false;
        for (int i = 0; i < pats.length(); i++) if (matchPattern(url, pats.optString(i))) return true;
        return false;
    }

    // ---------- injection ----------
    private static final String SHIM = "var chrome=(function(){var P='__ae_%ID%:';"
            + "function rd(){try{return JSON.parse(localStorage.getItem(P)||'{}');}catch(e){return {};}}"
            + "function wr(o){try{localStorage.setItem(P,JSON.stringify(o));}catch(e){}}"
            + "function area(){return {get:function(k,cb){var d=rd(),r={};if(k==null){r=d;}else if(typeof k==='string'){if(k in d)r[k]=d[k];}"
            + "else if(Array.isArray(k)){k.forEach(function(x){if(x in d)r[x]=d[x];});}else{for(var x in k){r[x]=(x in d)?d[x]:k[x];}}"
            + "if(cb){cb(r);return;}return Promise.resolve(r);},"
            + "set:function(o,cb){var d=rd();for(var x in o)d[x]=o[x];wr(d);if(cb){cb();return;}return Promise.resolve();},"
            + "remove:function(k,cb){var d=rd();[].concat(k).forEach(function(x){delete d[x];});wr(d);if(cb){cb();return;}return Promise.resolve();},"
            + "clear:function(cb){wr({});if(cb){cb();return;}return Promise.resolve();}};}"
            + "var ev={addListener:function(){},removeListener:function(){},hasListener:function(){return false;}};"
            + "return {runtime:{id:'%ID%',getURL:function(p){return p;},getManifest:function(){return {};},"
            + "sendMessage:function(){return Promise.resolve();},onMessage:ev,onInstalled:ev,lastError:undefined},"
            + "storage:{local:area(),sync:area(),onChanged:ev},"
            + "i18n:{getMessage:function(k){return k;},getUILanguage:function(){return navigator.language;}},"
            + "extension:{getURL:function(p){return p;}}};})();var browser=chrome;";

    /** Runs this account's enabled extensions on the page. UI thread only. */
    static void run(WebView w, String url, List<JSONObject> exts) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        for (JSONObject e : exts) {
            if (!e.optBoolean("on", true)) continue;
            JSONArray sc = e.optJSONArray("scripts");
            if (sc == null) continue;
            String id = e.optString("id");
            for (int i = 0; i < sc.length(); i++) {
                JSONObject s = sc.optJSONObject(i);
                if (s == null || !matchAny(url, s.optJSONArray("m")) || matchAny(url, s.optJSONArray("x"))) continue;
                String css = s.optString("css"), js = s.optString("js");
                String flag = "__ae_" + id + "_" + i;
                StringBuilder b = new StringBuilder("(function(){if(window." + flag + ")return;window." + flag + "=1;");
                if (!css.isEmpty()) {
                    b.append("try{var st=document.createElement('style');st.textContent=")
                            .append(JSONObject.quote(css))
                            .append(";(document.head||document.documentElement).appendChild(st);}catch(e){}");
                }
                if (!js.isEmpty()) {
                    b.append("try{").append(SHIM.replace("%ID%", id)).append("\n").append(js)
                            .append("\n}catch(e){console.log('[Accounts extension] '+e);}");
                }
                b.append("})();");
                w.evaluateJavascript(b.toString(), null);
            }
        }
    }

    /** On Chrome Web Store pages: tapping the page's "Add" button adds the extension to this account. */
    static final String STORE_HOOK = "(function(){if(window.__accHook)return;window.__accHook=1;"
            + "document.addEventListener('click',function(e){var b=e.target&&e.target.closest?"
            + "e.target.closest('button,a,[role=button]'):null;if(!b)return;"
            + "var t=(b.innerText||b.textContent||'').toLowerCase();"
            + "if(/add(ed)? to (chrome|desktop)/.test(t)){e.preventDefault();e.stopPropagation();"
            + "var m=location.href.match(/[a-p]{32}/);if(m&&window.AccExt)AccExt.addExtension(m[0],document.title);}},true);})();";
}
