package com.aegispilot.wardrive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Foreground wardriving engine: wifi scan loop + GPS fix capture + SQLite persistence.
 * Runs with a partial wakelock so a phone in a pocket keeps eating packets.
 *
 * v1.1 adds the evidence layer: every BSSID accumulates positioned observations,
 * and once a node has three or more of them the transmitter position is
 * multilaterated and stored, so the map can show where the radio actually is
 * instead of where you happened to be standing when you first heard it.
 */
public class WardriveService extends Service {

    public interface Listener { void onHits(int total); }

    public static final String ACTION_START = "com.aegispilot.wardrive.action.START";
    public static final String ACTION_STOP = "com.aegispilot.wardrive.action.STOP";

    private static final String CHAN = "wardrive";
    private static final String CHAN_T = "wardrive_targets";
    private static final int NID = 4701;
    private static final int NID_SUMMARY = 4702;
    private static final long SCAN_PERIOD_MS = 4200L;

    /** Minimum movement before a new observation is worth storing, meters. */
    private static final double OBS_MOVE_M = 8.0;
    /** Or this long in ms, so a stationary phone still builds a sample set. */
    private static final long OBS_IDLE_MS = 20000L;
    /** Recompute a transmitter estimate at most this often, ms. */
    private static final long EST_PERIOD_MS = 60000L;
    private static final int EST_MIN_OBS = 3;
    private static final int EST_MAX_OBS = 24;

    static volatile Listener listener;
    static volatile boolean running = false;
    static volatile boolean gpsFix = false;
    static volatile double lat = 0, lon = 0, alt = 0, acc = 0;

    private static final List<Hit> session = new ArrayList<>();
    /** BSSIDs already listed this sweep session, so re-sweeping an area still shows nodes. */
    private static final Set<String> sessionSeen = new HashSet<>();
    /** Prime targets already announced this session. */
    private static final Set<String> primeSeen = new HashSet<>();
    private static volatile int scanFails = 0;

    /** Session telemetry. */
    private static volatile long startMs = 0;
    private static volatile double sessionMeters = 0;
    private static volatile int scanCycles = 0;
    private static volatile int estCount = 0;

    private WifiManager wm;
    private LocationManager lm;
    private LocationListener locLis;
    private BroadcastReceiver rx;
    private Handler h;
    private PowerManager.WakeLock wl;
    private ToneGenerator tone;
    private Db db;
    private long lastBeep = 0;
    private long lastTargetNotify = 0;
    private int pendingTargets = 0;
    private int lastNotified = -1;
    private String lastNotifText = "";

    /** last observation timestamp per BSSID, and last obs position. */
    private final Map<String, long[]> obsStamp = new HashMap<>();
    private final Map<String, double[]> obsPos = new HashMap<>();
    private final Map<String, Long> estStamp = new HashMap<>();

    private double lastFixLat = 0, lastFixLon = 0;
    private boolean haveFixSeed = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean ok = false;
            try { if (wm != null) ok = wm.startScan(); } catch (Exception ignored) {}
            scanFails = ok ? 0 : Math.min(scanFails + 1, 50);
            scanCycles++;
            h.postDelayed(this, SCAN_PERIOD_MS);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override public void onCreate() {
        super.onCreate();
        h = new Handler(Looper.getMainLooper());
        db = Db.get(this);
        wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 55);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            persistRunning(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        begin();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        running = false;
        try { h.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { if (rx != null) unregisterReceiver(rx); } catch (Exception ignored) {}
        try { if (lm != null && locLis != null) lm.removeUpdates(locLis); } catch (Exception ignored) {}
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
        try { if (tone != null) tone.release(); } catch (Exception ignored) {}
        try { if (wm != null) wm.startScan(); } catch (Exception ignored) {}
        stopForeground(true);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- start

    private void begin() {
        if (running) { updateNotif(); return; }
        synchronized (session) { session.clear(); sessionSeen.clear(); }
        synchronized (primeSeen) { primeSeen.clear(); }
        lastNotified = -1;
        lastNotifText = "";
        scanFails = 0;
        pendingTargets = 0;
        estCount = 0;
        scanCycles = 0;
        sessionMeters = 0;
        startMs = System.currentTimeMillis();
        obsStamp.clear();
        obsPos.clear();
        estStamp.clear();
        haveFixSeed = false;
        persistRunning(true);
        createChannel();
        Notification n = build("sweep armed // acquiring fix");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NID, n);
        }
        running = true;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aegis:wardrive");
            wl.setReferenceCounted(false);
            try { wl.acquire(6 * 60 * 60 * 1000L); } catch (Exception ignored) {}
        }

        IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { harvest(); }
        };
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rx, f);

        startLocation();
        h.post(tick);
        try { if (wm != null) wm.startScan(); } catch (Exception ignored) {}
    }

    private void startLocation() {
        locLis = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (l == null) return;
                lat = l.getLatitude();
                lon = l.getLongitude();
                alt = l.hasAltitude() ? l.getAltitude() : 0;
                acc = l.hasAccuracy() ? l.getAccuracy() : 0;
                gpsFix = true;
                if (haveFixSeed) {
                    double d = Nav.dist(lastFixLat, lastFixLon, lat, lon);
                    // Ignore teleports: a 150 m jump in one fix is noise, not driving.
                    if (d > 1.5 && d < 150) sessionMeters += d;
                }
                lastFixLat = lat;
                lastFixLon = lon;
                haveFixSeed = true;
            }
        };
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try { lm.requestLocationUpdates(p, 1000L, 0f, locLis, Looper.getMainLooper()); }
            catch (Exception ignored) {}
        }
        try {
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) {
                lat = last.getLatitude(); lon = last.getLongitude();
                alt = last.hasAltitude() ? last.getAltitude() : 0;
                acc = last.hasAccuracy() ? last.getAccuracy() : 0;
                gpsFix = true;
                lastFixLat = lat; lastFixLon = lon; haveFixSeed = true;
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- scanning

    private void harvest() {
        if (wm == null) return;
        List<ScanResult> results;
        try { results = wm.getScanResults(); }
        catch (Exception e) { return; }
        if (results == null || results.isEmpty()) return;

        // Collapse multi-radio duplicates per BSSID, keeping the strongest level.
        Map<String, ScanResult> best = new HashMap<>();
        for (ScanResult r : results) {
            if (r == null || r.BSSID == null) continue;
            ScanResult cur = best.get(r.BSSID);
            if (cur == null || r.level > cur.level) best.put(r.BSSID, r);
        }

        long now = System.currentTimeMillis();
        boolean anyNew = false;
        for (ScanResult r : best.values()) {
            Hit hit = new Hit();
            hit.bssid = r.BSSID;
            hit.ssid = r.SSID == null ? "" : r.SSID;
            hit.auth = r.capabilities == null ? "" : r.capabilities;
            hit.channel = freqToChannel(r.frequency);
            hit.rssi = r.level;
            hit.best = r.level;
            hit.firstSeen = now;
            hit.lastSeen = now;
            hit.width = r.channelWidth > 0 ? r.channelWidth : 20;
            hit.std = standardOf(r);
            hit.judge();   // crypto, band, score, flags — the whole verdict

            boolean isNew = db.upsert(hit, lat, lon, alt, acc);
            observe(hit, now);
            boolean listNew;
            synchronized (session) {
                listNew = sessionSeen.add(hit.bssid);
                if (listNew) {
                    session.add(hit);
                } else {
                    for (Hit s : session) {
                        if (s.bssid.equals(hit.bssid)) {
                            s.rssi = hit.rssi;
                            s.lastSeen = now;
                            if (hit.rssi > s.best) s.best = hit.rssi;
                            s.score = hit.score;
                            s.crypto = hit.crypto;
                            s.wps = hit.wps;
                            s.wpsGuess = hit.wpsGuess;
                            break;
                        }
                    }
                }
            }
            if (isNew) anyNew = true;
            if (hit.isPrime()) {
                boolean announce;
                synchronized (primeSeen) { announce = primeSeen.add(hit.bssid); }
                if (announce) notifyTarget(hit);
            }
        }

        if (anyNew) beep();
        flushTargetSummary();
        updateNotif();
        Listener l = listener;
        if (l != null) {
            int n;
            synchronized (session) { n = session.size(); }
            l.onHits(n);
        }
    }

    // ------------------------------------------------------- evidence + estimate

    /**
     * Store a positioned observation when the node is newly heard OR the phone has
     * moved far enough (or waited long enough) to make a fresh sample meaningful.
     * Then, once the node has enough samples, multilaterate its transmitter.
     */
    private void observe(Hit h, long now) {
        if (!gpsFix || (lat == 0 && lon == 0)) return;

        long[] st = obsStamp.get(h.bssid);
        double[] pos = obsPos.get(h.bssid);
        boolean fresh;
        if (st == null || pos == null) {
            fresh = true;
        } else {
            double moved = Nav.dist(pos[0], pos[1], lat, lon);
            long since = now - st[0];
            fresh = moved >= OBS_MOVE_M || since >= OBS_IDLE_MS;
        }
        if (!fresh) return;

        db.recordObs(h.bssid, lat, lon, h.rssi, now);
        obsStamp.put(h.bssid, new long[]{now});
        obsPos.put(h.bssid, new double[]{lat, lon});

        Long lastEst = estStamp.get(h.bssid);
        if (lastEst != null && now - lastEst < EST_PERIOD_MS) return;

        int n = db.obsCount(h.bssid);
        if (n < EST_MIN_OBS) return;

        double[][] obs = db.obsFor(h.bssid, EST_MAX_OBS);
        double[] fix = Est.multilaterate(obs);
        if (fix == null) return;
        if (!Est.finite(fix[0]) || !Est.finite(fix[1])) return;
        db.estPut(h.bssid, fix[0], fix[1], fix[2], (int) fix[3]);
        estStamp.put(h.bssid, now);
        estCount++;
    }

    // ---------------------------------------------------------------- prime targets

    /**
     * One notification per newly seen prime target. Hard findings (WEP/OPEN) get
     * their own slot; WPS-likely gateways are grouped so a suburban street does
     * not bury the phone in alerts. Bursts collapse into a single summary.
     */
    private void notifyTarget(Hit h) {
        long now = System.currentTimeMillis();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (now - lastTargetNotify < 900L) { pendingTargets++; return; }
        lastTargetNotify = now;

        String tag = h.primeTag();
        String title = (h.isHardTarget() ? "◉ PRIME TARGET · " : "◎ WPS LIKELY · ") + tag;
        String body = (h.ssid.isEmpty() ? "<hidden>" : h.ssid)
                + "  ·  " + h.bssid
                + "  ·  " + h.rssi + " dBm  ·  ch" + h.channel
                + (h.isHardTarget() ? "  ·  exploit now" : "  ·  verify WPS/PIN");

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("target_bssid", h.bssid),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHAN_T)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("aegis.targets")
                .setNumber(primeSeen.size())
                .build();
        try { nm.notify(4800 + (h.bssid.hashCode() & 0x3FF), n); } catch (Exception ignored) {}
        try { if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120); }
        catch (Exception ignored) {}
    }

    /** Collapse a suppressed burst into one "N new targets" alert. */
    private void flushTargetSummary() {
        int pending = pendingTargets;
        if (pending <= 0) return;
        pendingTargets = 0;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        int total = primeSeen.size();
        String body = pending + " more prime target" + (pending == 1 ? "" : "s")
                + " this sweep  ·  " + total + " total";
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHAN_T)
                .setContentTitle("◉ TARGET SWEEP")
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup("aegis.targets")
                .setGroupSummary(true)
                .build();
        try { nm.notify(NID_SUMMARY, n); } catch (Exception ignored) {}
    }

    private void beep() {
        SharedPreferences sp = getSharedPreferences("wardrive", MODE_PRIVATE);
        if (!sp.getBoolean("sfx", true)) return;
        long now = System.currentTimeMillis();
        if (now - lastBeep < 280) return;
        lastBeep = now;
        try { if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 45); } catch (Exception ignored) {}
    }

    private static int freqToChannel(int freq) {
        if (freq >= 2412 && freq <= 2484) {
            return freq == 2484 ? 14 : (freq - 2407) / 5;
        }
        if (freq >= 5000 && freq < 5925) return (freq - 5000) / 5;
        if (freq >= 5955) return (freq - 5950) / 5;
        return 0;
    }

    /** PHY generation, from the radio when it will say, from band+width when it will not. */
    private static String standardOf(ScanResult r) {
        String caps = r.capabilities == null ? "" : r.capabilities.toUpperCase(java.util.Locale.US);
        if (caps.contains("11BE")) return "11be";
        if (caps.contains("11AX")) return "11ax";
        if (caps.contains("11AC")) return "11ac";
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                switch (r.getWifiStandard()) {
                    case ScanResult.WIFI_STANDARD_11BE: return "11be";
                    case ScanResult.WIFI_STANDARD_11AX: return "11ax";
                    case ScanResult.WIFI_STANDARD_11AC: return "11ac";
                    case ScanResult.WIFI_STANDARD_11N: return "11n";
                    case 1: return "11a";
                    case 2: return "11b";
                    case 3: return "11g";
                    default: break;
                }
            }
        } catch (Throwable ignored) { }
        if (r.frequency >= 5925) return "11ax";
        if (r.frequency >= 4900) return r.channelWidth > 20 ? "11ac" : "11n";
        return "11n";
    }

    // ---------------------------------------------------------------- notification

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHAN) == null) {
            NotificationChannel ch = new NotificationChannel(CHAN, "Wardrive sweep",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active 802.11 capture");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHAN_T) == null) {
            NotificationChannel t = new NotificationChannel(CHAN_T, "Prime targets",
                    NotificationManager.IMPORTANCE_HIGH);
            t.setDescription("WEP, open, and WPS-exposed access points");
            t.enableVibration(true);
            t.setVibrationPattern(new long[]{0, 90, 60, 160});
            t.enableLights(true);
            t.setLightColor(Color.RED);
            t.setShowBadge(true);
            t.setBypassDnd(false);
            nm.createNotificationChannel(t);
        }
    }

    private Notification build(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, WardriveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHAN)
                .setContentTitle("AEGIS//WARDRIVE")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "STOP", stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotif() {
        int n;
        synchronized (session) { n = session.size(); }
        int primes;
        synchronized (primeSeen) { primes = primeSeen.size(); }
        boolean radioOff = wm == null || !wm.isWifiEnabled();
        String s = n + " nodes captured"
                + (primes > 0 ? "  //  " + primes + " PRIME" : "")
                + (gpsFix ? " // GPS LOCK" : " // NO FIX")
                + (radioOff ? " // WIFI OFF" : scanFails >= 3 ? " // SCAN THROTTLED" : "");
        if (n == lastNotified && s.equals(lastNotifText)) return;
        lastNotified = n;
        lastNotifText = s;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try { nm.notify(NID, build(s)); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- persistence

    /** Remember that a sweep was live so a reboot can resume it. */
    private void persistRunning(boolean on) {
        try {
            getSharedPreferences("wardrive", MODE_PRIVATE)
                    .edit().putBoolean("resume_sweep", on).apply();
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- public API

    public static void start(Context c) {
        Intent i = new Intent(c, WardriveService.class).setAction(ACTION_START);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        try { c.startService(new Intent(c, WardriveService.class).setAction(ACTION_STOP)); }
        catch (Throwable ignored) {}
    }

    /** Auto-resume after boot when the operator left a sweep running. */
    public static void resumeIfArmed(Context c) {
        boolean armed = c.getSharedPreferences("wardrive", Context.MODE_PRIVATE)
                .getBoolean("resume_sweep", false);
        if (!armed) return;
        try { start(c); } catch (Throwable ignored) {}
    }

    public static boolean isRunning() { return running; }

    public static boolean hasFix() { return gpsFix; }

    public static double lat() { return lat; }
    public static double lon() { return lon; }

    public static long startedAt() { return startMs; }
    public static double meters() { return sessionMeters; }
    public static int estimates() { return estCount; }
    public static int scans() { return scanCycles; }

    public static String elapsed() {
        if (startMs <= 0) return "--:--";
        long s = Math.max(0, (System.currentTimeMillis() - startMs) / 1000L);
        long m = s / 60, sec = s % 60, hr = m / 60;
        if (hr > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", hr, m % 60, sec);
        return String.format(java.util.Locale.US, "%02d:%02d", m, sec);
    }

    public static double nodesPerMinute() {
        if (startMs <= 0) return 0;
        double mins = Math.max(1.0 / 60.0, (System.currentTimeMillis() - startMs) / 60000.0);
        int n;
        synchronized (session) { n = session.size(); }
        return n / mins;
    }

    public static List<Hit> snapshot() {
        List<Hit> copy;
        synchronized (session) { copy = new ArrayList<>(session); }
        // Prime targets rise to the top, then strongest signal first.
        Collections.sort(copy, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                int pa = a.isPrime() ? 1 : 0, pb = b.isPrime() ? 1 : 0;
                if (pa != pb) return pb - pa;
                return b.rssi - a.rssi;
            }
        });
        return copy;
    }

    public static int primeCount() {
        synchronized (primeSeen) { return primeSeen.size(); }
    }

    public static int sessionCount() {
        synchronized (session) { return session.size(); }
    }

    public static void setListener(Listener l) { listener = l; }
}
