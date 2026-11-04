package com.aegispilot.wardrive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Channel occupancy — the view a wardriver actually uses to pick a lane.
 *
 * Every observed AP is bucketed by channel and stacked by severity:
 *   accent = encrypted and sane, warn = WPS-exposed, bad = OPEN or WEP.
 * One row per band, so 2.4 GHz congestion does not visually drown the handful
 * of 6 GHz radios. Bars are scaled against the busiest channel on screen.
 */
public class SpectrumView extends View {

    private final int cBg, cGrid, cText, cDim, cAccent, cAccent2, cWarn, cBad, cPanel;
    private final float d;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Hit> hits = new ArrayList<>();
    private final Map<Integer, int[]> buckets = new HashMap<>();   // ch -> {secure, warn, bad}
    private int peak = 1;

    public SpectrumView(Context c, Theme th) {
        super(c);
        cBg = th.bg;
        cPanel = th.panel;
        cGrid = th.edge;
        cText = th.text;
        cDim = th.dim;
        cAccent = th.accent;
        cAccent2 = th.accent2;
        cWarn = th.warn;
        cBad = th.bad;
        d = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setHits(List<Hit> l) {
        hits.clear();
        if (l != null) hits.addAll(l);
        rebuild();
        invalidate();
    }

    private void rebuild() {
        buckets.clear();
        for (Hit h : hits) {
            if (h.channel <= 0) continue;
            int[] b = buckets.get(h.channel);
            if (b == null) { b = new int[3]; buckets.put(h.channel, b); }
            if (h.isOpen() || h.isWep()) b[2]++;
            else if (h.wps || h.wpsGuess) b[1]++;
            else b[0]++;
        }
        peak = 1;
        for (int[] b : buckets.values()) {
            int t = b[0] + b[1] + b[2];
            if (t > peak) peak = t;
        }
    }

    private int a(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        cv.drawRect(0, 0, w, h, paint(fill, cBg));

        txt.setStyle(Paint.Style.FILL);
        txt.setFakeBoldText(true);
        txt.setTextSize(9 * d);
        txt.setColor(a(cAccent, 230));
        cv.drawText("CHANNEL OCCUPANCY", 12 * d, 18 * d, txt);
        txt.setFakeBoldText(false);

        if (buckets.isEmpty()) {
            txt.setTextSize(10 * d);
            txt.setColor(cDim);
            txt.setTextAlign(Paint.Align.CENTER);
            cv.drawText("NO CHANNEL DATA YET", w / 2f, h / 2f, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            return;
        }

        // rows: 2.4, 5, 6
        List<Integer> b24 = new ArrayList<>(), b5 = new ArrayList<>(), b6 = new ArrayList<>();
        for (Integer ch : buckets.keySet()) {
            if (ch <= 14) b24.add(ch);
            else if (ch < 181) b5.add(ch);
            else b6.add(ch);
        }
        Collections.sort(b24);
        Collections.sort(b5);
        Collections.sort(b6);

        float top = 32 * d;
        float rowH = (h - top - 30 * d) / 3f;
        if (rowH < 40 * d) rowH = 40 * d;

        drawRow(cv, "2.4G", b24, top, rowH, w);
        drawRow(cv, "5G", b5, top + rowH, rowH, w);
        drawRow(cv, "6G", b6, top + rowH * 2, rowH, w);

        drawLegend(cv, w, h);
    }

    private void drawRow(Canvas cv, String name, List<Integer> chans, float top, float rowH, int w) {
        float labelW = 34 * d;
        float baseY = top + rowH - 8 * d;
        float maxBar = rowH - 26 * d;

        txt.setTextSize(7.5f * d);
        txt.setColor(a(cDim, 220));
        txt.setTextAlign(Paint.Align.LEFT);
        cv.drawText(name, 8 * d, top + 12 * d, txt);

        // baseline
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1);
        line.setPathEffect(null);
        line.setColor(a(cGrid, 140));
        cv.drawLine(labelW, baseY, w - 8 * d, baseY, line);

        if (chans.isEmpty()) {
            txt.setColor(a(cDim, 120));
            txt.setTextSize(7 * d);
            cv.drawText("none", labelW + 6 * d, baseY - 4 * d, txt);
            return;
        }

        float avail = w - labelW - 12 * d;
        float slot = avail / chans.size();
        float bw = Math.min(20 * d, slot * 0.62f);

        for (int i = 0; i < chans.size(); i++) {
            int ch = chans.get(i);
            int[] b = buckets.get(ch);
            int total = b[0] + b[1] + b[2];
            float cx = labelW + slot * i + slot / 2f;
            float hh = (total / (float) peak) * maxBar;
            if (hh < 3 * d) hh = 3 * d;

            float y = baseY;
            // stacked from the baseline up: secure, then wps, then open/wep
            float seg = hh * (b[0] / (float) total);
            if (seg > 0) { cv.drawRect(cx - bw / 2, y - seg, cx + bw / 2, y, paint(fill, a(cAccent, 220))); y -= seg; }
            seg = hh * (b[1] / (float) total);
            if (seg > 0) { cv.drawRect(cx - bw / 2, y - seg, cx + bw / 2, y, paint(fill, a(cWarn, 230))); y -= seg; }
            seg = hh * (b[2] / (float) total);
            if (seg > 0) { cv.drawRect(cx - bw / 2, y - seg, cx + bw / 2, y, paint(fill, a(cBad, 240))); y -= seg; }

            // channel number, only when the slot can carry it
            if (slot > 15 * d) {
                txt.setTextSize(6.4f * d);
                txt.setColor(a(cDim, 200));
                txt.setTextAlign(Paint.Align.CENTER);
                cv.drawText(String.valueOf(ch), cx, baseY + 9 * d, txt);
                txt.setTextAlign(Paint.Align.LEFT);
            }
        }

        // busiest-channel readout on the right of the row label
        txt.setTextSize(6.8f * d);
        txt.setColor(a(cDim, 170));
        txt.setTextAlign(Paint.Align.RIGHT);
        cv.drawText("peak " + peak, w - 10 * d, top + 12 * d, txt);
        txt.setTextAlign(Paint.Align.LEFT);
    }

    private void drawLegend(Canvas cv, int w, int h) {
        float y = h - 10 * d;
        float x = 10 * d;
        txt.setTextSize(6.8f * d);
        txt.setTextAlign(Paint.Align.LEFT);
        String[] labels = {"SECURE", "WPS?", "OPEN/WEP"};
        int[] colors = {cAccent, cWarn, cBad};
        for (int i = 0; i < labels.length; i++) {
            cv.drawRect(x, y - 7 * d, x + 7 * d, y, paint(fill, colors[i]));
            txt.setColor(a(cDim, 210));
            cv.drawText(labels[i], x + 10 * d, y, txt);
            x += 10 * d + txt.measureText(labels[i]) + 12 * d;
        }
        txt.setColor(a(cAccent2, 200));
        txt.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(hits.size() + " NODES", w - 10 * d, y, txt);
        txt.setTextAlign(Paint.Align.LEFT);
    }

    private Paint paint(Paint p, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }
}
