package ph.govwatch.issuance;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks one site: reads its links, compares with what was seen before, records the result. */
final class Checker {

    /** Held while a full check runs so the background job and a manual check never overlap. */
    static final Object LOCK = new Object();

    private static final int MAX_NEW_PER_SITE = 25;
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLOCKED_TITLE = Pattern.compile(
            "access denied|forbidden|just a moment|attention required|are you a robot"
                    + "|request blocked|error 1020|security check|\\b403\\b",
            Pattern.CASE_INSENSITIVE);

    private Checker() {
    }

    /** True when a "successful" page is really a bot-check or error page. */
    static boolean looksBlocked(String html) {
        Matcher m = TITLE.matcher(html);
        if (m.find()) {
            String title = LinkExtractor.clean(m.group(1));
            return html.length() < 60000 && BLOCKED_TITLE.matcher(title).find();
        }
        return html.trim().length() < 400;
    }

    /** Plain download from the phone, then process it. */
    static JSONObject runHttp(Store store, Sources.Source s) {
        HttpFetcher.Result r = HttpFetcher.fetch(s.url);
        if (r.error != null) {
            return fail(store, s, "error", r.error, "http");
        }
        if (r.code == 401 || r.code == 403 || r.code == 429 || r.code == 503) {
            return fail(store, s, "blocked", "HTTP " + r.code, "http");
        }
        if (r.code < 200 || r.code >= 300 || r.body == null) {
            return fail(store, s, "error", "HTTP " + r.code, "http");
        }
        if (looksBlocked(r.body)) {
            return fail(store, s, "blocked", "The site showed a security-check page", "http");
        }
        return process(store, s, r.body, "http");
    }

    /** True when a result is worth retrying through the in-app browser. */
    static boolean needsFallback(JSONObject r) {
        String st = r.optString("status");
        return "blocked".equals(st) || "error".equals(st);
    }

    static JSONObject process(Store store, Sources.Source s, String html, String method) {
        List<Link> links = LinkExtractor.issuanceLike(LinkExtractor.extract(html, s.url));
        if (links.isEmpty()) {
            return fail(store, s, "error", "No readable links found on the page", method);
        }
        boolean baseline = !store.hasBaseline(s.id);
        Set<String> seen = store.seen(s.id);
        List<Link> fresh = baseline ? new ArrayList<Link>() : Diff.fresh(links, seen);

        LinkedHashSet<String> merged = new LinkedHashSet<>(seen);
        for (Link l : links) {
            merged.add(l.url);
        }
        store.saveSeen(s.id, merged);
        if (!fresh.isEmpty()) {
            store.addToInbox(s.label, fresh.subList(0, Math.min(MAX_NEW_PER_SITE, fresh.size())));
        }

        JSONObject o = base(s, baseline ? "baseline" : "ok", method, null);
        try {
            o.put("total", links.size());
            o.put("newCount", fresh.size());
        } catch (Exception ignored) {
            // fields are optional on screen
        }
        store.saveResult(o);
        return o;
    }

    /**
     * Records a failure. If the site was read successfully before, that earlier result is kept
     * (with a note) so one blocked attempt does not hide good information.
     */
    static JSONObject fail(Store store, Sources.Source s, String status, String detail, String method) {
        JSONObject failure = base(s, status, method, detail);
        JSONObject previous = store.result(s.id);
        try {
            String prevStatus = previous == null ? "" : previous.optString("status");
            if ("ok".equals(prevStatus) || "baseline".equals(prevStatus)) {
                previous.put("note", "Latest attempt failed (" + detail + "); showing the last successful check.");
                store.saveResult(previous);
            } else {
                store.saveResult(failure);
            }
        } catch (Exception ignored) {
            store.saveResult(failure);
        }
        return failure;
    }

    private static JSONObject base(Sources.Source s, String status, String method, String detail) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.id);
            o.put("label", s.label);
            o.put("url", s.url);
            o.put("status", status);
            o.put("method", method);
            o.put("checkedAt", System.currentTimeMillis());
            if (detail != null) {
                o.put("detail", detail);
            }
        } catch (Exception ignored) {
            // cannot happen with plain values
        }
        return o;
    }
}
