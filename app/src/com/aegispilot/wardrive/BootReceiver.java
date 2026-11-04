package com.aegispilot.wardrive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Resume an armed sweep after the phone reboots.
 *
 * Android will not let a plain broadcast receiver start a foreground service
 * indefinitely, but a BOOT_COMPLETED receiver may, and the service immediately
 * posts its own ongoing notification. If the operator never started a sweep,
 * resume_sweep is false and this does nothing.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent intent) {
        if (c == null || intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            try { WardriveService.resumeIfArmed(c); } catch (Throwable ignored) { }
        }
    }
}
