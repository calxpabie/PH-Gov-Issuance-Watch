package ph.govwatch.issuance;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Saves what has been seen, the list of new items, and the latest status of each site. */
final class Store {

    private static final int SEEN_CAP = 2000;
    private static final int INBOX_CAP = 200;

    private final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("giw-native", Context.MODE_PRIVATE);
    }

    boolean hasBaseline(String sourceId) {
        return prefs.contains("seen_" + sourceId);
    }

    Set<String> seen(String sourceId) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("seen_" + sourceId, "[]"));
            for (int i = 0; i < a.length(); i++) {
                set.add(a.getString(i));
            }
        } catch (Exception ignored) {
            // start fresh
        }
        return set;
    }

    void saveSeen(String sourceId, Collection<String> urls) {
        List<String> list = new ArrayList<>(urls);
        if (list.size() > SEEN_CAP) {
            list = list.subList(list.size() - SEEN_CAP, list.size());
        }
        prefs.edit().putString("seen_" + sourceId, new JSONArray(list).toString()).apply();
    }

    synchronized JSONArray inbox() {
        try {
            return new JSONArray(prefs.getString("inbox", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    synchronized void addToInbox(String sourceLabel, List<Link> links) {
        if (links.isEmpty()) {
            return;
        }
        JSONArray old = inbox();
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            for (Link l : links) {
                JSONObject o = new JSONObject();
                o.put("src", sourceLabel);
                o.put("title", l.title);
                o.put("url", l.url);
                o.put("found", now);
                out.put(o);
            }
            for (int i = 0; i < old.length() && out.length() < INBOX_CAP; i++) {
                out.put(old.get(i));
            }
        } catch (Exception ignored) {
            // keep what we have
        }
        prefs.edit().putString("inbox", out.toString()).apply();
    }

    synchronized void clearInbox() {
        prefs.edit().putString("inbox", "[]").apply();
    }

    JSONObject result(String sourceId) {
        try {
            String s = prefs.getString("res_" + sourceId, null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    void saveResult(JSONObject r) {
        try {
            prefs.edit().putString("res_" + r.getString("id"), r.toString()).apply();
        } catch (Exception ignored) {
            // nothing to save
        }
    }

    long lastRun() {
        return prefs.getLong("lastRun", 0L);
    }

    void markRunFinished() {
        prefs.edit().putLong("lastRun", System.currentTimeMillis()).apply();
    }

    /** Everything the screen needs, as JSON. */
    JSONObject snapshot(boolean checking, boolean alertsAllowed) {
        JSONObject root = new JSONObject();
        try {
            JSONArray results = new JSONArray();
            for (Sources.Source s : Sources.ALL) {
                JSONObject r = result(s.id);
                if (r == null) {
                    r = new JSONObject();
                    r.put("id", s.id);
                    r.put("label", s.label);
                    r.put("url", s.url);
                    r.put("status", "never");
                }
                results.put(r);
            }
            root.put("results", results);
            root.put("inbox", inbox());
            root.put("lastRun", lastRun());
            root.put("checking", checking);
            root.put("alerts", alertsAllowed);
            root.put("intervalHours", CheckWorker.INTERVAL_HOURS);
        } catch (Exception ignored) {
            // return what we have
        }
        return root;
    }
}
