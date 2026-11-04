package com.aegispilot.wardrive;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cheap, dependency-free material for the terminal look. */
public final class Ui {

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context c, String s, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s == null ? "" : s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Label style: uppercase, tracked out, small. */
    public static TextView label(Context c, String s, int color, float sp) {
        TextView t = text(c, s, color, sp, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.14f);
        return t;
    }

    public static GradientDrawable box(Context c, int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static View divider(Context c, int color, int heightDp) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    public static TextView btn(Context c, String label, Theme th, boolean primary) {
        TextView t = text(c, label, primary ? th.ink : th.accent, 12f, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setPadding(dp(c, 12), dp(c, 13), dp(c, 12), dp(c, 13));
        t.setMinimumHeight(dp(c, 46));
        t.setBackground(primary ? Skin.buttonPrimary(c, th) : Skin.buttonSecondary(c, th));
        t.setClickable(true);
        t.setFocusable(true);
        fit(t, 9f, 12f);
        return t;
    }

    public static TextView chip(Context c, String label, Theme th, boolean active) {
        TextView t = text(c, label, active ? th.bright : th.muted, 10f, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.04f);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(null);
        t.setPadding(dp(c, 6), dp(c, 9), dp(c, 6), dp(c, 9));
        t.setMinimumHeight(dp(c, 36));
        t.setBackground(Skin.pill(c, th, active));
        t.setClickable(true);
        fit(t, 6f, 10f);
        return t;
    }

    /**
     * Kill every wrap in the system. Labels shrink before they are allowed to
     * break onto a second line, so no button ever grows a second row of text.
     */
    public static void fit(TextView t, float minSp, float maxSp) {
        t.setSingleLine(true);
        t.setMaxLines(1);
        t.setHorizontallyScrolling(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            t.setAutoSizeTextTypeUniformWithConfiguration(
                    Math.round(minSp), Math.round(maxSp), 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        }
    }

    public static LinearLayout card(Context c, Theme th) {
        LinearLayout l = col(c);
        l.setBackground(Skin.card(c, th));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static void margin(View v, int l, int t, int r, int b, Context c) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) raw;
            m.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
            v.setLayoutParams(m);
        }
    }
}
