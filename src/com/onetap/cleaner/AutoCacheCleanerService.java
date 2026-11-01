package com.onetap.cleaner;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public class AutoCacheCleanerService extends AccessibilityService {
    private static final String TAG = "AutoCacheService";
    private static AutoCacheCleanerService sInstance;

    private static final List<String> packageQueue = new ArrayList<>();
    private static int currentQueueIndex = 0;
    private static boolean isRunning = false;
    private static AutoCleanListener sListener;

    private Handler handler;
    private Runnable timeoutRunnable;
    private boolean clickedStorage = false;
    private boolean clickedClearCache = false;

    public interface AutoCleanListener {
        void onProgress(String currentPackage, int current, int total);
        void onFinished(int totalCleaned);
    }

    public static boolean isServiceRunning() {
        return sInstance != null;
    }

    public static boolean isCleaningActive() {
        return isRunning;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        handler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "AutoCacheCleanerService connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        isRunning = false;
        return super.onUnbind(intent);
    }

    public static void startAutoClean(Context context, List<String> packages, AutoCleanListener listener) {
        if (sInstance == null) {
            // Open accessibility settings if not yet enabled
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        }

        packageQueue.clear();
        packageQueue.addAll(packages);
        currentQueueIndex = 0;
        isRunning = true;
        sListener = listener;

        sInstance.processNextApp();
    }

    public static void stopAutoClean() {
        isRunning = false;
        packageQueue.clear();
        if (sInstance != null && sInstance.timeoutRunnable != null) {
            sInstance.handler.removeCallbacks(sInstance.timeoutRunnable);
        }
    }

    private void processNextApp() {
        if (!isRunning) return;

        if (currentQueueIndex >= packageQueue.size()) {
            finishCleaning();
            return;
        }

        String pkg = packageQueue.get(currentQueueIndex);
        clickedStorage = false;
        clickedClearCache = false;

        if (sListener != null) {
            sListener.onProgress(pkg, currentQueueIndex + 1, packageQueue.size());
        }

        // Open App Info settings for this package
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch settings for " + pkg, e);
            advanceToNext();
            return;
        }

        // Set safety timeout (4 seconds max per app)
        if (timeoutRunnable != null) handler.removeCallbacks(timeoutRunnable);
        timeoutRunnable = () -> {
            Log.d(TAG, "Timeout reached for " + pkg + ", advancing");
            advanceToNext();
        };
        handler.postDelayed(timeoutRunnable, 4000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // Step 1: Look for Storage button
            if (!clickedStorage) {
                AccessibilityNodeInfo storageNode = findClickableNodeWithText(root,
                        "Storage", "storage", "Storage & cache", "Speicher", "Almacenamiento", "Stockage", "Memoria");
                if (storageNode != null) {
                    clickedStorage = true;
                    storageNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    storageNode.recycle();
                    return;
                }
            }

            // Step 2: Look for Clear Cache button in Storage screen
            if (clickedStorage && !clickedClearCache) {
                AccessibilityNodeInfo cacheNode = findClickableNodeWithText(root,
                        "Clear cache", "Clear Cache", "Clear cache button", "Borrar caché", "Vider le cache", "Cache leeren", "Limpar cache");
                if (cacheNode != null) {
                    clickedClearCache = true;
                    cacheNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    cacheNode.recycle();

                    // Done with this app! Wait a moment for cache to clear, then go back
                    handler.postDelayed(() -> {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        handler.postDelayed(() -> {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            advanceToNext();
                        }, 250);
                    }, 350);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing accessibility event", e);
        } finally {
            root.recycle();
        }
    }

    private void advanceToNext() {
        if (timeoutRunnable != null) handler.removeCallbacks(timeoutRunnable);
        currentQueueIndex++;
        handler.postDelayed(this::processNextApp, 300);
    }

    private void finishCleaning() {
        isRunning = false;
        int total = packageQueue.size();
        packageQueue.clear();

        if (sListener != null) {
            sListener.onFinished(total);
        }

        // Return user to VibeClean
        Intent returnIntent = new Intent(this, MainActivity.class);
        returnIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(returnIntent);
    }

    private AccessibilityNodeInfo findClickableNodeWithText(AccessibilityNodeInfo root, String... searchTerms) {
        if (root == null) return null;

        for (String term : searchTerms) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(term);
            if (list != null && !list.isEmpty()) {
                for (AccessibilityNodeInfo node : list) {
                    if (node.isClickable() && node.isEnabled()) {
                        return node;
                    }
                    // Walk up parents if parent is clickable container
                    AccessibilityNodeInfo parent = node.getParent();
                    while (parent != null) {
                        if (parent.isClickable() && parent.isEnabled()) {
                            return parent;
                        }
                        parent = parent.getParent();
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void onInterrupt() {
        isRunning = false;
    }
}
