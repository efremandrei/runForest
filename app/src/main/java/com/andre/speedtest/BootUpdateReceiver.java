package com.andre.speedtest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AppUpdateChecker.checkFromBoot(context);
        }
    }
}