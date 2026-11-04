package com.aegispilot.wardrive;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * KML + GeoJSON writers.
 *
 * The WiGLE CSV is the archival format; this is the one you can actually look
 * at. Drop the KML on Google Earth and every node shows up as a labelled pin
 * coloured by threat tier, with the BSSID, crypto, channel and score in the
 * balloon. GeoJSON exists for QGIS / kepler.gl / anything web-shaped.
 */
public final class KmlExporter {

    public static final int KML = 0;
    public static final int GEOJSON = 1;

    private KmlExporter() {}

    // ---------------------------------------------------------------- KML

    public static String kml(List<Hit> hits) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n");
        sb.append("<name>AegisWardrive capture</name>\n");
        sb.append("<description>").append(esc(today())).append(" · ")
          .append(hits.size()).append(" nodes</description>\n");

        sb.append(style("crit", "ff2222ff", "ff0000ff"));
        sb.append(style("high", "ff00a5ff", "ff0080ff"));
        sb.append(style("med",  "ffffb400", "ffff9900"));
        sb.append(style("low",  "ff8c8c8c", "ff666666"));

        for (Hit h : hits) {
            String tier = h.score >= WifiFacts.TIER_CRIT ? "crit"
                    : h.score >= WifiFacts.TIER_HIGH ? "high"
                    : h.score >= WifiFacts.TIER_MED ? "med" : "low";
            sb.append("<Placemark>\n");
            sb.append("<name>").append(esc(h.ssidOrGhost())).append("</name>\n");
            sb.append("<styleUrl>#").append(tier).append("</styleUrl>\n");
            sb.append("<description><![CDATA[")
              .append("BSSID: ").append(h.bssid).append("<br/>")
              .append("Crypto: ").append(h.kind()).append("<br/>")
              .append("Band: ").append(h.band()).append(" GHz · ch").append(h.channel).append("<br/>")
              .append("Width: ").append(h.width).append(" MHz · ").append(h.std).append("<br/>")
              .append("RSSI: ").append(h.rssi).append(" dBm<br/>")
              .append("Vendor: ").append(h.vendor().isEmpty() ? "unknown" : h.vendor()).append("<br/>")
              .append("Score: ").append(h.score).append(" (").append(h.tier()).append(")<br/>")
              .append("Why: ").append(h.why).append("<br/>")
              .append("Seen: ").append(h.seen).append("×<br/>")
              .append(h.note == null || h.note.isEmpty() ? "" : "Note: " + h.note + "<br/>")
              .append("]]></description>\n");
            sb.append("<Point><coordinates>")
              .append(String.format(Locale.US, "%.6f,%.6f,%.0f", h.lon, h.lat, h.alt))
              .append("</coordinates></Point>\n");
            sb.append("</Placemark>\n");
        }
        sb.append("</Document>\n</kml>\n");
        return sb.toString();
    }

    private static String style(String id, String lineColor, String iconColor) {
        return "<Style id=\"" + id + "\">" +
                "<IconStyle><color>" + iconColor + "</color><scale>0.9</scale>" +
                "<Icon><href>http://maps.google.com/mapfiles/kml/shapes/wifi.png</href></Icon>" +
                "</IconStyle>" +
                "<LabelStyle><color>" + lineColor + "</color><scale>0.8</scale></LabelStyle>" +
                "<LineStyle><color>" + lineColor + "</color><width>1</width></LineStyle>" +
                "</Style>\n";
    }

    // ---------------------------------------------------------------- GeoJSON

    public static String geoJson(List<Hit> hits) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\"type\":\"FeatureCollection\",\"name\":\"AegisWardrive\",")
          .append("\"features\":[");
        boolean first = true;
        for (Hit h : hits) {
            if (h.lat == 0 && h.lon == 0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
              .append(String.format(Locale.US, "%.6f,%.6f,%.0f", h.lon, h.lat, h.alt))
              .append("]},\"properties\":{")
              .append("\"ssid\":\"").append(js(h.ssidOrGhost())).append("\",")
              .append("\"bssid\":\"").append(js(h.bssid)).append("\",")
              .append("\"crypto\":\"").append(js(h.kind())).append("\",")
              .append("\"channel\":").append(h.channel).append(',')
              .append("\"band\":\"").append(h.band()).append("\",")
              .append("\"width_mhz\":").append(h.width).append(',')
              .append("\"standard\":\"").append(js(h.std)).append("\",")
              .append("\"rssi\":").append(h.rssi).append(',')
              .append("\"score\":").append(h.score).append(',')
              .append("\"tier\":\"").append(h.tier()).append("\",")
              .append("\"wps\":").append(h.wps).append(',')
              .append("\"pmf\":").append(h.pmf).append(',')
              .append("\"hidden\":").append(h.hidden).append(',')
              .append("\"tagged\":").append(h.tagged).append(',')
              .append("\"seen\":").append(h.seen).append(',')
              .append("\"vendor\":\"").append(js(h.vendor())).append("\",")
              .append("\"why\":\"").append(js(h.why)).append("\",")
              .append("\"note\":\"").append(js(h.note)).append("\"")
              .append("}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ---------------------------------------------------------------- writers

    public static Uri export(Context c, List<Hit> hits, int kind) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = kind == KML ? "AegisWardrive_" + stamp + ".kml"
                : "AegisWardrive_" + stamp + ".geojson";
        String mime = kind == KML ? "application/vnd.google-earth.kml+xml" : "application/geo+json";

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/AegisWardrive");
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("MediaStore insert failed");
        try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("no output stream");
            String body = kind == KML ? kml(hits) : geoJson(hits);
            os.write(body.getBytes("UTF-8"));
            os.flush();
        }
        return uri;
    }

    public static Intent shareIntent(Context c, Uri uri, int kind) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(kind == KML ? "application/vnd.google-earth.kml+xml" : "application/geo+json");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_SUBJECT, "AegisWardrive " + (kind == KML ? "KML" : "GeoJSON"));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    // ---------------------------------------------------------------- helpers

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    if (ch >= 32) b.append(ch);
            }
        }
        return b.toString();
    }

    private static String js(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else if (ch >= 32) b.append(ch);
        }
        return b.toString();
    }
}
