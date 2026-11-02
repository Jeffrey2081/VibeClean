package com.onetap.cleaner;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;

public class StorageCleanerWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "StorageCleanerWidget";
    public static final String ACTION_STORAGE_CLEAN = "com.onetap.cleaner.ACTION_STORAGE_CLEAN";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, "Ready");
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId, String status) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_storage_cleaner);
            CleanerEngine engine = new CleanerEngine(context);

            JSONObject storage = engine.getStorageInfo();
            long freeBytes = storage.optLong("freeBytes", 0);
            int percentUsed = storage.optInt("percentUsed", 0);
            double freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0);

            views.setTextViewText(R.id.widget_storage_free_large, String.format(Locale.US, "%.1f GB", freeGb));
            views.setProgressBar(R.id.widget_storage_cleaner_progress, 100, Math.min(100, percentUsed), false);
            views.setTextViewText(R.id.widget_storage_cleaner_status, status);

            // Set up PendingIntent for Clean Cache Button
            Intent cleanIntent = new Intent(context, StorageCleanerWidgetProvider.class);
            cleanIntent.setAction(ACTION_STORAGE_CLEAN);
            cleanIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent cleanPending = PendingIntent.getBroadcast(context, appWidgetId + 5000, cleanIntent, flags);
            views.setOnClickPendingIntent(R.id.btn_widget_storage_clean, cleanPending);

            // Open app on click
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPending = PendingIntent.getActivity(context, appWidgetId, openIntent, flags);
            views.setOnClickPendingIntent(R.id.widget_storage_cleaner_container, openPending);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e(TAG, "Error updating StorageCleaner widget", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_STORAGE_CLEAN.equals(intent.getAction())) {
            try {
                Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(80);
            } catch (Exception ignored) {}

            new Thread(() -> {
                CleanerEngine engine = new CleanerEngine(context);
                JSONObject result = engine.quickClean();
                long totalFreed = result.optLong("totalFreed", 0);
                long freedMb = Math.max(1, totalFreed / (1024 * 1024));

                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, "🧹 Cache Cleared! Freed " + freedMb + " MB!", Toast.LENGTH_SHORT).show();
                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    ComponentName thisWidget = new ComponentName(context, StorageCleanerWidgetProvider.class);
                    int[] allIds = mgr.getAppWidgetIds(thisWidget);
                    for (int id : allIds) {
                        updateWidget(context, mgr, id, "Clean ✨");
                    }
                });
            }).start();
        }
    }
}
