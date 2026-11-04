package com.aegispilot.wardrive;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.Random;

/** Full-screen CRT layer: scanlines, rolling sweep, tear-glitches, vignette.
 *  Gain tuned down: readable first, spooky second. */
public class CrtOverlay extends View {

    // ---- intensity knobs (dialed back from v1) ----
    private static final int   LINE_DARK_A   = 0x2E;  // was 0x66
    private static final int   LINE_LIGHT_A  = 0x06;  // was 0x14
    private static final float LINE_GAP_DP   = 4f;    // was 3f (wider gap = less moire)
    private static final int   BAND_A_MID    = 12;    // was 26
    private static final int   BAND_A_HOT    = 20;    // was 40
    private static final int   BAND_EDGE_A   = 40;    // was 90
    private static final float BAND_HEIGHT   = 0.15f; // was 0.22
    private static final float SWEEP_SPEED   = 0.075f;// was 0.11
    private static final int   VIG_A         = 0x3A;  // was 0x55
    private static final float AUTO_BURST    = 0.30f; // was 0.45
    private static final int   FRAME_MS      = 42;    // was 33

    private final Paint pLine = new Paint();
    private final Paint pBand = new Paint();
    private final Paint pEdge = new Paint();
    private final Paint pGlitch = new Paint();
    private final Paint pVig = new Paint();
    private final Random rnd = new Random();
    private final Theme th;
    private final Context ctx;
    private Bitmap tile;
    private float sweep = -0.2f, burst = 0f, t = 0f;
    private long last = 0L, nextAuto = 0L;
    private boolean active = true;
    private float density = 1f;

    public CrtOverlay(Context c, Theme th) {
        super(c);
        this.th = th;
        this.ctx = c;
        density = c.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        pEdge.setStyle(Paint.Style.FILL);
        pGlitch.setStyle(Paint.Style.FILL);
        pVig.setStyle(Paint.Style.FILL);
    }

    public void burst(float power) { burst = Math.min(1f, Math.max(burst, power)); }

    public void setActive(boolean a) {
        active = a;
        if (a) { last = 0L; nextAuto = System.currentTimeMillis() + 6000 + rnd.nextInt(6000); removeCallbacks(frame); postOnAnimation(frame); }
        else removeCallbacks(frame);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int gap = Math.max(4, Math.round(LINE_GAP_DP * density));
        tile = Bitmap.createBitmap(1, gap, Bitmap.Config.ARGB_8888);
        Canvas tc = new Canvas(tile);
        tc.drawColor(0x00000000);
        Paint pl = new Paint();
        pl.setColor(withAlpha(0x000000, LINE_DARK_A));
        tc.drawRect(0, 0, 1, Math.max(1f, density * 0.8f), pl);
        pl.setColor(withAlpha(0xFFFFFF, LINE_LIGHT_A));
        tc.drawRect(0, Math.max(1f, density * 0.8f), 1, Math.max(1.6f, density * 1.2f), pl);
        pLine.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        pVig.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.78f,
                new int[]{0x00000000, 0x00000000, withAlpha(0x000000, VIG_A)},
                new float[]{0f, 0.66f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (!Skin.motion(ctx)) return;

        // Tesla appearance engine: effect intensity 1..3 scales every alpha
        float g = Skin.fxGain(ctx);
        int accent = th.accent, accent2 = th.accent2;

        // rolling sweep band
        float bandH = h * BAND_HEIGHT;
        float y = sweep * (h + bandH) - bandH;
        pBand.setShader(new LinearGradient(0, y, 0, y + bandH,
                new int[]{0x00FFFFFF, withAlpha(accent, (int) (BAND_A_MID * g)),
                          withAlpha(accent, (int) (BAND_A_HOT * g)), 0x00FFFFFF},
                new float[]{0f, 0.74f, 0.9f, 1f}, Shader.TileMode.CLAMP));
        pBand.setDither(true);
        cv.drawRect(0, y, w, y + bandH, pBand);
        pEdge.setColor(withAlpha(accent, (int) (BAND_EDGE_A * g)));
        cv.drawRect(0, y + bandH - Math.max(1f, density * 0.7f), w, y + bandH, pEdge);

        // scanlines
        if (tile != null) cv.drawRect(0, 0, w, h, pLine);

        // tear glitches (rarer, softer)
        float b = burst;
        if (b > 0.02f) {
            int bars = 1 + (int) (b * 4);
            for (int i = 0; i < bars; i++) {
                float gy = rnd.nextFloat() * h;
                float gh = (1 + rnd.nextInt(6)) * density;
                float gx = rnd.nextFloat() * w * 0.7f;
                float gw = w * (0.08f + rnd.nextFloat() * 0.38f);
                int col = rnd.nextBoolean() ? accent : accent2;
                pGlitch.setColor(withAlpha(col, (int) ((16 + 52 * b) * g)));
                cv.drawRect(gx, gy, gx + gw, gy + gh, pGlitch);
                if (rnd.nextInt(4) == 0) {
                    pGlitch.setColor(withAlpha(0x000000, (int) (64 * b)));
                    cv.drawRect(0, gy + gh, w, gy + gh + gh * 0.7f, pGlitch);
                }
            }
            // flicker wash
            pGlitch.setColor(withAlpha(accent2, (int) (7 * b * g)));
            cv.drawRect(0, 0, w, h, pGlitch);
        }

        // vignette
        if (pVig.getShader() != null) {
            float vg = Math.min(1f, g);
            cv.drawRect(0, 0, w, h, pVig);
        }
    }

    private static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((Math.max(0, Math.min(255, a))) << 24);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (!Skin.motion(ctx)) { postDelayed(this, 400); return; }
            long now = System.nanoTime();
            float dt = last == 0L ? 0.016f : Math.min(0.08f, (now - last) / 1e9f);
            last = now;
            t += dt;
            sweep += dt * SWEEP_SPEED;
            if (sweep > 1.15f) { sweep = -0.2f; nextAuto = System.currentTimeMillis() + 7000 + rnd.nextInt(12000); }
            burst = Math.max(0f, burst - dt * 1.8f);
            if (System.currentTimeMillis() > nextAuto) { burst(AUTO_BURST); nextAuto = System.currentTimeMillis() + 9000 + rnd.nextInt(14000); }
            invalidate();
            postOnAnimationDelayed(this, FRAME_MS);
        }
    };
}
