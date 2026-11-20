package ph.govwatch.issuance;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.util.ArrayDeque;

public class MainActivity extends Activity {

    private static final String HOME = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final String HOME_HOST = "appassets.androidplatform.net";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private Store store;
    private volatile boolean checking;
    private boolean askedForAlerts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        getWindow().setStatusBarColor(Color.parseColor("#123B32"));

        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOME_HOST.equals(u.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                    // no app can open this link
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(this), "Native");
        web.loadUrl(HOME);

        CheckWorker.schedule(this);
        if (!askedForAlerts) {
            askedForAlerts = true;
            askNotificationPermission();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---- called by the page (Bridge) -------------------------------------------------------

    String snapshotJson() {
        return store.snapshot(checking, Notifier.alertsAllowed(this)).toString();
    }

    boolean isChecking() {
        return checking;
    }

    void clearInbox() {
        store.clearInbox();
    }

    void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.alertsAllowed(this)) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                }
            });
        }
    }

    /** Checks every site now: plain download first, then the in-app browser for the ones that failed. */
    void startCheck() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (checking) {
                    return;
                }
                checking = true;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runDownloadPhase();
                    }
                }).start();
            }
        });
    }

    private void runDownloadPhase() {
        final ArrayDeque<Sources.Source> retry = new ArrayDeque<>();
        try {
            synchronized (Checker.LOCK) {
                for (Sources.Source s : Sources.ALL) {
                    JSONObject r = Checker.runHttp(store, s);
                    if (Checker.needsFallback(r)) {
                        retry.add(s);
                    }
                    pushProgress();
                }
            }
        } catch (Exception e) {
            // fall through; sites already processed keep their results
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                runBrowserPhase(retry);
            }
        });
    }

    private void runBrowserPhase(final ArrayDeque<Sources.Source> queue) {
        final Sources.Source s = queue.poll();
        if (s == null) {
            finishCheck();
            return;
        }
        if (isFinishing() || isDestroyed()) {
            checking = false;
            return;
        }
        WebFetcher.fetch(this, s.url, new WebFetcher.Callback() {
            @Override
            public void done(final String html, final String error) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (Checker.LOCK) {
                            if (html != null) {
                                Checker.process(store, s, html, "webview");
                            } else {
                                Checker.fail(store, s, "blocked", error == null ? "Blocked" : error, "webview");
                            }
                        }
                        pushProgress();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                runBrowserPhase(queue);
                            }
                        });
                    }
                }).start();
            }
        });
    }

    private void finishCheck() {
        store.markRunFinished();
        checking = false;
        pushProgress();
        evalJs("window.onNativeDone&&window.onNativeDone()");
    }

    private void pushProgress() {
        String json = snapshotJson();
        evalJs("window.onNativeProgress&&window.onNativeProgress(JSON.parse(" + JSONObject.quote(json) + "))");
    }

    private void evalJs(final String js) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null && !isDestroyed()) {
                    web.evaluateJavascript(js, null);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("Native");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
