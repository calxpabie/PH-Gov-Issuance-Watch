package ph.govwatch.issuance;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONTokener;

/**
 * Second attempt for sites that refuse a plain download: load the page in a real (hidden)
 * browser, let its scripts run, and read the finished page. Must be called on the main thread.
 */
final class WebFetcher {

    interface Callback {
        /** Exactly one of html / error is non-null. Called on the main thread. */
        void done(String html, String error);
    }

    private static final long SETTLE_MS = 3500;
    private static final long TIMEOUT_MS = 40000;

    private WebFetcher() {
    }

    static void fetch(Activity activity, String url, Callback callback) {
        new Job(activity, url, callback).start();
    }

    private static final class Job {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final String url;
        private final Callback callback;
        private final WebView web;
        private boolean finished;
        private boolean extractScheduled;

        Job(Activity activity, String url, Callback callback) {
            this.url = url;
            this.callback = callback;
            this.web = new WebView(activity);
        }

        void start() {
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String u, Bitmap favicon) {
                    extractScheduled = false;
                }

                @Override
                public void onPageFinished(WebView view, String u) {
                    if (finished || extractScheduled) {
                        return;
                    }
                    extractScheduled = true;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            extract();
                        }
                    }, SETTLE_MS);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) {
                        finish(null, "Could not load the page (" + error.getDescription() + ")");
                    }
                }
            });
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finish(null, "Timed out after 40 seconds");
                }
            }, TIMEOUT_MS);
            web.loadUrl(url);
        }

        void extract() {
            if (finished) {
                return;
            }
            web.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            String html = null;
                            try {
                                if (value != null && !"null".equals(value)) {
                                    Object parsed = new JSONTokener(value).nextValue();
                                    if (parsed instanceof String) {
                                        html = (String) parsed;
                                    }
                                }
                            } catch (Exception ignored) {
                                // handled below
                            }
                            if (html == null || html.isEmpty()) {
                                finish(null, "The page came back empty");
                            } else if (Checker.looksBlocked(html)) {
                                finish(null, "The site still showed a security-check page");
                            } else {
                                finish(html, null);
                            }
                        }
                    });
        }

        void finish(String html, String error) {
            if (finished) {
                return;
            }
            finished = true;
            handler.removeCallbacksAndMessages(null);
            try {
                web.stopLoading();
                web.destroy();
            } catch (Exception ignored) {
                // already gone
            }
            callback.done(html, error);
        }
    }
}
