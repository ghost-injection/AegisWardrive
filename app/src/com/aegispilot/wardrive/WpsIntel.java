package com.aegispilot.wardrive;

import java.util.Locale;

/**
 * WPS exposure heuristic.
 *
 * Android's ScanResult never carries a WPS information element: the platform
 * strips it, so wm.getScanResults() cannot confirm WPS the way it confirms WEP.
 * What we CAN do offline is flag the class of access point that ships with WPS
 * enabled from the factory: consumer ISP gateways and default-brand SSIDs.
 * That is a probability tag ("WPS LIKELY"), never a confirmed finding, and the
 * UI labels it as such. Confirmation needs an active WPS probe from a radio in
 * monitor mode, which stock Android will not give us.
 */
public final class WpsIntel {

    private WpsIntel() { }

    /* SSID stems used by gateways that ship WPS-PBC/PIN enabled by default. */
    private static final String[] DEFAULT_AP = {
            "sr", "hg", "tg", "dg", "fios", "actiontec", "arris", "ubee", "hitron",
            "sagemcom", "technicolor", "calix", "adtran", "pace", "compal", "askey",
            "mitrastar", "zyxel", "sercomm", "motorola", "sagemtel", "smartrg",
            "centurylink", "frontier", "spectrum", "xfinity", "optimum", "cox",
            "verizon", "att", "bell", "telus", "sky", "bt-", "bthub", "talktalk",
            "virgin", "plusnet", "ee-", "orange", "sfr", "livebox", "bbox",
            "netgear", "linksys", "tp-link", "tplink", "dlink", "d-link", "tenda",
            "asus", "belkin", "trendnet", "huawei", "zte", "ubiquiti", "unifi",
            "mikrotik", "mercusys", "totolink", "xiaomi", "redmi"
    };

    /** SSIDs that already advertise a setup/install state are stronger WPS bets. */
    private static final String[] SETUP_HINTS = {
            "setup", "install", "configure", "default", "wps"
    };

    /**
     * @return true when the SSID looks like a factory-default consumer AP, which
     *         is the population where WPS is on unless somebody turned it off.
     */
    public static boolean likely(String bssid, String ssid) {
        if (ssid == null) return false;
        String s = ssid.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) return false;

        for (String h : SETUP_HINTS) if (s.contains(h)) return true;

        for (String stem : DEFAULT_AP) {
            if (s.startsWith(stem)) {
                // "sr", "hg", "tg", "dg" are short stems: demand a digit right after
                // so we do not flag a cafe called "Srdjan" as an ISP gateway.
                if (stem.length() <= 2) {
                    if (s.length() > stem.length()
                            && Character.isDigit(s.charAt(stem.length()))) return true;
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** Short tag for rows and notifications. */
    public static String tag(boolean confirmed, boolean guess) {
        if (confirmed) return "WPS ON";
        return guess ? "WPS?" : "";
    }
}
