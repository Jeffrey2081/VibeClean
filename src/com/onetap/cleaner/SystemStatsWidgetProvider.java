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

public class SystemStatsWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "SystemStatsWidget";
    public static final String ACTION_STATS_BOOST = "com.onetap.cleaner.ACTION_STATS_BOOST";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, "Healthy 🟢");
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId, String status) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_system_stats);
            CleanerEngine engine = new CleanerEngine(context);
            TurboShellEngine turbo = new TurboShellEngine(context);

            JSONObject storage = engine.getStorageInfo();
            long freeBytes = storage.optLong("freeBytes", 0);
            int storagePercentUsed = storage.optInt("percentUsed", 0);
            double freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0);

            JSONObject ram = engine.getRamInfo();
            int ramPercent = ram.optInt("percentUsed", 0);

            JSONObject battery = engine.getBatteryInfo();
            String healthCondition = battery.optString("healthCondition", "Good (Optimal)");
            double temp = battery.optDouble("temperature", 31.0);
            int batPct = battery.optInt("percent", 84);

            JSONObject hw = turbo.getHardwareHealthInfo();
            String ramPlus = hw.optString("ramPlusStatus", "Low Wear");

            views.setTextViewText(R.id.widget_stat_storage, String.format(Locale.US, "%.1f GB", freeGb));
            views.setTextViewText(R.id.widget_stat_ram, ramPercent + "%");
            views.setTextViewText(R.id.widget_stat_battery, healthCondition);
            views.setTextViewText(R.id.widget_stat_battery_sub, String.format(Locale.US, "%.1f°C · %d%%", temp, batPct));
            views.setTextViewText(R.id.widget_stat_swap, "Optimized");
            views.setTextViewText(R.id.widget_stat_swap_sub, "RAM+: " + ramPlus);
            views.setTextViewText(R.id.widget_stats_status, status);

            // Progress bars
            views.setProgressBar(R.id.widget_progress_storage, 100, Math.min(100, storagePercentUsed), false);
            views.setProgressBar(R.id.widget_progress_ram, 100, Math.min(100, ramPercent), false);

            // Boost action
            Intent boostIntent = new Intent(context, SystemStatsWidgetProvider.class);
            boostIntent.setAction(ACTION_STATS_BOOST);
            boostIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent boostPending = PendingIntent.getBroadcast(context, appWidgetId + 2000, boostIntent, flags);
            views.setOnClickPendingIntent(R.id.btn_widget_stats_boost, boostPending);

            // Open app on click
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPending = PendingIntent.getActivity(context, appWidgetId, openIntent, flags);
            views.setOnClickPendingIntent(R.id.widget_stats_container, openPending);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e(TAG, "Error updating SystemStats widget", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_STATS_BOOST.equals(intent.getAction())) {
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
                    Toast.makeText(context, "⚡ System Optimized: Freed " + freedMb + " MB!", Toast.LENGTH_SHORT).show();
                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    ComponentName thisWidget = new ComponentName(context, SystemStatsWidgetProvider.class);
                    int[] allIds = mgr.getAppWidgetIds(thisWidget);
                    for (int id : allIds) {
                        updateWidget(context, mgr, id, "Boosted ✨");
                    }
                });
            }).start();
        }
    }
}
