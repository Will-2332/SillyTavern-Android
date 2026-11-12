package com.sillytavern.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.io.IOException;
import java.security.GeneralSecurityException;
// Removed SwipeRefreshLayout import
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.SeekBar;
import android.view.Gravity;
import android.view.ViewGroup;
import androidx.activity.OnBackPressedCallback;

public class MainActivity extends BridgeActivity {
    private static final int FILE_CHOOSER_RESULT_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private ValueCallback<Uri[]> filePathCallback;
    private static final String PREFS_NAME = "SillyTavernPrefs";
    private static final String KEY_AUTH_USER = "auth_user";
    private static final String KEY_AUTH_PASS = "auth_pass";
    private static final String KEY_BACKGROUND_MODE = "background_mode";
    private static final String KEY_TOP_BAR_ENABLED = "top_bar_enabled";

    private static final String KEY_ZOOM_LEVEL = "sillytavern_zoom_level";
    private LinearLayout rootLayout;
    private LinearLayout topBar;
    private SeekBar zoomSlider;
    private int currentZoomProgress = 100; // Default 100%
    private static final String KEY_REPLY_NOTIFICATIONS = "reply_notifications";
    private static final String REPLY_CHANNEL_ID = "ReplyChannel";
    private static final int REPLY_NOTIFICATION_ID = 2;

    private volatile boolean inForeground = false;
    private final Handler fallbackHandler = new Handler(Looper.getMainLooper());
    private volatile long lastReplyNotificationAt = -1;

    // Injected after every page load. Wraps fetch and XMLHttpRequest to count the
    // page's in-flight network requests and tells GenerationBridge when that count
    // leaves or returns to zero, so the CPU is kept awake for exactly as long as
    // SillyTavern is waiting on something: a reply, a summary, embeddings, image
    // or audio generation, or saving the chat. A streamed response counts until
    // its body has been fully read.
    // Also forwards the finished reply to NotificationBridge on GENERATION_ENDED.
    private static final String GENERATION_HOOK_JS =
            "(function(){" +
            "if(window.__stActivityHook)return;window.__stActivityHook=true;" +
            "var n=0,B=function(){return window.GenerationBridge;};" +
            "var inc=function(){if(++n===1&&B())B().setBusy(true);};" +
            "var once=function(){var d=false;return function(){if(d)return;d=true;if(--n===0&&B())B().setBusy(false);};};" +
            "var of=window.fetch;" +
            "if(of)window.fetch=function(){" +
            "inc();var done=once();" +
            "return of.apply(this,arguments).then(function(r){" +
            "try{var rd=r.clone().body.getReader();(function pump(){rd.read().then(function(x){if(x.done)done();else pump();},done);})();}catch(err){done();}" +
            "return r;" +
            "},function(err){done();throw err;});" +
            "};" +
            "var os=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.send=function(){" +
            "inc();var done=once();this.addEventListener(\"loadend\",done);" +
            "try{return os.apply(this,arguments);}catch(err){done();throw err;}" +
            "};" +
            "})();" +
            "(function(){" +
            "if(window.__stReplyHook)return;window.__stReplyHook=true;" +
            "var of2=window.fetch;" +
            "if(of2)window.fetch=function(input){" +
            "var u=\"\";try{u=String(input&&input.url?input.url:input);}catch(err){}" +
            "var p=of2.apply(this,arguments);" +
            "if(!/generat/i.test(u))return p;" +
            "var t0=Date.now();" +
            "return p.then(function(r){" +
            "try{var rd=r.clone().body.getReader();(function pump(){rd.read().then(function(x){if(!x.done){pump();return;}if(Date.now()-t0>5000&&window.NotificationBridge)window.NotificationBridge.onGenerationRequestFinished();},function(){});})();}catch(err){}" +
            "return r;" +
            "});" +
            "};" +
            "var tries=0,t=setInterval(function(){" +
            "if(++tries>120){clearInterval(t);return;}" +
            "var ST=window.SillyTavern;if(!ST||!ST.getContext)return;" +
            "var c=ST.getContext(),e=c.event_types||c.eventTypes;" +
            "if(!c.eventSource||!e)return;" +
            "var ev=e.CHARACTER_MESSAGE_RENDERED||e.GENERATION_ENDED;if(!ev)return;" +
            "clearInterval(t);" +
            "c.eventSource.on(ev,function(id,type){" +
            "try{" +
            "if(type===\"first_message\")return;" +
            "var chat=ST.getContext().chat||[],m=chat[typeof id===\"number\"&&chat[id]?id:chat.length-1];" +
            "if(m&&!m.is_user&&!m.is_system&&window.NotificationBridge)window.NotificationBridge.onReplyFinished(String(m.name||\"SillyTavern\"),String(m.mes||\"\"));" +
            "}catch(err){}" +
            "});" +
            "},1000);" +
            "})();";

