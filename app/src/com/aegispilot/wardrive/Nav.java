package com.aegispilot.wardrive;

/**
 * Geo math for the HUD: distance, bearing, cardinal naming, ETA and
 * Waze-style relative-turn instruction. Pure functions, no Android deps.
 */
public final class Nav {

    public static final double R_EARTH = 6371008.8;

    private Nav() {}

    /** Great-circle distance in meters. */
    public static double dist(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = Math.toRadians(la2 - la1), dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R_EARTH * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Initial bearing 0..360 from point 1 to point 2 (true north). */
    public static double bearing(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dl = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Local east/north offset in meters. */
    public static double[] enu(double la1, double lo1, double la2, double lo2) {
        double n = (la2 - la1) * 111320.0;
        double e = (lo2 - lo1) * 111320.0 * Math.max(0.15, Math.cos(Math.toRadians(la1)));
        return new double[]{e, n};
    }

    public static String cardinal(double b) {
        String[] d = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int i = (int) Math.round(((b % 360) + 360) % 360 / 22.5) % 16;
        return d[i];
    }

    /** Meters, human readable, metric-first. */
    public static String range(double m) {
        if (m < 0) m = 0;
        if (m < 10) return String.format("%.1f m", m);
        if (m < 1000) return String.format("%.0f m", m);
        if (m < 10000) return String.format("%.2f km", m / 1000.0);
        return String.format("%.1f km", m / 1000.0);
    }

    /** ETA text for a given pace in meters/second. */
    public static String eta(double m, double mps) {
        if (mps <= 0.1) return "--";
        double s = m / mps;
        if (s < 45) return String.format("%.0f s", s);
        if (s < 3600) return String.format("%.0f min", s / 60.0);
        return String.format("%.1f h", s / 3600.0);
    }

    /** Signed relative bearing -180..180 (positive = target is to your right). */
    public static double relative(double targetBearing, double heading) {
        double r = (targetBearing - heading + 540) % 360 - 180;
        return r;
    }

    /** Waze-style instruction from heading toward target. */
    public static String guidance(double targetBearing, double heading) {
        double r = relative(targetBearing, heading);
        double a = Math.abs(r);
        if (a < 12) return "HOLD COURSE";
        if (a > 160) return "TURN AROUND";
        if (a > 100) return r > 0 ? "HARD RIGHT" : "HARD LEFT";
        if (a > 35) return (r > 0 ? "TURN RIGHT " : "TURN LEFT ") + Math.round(a) + "\u00b0";
        return (r > 0 ? "BEAR RIGHT " : "BEAR LEFT ") + Math.round(a) + "\u00b0";
    }

    /** Compass rose label with degrees. */
    public static String compass(double b) {
        return String.format("%03.0f\u00b0 %s", (b % 360 + 360) % 360, cardinal(b));
    }
}
