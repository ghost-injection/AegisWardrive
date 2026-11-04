package com.aegispilot.wardrive;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.location.Location;
import android.location.LocationListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.InputStream;
import java.util.Date;
import java.text.SimpleDateFormat;

public class MainActivity extends Activity implements WardriveService.Listener {

    private static final int REQ_PERM = 2001;

    private Theme th;
    private Db db;
    private LinearLayout root, nav, feed;
    private FrameLayout bodyFrame;

    // HUD map state
    private MapView mapView;
    private TextView mapGpsLabel;
    private double lastLat, lastLon, lastAlt;
    private float lastBear, lastSpeed, lastAcc = 8;
    private boolean hasFix;
    private long lastNodePull;
    private boolean fixing;
    private static final int REQ_CSV = 9103;
    private volatile boolean csvCancel;
    private LinearLayout settingsBox, shellWrap;
    private boolean settingsOpen = false;
    private TextView footLabel;
    private RadarView radar;
    private TextView radarChip, mapChip;
    private FrameLayout scanStage;
    private boolean sweepMap = false;

    /* ---- navigation to a target ---- */
    private LinearLayout navStrip;
    private TextView navArrow, navDist, navInfo;
    private Hit navTarget;
    /** Set when the operator picks a row in TARGETS; consumed by buildMap(). */
    private Hit pendingMapTarget;
    private long navTargetLoad;
    private boolean inRangeAnnounced;
    private static final double IN_RANGE_M = 15.0;
    private static final int IN_RANGE_RSSI = -52;
    private TextView statusDot, bigBtn, stUnique, stOpen, stWep, stGps, stPrime, stHidden, feedEmpty, exportPathLabel;
    private int lastPrimeCount = -1;
    private int screen = 0;
    private int lastFeedCount = -1;
    private int lastLiveCount = 0;
    private CrtOverlay crt;
    private TextView titleView;
    private Uri lastExport;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (screen == 0) refreshLive();
            refreshBigBtn();
            updateNav();
            if (th.animated) {
                th.recolor(th.hueNow());
                root.setBackground(Skin.shell(MainActivity.this, th, 24));
                if (radar != null) radar.invalidate();
            }
            ui.postDelayed(this, th.animated ? 220 : 900);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        th = Theme.byId(prefs().getString("theme", "tesla"));
        db = Db.get(this);
        getWindow().setStatusBarColor(th.bg);
        getWindow().setNavigationBarColor(th.bg);

