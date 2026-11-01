package com.onetap.cleaner;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DefaultsHelper {
    private static final String TAG = "DefaultsHelper";

    public static JSONObject getDefaultAppsOverview(Context context) {
        JSONObject res = new JSONObject();
        PackageManager pm = context.getPackageManager();

        try {
            // 1. Default Browser
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
            ResolveInfo browserInfo = pm.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY);
            JSONObject browserObj = new JSONObject();
            if (browserInfo != null && browserInfo.activityInfo != null) {
                String pkg = browserInfo.activityInfo.packageName;
                browserObj.put("packageName", pkg);
                browserObj.put("name", browserInfo.loadLabel(pm).toString());
                browserObj.put("isDefaultSet", !pkg.equals("android") && !pkg.equals("com.android.internal.app.ResolverActivity"));
            }
            res.put("browser", browserObj);

            // 2. Default Phone / Dialer
            Intent dialerIntent = new Intent(Intent.ACTION_DIAL);
            ResolveInfo dialerInfo = pm.resolveActivity(dialerIntent, PackageManager.MATCH_DEFAULT_ONLY);
            JSONObject dialerObj = new JSONObject();
            if (dialerInfo != null && dialerInfo.activityInfo != null) {
                String pkg = dialerInfo.activityInfo.packageName;
                dialerObj.put("packageName", pkg);
                dialerObj.put("name", dialerInfo.loadLabel(pm).toString());
                dialerObj.put("isDefaultSet", !pkg.equals("android") && !pkg.equals("com.android.internal.app.ResolverActivity"));
            }
            res.put("dialer", dialerObj);

            // 3. Default SMS
            String defaultSms = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                defaultSms = Telephony.Sms.getDefaultSmsPackage(context);
            }
            JSONObject smsObj = new JSONObject();
            if (defaultSms != null) {
                smsObj.put("packageName", defaultSms);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(defaultSms, 0);
                    smsObj.put("name", ai.loadLabel(pm).toString());
                    smsObj.put("isDefaultSet", true);
                } catch (Exception e) {
                    smsObj.put("name", defaultSms);
                    smsObj.put("isDefaultSet", true);
                }
            }
            res.put("sms", smsObj);

            // 4. Default Home / Launcher
            Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo homeInfo = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            JSONObject homeObj = new JSONObject();
            if (homeInfo != null && homeInfo.activityInfo != null) {
                String pkg = homeInfo.activityInfo.packageName;
                homeObj.put("packageName", pkg);
                homeObj.put("name", homeInfo.loadLabel(pm).toString());
                homeObj.put("isDefaultSet", !pkg.equals("android") && !pkg.equals("com.android.internal.app.ResolverActivity"));
            }
            res.put("launcher", homeObj);

            // 5. Scan apps that have preferred activities configured
            JSONArray preferredApps = new JSONArray();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            List<IntentFilter> filters = new ArrayList<>();
            List<ComponentName> activities = new ArrayList<>();

            for (ApplicationInfo app : apps) {
                filters.clear();
                activities.clear();
                int count = pm.getPreferredActivities(filters, activities, app.packageName);
                if (count > 0) {
                    JSONObject appWithPref = new JSONObject();
                    appWithPref.put("packageName", app.packageName);
                    appWithPref.put("name", app.loadLabel(pm).toString());
                    appWithPref.put("defaultCount", count);
                    preferredApps.put(appWithPref);
                }
            }
            res.put("preferredApps", preferredApps);

        } catch (Exception e) {
            Log.e(TAG, "Error getting default apps", e);
        }

        return res;
    }

    public static boolean clearAppDefaults(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.clearPackagePreferredActivities(packageName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing package preferred activities for " + packageName, e);
            return false;
        }
    }

    public static void openAppDefaultSettings(Context context, String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error opening app details settings", e);
        }
    }

    public static void openSystemDefaultAppsSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }
}
