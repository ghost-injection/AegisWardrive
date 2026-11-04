package com.aegispilot.wardrive;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * WiGLE 1.4 CSV writer. The column order and header lines are byte-for-byte what
 * WiGLE's importer expects, so these files can be uploaded (or fed to any other
 * wardriving tool) without transformation.
 */
public final class CsvExporter {

    public static final String APP_RELEASE = "AegisWardrive-1.0";

    private CsvExporter() {}

    public static String header() {
        StringBuilder sb = new StringBuilder();
        sb.append("WigleWifi-1.4")
          .append(",appRelease=").append(APP_RELEASE)
          .append(",model=").append(esc(Build.MODEL))
          .append(",release=").append(esc(Build.VERSION.RELEASE))
          .append(",device=").append(esc(Build.DEVICE))
          .append(",display=").append(esc(Build.DISPLAY))
          .append(",brand=").append(esc(Build.BRAND))
          .append(",board=").append(esc(Build.BOARD))
          .append(",starred=false")
          .append(",comment=")
          .append("\n");
        sb.append("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,")
          .append("AltitudeMeters,AccuracyMeters,Type,")
          .append("Crypto,Band,WidthMhz,Standard,WPS,PMF,Score,Tier,Flags,Vendor,Note\n");
        return sb.toString();
    }

    public static String rows(List<Hit> hits) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            sb.append(esc(h.bssid)).append(',')
              .append(esc(clean(h.ssid))).append(',')
              .append(esc(h.auth)).append(',')
              .append(fmt.format(new Date(h.firstSeen))).append(',')
              .append(h.channel).append(',')
              .append(h.rssi).append(',')
              .append(fmt(h.lat)).append(',')
              .append(fmt(h.lon)).append(',')
              .append(fmt(h.alt)).append(',')
              .append(fmt(h.acc)).append(',')
              .append("WIFI").append(',')
              .append(esc(h.kind())).append(',')
              .append(h.band()).append(',')
              .append(h.width).append(',')
              .append(esc(h.std)).append(',')
              .append(h.wps ? 1 : 0).append(',')
              .append(h.pmf ? 1 : 0).append(',')
              .append(h.score).append(',')
              .append(h.tier()).append(',')
              .append(esc(h.why)).append(',')
              .append(esc(h.vendor())).append(',')
              .append(esc(h.note)).append('\n');
        }
        return sb.toString();
    }

    public static String filename() {
        return "AegisWardrive_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
    }

    /** Writes to the public Downloads folder via MediaStore (scoped storage safe). */
    public static Uri export(Context c, List<Hit> hits) throws Exception {
        String name = filename();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AegisWardrive");
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("MediaStore insert failed");
        try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("no output stream");
            os.write(header().getBytes("UTF-8"));
            os.write(rows(hits).getBytes("UTF-8"));
            os.flush();
        }
        return uri;
    }

    public static Intent shareIntent(Context c, Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_SUBJECT, "AegisWardrive capture");
        i.putExtra(Intent.EXTRA_TEXT, "WiGLE 1.4 CSV // AegisWardrive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') sb.append(' ');
            else if (ch >= 32) sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** CSV field escape: quote when the value holds a delimiter, quote or newline. */
    private static String esc(String v) {
        if (v == null) return "";
        boolean need = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!need) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
