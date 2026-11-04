package com.aegispilot.wardrive;

import android.graphics.Color;

/**
 * Tesla Life skin engine, ported to Android.
 *
 * Ported 1:1 from the user's WPF app (Life.cs ThemePaint / Themes.cs):
 *   every colour is derived from ONE hue through HSV, never hand-picked.
 *   Accent .85/1 · Bright .16/1 · Muted .23/.78 · Dim .35/.50 · Edge .50/.28
 *   Panel .55/.10 · Input .50/.08 · ButtonFill .48/.12 · ButtonText .23/.86
 *   StartFill .40/1 · Ink .70/.09 · Track .50/.18 · Visor .50/.17
 *
 * Five skins, same five the Tesla Life build ships:
 *   NEON 166 · CATHODE 35 · HACKER GREEN 118 · SPECTRUM RGB (animated) · TESLA / ELECTRIC BLUE 192
 */
public final class Theme {

    public final String id;
    public final String name;
    public final String tagline;
    public final float baseHue;
    public final boolean animated;

    /* ---- palette (mutable: SPECTRUM RGB recolours live) ---- */
    public int accent, bright, muted, dim, edge, panel, input,
               buttonFill, buttonText, startFill, ink, track, visor;

    /* ---- legacy aliases the rest of the scanner already speaks ---- */
    public int bg, panelAlt, accent2, text, good, warn, bad, line;

    public Theme(String id, String name, String tagline, float hue, boolean animated) {
        this.id = id;
        this.name = name;
        this.tagline = tagline;
        this.baseHue = hue;
        this.animated = animated;
        recolor(hue);
    }

    /** Rebuild the whole 13-slot palette from a single hue. */
    public void recolor(float hueDeg) {
        float h = ((hueDeg % 360f) + 360f) % 360f;
        accent     = hsv(h, 0.85f, 1.00f);
        bright     = hsv(h, 0.16f, 1.00f);
        muted      = hsv(h, 0.23f, 0.78f);
        dim        = hsv(h, 0.35f, 0.50f);
        edge       = hsv(h, 0.50f, 0.28f);
        panel      = hsv(h, 0.55f, 0.10f);
        input      = hsv(h, 0.50f, 0.08f);
        buttonFill = hsv(h, 0.48f, 0.12f);
        buttonText = hsv(h, 0.23f, 0.86f);
        startFill  = hsv(h, 0.40f, 1.00f);
        ink        = hsv(h, 0.70f, 0.09f);
        track      = hsv(h, 0.50f, 0.18f);
        visor      = hsv(h, 0.50f, 0.17f);

        /* aliases */
        text     = bright;
        bg       = hsv(h, 0.60f, 0.055f);   // shell void, darker than Panel
        panelAlt = hsv(h, 0.52f, 0.16f);
        accent2  = hsv(h + 38f, 0.78f, 1.00f); // gradient partner (cyan -> blue)
        good     = hsv(150f, 0.58f, 0.95f);
        warn     = hsv(45f, 0.92f, 1.00f);
        bad      = hsv(356f, 0.86f, 1.00f);
        line     = hsv(h, 0.42f, 0.30f);   // hairline divider between feed sections
    }

    /** Shell gradient stop i (0..2) — Tesla Life's teshsv ramp. */
    public int shellStop(int i, int glassAlpha) {
        float h = baseHue;
        int rgb = hsv(h + (i * 4f), 0.55f, 0.10f + (i * 0.015f));
        if (animated) rgb = hsv(hueNow() + (i * 30f), 0.62f, 0.12f);
        return withAlpha(rgb, glassAlpha);
    }

    /** Accent ring / border stops. TESLA sweeps hue per stop, RGB spins fast. */
    public int ringStop(int i) {
        if (animated) return hsv(hueNow() + (i * 100f), 0.80f, 1f);
        if ("tesla".equals(id)) return hsv(baseHue + (i * 18f), 0.80f, 1f);
        return accent;
    }

    public float hueNow() {
        if (!animated) return baseHue;
        return (System.currentTimeMillis() / 1000f) * (360f / Math.max(4f, Skin.speedSeconds(null)));
    }

    public static final Theme[] ALL = new Theme[]{
            new Theme("neon",     "NEON",                 "CHARGE YOUR SIGNAL.",          166f, false),
            new Theme("cathode",  "CATHODE",              "ANALOG SCARS. DIGITAL TEETH.",  35f, false),
            new Theme("hacker",   "HACKER GREEN",         "SHED THE SKIN. KEEP THE NOISE.",118f, false),
            new Theme("spectrum", "SPECTRUM RGB",         "EVERY HUE IS A WOUND.",         200f, true),
            new Theme("tesla",    "ELECTRIC BLUE",        "BATTERY. THERMAL. STORAGE.",    192f, false)
    };

    public static Theme byId(String id) {
        if (id != null) {
            for (Theme t : ALL) if (t.id.equals(id)) return t;
            /* migrate skins from the old DedSec build */
            if (id.equals("dedsec") || id.equals("blume")) return ALL[4];
            if (id.equals("wrench") || id.equals("defalt")) return ALL[0];
            if (id.equals("anon") || id.equals("ctos")) return ALL[1];
        }
        return ALL[4];
    }

    public int swatchA() { return accent; }
    public int swatchB() { return accent2; }
    public int swatchC() { return panelAlt; }

    // ---------------------------------------------------------------- helpers

    public static int hsv(float h, float s, float v) {
        return Color.HSVToColor(new float[]{((h % 360f) + 360f) % 360f,
                clamp(s), clamp(v)});
    }

    public static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((Math.max(0, Math.min(255, a))) << 24);
    }

    private static float clamp(float f) { return f < 0f ? 0f : (f > 1f ? 1f : f); }
}
