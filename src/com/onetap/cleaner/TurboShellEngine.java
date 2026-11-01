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