        root = Ui.col(this);
        root.setBackground(Skin.shell(this, th, 24));
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 10));

        root.addView(header(), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        settingsBox = settingsPanel();
        settingsBox.setVisibility(View.GONE);
        root.addView(settingsBox, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bodyFrame = new FrameLayout(this);
        root.addView(bodyFrame, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(footer(), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        shellWrap = Ui.col(this);
        shellWrap.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        shellWrap.addView(root, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        applyInsets(shellWrap);
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(th.bg);
        stage.addView(shellWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        crt = new CrtOverlay(this, th);
        stage.addView(crt, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(stage);
        show(0);
        startFixes();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * A tapped prime-target notification carries target_bssid. Until now that
     * extra was written and never read, so the tap just opened the app. This
     * arms navigation straight onto the node that fired the alert.
     */
    private void handleIntent(Intent it) {
        if (it == null) return;
        final String b = it.getStringExtra("target_bssid");
        if (b == null || b.isEmpty()) return;
        // Radios and adb hand out lowercase; the ledger stores uppercase.
        hudTargetBssid = b.trim().toUpperCase(Locale.US);
        navTarget = null;
        navTargetLoad = 0;
        inRangeAnnounced = false;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (screen != 0) show(0);
                updateNav();
            }
        }, 350);
    }

    private void applyInsets(final View v) {
        final int pad = Ui.dp(this, 10);
        if (Build.VERSION.SDK_INT >= 30) {
            v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(View x, android.view.WindowInsets ins) {
                    android.graphics.Insets i = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                    x.setPadding(pad, i.top + pad, pad, i.bottom + pad);
                    return ins;
                }
            });
        } else {
            v.setPadding(pad, Ui.dp(this, 30), pad, pad);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        WardriveService.setListener(this);
        if (crt != null) crt.setActive(true);
        if (prefs().getBoolean("keepon", true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        WardriveService.setListener(null);
        if (crt != null) crt.setActive(false);
        ui.removeCallbacks(ticker);
    }

    @Override public void onBackPressed() {
        if (screen != 0) show(0);
        else super.onBackPressed();
    }

    @Override public void onHits(int total) {
        if (screen == 0) refreshLive();
    }

    // ---------------------------------------------------------------- chrome

    private View header() {
        LinearLayout h = Ui.col(this);

        // ── micro rail ────────────────────────────────────────────────
        LinearLayout micro = Ui.row(this);
        micro.setGravity(Gravity.CENTER_VERTICAL);
        TextView kicker = Ui.label(this, "A E G I S  /  W A R D R I V E   S Y S T E M S", th.muted, 7.5f);
        kicker.setLetterSpacing(0.16f);
        micro.addView(kicker, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cyc = Skin.iconButton(this, th, "\u25C8");
        cyc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTheme(); }
        });
        LinearLayout.LayoutParams cycLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cycLp.rightMargin = Ui.dp(this, 6);
        micro.addView(cyc, cycLp);

        TextView gear = Skin.iconButton(this, th, "\u2261");
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSettings(); }
        });
        micro.addView(gear);
        h.addView(micro);

        // ── brand card ────────────────────────────────────────────────
        LinearLayout brand = Ui.col(this);
        brand.setBackground(Skin.brand(this, th));
        brand.setPadding(Ui.dp(this, 16), Ui.dp(this, 13),
                Ui.dp(this, 16), Ui.dp(this, 13));
        LinearLayout.LayoutParams brandLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        brandLp.topMargin = Ui.dp(this, 9);

        titleView = Ui.text(this, "WARDRIVE", th.bright, 30f, true);
        titleView.setLetterSpacing(0.10f);
        brand.addView(titleView);

        TextView acc = Ui.label(this, "SWEEP THE SPECTRUM.", th.accent, 10.5f);
        acc.setLetterSpacing(0.14f);
        acc.setPadding(0, Ui.dp(this, 2), 0, 0);
        brand.addView(acc);

        TextView sub = Ui.text(this, "EVERY SIGNAL LEAVES A SCAR. LOCAL CAPTURE ONLY.",
                th.muted, 7.8f, false);
        sub.setLetterSpacing(0.04f);
        sub.setPadding(0, Ui.dp(this, 5), 0, 0);
        brand.addView(sub);
        h.addView(brand, brandLp);

        // ── gradient rule ─────────────────────────────────────────────
        View rule = new View(this);
        rule.setBackground(Skin.rule(this, th));
        LinearLayout.LayoutParams ruleLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2));
        ruleLp.topMargin = Ui.dp(this, 12);
        h.addView(rule, ruleLp);

        // ── mode row ──────────────────────────────────────────────────
        nav = Ui.row(this);
        LinearLayout.LayoutParams navLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.topMargin = Ui.dp(this, 10);
        h.addView(nav, navLp);
        buildNav();
        return h;
    }

    private void buildNav() {
        nav.removeAllViews();
        // Four primary lanes only. Toggles live in the appearance panel, where
        // a phone-width row has room to breathe instead of wrapping to a second line.
        String[] labels = {"SWEEP", "TARGETS", "MAP", "BANDS", "EXPORT", "SKIN"};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView cell = Ui.chip(this, labels[i], th, idx == screen);
            // Six lanes on a phone width: let the text auto-shrink rather than clip.
            Ui.fit(cell, 5.5f, 10f);
            cell.setPadding(Ui.dp(this, 2), Ui.dp(this, 9), Ui.dp(this, 2), Ui.dp(this, 9));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (screen != idx) show(idx); }
            });
            LinearLayout.LayoutParams lp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(this, idx == labels.length - 1 ? 0 : 3);
            nav.addView(cell, lp);
        }
    }

    /** Label + ON/OFF pill. Writes the pref, repaints the pill, never rebuilds the tree. */
    private View toggleRow(String label, boolean on, final String key, final boolean def) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 1));

        TextView t = Ui.label(this, label, th.muted, 9f);
        t.setSingleLine(true);
        r.addView(t, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView pill = Ui.chip(this, on ? "ON" : "OFF", th, on);
        pill.setMinimumWidth(Ui.dp(this, 54));
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !prefs().getBoolean(key, def);
                prefs().edit().putBoolean(key, now).apply();
                pill.setText(now ? "ON" : "OFF");
                pill.setTextColor(now ? th.bright : th.muted);
                pill.setBackground(Skin.pill(MainActivity.this, th, now));
                if ("keepon".equals(key)) {
                    if (now) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                if (crt != null) crt.invalidate();
            }
        });
        r.addView(pill);
        return r;
    }

    private void cycleTheme() {
        int at = 0;
        for (int i = 0; i < Theme.ALL.length; i++) if (Theme.ALL[i].id.equals(th.id)) at = i;
        Theme next = Theme.ALL[(at + 1) % Theme.ALL.length];
        prefs().edit().putString("theme", next.id).apply();
        toast(next.name);
        recreate();
    }

    private void toggleSettings() {
        settingsOpen = !settingsOpen;
        settingsBox.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
        if (crt != null) crt.burst(0.4f);
    }

    /** Tesla Life appearance panel: glass, effects, motion, cycle speed. */
    private LinearLayout settingsPanel() {
        LinearLayout p = Ui.col(this);
        p.setBackground(Skin.card(this, th));
        p.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.label(this, "APPEARANCE ENGINE", th.accent, 8.5f),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView reset = Ui.label(this, "RESET", th.dim, 8f);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs().edit().putInt("glass", Skin.GLASS_DEF)
                        .putInt("fx", Skin.FX_DEF)
                        .putInt("speed", Skin.SPEED_DEF)
                        .putBoolean("motion", true).apply();
                recreate();
            }
        });
        head.addView(reset);
        p.addView(head);

        footLabel = Ui.text(this, "", th.muted, 8f, false);
        footLabel.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 2));
        p.addView(footLabel);

        Skin.slider(this, th, p, "GLASS OPACITY", 0, 95, Skin.glass(this),
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                        prefs().edit().putInt("glass", prog).apply();
                        root.setBackground(Skin.shell(MainActivity.this, th, 24));
                        statusLine();
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                });

        Skin.slider(this, th, p, "EFFECT INTENSITY", 1, 3, Skin.fx(this),
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                        prefs().edit().putInt("fx", prog + 1).apply();
                        if (crt != null) crt.invalidate();
                        statusLine();
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                });

        Skin.slider(this, th, p, "RGB CYCLE SPEED", 4, 30, Skin.speedSeconds(this),
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                        prefs().edit().putInt("speed", prog + 4).apply();
                        statusLine();
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                });

        View rule = new View(this);
        rule.setBackground(Skin.rule(this, th));
        LinearLayout.LayoutParams rlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        rlp.topMargin = Ui.dp(this, 6);
        rlp.bottomMargin = Ui.dp(this, 8);
        p.addView(rule, rlp);

        p.addView(toggleRow("AMBIENT MOTION", Skin.motion(this), "motion", true));
        p.addView(toggleRow("KEEP SCREEN AWAKE", prefs().getBoolean("keepon", true), "keepon", true));
        statusLine();
        return p;
    }

    private void statusLine() {
        if (footLabel == null) return;
        footLabel.setText(th.name + "  ·  GLASS " + Skin.glass(this) + "%"
                + "  ·  FX " + Skin.fx(this) + "/3"
                + "  ·  RGB " + Skin.speedSeconds(this) + "s");
    }

    private View footer() {
        LinearLayout f = Ui.col(this);
        View rule = new View(this);
        rule.setBackground(Skin.rule(this, th));
        f.addView(rule, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));

        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 1));
        final String baseName = th.name;
        final TextView skinName = Ui.label(this, baseName + "  ·  " + th.tagline, th.dim, 7.5f);
        if ("tesla".equals(th.id)) {
            final Handler beat = new Handler(Looper.getMainLooper());
            skinName.setText(baseName + "  ·  " + Sys.status(this));
            beat.post(new Runnable() {
                @Override public void run() {
                    if (!skinName.isAttachedToWindow()) return;
                    skinName.setText(baseName + "  ·  " + Sys.status(MainActivity.this));
                    beat.postDelayed(this, 4000L);
                }
            });
        }
        r.addView(skinName, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusDot = Ui.label(this, "IDLE", th.dim, 8f);
        r.addView(statusDot);
        f.addView(r);
        return f;
    }

    private void show(int s) {
        screen = s;
        bodyFrame.removeAllViews();
        switch (s) {
            case 0: bodyFrame.addView(buildScan()); break;
            case 1: bodyFrame.addView(buildHits()); break;
            case 2: bodyFrame.addView(buildMap()); break;
            case 3: bodyFrame.addView(buildBands()); break;
            case 4: bodyFrame.addView(buildExport()); break;
            default: bodyFrame.addView(buildThemes()); break;
        }
        buildNav();
        if (crt != null) crt.burst(0.5f);
        if (s == 0) refreshLive();
    }

    // ---------------------------------------------------------------- SCAN

    private View buildScan() {
        LinearLayout v = Ui.col(this);

        // ---- RADAR / MAP stage: same data, two ways of seeing it ----
        LinearLayout modeRow = Ui.row(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 4));

        radarChip = Ui.chip(this, "RADAR", th, !sweepMap);
        radarChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (!sweepMap) return;
                sweepMap = false;
                buildStage();
            }
        });
        LinearLayout.LayoutParams rlp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.rightMargin = Ui.dp(this, 6);
        modeRow.addView(radarChip, rlp);

        mapChip = Ui.chip(this, "MAP", th, sweepMap);
        mapChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (sweepMap) return;
                sweepMap = true;
                buildStage();
            }
        });
        modeRow.addView(mapChip, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        v.addView(modeRow);

        scanStage = new FrameLayout(this);
        v.addView(scanStage, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildStage();

        LinearLayout stats = Ui.row(this);
        stats.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 4));
        stUnique = statCell(stats, "NODES");
        stOpen = statCell(stats, "OPEN");
        stWep = statCell(stats, "WEP");
        stPrime = statCell(stats, "PRIME");
        stHidden = statCell(stats, "HIDDEN");
        stGps = statCell(stats, "GPS");
        v.addView(stats);

        // ---- navigation strip: live bearing + range to the selected target ----
        navStrip = Ui.card(this, th);
        navStrip.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        navStrip.setVisibility(View.GONE);
        LinearLayout navRow = Ui.row(this);

        navArrow = Ui.text(this, "\u25B2", th.accent2, 22f, true);
        navArrow.setGravity(Gravity.CENTER);
        navRow.addView(navArrow, Ui.lp(Ui.dp(this, 40), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout navCol = Ui.col(this);
        navDist = Ui.text(this, "--", th.text, 13f, true);
        navInfo = Ui.text(this, "", th.dim, 7.5f, false);
        navCol.addView(navDist);
        navCol.addView(navInfo);
        navRow.addView(navCol, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView navX = Ui.text(this, "\u2715", th.dim, 12f, false);
        navX.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        navX.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                hudTargetBssid = null;
                navTarget = null;
                inRangeAnnounced = false;
                if (mapView != null) mapView.setTarget(null);
                updateNav();
                toast("NAVIGATION CLEARED");
            }
        });
        navRow.addView(navX);

        navStrip.addView(navRow);
        v.addView(navStrip);

        LinearLayout feedCard = Ui.card(this, th);
        feedCard.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        TextView fh = Ui.label(this, "LIVE FEED", th.dim, 8f);
        feedCard.addView(fh);
        feed = Ui.col(this);
        feed.setPadding(0, Ui.dp(this, 5), 0, 0);
        feedCard.addView(feed);
        LinearLayout feedWrap = Ui.col(this);
        feedWrap.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 6));
        feedWrap.addView(feedCard, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.addView(feedWrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btnWrap = Ui.col(this);
        btnWrap.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        bigBtn = Ui.btn(this, "\u25B6  START SWEEP", th, true);
        bigBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v2) { toggle(); }
        });
        btnWrap.addView(bigBtn, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.addView(btnWrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshBigBtn();
        return v;
    }

    /**
     * Swap the sweep stage between the polar radar and the geo map without
     * leaving the screen. The radar shows bearing-by-signal; the map shows the
     * multilaterated transmitter position on real ground coordinates.
     */
    private void buildStage() {
        if (scanStage == null) return;
        scanStage.removeAllViews();
        if (radarChip != null && mapChip != null) {
            radarChip.setTextColor(!sweepMap ? th.bright : th.muted);
            radarChip.setBackground(Skin.pill(this, th, !sweepMap));
            mapChip.setTextColor(sweepMap ? th.bright : th.muted);
            mapChip.setBackground(Skin.pill(this, th, sweepMap));
        }
        if (sweepMap) {
            radar = null;
            mapView = new MapView(this, th);
            mapView.setLabels(prefs().getBoolean("labels", true));
            try { mapView.setEstimates(db.estAll()); } catch (Throwable ignored) { }
            mapView.setOnTargetTap(new MapView.OnTargetTap() {
                @Override public void onTarget(Hit h) { openTarget(h); }
            });
            if (hasFix) mapView.setSelf(lastLat, lastLon, lastAlt, lastBear, lastSpeed, lastAcc);
            scanStage.addView(mapView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            refreshMapNodes();
            restoreTarget();
        } else {
            mapView = null;
            radar = new RadarView(this, th);
            radar.setHits(WardriveService.snapshot());
            radar.setActive(WardriveService.isRunning());
            scanStage.addView(radar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private TextView statCell(LinearLayout parent, String label) {
        LinearLayout cell = Ui.col(this);
        cell.setGravity(Gravity.CENTER);
        TextView num = Ui.text(this, "0", th.accent, 17f, true);
        num.setGravity(Gravity.CENTER);
        TextView lab = Ui.label(this, label, th.dim, 7.5f);
        lab.setGravity(Gravity.CENTER);
        cell.addView(num);
        cell.addView(lab);
        parent.addView(cell, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return num;
    }

    private void refreshLive() {
        List<Hit> s = WardriveService.snapshot();
        int open = 0, wep = 0, prime = 0, hidden = 0;
        for (Hit h : s) {
            if (h.isOpen()) open++;
            if (h.isWep()) wep++;
            if (h.isPrime()) prime++;
            if (h.isMasked()) hidden++;
        }
        if (stUnique != null) stUnique.setText(String.valueOf(s.size()));
        if (stOpen != null) stOpen.setText(String.valueOf(open));
        if (stWep != null) stWep.setText(String.valueOf(wep));
        if (stHidden != null) {
            stHidden.setText(String.valueOf(hidden));
            stHidden.setTextColor(hidden > 0 ? th.accent2 : th.accent);
        }
        if (stPrime != null) {
            stPrime.setText(String.valueOf(prime));
            stPrime.setTextColor(prime > 0 ? th.bad : th.accent);
        }
        if (stGps != null) {
            stGps.setText(WardriveService.hasFix() ? "LOCK" : "--");
            stGps.setTextColor(WardriveService.hasFix() ? th.good : th.dim);
        }
        if (radar != null) {
            radar.setHits(s);
            radar.setActive(WardriveService.isRunning());
        }
        if (sweepMap && mapView != null) {
            long now = System.currentTimeMillis();
            if (now - lastNodePull > 4000) {
                lastNodePull = now;
                refreshMapNodes();
                if (hasFix) mapView.setSelf(lastLat, lastLon, lastAlt, lastBear, lastSpeed, lastAcc);
            }
        }
        if (s.size() > lastLiveCount) {
            if (lastLiveCount > 0) {
                if (crt != null) crt.burst(0.85f);
                pulse(stUnique);
            }
            lastLiveCount = s.size();
        }
        if (prime > lastPrimeCount) {
            if (lastPrimeCount >= 0) {
                if (crt != null) crt.burst(1.0f);
                pulse(stPrime);
                vibrateTarget();
                if (titleView != null) titleView.setTextColor(th.bad);
                ui.postDelayed(new Runnable() {
                    @Override public void run() { if (titleView != null) titleView.setTextColor(th.accent); }
                }, 220L);
            }
            lastPrimeCount = prime;
        }
        if (feed != null && s.size() != lastFeedCount) {
            lastFeedCount = s.size();
            rebuildFeed(s);
        }
        if (Math.random() < 0.16) jitterTitle();
    }

    private void vibrateTarget() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(VibrationEffect.createWaveform(
                        new long[]{0, 70, 50, 140}, -1));
            } else {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(new long[]{0, 70, 50, 140}, -1);
            }
        } catch (Exception ignored) {}
    }

    private void pulse(View v) {
        if (v == null) return;
        v.setScaleX(1f); v.setScaleY(1f);
        v.animate().scaleX(1.22f).scaleY(1.22f).setDuration(90).withEndAction(new Runnable() {
            @Override public void run() {
                if (v == null) return;
                v.animate().scaleX(1f).scaleY(1f).setDuration(170).start();
            }
        }).start();
    }

    private void jitterTitle() {
        if (titleView == null) return;
        float dx = (float) (Math.random() * 3.0 - 1.5);
        titleView.setTranslationX(Ui.dp(this, dx));
        titleView.setTextColor(Math.random() < 0.35 ? th.accent2 : th.accent);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (titleView == null) return;
                titleView.setTranslationX(0f);
                titleView.setTextColor(th.accent);
            }
        }, 55);
    }

    private void rebuildFeed(List<Hit> s) {
        feed.removeAllViews();
        if (s.isEmpty()) {
            TextView e = Ui.label(this, "AWAITING PACKETS // PRESS START", th.dim, 8.5f);
            e.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            feed.addView(e);
            return;
        }
        // Prime targets are pinned above the rolling feed so they never scroll away.
        int primes = 0;
        for (Hit h : s) {
            if (!h.isPrime()) continue;
            if (primes++ >= 3) break;
            feed.addView(feedRow(h, true));
        }
        if (primes > 0) {
            View sep = new View(this);
            sep.setBackgroundColor(th.line);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
            slp.topMargin = Ui.dp(this, 3);
            slp.bottomMargin = Ui.dp(this, 3);
            feed.addView(sep, slp);
        }
        int from = Math.max(0, s.size() - 4);
        for (int i = s.size() - 1; i >= from; i--) {
            Hit h = s.get(i);
            if (h.isPrime()) continue;
            feed.addView(feedRow(h, false));
        }
    }

    private TextView feedRow(Hit h, boolean prime) {
        String ssid = h.ssidOrGhost();
        if (ssid.length() > 15) ssid = ssid.substring(0, 14) + "\u2026";
        String head = prime ? "\u25C9 " + h.primeTag() + " " : "\u00BB ";
        String line = String.format(Locale.US, "%-3s %-15s %4d  ch%d",
                h.kind(), ssid, h.rssi, h.channel);
        TextView t = Ui.text(this, head + line,
                prime ? (h.isHardTarget() ? th.bad : th.warn)
                        : h.isWep() ? th.bad : h.isOpen() ? th.warn : th.text,
                9.5f, prime);
        t.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        return t;
    }

    /**
     * Live navigation to the selected target: rotating arrow, range, relative
     * turn, and an "in range" state that fires once when you get close enough
     * to actually work the radio (≈15 m or -52 dBm).
     */
    private void updateNav() {
        if (navStrip == null) return;
        if (hudTargetBssid == null) {
            navStrip.setVisibility(View.GONE);
            return;
        }
        long now = System.currentTimeMillis();
        if (navTarget == null || navTarget.bssid == null
                || !navTarget.bssid.equals(hudTargetBssid) || now - navTargetLoad > 5000) {
            navTarget = db.get(hudTargetBssid);
            navTargetLoad = now;
        }
        if (navTarget == null) {
            navStrip.setVisibility(View.GONE);
            return;
        }
        navStrip.setVisibility(View.VISIBLE);

        double[] e = db.estGet(navTarget.bssid);
        double tlat = e != null ? e[0] : navTarget.lat;
        double tlon = e != null ? e[1] : navTarget.lon;
        boolean geo = tlat != 0 || tlon != 0;

        if (!hasFix || !geo) {
            navArrow.setRotation(0);
            navArrow.setTextColor(th.dim);
            navDist.setText("NO FIX");
            navDist.setTextColor(th.dim);
            navInfo.setText(navTarget.ssidOrGhost().toUpperCase(Locale.US)
                    + " \u00b7 AWAITING GPS + POSITION");
            return;
        }

        double d = Nav.dist(lastLat, lastLon, tlat, tlon);
        double brg = Nav.bearing(lastLat, lastLon, tlat, tlon);
        boolean inRange = d <= IN_RANGE_M || navTarget.rssi >= IN_RANGE_RSSI;

        // Point the arrow relative to where the phone is facing; fall back to
        // absolute bearing when the device has no heading yet.
        navArrow.setRotation(lastBear > 0 ? (float) Nav.relative(brg, lastBear) : (float) brg);
        navArrow.setTextColor(inRange ? th.good : th.accent2);

        navDist.setText(inRange ? "IN RANGE \u00b7 " + Nav.range(d) : Nav.range(d));
        navDist.setTextColor(inRange ? th.good : th.text);

        String mode = lastBear > 0 ? Nav.guidance(brg, lastBear) : Nav.compass(brg);
        navInfo.setText(navTarget.ssidOrGhost().toUpperCase(Locale.US)
                + " \u00b7 " + mode
                + " \u00b7 " + navTarget.rssi + "dBm"
                + " \u00b7 WALK " + Nav.eta(d, 1.35));

        if (inRange && !inRangeAnnounced) {
            inRangeAnnounced = true;
            vibrateTarget();
            if (crt != null) crt.burst(1.0f);
            toast("TARGET IN RANGE");
        }
        if (!inRange && inRangeAnnounced && d > IN_RANGE_M * 2.5) inRangeAnnounced = false;
    }

    private void refreshBigBtn() {
        if (bigBtn == null) return;
        boolean r = WardriveService.isRunning();
        bigBtn.setText(r ? "\u25A0  STOP SWEEP" : "\u25B6  START SWEEP");
        bigBtn.setBackground(Ui.box(this, r ? th.panel : th.accent, r ? th.bad : th.accent, 2, r ? 1 : 0));
        bigBtn.setTextColor(r ? th.bad : th.bg);
        if (statusDot != null) {
            boolean fix = WardriveService.hasFix();
            statusDot.setText(r ? (fix ? "SWEEPING // LOCK" : "SWEEPING // NO FIX") : "IDLE");
            statusDot.setTextColor(r ? (fix ? th.accent : th.warn) : th.dim);
        }
    }

    // ---------------------------------------------------------------- start/stop

    private void toggle() {
        if (WardriveService.isRunning()) {
            WardriveService.stop(this);
            ui.postDelayed(new Runnable() {
                @Override public void run() { refreshBigBtn(); }
            }, 350);
            toast("SWEEP HALTED");
            return;
        }
        if (!hasPerms()) { requestScanPerms(); return; }
        if (!gpsOn()) { gpsDialog(); return; }
        beginSweep();
    }

    private void beginSweep() {
        try {
            WardriveService.start(this);
            toast("SWEEP LIVE // GPS ACQUIRING");
        } catch (Exception e) {
            toast("START FAILED: " + e.getMessage());
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { refreshBigBtn(); }
        }, 400);
    }

    private boolean hasPerms() {
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean near = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        return fine && near;
    }

    private void requestScanPerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(p.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_PERM) return;
        if (hasPerms()) {
            startFixes();
            if (gpsOn()) beginSweep(); else gpsDialog();
        } else {
            toast("LOCATION + NEARBY WIFI REQUIRED TO CAPTURE");
        }
    }

    private boolean gpsOn() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try { return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER); }
        catch (Exception e) { return false; }
    }

    private void gpsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("LOCATION SERVICES OFF")
                .setMessage("Wardriving without coordinates produces dead rows. Every WiGLE record needs a fix.\n\nEnable location, then start the sweep.")
                .setPositiveButton("OPEN SETTINGS", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
                        catch (Exception ignored) {}
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    // ---------------------------------------------------------------- NODES

    private View buildHits() {
        ScrollView sv = new ScrollView(this);
        LinearLayout v = Ui.col(this);
        v.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 18));

        List<Hit> all = db.all();
        List<Hit> list = new ArrayList<>();
        for (Hit h : all) if (matches(h) && matchesQuery(h)) list.add(h);
        list = sortList(list);

        LinearLayout head = Ui.row(this);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView t = Ui.label(this, "TARGET INTELLIGENCE", th.dim, 9f);
        head.addView(t, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView n = Ui.text(this, list.size() + "/" + all.size(), th.accent, 12f, true);
        hitsCountLabel = n;
        head.addView(n);
        v.addView(head);
        v.addView(Ui.divider(this, alpha(th.dim, 70), 1));
        v.addView(spacer(6));

        // ---- search: SSID, BSSID, vendor, note or channel ----
        LinearLayout searchRow = Ui.row(this);
        searchRow.setBackground(Ui.box(this, th.panel, alpha(th.accent, 90), 8, 1));
        searchRow.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 6), 0);

        TextView mag = Ui.text(this, "\u2315", th.dim, 12f, false);
        searchRow.addView(mag);

        searchBox = new EditText(this);
        searchBox.setHint("search ssid / bssid / vendor / ch");
        searchBox.setText(query);
        searchBox.setTextColor(th.text);
        searchBox.setHintTextColor(alpha(th.dim, 170));
        searchBox.setTextSize(9.5f);
        searchBox.setSingleLine(true);
        searchBox.setBackgroundColor(0x00000000);
        searchBox.setPadding(Ui.dp(this, 7), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim();
                rebuildTargetList();
            }
        });
        searchRow.addView(searchBox, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = Ui.text(this, "\u2715", th.dim, 11f, false);
        clear.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                query = "";
                if (searchBox != null) searchBox.setText("");
                rebuildTargetList();
            }
        });
        searchRow.addView(clear);
        v.addView(searchRow);
        v.addView(spacer(7));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(this);
        String[] filters = {"ALL", "PRIME", "HIDDEN", "REVEAL", "CRIT", "OPEN", "WEP", "WPA3", "WPS", "5G+6G", "TAGGED"};
        for (String f : filters) {
            TextView c = Ui.chip(this, f, th, f.equals(filter));
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) {
                    filter = f;
                    show(1);
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            chips.addView(c, lp);
        }
        hsv.addView(chips);
        v.addView(hsv);
        v.addView(spacer(7));

        // ---- sort cycle ----
        LinearLayout sortRow = Ui.row(this);
        sortRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView sortLab = Ui.label(this, "SORT", th.dim, 7.5f);
        sortRow.addView(sortLab, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView sortChip = Ui.chip(this, SORTS[sortMode], th, true);
        sortChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                sortMode = (sortMode + 1) % SORTS.length;
                sortChip.setText(SORTS[sortMode]);
                rebuildTargetList();
            }
        });
        sortRow.addView(sortChip);
        v.addView(sortRow);

        // ---- signal floor: the fastest way to kill clutter ----
        LinearLayout floorRow = Ui.row(this);
        floorRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        floorRow.setPadding(0, Ui.dp(this, 7), 0, 0);
        TextView floorLab = Ui.label(this, "STRENGTH", th.dim, 7.5f);
        floorRow.addView(floorLab, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView floorChip = Ui.chip(this, FLOOR_LABELS[0], th, strengthFloor > -100);
        floorChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                int at = 0;
                for (int i = 0; i < FLOORS.length; i++) if (FLOORS[i] == strengthFloor) at = i;
                at = (at + 1) % FLOORS.length;
                strengthFloor = FLOORS[at];
                floorChip.setText(FLOOR_LABELS[at]);
                floorChip.setTextColor(strengthFloor > -100 ? th.bright : th.muted);
                floorChip.setBackground(Skin.pill(MainActivity.this, th, strengthFloor > -100));
                rebuildTargetList();
            }
        });
        floorRow.addView(floorChip);
        v.addView(floorRow);
        v.addView(spacer(8));

        hitsListBox = Ui.col(this);
        v.addView(hitsListBox);
        rebuildTargetList();

        sv.addView(v);
        return sv;
    }

    /**
     * Repaint only the result list, so typing in the search box does not steal
     * focus or rebuild the entire screen on every keystroke.
     */
    private void rebuildTargetList() {
        if (hitsListBox == null) return;
        hitsListBox.removeAllViews();
        List<Hit> all = db.all();
        List<Hit> list = new ArrayList<>();
        for (Hit h : all) if (matches(h) && matchesQuery(h)) list.add(h);
        list = sortList(list);

        if (hitsCountLabel != null) {
            hitsCountLabel.setText(list.size() + "/" + all.size());
        }

        int shown = 0;
        for (Hit h : list) {
            if (shown >= 400) break;
            hitsListBox.addView(hitRow(h));
            hitsListBox.addView(spacer(5));
            shown++;
        }
        if (shown == 0) {
            String msg = all.isEmpty() ? "NO CAPTURES YET \u00b7 RUN A SWEEP"
                    : query.isEmpty() ? "NOTHING IN THIS FILTER"
                    : "NO MATCH FOR \"" + query.toUpperCase(Locale.US) + "\"";
            TextView e = Ui.label(this, msg, th.dim, 9f);
            e.setPadding(0, Ui.dp(this, 20), 0, 0);
            hitsListBox.addView(e);
        }
    }

    private String filter = "ALL";
    private String query = "";
    private int sortMode = 0;
    private SpectrumView spectrum;
    private int exportScope = 0;
    private EditText searchBox;
    private LinearLayout hitsListBox;
    private TextView hitsCountLabel;
    /** Hide anything weaker than this. The single best anti-clutter control. */
    private int strengthFloor = -100;

    private static final int[] FLOORS = {-100, -80, -70, -60, -50};
    private static final String[] FLOOR_LABELS = {"ALL", "\u2265-80", "\u2265-70", "\u2265-60", "\u2265-50"};

    private static final String[] SORTS = {"SCORE", "NEAREST", "SIGNAL", "SEEN", "RECENT"};

    /** Search + sort applied on top of the chip filter. */
    private List<Hit> sortList(List<Hit> in) {
        if (sortMode == 1) {
            try { nearEst = db.estAll(); } catch (Throwable ignored) { }
        }
        List<Hit> out = new ArrayList<>(in);
        java.util.Collections.sort(out, new java.util.Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                switch (sortMode) {
                    case 1: {
                        // nearest first, using the estimated transmitter position when known
                        if (!hasFix) return b.score - a.score;
                        return Double.compare(distTo(a), distTo(b));
                    }
                    case 2: return b.rssi - a.rssi;
                    case 3: return b.seen - a.seen;
                    case 4: return Long.compare(b.lastSeen, a.lastSeen);
                    default: return b.score - a.score;
                }
            }
        });
        return out;
    }

    /** Cached estimates for the duration of one sort pass. */
    private java.util.Map<String, double[]> nearEst = new java.util.HashMap<>();

    /** Distance from the operator to a node, preferring the estimated TX position. */
    private double distTo(Hit h) {
        double[] e = nearEst.get(h.bssid);
        double lat = e != null ? e[0] : h.lat;
        double lon = e != null ? e[1] : h.lon;
        if (lat == 0 && lon == 0) return Double.MAX_VALUE;
        return Nav.dist(lastLat, lastLon, lat, lon);
    }

    private boolean matchesQuery(Hit h) {
        if (query.isEmpty()) return true;
        String q = query.toLowerCase(Locale.US);
        if (h.ssid != null && h.ssid.toLowerCase(Locale.US).contains(q)) return true;
        if (h.bssid != null && h.bssid.toLowerCase(Locale.US).contains(q)) return true;
        if (h.vendor() != null && h.vendor().toLowerCase(Locale.US).contains(q)) return true;
        if (h.note != null && h.note.toLowerCase(Locale.US).contains(q)) return true;
        return String.valueOf(h.channel).equals(q);
    }

    private boolean matches(Hit h) {
        if (h.rssi < strengthFloor) return false;
        switch (filter) {
            case "PRIME": return h.isPrime();
            case "HIDDEN": return h.isMasked();
            case "REVEAL": return h.revealed();
            case "CRIT": return h.score >= WifiFacts.TIER_CRIT;
            case "OPEN": return h.isOpen();
            case "WEP": return h.isWep();
            case "WPA3": return h.kind().startsWith("WPA3") || h.kind().contains("OWE");
            case "WPS": return h.wps;
            case "5G+6G": return h.band != 24;
            case "TAGGED": return h.tagged;
            default: return true;
        }
    }

    private int tierColor(int score) {
        if (score >= WifiFacts.TIER_CRIT) return th.bad;
        if (score >= WifiFacts.TIER_HIGH) return th.warn;
        if (score >= WifiFacts.TIER_MED) return th.accent;
        return th.dim;
    }

    private View hitRow(final Hit h) {
        LinearLayout card = Ui.card(this, th);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = Ui.row(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // score badge — the thing the operator actually scans for
        TextView badge = Ui.text(this, String.valueOf(h.score), tierColor(h.score), 10f, true);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setSingleLine(true);
        int bw = Ui.dp(this, 34), bh = Ui.dp(this, 30);
        badge.setBackground(Ui.box(this, alpha(tierColor(h.score), 46), alpha(tierColor(h.score), 190), 6, 1));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(bw, bh);
        blp.rightMargin = Ui.dp(this, 9);
        top.addView(badge, blp);

        LinearLayout nameCol = Ui.col(this);
        TextView name = Ui.text(this, h.ssidOrGhost(),
                h.isWep() ? th.bad : h.isOpen() ? th.warn : th.text, 12f, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameCol.addView(name);

        String subTxt = h.kind() + " \u00b7 " + h.band() + "G ch" + h.channel
                + " \u00b7 " + h.width + "MHz" + (h.std.isEmpty() ? "" : " \u00b7 " + h.std);
        TextView sub = Ui.text(this, subTxt, alpha(th.dim, 235), 8.5f, false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameCol.addView(sub);
        top.addView(nameCol, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rssi = Ui.text(this, h.rssi + "", th.accent, 10f, false);
        rssi.setSingleLine(true);
        top.addView(rssi);
        card.addView(top);

        TextView why = Ui.text(this, h.why, alpha(tierColor(h.score), 225), 8f, false);
        why.setSingleLine(true);
        why.setEllipsize(android.text.TextUtils.TruncateAt.END);
        why.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(why);

        String vend = h.vendor();
        String footTxt = h.bssid + "  \u00b7  x" + h.seen + (vend.isEmpty() ? "" : "  \u00b7  " + vend);
        if (h.isMasked()) footTxt = "\u25cc HIDDEN  \u00b7  " + footTxt;
        else if (h.revealed()) footTxt = "\u25c8 UNMASKED  \u00b7  " + footTxt;
        if (h.tagged) footTxt = "\u25c6 TARGET  \u00b7  " + footTxt;
        TextView foot = Ui.text(this, footTxt, alpha(th.dim, 190), 7.5f, false);
        foot.setSingleLine(true);
        foot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        foot.setPadding(0, Ui.dp(this, 3), 0, 0);
        card.addView(foot);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { focusTargetOnMap(h); }
        });
        // Long press keeps the old behaviour: the full intelligence sheet, no map jump.
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { openTarget(h); return true; }
        });
        return card;
    }

    /**
     * TARGETS row tap: jump to HUD MAP, zoom to the node and open its details.
     * A node with no captured position cannot be centred, so it opens the sheet
     * instead of dumping the operator on an empty map with no explanation.
     */
    private void focusTargetOnMap(final Hit h) {
        if (h == null) return;
        if (h.lat == 0 && h.lon == 0) {
            openTarget(h);
            toast("NO POSITION CAPTURED \u00b7 DETAILS ONLY");
            return;
        }
        pendingMapTarget = h;
        hudTargetBssid = h.bssid;
        show(2);
        mapTargetDialog(h);
    }

    // ---------------------------------------------------------------- NODE PANEL

    private void kvRow(LinearLayout p, String k, String val, int color) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        TextView kk = Ui.text(this, k, alpha(th.dim, 210), 8f, false);
        kk.setSingleLine(true);
        kk.setWidth(Ui.dp(this, 92));
        r.addView(kk);
        TextView vv = Ui.text(this, val, color, 9f, false);
        vv.setSingleLine(false);
        r.addView(vv, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        p.addView(r);
    }

    /** What you would actually do with this radio, from the crypto alone. */
    private String attackSurface(Hit h) {
        if (h.isWep()) return "WEP · RC4 IV REUSE\nAIRCRACK-NG / FRAGMENTATION\nCAPTURE ~20K IVS, CRACK IN MINUTES";
        if (h.isOpen()) return "OPEN · NO AUTH\nJOIN DIRECTLY · DHCP + DNS ANALYSIS\nCAPTIVE PORTAL / MITM SURFACE";
        if (h.wps) return "WPS CONFIRMED IN BEACON\nREAVER / BULLY PIN ATTACK\nPIXIE-DUST ON VULNERABLE CHIPSETS";
        if (h.wpsGuess) return "WPS LIKELY · FACTORY-DEFAULT AP\nVERIFY PBC/PIN STATE BEFORE COMMITTING\nREAVER / BULLY IF ENABLED";
        if (h.kind().startsWith("WPA3") && !h.kind().contains("WPA2")) return "WPA3-SAE · NO OFFLINE DICT\nPMF ENFORCED · DEAUTH RESISTANT\nONLY ONLINE GUESSING OR IMPLANT";
        if (h.kind().contains("WPA2/WPA3")) return "TRANSITION MODE · DOWNGRADE PATH\nSAE + PSK BOTH ADVERTISED\nCAPTURE 4-WAY FROM A PSK CLIENT";
        if (h.kind().contains("EAP")) return "802.1X ENTERPRISE\nCAPTURE EAP IDENTITY + MSCHAPV2\nEVIL TWIN / RADIUS RELAY TERRITORY";
        if (h.kind().startsWith("WPA2")) return "WPA2-PSK · HANDSHAKE TARGET\nPMKID VIA HCXDUMPSUITE (NO CLIENT NEEDED)\nOR 4-WAY CAPTURE + DICTIONARY";
        if (h.kind().startsWith("WPA")) return "WPA-TKIP · LEGACY\nTKIP WEAKNESSES + HANDSHAKE CAPTURE\nOFTEN PAIRED WITH WPS";
        return "BASELINE · NO OBVIOUS WEAKNESS\nPASSIVE MAPPING ONLY";
    }

    private void openTarget(final Hit h) {
        LinearLayout p = Ui.col(this);
        int pad = Ui.dp(this, 14);
        p.setPadding(pad, pad, pad, pad);

        String flags = (h.wps ? "WPS " : "") + (h.wpsGuess && !h.wps ? "WPS? " : "")
                + (h.pmf ? "PMF " : "")
                + (h.hidden ? "HIDDEN " : "")
                + (h.crypto != null && h.crypto.contains("EAP") ? "802.1X " : "")
                + (h.tagged ? "TAGGED " : "");
        if (flags.trim().isEmpty()) flags = "NONE";

        // ---- identity ----
        kvRow(p, "SSID", h.ssidOrGhost(), th.text);
        kvRow(p, "BSSID", h.bssid, th.dim);
        kvRow(p, "VENDOR", h.vendor().isEmpty() ? "UNKNOWN OUI" : h.vendor(), th.dim);

        // ---- crypto / PHY ----
        kvRow(p, "CRYPTO", h.kind(), h.isOpen() || h.isWep() ? th.warn : th.accent);
        kvRow(p, "BAND / CH", h.band() + " GHz \u00b7 ch" + h.channel, th.dim);
        kvRow(p, "WIDTH / PHY", h.width + " MHz \u00b7 " + (h.std.isEmpty() ? "?" : h.std), th.dim);
        kvRow(p, "FLAGS", flags.trim(), h.wps ? th.warn : th.dim);

        // ---- signal ----
        kvRow(p, "SIGNAL", h.rssi + " dBm \u00b7 best " + h.best
                + " \u00b7 " + Est.quality(h.rssi), th.accent);

        // ---- distance from the operator right now ----
        if (hasFix && h.lat != 0 && h.lon != 0) {
            double d = Nav.dist(lastLat, lastLon, h.lat, h.lon);
            kvRow(p, "RANGE", Nav.range(d) + " \u00b7 " + Nav.compass(Nav.bearing(lastLat, lastLon, h.lat, h.lon)),
                    th.accent2);
            kvRow(p, "ETA", "WALK " + Nav.eta(d, 1.35) + " \u00b7 DRIVE " + Nav.eta(d, 11.0), th.dim);
        }

        // ---- first/last seen ----
        kvRow(p, "FIRST SEEN", fmtTime(h.firstSeen), th.dim);
        kvRow(p, "LAST SEEN", fmtTime(h.lastSeen) + " \u00b7 x" + h.seen, th.dim);

        // ---- captured position ----
        kvRow(p, "CAPTURED AT", String.format(Locale.US, "%.6f, %.6f \u00b1%.0fm", h.lat, h.lon, h.acc), th.dim);

        // ---- multilaterated transmitter position ----
        double[] est = db.estGet(h.bssid);
        int obs = db.obsCount(h.bssid);
        if (est != null) {
            kvRow(p, "EST TX", String.format(Locale.US, "%.6f, %.6f \u00b1%.0fm",
                    est[0], est[1], est[2]), th.good);
            kvRow(p, "SAMPLE SET", obs + " obs \u00b7 " + (int) est[3] + " used", th.dim);
            if (hasFix) {
                double de = Nav.dist(lastLat, lastLon, est[0], est[1]);
                kvRow(p, "EST RANGE", Nav.range(de) + " \u00b7 " + Nav.compass(Nav.bearing(lastLat, lastLon, est[0], est[1])),
                        th.accent2);
            }
        } else {
            kvRow(p, "EST TX", obs < 3 ? "NEED " + (3 - obs) + " MORE OBS" : "PENDING", th.dim);
        }

        // ---- verdict ----
        kvRow(p, "SCORE", h.score + " \u00b7 " + h.tier(), tierColor(h.score));
        kvRow(p, "WHY", h.why, alpha(th.text, 220));

        // ---- what to do about it ----
        TextView surface = Ui.text(this, attackSurface(h),
                h.isHardTarget() ? th.bad : (h.wpsGuess ? th.warn : th.accent2), 8.5f, false);
        surface.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 4));
        p.addView(surface);

        // ---- mask state + unmask path ----
        if (h.isMasked()) {
            TextView unmask = Ui.text(this,
                    "MASKED \u00b7 SSID SUPPRESSED\n"
                            + "1. WATCH THIS BSSID \u2014 Android caches the name after any\n"
                            + "   client associates, and it will appear here automatically.\n"
                            + "2. PROBE-RESPONSE CAPTURE \u2014 a masked AP still answers\n"
                            + "   directed probes with its SSID in the clear. That needs a\n"
                            + "   monitor-mode radio (Pi + external adapter); stock Android\n"
                            + "   cannot put its own wifi chip in monitor mode.\n"
                            + "3. LOOK FOR THE TWIN \u2014 same OUI, same channel, adjacent\n"
                            + "   BSSID is often the visible half of the same router.",
                    th.accent2, 8.2f, false);
            unmask.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
            p.addView(unmask);
        } else if (h.revealed()) {
            TextView rev = Ui.text(this,
                    "\u25c8 UNMASKED \u00b7 was hidden, now broadcasts \"" + h.ssidOrGhost() + "\"",
                    th.good, 9f, true);
            rev.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
            p.addView(rev);
        }

        final EditText note = new EditText(this);
        note.setHint("operator note");
        note.setText(h.note == null ? "" : h.note);
        note.setTextColor(th.text);
        note.setHintTextColor(alpha(th.dim, 180));
        note.setTextSize(9.5f);
        note.setSingleLine(true);
        note.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        p.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(p);

        new AlertDialog.Builder(this)
                .setTitle("NODE " + h.bssid)
                .setView(scroll)
                .setPositiveButton(h.tagged ? "UNTAG" : "MARK TARGET", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        db.setTagged(h.bssid, !h.tagged);
                        db.setNote(h.bssid, note.getText().toString());
                        show(1);
                    }
                })
                .setNeutralButton("NAVIGATE", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        db.setNote(h.bssid, note.getText().toString());
                        hudTargetBssid = h.bssid;
                        navTarget = h;
                        navTargetLoad = System.currentTimeMillis();
                        inRangeAnnounced = false;
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData
                                    .newPlainText("bssid", h.bssid));
                        } catch (Throwable ignored) {}
                        show(0);
                        updateNav();
                        toast("NAVIGATING // BSSID COPIED");
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private static String fmtTime(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    // ---------------------------------------------------------------- EXPORT

    // ---------------------------------------------------------------- HUD map

    private List<Hit> mapNodes = new ArrayList<>();
    private double lastNodeLat, lastNodeLon;

    private View buildMap() {
        LinearLayout v = Ui.col(this);
        v.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 2));

        LinearLayout head = Ui.row(this);
        head.addView(Ui.label(this, "HUD MAP", th.accent, 10f), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mapGpsLabel = Ui.label(this, hasFix ? "LIVE FIX" : "NO FIX", hasFix ? th.good : th.dim, 8.5f);
        head.addView(mapGpsLabel);
        v.addView(head);
        v.addView(spacer(6));

        LinearLayout frame = Ui.col(this);
        frame.setPadding(Ui.dp(this, 1), Ui.dp(this, 1), Ui.dp(this, 1), Ui.dp(this, 1));
        frame.setBackground(Ui.box(this, th.edge, alpha(th.accent, 90), 10, 1));
        mapView = new MapView(this, th);
        mapView.setLabels(prefs().getBoolean("labels", true));
        mapView.setOnTargetTap(new MapView.OnTargetTap() {
            @Override public void onTarget(Hit h) { mapTargetDialog(h); }
        });
        if (pendingMapTarget != null) {
            final Hit jump = pendingMapTarget;
            pendingMapTarget = null;
            // Wait for layout: setTarget() divides by view width to pick a zoom, and
            // width is zero until this view has been measured.
            mapView.post(new Runnable() {
                @Override public void run() {
                    mapView.setTarget(jump);
                    mapView.recenter();
                    mapView.invalidate();
                }
            });
        }
        frame.addView(mapView, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        v.addView(frame, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout ctl = Ui.row(this);
        ctl.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        ctl.addView(chip("\u2212  ZOOM", th.dim, new View.OnClickListener() {
            @Override public void onClick(View x) { if (mapView != null) mapView.zoomBy(1.8); }
        }));
        ctl.addView(chip("\u25CE  RECENTER", th.accent, new View.OnClickListener() {
            @Override public void onClick(View x) { if (mapView != null) mapView.recenter(); }
        }));
        ctl.addView(chip("+  ZOOM", th.dim, new View.OnClickListener() {
            @Override public void onClick(View x) { if (mapView != null) mapView.zoomBy(0.55); }
        }));
        ctl.addView(chip("\u2691  BEST NODE", th.accent2, new View.OnClickListener() {
            @Override public void onClick(View x) { plotBest(); }
        }));
        ctl.addView(chip("\u2715  CLEAR", th.bad, new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (mapView != null) { mapView.setTarget(null); toast("ROUTE CLEARED"); }
            }
        }));
        final TextView labelChip = chip("\u25A4  LABELS", th.accent2, new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (mapView == null) return;
                boolean on = !mapView.labelsOn();
                mapView.setLabels(on);
                prefs().edit().putBoolean("labels", on).apply();
                ((TextView) x).setText(on ? "\u25A4  LABELS" : "\u25A1  LABELS");
                ((TextView) x).setTextColor(on ? th.accent2 : th.dim);
            }
        });
        ctl.addView(labelChip);
        hs.addView(ctl);
        v.addView(hs, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = Ui.label(this,
                "PINCH TO ZOOM \u00b7 DRAG TO PAN \u00b7 TAP A NODE TO PLOT A ROUTE", th.dim, 8f);
        hint.setPadding(0, Ui.dp(this, 4), 0, 0);
        v.addView(hint);

        refreshMapNodes();
        restoreTarget();
        return v;
    }

    private void restoreTarget() {
        if (mapView == null || hudTargetBssid == null) return;
        Hit h = db.get(hudTargetBssid);
        if (h != null) mapView.setTarget(h);
    }

    private TextView chip(String label, int col, View.OnClickListener l) {
        TextView t = Ui.text(this, label, col, 9.5f, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setBackground(Ui.box(this, th.panel, alpha(col, 110), 8, 1));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 7);
        t.setLayoutParams(lp);
        return t;
    }

    private void refreshMapNodes() {
        if (mapView == null) return;
        List<Hit> l;
        try {
            if (hasFix) l = db.near(lastLat, lastLon, Math.max(700, mapView.getRangeMeters() * 2.4), 800);
            else l = db.geotagged(500);
        } catch (Throwable t) { return; }
        mapNodes = l;
        mapView.setHits(l);
        try { mapView.setEstimates(db.estAll()); } catch (Throwable ignored) { }
    }

    private void plotBest() {
        if (mapView == null) return;
        if (mapNodes.isEmpty()) { toast("NO GEOTAGGED NODES // IMPORT OR SWEEP FIRST"); return; }
        Hit best = null;
        double bd = Double.MAX_VALUE;
        for (Hit h : mapNodes) {
            double dd = hasFix ? Nav.dist(lastLat, lastLon, h.lat, h.lon) : (10000 - h.score);
            if (dd < bd) { bd = dd; best = h; }
        }
        if (best == null) return;
        hudTargetBssid = best.bssid;
        mapView.setTarget(best);
        toast((best.ssidOrGhost() + " // " + Nav.range(bd)).toUpperCase(Locale.US));
    }

    private void mapTargetDialog(final Hit h) {
        LinearLayout v = Ui.col(this);
        int pad = Ui.dp(this, 16);
        v.setPadding(pad, pad, pad, pad);
        v.addView(Ui.label(this, h.ssidOrGhost(), th.accent, 13f));
        v.addView(spacer(6));
        v.addView(Ui.text(this, h.bssid, th.dim, 9f, false));
        v.addView(Ui.text(this, h.kind() + " \u00b7 " + h.band() + "G \u00b7 ch" + h.channel
                + " \u00b7 " + h.std, th.text, 9.5f, false));
        String dist = hasFix ? Nav.range(Nav.dist(lastLat, lastLon, h.lat, h.lon)) + " \u00b7 "
                + Nav.compass(Nav.bearing(lastLat, lastLon, h.lat, h.lon)) : "no fix";
        v.addView(Ui.text(this, dist, th.accent2, 10f, true));
        v.addView(Ui.text(this, "SCORE " + h.score + " \u00b7 " + h.tier() + " \u00b7 " + h.why,
                th.dim, 8.5f, false));
        new AlertDialog.Builder(this)
                .setTitle("NODE ON MAP")
                .setView(v)
                .setPositiveButton("PLOT ROUTE", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        hudTargetBssid = h.bssid;
                        if (mapView != null) mapView.setTarget(h);
                        toast("ROUTE PLOTTED // " + h.ssidOrGhost().toUpperCase(Locale.US));
                    }
                })
                .setNeutralButton(h.tagged ? "UNMARK" : "MARK", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        db.setTagged(h.bssid, !h.tagged);
                        refreshMapNodes();
                        toast(h.tagged ? "UNMARKED" : "MARKED");
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    // ---------------------------------------------------------------- live fixes

    private String hudTargetBssid;

    private final LocationListener fixListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { onFix(l); }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private void startFixes() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestScanPerms();
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        if (!fixing) {
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 900, 0.6f, fixListener, Looper.getMainLooper()); }
            catch (Throwable ignored) {}
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500, 2f, fixListener, Looper.getMainLooper()); }
            catch (Throwable ignored) {}
            fixing = true;
        }
        try {
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) onFix(l);
        } catch (Throwable ignored) {}
    }

    private void onFix(Location l) {
        hasFix = true;
        lastLat = l.getLatitude();
        lastLon = l.getLongitude();
        lastAlt = l.hasAltitude() ? l.getAltitude() : 0;
        if (l.hasBearing()) lastBear = l.getBearing();
        lastSpeed = l.hasSpeed() ? l.getSpeed() : 0;
        lastAcc = l.hasAccuracy() ? l.getAccuracy() : lastAcc;
        if (mapView != null) mapView.setSelf(lastLat, lastLon, lastAlt, lastBear, lastSpeed, lastAcc);
        if (mapGpsLabel != null) {
            mapGpsLabel.setText("LIVE FIX \u00b7 \u00b1" + Math.round(lastAcc) + "m");
            mapGpsLabel.setTextColor(th.good);
        }
        long now = System.currentTimeMillis();
        boolean moved = lastNodePull == 0 || Nav.dist(lastNodeLat, lastNodeLon, lastLat, lastLon) > 30;
        if (moved || now - lastNodePull > 5000) {
            lastNodeLat = lastLat;
            lastNodeLon = lastLon;
            lastNodePull = now;
            refreshMapNodes();
        }
    }

    // ---------------------------------------------------------------- csv ingest

    private void pickCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(i, REQ_CSV); }
        catch (Throwable t) { toast("NO FILE PICKER AVAILABLE"); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CSV) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        importCsv(data.getData());
    }

    private void importCsv(final Uri uri) {
        final String name = uri.getLastPathSegment() == null ? "wardrive.csv" : uri.getLastPathSegment();
        csvCancel = false;
        final TextView msg = Ui.text(this, "OPENING\n" + name, th.text, 9.5f, false);
        msg.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 6));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("WIGLE CSV INGEST")
                .setView(msg)
                .setNegativeButton("ABORT", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { csvCancel = true; }
                })
                .create();
        dlg.setCancelable(false);
        dlg.show();

        new Thread(new Runnable() {
            @Override public void run() {
                CsvImport.Result res = null;
                String err = null;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    res = CsvImport.run(MainActivity.this, in, name, new CsvImport.Progress() {
                        @Override public void state(final String nm, final int rows, final int ins, final int skip) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    msg.setText(rows + " ROWS \u00b7 " + ins + " NODES\n" + skip + " SKIPPED\n" + nm);
                                }
                            });
                        }
                        @Override public boolean cancelled() { return csvCancel; }
                    });
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final CsvImport.Result fr = res;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { dlg.dismiss(); } catch (Throwable ignored) {}
                        if (fe != null) { toast("IMPORT FAILED // " + fe); return; }
                        if (fr == null) { toast("IMPORT FAILED // NO RESULT"); return; }
                        toast("INGESTED " + fr.inserted + " NODES // " + fr.skipped + " SKIPPED");
                        if (screen == 2) refreshMapNodes();
                        else show(screen);
                        if (fr.capped == 1) toast("CAPPED AT " + CsvImport.DEFAULT_CAP + " // RUN AGAIN FOR MORE");
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- BANDS

    private View buildBands() {
        LinearLayout v = Ui.col(this);
        v.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 6));

        LinearLayout head = Ui.row(this);
        head.addView(Ui.label(this, "SPECTRUM", th.accent, 10f),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView live = Ui.label(this, WardriveService.isRunning() ? "LIVE" : "LEDGER",
                WardriveService.isRunning() ? th.good : th.dim, 8.5f);
        head.addView(live);
        v.addView(head);
        v.addView(spacer(6));

        LinearLayout frame = Ui.col(this);
        frame.setPadding(Ui.dp(this, 1), Ui.dp(this, 1), Ui.dp(this, 1), Ui.dp(this, 1));
        frame.setBackground(Ui.box(this, th.edge, alpha(th.accent, 90), 10, 1));

        spectrum = new SpectrumView(this, th);
        List<Hit> nodes = WardriveService.isRunning() ? WardriveService.snapshot() : db.all();
        spectrum.setHits(nodes);
        frame.addView(spectrum, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        v.addView(frame, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        v.addView(spacer(8));

        LinearLayout summary = Ui.card(this, th);
        summary.addView(Ui.label(this, "BAND SPLIT", th.dim, 8f));
        summary.addView(spacer(5));
        summary.addView(kv("2.4 GHZ", db.count() - db.countFive()));
        summary.addView(kv("5 GHZ", db.countFive() - db.countSix()));
        summary.addView(kv("6 GHZ", db.countSix()));
        summary.addView(kv("WPS EXPOSED", db.countWps()));
        summary.addView(kv("MASKED", db.countHidden()));
        summary.addView(kv("UNMASKED", db.countUnmasked()));
        summary.addView(kv("PRIME TARGETS", db.countPrime()));
        v.addView(summary);

        TextView hint = Ui.label(this,
                "TALLER BAR = MORE NODES ON THAT CHANNEL \u00b7 RED = OPEN/WEP", th.dim, 7.5f);
        hint.setPadding(0, Ui.dp(this, 6), 0, 0);
        v.addView(hint);
        return v;
    }

    private View buildExport() {
        ScrollView sv = new ScrollView(this);
        LinearLayout v = Ui.col(this);
        v.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 20));

        LinearLayout stats = Ui.card(this, th);
        stats.addView(Ui.label(this, "CAPTURE LEDGER", th.dim, 8.5f));
        stats.addView(spacer(6));
        stats.addView(kv("UNIQUE NODES", db.count()));
        stats.addView(kv("OPEN", db.countOpen()));
        stats.addView(kv("WEP", db.countWep()));
        stats.addView(kv("HIDDEN / MASKED", db.countHidden()));
        stats.addView(kv("UNMASKED", db.countUnmasked()));
        stats.addView(kv("WPA / RSN", db.countWpa()));
        stats.addView(kv("5 GHZ", db.countFive()));
        v.addView(stats);
        v.addView(spacer(10));

        LinearLayout fmt = Ui.card(this, th);
        fmt.addView(Ui.label(this, "OUTPUT FORMAT", th.dim, 8.5f));
        fmt.addView(spacer(6));
        TextView body = Ui.text(this,
                "WigleWifi-1.4\n"
                        + "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,\n"
                        + "CurrentLatitude,CurrentLongitude,\n"
                        + "AltitudeMeters,AccuracyMeters,Type",
                th.text, 8.5f, false);
        fmt.addView(body);
        TextView note = Ui.text(this,
                "Same header, same column order, same 11 fields. Any WiGLE-compatible "
                        + "tool or importer can swallow this file.",
                th.dim, 8.5f, false);
        note.setPadding(0, Ui.dp(this, 6), 0, 0);
        fmt.addView(note);
        v.addView(fmt);
        v.addView(spacer(12));

        TextView exportBtn = Ui.btn(this, "EXPORT CSV", th, true);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { doExport(false); }
        });
        v.addView(exportBtn, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.addView(spacer(8));

        TextView shareBtn = Ui.btn(this, "SHARE LAST CSV", th, false);
        shareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { doExport(true); }
        });
        v.addView(shareBtn, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.addView(spacer(10));

        TextView csvBtn = Ui.text(this, "\u2913  INGEST WIGLE .CSV / .CSV.GZ", th.accent2, 10f, true);
        csvBtn.setGravity(android.view.Gravity.CENTER);
        csvBtn.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        csvBtn.setBackground(Ui.box(this, th.panel, alpha(th.accent2, 130), 9, 1));
        csvBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { pickCsv(); }
        });
        v.addView(csvBtn, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.addView(spacer(8));

        exportPathLabel = Ui.text(this,
                lastExport == null ? "TARGET: /Downloads/AegisWardrive/" : "LAST: " + lastExport,
                th.dim, 8.5f, false);
        v.addView(exportPathLabel);
        v.addView(spacer(18));

        TextView purge = Ui.btn(this, "PURGE LEDGER", th, false);
        purge.setTextColor(th.bad);
        purge.setBackground(Ui.box(this, th.panel, alpha(th.bad, 150), 2, 1));
        purge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { confirmPurge(); }
        });
        v.addView(purge, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sv.addView(v);
        return sv;
    }

    private View kv(String k, int val) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        r.addView(Ui.text(this, k, th.dim, 10f, false), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(Ui.text(this, String.valueOf(val), th.accent, 11f, true));
        return r;
    }

    private void doExport(boolean share) {
        List<Hit> all = db.all();
        if (all.isEmpty()) { toast("LEDGER EMPTY // NOTHING TO EXPORT"); return; }
        try {
            Uri u = CsvExporter.export(this, all);
            lastExport = u;
            if (exportPathLabel != null) exportPathLabel.setText("LAST: " + u);
            toast(all.size() + " ROWS \u2192 Downloads/AegisWardrive");
            if (share) {
                startActivity(Intent.createChooser(CsvExporter.shareIntent(this, u), "SHARE CSV"));
            }
        } catch (Exception e) {
            toast("EXPORT FAILED: " + e.getMessage());
        }
    }

    private void confirmPurge() {
        new AlertDialog.Builder(this)
                .setTitle("PURGE LEDGER")
                .setMessage("Delete every captured node from local storage? Export first if you want the data.")
                .setPositiveButton("PURGE", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        db.purge();
                        lastFeedCount = -1;
                        toast("LEDGER WIPED");
                        show(4);
                    }
                })
                .setNegativeButton("KEEP", null)
                .show();
    }

    // ---------------------------------------------------------------- THEMES

    private View buildThemes() {
        ScrollView sv = new ScrollView(this);
        LinearLayout v = Ui.col(this);
        v.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 20));

        v.addView(Ui.label(this, "INTERFACE SKIN", th.dim, 8.5f));
        v.addView(spacer(8));

        for (final Theme t : Theme.ALL) {
            LinearLayout card = Ui.card(this, th);
            card.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));

            LinearLayout top = Ui.row(this);
            LinearLayout sw = Ui.row(this);
            sw.addView(swatch(t.accent), Ui.lp(Ui.dp(this, 16), Ui.dp(this, 16)));
            sw.addView(swatch(t.accent2), Ui.lp(Ui.dp(this, 16), Ui.dp(this, 16)));
            sw.addView(swatch(t.panelAlt), Ui.lp(Ui.dp(this, 16), Ui.dp(this, 16)));
            LinearLayout.LayoutParams swlp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            swlp.rightMargin = Ui.dp(this, 12);
            top.addView(sw, swlp);

            LinearLayout names = Ui.col(this);
            names.addView(Ui.text(this, t.name, t.accent, 12f, true));
            names.addView(Ui.text(this, t.tagline, th.dim, 8f, false));
            top.addView(names, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (t.id.equals(th.id)) {
                top.addView(Ui.label(this, "ACTIVE", th.good, 8f));
            }
            card.addView(top);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    prefs().edit().putString("theme", t.id).apply();
                    recreate();
                }
            });
            v.addView(card);
            v.addView(spacer(7));
        }

        v.addView(spacer(12));
        v.addView(Ui.label(this, "BEHAVIOR", th.dim, 8.5f));
        v.addView(spacer(8));
        v.addView(toggle("SOUND FX", "sfx", true));
        v.addView(spacer(7));
        v.addView(toggle("KEEP SCREEN AWAKE", "keepon", true));

        v.addView(spacer(16));
        LinearLayout about = Ui.card(this, th);
        about.addView(Ui.label(this, "AEGIS//WARDRIVE v1.0", th.accent, 9f));
        about.addView(spacer(5));
        about.addView(Ui.text(this,
                "Local capture only. No cloud, no account, no telemetry.\n"
                        + "WiGLE 1.4 CSV output. Built because somebody welded the door shut.",
                th.dim, 8.5f, false));
        v.addView(about);

        sv.addView(v);
        return sv;
    }

    private View swatch(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        return v;
    }

    private View toggle(final String label, final String key, boolean def) {
        boolean on = prefs().getBoolean(key, def);
        LinearLayout card = Ui.card(this, th);
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, label, th.text, 10.5f, false), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView st = Ui.label(this, on ? "ON" : "OFF", on ? th.good : th.dim, 9f);
        r.addView(st);
        card.addView(r);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !prefs().getBoolean(key, true);
                prefs().edit().putBoolean(key, now).apply();
                show(5);
            }
        });
        return card;
    }

    // ---------------------------------------------------------------- misc

    private SharedPreferences prefs() { return getSharedPreferences("wardrive", MODE_PRIVATE); }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, dp)));
        return v;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }
}
