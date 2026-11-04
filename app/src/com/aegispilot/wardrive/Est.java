package com.aegispilot.wardrive;

/** RSSI -> meters, multilateration, proximity bands. Pure math. */
public final class Est {
    public static final double TX = -45.0;
    public static final double N = 2.7;

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double rssiToMeters(double rssi) {
        return clamp(Math.pow(10.0, (TX - rssi) / (10.0 * N)), 1.0, 400.0);
    }

    public static double metersToRssi(double m) {
        return TX - 10.0 * N * Math.log10(Math.max(1.0, m));
    }

    /** obs rows: {lat, lon, rssi}. Returns {lat, lon, uncMeters, obsCount}. */
    public static double[] multilaterate(double[][] obs) {
        int n = obs.length;
        if (n == 0) return null;
        double mLat = 0, mLon = 0;
        for (double[] o : obs) { mLat += o[0]; mLon += o[1]; }
        mLat /= n; mLon /= n;
        double cs = Math.cos(Math.toRadians(mLat));
        double[] xs = new double[n], ys = new double[n], rs = new double[n];
        int ref = 0;
        for (int i = 0; i < n; i++) {
            xs[i] = (obs[i][1] - mLon) * 111320.0 * cs;
            ys[i] = (obs[i][0] - mLat) * 111320.0;
            rs[i] = rssiToMeters(obs[i][2]);
            if (rs[i] < rs[ref]) ref = i;
        }
        double ex, ey;
        if (n == 1) {
            ex = xs[0]; ey = ys[0];
        } else {
            double s11 = 0, s12 = 0, s22 = 0, b1 = 0, b2 = 0;
            for (int i = 0; i < n; i++) {
                if (i == ref) continue;
                double ax = 2 * (xs[i] - xs[ref]);
                double ay = 2 * (ys[i] - ys[ref]);
                double b = (rs[ref] * rs[ref] - rs[i] * rs[i])
                        + (xs[i] * xs[i] - xs[ref] * xs[ref])
                        + (ys[i] * ys[i] - ys[ref] * ys[ref]);
                double w = 1.0 / Math.max(6.0, rs[i]);
                s11 += w * ax * ax; s12 += w * ax * ay;
                s22 += w * ay * ay; b1 += w * ax * b; b2 += w * ay * b;
            }
            double det = s11 * s22 - s12 * s12;
            if (Math.abs(det) < 1e-9) {
                ex = xs[ref]; ey = ys[ref];
            } else {
                ex = (b1 * s22 - b2 * s12) / det;
                ey = (s11 * b2 - s12 * b1) / det;
            }
            if (!finite(ex) || !finite(ey) || Math.hypot(ex, ey) > 5000) {
                ex = xs[ref]; ey = ys[ref];
            }
            double dRef = Math.hypot(ex - xs[ref], ey - ys[ref]);
            double lim = rs[ref] * 1.6 + 20.0;
            if (dRef > lim) {
                ex = xs[ref] + (ex - xs[ref]) * lim / dRef;
                ey = ys[ref] + (ey - ys[ref]) * lim / dRef;
            }
        }
        double res = 0;
        for (int i = 0; i < n; i++) {
            res += Math.abs(Math.hypot(ex - xs[i], ey - ys[i]) - rs[i]);
        }
        res /= n;
        double unc = clamp(res * 1.5 + 5.0, 5.0, 350.0);
        double lat = mLat + ey / 111320.0;
        double lon = mLon + ex / (111320.0 * Math.max(0.2, cs));
        return new double[]{lat, lon, unc, n};
    }

    public static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    public static String band(double m) {
        if (m < 5) return "CONTACT";
        if (m < 15) return "IMMEDIATE";
        if (m < 40) return "NEAR";
        if (m < 120) return "APPROACHING";
        if (m < 400) return "DISTANT";
        return "REMOTE";
    }

    public static String quality(double rssi) {
        if (rssi >= -50) return "STRONG";
        if (rssi >= -62) return "GOOD";
        if (rssi >= -74) return "FAIR";
        if (rssi >= -86) return "WEAK";
        return "FAINT";
    }
}
