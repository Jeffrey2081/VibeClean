package com.onetap.cleaner;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.CallLog;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class HistoryLogHelper {
    private static final String TAG = "HistoryLogHelper";

    public static JSONObject getHistoryStatus(Context context) {
        JSONObject res = new JSONObject();
        try {
            // Check clipboard
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            boolean hasClip = cm != null && cm.hasPrimaryClip();
            res.put("hasClipboardData", hasClip);

            // Check log files count & size
            long logBytes = 0;
            int logCount = 0;
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) {
                List<File> logs = new ArrayList<>();
                findLogFiles(ext, logs, 0);
                logCount = logs.size();
                for (File f : logs) {
                    logBytes += f.length();
                }
            }
            res.put("logCount", logCount);
            res.put("logBytes", logBytes);

            // Call log count (if permission granted)
            int callLogCount = 0;
            boolean canReadCallLog = context.checkCallingOrSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                    == PackageManager.PERMISSION_GRANTED;
            res.put("canReadCallLog", canReadCallLog);
            if (canReadCallLog) {
                try {
                    Cursor cursor = context.getContentResolver().query(
                            CallLog.Calls.CONTENT_URI,
                            new String[]{CallLog.Calls._ID},
                            null, null, null
                    );
                    if (cursor != null) {
                        callLogCount = cursor.getCount();
                        cursor.close();
                    }
                } catch (Exception ignored) {}
            }
            res.put("callLogCount", callLogCount);

        } catch (Exception e) {
            Log.e(TAG, "Error getting history status", e);
        }
        return res;
    }

    public static JSONObject cleanClipboard(Context context) {
        JSONObject res = new JSONObject();
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip();
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""));
                }
                res.put("success", true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing clipboard", e);
        }
        return res;
    }

    public static JSONObject cleanLogFiles(Context context) {
        JSONObject res = new JSONObject();
        long bytesFreed = 0;
        int filesDeleted = 0;

        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) {
            List<File> logs = new ArrayList<>();
            findLogFiles(ext, logs, 0);
            for (File f : logs) {
                long len = f.length();
                if (f.delete()) {
                    bytesFreed += len;
                    filesDeleted++;
                }
            }
        }

        try {
            res.put("bytesFreed", bytesFreed);
            res.put("filesDeleted", filesDeleted);
        } catch (Exception ignored) {}
        return res;
    }

    public static JSONObject cleanCallLogs(Context context) {
        JSONObject res = new JSONObject();
        int deleted = 0;
        try {
            if (context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_CALL_LOG)
                    == PackageManager.PERMISSION_GRANTED) {
                deleted = context.getContentResolver().delete(CallLog.Calls.CONTENT_URI, null, null);
            }
            res.put("deletedCount", deleted);
            res.put("success", true);
        } catch (Exception e) {
            Log.e(TAG, "Error clearing call logs", e);
        }
        return res;
    }

    public static JSONObject cleanBrowserHistoryAndCaches(Context context) {
        JSONObject res = new JSONObject();
        try {
            WebStorage.getInstance().deleteAllData();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
            }
            res.put("success", true);
        } catch (Exception e) {
            Log.e(TAG, "Error clearing web history", e);
        }
        return res;
    }

    private static void findLogFiles(File dir, List<File> logs, int depth) {
        if (depth > 5 || logs.size() >= 200) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                String name = f.getName().toLowerCase();
                if (!name.startsWith(".") && !name.equals("android")) {
                    findLogFiles(f, logs, depth + 1);
                }
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".log") || name.endsWith(".stackdump") ||
                        name.startsWith("crash_") || name.startsWith("anr_") ||
                        name.contains("logcat")) {
                    logs.add(f);
                }
            }
        }
    }
}