    private SharedPreferences getSafeSharedPreferences(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Enable edge-to-edge display (content behind system bars)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Apply window insets as padding to avoid obstruction and handle keyboard
        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            // Apply the insets as padding to the view.
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, Math.max(systemBars.bottom, ime.bottom));

            return WindowInsetsCompat.CONSUMED;
        });

        // Configure system bars
        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(),
                getWindow().getDecorView());
        if (windowInsetsController != null) {
            windowInsetsController.setAppearanceLightStatusBars(false);
            windowInsetsController.setAppearanceLightNavigationBars(false);
        }

        setupUserInterface();
        setupBackNavigation();
        GenerationWakeLock.setListener(holding -> runOnUiThread(() -> setKeepPageRunning(holding)));
    }

    private void setupUserInterface() {
        // Wrap the WebView in a LinearLayout with a Top Bar
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);

                // Root Layout (Vertical)
                rootLayout = new LinearLayout(this);
                rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                rootLayout.setOrientation(LinearLayout.VERTICAL);

                // Top Bar Layout (Horizontal)
                topBar = new LinearLayout(this);
                topBar.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (int) (32 * getResources().getDisplayMetrics().density))); // 32dp height (Slimmer)
                topBar.setOrientation(LinearLayout.HORIZONTAL);
                topBar.setGravity(Gravity.CENTER_VERTICAL);
                topBar.setBackgroundColor(0xFF191B28); // Dark Navy background
                topBar.setPadding(8, 0, 8, 0);

                // Refresh Button
                Button refreshBtn = new Button(this);
                refreshBtn.setText("R"); // Minimalist text icon
                refreshBtn.setTextColor(0xFFFFFFFF); // White text
                refreshBtn.setLayoutParams(new LinearLayout.LayoutParams(
                        (int) (48 * getResources().getDisplayMetrics().density), // Keep touch target width
                        ViewGroup.LayoutParams.MATCH_PARENT));
                refreshBtn.setBackgroundColor(0x00000000); // Transparent background
                refreshBtn.setOnClickListener(v -> webView.reload());
                topBar.addView(refreshBtn);

                // Zoom Slider
                zoomSlider = new SeekBar(this);
                LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1.0f); // Weight 1 to fill space
                sliderParams.setMargins(16, 0, 16, 0);
                zoomSlider.setLayoutParams(sliderParams);
                zoomSlider.setMax(150); // Range 0-150 maps to 50%-200%

                // Restore saved zoom
                SharedPreferences prefs = getSafeSharedPreferences(this);
                currentZoomProgress = prefs.getInt(KEY_ZOOM_LEVEL, 50); // Default middle (100% -> index 50)
                zoomSlider.setProgress(currentZoomProgress);

                zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        currentZoomProgress = progress;
                        applyZoom(webView, progress);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        // Persist zoom on stop
                        SharedPreferences.Editor editor = getSafeSharedPreferences(MainActivity.this).edit();
                        editor.putInt(KEY_ZOOM_LEVEL, currentZoomProgress);
                        editor.apply();
                    }
                });
                topBar.addView(zoomSlider);

                // Set initial visibility
                SharedPreferences uiPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                boolean topBarEnabled = uiPrefs.getBoolean(KEY_TOP_BAR_ENABLED, true);
                topBar.setVisibility(topBarEnabled ? android.view.View.VISIBLE : android.view.View.GONE);

                // Add Top Bar to Root
                rootLayout.addView(topBar);

                // Add WebView to Root (Fill remaining space)
                webView.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1.0f));
                rootLayout.addView(webView);

                // Add Root to Parent
                parent.addView(rootLayout);
            }
        }
    }

    // Helper to apply zoom via Viewport Meta Tag
    private void applyZoom(WebView webView, int progress) {
        // Map 0-150 to 0.5-2.0
        float userScale = 0.5f + (progress / 100.0f);
        String js = "var meta = document.querySelector('meta[name=\"viewport\"]');" +
                "if (!meta) {" +
                "    meta = document.createElement('meta');" +
                "    meta.name = 'viewport';" +
                "    document.head.appendChild(meta);" +
                "}" +
                "meta.content = 'width=device-width, initial-scale=" + userScale + ", maximum-scale=" + userScale
                + ", minimum-scale=" + userScale + ", user-scalable=yes';";
        webView.evaluateJavascript(js, null);
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getBridge().getWebView();
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    // Check if we are at root/main menu AND on localhost
                    boolean atLocalRoot = false;
                    String currentUrl = webView != null ? webView.getUrl() : null;
                    if (currentUrl != null) {
                        Uri uri = Uri.parse(currentUrl);
                        String host = uri.getHost();
                        String path = uri.getPath();

                        boolean isLocalhost = host != null
                                && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"));
                        boolean isRootPath = path == null || path.isEmpty() || path.equals("/")
                                || path.equals("/index.html");

                        atLocalRoot = isLocalhost && isRootPath;
                    }

                    if (!atLocalRoot) {
                        // If not at local root (e.g. remote root), go to Launcher
                        if (webView != null) {
                            webView.loadUrl("http://localhost/index.html");
                        }
                    } else {
                        // Standard system back behavior (minimize/exit app)
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        inForeground = true;
        NotificationManagerCompat.from(this).cancel(REPLY_NOTIFICATION_ID);

        // Get the WebView instance from Capacitor's Bridge
        WebView webView = this.getBridge().getWebView();

        // Add Javascript Interface for Auth, Background
        webView.addJavascriptInterface(new AuthBridge(this), "AuthBridge");
        webView.addJavascriptInterface(new BackgroundBridge(this), "BackgroundBridge");
        webView.addJavascriptInterface(new UIBridge(this), "UIBridge");
        webView.addJavascriptInterface(new GenerationBridge(this), "GenerationBridge");
        webView.addJavascriptInterface(new NotificationBridge(this), "NotificationBridge");

        // Sync background service state. The keep-alive is only needed once the app
        // leaves the foreground, so it's started from onPause, not here.
        syncBackgroundService(false);

        // Enable Zoom Support
        WebSettings webSettings = webView.getSettings();
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setJavaScriptEnabled(true);

        // Re-apply zoom on resume/page reload
        applyZoom(webView, currentZoomProgress);

        // Set custom WebViewClient to handle Basic Auth and Zoom Fix
        webView.setWebViewClient(new BridgeWebViewClient(this.getBridge()) {
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                SharedPreferences prefs = getSafeSharedPreferences(MainActivity.this);
                String user = prefs.getString(KEY_AUTH_USER, null);
                String pass = prefs.getString(KEY_AUTH_PASS, null);

                if (user != null && !user.isEmpty() && pass != null) {
                    handler.proceed(user, pass);
                } else {
                    super.onReceivedHttpAuthRequest(view, handler, host, realm);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Re-apply zoom setting when page loads
                applyZoom(view, currentZoomProgress);

                // Inject JS for Zoom Fix and Scroll Handling
                // Inject CSS locally to force touch-action but rely on applyZoom for Viewport
                String jsInjection =
                        // CSS Force Zoom: Ensure touch-action allows scaling
                        "var style = document.createElement('style');" +
                                "style.innerHTML = 'html, body { touch-action: pan-x pan-y pinch-zoom !important; }';" +
                                "document.head.appendChild(style);";

                view.evaluateJavascript(jsInjection, null);

                view.evaluateJavascript(GENERATION_HOOK_JS, null);
            }
        });

        // Set a custom WebChromeClient to handle permission requests and file selection
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Automatically grant all permissions (Camera, Microphone, etc.)
                request.grant(request.getResources());
            }

            // For Android 5.0+
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                    WebChromeClient.FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    // --- Keeping the page running in the background ---
    //
    // About a minute after a WebView is hidden, Chromium freezes the page: timers
    // stop and a streaming response is no longer read, so a reply that is still
    // generating stalls until the app is reopened, wake lock or not. While work is
    // in flight we therefore tell the WebView its window is still visible, and
    // hand the real state back as soon as the work (plus grace period) is over,
    // so an idle page is still allowed to freeze and costs no battery.
    private final android.os.Handler pageHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean keepPageRunning = false;
    private boolean activityStopped = false;
    private final Runnable assertPageVisible = new Runnable() {
        @Override
        public void run() {
            if (!keepPageRunning || !activityStopped) return;
            WebView webView = getBridge() != null ? getBridge().getWebView() : null;
            if (webView != null) {
                webView.dispatchWindowVisibilityChanged(android.view.View.VISIBLE);
            }
            // Re-assert periodically in case the system reports the real state again.
            pageHandler.postDelayed(this, 15 * 1000L);
        }
    };

    private void setKeepPageRunning(boolean keep) {
        keepPageRunning = keep;
        pageHandler.removeCallbacks(assertPageVisible);
        if (!activityStopped) return;
        if (keep) {
            assertPageVisible.run();
        } else {
            WebView webView = getBridge() != null ? getBridge().getWebView() : null;
            if (webView != null) {
                webView.dispatchWindowVisibilityChanged(android.view.View.GONE);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        activityStopped = false;
        pageHandler.removeCallbacks(assertPageVisible);
    }

    @Override
    public void onStop() {
        super.onStop();
        activityStopped = true;
        // The system hides the window right around onStop; override that just after.
        if (keepPageRunning) {
            pageHandler.postDelayed(assertPageVisible, 500);
        }
    }

    @Override
    public void onDestroy() {
        GenerationWakeLock.setListener(null);
        pageHandler.removeCallbacks(assertPageVisible);
        super.onDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();
        inForeground = false;
        syncBackgroundService(true);
    }

    private boolean isBackgroundModeEnabled() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BACKGROUND_MODE, false);
    }

    private void syncBackgroundService(boolean startIfEnabled) {
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (isBackgroundModeEnabled()) {
            if (!startIfEnabled) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } else {
            stopService(serviceIntent);
            GenerationWakeLock.releaseNow();
        }
    }

    // Android 13+ hides the foreground-service notification (and its Stop action)
    // unless POST_NOTIFICATIONS is granted, so ask for it when background mode is on.
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        // Called from the JavaScript bridge thread.
        runOnUiThread(() -> ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST_CODE));
    }

    public class GenerationBridge {
        Context mContext;

        GenerationBridge(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setBusy(boolean busy) {
            if (busy) {
                if (isBackgroundModeEnabled()) {
                    GenerationWakeLock.acquire(mContext);
                }
                return;
            }
            GenerationWakeLock.release();
        }
    }

    public class BackgroundBridge {
        Context mContext;

        BackgroundBridge(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setBackgroundMode(boolean enabled) {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean wasEnabled = prefs.getBoolean(KEY_BACKGROUND_MODE, false);
            prefs.edit().putBoolean(KEY_BACKGROUND_MODE, enabled).apply();
            // Only prompt when the user turns the toggle on, not on every launch.
            if (enabled && !wasEnabled) {
                requestNotificationPermissionIfNeeded();
            }
            syncBackgroundService(false);
        }

        @JavascriptInterface
        public boolean isIgnoringBatteryOptimizations() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                return pm.isIgnoringBatteryOptimizations(mContext.getPackageName());
            }
            return true;
        }

        @JavascriptInterface
        public void requestIgnoreBatteryOptimizations() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + mContext.getPackageName()));
                mContext.startActivity(intent);
            }
        }
    }

    public class NotificationBridge {
        Context mContext;

        NotificationBridge(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setReplyNotifications(boolean enabled) {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean wasEnabled = prefs.getBoolean(KEY_REPLY_NOTIFICATIONS, false);
            prefs.edit().putBoolean(KEY_REPLY_NOTIFICATIONS, enabled).apply();
            if (enabled && !wasEnabled) {
                requestNotificationPermissionIfNeeded();
            }
        }

        @JavascriptInterface
        public void onReplyFinished(String name, String message) {
            String text = message.replaceAll("\\s+", " ").trim();
            if (text.length() > 300) text = text.substring(0, 300) + "…";
            if (postFinishedNotification(name + " replied", text, false)) {
                lastReplyNotificationAt = SystemClock.elapsedRealtime();
            }
        }

        // Called when a long request to one of SillyTavern's generation endpoints
        // (chat, image, voice) has completed. Posts a generic notification unless
        // the reply event above covers it, which arrives within a second or so.
        @JavascriptInterface
        public void onGenerationRequestFinished() {
            final long finishedAt = SystemClock.elapsedRealtime();
            fallbackHandler.postDelayed(() -> {
                if (lastReplyNotificationAt >= finishedAt - 5000) return;
                postFinishedNotification("SillyTavern", "Generation finished", false);
            }, 3000);
        }

        // Posts a notification straight away, even in the foreground, so the user
        // can check that notifications from this app are allowed and visible.
        @JavascriptInterface
        public void sendTestNotification() {
            requestNotificationPermissionIfNeeded();
            postFinishedNotification("SillyTavern", "Test notification. Reply notifications will look like this.", true);
        }
    }

    /**
     * Posts the "finished" notification if the setting is on and the app is in
     * the background (or always, when forced). Returns whether it was posted.
     */
    private boolean postFinishedNotification(String title, String text, boolean force) {
        if (!force) {
            // Only useful when the user isn't already looking at the chat.
            if (inForeground) return false;
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_REPLY_NOTIFICATIONS, false)) return false;
        }
        NotificationManagerCompat manager = NotificationManagerCompat.from(this);
        if (!manager.areNotificationsEnabled()) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    REPLY_CHANNEL_ID, "Finished replies", NotificationManager.IMPORTANCE_DEFAULT));
        }

        Intent openIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, REPLY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        try {
            manager.notify(REPLY_NOTIFICATION_ID, notification);
            return true;
        } catch (SecurityException e) {
            // Permission revoked between the check and the post.
            return false;
        }
    }

    public class AuthBridge {
        Context mContext;

        AuthBridge(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setCredentials(String user, String pass) {
            SharedPreferences prefs = getSafeSharedPreferences(mContext);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_AUTH_USER, user);
            editor.putString(KEY_AUTH_PASS, pass);
            editor.apply();
        }

        @JavascriptInterface
        public void clearCredentials() {
            SharedPreferences prefs = getSafeSharedPreferences(mContext);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(KEY_AUTH_USER);
            editor.remove(KEY_AUTH_PASS);
            editor.apply();
        }
    }

    public class UIBridge {
        Context mContext;

        UIBridge(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setTopBarEnabled(boolean enabled) {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_TOP_BAR_ENABLED, enabled).apply();

            // Update UI on Main Thread
            runOnUiThread(() -> {
                if (topBar != null) {
                    topBar.setVisibility(enabled ? android.view.View.VISIBLE : android.view.View.GONE);
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null)
                return;
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }
}
