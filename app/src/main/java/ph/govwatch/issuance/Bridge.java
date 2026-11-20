package ph.govwatch.issuance;

import android.webkit.JavascriptInterface;

/** The few functions the app's screen (web page) can call. Exposed to the page as "Native". */
final class Bridge {

    private final MainActivity activity;

    Bridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String getResults() {
        return activity.snapshotJson();
    }

    @JavascriptInterface
    public boolean isChecking() {
        return activity.isChecking();
    }

    @JavascriptInterface
    public void checkNow() {
        activity.startCheck();
    }

    @JavascriptInterface
    public void clearInbox() {
        activity.clearInbox();
    }

    @JavascriptInterface
    public void enableAlerts() {
        activity.askNotificationPermission();
    }
}
