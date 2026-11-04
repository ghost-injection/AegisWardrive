package com.aegispilot.wardrive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Sweeping radar with live blips. Pure canvas, no deps. */
public class RadarView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mtx = new Matrix();
    private final List<Hit> hits = new ArrayList<>();
    private Theme th;
    private float angle = 0f;
    private long lastNs = 0L;
    private boolean active = true;

    public RadarView(Context c, Theme th) {
        super(c);
        this.th = th;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1f);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setTheme(Theme t) { th = t; invalidate(); }

    public void setHits(List<Hit> h) {
        hits.clear();
        if (h != null) hits.addAll(h);
        invalidate();
    }

    public void setActive(boolean a) { active = a; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(6);

        c.drawColor(th.bg);
        drawGrid(c, cx, cy, r);
        drawRings(c, cx, cy, r);
        drawSweep(c, cx, cy, r);
        drawBlips(c, cx, cy, r);
        drawFrame(c, w, h, r, cx, cy);
        drawScanlines(c, w, h);

        if (active) {
            long now = System.nanoTime();
            if (lastNs != 0L) {
                float dt = (now - lastNs) / 1_000_000_000f;
                angle = (angle + dt * 130f) % 360f;
            }
            lastNs = now;
            postInvalidateOnAnimation();
        }
    }

    private void drawGrid(Canvas c, float cx, float cy, float r) {
        line.setColor(withAlpha(th.accent, 26));
        c.drawLine(cx - r, cy, cx + r, cy, line);
        c.drawLine(cx, cy - r, cx, cy + r, line);
        for (int i = 1; i <= 3; i++) {
            float q = r / 4f * i;
            c.drawLine(cx - r, cy - q, cx + r, cy - q, line);
            c.drawLine(cx - r, cy + q, cx + r, cy + q, line);
            c.drawLine(cx - q, cy - r, cx - q, cy + r, line);
            c.drawLine(cx + q, cy - r, cx + q, cy + r, line);
        }
    }

    private void drawRings(Canvas c, float cx, float cy, float r) {
        line.setColor(withAlpha(th.accent, 60));
        for (int i = 1; i <= 4; i++) c.drawCircle(cx, cy, r / 4f * i, line);
        line.setColor(withAlpha(th.accent, 110));
        c.drawCircle(cx, cy, r, line);
    }

    private void drawSweep(Canvas c, float cx, float cy, float r) {
        SweepGradient g = new SweepGradient(cx, cy,
                new int[]{withAlpha(th.accent, 0), withAlpha(th.accent, 20),
                        withAlpha(th.accent, 90), withAlpha(th.accent, 160)},
                new float[]{0f, 0.72f, 0.94f, 1f});
        mtx.setRotate(angle, cx, cy);
        g.setLocalMatrix(mtx);
        p.setShader(g);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);

        // leading edge
        double rad = Math.toRadians(angle);
        line.setColor(th.accent);
        line.setStrokeWidth(dp(1.4f));
        c.drawLine(cx, cy, cx + (float) Math.cos(rad) * r, cy + (float) Math.sin(rad) * r, line);
        line.setStrokeWidth(1f);

        RadialGradient core = new RadialGradient(cx, cy, dp(26),
                withAlpha(th.accent, 120), withAlpha(th.accent, 0), Shader.TileMode.CLAMP);
        p.setShader(core);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, dp(26), p);
        p.setShader(null);
        p.setColor(th.accent);
        c.drawCircle(cx, cy, dp(2.5f), p);
    }

    private void drawBlips(Canvas c, float cx, float cy, float r) {
        long t = System.currentTimeMillis();
        for (Hit h : hits) {
            double a = Math.toRadians(h.bearing());
            float rr = h.rangeNorm() * r;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            int col = h.isWep() ? th.bad : h.isOpen() ? th.warn : th.accent2;
            float pulse = 0.55f + 0.45f * (float) Math.abs(Math.sin(t / 420.0 + h.bssid.hashCode() % 7));
            p.setColor(withAlpha(col, (int) (80 * pulse)));
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, dp(7f) * pulse, p);
            p.setColor(col);
            c.drawCircle(x, y, dp(1.8f), p);
            if (h.best > -60) {
                line.setColor(withAlpha(col, 120));
                c.drawCircle(x, y, dp(11f), line);
            }
        }
    }

    private void drawFrame(Canvas c, int w, int h, float r, float cx, float cy) {
        p.setColor(th.dim);
        p.setTextSize(dp(8.5f));
        p.setTypeface(android.graphics.Typeface.MONOSPACE);
        String t1 = "RANGE " + String.format(java.util.Locale.US, "%.0fm", r / 2.2f);
        String t2 = "NODES " + hits.size();
        c.drawText(t1, cx - r, cy - r - dp(4), p);
        float tw = p.measureText(t2);
        c.drawText(t2, cx + r - tw, cy - r - dp(4), p);

        p.setColor(withAlpha(th.accent, 170));
        float b = dp(9);
        // corner brackets
        c.drawLine(cx - r, cy - r, cx - r + b, cy - r, p);
        c.drawLine(cx - r, cy - r, cx - r, cy - r + b, p);
        c.drawLine(cx + r, cy - r, cx + r - b, cy - r, p);
        c.drawLine(cx + r, cy - r, cx + r, cy - r + b, p);
        c.drawLine(cx - r, cy + r, cx - r + b, cy + r, p);
        c.drawLine(cx - r, cy + r, cx - r, cy + r - b, p);
        c.drawLine(cx + r, cy + r, cx + r - b, cy + r, p);
        c.drawLine(cx + r, cy + r, cx + r, cy + r - b, p);
    }

    private void drawScanlines(Canvas c, int w, int h) {
        p.setColor(withAlpha(Color.BLACK, 40));
        p.setStyle(Paint.Style.FILL);
        for (int y = 0; y < h; y += dp(3)) c.drawRect(0, y, w, y + 1, p);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
