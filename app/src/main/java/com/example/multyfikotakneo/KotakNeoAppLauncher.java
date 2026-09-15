package com.example.multyfikotakneo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/** Opens the installed Kotak Neo Android app explicitly, never a browser fallback. */
public final class KotakNeoAppLauncher {
    private static final String KOTAK_PACKAGE = "com.kotak.neo";

    private KotakNeoAppLauncher() {}

    public static boolean open(Context context) {
        PackageManager pm = context.getPackageManager();

        // Resolve only launcher activities that belong to the Kotak Neo package.
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);
        probe.setPackage(KOTAK_PACKAGE);

        List<ResolveInfo> matches = pm.queryIntentActivities(probe, 0);
        if (matches == null || matches.isEmpty()) {
            return false;
        }

        ActivityInfo activity = null;
        for (ResolveInfo match : matches) {
            if (match != null && match.activityInfo != null
                    && KOTAK_PACKAGE.equals(match.activityInfo.packageName)) {
                activity = match.activityInfo;
                break;
            }
        }
        if (activity == null) {
            return false;
        }

        // Use an explicit component so Android cannot route this request to Chrome/another browser.
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setComponent(new ComponentName(activity.packageName, activity.name));
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launch);
        return true;
    }
}
