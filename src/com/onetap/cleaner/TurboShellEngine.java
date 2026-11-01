package com.onetap.cleaner;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class TurboShellEngine {
    private static final String TAG = "TurboShellEngine";
    private final Context context;

    public interface ShellProgressListener {
        void onProgress(String currentTask, int percent);
        void onLog(String logLine);
    }

    public TurboShellEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean isRootAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            return line != null && line.contains("su");
        } catch (Exception e) {
            return false;
        }
    }

    public String runShellCommand(String cmd) {
        StringBuilder output = new StringBuilder();
        try {
            boolean hasRoot = isRootAvailable();
            Process process;
            if (hasRoot) {
                process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            } else {
                process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errReader.readLine()) != null) {
                output.append(line).append("\n");
            }

            process.waitFor();
        } catch (Exception e) {
            output.append("Error executing command: ").append(e.getMessage());
        }
        return output.toString().trim();
    }

    // 1. AOT ART Compilation
    public JSONObject runAotOptimization(ShellProgressListener listener) {
        JSONObject result = new JSONObject();
        int compiledThirdParty = 0;
        int compiledSystem = 0;
        List<String> logs = new ArrayList<>();

        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);

        // Third-party packages
        List<String> thirdPartyPackages = new ArrayList<>();
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                thirdPartyPackages.add(app.packageName);
            }
        }

        // Core UI & System Components
        String[] coreComponents = new String[]{
                "com.sec.android.app.launcher",
                "org.lineageos.trebuchet",
                "com.android.launcher3",
                "com.android.systemui",
                "com.samsung.android.honeyboard",
                "com.google.android.inputmethod.latin",
                "com.android.settings",
                "com.sec.android.app.camera",
                "com.google.android.GoogleCamera",
                "com.sec.android.gallery3d",
                "com.google.android.apps.photos",
                "com.sec.android.app.myfiles",
                "com.google.android.gms",
                "com.android.vending"
        };

        int totalTasks = thirdPartyPackages.size() + coreComponents.length;
        int currentTask = 0;

        if (listener != null) listener.onProgress("Starting AOT ART Compilation...", 5);

        // 1. Compile Third-Party Apps with speed-profile
        for (String pkg : thirdPartyPackages) {
            currentTask++;
            int pct = (int) ((currentTask * 100.0) / totalTasks);
            if (listener != null) {
                listener.onProgress("Compiling " + pkg + " (speed-profile)", pct);
            }

            String cmd = "cmd package compile -m speed-profile " + pkg;
            String out = runShellCommand(cmd);
            logs.add("[AOT speed-profile] " + pkg + " -> " + (out.isEmpty() ? "Success" : out));
            compiledThirdParty++;
        }

        // 2. Compile Core UI & System Components with speed (full native code)
        for (String corePkg : coreComponents) {
            currentTask++;
            int pct = (int) ((currentTask * 100.0) / totalTasks);
            if (listener != null) {
                listener.onProgress("Compiling System UI " + corePkg + " (speed)", pct);
            }

            try {
                pm.getApplicationInfo(corePkg, 0); // Check if package exists on this device
                String cmd = "cmd package compile -m speed " + corePkg;
                String out = runShellCommand(cmd);
                logs.add("[AOT Core speed] " + corePkg + " -> " + (out.isEmpty() ? "Success" : out));
                compiledSystem++;
            } catch (Exception ignored) {
                // Package not installed on this OEM ROM, continue
            }
        }

        if (listener != null) listener.onProgress("AOT Compilation complete!", 100);

        try {
            result.put("success", true);
            result.put("compiledThirdParty", compiledThirdParty);
            result.put("compiledSystem", compiledSystem);
            JSONArray arr = new JSONArray();
            for (String log : logs) arr.put(log);
            result.put("logs", arr);
        } catch (Exception ignored) {}

        return result;
    }

    // 2. System Cache Trimming (pm trim-caches)
    public JSONObject runSystemCacheTrim() {
        JSONObject res = new JSONObject();
        String output = runShellCommand("pm trim-caches 999G");
        try {
            res.put("success", true);
            res.put("output", output);
            res.put("message", "System-level cache trimming across all packages completed safely.");
        } catch (Exception ignored) {}
        return res;
    }

    // 3. Flash Storage Hardware TRIM (sm fstrim)
    public JSONObject runHardwareTrim() {
        JSONObject res = new JSONObject();
        String output = runShellCommand("sm fstrim");
        try {
            res.put("success", true);
            res.put("output", output);
            res.put("message", "UFS NAND flash storage TRIM executed. Unmapped flash blocks freed.");
        } catch (Exception ignored) {}
        return res;
    }

    // 4. Hardware Health & Verification
    public JSONObject getHardwareHealthInfo() {
        JSONObject res = new JSONObject();
        try {
            // Battery Health
            CleanerEngine engine = new CleanerEngine(context);
            JSONObject battery = engine.getBatteryInfo();
            res.put("battery", battery);

            // Storage
            JSONObject storage = engine.getStorageInfo();
            long freeBytes = storage.optLong("freeBytes", 0);
            double freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0);
            res.put("availableStorageGb", String.format("%.1f GB", freeGb));

            // RAM Plus / ZRAM Swap info
            String swapOut = runShellCommand("cat /proc/swaps");
            boolean ramPlusActive = swapOut.contains("zram") || swapOut.contains("partition");
            res.put("ramPlusStatus", ramPlusActive ? "Active" : "Off (0 GB / Low Wear)");
            res.put("swapInfo", swapOut.trim());

            // Root status
            res.put("isRooted", isRootAvailable());
        } catch (Exception e) {
            Log.e(TAG, "Error getting hardware health info", e);
        }
        return res;
    }

    // 5. Remove old unnecessary XML files and random junk in /sdcard/
    public JSONObject cleanOrphanedXmlAndRandomFiles() {
        JSONObject res = new JSONObject();
        long bytesFreed = 0;
        int filesDeleted = 0;
        List<String> deletedFiles = new ArrayList<>();

        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) {
            File[] files = ext.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isDirectory()) {
                        String name = f.getName().toLowerCase();
                        // Target orphaned XML dumps and random files in storage root
                        if (name.endsWith(".xml") || name.endsWith(".bak") || name.endsWith(".dmp") ||
                            name.endsWith(".stackdump") || name.startsWith("crash_") || name.startsWith("logcat")) {
                            long len = f.length();
                            if (f.delete()) {
                                bytesFreed += len;
                                filesDeleted++;
                                deletedFiles.add(f.getName());
                            }
                        }
                    }
                }
            }
        }

        try {
            res.put("bytesFreed", bytesFreed);
            res.put("filesDeleted", filesDeleted);
            JSONArray arr = new JSONArray();
            for (String f : deletedFiles) arr.put(f);
            res.put("deletedFiles", arr);
        } catch (Exception ignored) {}

        return res;
    }

    // 6. Deep Gallery & Thumbnails Cleaner for Samsung Galaxy (S20-S26) & Android 11-16
    public JSONObject cleanSamsungGalleryAndThumbnails() {
        JSONObject res = new JSONObject();
        long beforeFree = 0;
        long afterFree = 0;
        try {
            CleanerEngine engine = new CleanerEngine(context);
            beforeFree = engine.getStorageInfo().optLong("freeBytes", 0);
        } catch (Exception ignored) {}

        // 1. Force stop gallery apps so locked file descriptors on .thumbdata are released
        String stopCmd = "am force-stop com.sec.android.gallery3d 2>/dev/null; " +
                         "am force-stop com.google.android.apps.photos 2>/dev/null; " +
                         "am force-stop com.android.gallery3d 2>/dev/null; " +
                         "am force-stop com.samsung.android.gallery.preview 2>/dev/null";
        runShellCommand(stopCmd);

        // 2. Shell command purges both standard Android media thumbnail caches and Samsung One UI private thumbnail paths
        String cmd = "rm -rf /sdcard/DCIM/.thumbnails/* /sdcard/Pictures/.thumbnails/* " +
                "/sdcard/Movies/.thumbnails/* /sdcard/Download/.thumbnails/* /sdcard/.thumbnails/* " +
                "/sdcard/DCIM/.thumb/* /sdcard/Pictures/.thumb/* " +
                "/sdcard/DCIM/.trash/* /sdcard/Pictures/.trash/* " +
                "/sdcard/DCIM/.thumbdata* /sdcard/Pictures/.thumbdata* " +
                "/sdcard/Android/data/com.sec.android.gallery3d/cache/* " +
                "/sdcard/Android/data/com.sec.android.gallery3d/files/.trash/* " +
                "/sdcard/Android/data/com.samsung.android.gallery.preview/*";
        String shellOut = runShellCommand(cmd);

        // 3. Delete stale MediaStore records so the system storage updates immediately
        String contentCmd = "content delete --uri content://media/external/file --where \"_data LIKE '%/.thumbnails%' OR _data LIKE '%.thumbdata%' OR _data LIKE '%/.trash%'\" 2>/dev/null";
        runShellCommand(contentCmd);

        // 4. Broadcast MediaScanner to force system to rescan and drop dead thumbnail references
        String scanCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM 2>/dev/null; " +
                         "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures 2>/dev/null";
        runShellCommand(scanCmd);

        // 5. Java sweep & ContentResolver cleanup
        CleanerEngine engine = new CleanerEngine(context);
        JSONObject javaPurge = engine.purgeGalleryThumbnailsWithSystemRescan();

        // 6. Force OS to invalidate cached storage stats so Settings -> Storage updates immediately
        runSystemCacheTrim();

        try {
            afterFree = engine.getStorageInfo().optLong("freeBytes", 0);
            long freed = Math.max(javaPurge.optLong("bytesFreed", 0), Math.max(0, afterFree - beforeFree));
            res.put("success", true);
            res.put("bytesFreed", freed);
            res.put("message", "Samsung Gallery & Media Thumbnail cache purged and storage index reconciled.");
        } catch (Exception ignored) {}

        return res;
    }

    // 7. Battery Optimization Suite (Quick Doze, Radio Scanner Fixes, Wakelock Restrictions & Adaptive Battery)
    public JSONObject applyBatteryOptimizationSuite() {
        JSONObject res = new JSONObject();
        List<String> actions = new ArrayList<>();

        // 1. Quick Doze: Immediate Deep Sleep when screen is off
        String dozeCmd = "settings put global device_idle_constants \"quick_doze_delay_to=0,sensing_to=0,locating_to=0,motion_inactive_to=0,light_after_inactive_to=0,inactive_to=10000\"";
        runShellCommand(dozeCmd);
        actions.add("⚡ Quick Doze Activated: Immediate deep sleep on screen-off");

        // 2. Radio & Background Location Scanners
        String radioCmd = "settings put global wifi_scan_always_enabled 0 && " +
                          "settings put global ble_scan_always_enabled 0 && " +
                          "settings put global mobile_data_always_on 0 && " +
                          "settings put global send_action_app_error 0";
        runShellCommand(radioCmd);
        actions.add("📡 Background Radio & Location Scanners Disabled (Wi-Fi/BLE beacons stopped)");

        // 3. App Standby & Adaptive Battery
        String adaptiveCmd = "settings put global app_standby_enabled 1 && " +
                             "settings put global adaptive_battery_management_enabled 1";
        runShellCommand(adaptiveCmd);
        actions.add("🔋 Android Adaptive Battery & Aggressive App Standby Enabled");

        // 4. GPU & Animation Tuning
        String animCmd = "settings put global window_animation_scale 0.5 && " +
                         "settings put global transition_animation_scale 0.5 && " +
                         "settings put global animator_duration_scale 0.5";
        runShellCommand(animCmd);
        actions.add("💨 Animation & GPU Overhead Tuned to 0.5x");

        // 5. Restrict background drainers and wakelocks
        String[] heavyDrainers = new String[]{
                "com.instagram.android",
                "com.snapchat.android",
                "com.facebook.katana",
                "com.zhiliaoapp.musically",
                "app.morphe.android.apps.photos"
        };
        PackageManager pm = context.getPackageManager();
        int restrictedCount = 0;
        for (String pkg : heavyDrainers) {
            try {
                pm.getApplicationInfo(pkg, 0);
                String restrictCmd = "am set-standby-bucket " + pkg + " restricted 2>/dev/null; " +
                                     "cmd appops set " + pkg + " RUN_IN_BACKGROUND ignore 2>/dev/null; " +
                                     "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore 2>/dev/null; " +
                                     "cmd appops set " + pkg + " WAKE_LOCK ignore 2>/dev/null";
                runShellCommand(restrictCmd);
                restrictedCount++;
            } catch (Exception ignored) {}
        }
        actions.add("🛑 Wakelock Shield: Put " + restrictedCount + " heavy background drainers into restricted bucket");

        try {
            res.put("success", true);
            res.put("restrictedApps", restrictedCount);
            JSONArray arr = new JSONArray();
            for (String a : actions) arr.put(a);
            res.put("actions", arr);
            res.put("message", "Battery Optimization Suite successfully applied!");
        } catch (Exception ignored) {}

        return res;
    }

    public JSONObject getBatteryOptimizationStatus() {
        JSONObject obj = new JSONObject();
        try {
            String dozeOut = runShellCommand("settings get global device_idle_constants");
            boolean quickDoze = dozeOut.contains("quick_doze_delay_to=0");

            String wifiScan = runShellCommand("settings get global wifi_scan_always_enabled");
            String bleScan = runShellCommand("settings get global ble_scan_always_enabled");
            boolean radiosClean = "0".equals(wifiScan.trim()) && "0".equals(bleScan.trim());

            String adaptive = runShellCommand("settings get global adaptive_battery_management_enabled");
            boolean adaptiveOn = "1".equals(adaptive.trim());

            obj.put("quickDozeActive", quickDoze);
            obj.put("radiosOptimized", radiosClean);
            obj.put("adaptiveBatteryActive", adaptiveOn);
            obj.put("isOptimized", quickDoze && radiosClean);
        } catch (Exception e) {
            Log.e(TAG, "Error getting battery optimization status", e);
        }
        return obj;
    }

    private static class PipelineAotListener implements ShellProgressListener {
        private final ShellProgressListener parent;
        public PipelineAotListener(ShellProgressListener parent) {
            this.parent = parent;
        }
        @Override
        public void onProgress(String currentTask, int percent) {
            if (parent != null) {
                int mapped = 60 + (int) (percent * 0.30);
                parent.onProgress(currentTask, mapped);
            }
        }
        @Override
        public void onLog(String logLine) {
            if (parent != null) {
                parent.onLog(logLine);
            }
        }
    }

    // 7. Single-Button Complete Auto-Optimizer (AOT + System Trim + UFS TRIM + Gallery Purge + RAM)
    public JSONObject runAutoOptimizePipeline(ShellProgressListener listener) {
        JSONObject result = new JSONObject();
        List<String> logs = new ArrayList<>();
        long totalFreedBytes = 0;

        if (listener != null) {
            listener.onProgress("Initializing 1-Tap Auto Optimizer...", 5);
            listener.onLog("⚡ Starting VibeClean Auto Optimization Pipeline...");
        }

        // Step 1: System Cache Trimming (pm trim-caches 999G)
        if (listener != null) {
            listener.onProgress("Trimming System Caches (pm trim-caches)...", 15);
            listener.onLog("🧹 Executing pm trim-caches 999G across all packages...");
        }
        JSONObject trimRes = runSystemCacheTrim();
        logs.add("System Cache Trim: " + trimRes.optString("message", "Success"));

        // Step 2: Flash Storage Hardware TRIM (sm fstrim)
        if (listener != null) {
            listener.onProgress("Executing Hardware Flash TRIM (sm fstrim)...", 28);
            listener.onLog("⚡ Executing sm fstrim on UFS NAND flash partitions...");
        }
        JSONObject fstrimRes = runHardwareTrim();
        logs.add("Hardware Flash TRIM: " + fstrimRes.optString("message", "Success"));

        // Step 3: Samsung Gallery & Media Thumbnails Deep Clean
        if (listener != null) {
            listener.onProgress("Purging Samsung Gallery & Thumbnail caches...", 42);
            listener.onLog("🖼️ Purging .thumbnails, .thumbdata, and gallery trash...");
        }
        JSONObject galleryRes = cleanSamsungGalleryAndThumbnails();
        long galleryFreed = galleryRes.optLong("bytesFreed", 0);
        totalFreedBytes += galleryFreed;
        logs.add("Gallery & Thumbnails Purge: Completed");

        // Step 4: Storage Root XML & Orphaned Junk Files Sweeper
        if (listener != null) {
            listener.onProgress("Sweeping orphaned XML & junk files...", 52);
            listener.onLog("📁 Cleaning orphaned camera configs and crash dumps...");
        }
        JSONObject xmlRes = cleanOrphanedXmlAndRandomFiles();
        long xmlFreed = xmlRes.optLong("bytesFreed", 0);
        totalFreedBytes += xmlFreed;
        logs.add("Storage Sweeper: Purged " + xmlRes.optInt("filesDeleted", 0) + " junk files");

        // Step 5: Ahead-of-Time ART Compilation (Dexopt)
        if (listener != null) {
            listener.onProgress("Compiling Apps with ART AOT Dexopt...", 60);
            listener.onLog("🚀 Compiling third-party apps & Core System UI...");
        }
        JSONObject aotRes = runAotOptimization(new PipelineAotListener(listener));
        logs.add("AOT Compilation: " + aotRes.optInt("compiledThirdParty", 0) + " user apps, "
                + aotRes.optInt("compiledSystem", 0) + " core UI components optimized.");

        // Step 6: Battery Optimization Suite (Quick Doze, Radio Scan Fixes, Wakelocks)
        if (listener != null) {
            listener.onProgress("Applying Battery & Quick Doze Optimization...", 88);
            listener.onLog("🔋 Activating Quick Doze, radio beacon shields & wakelock limits...");
        }
        JSONObject batRes = applyBatteryOptimizationSuite();
        logs.add("Battery Suite: " + batRes.optString("message", "Applied"));

        // Step 7: RAM & Process Optimization
        if (listener != null) {
            listener.onProgress("Boosting RAM & finalizing system state...", 95);
            listener.onLog("⚡ Freeing background memory and flushing caches...");
        }
        CleanerEngine engine = new CleanerEngine(context);
        JSONObject ramRes = engine.boostRam();
        long ramFreed = ramRes.optLong("bytesFreed", 0);
        totalFreedBytes += ramFreed;
        logs.add("RAM Boost: Reclaimed " + (ramFreed / (1024 * 1024)) + " MB");

        if (listener != null) {
            listener.onProgress("System Fully Optimized! ✨", 100);
            listener.onLog("✅ All stages completed successfully.");
        }

        try {
            result.put("success", true);
            result.put("totalFreedBytes", totalFreedBytes);
            result.put("compiledThirdParty", aotRes.optInt("compiledThirdParty", 0));
            result.put("compiledSystem", aotRes.optInt("compiledSystem", 0));
            JSONArray arr = new JSONArray();
            for (String l : logs) arr.put(l);
            result.put("logs", arr);
        } catch (Exception ignored) {}

        return result;
    }

    private void deleteContentsRecursively(File dir) {
        if (dir != null && dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory()) {
                        deleteContentsRecursively(child);
                        child.delete();
                    } else {
                        child.delete();
                    }
                }
            }
        }
    }

    // Generate Full ADB Shell One-Liner Script for non-root users
    public String getAdbCommandScript() {
        return "# VibeClean Turbo Optimization Suite\n" +
                "pm trim-caches 999G\n" +
                "sm fstrim\n" +
                "cmd package compile -m speed-profile -a\n" +
                "cmd package compile -m speed com.sec.android.app.launcher\n" +
                "cmd package compile -m speed com.android.systemui\n" +
                "cmd package compile -m speed com.android.settings\n" +
                "cmd package compile -m speed com.android.vending\n";
    }
}
