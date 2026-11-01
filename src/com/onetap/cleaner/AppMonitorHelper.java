package com.onetap.cleaner;

import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class AppMonitorHelper {
    private static final String TAG = "AppMonitorHelper";
    public static final String PREFS_NAME = "OneTapCleanerPrefs";
    public static final String KEY_CACHE_LIMIT_MB = "cache_limit_mb";
    public static final String CHANNEL_ID_ALERTS = "onetap_cache_alerts";

    public static class AppItem {
        public String packageName;
        public String label;
        public String versionName = "";
        public boolean isSystem;
        public long codeBytes = 0;
        public long dataBytes = 0;
        public long cacheBytes = 0;
        public long totalBytes = 0;
        public String iconBase64 = "";

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("packageName", packageName);
                obj.put("label", label);
                obj.put("versionName", versionName);
                obj.put("isSystem", isSystem);
                obj.put("codeBytes", codeBytes);
                obj.put("dataBytes", dataBytes);
                obj.put("cacheBytes", cacheBytes);
                obj.put("totalBytes", totalBytes);
                obj.put("icon", iconBase64);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    public static boolean hasUsageStatsPermission(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static JSONArray getInstalledApps(Context context, String sortBy, boolean userAppsOnly) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<AppItem> list = new ArrayList<>();

        boolean hasUsage = hasUsageStatsPermission(context);
        StorageStatsManager storageStats = null;
        if (hasUsage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                storageStats = (StorageStatsManager) context.getSystemService(Context.STORAGE_STATS_SERVICE);
            } catch (Exception ignored) {}
        }

        File externalStorage = Environment.getExternalStorageDirectory();
        File androidData = new File(externalStorage, "Android/data");

        for (ApplicationInfo info : installed) {
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (userAppsOnly && isSystem) {
                continue;
            }

            AppItem item = new AppItem();
            item.packageName = info.packageName;
            item.isSystem = isSystem;
            item.label = info.loadLabel(pm).toString();

            try {
                PackageInfo pkgInfo = pm.getPackageInfo(info.packageName, 0);
                item.versionName = pkgInfo.versionName != null ? pkgInfo.versionName : "";
            } catch (Exception ignored) {}

            // Measure code size from APK file
            if (info.sourceDir != null) {
                File apkFile = new File(info.sourceDir);
                if (apkFile.exists()) {
                    item.codeBytes = apkFile.length();
                }
            }

            // If Usage Stats is allowed, get exact storage stats from system
            if (storageStats != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    UUID uuid = StorageManager.UUID_DEFAULT;
                    if (info.storageUuid != null) {
                        uuid = info.storageUuid;
                    }
                    StorageStats stats = storageStats.queryStatsForPackage(uuid, info.packageName, Process.myUserHandle());
                    item.codeBytes = stats.getAppBytes();
                    item.dataBytes = stats.getDataBytes();
                    item.cacheBytes = stats.getCacheBytes();
                } catch (Exception ignored) {
                    // Fall back to filesystem scan
                    measureFromFilesystem(item, androidData);
                }
            } else {
                measureFromFilesystem(item, androidData);
            }

            item.totalBytes = item.codeBytes + item.dataBytes + item.cacheBytes;
            list.add(item);
        }

        // Sort items
        if ("cache".equalsIgnoreCase(sortBy)) {
            Collections.sort(list, (a, b) -> Long.compare(b.cacheBytes, a.cacheBytes));
        } else if ("data".equalsIgnoreCase(sortBy)) {
            Collections.sort(list, (a, b) -> Long.compare(b.dataBytes, a.dataBytes));
        } else if ("name".equalsIgnoreCase(sortBy)) {
            Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        } else {
            // default: total size
            Collections.sort(list, (a, b) -> Long.compare(b.totalBytes, a.totalBytes));
        }

        // Render icons for top 50 apps (to keep JSON payload light and snappy)
        int iconLimit = Math.min(list.size(), 60);
        for (int i = 0; i < iconLimit; i++) {
            AppItem it = list.get(i);
            try {
                Drawable d = pm.getApplicationIcon(it.packageName);
                it.iconBase64 = drawableToBase64(d);
            } catch (Exception ignored) {}
        }

        JSONArray result = new JSONArray();
        for (AppItem it : list) {
            result.put(it.toJson());
        }
        return result;
    }

    private static void measureFromFilesystem(AppItem item, File androidData) {
        if (androidData != null && androidData.exists()) {
            File appDir = new File(androidData, item.packageName);
            if (appDir.exists()) {
                File cacheDir = new File(appDir, "cache");
                if (cacheDir.exists()) {
                    item.cacheBytes = getFolderSize(cacheDir);
                }
                File filesDir = new File(appDir, "files");
                if (filesDir.exists()) {
                    item.dataBytes = getFolderSize(filesDir);
                }
            }
        }
    }

    private static long getFolderSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (!f.isDirectory()) return f.length();
        long size = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                size += getFolderSize(child);
            }
        }
        return size;
    }

    public static String drawableToBase64(Drawable drawable) {
        try {
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            width = Math.min(width, 72);
            height = Math.min(height, 72);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream);
            byte[] byteArray = stream.toByteArray();
            return "data:image/png;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    public static void checkCacheLimitAlerts(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int limitMb = prefs.getInt(KEY_CACHE_LIMIT_MB, 250); // Default 250 MB
        long limitBytes = ((long) limitMb) * 1024 * 1024;

        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);

        StorageStatsManager storageStats = null;
        if (hasUsageStatsPermission(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                storageStats = (StorageStatsManager) context.getSystemService(Context.STORAGE_STATS_SERVICE);
            } catch (Exception ignored) {}
        }

        File externalStorage = Environment.getExternalStorageDirectory();
        File androidData = new File(externalStorage, "Android/data");

        for (ApplicationInfo info : installed) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

            long cache = 0;
            if (storageStats != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    UUID uuid = info.storageUuid != null ? info.storageUuid : StorageManager.UUID_DEFAULT;
                    StorageStats stats = storageStats.queryStatsForPackage(uuid, info.packageName, Process.myUserHandle());
                    cache = stats.getCacheBytes();
                } catch (Exception ignored) {
                    File c = new File(androidData, info.packageName + "/cache");
                    cache = getFolderSize(c);
                }
            } else {
                File c = new File(androidData, info.packageName + "/cache");
                cache = getFolderSize(c);
            }

            if (cache >= limitBytes) {
                String label = info.loadLabel(pm).toString();
                long cacheMb = cache / (1024 * 1024);
                sendCacheAlertNotification(context, info.packageName, label, cacheMb, limitMb);
                break; // Alert top offending app to avoid spamming
            }
        }
    }

    private static void sendCacheAlertNotification(Context context, String pkg, String label, long cacheMb, int limitMb) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID_ALERTS,
                    "Cache Limit Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Alerts when apps exceed cache thresholds");
            nm.createNotificationChannel(channel);
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra("open_tab", "apps");
        intent.putExtra("target_pkg", pkg);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                context,
                pkg.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(context, CHANNEL_ID_ALERTS);
        } else {
            builder = new android.app.Notification.Builder(context);
        }

        builder.setContentTitle("⚠️ High Cache Alert: " + label)
                .setContentText(label + " is using " + cacheMb + " MB of cache (limit: " + limitMb + " MB). Tap to clean.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setAutoCancel(true);

        nm.notify(pkg.hashCode(), builder.build());
    }
}
