package com.aegispilot.wardrive;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Single owner of wardrive.db.
 *
 * v3 adds two tables that make transmitter estimation possible:
 *   obs — every observation of a BSSID at a position (the raw evidence)
 *   est — the multilaterated transmitter position derived from those obs
 *
 * Everything in this file speaks MILLISECONDS. The CSV importer used to write
 * seconds, which exported as 1970; read() repairs any legacy second-scale row
 * it encounters.
 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "wardrive.db";
    private static final int VERSION = 4;
    private static Db INSTANCE;

    public static synchronized Db get(Context c) {
        if (INSTANCE == null) INSTANCE = new Db(c.getApplicationContext());
        return INSTANCE;
    }

    private Db(Context c) { super(c, NAME, null, VERSION); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE hits (" +
                "bssid TEXT PRIMARY KEY," +
                "ssid TEXT," +
                "auth TEXT," +
                "first_seen INTEGER," +
                "last_seen INTEGER," +
                "channel INTEGER," +
                "best_rssi INTEGER," +
                "last_rssi INTEGER," +
                "lat REAL," +
                "lon REAL," +
                "alt REAL," +
                "acc REAL," +
                "seen INTEGER," +
                "crypto TEXT," +
                "band INTEGER," +
                "width INTEGER," +
                "std TEXT," +
                "score INTEGER," +
                "why TEXT," +
                "wps INTEGER," +
                "pmf INTEGER," +
                "hidden INTEGER," +
                "tagged INTEGER DEFAULT 0," +
                "note TEXT," +
                "was_hidden INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_last ON hits(last_seen)");
        db.execSQL("CREATE INDEX idx_score ON hits(score)");
        db.execSQL("CREATE INDEX idx_geo ON hits(lat,lon)");
        createObs(db);
        createEst(db);
    }

    private void createObs(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS obs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "bssid TEXT," +
                "lat REAL," +
                "lon REAL," +
                "rssi INTEGER," +
                "ts INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_bssid ON obs(bssid, ts)");
    }

    private void createEst(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS est (" +
                "bssid TEXT PRIMARY KEY," +
                "lat REAL," +
                "lon REAL," +
                "unc REAL," +
                "obs INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            String[] cols = {
                    "crypto TEXT", "band INTEGER", "width INTEGER", "std TEXT",
                    "score INTEGER", "why TEXT", "wps INTEGER", "pmf INTEGER",
                    "hidden INTEGER", "tagged INTEGER DEFAULT 0", "note TEXT"
            };
            for (String col : cols) {
                try { db.execSQL("ALTER TABLE hits ADD COLUMN " + col); } catch (Exception ignored) { }
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_score ON hits(score)");
        }
        if (o < 3) {
            createObs(db);
            createEst(db);
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_geo ON hits(lat,lon)"); }
            catch (Exception ignored) { }
        }
        if (o < 4) {
            // was_hidden: the AP was masked when first captured. If a name later
            // appears on the same BSSID, the network effectively unmasked itself.
            try { db.execSQL("ALTER TABLE hits ADD COLUMN was_hidden INTEGER DEFAULT 0"); }
            catch (Exception ignored) { }
            try { db.execSQL("UPDATE hits SET was_hidden=1 WHERE hidden=1"); }
            catch (Exception ignored) { }
        }
    }

    /**
     * A helper that opens this same file at a different schema version would
     * downgrade-crash every later open. Absorb it instead of dying.
     */
    @Override
    public void onDowngrade(SQLiteDatabase db, int o, int n) {
        onUpgrade(db, n, o);
    }

    /** @return true when this BSSID was never seen before. */
    public boolean upsert(Hit h, double lat, double lon, double alt, double acc) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("bssid", h.bssid);
        cv.put("ssid", h.ssid == null ? "" : h.ssid);
        cv.put("auth", h.auth == null ? "" : h.auth);
        cv.put("first_seen", h.firstSeen);
        cv.put("last_seen", h.lastSeen);
        cv.put("channel", h.channel);
        cv.put("best_rssi", h.rssi);
        cv.put("last_rssi", h.rssi);
        cv.put("lat", lat);
        cv.put("lon", lon);
        cv.put("alt", alt);
        cv.put("acc", acc);
        cv.put("seen", 1);
        cv.put("crypto", h.crypto);
        cv.put("band", h.band);
        cv.put("width", h.width);
        cv.put("std", h.std);
        cv.put("score", h.score);
        cv.put("why", h.why);
        cv.put("wps", h.wps ? 1 : 0);
        cv.put("pmf", h.pmf ? 1 : 0);
        cv.put("hidden", h.hidden ? 1 : 0);
        cv.put("was_hidden", h.hidden ? 1 : 0);

        long id = db.insertWithOnConflict("hits", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) {
            // Keep the earliest sighting, the strongest signal, and only move the
            // position forward in time so an old CSV row cannot rewind a live fix.
            db.execSQL("UPDATE hits SET last_seen=MAX(last_seen,?), first_seen=MIN(first_seen,?)," +
                            "last_rssi=?, best_rssi=MAX(best_rssi,?), " +
                            "seen=seen+1, ssid=?, auth=?, channel=?, crypto=?, band=?, width=?, " +
                            "std=?, score=?, why=?, wps=?, pmf=?, hidden=?," +
                            // SQLite assigns from OLD values, so hidden=1 still means
                            // "was hidden" when the reveal check runs. A name arriving
                            // on a previously masked BSSID is an automatic unmask.
                            "was_hidden=CASE WHEN hidden=1 AND ?<>'' THEN 1 ELSE was_hidden END," +
                            "lat=CASE WHEN ?>=last_seen THEN ? ELSE lat END," +
                            "lon=CASE WHEN ?>=last_seen THEN ? ELSE lon END," +
                            "alt=CASE WHEN ?>=last_seen THEN ? ELSE alt END," +
                            "acc=CASE WHEN ?>=last_seen THEN ? ELSE acc END " +
                            "WHERE bssid=?",
                    new Object[]{h.lastSeen, h.firstSeen, h.rssi, h.rssi, h.ssid == null ? "" : h.ssid,
                            h.auth == null ? "" : h.auth, h.channel, h.crypto, h.band, h.width,
                            h.std, h.score, h.why, h.wps ? 1 : 0, h.pmf ? 1 : 0, h.hidden ? 1 : 0,
                            h.ssid == null ? "" : h.ssid,
                            h.lastSeen, lat, h.lastSeen, lon, h.lastSeen, alt, h.lastSeen, acc,
                            h.bssid});
            return false;
        }
        return true;
    }

    private static final String COLS = "bssid,ssid,auth,first_seen,last_seen,channel,best_rssi,lat,lon,alt,acc,seen," +
            "crypto,band,width,std,score,why,wps,pmf,hidden,tagged,note,was_hidden";

    /**
     * Bulk ingest for CSV imports: one transaction, index created once.
     * Each hit carries its own coordinates — never a shared batch position.
     */
    public int bulk(List<Hit> list) {
        if (list == null || list.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_geo ON hits(lat,lon)");
        int n = 0;
        db.beginTransaction();
        try {
            for (Hit h : list) {
                upsert(h, h.lat, h.lon, h.alt, h.acc);
                n++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return n;
    }

    /** Back-compat shim: coordinates come from each Hit, not from the caller. */
    public int bulk(List<Hit> list, double lat, double lon, double alt, double acc) {
        return bulk(list);
    }

    // ------------------------------------------------------------------ obs

    /** One raw sighting. Caller throttles; this always writes. */
    public void recordObs(String bssid, double lat, double lon, int rssi, long ts) {
        if (bssid == null || bssid.isEmpty()) return;
        if (lat == 0 && lon == 0) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put("bssid", bssid);
            cv.put("lat", lat);
            cv.put("lon", lon);
            cv.put("rssi", rssi);
            cv.put("ts", ts);
            getWritableDatabase().insert("obs", null, cv);
        } catch (Throwable ignored) { }
    }

    /** @return rows of {lat, lon, rssi} for multilateration. */
    public double[][] obsFor(String bssid, int limit) {
        List<double[]> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT lat,lon,rssi FROM obs WHERE bssid=? ORDER BY ts DESC LIMIT " +
                            Math.max(1, limit), new String[]{bssid});
            while (c.moveToNext()) out.add(new double[]{c.getDouble(0), c.getDouble(1), c.getDouble(2)});
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        return out.toArray(new double[0][]);
    }

    public int obsCount(String bssid) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM obs WHERE bssid=?",
                    new String[]{bssid});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        return 0;
    }

    // ------------------------------------------------------------------ est

    public void estPut(String bssid, double lat, double lon, double unc, int obs) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("bssid", bssid);
            cv.put("lat", lat);
            cv.put("lon", lon);
            cv.put("unc", unc);
            cv.put("obs", obs);
            getWritableDatabase().insertWithOnConflict("est", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable ignored) { }
    }

    /** @return {lat, lon, unc, obs} or null. */
    public double[] estGet(String bssid) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT lat,lon,unc,obs FROM est WHERE bssid=?", new String[]{bssid});
            if (c.moveToNext()) {
                return new double[]{c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)};
            }
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        return null;
    }

    public int estCount() { return countWhere("1=1", "est"); }

    /** All computed transmitter estimates, keyed by BSSID. */
    public java.util.Map<String, double[]> estAll() {
        java.util.Map<String, double[]> out = new java.util.HashMap<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT bssid,lat,lon,unc,obs FROM est", null);
            while (c.moveToNext()) {
                out.put(c.getString(0), new double[]{c.getDouble(1), c.getDouble(2),
                        c.getDouble(3), c.getDouble(4)});
            }
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        return out;
    }

    // ---------------------------------------------------------------- reads

    /** Nodes inside a lat/lon box, best score first. Used by the HUD map. */
    public List<Hit> near(double lat, double lon, double meters, int limit) {
        double dLat = meters / 111320.0;
        double dLon = meters / (111320.0 * Math.max(0.15, Math.cos(Math.toRadians(lat))));
        List<Hit> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM hits WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
                        "ORDER BY score DESC LIMIT " + Math.max(1, limit),
                new String[]{String.valueOf(lat - dLat), String.valueOf(lat + dLat),
                        String.valueOf(lon - dLon), String.valueOf(lon + dLon)});
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    /** Everything that carries coordinates, best first. Fallback when no fix. */
    public List<Hit> geotagged(int limit) {
        List<Hit> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM hits WHERE (lat != 0 OR lon != 0) " +
                        "ORDER BY score DESC, last_seen DESC LIMIT " + Math.max(1, limit), null);
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    /** Legacy rows written in seconds would sort as 1970. Repair on read. */
    private static long ms(long t) {
        if (t <= 0) return t;
        return t < 100000000000L ? t * 1000L : t;
    }

    private Hit read(Cursor c) {
        Hit h = new Hit();
        h.bssid = c.getString(0);
        h.ssid = c.getString(1);
        h.auth = c.getString(2);
        h.firstSeen = ms(c.getLong(3));
        h.lastSeen = ms(c.getLong(4));
        h.channel = c.getInt(5);
        h.best = c.getInt(6);
        h.rssi = c.getInt(6);
        h.lat = c.getDouble(7);
        h.lon = c.getDouble(8);
        h.alt = c.getDouble(9);
        h.acc = c.getDouble(10);
        h.seen = c.getInt(11);
        h.crypto = c.isNull(12) ? "" : c.getString(12);
        h.band = c.isNull(13) ? 0 : c.getInt(13);
        h.width = c.isNull(14) ? 0 : c.getInt(14);
        h.std = c.isNull(15) ? "" : c.getString(15);
        h.score = c.isNull(16) ? 0 : c.getInt(16);
        h.why = c.isNull(17) ? "" : c.getString(17);
        h.wps = !c.isNull(18) && c.getInt(18) == 1;
        h.pmf = !c.isNull(19) && c.getInt(19) == 1;
        h.hidden = !c.isNull(20) && c.getInt(20) == 1;
        h.tagged = !c.isNull(21) && c.getInt(21) == 1;
        h.note = c.isNull(22) ? "" : c.getString(22);
        h.wasHidden = !c.isNull(23) && c.getInt(23) == 1;
        if (h.crypto == null || h.crypto.isEmpty() || h.score == 0) h.judge();
        return h;
    }

    private List<Hit> query(String where, String order, String... args) {
        List<Hit> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM hits" + (where == null ? "" : " WHERE " + where) +
                        " ORDER BY " + order, args);
        while (c.moveToNext()) out.add(read(c));
        c.close();
        return out;
    }

    public List<Hit> all() { return query(null, "score DESC, last_seen DESC"); }

    /** Everything worth walking toward, best first. */
    public List<Hit> targets(int minScore) {
        return query("score >= ?", "score DESC, last_seen DESC", String.valueOf(minScore));
    }

    public List<Hit> tagged() {
        return query("tagged = 1", "score DESC, last_seen DESC");
    }

    /** WEP, OPEN and confirmed-WPS only — the export worth handing to a radio. */
    public List<Hit> primes() {
        return query("(crypto='WEP' OR crypto='OPEN' OR wps=1)", "score DESC, last_seen DESC");
    }

    public Hit get(String bssid) {
        List<Hit> l = query("bssid = ? COLLATE NOCASE", "last_seen DESC", bssid);
        return l.isEmpty() ? null : l.get(0);
    }

    public void setTagged(String bssid, boolean on) {
        getWritableDatabase().execSQL("UPDATE hits SET tagged=? WHERE bssid=?",
                new Object[]{on ? 1 : 0, bssid});
    }

    public void setNote(String bssid, String note) {
        getWritableDatabase().execSQL("UPDATE hits SET note=? WHERE bssid=?",
                new Object[]{note == null ? "" : note, bssid});
    }

    public int count() { return countWhere(null, "hits"); }

    public int countOpen() { return countWhere("crypto='OPEN' OR auth=''", "hits"); }

    public int countWep() { return countWhere("crypto='WEP' OR auth LIKE '%WEP%'", "hits"); }

    public int countWpa() { return countWhere("crypto NOT IN ('OPEN','WEP','') AND crypto IS NOT NULL", "hits"); }

    public int countWpa3() { return countWhere("crypto LIKE 'WPA3%' OR crypto LIKE 'WPA2/WPA3%'", "hits"); }

    public int countWps() { return countWhere("wps=1", "hits"); }

    public int countCritical() { return countWhere("score >= " + WifiFacts.TIER_CRIT, "hits"); }

    public int countPrime() { return countWhere("(crypto='WEP' OR crypto='OPEN' OR wps=1)", "hits"); }

    public int countFive() { return countWhere("channel > 14", "hits"); }

    public int countSix() { return countWhere("band = 60", "hits"); }

    public int countToday(long since) { return countWhere("last_seen >= " + since, "hits"); }

    public int countTagged() { return countWhere("tagged = 1", "hits"); }

    /** Currently masked: still broadcasting no SSID. */
    public int countHidden() { return countWhere("hidden = 1", "hits"); }

    /** Masked when first captured, now broadcasting a name. The money stat. */
    public int countUnmasked() { return countWhere("was_hidden = 1 AND hidden = 0 AND ssid <> ''", "hits"); }

    /** Every masked network, strongest first — the unmask worklist. */
    public List<Hit> hidden() {
        return query("hidden = 1", "rssi DESC, last_seen DESC");
    }

    private int countWhere(String where, String table) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where), null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    public void purge() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM hits");
        db.execSQL("DELETE FROM obs");
        db.execSQL("DELETE FROM est");
    }
}
