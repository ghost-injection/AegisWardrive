package com.aegispilot.wardrive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD map: local ENU projection around the operator, range rings, target nodes,
 * route line to a selected target, heading cone, live position tracking and a
 * Waze-style guidance strip. No tiles, no network, no API keys: it plots real
 * lat/lon geometry from the scanner + imported WiGLE databases.
 */
public class MapView extends View {

    public interface OnTargetTap { void onTarget(Hit h); }

    private final int cBg, cGrid, cText, cDim, cAccent, cAccent2, cGood, cWarn, cBad, cPanel;
    private final float d;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Hit> hits = new ArrayList<>();
    private Hit target;
    private OnTargetTap tapCb;

    /* label engine: every visible SSID gets a readable pill, placed so no two collide */
    private boolean labels = true;
    private final List<RectF> placed = new ArrayList<>();
    private final List<Hit> labelOrder = new ArrayList<>();
    private final RectF box = new RectF();

    /**
     * Multilaterated transmitter positions, keyed by BSSID:
     * {lat, lon, uncertaintyMeters, obsCount}. When a node has an estimate the
     * map plots THAT, not the position where the scan happened to be captured.
     */
    private java.util.Map<String, double[]> est = new java.util.HashMap<>();
    private boolean showEst = true;

    private boolean hasSelf;
    private double sLat, sLon;
    private double sAlt, sAcc = 8;
    private float sBear, sSpeed;
    private long fixAge;

    private double mpp = 0.28;            // meters per pixel
    private double panE, panN;            // view-center offset from operator, meters
    private float downX, downY;
    private boolean dragging, moved;
    private final ScaleGestureDetector scaleDet;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable pulses = new Runnable() {
        @Override public void run() {
            invalidate();
            ticker.postDelayed(this, 90);
        }
    };

    public MapView(Context c, Theme th) {
        this(c, th, null);
    }

