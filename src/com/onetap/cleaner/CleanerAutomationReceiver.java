package com.onetap.cleaner;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

public class CleanerAutomationReceiver extends BroadcastReceiver {
    private static final String TAG = "CleanerAutoReceiver";
    public static final String ACTION_SCHEDULED_CLEAN = "com.onetap.cleaner.ACTION_SCHEDULED_CLEAN";
    public static final String CHANNEL_ID_AUTO = "onetap_auto_clean";
    public static final int JOB_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.d(TAG, "Received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            schedulePeriodicJob(context);
        } else if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
            handleLowStorage(context);
        } else if (ACTION_SCHEDULED_CLEAN.equals(action)) {
            handleScheduledClean(context);
        }
    }

    public static void schedulePeriodicJob(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;

            ComponentName serviceComponent = new ComponentName(context, CleanerJobService.class);
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, serviceComponent);

            // Run roughly every 6-12 hours
            builder.setPeriodic(6 * 60 * 60 * 1000); // 6 hours
            builder.setPersisted(true);

            js.schedule(builder.build());
            Log.d(TAG, "Periodic cleaning job scheduled");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling periodic job", e);
        }
    }

    private void handleLowStorage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AppMonitorHelper.PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoCleanEnabled = prefs.getBoolean("auto_clean_low_storage", true);

        if (autoCleanEnabled) {
            new Thread(() -> {
                CleanerEngine engine = new CleanerEngine(context);
                JSONObject result = engine.quickClean();
                long totalFreed = result.optLong("totalFreed", 0);
                long freedMb = Math.max(1, totalFreed / (1024 * 1024));

                sendLowStorageNotification(context, freedMb);
            }).start();
        }
    }

    private void handleScheduledClean(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AppMonitorHelper.PREFS_NAME, Context.MODE_PRIVATE);
        boolean scheduledCleanEnabled = prefs.getBoolean("scheduled_auto_clean", true);

        if (scheduledCleanEnabled) {
            new Thread(() -> {
                CleanerEngine engine = new CleanerEngine(context);
                JSONObject result = engine.quickClean();
                long totalFreed = result.optLong("totalFreed", 0);
                long freedMb = Math.max(1, totalFreed / (1024 * 1024));

                // Also check app cache thresholds
                AppMonitorHelper.checkCacheLimitAlerts(context);
            }).start();
        }
    }

    private void sendLowStorageNotification(Context context, long freedMb) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID_AUTO,
                    "Automated Cleaning Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Notifications about automated cleanups");
            nm.createNotificationChannel(channel);
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(context, CHANNEL_ID_AUTO);
        } else {
            builder = new android.app.Notification.Builder(context);
        }

        builder.setContentTitle("🧹 OneTap Auto-Clean Triggered")
                .setContentText("Storage was running low! Automatically freed " + freedMb + " MB of temporary junk.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setAutoCancel(true);

        nm.notify(2001, builder.build());
    }
}
