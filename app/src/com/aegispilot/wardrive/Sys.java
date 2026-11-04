package com.aegispilot.wardrive;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;

import java.util.Locale;

/** Live device telemetry for the header line: battery, thermals, RAM, disk. */
public final class Sys {

    public static String status(Context c) {
        StringBuilder b = new StringBuilder();

        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int pct = sc > 0 ? (int) (lvl * 100f / sc) : -1;
                int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean chg = st == BatteryManager.BATTERY_STATUS_CHARGING
                        || st == BatteryManager.BATTERY_STATUS_FULL;
                if (pct >= 0) b.append("BATT ").append(pct).append('%').append(chg ? " +CHG" : "");
                int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (t != Integer.MIN_VALUE) b.append(" · ").append(t / 10).append('C');
            }
        } catch (Throwable ignored) {}

        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 29) {
                int ts = pm.getCurrentThermalStatus();
                String nm = ts >= PowerManager.THERMAL_STATUS_SEVERE ? "HOT"
                        : ts >= PowerManager.THERMAL_STATUS_MODERATE ? "WARM"
                        : ts >= PowerManager.THERMAL_STATUS_LIGHT ? "MILD" : "COOL";
                b.append(" · ").append(nm);
            }
        } catch (Throwable ignored) {}

        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                b.append(" · RAM ").append(String.format(Locale.US, "%.1fG", mi.availMem / 1073741824.0));
            }
        } catch (Throwable ignored) {}

        try {
            StatFs sf = new StatFs(c.getFilesDir().getAbsolutePath());
            b.append(" · ").append(String.format(Locale.US, "%.0fG", sf.getAvailableBytes() / 1073741824.0)).append(" DISK");
        } catch (Throwable ignored) {}

        return b.length() == 0 ? "SYSTEM NOMINAL" : b.toString();
    }
}