    public MapView(Context c, Theme th, AttributeSet a) {
        super(c, a);
        cBg = th.bg;
        cPanel = th.panel;
        cGrid = th.edge;
        cText = th.text;
        cDim = th.dim;
        cAccent = th.accent;
        cAccent2 = th.accent2;
        cGood = th.good;
        cWarn = th.warn;
        cBad = th.bad;
        d = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        scaleDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector g) {
                mpp = clamp(mpp / g.getScaleFactor(), 0.05, 40.0);
                invalidate();
                return true;
            }
        });
    }

    public void setOnTargetTap(OnTargetTap cb) { tapCb = cb; }

    /** Feed the multilaterated transmitter positions in. */
    public void setEstimates(java.util.Map<String, double[]> m) {
        est = m == null ? new java.util.HashMap<String, double[]>() : m;
        invalidate();
    }

    public void setShowEst(boolean on) { showEst = on; invalidate(); }

    public boolean showEstOn() { return showEst; }

    /** Draw position for a node: estimated transmitter if known, else capture point. */
    private double[] pos(Hit h) {
        if (showEst) {
            double[] e = est.get(h.bssid);
            if (e != null && (e[0] != 0 || e[1] != 0)) return e;
        }
        return new double[]{h.lat, h.lon};
    }

    private boolean hasEst(Hit h) {
        double[] e = est.get(h.bssid);
        return e != null && (e[0] != 0 || e[1] != 0);
    }

    public void setHits(List<Hit> l) {
        hits.clear();
        if (l != null) hits.addAll(l);
        invalidate();
    }

    /** Toggle SSID labels on the map. */
    public void setLabels(boolean on) { labels = on; invalidate(); }

    public boolean labelsOn() { return labels; }

    public void setTarget(Hit h) {
        target = h;
        if (h != null && hasSelf && h.lat != 0 && h.lon != 0) {
            double dist = Nav.dist(sLat, sLon, h.lat, h.lon);
            mpp = clamp(dist / (Math.min(getWidth(), getHeight()) * 0.34), 0.05, 40.0);
            panE = 0; panN = 0;
        }
        invalidate();
    }

    public Hit getTarget() { return target; }

    public void setSelf(double lat, double lon, double alt, float bearing, float speed, float acc) {
        hasSelf = true;
        sLat = lat; sLon = lon; sAlt = alt;
        sBear = bearing < 0 ? 0 : bearing;
        sSpeed = speed < 0 ? 0 : speed;
        sAcc = acc <= 0 ? 8 : acc;
        fixAge = System.currentTimeMillis();
        invalidate();
    }

    public void setRangeMeters(double m) {
        mpp = clamp(m / (Math.min(getWidth(), getHeight()) * 0.42), 0.05, 40.0);
        invalidate();
    }

    public double getRangeMeters() { return mpp * Math.min(getWidth(), getHeight()) * 0.42; }

    public void recenter() { panE = 0; panN = 0; invalidate(); }

    public void zoomBy(double factor) { mpp = clamp(mpp * factor, 0.05, 40.0); invalidate(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ticker.post(pulses);
    }

    @Override protected void onDetachedFromWindow() {
        ticker.removeCallbacks(pulses);
        super.onDetachedFromWindow();
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private int a(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private double cosLat() { return Math.max(0.15, Math.cos(Math.toRadians(hasSelf ? sLat : 39.5))); }

    private double camLat() { return (hasSelf ? sLat : 39.5) + panN / 111320.0; }
    private double camLon() { return (hasSelf ? sLon : -119.8) + panE / (111320.0 * cosLat()); }

    private float px(double lon) {
        double e = (lon - camLon()) * 111320.0 * cosLat();
        return (float) (getWidth() / 2.0 + e / mpp);
    }

    private float py(double lat) {
        double n = (lat - camLat()) * 111320.0;
        return (float) (getHeight() / 2.0 - n / mpp);
    }

    private int tierColor(int score) {
        if (score >= WifiFacts.TIER_CRIT) return cBad;
        if (score >= WifiFacts.TIER_HIGH) return cWarn;
        if (score >= WifiFacts.TIER_MED) return cAccent;
        return cGood;
    }

    @Override protected void onDraw(Canvas cv) {
        final int w = getWidth(), h = getHeight();
        cv.drawRect(0, 0, w, h, fillOf(cBg));

        drawGrid(cv, w, h);
        drawRings(cv, w, h);
        drawHits(cv, w, h);
        drawRoute(cv, w, h);
        drawSelf(cv, w, h);
        if (labels) drawLabels(cv, w, h);
        drawChrome(cv, w, h);
    }

    private Paint fillOf(int col) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(col);
        fill.setAlpha(255);
        return fill;
    }

    private void drawGrid(Canvas cv, int w, int h) {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1);
        line.setColor(a(cGrid, 70));
        int step = (int) (28 * d);
        for (int x = 0; x < w; x += step) cv.drawLine(x, 0, x, h, line);
        for (int y = 0; y < h; y += step) cv.drawLine(0, y, w, y, line);
    }

    private void drawRings(Canvas cv, int w, int h) {
        double[] nice = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 25000};
        double ring = nice[nice.length - 1];
        for (double n : nice) {
            if (n / mpp > 70 * d) { ring = n; break; }
        }
        float cx = w / 2f, cy = h / 2f;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1);
        txt.setStyle(Paint.Style.FILL);
        txt.setTextSize(8.5f * d);
        txt.setFakeBoldText(false);
        for (int i = 1; i <= 4; i++) {
            double m = ring * i;
            float r = (float) (m / mpp);
            if (r > Math.max(w, h)) break;
            line.setColor(a(cAccent, i == 4 ? 70 : 40));
            if (i == 4) line.setPathEffect(new DashPathEffect(new float[]{4 * d, 6 * d}, 0));
            else line.setPathEffect(null);
            cv.drawCircle(cx, cy, r, line);
            line.setPathEffect(null);
            txt.setColor(a(cDim, 190));
            cv.drawText(Nav.range(m), cx + 6 * d, cy - r + 10 * d, txt);
        }
        // north
        line.setColor(a(cAccent2, 200));
        line.setStrokeWidth(1.6f * d);
        cv.drawLine(w - 22 * d, 26 * d, w - 22 * d, 46 * d, line);
        txt.setColor(cAccent2);
        txt.setTextSize(9 * d);
        txt.setTextAlign(Paint.Align.CENTER);
        cv.drawText("N", w - 22 * d, 22 * d, txt);
        txt.setTextAlign(Paint.Align.LEFT);
    }

    private void drawHits(Canvas cv, int w, int h) {
        if (hits.isEmpty()) return;
        for (Hit hh : hits) {
            if (hh.lat == 0 && hh.lon == 0) continue;
            double[] p = pos(hh);
            if (p[0] == 0 && p[1] == 0) continue;
            float x = px(p[1]), y = py(p[0]);
            if (x < -30 || y < -30 || x > w + 30 || y > h + 30) continue;
            int col = tierColor(hh.score);

            // estimated node: uncertainty halo + tether back to where it was heard
            if (hasEst(hh)) {
                double unc = est.get(hh.bssid)[2];
                float ur = (float) (unc / mpp);
                if (ur > 3 * d) {
                    fill.setStyle(Paint.Style.FILL);
                    fill.setColor(a(col, 26));
                    cv.drawCircle(x, y, Math.min(ur, Math.min(w, h) * 0.5f), fill);
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(1f);
                    line.setPathEffect(new DashPathEffect(new float[]{3 * d, 4 * d}, 0));
                    line.setColor(a(col, 90));
                    cv.drawCircle(x, y, Math.min(ur, Math.min(w, h) * 0.5f), line);
                    line.setPathEffect(null);
                }
                float cx = px(hh.lon), cy = py(hh.lat);
                if (Math.hypot(cx - x, cy - y) > 6 * d) {
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(1f);
                    line.setColor(a(col, 70));
                    line.setPathEffect(new DashPathEffect(new float[]{2 * d, 5 * d}, 0));
                    cv.drawLine(cx, cy, x, y, line);
                    line.setPathEffect(null);
                    fill.setStyle(Paint.Style.FILL);
                    fill.setColor(a(col, 55));
                    cv.drawCircle(cx, cy, 2.0f * d, fill);
                }
            }

            float r = hh.score >= WifiFacts.TIER_CRIT ? 4.2f * d : 3.0f * d;
            if (hh.isPrime()) drawPrime(cv, x, y, r, col, hh);
            if (hh.tagged) {
                glow.setStyle(Paint.Style.STROKE);
                glow.setStrokeWidth(1.4f * d);
                glow.setColor(a(cAccent2, 200));
                cv.drawCircle(x, y, r + 4 * d, glow);
            }
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(a(col, 60));
            cv.drawCircle(x, y, r + 3.5f * d, fill);
            fill.setColor(col);
            cv.drawCircle(x, y, r, fill);
            if (target != null && !target.bssid.isEmpty() && target.bssid.equals(hh.bssid)) {
                glow.setStyle(Paint.Style.STROKE);
                glow.setStrokeWidth(2 * d);
                glow.setColor(cAccent2);
                cv.drawCircle(x, y, r + 8 * d, glow);
            }
        }
    }

    /**
     * Prime-target signature: two expanding shockwaves, a rotating diamond
     * bracket cage, and a hard blink on the core. Unmistakable at any zoom.
     */
    private void drawPrime(Canvas cv, float x, float y, float r, int col, Hit hh) {
        long now = System.currentTimeMillis();
        float phase = (now % 1400L) / 1400f;
        int hard = hh.isHardTarget() ? col : cWarn;

        line.setStyle(Paint.Style.STROKE);
        line.setPathEffect(null);
        for (int i = 0; i < 2; i++) {
            float p = (phase + i * 0.5f) % 1f;
            float rad = r + (5f + p * 26f) * d;
            int alpha = (int) (185 * (1f - p));
            line.setStrokeWidth((1.9f - p * 1.1f) * d);
            line.setColor(a(hard, Math.max(0, alpha)));
            cv.drawCircle(x, y, rad, line);
        }

        // rotating bracket cage
        float cage = r + 7.5f * d;
        float rot = (now % 3600L) / 3600f * 360f;
        line.setStrokeWidth(1.8f * d);
        line.setColor(a(hard, 230));
        for (int q = 0; q < 4; q++) {
            float base = rot + q * 90f;
            cv.drawArc(x - cage, y - cage, x + cage, y + cage,
                    base, 26f, false, line);
        }

        // core blink
        boolean on = (now / 260L) % 2 == 0;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(a(hard, on ? 245 : 120));
        cv.drawCircle(x, y, r * (on ? 1.15f : 0.85f), fill);

        if (hh.tagged || mpp < 0.9) {
            txt.setStyle(Paint.Style.FILL);
            txt.setTextSize(7.6f * d);
            txt.setFakeBoldText(true);
            txt.setColor(hard);
            txt.setTextAlign(Paint.Align.CENTER);
            cv.drawText(hh.primeTag(), x, y - (r + 13f * d), txt);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setFakeBoldText(false);
        }
    }

    private void drawRoute(Canvas cv, int w, int h) {
        if (target == null || target.lat == 0 && target.lon == 0) return;
        if (!hasSelf) return;
        double[] tp = pos(target);
        float sx = px(sLon), sy = py(sLat), tx = px(tp[1]), ty = py(tp[0]);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f * d);
        line.setColor(a(cAccent2, 210));
        line.setPathEffect(new DashPathEffect(new float[]{8 * d, 7 * d}, (float) (System.currentTimeMillis() / 45 % 1000)));
        cv.drawLine(sx, sy, tx, ty, line);
        line.setPathEffect(null);

        double dist = Nav.dist(sLat, sLon, tp[0], tp[1]);
        double brg = Nav.bearing(sLat, sLon, tp[0], tp[1]);

        // midpoint pill with range + bearing
        String mid = Nav.range(dist) + "  " + Nav.compass(brg);
        txt.setTextSize(9.5f * d);
        txt.setColor(cText);
        float tw = txt.measureText(mid);
        float mx = (sx + tx) / 2f, my = (sy + ty) / 2f;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(a(cPanel, 235));
        RectF box = new RectF(mx - tw / 2 - 7 * d, my - 17 * d, mx + tw / 2 + 7 * d, my - 1 * d);
        cv.drawRoundRect(box, 6 * d, 6 * d, fill);
        txt.setTextAlign(Paint.Align.CENTER);
        cv.drawText(mid, mx, my - 6 * d, txt);
        txt.setTextAlign(Paint.Align.LEFT);

        // off-screen chevron toward the target
        if (tx < 0 || ty < 0 || tx > w || ty > h) {
            float cx = w / 2f, cy = h / 2f;
            double ang = Math.atan2(ty - cy, tx - cx);
            float rad = Math.min(w, h) * 0.44f;
            float ex = cx + (float) Math.cos(ang) * rad;
            float ey = cy + (float) Math.sin(ang) * rad;
            Path p = new Path();
            p.moveTo(ex + (float) Math.cos(ang) * 12 * d, ey + (float) Math.sin(ang) * 12 * d);
            p.lineTo(ex + (float) Math.cos(ang + 2.5) * 12 * d, ey + (float) Math.sin(ang + 2.5) * 12 * d);
            p.close();
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(cAccent2);
            cv.drawPath(p, fill);
        }
    }

    private void drawSelf(Canvas cv, int w, int h) {
        if (!hasSelf) {
            txt.setColor(cDim);
            txt.setTextSize(11 * d);
            txt.setTextAlign(Paint.Align.CENTER);
            cv.drawText("AWAITING GPS FIX", w / 2f, h / 2f, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float x = px(sLon), y = py(sLat);
        float accR = (float) (sAcc / mpp);
        if (accR > 8 * d) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(a(cAccent, 26));
            cv.drawCircle(x, y, Math.min(accR, Math.min(w, h) * 0.6f), fill);
        }
        // heading cone
        double br = Math.toRadians(sBear);
        Path cone = new Path();
        cone.moveTo(x, y);
        cone.lineTo(x + (float) Math.sin(br - 0.36) * 46 * d, y - (float) Math.cos(br - 0.36) * 46 * d);
        cone.lineTo(x + (float) Math.sin(br) * 58 * d, y - (float) Math.cos(br) * 58 * d);
        cone.lineTo(x + (float) Math.sin(br + 0.36) * 46 * d, y - (float) Math.cos(br + 0.36) * 46 * d);
        cone.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(a(cAccent, 44));
        cv.drawPath(cone, fill);

        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(1.4f * d);
        glow.setColor(a(cAccent, 110));
        float pulse = (float) ((System.currentTimeMillis() % 2400) / 2400.0);
        cv.drawCircle(x, y, (12 + pulse * 34) * d, glow);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(cAccent);
        cv.drawCircle(x, y, 5.2f * d, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * d);
        line.setColor(Color.WHITE);
        cv.drawCircle(x, y, 5.2f * d, line);
    }

    /**
     * SSID labels with collision-aware placement.
     *
     * Every on-screen node is a dot until you can read its name. This walks the
     * visible set in priority order (tagged, then prime, then strongest score)
     * and tries eight anchor positions around each dot, skipping any that would
     * overlap a label already placed. Highest-priority names always win the
     * space; low-value nodes give up their label rather than turn the map into
     * unreadable mush. Labels are drawn as filled pills so they stay legible
     * over grid lines, rings and route strokes.
     */
    private void drawLabels(Canvas cv, int w, int h) {
        placed.clear();
        labelOrder.clear();

        int bottomGuard = (target != null && hasSelf) ? (int) (50 * d) : 0;
        int maxY = h - bottomGuard;
        if (maxY < 40 * d) maxY = h;

        // Reserve the top-left telemetry block so labels never sit under it.
        placed.add(new RectF(0, 0, 200 * d, 58 * d));
        // Reserve node-count corner.
        placed.add(new RectF(0, h - 24 * d, 110 * d, h));

        for (Hit hh : hits) {
            if (hh.lat == 0 && hh.lon == 0) continue;
            double[] pp = pos(hh);
            float x = px(pp[1]), y = py(pp[0]);
            if (x < 0 || y < 0 || x > w || y > h) continue;
            labelOrder.add(hh);
        }
        if (labelOrder.isEmpty()) return;

        final java.util.Comparator<Hit> pri = new java.util.Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (a.tagged != b.tagged) return a.tagged ? -1 : 1;
                boolean pa = a.isPrime(), pb = b.isPrime();
                if (pa != pb) return pa ? -1 : 1;
                if (a.score != b.score) return b.score - a.score;
                return b.rssi - a.rssi;
            }
        };
        java.util.Collections.sort(labelOrder, pri);

        // Zoom decides how much ink the map can carry.
        int budget = mpp < 0.9 ? 70 : mpp < 2.5 ? 44 : 24;

        int drawn = 0;
        for (Hit hh : labelOrder) {
            if (drawn >= budget) break;
            double[] pp = pos(hh);
            float x = px(pp[1]), y = py(pp[0]);
            int col = tierColor(hh.score);
            boolean strong = hh.tagged || hh.isPrime();
            if (strong) col = hh.isHardTarget() ? cBad : (hh.tagged ? cAccent2 : cWarn);

            String name = hh.ssidOrGhost();
            if (name.length() > 20) name = name.substring(0, 19) + "\u2026";

            float sp = (strong ? 8.6f : 7.9f) * d;
            txt.setStyle(Paint.Style.FILL);
            txt.setFakeBoldText(strong);
            txt.setTextSize(sp);
            txt.setTextAlign(Paint.Align.LEFT);
            float tw = txt.measureText(name);
            float padX = 4.5f * d;
            float bw = tw + padX * 2;
            float bh = sp + 6f * d;
            float r = (hh.score >= WifiFacts.TIER_CRIT ? 4.2f : 3.0f) * d;
            float gap = r + 3.5f * d;

            // eight candidate anchors, best first
            float[][ ] cand = new float[][]{
                    {x + gap,               y - bh / 2f},
                    {x - gap - bw,          y - bh / 2f},
                    {x - bw / 2f,           y - gap - bh},
                    {x - bw / 2f,           y + gap},
                    {x + gap * 0.8f,        y - gap - bh},
                    {x - gap * 0.8f - bw,   y - gap - bh},
                    {x + gap * 0.8f,        y + gap * 0.6f},
                    {x - gap * 0.8f - bw,   y + gap * 0.6f}
            };

            boolean ok = false;
            float bx = 0, by = 0;
            for (float[] cnd : cand) {
                float l = cnd[0], t = cnd[1];
                if (l < 2 * d || t < 2 * d || l + bw > w - 2 * d || t + bh > maxY) continue;
                box.set(l, t, l + bw, t + bh);
                if (hitsRect(box)) continue;
                bx = l; by = t; ok = true;
                break;
            }
            if (!ok) continue;

            // pill
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(a(cPanel, 236));
            RectF pill = new RectF(bx, by, bx + bw, by + bh);
            cv.drawRoundRect(pill, 4 * d, 4 * d, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1f);
            line.setPathEffect(null);
            line.setColor(a(col, strong ? 220 : 120));
            cv.drawRoundRect(pill, 4 * d, 4 * d, line);

            // leader tick when the pill floats far from its dot
            if (Math.abs((by + bh / 2f) - y) > bh * 0.9f || Math.abs((bx + bw / 2f) - x) > bw * 0.9f) {
                line.setColor(a(col, strong ? 150 : 80));
                float ex = Math.max(bx, Math.min(x, bx + bw));
                float ey = Math.max(by, Math.min(y, by + bh));
                cv.drawLine(x, y, ex, ey, line);
            }

            txt.setColor(strong ? col : a(cText, 235));
            cv.drawText(name, bx + padX, by + bh - 4.2f * d, txt);

            placed.add(new RectF(pill));
            drawn++;
        }
        txt.setFakeBoldText(false);
        txt.setTextAlign(Paint.Align.LEFT);
    }

    private boolean hitsRect(RectF r) {
        for (RectF p : placed) {
            if (RectF.intersects(r, p)) return true;
        }
        return false;
    }

    private void drawChrome(Canvas cv, int w, int h) {
        txt.setFakeBoldText(true);
        txt.setTextSize(8.5f * d);
        txt.setColor(a(cAccent, 220));
        cv.drawText("HUD \u00b7 LIVE", 12 * d, 18 * d, txt);
        txt.setFakeBoldText(false);

        txt.setTextSize(8 * d);
        txt.setColor(cDim);
        String pos = hasSelf
                ? String.format("%.5f, %.5f", sLat, sLon)
                : "no fix";
        cv.drawText(pos, 12 * d, 30 * d, txt);

        if (hasSelf) {
            String hdg = Nav.compass(sBear) + "   " + String.format("%.1f m/s", sSpeed);
            cv.drawText(hdg, 12 * d, 42 * d, txt);
            if (fixAge > 0 && System.currentTimeMillis() - fixAge > 20000) {
                txt.setColor(cWarn);
                cv.drawText("STALE FIX " + ((System.currentTimeMillis() - fixAge) / 1000) + "s", 12 * d, 54 * d, txt);
            }
        }

        // scale bar bottom-right
        double[] nice = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};
        double bar = nice[0];
        for (double n : nice) { if (n / mpp < w * 0.30) bar = n; }
        float bw = (float) (bar / mpp);
        float bx = w - bw - 12 * d, by = h - 14 * d;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * d);
        line.setColor(a(cText, 200));
        cv.drawLine(bx, by, bx + bw, by, line);
        cv.drawLine(bx, by - 4 * d, bx, by + 4 * d, line);
        cv.drawLine(bx + bw, by - 4 * d, bx + bw, by + 4 * d, line);
        txt.setColor(a(cDim, 220));
        txt.setTextSize(8 * d);
        txt.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(Nav.range(bar), bx + bw, by - 7 * d, txt);
        txt.setTextAlign(Paint.Align.LEFT);

        // node count bottom-left
        txt.setColor(a(cDim, 200));
        cv.drawText(hits.size() + " NODES", 12 * d, h - 12 * d, txt);

        if (target == null || !hasSelf) return;
        // guidance strip
        double[] gp = pos(target);
        double dist = Nav.dist(sLat, sLon, gp[0], gp[1]);
        double brg = Nav.bearing(sLat, sLon, gp[0], gp[1]);
        String g = dist < 12 ? "ARRIVED \u00b7 TARGET IN SIGHT" : Nav.guidance(brg, sBear);
        int gcol = dist < 12 ? cGood : cAccent2;

        float stripH = 46 * d;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(a(cPanel, 240));
        cv.drawRect(0, h - stripH, w, h, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1);
        line.setColor(a(gcol, 120));
        cv.drawLine(0, h - stripH, w, h - stripH, line);

        txt.setFakeBoldText(true);
        txt.setTextSize(13 * d);
        txt.setColor(gcol);
        cv.drawText(g, 12 * d, h - stripH + 19 * d, txt);
        txt.setFakeBoldText(false);

        txt.setTextSize(8.5f * d);
        txt.setColor(cText);
        String l2 = Nav.range(dist) + " \u00b7 " + Nav.compass(brg) + " \u00b7 WALK " +
                Nav.eta(dist, 1.35) + " \u00b7 DRIVE " + Nav.eta(dist, 11.0);
        cv.drawText(l2, 12 * d, h - stripH + 33 * d, txt);

        txt.setColor(a(cDim, 230));
        txt.setTextSize(8 * d);
        String nm = target.ssidOrGhost();
        if (nm.length() > 26) nm = nm.substring(0, 26) + "\u2026";
        txt.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(nm, w - 12 * d, h - stripH + 19 * d, txt);
        txt.setTextAlign(Paint.Align.LEFT);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDet.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); dragging = true; moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && e.getPointerCount() == 1 && !scaleDet.isInProgress()) {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dx) > 3 * d || Math.abs(dy) > 3 * d) moved = true;
                    panE -= dx * mpp;
                    panN += dy * mpp;
                    downX = e.getX(); downY = e.getY();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (!moved && e.getActionMasked() == MotionEvent.ACTION_UP) pick(e.getX(), e.getY());
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void pick(float x, float y) {
        if (tapCb == null) return;
        Hit best = null;
        float bd = 34 * d;
        for (Hit hh : hits) {
            if (hh.lat == 0 && hh.lon == 0) continue;
            double[] pp = pos(hh);
            float dx = px(pp[1]) - x, dy = py(pp[0]) - y;
            float dd = (float) Math.hypot(dx, dy);
            if (dd < bd) { bd = dd; best = hh; }
        }
        if (best != null) tapCb.onTarget(best);
    }
}
