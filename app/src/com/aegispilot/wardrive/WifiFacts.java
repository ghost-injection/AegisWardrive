package com.aegispilot.wardrive;

import java.util.Locale;

/**
 * AEGIS//WARDRIVE — target intelligence.
 *
 * Turns a raw ScanResult into a verdict: what the crypto really is, what band
 * and width it rides, and how interesting it is to an operator with a radio.
 *
 * Everything here is derived from the capability string the radio reports. The
 * classic wardriving CSV only carries AuthMode, which collapses WPA2 and WPA3
 * into one word and hides WPS, PMF and transition mode entirely. We keep the
 * full picture and score it.
 */
public final class WifiFacts {

    // ---------- crypto labels ----------
    public static final String OPEN = "OPEN";
    public static final String WEP = "WEP";
    public static final String WPA = "WPA";
    public static final String WPA2 = "WPA2";
    public static final String WPA2_3 = "WPA2/WPA3";
    public static final String WPA3 = "WPA3";
    public static final String WPA3_EAP = "WPA3-EAP";
    public static final String WPA2_EAP = "WPA2-EAP";
    public static final String OWE = "OWE";
    public static final String UNKNOWN = "?";

    // ---------- tiers ----------
    public static final int TIER_CRIT = 75;
    public static final int TIER_HIGH = 55;
    public static final int TIER_MED = 35;

    public String crypto = UNKNOWN;   // human label, short
    public int band;                  // 24, 50, 60
    public int width;                 // MHz
    public String standard = "";      // 11n / 11ac / 11ax / 11be
    public boolean wps;
    public boolean wpsGuess;          // WPS likely, not confirmed (Android strips the WPS IE)
    public boolean pmf;               // 802.11w management frame protection
    public boolean enterprise;        // 802.1X / EAP
    public boolean sae;               // WPA3-Personal
    public boolean transition;        // WPA2+WPA3 mixed
    public boolean adhoc;             // IBSS
    public boolean hidden;            // empty SSID
    public boolean passpoint;         // Hotspot 2.0
    public int score;                 // 0..100 target interest
    public String why = "";           // compact reason string for the row

    /** Parse one capability string plus the radio metadata beside it. */
    public static WifiFacts of(String ssid, String caps, int freq, int widthMhz, String std, int level) {
        return of(ssid, caps, freq, widthMhz, std, level, null);
    }

    /** Parse one capability string plus the radio metadata beside it. */
    public static WifiFacts of(String ssid, String caps, int freq, int widthMhz, String std, int level,
                               String bssid) {
        WifiFacts f = new WifiFacts();
        f.hidden = ssid == null || ssid.trim().isEmpty();
        f.band = bandOf(freq);
        f.width = widthMhz > 0 ? widthMhz : 20;
        f.standard = std == null ? "" : std;

        String c = caps == null ? "" : caps.toUpperCase(Locale.US);
        boolean rsn = c.contains("RSN") || c.contains("[WPA2") || c.contains("[WPA3");
        boolean wpa = c.contains("[WPA-") || c.contains("[WPA]");
        boolean wep = c.contains("WEP");
        boolean eap = c.contains("EAP") || c.contains("802.1X") || c.contains("1X");
        boolean saeTok = c.contains("SAE");
        boolean oweTok = c.contains("OWE");
        f.wps = c.contains("WPS");
        // Some stacks omit the IE entirely, so fall back to a factory-default AP heuristic.
        if (!f.wps) f.wpsGuess = WpsIntel.likely(bssid, ssid);
        f.pmf = c.contains("MFPC") || c.contains("MFPR") || c.contains("802.11W");
        f.adhoc = c.contains("IBSS");
        f.passpoint = c.contains("PASSPOINT") || c.contains("HS20");
        f.enterprise = eap;
        f.sae = saeTok;
        f.transition = saeTok && (c.contains("[WPA2") || c.contains("PSK"));

        if (oweTok) f.crypto = OWE;
        else if (saeTok && f.transition) f.crypto = WPA2_3;
        else if (saeTok && eap) f.crypto = WPA3_EAP;
        else if (saeTok) f.crypto = WPA3;
        else if (eap && rsn) f.crypto = WPA2_EAP;
        else if (eap) f.crypto = WPA2_EAP;
        else if (rsn) f.crypto = WPA2;
        else if (wpa) f.crypto = WPA;
        else if (wep) f.crypto = WEP;
        else f.crypto = OPEN;

        f.scoreIt(ssid, level);
        return f;
    }

    public static int bandOf(int freq) {
        if (freq >= 5925) return 60;
        if (freq >= 4900) return 50;
        return 24;
    }

    public static String bandName(int band) {
        if (band == 60) return "6G";
        if (band == 50) return "5G";
        return "2.4";
    }

    public String bandName() { return bandName(band); }

