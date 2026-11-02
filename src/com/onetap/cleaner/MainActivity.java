package com.onetap.cleaner;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 101;
    private WebView webView;
    private CleanerBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Dark status bar and navigation bar styling
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(0xFF0A0F1D);
        window.setNavigationBarColor(0xFF0A0F1D);

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        initWebView();
        requestInitialPermissions();

        // Schedule periodic automation background job
        CleanerAutomationReceiver.schedulePeriodicJob(this);
    }

    public static class CleanChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            if (request != null) {
                request.grant(request.getResources());
            }
        }
    }

    public static class CleanWebViewClient extends WebViewClient {
        private final MainActivity activity;

        public CleanWebViewClient(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url != null && url.startsWith("file:///android_asset/")) {
                return false;
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.clearHistory();
            if (activity != null) {
                activity.handleIntent(activity.getIntent());
            }
        }
    }

    private void initWebView() {
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        bridge = new CleanerBridge(this, webView);
        webView.addJavascriptInterface(bridge, "AndroidCleaner");

        webView.setWebViewClient(new CleanWebViewClient(this));
        webView.setWebChromeClient(new CleanChromeClient());

        webView.clearHistory();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void handleIntent(android.content.Intent intent) {
        if (intent != null && intent.hasExtra("open_tab")) {
            String tab = intent.getStringExtra("open_tab");
            if (webView != null) {
                webView.evaluateJavascript("window.switchTab && window.switchTab('tab-" + tab + "');", null);
            }
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void requestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> perms = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS);
                }
            }

            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), PERMISSION_REQ_CODE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Update all 5 home screen widgets on app resume
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);

            int[] oneTapIds = mgr.getAppWidgetIds(new ComponentName(this, OneTapWidgetProvider.class));
            for (int id : oneTapIds) OneTapWidgetProvider.updateWidget(this, mgr, id, "Ready");

            int[] statsIds = mgr.getAppWidgetIds(new ComponentName(this, SystemStatsWidgetProvider.class));
            for (int id : statsIds) SystemStatsWidgetProvider.updateWidget(this, mgr, id, "Healthy");

            int[] batIds = mgr.getAppWidgetIds(new ComponentName(this, BatteryMonitorWidgetProvider.class));
            for (int id : batIds) BatteryMonitorWidgetProvider.updateWidget(this, mgr, id, "Active 🟢");

            int[] storIds = mgr.getAppWidgetIds(new ComponentName(this, StorageCleanerWidgetProvider.class));
            for (int id : storIds) StorageCleanerWidgetProvider.updateWidget(this, mgr, id, "Ready");

            int[] pillIds = mgr.getAppWidgetIds(new ComponentName(this, QuickCleanWidgetProvider.class));
            for (int id : pillIds) QuickCleanWidgetProvider.updateWidget(this, mgr, id, "1-TAP BOOST");
        } catch (Exception ignored) {}

        if (webView != null) {
            webView.evaluateJavascript("window.onAppResume && window.onAppResume();", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.handleBackPress && window.handleBackPress();", val -> {
                if ("false".equals(val) || "null".equals(val) || val == null) {
                    super.onBackPressed();
                }
            });
        } else {
            super.onBackPressed();
        }
    }
}
