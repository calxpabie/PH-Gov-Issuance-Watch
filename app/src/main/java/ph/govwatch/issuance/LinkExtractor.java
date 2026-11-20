package ph.govwatch.issuance;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the links out of an HTML page and keeps the ones that look like
 * government issuances. Pure Java (no Android classes) so it can be tested anywhere.
 */
public final class LinkExtractor {

    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\s[^>]*?href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Pattern ISSUANCE_WORDS = Pattern.compile(
            "circular|memorandum|proclamation|executive[ -]order|administrative[ -]order"
                    + "|resolution|issuance|advisory|guideline|bulletin|order[ -]no"
                    + "|\\bno\\.\\s*\\d|\\bmc\\b|\\brmc\\b|\\bjmc\\b|\\bao\\b|\\beo\\b"
                    + "|\\.pdf|\\.docx?(\\?|$)",
            Pattern.CASE_INSENSITIVE);

    private LinkExtractor() {
    }

    /** All usable http(s) links on the page, resolved against baseUrl, one per URL. */
    public static List<Link> extract(String html, String baseUrl) {
        List<Link> result = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return result;
        }
        URI base;
        try {
            base = new URI(baseUrl);
        } catch (Exception e) {
            return result;
        }
        Map<String, Link> byUrl = new LinkedHashMap<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String href = unescape(m.group(2)).trim();
            if (href.isEmpty() || href.startsWith("#")) {
                continue;
            }
            String lower = href.toLowerCase(Locale.ROOT);
            if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
                continue;
            }
            String abs;
            try {
                abs = base.resolve(href.replace(" ", "%20")).toString();
            } catch (Exception e) {
                continue;
            }
            int hash = abs.indexOf('#');
            if (hash >= 0) {
                abs = abs.substring(0, hash);
            }
            if (!abs.startsWith("http://") && !abs.startsWith("https://")) {
                continue;
            }
            String text = clean(m.group(3));
            if (text.isEmpty()) {
                text = fileName(abs);
            }
            if (text.isEmpty()) {
                continue;
            }
            Link existing = byUrl.get(abs);
            if (existing == null || text.length() > existing.title.length()) {
                byUrl.put(abs, new Link(text, abs));
            }
        }
        result.addAll(byUrl.values());
        return result;
    }

    /**
     * Keeps links that look like issuances. If the page has none that match
     * (unusual layout), falls back to every reasonably long link so changes are still noticed.
     */
    public static List<Link> issuanceLike(List<Link> all) {
        List<Link> keep = new ArrayList<>();
        for (Link l : all) {
            int n = l.title.length();
            if (n >= 6 && n <= 300
                    && (ISSUANCE_WORDS.matcher(l.title).find() || ISSUANCE_WORDS.matcher(l.url).find())) {
                keep.add(l);
            }
        }
        if (keep.isEmpty()) {
            for (Link l : all) {
                int n = l.title.length();
                if (n >= 15 && n <= 300) {
                    keep.add(l);
                }
            }
        }
        return keep;
    }

    /** Strips tags, decodes entities, collapses whitespace. */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = TAGS.matcher(s).replaceAll(" ");
        t = unescape(t);
        return SPACES.matcher(t).replaceAll(" ").trim();
    }

    static String fileName(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.contains("://")) {
            return "";
        }
        return name.replace("%20", " ");
    }

    static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String repl = " ";
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                repl = new String(Character.toChars(cp));
            } catch (Exception ignored) {
                // keep a space
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString()
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