    /** Target interest. Not a cvss score: an operator's "worth walking toward" score. */
    private void scoreIt(String ssid, int level) {
        int s = 0;
        StringBuilder w = new StringBuilder();

        if (OPEN.equals(crypto)) { s += 26; add(w, "NO AUTH"); }
        else if (WEP.equals(crypto)) { s += 32; add(w, "WEP CRACKABLE"); }
        else if (OWE.equals(crypto)) { s += 6; add(w, "OWE ENCRYPTED-OPEN"); }
        else if (WPA.equals(crypto)) { s += 18; add(w, "TKIP ERA"); }
        else if (WPA2.equals(crypto)) { s += 7; add(w, "HANDSHAKE TARGET"); }
        else if (WPA2_3.equals(crypto)) { s += 10; add(w, "TRANSITION DOWNGRADE"); }
        else if (WPA3.equals(crypto)) { s += 9; add(w, "SAE"); }
        else if (WPA3_EAP.equals(crypto) || WPA2_EAP.equals(crypto)) { s += 14; add(w, "802.1X CREDS"); }

        if (wps) { s += 15; add(w, "WPS ON"); }
        else if (wpsGuess) { s += 11; add(w, "WPS LIKELY"); }
        if (hidden) { s += 11; add(w, "HIDDEN"); }
        if (adhoc) { s += 8; add(w, "ADHOC"); }
        if (passpoint) { s += 4; add(w, "HOTSPOT2.0"); }
        if (!pmf && (WPA2.equals(crypto) || WPA2_3.equals(crypto))) { s += 4; add(w, "NO PMF"); }

        String low = ssid == null ? "" : ssid.toLowerCase(Locale.US);
        String vendor = defaultSsidVendor(low);
        if (vendor != null) { s += 12; add(w, "DEFAULT " + vendor); }
        if (low.contains("guest")) { s += 8; add(w, "GUEST"); }
        if (low.contains("iot") || low.contains("camera") || low.contains("printer")) { s += 6; add(w, "IOT"); }
        if (low.contains("setup") || low.contains("install")) { s += 10; add(w, "SETUP AP"); }

        if (level >= -45) { s += 16; add(w, "IN YOUR FACE"); }
        else if (level >= -60) { s += 11; add(w, "STRONG"); }
        else if (level >= -72) { s += 6; }
        else if (level <= -88) { s -= 4; add(w, "FAINT"); }

        if (band == 24) s += 4;               // longer reach, better for sitting on
        if (width >= 80) s += 3;              // wide channels, more airtime to sample

        if (s < 0) s = 0;
        if (s > 100) s = 100;
        score = s;
        why = w.length() == 0 ? "BASELINE" : w.toString();
    }

    private static void add(StringBuilder b, String t) {
        if (b.length() > 0) b.append(" · ");
        b.append(t);
    }

    private static String defaultSsidVendor(String low) {
        String[] v = {"linksys", "netgear", "xfinity", "dlink", "d-link", "tp-link", "tplink",
                "asus", "belkin", "trendnet", "actiontec", "arris", "motorola", "sercomm",
                "sky", "talktalk", "bt-", "bthub", "virgin", "spectrum", "optimum", "frontier",
                "centurylink", "huawei", "zte", "tenda", "ubiquiti", "unifi", "mikrotik"};
        for (String s : v) if (low.contains(s)) return s.toUpperCase(Locale.US);
        // untouched factory SSIDs, e.g. "NETGEAR47", "TP-LINK_2G"
        if (low.matches("^[a-z0-9]{2,}-?[0-9a-f]{4,}$") && low.length() <= 20) return "FACTORY";
        return null;
    }

    public static String tier(int score) {
        if (score >= TIER_CRIT) return "CRIT";
        if (score >= TIER_HIGH) return "HIGH";
        if (score >= TIER_MED) return "MED";
        return "LOW";
    }

    public String tier() { return tier(score); }

    /** Vendor from BSSID OUI — offline, from a small baked table. Unknown stays unknown. */
    public static String vendor(String bssid) {
        if (bssid == null || bssid.length() < 8) return "";
        String oui = bssid.substring(0, 8).toUpperCase(Locale.US);
        String v = OUI.get(oui);
        if (v != null) return v;
        // locally-administered bit set = randomised / virtual interface
        try {
            int b = Integer.parseInt(bssid.substring(0, 2), 16);
            if ((b & 0x02) != 0) return "RANDOMIZED";
        } catch (Exception ignored) { }
        return "";
    }

