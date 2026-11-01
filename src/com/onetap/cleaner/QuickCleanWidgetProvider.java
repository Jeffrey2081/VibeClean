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

public class QuickCleanWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "QuickCleanWidget";
    public static final String ACTION_PILL_CLEAN = "com.onetap.cleaner.ACTION_PILL_CLEAN";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, "1-TAP BOOST");
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId, String title) {
        try {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_pill);
            CleanerEngine engine = new CleanerEngine(context);

            JSONObject storage = engine.getStorageInfo();
            long freeBytes = storage.optLong("freeBytes", 0);
            double freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0);

            views.setTextViewText(R.id.widget_pill_title, title);
            views.setTextViewText(R.id.widget_pill_storage, String.format(Locale.US, "%.1f GB Free", freeGb));

            // Set Clean action
            Intent cleanIntent = new Intent(context, QuickCleanWidgetProvider.class);
            cleanIntent.setAction(ACTION_PILL_CLEAN);
            cleanIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent cleanPending = PendingIntent.getBroadcast(context, appWidgetId + 3000, cleanIntent, flags);
            views.setOnClickPendingIntent(R.id.btn_widget_pill_clean, cleanPending);

            // Click pill background to launch app
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPending = PendingIntent.getActivity(context, appWidgetId, openIntent, flags);
            views.setOnClickPendingIntent(R.id.widget_pill_container, openPending);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        } catch (Exception e) {
            Log.e(TAG, "Error updating QuickClean widget", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_PILL_CLEAN.equals(intent.getAction())) {
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
                    Toast.makeText(context, "⚡ Cleaned " + freedMb + " MB!", Toast.LENGTH_SHORT).show();
                    AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    ComponentName thisWidget = new ComponentName(context, QuickCleanWidgetProvider.class);
                    int[] allIds = mgr.getAppWidgetIds(thisWidget);
                    for (int id : allIds) {
                        updateWidget(context, mgr, id, "CLEANED ✨");
                    }
                });
            }).start();
        }
    }
}
