package com.accmgr.phone;

import android.content.Context;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-account "extensions": JavaScript user scripts that run only inside that account. */
final class Extensions {
    private Extensions() {}

    static List<JSONObject> list(Context c, String acc) {
        List<JSONObject> out = new ArrayList<>();
        try {
            File f = Store.extFile(c, acc);
            if (f.exists()) {
                JSONArray a = new JSONArray(Store.readText(f));
                for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
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

    static void add(Context c, String acc, String name, String match, String code) throws Exception {
        List<JSONObject> l = list(c, acc);
        JSONObject o = new JSONObject();
        o.put("id", "e" + Long.toHexString(System.currentTimeMillis()));
        o.put("name", name);
        o.put("match", match);
        o.put("code", code);
        o.put("on", true);
        l.add(o);
        save(c, acc, l);
    }

    static void remove(Context c, String acc, String id) throws Exception {
        List<JSONObject> l = list(c, acc);
        List<JSONObject> keep = new ArrayList<>();
        for (JSONObject o : l) if (!id.equals(o.optString("id"))) keep.add(o);
        save(c, acc, keep);
    }

    static void setOn(Context c, String acc, String id, boolean on) throws Exception {
        List<JSONObject> l = list(c, acc);
        for (JSONObject o : l) if (id.equals(o.optString("id"))) o.put("on", on);
        save(c, acc, l);
    }

    /** Reads "// @name" and "// @match / @include" lines from a user-script header. Returns {name, matches}. */
    static String[] meta(String code) {
        String name = "", match = "";
        int s = code.indexOf("==UserScript==");
        int e = code.indexOf("==/UserScript==");
        if (s >= 0 && e > s) {
            Matcher m = Pattern.compile("//\\s*@([A-Za-z\\-]+)\\s+(.+)").matcher(code.substring(s, e));
            while (m.find()) {
                String k = m.group(1), v = m.group(2).trim();
                if (k.equals("name") && name.isEmpty()) name = v;
                else if (k.equals("match") || k.equals("include")) match += (match.isEmpty() ? "" : "\n") + v;
            }
        }
        return new String[]{name, match};
    }

    static boolean matches(String url, String patterns) {
        if (patterns == null || patterns.trim().isEmpty()) return true;
        for (String p : patterns.split("[\\n,]")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if (p.equals("*") || p.equals("<all_urls>")) return true;
            if (!p.contains("*") && !p.contains("://")) {
                if (url.toLowerCase().contains(p.toLowerCase())) return true;
                continue;
            }
            StringBuilder re = new StringBuilder();
            for (char ch : p.toCharArray()) {
                if (ch == '*') re.append(".*");
                else re.append(Pattern.quote(String.valueOf(ch)));
            }
            try {
                if (Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE).matcher(url).matches()) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** Runs every enabled script of this account whose pattern matches the page. UI thread only. */
    static void run(Context c, String acc, WebView w, String url) {
        if (acc == null || url == null) return;
        for (JSONObject o : list(c, acc)) {
            if (!o.optBoolean("on", true) || !matches(url, o.optString("match"))) continue;
            String id = o.optString("id");
            String js = "(function(){if(window.__ae_" + id + ")return;window.__ae_" + id + "=1;try{"
                    + "var unsafeWindow=window;"
                    + "function GM_addStyle(css){var s=document.createElement('style');s.textContent=css;"
                    + "(document.head||document.documentElement).appendChild(s);return s;}"
                    + "function GM_log(){console.log.apply(console,arguments);}\n"
                    + o.optString("code")
                    + "\n}catch(e){console.log('[Accounts extension] '+e);}})();";
            w.evaluateJavascript(js, null);
        }
    }
}
