package com.accmgr.phone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private SharedPreferences sp;
    private TextView sub, linkBar, updateBar, pendingBar, emptyTitle, emptyText;
    private EditText search;
    private ListView list;
    private LinearLayout emptyBox;
    private BaseAdapter adapter;
    private List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private volatile boolean busy = false;
    private String pendingUrl, updAssetUrl;

    private int dp(int v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        // old shared backup files should never linger
        Store.deleteRec(ShareProvider.dir(this));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), 0);

        // ----- header -----
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        head.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.addView(Ui.tv(this, "Accounts", 26, Ui.FG, true));
        sub = Ui.tv(this, "", 12, Ui.MUTED, false);
        tcol.addView(sub);
        head.addView(tcol, Ui.lp(this, 0, -2, 1f, 14, 0, 8, 0));
        ImageView setBtn = Ui.iconButton(this, R.drawable.ic_settings, 44);
        head.addView(setBtn);
        root.addView(head, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 16));

        // ----- search -----
        LinearLayout sbox = new LinearLayout(this);
        sbox.setGravity(Gravity.CENTER_VERTICAL);
        sbox.setBackground(Ui.card(this, Ui.PANEL, 16));
        sbox.setPadding(dp(14), 0, dp(14), 0);
        ImageView si = Ui.icon(this, R.drawable.ic_search, Ui.MUTED, 20);
        sbox.addView(si);
        search = new EditText(this);
        search.setHint("Search accounts");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.FG);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(dp(10), dp(13), 0, dp(13));
        sbox.addView(search, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(sbox, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 12));

        // ----- banners -----
        linkBar = banner(Ui.ACCENT, Ui.ACCENT_B);
        updateBar = banner(Ui.ACCENT, Ui.ACCENT_B);
        pendingBar = banner(0xFFFFA63D, 0xFFFF7A5C);
        root.addView(linkBar, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 10));
        root.addView(updateBar, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 10));
        root.addView(pendingBar, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 10));
        linkBar.setOnClickListener(v -> {
            pendingUrl = null;
            linkBar.setVisibility(View.GONE);
        });
        updateBar.setOnClickListener(v -> Updater.install(this, updAssetUrl, this::info));
        pendingBar.setOnClickListener(v -> finishPending());

        // ----- list + empty state -----
        FrameLayout listWrap = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(10));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(110));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) { return rowView(shown.get(i)); }
        };
        list.setAdapter(adapter);
        listWrap.addView(list, new FrameLayout.LayoutParams(-1, -1));

        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(dp(30), 0, dp(30), dp(80));
        ImageView el = new ImageView(this);
        el.setImageResource(R.drawable.logo);
        el.setAlpha(0.95f);
        emptyBox.addView(el, new LinearLayout.LayoutParams(dp(84), dp(84)));
        emptyTitle = Ui.tv(this, "No accounts yet", 20, Ui.FG, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyBox.addView(emptyTitle, Ui.lp(this, -2, -2, 0f, 0, 18, 0, 6));
        emptyText = Ui.tv(this, "Create an account to keep its login and browsing data safe on this phone.",
                14, Ui.MUTED, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(0, 1.2f);
        emptyBox.addView(emptyText);
        listWrap.addView(emptyBox, new FrameLayout.LayoutParams(-1, -1));
        root.addView(listWrap, new LinearLayout.LayoutParams(-1, 0, 1f));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // ----- floating "New account" button -----
        LinearLayout fab = new LinearLayout(this);
        fab.setGravity(Gravity.CENTER_VERTICAL);
        fab.setBackground(Ui.ripple(this, Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_B, 28), 28));
        fab.setPadding(dp(18), dp(14), dp(22), dp(14));
        fab.setElevation(dp(8));
        fab.addView(Ui.icon(this, R.drawable.ic_add, 0xFFFFFFFF, 22));
        TextView ft = Ui.tv(this, "New account", 15, 0xFFFFFFFF, true);
        ft.setPadding(dp(8), 0, 0, 0);
        fab.addView(ft);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, dp(20), dp(24));
        frame.addView(fab, fp);
        setContentView(frame);

        fab.setOnClickListener(v -> accountForm(null));
        setBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < shown.size()) openAccount(shown.get(pos));
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < shown.size()) accountMenu(shown.get(pos));
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable e) { applyFilter(); }
        });

        handleIntent(getIntent());
        Updater.check(this, false, (tag, url, msg) -> runOnUiThread(() -> {
            if (tag != null) {
                updAssetUrl = url;
                updateBar.setText("Update " + tag + " is ready \u2014 tap to install");
                updateBar.setVisibility(View.VISIBLE);
            }
        }));
    }

    private TextView banner(int c1, int c2) {
        TextView t = new TextView(this);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setBackground(Ui.gradient(this, c1, c2, 14));
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setVisibility(View.GONE);
        return t;
    }

    private View rowView(JSONObject a) {
        final Context c = this;
        String id = a.optString("id");
        boolean saved = Store.hasSession(c, id);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.ripple(c, Ui.card(c, Ui.PANEL, 18), 18));
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        FrameLayout av = new FrameLayout(c);
        av.addView(Ui.avatar(c, a.optString("name"), id, 46), new FrameLayout.LayoutParams(dp(46), dp(46)));
        if (saved) {
            View dot = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Ui.GREEN);
            g.setStroke(dp(2), Ui.PANEL);
            dot.setBackground(g);
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM | Gravity.END);
            av.addView(dot, dl);
        }
        row.addView(av, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = Ui.tv(c, a.optString("name"), 16, Ui.FG, true);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(nm);
        String em = a.optString("email");
        TextView e2 = Ui.tv(c, em.isEmpty() ? "No email added" : em, 13, Ui.MUTED, false);
        e2.setSingleLine(true);
        e2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(e2);
        long lu = a.optLong("last_used", 0);
        String st = (saved ? "Login saved" : "Not signed in") + " \u00B7 "
                + (lu > 0 ? "used " + Ui.ago(lu) : "never opened");
        col.addView(Ui.tv(c, st, 11, saved ? Ui.GREEN : Ui.MUTED, false));
        row.addView(col, Ui.lp(c, 0, -2, 1f, 14, 0, 8, 0));

        String tag = a.optString("tag");
        if (!tag.isEmpty()) {
            TextView chip = Ui.tv(c, tag, 11, Ui.FG, true);
            chip.setBackground(Ui.card(c, Ui.PANEL2, 10));
            chip.setPadding(dp(10), dp(4), dp(10), dp(4));
            row.addView(chip);
        }
        return row;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    // A link from another app: pick an account and the link opens inside it
    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        String u = i.getData().toString();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return;
        if (Store.browserRunning(this)) {
            info("A browser is already open. Close it first, then open the link again.");
            return;
        }
        pendingUrl = u;
        linkBar.setText("Open this link with which account?\n" + u + "\nTap an account below. Tap here to cancel.");
        linkBar.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        if (sp.getString("pending_id", null) != null && !busy) finishPending();
    }

    // ---------- list ----------
    private void reload() {
        all = Store.accounts(this);
        applyFilter();
        if (!busy) resetInfo();
        updatePendingUi();
    }

    private void applyFilter() {
        String q = search.getText().toString().toLowerCase().trim();
        shown.clear();
        for (JSONObject a : all) {
            String blob = (a.optString("name") + " " + a.optString("email") + " " + a.optString("tag")).toLowerCase();
            if (q.isEmpty() || blob.contains(q)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
        boolean empty = shown.isEmpty();
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (all.isEmpty()) {
            emptyTitle.setText("No accounts yet");
            emptyText.setText("Create an account to keep its login and browsing data safe on this phone.");
        } else {
            emptyTitle.setText("No matches");
            emptyText.setText("Try a different name, email or label.");
        }
    }

    private void info(final String t) { runOnUiThread(() -> sub.setText(t)); }

    private void resetInfo() {
        int n = all.size();
        sub.setText(n == 0 ? "Private \u00B7 stored on this phone"
                : n + (n == 1 ? " account" : " accounts") + " \u00B7 stored on this phone");
    }

    private void updatePendingUi() {
        String n = sp.getString("pending_name", null);
        if (sp.getString("pending_id", null) != null) {
            pendingBar.setText("Unsaved session: " + n + ". Tap to save it now.");
            pendingBar.setVisibility(View.VISIBLE);
        } else {
            pendingBar.setVisibility(View.GONE);
        }
    }

    // ---------- account actions ----------
    private void accountMenu(final JSONObject a) {
        final String id = a.optString("id"), name = a.optString("name");
        Ui.menu(this, name, new String[]{"Open", "Edit details", "Browsing history", "Delete account"}, 3, i -> {
            if (i == 0) openAccount(a);
            else if (i == 1) accountForm(a);
            else if (i == 2) Ui.history(this, id, name, null);
            else deleteAccount(a);
        });
    }

    private void deleteAccount(final JSONObject a) {
        final String id = a.optString("id");
        if (id.equals(sp.getString("pending_id", null))) {
            info("Save the open session first.");
            return;
        }
        Ui.confirm(this, "Delete \"" + a.optString("name") + "\"?",
                "Its saved login and browsing history will be removed from this phone. This can't be undone.",
                "Delete", true, () -> {
                    try {
                        Store.deleteAccount(this, id);
                    } catch (Exception e) {
                        Toast.makeText(this, "Couldn't delete: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    reload();
                });
    }

    private void accountForm(final JSONObject existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText n = Ui.field(this, "Name  (e.g. Work, 05)");
        final EditText em = Ui.field(this, "Email (optional)");
        final EditText tg = Ui.field(this, "Label (optional)");
        em.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (existing != null) {
            n.setText(existing.optString("name"));
            em.setText(existing.optString("email"));
            tg.setText(existing.optString("tag"));
        }
        box.addView(n, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 10));
        box.addView(em, Ui.lp(this, -1, -2, 0f, 0, 0, 0, 10));
        box.addView(tg);
        Ui.dialog(this, existing == null ? "New account" : "Edit account",
                existing == null ? "Its login and browsing data will be stored only on this phone." : null,
                box, "Cancel", existing == null ? "Create" : "Save", false, () -> {
                    String name = n.getText().toString().trim();
                    if (name.isEmpty()) {
                        n.setError("Enter a name");
                        return false;
                    }
                    String email = em.getText().toString().trim();
                    String tag = tg.getText().toString().trim();
                    try {
                        if (existing == null) {
                            JSONObject o = Store.addAccount(this, name, email, tag);
                            reload();
                            openAccount(o);
                        } else {
                            Store.updateAccount(this, existing.optString("id"), name, email, tag);
                            reload();
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    return true;
                });
    }

    // ---------- open / close flow ----------
    private void openAccount(final JSONObject a) {
        if (busy) return;
        if (Store.browserRunning(this)) {
            info("A browser is already open");
            return;
        }
        final String id = a.optString("id"), name = a.optString("name");
        busy = true;
        info("Opening " + name + "\u2026");
        new Thread(() -> {
            try {
                if (sp.getString("pending_id", null) != null) Store.finishPending(this);
                Store.prepareSession(this, id);
                Store.touch(this, id);
                sp.edit().putString("pending_id", id).putString("pending_name", name).commit();
                runOnUiThread(() -> {
                    busy = false;
                    Intent it = new Intent(MainActivity.this, BrowserActivity.class);
                    it.putExtra("id", id);
                    it.putExtra("name", name);
                    it.putExtra("email", a.optString("email"));
                    it.putExtra("history", sp.getBoolean("save_history", true));
                    if (pendingUrl != null) {
                        it.putExtra("url", pendingUrl);
                        pendingUrl = null;
                        linkBar.setVisibility(View.GONE);
                    }
                    startActivity(it);
                });
            } catch (Exception ex) {
                busy = false;
                info("Couldn't open: " + ex.getMessage());
            }
        }).start();
    }

    private void finishPending() {
        if (sp.getString("pending_id", null) == null || busy) return;
        busy = true;
        info("Saving session on this phone\u2026");
        new Thread(() -> {
            String msg;
            try {
                Store.finishPending(this);
                msg = "Saved safely on this phone";
            } catch (Exception ex) {
                msg = "Couldn't save yet: " + ex.getMessage();
            }
            busy = false;
            final String m = msg;
            runOnUiThread(() -> {
                reload();
                sub.setText(m);
            });
        }).start();
    }
}
