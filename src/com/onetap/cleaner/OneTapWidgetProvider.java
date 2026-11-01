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

public class OneTapWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "OneTapWidgetProvider";
    public static final String ACTION_WIDGET_CLEAN = "com.onetap.cleaner.ACTION_WIDGET_CLEAN";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, "Ready");
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId, String status) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_onetap_cleaner);
            CleanerEngine engine = new CleanerEngine(context);

            JSONObject storage = engine.getStorageInfo();
            long freeBytes = storage.optLong("freeBytes", 0);
            int percentUsed = storage.optInt("percentUsed", 0);
            double freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0);

            JSONObject ram = engine.getRamInfo();
            int ramPercent = ram.optInt("percentUsed", 0);

            views.setTextViewText(R.id.widget_storage_text, String.format(Locale.US, "%.1f GB (Free)", freeGb));
            views.setTextViewText(R.id.widget_ram_text, ramPercent + "% Used");
            views.setTextViewText(R.id.widget_status, status);

            // Set up PendingIntent for 1-Tap Clean Button
            Intent cleanIntent = new Intent(context, OneTapWidgetProvider.class);
            cleanIntent.setAction(ACTION_WIDGET_CLEAN);
            cleanIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent cleanPending = PendingIntent.getBroadcast(context, appWidgetId + 1000, cleanIntent, flags);
            views.setOnClickPendingIntent(R.id.btn_widget_clean, cleanPending);

            // Set up PendingIntent to launch main activity when clicking card
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPending = PendingIntent.getActivity(context, appWidgetId, openIntent, flags);
            views.setOnClickPendingIntent(R.id.widget_container, openPending);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e(TAG, "Error updating widget", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_WIDGET_CLEAN.equals(intent.getAction())) {
            // Trigger haptic vibration
            try {
                Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(80);
            } catch (Exception ignored) {}

            // Run clean in background thread
            new Thread(() -> {
                CleanerEngine engine = new CleanerEngine(context);
                JSONObject result = engine.quickClean();
                long totalFreed = result.optLong("totalFreed", 0);
                long freedMb = Math.max(1, totalFreed / (1024 * 1024));

                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, "⚡ 1-Tap Clean: Freed " + freedMb + " MB!", Toast.LENGTH_SHORT).show();
                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    ComponentName thisWidget = new ComponentName(context, OneTapWidgetProvider.class);
                    int[] allIds = mgr.getAppWidgetIds(thisWidget);
                    for (int id : allIds) {
                        updateWidget(context, mgr, id, "Boosted ✨");
                    }
                });
            }).start();
        }
    }
}
