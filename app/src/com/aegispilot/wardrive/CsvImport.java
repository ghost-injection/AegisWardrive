package com.aegispilot.wardrive;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * Streaming WiGLE wardrive CSV ingester.
 *
 * Accepts the exact files WiGLE exports / expects on upload:
 *   WigleWifi-1.4 header block, then a CSV header row with MAC,SSID,AuthMode,
 *   FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,
 *   AccuracyMeters,Type (order may vary; we map by name).
 *
 * Handles .csv and .csv.gz, quoted SSIDs with "" escapes, Type != WIFI rows,
 * and inserts in transactions so a multi-hundred-thousand row file lands in
 * minutes instead of hours.
 */
public final class CsvImport {

    public interface Progress {
        void state(String msg, int rows, int inserted, int skipped);
        boolean cancelled();
    }

    /** Hard ceiling per import run so a 6.5M row national dump cannot hang the phone. */
    public static final int DEFAULT_CAP = 750000;

    public static final class Result {
        public int rows, inserted, skipped, capped;
        public String note;
    }

    private CsvImport() {}

    public static Result run(Context c, InputStream raw, String name, Progress cb) throws IOException {
        Result r = new Result();
        Db db = Db.get(c);
        InputStream in = gunzipIfNeeded(raw);

        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        String line = br.readLine();
        if (line == null) throw new IOException("empty file");

        // WiGLE exports start with a WigleWifi-x.y metadata line, then the header.
        if (line.startsWith("WigleWifi") || line.startsWith("MAC,") == false) {
            if (line.startsWith("WigleWifi")) {
                line = br.readLine();
                if (line == null) throw new IOException("no header row");
            }
        }
        Map<String, Integer> idx = headerIndex(split(line));
        if (!idx.containsKey("mac")) throw new IOException("not a WiGLE csv: no MAC column");

        int iMac = idx.get("mac");
        int iSsid = pick(idx, "ssid");
        int iAuth = pick(idx, "authmode");
        int iFirst = pick(idx, "firstseen");
        int iCh = pick(idx, "channel");
        int iRssi = pick(idx, "rssi");
        int iLat = pick(idx, "currentlatitude");
        int iLon = pick(idx, "currentlongitude");
        int iAlt = pick(idx, "altitudemeters");
        int iAcc = pick(idx, "accuracymeters");
        int iType = pick(idx, "type");

        SimpleDateFormat wf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        wf.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis() / 1000L;

        List<Hit> batch = new ArrayList<>(512);
        while ((line = br.readLine()) != null) {
            if (cb != null && (r.rows & 0x3FF) == 0 && cb.cancelled()) {
                r.note = "cancelled by operator";
                break;
            }
            if (line.length() < 8) continue;
            r.rows++;
            String[] f = split(line);
            if (f.length <= iMac) { r.skipped++; continue; }
            String mac = clean(f[iMac]).toUpperCase(Locale.US);
            if (mac.length() < 11) { r.skipped++; continue; }

            if (iType >= 0 && f.length > iType) {
                String t = clean(f[iType]).toUpperCase(Locale.US);
                if (!t.isEmpty() && !t.equals("WIFI")) { r.skipped++; continue; }
            }

            double lat = num(f, iLat), lon = num(f, iLon);
            if (lat == 0 && lon == 0) { r.skipped++; continue; }

            Hit h = new Hit();
            h.bssid = mac;
            h.ssid = iSsid >= 0 && f.length > iSsid ? clean(f[iSsid]) : "";
            h.auth = iAuth >= 0 && f.length > iAuth ? clean(f[iAuth]) : "";
            h.channel = (int) num(f, iCh);
            h.rssi = (int) num(f, iRssi);
            h.best = h.rssi;
            h.lat = lat;
            h.lon = lon;
            h.alt = num(f, iAlt);
            h.acc = num(f, iAcc);
            h.firstSeen = parseTime(wf, f, iFirst, now);
            h.lastSeen = h.firstSeen;
            h.seen = 1;
            h.judge();

            batch.add(h);
            r.inserted++;
            if (batch.size() >= 400) {
                db.bulk(batch, lat, lon, h.alt, h.acc);
                batch.clear();
                if (cb != null && (r.inserted & 0x7FF) == 0) {
                    cb.state(name, r.rows, r.inserted, r.skipped);
                }
            }
            if (r.inserted >= DEFAULT_CAP) {
                r.capped = 1;
                break;
            }
        }
        if (!batch.isEmpty()) db.bulk(batch, batch.get(0).lat, batch.get(0).lon, 0, 0);
        br.close();
        if (cb != null) cb.state(name, r.rows, r.inserted, r.skipped);
        if (r.note == null) {
            r.note = r.capped == 1
                    ? "capped at " + DEFAULT_CAP + " rows (run again for the rest)"
                    : "ok";
        }
        return r;
    }

    private static InputStream gunzipIfNeeded(InputStream in) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(in, 2);
        byte[] m = new byte[2];
        int n = pb.read(m);
        if (n == 2) pb.unread(m, 0, 2);
        if (n == 2 && (m[0] & 0xFF) == 0x1F && (m[1] & 0xFF) == 0x8B) {
            return new GZIPInputStream(pb, 1 << 16);
        }
        return pb;
    }

    private static Map<String, Integer> headerIndex(String[] cols) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < cols.length; i++) {
            String k = cols[i].trim().toLowerCase(Locale.US).replace("\uFEFF", "").replace("\"", "");
            m.put(k, i);
        }
        // tolerate the short WiGLE-BLE form and minor renames
        if (!m.containsKey("currentlatitude") && m.containsKey("latitude")) m.put("currentlatitude", m.get("latitude"));
        if (!m.containsKey("currentlongitude") && m.containsKey("longitude")) m.put("currentlongitude", m.get("longitude"));
        if (!m.containsKey("authmode") && m.containsKey("auth")) m.put("authmode", m.get("auth"));
        return m;
    }

    private static int pick(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? -1 : v;
    }

    private static double num(String[] f, int i) {
        if (i < 0 || f.length <= i) return 0;
        try { return Double.parseDouble(clean(f[i])); }
        catch (Exception e) { return 0; }
    }

    private static long parseTime(SimpleDateFormat f, String[] row, int i, long fallback) {
        if (i < 0 || row.length <= i) return fallback;
        String v = clean(row[i]);
        if (v.isEmpty()) return fallback;
        try { Date dt = f.parse(v); return dt == null ? fallback : dt.getTime() / 1000L; }
        catch (Exception e) { return fallback; }
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    /** CSV split honoring double quotes. */
    private static String[] split(String line) {
        List<String> out = new ArrayList<>(16);
        StringBuilder sb = new StringBuilder(line.length() / 4 + 8);
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                q = !q;
                sb.append(ch);
            } else if (ch == ',' && !q) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }
}
