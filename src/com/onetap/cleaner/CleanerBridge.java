package com.onetap.cleaner;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class CleanerBridge implements CleanerEngine.ScanProgressListener,
        TurboShellEngine.ShellProgressListener,
        AutoCacheCleanerService.AutoCleanListener {
    private static final String TAG = "CleanerBridge";
    private final Activity activity;
    private final WebView webView;
    private final Handler mainHandler;
    private final CleanerEngine engine;
    private final TurboShellEngine turboEngine;
    private JSONObject lastScanResult = null;

    public CleanerBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.engine = new CleanerEngine(activity);
        this.turboEngine = new TurboShellEngine(activity);
    }

    private void runOnJs(String jsCode) {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(jsCode, null);
            }
        });
    }

    @Override
    public void onProgress(String currentFile, int percent, long bytesFound) {
        String cleanPath = currentFile.replace("'", "\\'").replace("\"", "\\\"");
        runOnJs("window.onScanProgress && window.onScanProgress(" + percent + ", '" + cleanPath + "', " + bytesFound + ");");
    }

    @Override
    public void onProgress(String currentTask, int percent) {
        String clean = currentTask.replace("'", "\\'").replace("\"", "\\\"");
        runOnJs("window.onAotProgress && window.onAotProgress('" + clean + "', " + percent + "); " +
                "window.onAutoOptimizeProgress && window.onAutoOptimizeProgress('" + clean + "', " + percent + ");");
    }

    @Override
    public void onLog(String logLine) {
        String clean = logLine.replace("'", "\\'").replace("\"", "\\\"");
        runOnJs("window.onAotLog && window.onAotLog('" + clean + "'); " +
                "window.onAutoOptimizeLog && window.onAutoOptimizeLog('" + clean + "');");
    }

    @Override
    public void onProgress(String currentPackage, int current, int total) {
        runOnJs("window.onAutoCleanProgress && window.onAutoCleanProgress('" + currentPackage + "', " + current + ", " + total + ");");
    }

    @Override
    public void onFinished(int totalCleaned) {
        runOnJs("window.onAutoCleanFinished && window.onAutoCleanFinished(" + totalCleaned + ");");
    }

    @JavascriptInterface
    public String getDashboardInfo() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("deviceModel", Build.MANUFACTURER + " " + Build.MODEL);
            obj.put("androidVersion", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            obj.put("storage", engine.getStorageInfo());
            obj.put("ram", engine.getRamInfo());
            obj.put("battery", engine.getBatteryInfo());
            obj.put("hasStoragePermission", hasStoragePermission());
            obj.put("hasUsageStatsPermission", AppMonitorHelper.hasUsageStatsPermission(activity));
        } catch (Exception e) {
            Log.e(TAG, "Error in getDashboardInfo", e);
        }
        return obj.toString();
    }

    @JavascriptInterface
    public void startScan() {
        new Thread(() -> {
            try {
                JSONObject scanResult = engine.scanAllJunk(this);
                lastScanResult = scanResult;
                final String jsonStr = scanResult.toString();

                runOnJs("window.onScanComplete && window.onScanComplete(" + jsonStr + ");");
            } catch (Exception e) {
                Log.e(TAG, "Scan error", e);
                runOnJs("window.onScanError && window.onScanError('" + e.getMessage() + "');");
            }
        }).start();
    }

    @JavascriptInterface
    public void cleanJunk(String categoryIdsJson) {
        new Thread(() -> {
            try {
                JSONArray selectedCats = new JSONArray(categoryIdsJson);
                List<String> catIds = new ArrayList<>();
                for (int i = 0; i < selectedCats.length(); i++) {
                    catIds.add(selectedCats.getString(i));
                }

                JSONObject cleanRes = engine.cleanCategories(catIds);

                // Update lastScanResult if present so cleaned categories show 0 bytes immediately
                if (lastScanResult != null) {
                    JSONArray cats = lastScanResult.optJSONArray("categories");
                    if (cats != null) {
                        for (int i = 0; i < cats.length(); i++) {
                            JSONObject cat = cats.getJSONObject(i);
                            if (catIds.contains(cat.getString("id"))) {
                                cat.put("totalBytes", 0);
                                cat.put("fileCount", 0);
                                cat.put("sampleFiles", new JSONArray());
                            }
                        }
                    }
                }

                vibrate(100);
                final String resultJson = cleanRes.toString();
                runOnJs("window.onCleanComplete && window.onCleanComplete(" + resultJson + ");");

            } catch (Exception e) {
                Log.e(TAG, "Error cleaning junk", e);
                runOnJs("window.onCleanComplete && window.onCleanComplete({'bytesFreed':0, 'filesDeleted':0});");
            }
        }).start();
    }

    @JavascriptInterface
    public String boostRam() {
        JSONObject res = engine.boostRam();
        vibrate(80);
        return res.toString();
    }

    @JavascriptInterface
    public String quickClean() {
        JSONObject res = engine.quickClean();
        vibrate(120);
        return res.toString();
    }

    @JavascriptInterface
    public String getInstalledApps(String sortBy, boolean userAppsOnly) {
        JSONArray apps = AppMonitorHelper.getInstalledApps(activity, sortBy, userAppsOnly);
        return apps.toString();
    }

    @JavascriptInterface
    public String getDefaultApps() {
        JSONObject obj = DefaultsHelper.getDefaultAppsOverview(activity);
        return obj.toString();
    }

    @JavascriptInterface
    public boolean clearAppDefault(String packageName) {
        vibrate(50);
        return DefaultsHelper.clearAppDefaults(activity, packageName);
    }

    @JavascriptInterface
    public void openAppDetails(String packageName) {
        DefaultsHelper.openAppDefaultSettings(activity, packageName);
    }

    @JavascriptInterface
    public void openSystemDefaultApps() {
        DefaultsHelper.openSystemDefaultAppsSettings(activity);
    }

    @JavascriptInterface
    public String getHistoryStatus() {
        JSONObject obj = HistoryLogHelper.getHistoryStatus(activity);
        return obj.toString();
    }

    @JavascriptInterface
    public String cleanClipboard() {
        vibrate(50);
        return HistoryLogHelper.cleanClipboard(activity).toString();
    }

    @JavascriptInterface
    public String cleanLogFiles() {
        vibrate(80);
        return HistoryLogHelper.cleanLogFiles(activity).toString();
    }

    @JavascriptInterface
    public String cleanCallLogs() {
        vibrate(80);
        return HistoryLogHelper.cleanCallLogs(activity).toString();
    }

    @JavascriptInterface
    public String cleanBrowserHistory() {
        vibrate(60);
        return HistoryLogHelper.cleanBrowserHistoryAndCaches(activity).toString();
    }

    @JavascriptInterface
    public String getLargeFiles(long thresholdMb) {
        long thresholdBytes = thresholdMb * 1024 * 1024;
        JSONArray arr = engine.scanLargeFiles(thresholdBytes);
        return arr.toString();
    }

    @JavascriptInterface
    public boolean deleteSingleFile(String filePath) {
        try {
            java.io.File f = new java.io.File(filePath);
            return f.delete();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String getAutomationSettings() {
        SharedPreferences prefs = activity.getSharedPreferences(AppMonitorHelper.PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject obj = new JSONObject();
        try {
            obj.put("cacheLimitMb", prefs.getInt(AppMonitorHelper.KEY_CACHE_LIMIT_MB, 250));
            obj.put("autoCleanLowStorage", prefs.getBoolean("auto_clean_low_storage", true));
            obj.put("scheduledAutoClean", prefs.getBoolean("scheduled_auto_clean", true));
        } catch (Exception ignored) {}
        return obj.toString();
    }

    @JavascriptInterface
    public void saveAutomationSettings(String settingsJson) {
        try {
            JSONObject obj = new JSONObject(settingsJson);
            SharedPreferences prefs = activity.getSharedPreferences(AppMonitorHelper.PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            if (obj.has("cacheLimitMb")) {
                editor.putInt(AppMonitorHelper.KEY_CACHE_LIMIT_MB, obj.getInt("cacheLimitMb"));
            }
            if (obj.has("autoCleanLowStorage")) {
                editor.putBoolean("auto_clean_low_storage", obj.getBoolean("autoCleanLowStorage"));
            }
            if (obj.has("scheduledAutoClean")) {
                editor.putBoolean("scheduled_auto_clean", obj.getBoolean("scheduledAutoClean"));
            }
            editor.apply();
            vibrate(50);
        } catch (Exception e) {
            Log.e(TAG, "Error saving automation settings", e);
        }
    }

    @JavascriptInterface
    public boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    @JavascriptInterface
    public void requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            }
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                activity.startActivity(intent);
            } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public void requestUsageStatsPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error opening usage stats settings", e);
        }
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(ms);
            }
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Accessibility Auto-Cache Cleaner
    // =========================================================================
    @JavascriptInterface
    public boolean isAccessibilityServiceEnabled() {
        return AutoCacheCleanerService.isServiceRunning();
    }

    @JavascriptInterface
    public void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open accessibility settings", e);
        }
    }

    @JavascriptInterface
    public void startAutoAccessibilityClean(String packagesJson) {
        try {
            JSONArray arr = new JSONArray(packagesJson);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }

            vibrate(80);
            AutoCacheCleanerService.startAutoClean(activity, list, this);
        } catch (Exception e) {
            Log.e(TAG, "Error starting auto clean", e);
        }
    }

    @JavascriptInterface
    public void stopAutoAccessibilityClean() {
        AutoCacheCleanerService.stopAutoClean();
    }

    // =========================================================================
    // Turbo Shell, Auto Optimize & AOT ART Optimization
    // =========================================================================
    @JavascriptInterface
    public void runAutoOptimize() {
        new Thread(() -> {
            try {
                JSONObject res = turboEngine.runAutoOptimizePipeline(this);
                vibrate(120);
                runOnJs("window.onAutoOptimizeComplete && window.onAutoOptimizeComplete(" + res.toString() + ");");
            } catch (Exception e) {
                Log.e(TAG, "Auto optimize error", e);
                runOnJs("window.onAutoOptimizeComplete && window.onAutoOptimizeComplete({'success':false});");
            }
        }).start();
    }

    @JavascriptInterface
    public String cleanSamsungGalleryJunk() {
        vibrate(80);
        return turboEngine.cleanSamsungGalleryAndThumbnails().toString();
    }

    @JavascriptInterface
    public void runAotOptimization() {
        new Thread(() -> {
            try {
                JSONObject res = turboEngine.runAotOptimization(this);
                vibrate(120);
                runOnJs("window.onAotComplete && window.onAotComplete(" + res.toString() + ");");
            } catch (Exception e) {
                Log.e(TAG, "AOT optimization error", e);
                runOnJs("window.onAotComplete && window.onAotComplete({'success':false});");
            }
        }).start();
    }

    @JavascriptInterface
    public String runSystemCacheTrim() {
        vibrate(80);
        return turboEngine.runSystemCacheTrim().toString();
    }

    @JavascriptInterface
    public String runHardwareTrim() {
        vibrate(80);
        return turboEngine.runHardwareTrim().toString();
    }

    @JavascriptInterface
    public String getHardwareHealthInfo() {
        return turboEngine.getHardwareHealthInfo().toString();
    }

    @JavascriptInterface
    public String cleanOrphanedXmlAndRandomFiles() {
        vibrate(80);
        return turboEngine.cleanOrphanedXmlAndRandomFiles().toString();
    }

    @JavascriptInterface
    public String applyBatteryOptimizationSuite() {
        vibrate(100);
        return turboEngine.applyBatteryOptimizationSuite().toString();
    }

    @JavascriptInterface
    public String applyCustomBatteryOptimizations(String selectedKeysJson) {
        try {
            JSONArray arr = new JSONArray(selectedKeysJson);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
            vibrate(100);
            return turboEngine.applyCustomBatteryOptimizations(list).toString();
        } catch (Exception e) {
            Log.e(TAG, "Error applying custom battery optimizations", e);
            return "{\"success\":false}";
        }
    }

    @JavascriptInterface
    public String getBatteryOptimizationStatus() {
        return turboEngine.getBatteryOptimizationStatus().toString();
    }

    @JavascriptInterface
    public String getAdbCommandScript() {
        return turboEngine.getAdbCommandScript();
    }

    // =========================================================================
    // Material 3 Home Screen Widget Direct Pinning
    // =========================================================================
    @JavascriptInterface
    public boolean isPinWidgetSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                AppWidgetManager appWidgetManager = activity.getSystemService(AppWidgetManager.class);
                return appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported();
            } catch (Exception ignored) {}
        }
        return false;
    }

    @JavascriptInterface
    public boolean pinWidget(int widgetType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                AppWidgetManager appWidgetManager = activity.getSystemService(AppWidgetManager.class);
                if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported()) {
                    Class<?> providerClass;
                    switch (widgetType) {
                        case 1: providerClass = SystemStatsWidgetProvider.class; break;
                        case 2: providerClass = OneTapWidgetProvider.class; break;
                        case 3: providerClass = BatteryMonitorWidgetProvider.class; break;
                        case 4: providerClass = StorageCleanerWidgetProvider.class; break;
                        case 5: providerClass = QuickCleanWidgetProvider.class; break;
                        default: providerClass = OneTapWidgetProvider.class; break;
                    }
                    ComponentName myProvider = new ComponentName(activity, providerClass);
                    vibrate(60);
                    return appWidgetManager.requestPinAppWidget(myProvider, null, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error pinning widget to home screen", e);
            }
        }
        return false;
    }
}
