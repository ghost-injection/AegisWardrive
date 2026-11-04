package com.aegispilot.wardrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Tesla Life appearance layer.
 *
 * Carries the WPF app's live settings — Glass (0-95), Effects intensity (1-3),
 * Visual effects (motion) toggle and RGB cycle speed (4-30s) — and turns them
 * into Android drawables so the whole shell reacts exactly like the desktop.
 */
public final class Skin {

    public static final String P = "wardrive";
    public static final int GLASS_DEF = 65;   // Tesla Life ships Value=65
    public static final int FX_DEF    = 2;    // Value=2 of 1..3
    public static final int SPEED_DEF = 12;   // Value=12 of 4..30

    private Skin() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static int glass(Context c) {
        return c == null ? GLASS_DEF : prefs(c).getInt("glass", GLASS_DEF);
    }

    public static int fx(Context c) {
        return c == null ? FX_DEF : prefs(c).getInt("fx", FX_DEF);
    }

    public static boolean motion(Context c) {
        return c == null || prefs(c).getBoolean("motion", true);
    }

    public static int speedSeconds(Context c) {
        return c == null ? SPEED_DEF : prefs(c).getInt("speed", SPEED_DEF);
    }

    /** Glass 0..95 -> 30..255 alpha. Never fully transparent, never flat black. */
    public static int glassAlpha(Context c) {
        int g = Math.max(0, Math.min(95, glass(c)));
        return (int) (34 + (g / 95f) * 221f);
    }

    /** Multiplier 0.55 .. 1.45 from the 1..3 intensity slider. */
    public static float fxGain(Context c) {
        return 0.55f + ((Math.max(1, Math.min(3, fx(c))) - 1) / 2f) * 0.90f;
    }

    // ------------------------------------------------------------ drawables

    /** The outer Tesla shell: 3-stop diagonal ramp + 1px accent edge. */
    public static GradientDrawable shell(Context c, Theme th, float radiusDp) {
        int a = glassAlpha(c);
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{th.shellStop(0, a), th.shellStop(1, a), th.shellStop(2, a)});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(Ui.dp(c, radiusDp));
        d.setStroke(Ui.dp(c, 1), Theme.withAlpha(th.accent, 0x66));
        return d;
    }

    /** Inner hairline ring Tesla stacks 3dp inside the shell. */
    public static GradientDrawable innerRing(Context c, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(Ui.dp(c, radiusDp));
        d.setStroke(Ui.dp(c, 1), 0x20FFFFFF);
        return d;
    }

    /** Brand card: visor gradient + soft accent edge, radius 18. */
    public static GradientDrawable brand(Context c, Theme th) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Theme.withAlpha(th.buttonFill, 0xD5), Theme.withAlpha(th.input, 0xA4)});
        d.setCornerRadius(Ui.dp(c, 18));
        d.setStroke(Ui.dp(c, 1), Theme.withAlpha(th.accent, 0x30));
        return d;
    }

    /** Standard content card: Panel with a whisper of gradient. */
    public static GradientDrawable card(Context c, Theme th) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Theme.withAlpha(th.panel, 0xF2), Theme.withAlpha(th.input, 0xE0)});
        d.setCornerRadius(Ui.dp(c, 12));
        d.setStroke(Ui.dp(c, 1), Theme.withAlpha(th.edge, 0xFF));
        return d;
    }

    /** Primary (StartFill) button: bright fill, ink text. */
    public static GradientDrawable buttonPrimary(Context c, Theme th) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{th.startFill, th.accent});
        d.setCornerRadius(Ui.dp(c, 10));
        d.setStroke(Ui.dp(c, 1), Theme.withAlpha(th.bright, 0x55));
        return d;
    }

    /** Secondary button: ButtonFill + Edge. */
    public static GradientDrawable buttonSecondary(Context c, Theme th) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{th.buttonFill, Theme.withAlpha(th.input, 0xFF)});
        d.setCornerRadius(Ui.dp(c, 10));
        d.setStroke(Ui.dp(c, 1), Theme.withAlpha(th.edge, 0xFF));
        return d;
    }

    /** Header rule: accent -> accent2 -> transparent, left to right. */
    public static GradientDrawable rule(Context c, Theme th) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{th.accent, th.ringStop(1), 0x00000000});
        d.setShape(GradientDrawable.RECTANGLE);
        return d;
    }

    /** Pill / mode button. */
    public static StateListDrawable pill(Context c, Theme th, boolean active) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable pressed = pillBase(c, th, true);
        GradientDrawable idle = pillBase(c, th, active);
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, idle);
        return s;
    }

    private static GradientDrawable pillBase(Context c, Theme th, boolean active) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                active
                        ? new int[]{Theme.withAlpha(th.accent, 0x33), Theme.withAlpha(th.accent, 0x14)}
                        : new int[]{th.buttonFill, Theme.withAlpha(th.input, 0xEE)});
        d.setCornerRadius(Ui.dp(c, 8));
        d.setStroke(Ui.dp(c, 1), active ? Theme.withAlpha(th.accent, 0xCC)
                : Theme.withAlpha(th.edge, 0xFF));
        return d;
    }

    /** Stat cell chip fill. */
    public static GradientDrawable visor(Context c, Theme th) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Theme.withAlpha(th.visor, 0xE6), Theme.withAlpha(th.panel, 0xF0)});
        d.setCornerRadius(Ui.dp(c, 10));
        d.setStroke(Ui.dp(c, 1), Theme.withAlpha(th.edge, 0xAA));
        return d;
    }

    /** Slider styled like the WPF one: 3dp edge track, accent-ball thumb. */
    public static void tint(SeekBar sb, Theme th) {
        int track = Theme.withAlpha(th.edge, 0xFF);
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(th.accent));
        sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(track));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(th.accent));
        sb.setSplitTrack(false);
        sb.setPadding(Ui.dp(sb.getContext(), 2), 0, Ui.dp(sb.getContext(), 2), 0);
    }

    // ------------------------------------------------------------ widgets

    /** One labelled slider row, Tesla settings-panel style. */
    public static SeekBar slider(Context c, Theme th, LinearLayout parent,
                                 String label, int min, int max, int value,
                                 SeekBar.OnSeekBarChangeListener l) {
        TextView lab = Ui.text(c, label, th.muted, 10f, false);
        lab.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 2));
        parent.addView(lab);

        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        tint(sb, th);
        sb.setOnSeekBarChangeListener(l);
        parent.addView(sb, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 30)));
        return sb;
    }

    public static TextView iconButton(Context c, Theme th, String glyph) {
        TextView t = Ui.text(c, glyph, th.buttonText, 15f, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 9), Ui.dp(c, 4), Ui.dp(c, 9), Ui.dp(c, 4));
        t.setBackground(pill(c, th, false));
        t.setClickable(true);
        return t;
    }
}