    private static final java.util.HashMap<String, String> OUI = new java.util.HashMap<>();
    static {
        OUI.put("00:1A:2B", "ASUSTEK");
        OUI.put("00:1D:7E", "CISCO-LINKSYS");
        OUI.put("00:14:6C", "NETGEAR");
        OUI.put("00:1B:2F", "NETGEAR");
        OUI.put("20:4E:7F", "NETGEAR");
        OUI.put("A0:40:A0", "NETGEAR");
        OUI.put("00:24:B2", "NETGEAR");
        OUI.put("00:0C:41", "LINKSYS");
        OUI.put("00:12:17", "CISCO-LINKSYS");
        OUI.put("C0:56:27", "BELKIN");
        OUI.put("94:10:3E", "BELKIN");
        OUI.put("00:1E:58", "D-LINK");
        OUI.put("00:22:B0", "D-LINK");
        OUI.put("F4:8C:EB", "D-LINK");
        OUI.put("50:C7:BF", "TP-LINK");
        OUI.put("A4:2B:B0", "TP-LINK");
        OUI.put("98:DA:C4", "TP-LINK");
        OUI.put("00:0F:66", "CISCO");
        OUI.put("00:1A:A1", "CISCO");
        OUI.put("F8:1A:67", "ARUBA");
        OUI.put("00:0B:86", "ARUBA");
        OUI.put("24:F5:A2", "UBIQUITI");
        OUI.put("78:8A:20", "UBIQUITI");
        OUI.put("04:18:D6", "UBIQUITI");
        OUI.put("00:15:6D", "UBIQUITI");
        OUI.put("D4:CA:6D", "ROUTERBOARD");
        OUI.put("4C:5E:0C", "MIKROTIK");
        OUI.put("64:D1:54", "MIKROTIK");
        OUI.put("E4:8D:8C", "ROUTERBOARD");
        OUI.put("00:1E:C0", "MICRO-STAR");
        OUI.put("9C:5C:8E", "ASUS");
        OUI.put("1C:87:2C", "ASUS");
        OUI.put("2C:FD:A1", "ASUS");
        OUI.put("00:11:22", "CIMSYS");
        OUI.put("F0:9F:C2", "UBIQUITI");
        OUI.put("DC:2C:6E", "ZTE");
        OUI.put("34:E8:94", "HUAWEI");
        OUI.put("48:8E:EF", "HUAWEI");
        OUI.put("C8:94:D2", "HUAWEI");
        OUI.put("50:2F:A8", "HUAWEI");
        OUI.put("00:26:5A", "D-LINK");
        OUI.put("B0:BE:76", "TP-LINK");
        OUI.put("C4:E9:84", "TP-LINK");
        OUI.put("74:DA:88", "TP-LINK");
        OUI.put("8C:3B:AD", "NETGEAR");
        OUI.put("B0:39:56", "NETGEAR");
        OUI.put("C4:3D:C7", "NETGEAR");
        OUI.put("44:94:FC", "NETGEAR");
        OUI.put("00:23:69", "CISCO-LINKSYS");
        OUI.put("68:7F:74", "CISCO-LINKSYS");
        OUI.put("48:F8:B3", "CISCO-LINKSYS");
        OUI.put("C0:C1:C0", "CISCO-LINKSYS");
        OUI.put("00:18:39", "CISCO-LINKSYS");
        OUI.put("00:25:9C", "CISCO-LINKSYS");
        OUI.put("58:6D:8F", "CISCO-LINKSYS");
        OUI.put("00:1C:10", "CISCO-LINKSYS");
        OUI.put("EC:1A:59", "BELKIN");
        OUI.put("08:86:3B", "BELKIN");
        OUI.put("B4:75:0E", "BELKIN");
        OUI.put("00:1F:90", "ACTIONTEC");
        OUI.put("00:26:B8", "ACTIONTEC");
        OUI.put("00:1D:CF", "ARRIS");
        OUI.put("00:1A:66", "ARRIS");
        OUI.put("20:3D:BD", "ARRIS");
        OUI.put("00:1F:7E", "ARRIS");
        OUI.put("00:24:36", "APPLE");
        OUI.put("F0:18:98", "APPLE");
        OUI.put("AC:BC:32", "APPLE");
        OUI.put("00:03:93", "APPLE");
        OUI.put("3C:5A:B4", "GOOGLE");
        OUI.put("F4:F5:D8", "GOOGLE");
        OUI.put("A4:77:33", "GOOGLE");
        OUI.put("DA:A1:19", "GOOGLE");
        OUI.put("00:1A:11", "GOOGLE");
        OUI.put("94:EB:2C", "GOOGLE");
        OUI.put("F0:EF:86", "GOOGLE");
        OUI.put("00:0E:58", "SONOS");
        OUI.put("5C:AA:FD", "SONOS");
        OUI.put("48:A6:B8", "SONOS");
        OUI.put("B8:E9:37", "SONOS");
        OUI.put("34:EA:34", "HIKVISION");
        OUI.put("44:19:B6", "HIKVISION");
        OUI.put("BC:AD:28", "HIKVISION");
        OUI.put("3C:EF:8C", "DAHUA");
        OUI.put("E0:50:8B", "DAHUA");
        OUI.put("9C:8E:99", "DAHUA");
        OUI.put("00:62:6E", "PRIVATE");
        OUI.put("02:00:00", "LOCAL");
    }
}
