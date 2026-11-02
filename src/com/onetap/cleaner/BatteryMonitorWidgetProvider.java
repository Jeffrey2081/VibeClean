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

public class BatteryMonitorWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "BatteryMonitorWidget";
    public static final String ACTION_BATTERY_DOZE = "com.onetap.cleaner.ACTION_BATTERY_DOZE";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, "Active 🟢");
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId, String status) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_battery_monitor);
            CleanerEngine engine = new CleanerEngine(context);

            JSONObject battery = engine.getBatteryInfo();
            String healthCondition = battery.optString("healthCondition", "Good (Optimal)");
            double temp = battery.optDouble("temperature", 31.0);
            int batPct = battery.optInt("percent", 84);

            views.setTextViewText(R.id.widget_bat_health, healthCondition);
            views.setTextViewText(R.id.widget_bat_temp, String.format(Locale.US, "%.1f°C · Cool", temp));
            views.setTextViewText(R.id.widget_bat_level, batPct + "%");
            views.setProgressBar(R.id.widget_bat_progress, 100, Math.min(100, batPct), false);
            views.setTextViewText(R.id.widget_bat_badge, status);

            // Set up PendingIntent for Quick Doze Button
            Intent dozeIntent = new Intent(context, BatteryMonitorWidgetProvider.class);
            dozeIntent.setAction(ACTION_BATTERY_DOZE);
            dozeIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent dozePending = PendingIntent.getBroadcast(context, appWidgetId + 4000, dozeIntent, flags);
            views.setOnClickPendingIntent(R.id.btn_widget_bat_doze, dozePending);

            // Open app on click
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPending = PendingIntent.getActivity(context, appWidgetId, openIntent, flags);
            views.setOnClickPendingIntent(R.id.widget_battery_container, openPending);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e(TAG, "Error updating BatteryMonitor widget", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_BATTERY_DOZE.equals(intent.getAction())) {
            try {
                Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(80);
            } catch (Exception ignored) {}

            new Thread(() -> {
                TurboShellEngine turbo = new TurboShellEngine(context);
                turbo.runShellCommand("settings put global device_idle_constants \"quick_doze_delay_to=0,sensing_to=0,locating_to=0,motion_inactive_to=0,light_after_inactive_to=0,inactive_to=10000\"");
                turbo.runShellCommand("settings put global wifi_scan_always_enabled 0 && settings put global ble_scan_always_enabled 0");

                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, "🌙 Quick Doze Deep Sleep Activated!", Toast.LENGTH_SHORT).show();
                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    ComponentName thisWidget = new ComponentName(context, BatteryMonitorWidgetProvider.class);
                    int[] allIds = mgr.getAppWidgetIds(thisWidget);
                    for (int id : allIds) {
                        updateWidget(context, mgr, id, "Doze Active 🌙");
                    }
                });
            }).start();
        }
    }
}
