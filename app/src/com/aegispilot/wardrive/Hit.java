package com.aegispilot.wardrive;

import java.util.Locale;

/** One captured access point — with its intelligence verdict attached. */
public class Hit {
    public String bssid = "";
    public String ssid = "";
    public String auth = "";          // raw capability string from the radio
    public int channel;
    public int rssi = -100;
    public int best = -100;
    public long firstSeen;
    public long lastSeen;
    public double lat;
    public double lon;
    public double alt;
    public double acc;
    public int seen = 1;

    // ---- derived intelligence (persisted so old rows stay cheap to render) ----
    public String crypto = "";
    public int band;                  // 24 / 50 / 60
    public int width;                 // MHz
    public String std = "";           // 11n / 11ac / 11ax / 11be
    public int score;
    public String why = "";
    public boolean wps;
    public boolean wpsGuess;
    public boolean pmf;
    public boolean hidden;
    /** Was masked when first captured. If it now has a name, it unmasked itself. */
    public boolean wasHidden;
    public boolean tagged;
    public String note = "";

    /** Fill derived fields from the raw radio data. Idempotent. */
    public Hit judge() {
        WifiFacts f = WifiFacts.of(ssid, auth, freqOf(channel), width, std, rssi, bssid);
        crypto = f.crypto;
        band = f.band;
        if (width <= 0) width = f.width;
        wps = f.wps;
        wpsGuess = f.wpsGuess;
        pmf = f.pmf;
        hidden = f.hidden;
        score = f.score;
        why = f.why;
        if (std == null || std.isEmpty()) std = f.standard;
        return this;
    }

    public static int freqOf(int ch) {
        if (ch == 14) return 2484;
        if (ch >= 1 && ch <= 13) return 2407 + ch * 5;
        if (ch >= 32 && ch <= 177) return 5000 + ch * 5;
        if (ch >= 181 && ch <= 233) return 5950 + ch * 5;
        return 2412;
    }

    public boolean isOpen() { return WifiFacts.OPEN.equals(crypto) || authUpper().isEmpty(); }

    public boolean isWep() { return WifiFacts.WEP.equals(crypto) || authUpper().contains("WEP"); }

    public boolean isWpa() {
        String a = authUpper();
        return a.contains("WPA") || a.contains("RSN") || a.contains("SAE");
    }

    public boolean isFive() { return band == 50 || channel > 14; }

    /**
     * Prime target: the radios worth stopping the car for.
     * WEP and OPEN are confirmed from the beacon capabilities. WPS is a
     * factory-default heuristic because Android strips the WPS IE.
     */
    public boolean isPrime() {
        return isWep() || isOpen() || wps || wpsGuess;
    }

    /** Still broadcasting nothing. */
    public boolean isMasked() { return hidden; }

    /** Was masked at first capture and is now naming itself — auto-unmasked. */
    public boolean revealed() {
        return wasHidden && !hidden && ssid != null && !ssid.trim().isEmpty();
    }

    /** Hard targets are confirmed weaknesses; soft ones are WPS-likely gateways. */
    public boolean isHardTarget() { return isWep() || isOpen() || wps; }

    public String primeTag() {
        if (isWep()) return "WEP";
        if (isOpen()) return "OPEN";
        if (wps) return "WPS";
        if (wpsGuess) return "WPS?";
        return "";
    }

    public String band() { return WifiFacts.bandName(band == 0 ? WifiFacts.bandOf(freqOf(channel)) : band); }

    public String kind() {
        if (crypto != null && !crypto.isEmpty()) return crypto;
        return isWep() ? "WEP" : isOpen() ? "OPEN" : "WPA";
    }

    public String tier() { return WifiFacts.tier(score); }

    public String ssidOrGhost() {
        return (ssid == null || ssid.trim().isEmpty()) ? "<HIDDEN>" : ssid;
    }

    public String vendor() { return WifiFacts.vendor(bssid); }

    private String authUpper() { return auth == null ? "" : auth.toUpperCase(Locale.US); }

    /** Stable pseudo-bearing for radar placement. */
    public float bearing() {
        int h = 0;
        for (int i = 0; i < bssid.length(); i++) h = h * 31 + bssid.charAt(i);
        return Math.abs(h % 360);
    }

    public float rangeNorm() {
        float f = (rssi + 100f) / 70f;
        if (f < 0f) f = 0f;
        if (f > 1f) f = 1f;
        return 0.18f + (1f - f) * 0.78f;
    }
}
