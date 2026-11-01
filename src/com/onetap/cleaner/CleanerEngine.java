package com.onetap.cleaner;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CleanerEngine {
    private static final String TAG = "CleanerEngine";
    private final Context context;
    private final java.util.Map<String, List<String>> categoryFilesMap = new java.util.concurrent.ConcurrentHashMap<>();

    public interface ScanProgressListener {
        void onProgress(String currentFile, int percent, long bytesFound);
    }

    public static class JunkCategory {
        public String id;
        public String name;
        public String description;
        public long totalBytes = 0;
        public int fileCount = 0;
        public List<String> filePaths = new ArrayList<>();

        public JunkCategory(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("name", name);
                obj.put("description", description);
                obj.put("totalBytes", totalBytes);
                obj.put("fileCount", fileCount);
                JSONArray arr = new JSONArray();
                int limit = Math.min(filePaths.size(), 100);
                for (int i = 0; i < limit; i++) {
                    arr.put(filePaths.get(i));
                }
                obj.put("sampleFiles", arr);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    public CleanerEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject getStorageInfo() {
        JSONObject obj = new JSONObject();
        try {
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long totalInternal = totalBlocks * blockSize;
            long freeInternal = availableBlocks * blockSize;
            long usedInternal = totalInternal - freeInternal;

            obj.put("totalBytes", totalInternal);
            obj.put("freeBytes", freeInternal);
            obj.put("usedBytes", usedInternal);
            int percentUsed = totalInternal > 0 ? (int) ((usedInternal * 100) / totalInternal) : 0;
            obj.put("percentUsed", percentUsed);

            // SD Card / Shared External storage check
            File extPath = Environment.getExternalStorageDirectory();
            StatFs extStat = new StatFs(extPath.getPath());
            long extTotal = extStat.getBlockCountLong() * extStat.getBlockSizeLong();
            long extFree = extStat.getAvailableBlocksLong() * extStat.getBlockSizeLong();
            obj.put("extTotalBytes", extTotal);
            obj.put("extFreeBytes", extFree);
            obj.put("extUsedBytes", extTotal - extFree);

        } catch (Exception e) {
            Log.e(TAG, "Error calculating storage info", e);
        }
        return obj;
    }

    public JSONObject getRamInfo() {
        JSONObject obj = new JSONObject();
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            if (activityManager != null) {
                activityManager.getMemoryInfo(memInfo);
                long totalRam = memInfo.totalMem;
                long availRam = memInfo.availMem;
                long usedRam = totalRam - availRam;
                int percentUsed = totalRam > 0 ? (int) ((usedRam * 100) / totalRam) : 0;

                obj.put("totalBytes", totalRam);
                obj.put("availBytes", availRam);
                obj.put("usedBytes", usedRam);
                obj.put("percentUsed", percentUsed);
                obj.put("lowMemory", memInfo.lowMemory);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting RAM info", e);
        }
        return obj;
    }

    private String readSysfsLine(String path) {
        try {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line = br.readLine();
                br.close();
                if (line != null) return line.trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public JSONObject getBatteryInfo() {
        JSONObject obj = new JSONObject();
        try {
            Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0); // in tenths of a degree C
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);

                int batteryPct = scale > 0 ? (int) ((level / (float) scale) * 100) : level;
                float tempCelsius = temp / 10.0f;
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;

                String healthCondition;
                switch (health) {
                    case BatteryManager.BATTERY_HEALTH_GOOD:
                        healthCondition = "Good (Optimal)";
                        break;
                    case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                        healthCondition = "Overheating ⚠️";
                        break;
                    case BatteryManager.BATTERY_HEALTH_DEAD:
                        healthCondition = "Dead ⚠️";
                        break;
                    case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                        healthCondition = "Over Voltage ⚠️";
                        break;
                    case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                        healthCondition = "Failure ⚠️";
                        break;
                    case BatteryManager.BATTERY_HEALTH_COLD:
                        healthCondition = "Cold ❄️";
                        break;
                    default:
                        healthCondition = "Good (Normal)";
                        break;
                }

                // Check Samsung & Linux sysfs values
                String sysHealth = readSysfsLine("/sys/class/power_supply/battery/health");
                if (sysHealth != null && !sysHealth.isEmpty() && sysHealth.equalsIgnoreCase("good")) {
                    healthCondition = "Good (Optimal)";
                }
                String sysAsoc = readSysfsLine("/sys/class/power_supply/battery/fg_asoc");
                String sysCycle = readSysfsLine("/sys/class/power_supply/battery/battery_cycle");

                obj.put("percent", batteryPct);
                obj.put("temperature", tempCelsius);
                obj.put("isCharging", isCharging);
                obj.put("health", healthCondition);
                obj.put("healthCondition", healthCondition);
                obj.put("asocRetention", sysAsoc != null ? sysAsoc + "%" : "100%");
                obj.put("cycleCount", sysCycle != null ? sysCycle : "--");
                obj.put("healthSummary", healthCondition + (tempCelsius > 0 ? " · " + String.format(Locale.US, "%.1f°C", tempCelsius) : ""));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting Battery info", e);
        }
        return obj;
    }

    public JSONObject scanAllJunk(ScanProgressListener listener) {
        JunkCategory cacheCat = new JunkCategory("cache", "App & System Cache", "Temporary cache files created by installed apps");
        JunkCategory residualCat = new JunkCategory("residual", "Residual Junk", "Leftover files, temporary logs, and crash reports");
        JunkCategory apkCat = new JunkCategory("apk", "Obsolete APKs", "Downloaded package installers no longer needed");
        JunkCategory thumbCat = new JunkCategory("thumbnails", "Gallery & Thumbnails", "Cached photo previews and media thumbnails");
        JunkCategory emptyDirCat = new JunkCategory("empty_dirs", "Empty Folders", "Orphaned folders taking up filesystem index entries");
        JunkCategory sdLeftoversCat = new JunkCategory("sd_leftovers", "SD Uninstalled Leftovers", "App data lingering from apps you previously uninstalled");

        Set<String> installedPackages = getInstalledPackageSet();

        File externalStorage = Environment.getExternalStorageDirectory();
        long totalBytesScanned = 0;

        if (externalStorage != null && externalStorage.exists()) {
            File androidData = new File(externalStorage, "Android/data");
            File androidMedia = new File(externalStorage, "Android/media");

            // 1. Scan external app caches & uninstalled leftovers
            if (androidData.exists() && androidData.isDirectory()) {
                File[] appDirs = androidData.listFiles();
                if (appDirs != null) {
                    for (File appDir : appDirs) {
                        String pkgName = appDir.getName();
                        if (listener != null) {
                            listener.onProgress("Scanning: " + pkgName, 15, totalBytesScanned);
                        }

                        // Check if uninstalled app leftover
                        if (!installedPackages.contains(pkgName)) {
                            scanDirectoryRecursively(appDir, sdLeftoversCat);
                            totalBytesScanned += sdLeftoversCat.totalBytes;
                            continue;
                        }

                        // Scan app cache folder
                        File cacheDir = new File(appDir, "cache");
                        if (cacheDir.exists() && cacheDir.isDirectory()) {
                            scanDirectoryRecursively(cacheDir, cacheCat);
                            totalBytesScanned += cacheCat.totalBytes;
                        }
                    }
                }
            }

            // 2. Scan internal cache dirs
            try {
                File internalCache = context.getCacheDir();
                if (internalCache != null && internalCache.exists()) {
                    scanDirectoryRecursively(internalCache, cacheCat);
                }
                File externalCache = context.getExternalCacheDir();
                if (externalCache != null && externalCache.exists()) {
                    scanDirectoryRecursively(externalCache, cacheCat);
                }
            } catch (Exception ignored) {}

            if (listener != null) {
                listener.onProgress("Scanning system storage...", 40, totalBytesScanned);
            }

            // 3. Scan Downloads & General SD card for APKs, Logs, Temp, Thumbnails, Empty folders
            scanStorageGeneral(externalStorage, residualCat, apkCat, thumbCat, emptyDirCat, listener, totalBytesScanned);
        }

        JSONObject result = new JSONObject();
        try {
            JSONArray categories = new JSONArray();
            categories.put(cacheCat.toJson());
            categories.put(residualCat.toJson());
            categories.put(apkCat.toJson());
            categories.put(thumbCat.toJson());
            categories.put(emptyDirCat.toJson());
            categories.put(sdLeftoversCat.toJson());

            long grandTotalBytes = cacheCat.totalBytes + residualCat.totalBytes + apkCat.totalBytes
                    + thumbCat.totalBytes + sdLeftoversCat.totalBytes;
            int grandTotalFiles = cacheCat.fileCount + residualCat.fileCount + apkCat.fileCount
                    + thumbCat.fileCount + emptyDirCat.fileCount + sdLeftoversCat.fileCount;

            // Retain full file lists per category for thorough cleaning
            categoryFilesMap.put(cacheCat.id, new ArrayList<>(cacheCat.filePaths));
            categoryFilesMap.put(residualCat.id, new ArrayList<>(residualCat.filePaths));
            categoryFilesMap.put(apkCat.id, new ArrayList<>(apkCat.filePaths));
            categoryFilesMap.put(thumbCat.id, new ArrayList<>(thumbCat.filePaths));
            categoryFilesMap.put(emptyDirCat.id, new ArrayList<>(emptyDirCat.filePaths));
            categoryFilesMap.put(sdLeftoversCat.id, new ArrayList<>(sdLeftoversCat.filePaths));

            result.put("categories", categories);
            result.put("totalBytes", grandTotalBytes);
            result.put("totalFiles", grandTotalFiles);
        } catch (Exception e) {
            Log.e(TAG, "Error building scan result", e);
        }

        return result;
    }

    private void scanStorageGeneral(File root, JunkCategory residual, JunkCategory apks, JunkCategory thumbs,
                                    JunkCategory emptyDirs, ScanProgressListener listener, long totalBytes) {
        if (root == null || !root.exists()) return;

        // 1. Explicitly scan known Samsung S-series & Android Gallery / Thumbnail / Trash folders
        File[] knownThumbDirs = new File[]{
                new File(root, "DCIM/.thumbnails"),
                new File(root, "DCIM/.thumb"),
                new File(root, "Pictures/.thumbnails"),
                new File(root, "Pictures/.thumb"),
                new File(root, "Movies/.thumbnails"),
                new File(root, "Download/.thumbnails"),
                new File(root, "Music/.thumbnails"),
                new File(root, ".thumbnails"),
                new File(root, "DCIM/.trash"),
                new File(root, "Pictures/.trash"),
                new File(root, "Android/data/com.sec.android.gallery3d/cache"),
                new File(root, "Android/data/com.sec.android.gallery3d/files/.trash"),
                new File(root, "Android/data/com.samsung.android.gallery.preview"),
                new File(root, "Android/data/com.google.android.apps.photos/cache")
        };
        for (File kd : knownThumbDirs) {
            if (kd != null && kd.exists()) {
                scanDirectoryRecursively(kd, thumbs);
            }
        }

        File[] files = root.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                String name = file.getName();
                // Skip Android/data already scanned or system mount points
                if (name.equals("Android") || name.startsWith(".")) {
                    if (name.equalsIgnoreCase(".thumbnails") || name.equalsIgnoreCase(".thumb") || name.equalsIgnoreCase(".trash")) {
                        scanDirectoryRecursively(file, thumbs);
                    }
                    continue;
                }

                // Check for thumbnail folders
                if (name.equalsIgnoreCase("thumbnails") || name.equalsIgnoreCase(".thumbnails") ||
                    name.equalsIgnoreCase("thumbs") || name.equalsIgnoreCase(".thumb")) {
                    scanDirectoryRecursively(file, thumbs);
                    continue;
                }

                // Check if directory is empty
                File[] sub = file.listFiles();
                if (sub != null && sub.length == 0) {
                    emptyDirs.fileCount++;
                    emptyDirs.filePaths.add(file.getAbsolutePath());
                    continue;
                }

                // Scan common junk and media folders
                if (name.equalsIgnoreCase("Download") || name.equalsIgnoreCase("Downloads") ||
                    name.equalsIgnoreCase("temp") || name.equalsIgnoreCase("tmp")) {
                    scanGeneralFolder(file, residual, apks, thumbs, emptyDirs);
                } else if (name.equalsIgnoreCase("DCIM") || name.equalsIgnoreCase("Pictures") ||
                           name.equalsIgnoreCase("Movies") || name.equalsIgnoreCase("Documents")) {
                    scanMediaForThumbs(file, thumbs);
                }
            } else {
                checkSingleFile(file, residual, apks, thumbs);
            }
        }
    }

    private void scanGeneralFolder(File folder, JunkCategory residual, JunkCategory apks, JunkCategory thumbs, JunkCategory emptyDirs) {
        File[] files = folder.listFiles();
        if (files == null) return;

        if (files.length == 0) {
            emptyDirs.fileCount++;
            emptyDirs.filePaths.add(folder.getAbsolutePath());
            return;
        }

        for (File f : files) {
            if (f.isDirectory()) {
                String dName = f.getName().toLowerCase();
                if (dName.contains("thumb") || dName.equals(".trash") || dName.equals("trash")) {
                    scanDirectoryRecursively(f, thumbs);
                } else {
                    scanGeneralFolder(f, residual, apks, thumbs, emptyDirs);
                }
            } else {
                checkSingleFile(f, residual, apks, thumbs);
            }
        }
    }

    private void scanMediaForThumbs(File folder, JunkCategory thumbs) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.equals(".database_uuid") || name.equals(".nomedia") || name.equals(".database_uuid-journal")) {
                continue;
            }
            if (f.isDirectory()) {
                if (name.equals(".thumbnails") || name.equals("thumbnails") ||
                    name.equals(".thumb") || name.equals("thumbs") ||
                    name.equals(".trash") || name.equals(".trashed") ||
                    name.equals("trash") || name.equals("sec_photo_editor") ||
                    name.equals("story") || name.equals("photoview") ||
                    name.equals("cloud_cache")) {
                    scanDirectoryRecursively(f, thumbs);
                } else {
                    scanMediaForThumbs(f, thumbs);
                }
            } else {
                // Check if file is a thumbnail, .thumbdata, or MediaStore trashed file
                if (name.startsWith(".thumbdata") || name.startsWith("thumbdata") ||
                    name.startsWith(".trashed-") || name.endsWith(".thumb") ||
                    name.endsWith(".thumbnail") || name.contains("_thumb.") ||
                    name.equals("thumbnail.idx") || name.equals("micro_thumbnail.db") ||
                    name.contains(".thumbdata3") || name.contains(".thumbdata4")) {
                    long len = f.length();
                    thumbs.totalBytes += len;
                    thumbs.fileCount++;
                    thumbs.filePaths.add(f.getAbsolutePath());
                }
            }
        }
    }

    private void checkSingleFile(File f, JunkCategory residual, JunkCategory apks, JunkCategory thumbs) {
        String name = f.getName().toLowerCase();
        long len = f.length();

        if (name.endsWith(".apk")) {
            apks.totalBytes += len;
            apks.fileCount++;
            apks.filePaths.add(f.getAbsolutePath());
        } else if (name.startsWith(".thumbdata") || name.startsWith("thumbdata") ||
                name.startsWith(".trashed-") || name.endsWith(".thumb") ||
                name.endsWith(".thumbnail") || name.contains("_thumb.")) {
            thumbs.totalBytes += len;
            thumbs.fileCount++;
            thumbs.filePaths.add(f.getAbsolutePath());
        } else if (name.endsWith(".tmp") || name.endsWith(".temp") || name.endsWith(".bak") ||
                name.endsWith(".old") || name.endsWith(".log") || name.endsWith(".stackdump") ||
                name.startsWith("crash_") || name.startsWith("anr_")) {
            residual.totalBytes += len;
            residual.fileCount++;
            residual.filePaths.add(f.getAbsolutePath());
        }
    }

    private void checkSingleFile(File f, JunkCategory residual, JunkCategory apks) {
        checkSingleFile(f, residual, apks, residual);
    }

    private void scanDirectoryRecursively(File dir, JunkCategory category) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.equals(".database_uuid") || name.equals(".nomedia") || name.equals(".database_uuid-journal")) {
                continue;
            }
            if (f.isDirectory()) {
                scanDirectoryRecursively(f, category);
            } else {
                long len = f.length();
                category.totalBytes += len;
                category.fileCount++;
                category.filePaths.add(f.getAbsolutePath());
            }
        }
    }

    public JSONObject cleanCategories(List<String> categoryIds) {
        JSONObject res = new JSONObject();
        long bytesFreed = 0;
        int filesDeleted = 0;
        int errors = 0;

        // If thumbnails selected, force-stop gallery apps first to break file locks on .thumbdata
        if (categoryIds.contains("thumbnails")) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses("com.sec.android.gallery3d");
                    am.killBackgroundProcesses("com.google.android.apps.photos");
                    am.killBackgroundProcesses("com.samsung.android.gallery.preview");
                }
            } catch (Exception ignored) {}

            JSONObject purgeRes = purgeGalleryThumbnailsWithSystemRescan();
            bytesFreed += purgeRes.optLong("bytesFreed", 0);
            filesDeleted += purgeRes.optInt("filesDeleted", 0);
        }

        List<String> pathsToDelete = new ArrayList<>();
        for (String catId : categoryIds) {
            List<String> paths = categoryFilesMap.get(catId);
            if (paths != null) {
                pathsToDelete.addAll(paths);
            }
        }

        for (String path : pathsToDelete) {
            try {
                File file = new File(path);
                if (file.exists()) {
                    long size = file.isDirectory() ? getDirSize(file) : file.length();
                    boolean deleted = deleteRecursively(file);
                    if (deleted) {
                        bytesFreed += size;
                        filesDeleted++;
                    } else {
                        // If file locked by system daemon, truncate to 0 bytes so it takes 0 storage
                        if (file.isFile()) {
                            try {
                                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                                fos.getChannel().truncate(0);
                                fos.close();
                                bytesFreed += size;
                                filesDeleted++;
                            } catch (Exception e) {
                                errors++;
                            }
                        } else {
                            errors++;
                        }
                    }

                    // Delete MediaStore entry if media/thumbnail
                    try {
                        if (path.contains("DCIM") || path.contains("Pictures") ||
                            path.contains("thumbnails") || path.contains(".trash") ||
                            path.contains(".thumbdata")) {
                            context.getContentResolver().delete(
                                    MediaStore.Files.getContentUri("external"),
                                    MediaStore.MediaColumns.DATA + "=?",
                                    new String[]{path});
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                errors++;
            }
        }

        // Clear files from the map for the cleaned categories
        for (String catId : categoryIds) {
            List<String> list = categoryFilesMap.get(catId);
            if (list != null) list.clear();
        }

        try {
            res.put("bytesFreed", bytesFreed);
            res.put("filesDeleted", filesDeleted);
            res.put("errors", errors);
        } catch (Exception ignored) {}

        // Rescan system media storage so deleted files update in Android Settings immediately
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) {
                String[] scanPaths = new String[]{
                        new File(ext, "DCIM").getAbsolutePath(),
                        new File(ext, "Pictures").getAbsolutePath(),
                        new File(ext, "Movies").getAbsolutePath(),
                        new File(ext, "Download").getAbsolutePath()
                };
                MediaScannerConnection.scanFile(context, scanPaths, null, null);
            }
        } catch (Exception ignored) {}

        return res;
    }

    public JSONObject cleanFiles(List<String> pathsToDelete) {
        JSONObject res = new JSONObject();
        long bytesFreed = 0;
        int filesDeleted = 0;
        int errors = 0;

        for (String path : pathsToDelete) {
            try {
                File file = new File(path);
                if (file.exists()) {
                    long size = file.isDirectory() ? getDirSize(file) : file.length();
                    boolean deleted = deleteRecursively(file);
                    if (deleted) {
                        bytesFreed += size;
                        filesDeleted++;

                        // Also notify Android MediaStore if deleted file was media/thumbnail
                        try {
                            if (path.contains("DCIM") || path.contains("Pictures") ||
                                path.contains("thumbnails") || path.contains(".trash") ||
                                path.contains(".thumbdata")) {
                                context.getContentResolver().delete(
                                        MediaStore.Files.getContentUri("external"),
                                        MediaStore.MediaColumns.DATA + "=?",
                                        new String[]{path});
                            }
                        } catch (Exception ignored) {}
                    } else {
                        errors++;
                    }
                }
            } catch (Exception e) {
                errors++;
            }
        }

        try {
            res.put("bytesFreed", bytesFreed);
            res.put("filesDeleted", filesDeleted);
            res.put("errors", errors);
        } catch (Exception ignored) {}

        // Rescan system media storage so deleted files update in Android Settings immediately
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) {
                String[] scanPaths = new String[]{
                        new File(ext, "DCIM").getAbsolutePath(),
                        new File(ext, "Pictures").getAbsolutePath(),
                        new File(ext, "Movies").getAbsolutePath(),
                        new File(ext, "Download").getAbsolutePath()
                };
                MediaScannerConnection.scanFile(context, scanPaths, null, null);
            }
        } catch (Exception ignored) {}

        return res;
    }

    // Comprehensive Samsung & Android Gallery Thumbnail Purge with full system storage rescan
    public JSONObject purgeGalleryThumbnailsWithSystemRescan() {
        JSONObject res = new JSONObject();
        long bytesFreed = 0;
        int filesDeleted = 0;

        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) {
            File[] targets = new File[]{
                    new File(ext, "DCIM/.thumbnails"),
                    new File(ext, "DCIM/.thumb"),
                    new File(ext, "Pictures/.thumbnails"),
                    new File(ext, "Pictures/.thumb"),
                    new File(ext, "Movies/.thumbnails"),
                    new File(ext, "Download/.thumbnails"),
                    new File(ext, "Music/.thumbnails"),
                    new File(ext, ".thumbnails"),
                    new File(ext, "DCIM/.trash"),
                    new File(ext, "Pictures/.trash")
            };

            for (File t : targets) {
                if (t != null && t.exists()) {
                    long sz = getDirSize(t);
                    if (deleteRecursively(t)) {
                        bytesFreed += sz;
                        filesDeleted++;
                    }
                }
            }

            // Target .thumbdata files specifically in DCIM and Pictures
            File[] mediaRoots = new File[]{ new File(ext, "DCIM"), new File(ext, "Pictures"), new File(ext, "Movies") };
            for (File mRoot : mediaRoots) {
                if (mRoot != null && mRoot.exists()) {
                    File[] subFiles = mRoot.listFiles();
                    if (subFiles != null) {
                        for (File sf : subFiles) {
                            String name = sf.getName().toLowerCase();
                            if (name.contains("thumbdata") || name.startsWith(".trashed-") || name.endsWith(".thumb") || name.equals(".thumbnails")) {
                                long sz = sf.length();
                                if (sf.delete()) {
                                    bytesFreed += sz;
                                    filesDeleted++;
                                } else {
                                    // If deletion locked by gallery daemon, truncate to 0 bytes so it consumes 0 storage
                                    try {
                                        java.io.FileOutputStream fos = new java.io.FileOutputStream(sf);
                                        fos.getChannel().truncate(0);
                                        fos.close();
                                        bytesFreed += sz;
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1. MediaStore Database cleanup via ContentResolver
        try {
            context.getContentResolver().delete(
                    MediaStore.Files.getContentUri("external"),
                    "_data LIKE '%/.thumbnails%' OR _data LIKE '%.thumbdata%' OR _data LIKE '%/.trash%' OR _data LIKE '%.trashed-%'",
                    null);
        } catch (Exception ignored) {}

        // 2. Trigger MediaScanner to rescan DCIM and Pictures
        try {
            if (ext != null && ext.exists()) {
                String[] scanPaths = new String[]{
                        new File(ext, "DCIM").getAbsolutePath(),
                        new File(ext, "Pictures").getAbsolutePath(),
                        new File(ext, "Movies").getAbsolutePath(),
                        new File(ext, "Download").getAbsolutePath()
                };
                MediaScannerConnection.scanFile(context, scanPaths, null, null);
            }
        } catch (Exception ignored) {}

        // 3. Broadcast Media Scanner Intent
        try {
            if (ext != null && ext.exists()) {
                context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.parse("file://" + new File(ext, "DCIM").getAbsolutePath())));
                context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.parse("file://" + new File(ext, "Pictures").getAbsolutePath())));
            }
        } catch (Exception ignored) {}

        try {
            res.put("success", true);
            res.put("bytesFreed", bytesFreed);
            res.put("filesDeleted", filesDeleted);
            res.put("message", "Thumbnails purged and system media storage index refreshed.");
        } catch (Exception ignored) {}

        return res;
    }

    public JSONObject quickClean() {
        // Quick clean for Home Screen Widget and Low-Storage Automation:
        // Cleans internal context cache, temp files, and empty dirs, and boosts RAM.
        long freedBytes = 0;
        int filesDeleted = 0;

        try {
            File internalCache = context.getCacheDir();
            if (internalCache != null && internalCache.exists()) {
                long size = getDirSize(internalCache);
                deleteContentsRecursively(internalCache);
                freedBytes += size;
            }

            File externalCache = context.getExternalCacheDir();
            if (externalCache != null && externalCache.exists()) {
                long size = getDirSize(externalCache);
                deleteContentsRecursively(externalCache);
                freedBytes += size;
            }

            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) {
                File tmpDir = new File(ext, "tmp");
                if (tmpDir.exists()) {
                    freedBytes += getDirSize(tmpDir);
                    deleteRecursively(tmpDir);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Quick clean error", e);
        }

        JSONObject ramRes = boostRam();
        long ramFreed = ramRes.optLong("bytesFreed", 0);

        JSONObject out = new JSONObject();
        try {
            out.put("bytesFreed", freedBytes);
            out.put("ramFreed", ramFreed);
            out.put("totalFreed", freedBytes + ramFreed);
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject boostRam() {
        JSONObject res = new JSONObject();
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(before);

                // Kill background processes for all installed third party applications
                PackageManager pm = context.getPackageManager();
                List<ApplicationInfo> packages = pm.getInstalledApplications(0);
                int killedCount = 0;
                for (ApplicationInfo app : packages) {
                    if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !app.packageName.equals(context.getPackageName())) {
                        activityManager.killBackgroundProcesses(app.packageName);
                        killedCount++;
                    }
                }

                System.gc();

                ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(after);

                long freed = Math.max(0, after.availMem - before.availMem);
                res.put("bytesFreed", freed);
                res.put("killedProcesses", killedCount);
                res.put("availMem", after.availMem);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error boosting RAM", e);
        }
        return res;
    }

    public JSONArray scanLargeFiles(long thresholdBytes) {
        JSONArray arr = new JSONArray();
        File ext = Environment.getExternalStorageDirectory();
        if (ext == null || !ext.exists()) return arr;

        List<File> largeList = new ArrayList<>();
        findLargeFiles(ext, thresholdBytes, largeList, 0);

        for (File f : largeList) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("path", f.getAbsolutePath());
                o.put("bytes", f.length());
                o.put("lastModified", f.lastModified());
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private void findLargeFiles(File dir, long threshold, List<File> result, int depth) {
        if (depth > 6 || result.size() >= 50) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                String name = f.getName();
                if (!name.startsWith(".") && !name.equals("Android")) {
                    findLargeFiles(f, threshold, result, depth + 1);
                }
            } else {
                if (f.length() >= threshold) {
                    result.add(f);
                }
            }
        }
    }

    private Set<String> getInstalledPackageSet() {
        Set<String> set = new HashSet<>();
        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                set.add(app.packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting installed packages", e);
        }
        return set;
    }

    private boolean deleteRecursively(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return fileOrDirectory.delete();
    }

    private void deleteContentsRecursively(File dir) {
        if (dir != null && dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
    }

    private long getDirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    size += getDirSize(f);
                } else {
                    size += f.length();
                }
            }
        }
        return size;
    }
}
