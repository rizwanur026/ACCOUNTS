package com.accmgr.phone;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class Ui {
    private Ui() {}

    // Palette: deep midnight surfaces, violet -> blue accent
    static final int BG = 0xFF0A0A10, PANEL = 0xFF14141D, PANEL2 = 0xFF1C1C28, BORDER = 0xFF262636,
            FG = 0xFFF3F3F8, MUTED = 0xFF8C8CA6, ACCENT = 0xFF7B5CFF, ACCENT_B = 0xFF4C8DFF,
            GREEN = 0xFF34D399, DANGER = 0xFFFF5C7A, WARN = 0xFFFFB84D;

    private static final int[][] AVATARS = {
            {0xFF7B5CFF, 0xFF4C8DFF}, {0xFFFF6B9A, 0xFFFF9A5C}, {0xFF22C1A5, 0xFF3BA0FF},
            {0xFFB05CFF, 0xFFFF5CA8}, {0xFFFFB347, 0xFFFF6B5C}, {0xFF4CD0FF, 0xFF7B5CFF}};

    interface Ok { boolean run(); }
    interface Pick { void pick(int index); }
    interface UrlPick { void pick(String url); }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- drawables ----------
    static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable card(Context c, int color, int radiusDp) {
        GradientDrawable g = rounded(c, color, radiusDp);
        g.setStroke(Math.max(1, dp(c, 1)), BORDER);
        return g;
    }

    static GradientDrawable gradient(Context c, int a, int b, int radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static Drawable ripple(Context c, Drawable content, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), content,
                rounded(c, 0xFFFFFFFF, radiusDp));
    }

    // ---------- text / views ----------
    static TextView tv(Context c, String text, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float wt, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, wt);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static Button button(Context c, String text, boolean primary, boolean danger) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 18), dp(c, 11), dp(c, 18), dp(c, 11));
        if (danger) {
            b.setTextColor(0xFFFFFFFF);
            b.setBackground(ripple(c, gradient(c, 0xFFFF5C7A, 0xFFFF7A5C, 14), 14));
        } else if (primary) {
            b.setTextColor(0xFFFFFFFF);
            b.setBackground(ripple(c, gradient(c, ACCENT, ACCENT_B, 14), 14));
        } else {
            b.setTextColor(FG);
            b.setBackground(ripple(c, card(c, PANEL2, 14), 14));
        }
        return b;
    }

    static ImageView icon(Context c, int res, int tint, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    static ImageView iconButton(Context c, int res, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(FG);
        int pad = dp(c, 11);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(ripple(c, card(c, PANEL, sizeDp / 2), sizeDp / 2));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(FG);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setBackground(card(c, PANEL2, 14));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return e;
    }

    static TextView avatar(Context c, String name, String id, int sizeDp) {
        int h = Math.abs((id == null ? "" : id).hashCode());
        int[] cols = AVATARS[h % AVATARS.length];
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, cols);
        g.setShape(GradientDrawable.OVAL);
        TextView t = new TextView(c);
        String n = name == null ? "" : name.trim();
        t.setText(n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(Locale.getDefault()));
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(sizeDp * 0.4f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(g);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return t;
    }

    // ---------- dialogs ----------
    static Dialog dialog(Context c, String title, String msg, View body, String cancelText,
                         String okText, boolean danger, final Ok ok) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(card(c, PANEL, 24));
        box.setPadding(dp(c, 22), dp(c, 22), dp(c, 22), dp(c, 14));
        if (title != null) box.addView(tv(c, title, 19, FG, true));
        if (msg != null) {
            TextView m = tv(c, msg, 14, MUTED, false);
            m.setLineSpacing(0, 1.18f);
            box.addView(m, lp(c, -1, -2, 0f, 0, 8, 0, 0));
        }
        if (body != null) box.addView(body, lp(c, -1, -2, 0f, 0, 16, 0, 0));
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = tv(c, cancelText == null ? "Cancel" : cancelText, 14, MUTED, true);
        cancel.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        cancel.setBackground(ripple(c, rounded(c, 0x00000000, 12), 12));
        cancel.setOnClickListener(v -> d.dismiss());
        row.addView(cancel);
        if (okText != null) {
            Button okB = button(c, okText, true, danger);
            okB.setOnClickListener(v -> {
                if (ok == null || ok.run()) d.dismiss();
            });
            row.addView(okB, lp(c, -2, -2, 0f, 6, 0, 0, 0));
        }
        box.addView(row, lp(c, -1, -2, 0f, 0, 14, 0, 0));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (c.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        return d;
    }

    static void confirm(Context c, String title, String msg, String okText, boolean danger, final Runnable go) {
        dialog(c, title, msg, null, "Cancel", okText, danger, () -> {
            go.run();
            return true;
        });
    }

    static void message(Context c, String title, String msg) {
        dialog(c, title, msg, null, "Close", null, false, null);
    }

    static void menu(Context c, String title, String[] labels, int dangerIndex, final Pick pick) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(card(c, PANEL, 24));
        box.setPadding(dp(c, 10), dp(c, 18), dp(c, 10), dp(c, 10));
        TextView t = tv(c, title, 17, FG, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(c, 12), 0, dp(c, 12), dp(c, 8));
        box.addView(t);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView item = tv(c, labels[i], 16, i == dangerIndex ? DANGER : FG, false);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(c, 12), 0, dp(c, 12), 0);
            item.setBackground(ripple(c, rounded(c, 0x00000000, 12), 12));
            item.setOnClickListener(v -> {
                d.dismiss();
                pick.pick(idx);
            });
            box.addView(item, new LinearLayout.LayoutParams(-1, dp(c, 52)));
        }
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (c.getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    static Dialog loading(Context c, String msg) {
        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(card(c, PANEL, 20));
        box.setPadding(dp(c, 22), dp(c, 20), dp(c, 26), dp(c, 20));
        ProgressBar pb = new ProgressBar(c);
        pb.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        box.addView(pb, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        TextView lt = tv(c, msg, 15, FG, false);
        lt.setId(android.R.id.text1);
        box.addView(lt, lp(c, -2, -2, 0f, 16, 0, 0, 0));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.show();
        return d;
    }

    // ---------- history ----------
    static void history(final Activity a, final String id, String name, final UrlPick pick) {
        final List<JSONObject> items = Store.history(a, id);
        String title = "History \u00B7 " + name;
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        if (items.isEmpty()) {
            body.addView(tv(a, "Nothing here yet. Pages you visit will show up here.", 14, MUTED, false));
            dialog(a, title, null, body, "Close", null, false, null);
            return;
        }
        ListView lv = new ListView(a);
        lv.setDivider(null);
        lv.setDividerHeight(dp(a, 6));
        lv.setSelector(new ColorDrawable(Color.TRANSPARENT));
        lv.setVerticalScrollBarEnabled(false);
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                JSONObject o = items.get(i);
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackground(ripple(a, rounded(a, PANEL2, 12), 12));
                row.setPadding(dp(a, 12), dp(a, 9), dp(a, 12), dp(a, 9));
                String t = o.optString("t");
                TextView tt = tv(a, t.isEmpty() ? o.optString("u") : t, 14, FG, true);
                tt.setSingleLine(true);
                tt.setEllipsize(TextUtils.TruncateAt.END);
                TextView uu = tv(a, o.optString("u"), 11, MUTED, false);
                uu.setSingleLine(true);
                uu.setEllipsize(TextUtils.TruncateAt.END);
                TextView ts = tv(a, ago(o.optLong("ts")), 11, ACCENT, false);
                row.addView(tt);
                row.addView(uu);
                row.addView(ts);
                return row;
            }
        });
        body.addView(lv, new LinearLayout.LayoutParams(-1,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.5f)));
        final Dialog[] ref = new Dialog[1];
        lv.setOnItemClickListener((p, v, pos, rid) -> {
            if (pick != null && pos < items.size()) {
                ref[0].dismiss();
                pick.pick(items.get(pos).optString("u"));
            }
        });
        ref[0] = dialog(a, title, null, body, "Close", "Clear history", true, () -> {
            Store.clearHistory(a, id);
            return true;
        });
    }

    static String ago(long ts) {
        if (ts <= 0) return "never";
        long s = (System.currentTimeMillis() - ts) / 1000;
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        if (s < 172800) return "yesterday";
        if (s < 30L * 86400) return (s / 86400) + " days ago";
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(ts));
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }
}
