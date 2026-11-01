package com.onetap.cleaner;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

public class CleanerJobService extends JobService {
    private static final String TAG = "CleanerJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "CleanerJobService onStartJob started");

        new Thread(() -> {
            try {
                Context context = getApplicationContext();

                // 1. Check if any app has exceeded user's cache threshold limit
                AppMonitorHelper.checkCacheLimitAlerts(context);

                // 2. Check if automated cleaning is enabled
                SharedPreferences prefs = context.getSharedPreferences(AppMonitorHelper.PREFS_NAME, Context.MODE_PRIVATE);
                boolean autoClean = prefs.getBoolean("scheduled_auto_clean", true);

                if (autoClean) {
                    CleanerEngine engine = new CleanerEngine(context);
                    JSONObject storage = engine.getStorageInfo();
                    int percentUsed = storage.optInt("percentUsed", 0);

                    // If storage is high (e.g. > 85%) or routine cycle, run quick clean
                    if (percentUsed >= 85) {
                        engine.quickClean();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in JobService execution", e);
            } finally {
                jobFinished(params, false);
            }
        }).start();

        return true; // Work is running asynchronously
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // Reschedule if interrupted
    }
}
