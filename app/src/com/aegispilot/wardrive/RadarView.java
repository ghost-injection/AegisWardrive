package com.aegispilot.wardrive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sweeping radar with live blips. Pure canvas, no deps.
 *
 * Bearings are REAL. A blip is placed by true bearing from the operator's GPS fix
 * to the node's position (captured or multilaterated), rotated by the device
 * heading so the top of the grid is where the phone is pointing.
 *
 * Nodes with no position cannot be placed, and are counted rather than drawn at a
 * fabricated angle. If there is no GPS fix at all, bearings fall back to a stable
 * per-BSSID spread and the header says ESTIMATED, so the display never pretends.
 */
public class RadarView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mtx = new Matrix();
    private final List<Hit> hits = new ArrayList<>();
    private Theme th;
    private float angle = 0f;
    private long lastNs = 0L;
    private boolean active = true;

    // ---- real geometry -----------------------------------------------------
    private boolean hasFix = false;
    private double sLat = 0, sLon = 0;
    private float headingDeg = 0f;
    private Map<String, double[]> positions;
    private String targetBssid;

    private int plottedN = 0, unknownN = 0;

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

    /** Operator position and heading. Heading drives the heading-up rotation. */
    public void setSelf(double lat, double lon, float heading, boolean fix) {
        sLat = lat; sLon = lon; headingDeg = heading; hasFix = fix;
    }

    /** bssid -> {lat, lon} for captured and multilaterated node positions. */
    public void setPositions(Map<String, double[]> pos) { positions = pos; }

    /** Selected target; drawn last, highlighted, with a bearing line from the centre. */
    public void setTarget(String bssid) { targetBssid = bssid; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f, cy = h / 2f;
        // Reserve room for everything drawn outside the ring: outer stroke, corner
        // brackets, blip halos and the header labels.
        float pad = dp(16);
        float r = Math.min(w, h) / 2f - pad;
        if (r <= dp(12)) return;

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
        // compass ticks: N/E/S/W rotate with heading so "up" is always where you face
        float[] dirs = {0f, 90f, 180f, 270f};
        String[] names = {"N", "E", "S", "W"};
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(dp(8f));
        for (int i = 0; i < 4; i++) {
            double rel = Math.toRadians(dirs[i] - headingDeg);
            float tx = cx + (float) Math.sin(rel) * (r - dp(7));
            float ty = cy - (float) Math.cos(rel) * (r - dp(7)) + dp(3);
            p.setColor(withAlpha(th.accent, 120));
            c.drawText(names[i], tx - dp(2.5f), ty, p);
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

    /**
     * True bearing (deg from north) and normalised range for one node, or null
     * when it cannot be placed. Third element: 1 = measured, 0 = estimated.
     */
    private float[] polar(Hit h) {
        float rn = h.rangeNorm();
        double tlat = h.lat, tlon = h.lon;
        if (positions != null) {
            double[] q = positions.get(h.bssid);
            if (q != null) { tlat = q[0]; tlon = q[1]; }
        }
        boolean geo = (tlat != 0 || tlon != 0);
        if (hasFix && geo) {
            return new float[]{(float) Nav.bearing(sLat, sLon, tlat, tlon), rn, 1f};
        }
        if (!hasFix) {
            // No fix: keep nodes visible for orientation, but say the bearing is a guess.
            return new float[]{h.bearing(), rn, 0f};
        }
        return null;
    }

    private void drawBlips(Canvas c, float cx, float cy, float r) {
        long t = System.currentTimeMillis();
        plottedN = 0; unknownN = 0;
        Hit sel = null;

        for (Hit h : hits) {
            if (targetBssid != null && targetBssid.equals(h.bssid)) { sel = h; continue; }
            if (!plot(h, c, cx, cy, r, t, false)) unknownN++;
            else plottedN++;
        }
        // selected target drawn last so nothing overlaps it
        if (sel != null) {
            if (plot(sel, c, cx, cy, r, t, true)) plottedN++;
            else unknownN++;
        }
    }

    private boolean plot(Hit h, Canvas c, float cx, float cy, float r, long t, boolean selected) {
        float[] pol = polar(h);
        if (pol == null) return false;

        double rel = Math.toRadians(pol[0] - headingDeg);
        float rr = Math.min(pol[1], 1f) * Math.max(0f, r - dp(14f));
        float x = cx + (float) Math.sin(rel) * rr;
        float y = cy - (float) Math.cos(rel) * rr;

        int col = h.isWep() ? th.bad : h.isOpen() ? th.warn : th.accent2;
        float pulse = 0.55f + 0.45f * (float) Math.abs(Math.sin(t / 420.0 + h.bssid.hashCode() % 7));

        if (selected) {
            line.setColor(withAlpha(th.accent, 150));
            line.setStrokeWidth(dp(1.2f));
            c.drawLine(cx, cy, x, y, line);
            line.setStrokeWidth(1f);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(withAlpha(th.accent, 230));
            c.drawCircle(x, y, dp(11f) + dp(4f) * pulse, p);
            p.setStyle(Paint.Style.FILL);
        }

        p.setColor(withAlpha(col, (int) (80 * pulse)));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, (selected ? dp(9f) : dp(7f)) * pulse, p);
        p.setColor(col);
        c.drawCircle(x, y, selected ? dp(2.6f) : dp(1.8f), p);

        if (selected || h.best > -60) {
            line.setColor(withAlpha(selected ? th.accent : col, selected ? 200 : 120));
            c.drawCircle(x, y, dp(11f), line);
        }

        if (selected) {
            p.setColor(th.bright);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(9f));
            String name = h.ssidOrGhost();
            if (name.length() > 14) name = name.substring(0, 13) + "\u2026";
            String lbl = name + "  " + Math.round(pol[0]) + "\u00b0";
            float tw = p.measureText(lbl);
            float lx = Math.min(Math.max(x - tw / 2f, cx - r + dp(4)), cx + r - tw - dp(4));
            float ly = y > cy ? y - dp(17) : y + dp(21);
            c.drawText(lbl, lx, ly, p);
        }
        return true;
    }

    private void drawFrame(Canvas c, int w, int h, float r, float cx, float cy) {
        p.setColor(th.dim);
        p.setTextSize(dp(8.5f));
        p.setTypeface(Typeface.MONOSPACE);
        String t1 = "RANGE " + String.format(Locale.US, "%.0fm", r / 2.2f);
        String t2 = "NODES " + plottedN + (unknownN > 0 ? " \u00b7 " + unknownN + "?" : "");
        // Labels sit just INSIDE the ring so they can never clip the canvas.
        float labelY = cy - r + dp(10);
        c.drawText(t1, cx - r + dp(6), labelY, p);
        float tw = p.measureText(t2);
        c.drawText(t2, cx + r - tw - dp(6), labelY, p);

        // bearing source, stated plainly
        p.setTextSize(dp(7.5f));
        p.setColor(withAlpha(hasFix ? th.good : th.warn, 210));
        String mode = hasFix ? "HEADING UP \u00b7 TRUE BEARINGS" : "NO FIX \u00b7 BEARINGS ESTIMATED";
        float mw = p.measureText(mode);
        c.drawText(mode, cx - mw / 2f, labelY + dp(11), p);

        p.setColor(withAlpha(th.accent, 170));
        float b = dp(9);
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
