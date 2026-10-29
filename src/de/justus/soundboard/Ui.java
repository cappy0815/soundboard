package de.justus.soundboard;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Einheitliche Bausteine fuer das dunkle Design (Pillen-Knoepfe, Ueberschriften, Regler). */
final class Ui {

    static final int DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** gefuellte Pille */
    static void pill(TextView b, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(b.getContext(), 22));
        style(b, shape, Util.isLight(color) ? Color.rgb(20, 20, 24) : Color.WHITE);
    }

    /** umrandete Pille */
    static void outline(TextView b, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Util.alpha(color, 0.08f));
        shape.setStroke(dp(b.getContext(), 1.5f), Util.alpha(color, 0.75f));
        shape.setCornerRadius(dp(b.getContext(), 22));
        style(b, shape, color);
    }

    private static void style(TextView b, GradientDrawable shape, int textColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(b.getContext(), 22));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape, mask));
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        if (b instanceof Button) {
            ((Button) b).setStateListAnimator(null);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
        }
        int p = dp(b.getContext(), 16), pv = dp(b.getContext(), 8);
        b.setPadding(p, pv, p, pv);
    }

    static Button button(Context c, String label, int color, boolean filled, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        if (filled) pill(b, color);
        else outline(b, color);
        b.setOnClickListener(l);
        return b;
    }

    /** Abschnitts-Ueberschrift in Grossbuchstaben */
    static TextView section(Context c, String title) {
        TextView h = text(c, title.toUpperCase(java.util.Locale.GERMANY), 11, Util.AMBER, true);
        h.setLetterSpacing(0.1f);
        h.setPadding(0, dp(c, 16), 0, dp(c, 6));
        return h;
    }

    static Switch toggle(Context c, String label, boolean on) {
        Switch sw = new Switch(c);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setTextColor(Util.TEXT);
        sw.setChecked(on);
        sw.setPadding(0, dp(c, 6), 0, dp(c, 6));
        tint(sw);
        return sw;
    }

    static void tint(Switch sw) {
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{Util.AMBER, Color.rgb(170, 175, 185)}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{Util.alpha(Util.AMBER, 0.5f), Color.rgb(70, 75, 85)}));
    }

    /** Wert-Formatierer fuer Regler */
    interface Fmt {
        String fmt(float v);
    }

    interface OnValue {
        void value(float v);
    }

    /** Regler-Zeile: Beschriftung links, Regler, Wert rechts. Werte von min bis max in Schritten von step */
    static LinearLayout slider(Context c, String label, final float min, final float max, final float step, float value,
                               final Fmt fmt, final OnValue on) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 2), 0, dp(c, 2));
        TextView l = text(c, label, 14, Util.TEXT, false);
        row.addView(l, new LinearLayout.LayoutParams(dp(c, 150), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(c);
        final int steps = Math.round((max - min) / step);
        sb.setMax(steps);
        sb.setProgress(Math.round((Math.max(min, Math.min(max, value)) - min) / step));
        sb.setProgressTintList(ColorStateList.valueOf(Util.AMBER));
        sb.setThumbTintList(ColorStateList.valueOf(Util.AMBER));
        row.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = text(c, fmt.fmt(value), 14, Util.AMBER, true);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(dp(c, 76), ViewGroup.LayoutParams.WRAP_CONTENT));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = min + p * step;
                val.setText(fmt.fmt(v));
                if (on != null) on.value(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        return row;
    }

    /** Knoepfe eines angezeigten Dialogs stylen: Hauptaktion gefuellt, Rest umrandet, alles mittig */
    static AlertDialog dialog(AlertDialog d) {
        Button p = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Button n = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button u = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (p != null) pill(p, Util.AMBER);
        if (n != null) outline(n, Util.TEXT);
        if (u != null) outline(u, Util.RED);
        Button any = p != null ? p : (n != null ? n : u);
        if (any != null && any.getParent() instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) any.getParent();
            bar.setGravity(Gravity.CENTER);
            for (int i = 0; i < bar.getChildCount(); i++) {
                View ch = bar.getChildAt(i);
                if (!(ch instanceof Button)) ch.setVisibility(View.GONE);
            }
        }
        for (Button b : new Button[]{p, n, u}) {
            if (b == null || !(b.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) b.getLayoutParams();
            lp.leftMargin = dp(b.getContext(), 5);
            lp.rightMargin = dp(b.getContext(), 5);
            b.setLayoutParams(lp);
        }
        if (d.getWindow() != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Util.SURFACE);
            bg.setCornerRadius(dp(d.getContext(), 18));
            d.getWindow().setBackgroundDrawable(bg);
        }
        return d;
    }

    /** ScrollView mit "weiter scrollen"-Knopf unten, solange es nach unten weitergeht (wie in EHV Live) */
    static android.widget.FrameLayout withScrollHint(final android.widget.ScrollView sv) {
        Context c = sv.getContext();
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(c, 48));
        sv.setScrollbarFadingEnabled(false);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
        frame.addView(sv, new android.widget.FrameLayout.LayoutParams(-1, -2));
        final TextView more = text(c, "▼  weiter scrollen", 12, Util.BG, true);
        more.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Util.alpha(Util.AMBER, 0.92f));
        bg.setCornerRadius(dp(c, 14));
        more.setBackground(bg);
        more.setElevation(dp(c, 4));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(c, 8);
        frame.addView(more, lp);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sv.smoothScrollBy(0, sv.getHeight() * 2 / 3);
            }
        });
        final Runnable upd = new Runnable() {
            @Override
            public void run() {
                more.setVisibility(sv.canScrollVertically(1) ? View.VISIBLE : View.GONE);
            }
        };
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                upd.run();
            }
        });
        sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                upd.run();
            }
        });
        return frame;
    }

    /** Dialog auf einen Anteil der Bildschirmbreite setzen (Querformat-Tablet) */
    static void width(AlertDialog d, float frac) {
        if (d.getWindow() == null) return;
        int w = Math.round(d.getContext().getResources().getDisplayMetrics().widthPixels * frac);
        d.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
